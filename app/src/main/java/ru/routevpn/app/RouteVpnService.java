package ru.routevpn.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Network;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import org.amnezia.awg.backend.Backend;
import org.amnezia.awg.backend.GoBackend;
import org.amnezia.awg.backend.Statistics;
import org.amnezia.awg.backend.Tunnel;
import org.amnezia.awg.config.Config;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Serialized connection owner using the official backend and network-change DOWN/UP flow. */
public class RouteVpnService extends Service {
    public static final String ACTION_CONNECT = "ru.routevpn.app.CONNECT";
    public static final String ACTION_DISCONNECT = "ru.routevpn.app.DISCONNECT";
    public static final String ACTION_RESTORE = "ru.routevpn.app.RESTORE";
    public static final String EXTRA_PROFILE_ID = "profile_id";
    private static final String CHANNEL = "vpn_connection";
    private static final int NOTIFICATION_ID = 7;
    public static final class State {
        public final String phase, id, message;
        public final long rx, tx, handshake;
        State(String phase, String id, String message, long rx, long tx, long handshake) {
            this.phase = phase; this.id = id; this.message = message;
            this.rx = rx; this.tx = tx; this.handshake = handshake;
        }
    }
    private static volatile State latest = new State("DISCONNECTED", "", "VPN выключен", 0, 0, 0);
    private static final Object STATE_LOCK = new Object();
    private static volatile RouteVpnService currentOwner;
    public static State snapshot() { return latest; }
    public static boolean isActive(State state) {
        return "CONNECTED".equals(state.phase) || "CONNECTING".equals(state.phase)
                || "DISCONNECTING".equals(state.phase);
    }
    public static void restore(Context context, boolean alwaysOn) {
        ConnectionPreferences preferences = new ConnectionPreferences(context);
        if (!alwaysOn && !preferences.shouldRestore()) return;
        String id = preferences.profileId();
        if (id.isEmpty() && alwaysOn) id = new ProfileStore(context).getSelectedId();
        if (id == null || id.isEmpty()) return;
        try {
            context.startForegroundService(new Intent(context, RouteVpnService.class)
                    .setAction(ACTION_RESTORE).putExtra(EXTRA_PROFILE_ID, id));
        } catch (RuntimeException restricted) {
            // Android may defer background starts; Always-on or the next app launch can restore it.
        }
    }
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService probeWorker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TunnelWatchdog watchdog = new TunnelWatchdog();
    private final AtomicLong transportGeneration = new AtomicLong();
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private volatile DnsHealthProbe activeProbe;
    private volatile Future<?> probeFuture;
    private volatile String lastProbeResult = "NOT_RUN";
    private List<InetAddress> healthDns = Collections.emptyList();
    private boolean wakeReceiverRegistered;
    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                cancelHealthProbe();
                submitWorker(() -> watchdog.paused());
            } else if (screenInteractive()) {
                recordEvent("device_awake");
                requestHealthCheck(false);
            }
        }
    };
    private Backend backend;
    private ConnectionPreferences preferences;
    private UnderlyingNetworkMonitor networkMonitor;
    private ScheduledFuture<?> reconnect;
    private volatile boolean desired, destroying, stopping, rebuilding;
    private volatile Network observedNetwork;
    private volatile boolean networkMissing = true;
    private volatile String terminalError;
    private String profileName = "AWG VPN", activeId = "", notificationText = "";
    private volatile long startedAt;
    private volatile String routingMode = "UNKNOWN";
    private volatile int selectedPackageCount, peerCount, dnsCount, runtimeMtu;
    private volatile boolean keepaliveConfigured;
    private volatile boolean everStartedTunnel;
    private int failures;
    private final CompletableFuture<Void> cleanupComplete = new CompletableFuture<>();
    private RouteVpnService previousOwner;
    private final Tunnel tunnel = new Tunnel() {
        @Override public String getName() { return "routevpn"; }
        @Override public void onStateChange(Tunnel.State state) {
            // Deliberate DOWN during a rebuild is not Android revoking VPN permission.
            if (state == Tunnel.State.DOWN && !rebuilding && !stopping && !destroying) {
                desired = false; stopping = true; preferences.clearRequest();
                publish("DISCONNECTING", "VPN отключён системой", 0, 0, 0);
                main.post(RouteVpnService.this::stopSelf);
            }
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        synchronized (STATE_LOCK) { previousOwner = currentOwner; currentOwner = this; }
        preferences = new ConnectionPreferences(this);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "VPN-подключение", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Статус подключения и кнопка отключения VPN"); channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        networkMonitor = new UnderlyingNetworkMonitor(this, this::onUnderlyingNetworkChanged);
        try { networkMonitor.start(); } catch (RuntimeException ignored) { }
        IntentFilter wakes = new IntentFilter(Intent.ACTION_SCREEN_ON);
        wakes.addAction(Intent.ACTION_USER_PRESENT);
        wakes.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(wakeReceiver, wakes, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(wakeReceiver, wakes);
        wakeReceiverRegistered = true;
        worker.scheduleWithFixedDelay(this::refreshStatistics, 2, 2, TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESTORE : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            desired = false; stopping = true; preferences.clearRequest();
            cancelHealthProbe(); recordEvent("manual_disconnect");
            publish("DISCONNECTING", "Отключение…", latest.rx, latest.tx, latest.handshake);
            worker.execute(() -> { cancelReconnect(); tearDown(); main.post(this::stopSelf); });
            return START_NOT_STICKY;
        }
        if (!ACTION_CONNECT.equals(action) && !ACTION_RESTORE.equals(action)) {
            if (!desired) stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null && !preferences.shouldRestore()) { stopSelf(); return START_NOT_STICKY; }
        if (desired || stopping || destroying) return preferences.autoReconnect() ? START_STICKY : START_NOT_STICKY;
        String id = intent == null ? preferences.profileId() : intent.getStringExtra(EXTRA_PROFILE_ID);
        if (id == null || id.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        activeId = id; desired = true; stopping = false;
        startForeground(NOTIFICATION_ID, notification("Подключение…"));
        try { preferences.request(id); }
        catch (RuntimeException failed) { failPermanently("Не удалось сохранить состояние подключения."); return START_NOT_STICKY; }
        publish("CONNECTING", "Подключение…", 0, 0, 0);
        worker.execute(() -> connect(false));
        return preferences.autoReconnect() ? START_STICKY : START_NOT_STICKY;
    }
    /** Test seams avoid pretending unit tests exercise the native VPN engine or device Keystore. */
    protected Backend createBackend() { return new GoBackend(getApplicationContext()); }
    protected boolean permissionGranted() { return android.net.VpnService.prepare(this) == null; }
    protected Profile loadProfile(String id) {
        for (Profile profile : new ProfileStore(this).load()) if (id.equals(profile.id)) return profile;
        return null;
    }
    protected Set<String> installedPackages() {
        Set<String> installed = new HashSet<>();
        for (ApplicationInfo info : getPackageManager().getInstalledApplications(0)) installed.add(info.packageName);
        return installed;
    }
    protected long elapsedTime() { return SystemClock.elapsedRealtime(); }
    protected boolean screenInteractive() {
        PowerManager power = getSystemService(PowerManager.class);
        return power != null && power.isInteractive() && !power.isDeviceIdleMode();
    }
    protected Network ownVpnNetwork() {
        // API 30 exposes VPN ownership without location permission. Older devices retain passive restoration.
        if (Build.VERSION.SDK_INT < 30) return null;
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return null;
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && caps.getOwnerUid() == Process.myUid()) return network;
        }
        return null;
    }
    protected TunnelWatchdog.Result performHealthProbe(DnsHealthProbe probe) { return probe.run(); }
    private void connect(boolean replace) {
        if (!desired || stopping || destroying) return;
        if (previousOwner != null) {
            try { previousOwner.cleanupComplete.get(15, TimeUnit.SECONDS); previousOwner = null; }
            catch (Exception cleanupPending) { retry("Ожидаем завершения предыдущего подключения…"); return; }
            if (!desired || stopping || destroying) return;
        }
        final Profile profile;
        final Config config;
        try {
            profile = loadProfile(activeId);
            if (profile == null) { failPermanently("Конфиг не найден. Выберите сохранённый профиль."); return; }
            config = ConfigPolicy.buildForConnection(profile, installedPackages(), getPackageName());
        } catch (IllegalArgumentException validation) {
            failPermanently(validation.getMessage()); return;
        } catch (RuntimeException inaccessible) {
            retry("Профили временно недоступны. Разблокируйте телефон."); return;
        }
        if (!permissionGranted()) { failPermanently("Разрешите VPN-подключение в приложении."); return; }
        try {
            cancelHealthProbe();
            routingMode = profile.mode;
            selectedPackageCount = profile.packages == null ? 0 : profile.packages.size();
            peerCount = config.getPeers().size(); dnsCount = config.getInterface().getDnsServers().size();
            runtimeMtu = config.getInterface().getMtu().orElse(1280);
            keepaliveConfigured = config.getPeers().stream().anyMatch(peer -> peer.getPersistentKeepalive().isPresent());
            healthDns = DnsHealthProbe.routedDns(config);
            profileName = profile.name;
            if (backend == null) backend = createBackend();
            if (replace && backend.getState(tunnel) == Tunnel.State.UP) {
                rebuilding = true;
                try { backend.setState(tunnel, Tunnel.State.DOWN, null); }
                finally { rebuilding = false; }
                // Upstream also waits for Android to destroy the old backend VpnService before UP.
                Thread.sleep(500);
            }
            if (!desired || stopping || destroying) return;
            backend.setState(tunnel, Tunnel.State.UP, config);
            startedAt = elapsedTime(); failures = 0;
            everStartedTunnel = true;
            watchdog.started(startedAt); lastProbeResult = "NOT_RUN";
            recordEvent("tunnel_up");
            if (!desired || stopping || destroying) { tearDown(); return; }
            publish("CONNECTING", "Туннель создан. Ожидаем ответ сервера…", 0, 0, 0);
        } catch (Exception | LinkageError failure) {
            if (!desired || stopping || destroying) return;
            if (!permissionGranted()) failPermanently("Разрешение VPN отозвано. Подключитесь из приложения.");
            else retry("Не удалось связаться с VPN. Повторяем подключение…");
        }
    }
    /** Debounce avoids restarting twice while a network is being validated. */
    protected void onUnderlyingNetworkChanged(Network network) {
        boolean initialNetwork = observedNetwork == null && !everStartedTunnel;
        observedNetwork = network;
        networkMissing = network == null;
        cancelHealthProbe(); recordEvent(network == null ? "physical_network_lost" : "physical_network_changed");
        if (!desired || stopping || destroying || !preferences.autoReconnect()) return;
        worker.execute(() -> {
            if (!desired || stopping || destroying) return;
            if (network != null && initialNetwork) return;
            cancelReconnect();
            if (network == null) {
                publish("CONNECTING", "Сеть недоступна. Ждём подключения…", latest.rx, latest.tx, latest.handshake);
            } else {
                publish("CONNECTING", "Сеть изменилась. Восстанавливаем VPN…", latest.rx, latest.tx, 0);
                reconnect = worker.schedule(() -> { if (preferences.autoReconnect()) connect(true); }, 750, TimeUnit.MILLISECONDS);
            }
        });
    }
    static long retryDelaySeconds(int attempt) { return Math.min(60, 2L << Math.min(5, Math.max(0, attempt))); }
    private void retry(String message) {
        if (!desired || stopping || destroying) return;
        if (!preferences.autoReconnect()) { failPermanently(message); return; }
        long delay = retryDelaySeconds(failures++);
        publish("CONNECTING", message, latest.rx, latest.tx, 0);
        cancelReconnect();
        reconnect = worker.schedule(() -> { if (preferences.autoReconnect()) connect(true); }, delay, TimeUnit.SECONDS);
    }
    private void cancelReconnect() { if (reconnect != null) reconnect.cancel(false); reconnect = null; }
    private void failPermanently(String message) {
        desired = false; stopping = true; terminalError = message; preferences.clearRequest();
        publish("DISCONNECTING", "Завершение подключения…", 0, 0, 0);
        main.post(this::stopSelf);
    }
    private void refreshStatistics() {
        if (!desired || backend == null || destroying || stopping) return;
        if (networkMissing && preferences.autoReconnect()) return;
        try {
            if (backend.getState(tunnel) != Tunnel.State.UP) return;
            Statistics statistics = backend.getStatistics(tunnel);
            long handshake = 0;
            for (org.amnezia.awg.crypto.Key peer : statistics.peers()) {
                Statistics.PeerStats value = statistics.peer(peer);
                if (value != null) handshake = Math.max(handshake, value.latestHandshakeEpochMillis() / 1000);
            }
            watchdog.observe(elapsedTime(), statistics.totalRx(), handshake);
            String message;
            if (handshake > 0) {
                long age = Math.max(0, System.currentTimeMillis() / 1000 - handshake);
                message = age < 180 ? "VPN подключён" : "Туннель активен · ответ сервера " + age / 60 + " мин назад";
            } else if (elapsedTime() - startedAt > 25000) message = "Нет ответа сервера. Проверяем связь…";
            else message = "Ожидаем ответ сервера…";
            if (watchdog.failedProbes() > 0) message = "Проверяем связь внутри VPN…";
            // A stale idle handshake never restarts the tunnel; only repeated bound-probe failures do.
            publish(handshake > 0 && watchdog.failedProbes() == 0 ? "CONNECTED" : "CONNECTING", message,
                    statistics.totalRx(), statistics.totalTx(), Math.max(0, handshake));
            startHealthProbe(false);
        } catch (Exception ignored) { }
    }
    protected void requestHealthCheck(boolean diagnostic) {
        submitWorker(() -> { refreshStatistics(); startHealthProbe(diagnostic); });
    }
    private void startHealthProbe(boolean diagnostic) {
        final Network physical = observedNetwork;
        final long generation = transportGeneration.get();
        if (!desired || stopping || destroying || networkMissing || physical == null
                || backend == null || activeProbe != null) return;
        if (!diagnostic && (!preferences.autoReconnect() || !watchdog.shouldProbe(elapsedTime(), screenInteractive()))) return;
        final Network vpn;
        try {
            if (backend.getState(tunnel) != Tunnel.State.UP) return;
            vpn = ownVpnNetwork();
        } catch (Exception unavailable) { return; }
        long sequence = watchdog.beginProbe(elapsedTime());
        if (vpn == null || healthDns.isEmpty()) {
            lastProbeResult = vpn == null ? "UNAVAILABLE_NO_OWN_VPN" : "UNAVAILABLE_NO_ROUTED_DNS";
            watchdog.complete(TunnelWatchdog.Result.UNAVAILABLE, elapsedTime(), sequence); return;
        }
        DnsHealthProbe probe = new DnsHealthProbe(healthDns, vpn::bindSocket);
        activeProbe = probe; recordEvent("bound_dns_probe_started");
        probeFuture = probeWorker.submit(() -> {
            TunnelWatchdog.Result outcome;
            try { outcome = performHealthProbe(probe); }
            catch (RuntimeException unavailable) { outcome = TunnelWatchdog.Result.UNAVAILABLE; }
            final TunnelWatchdog.Result result = outcome;
            submitWorker(() -> {
                if (activeProbe != probe) return;
                if (!desired || stopping || destroying || generation != transportGeneration.get()
                        || networkMissing || !physical.equals(observedNetwork)) {
                    activeProbe = null; probeFuture = null; return;
                }
                refreshStatistics();
                activeProbe = null; probeFuture = null;
                lastProbeResult = result.name(); recordEvent("bound_dns_probe_" + result.name());
                boolean recover = watchdog.complete(result, elapsedTime(), sequence);
                if (recover && preferences.autoReconnect() && (diagnostic || screenInteractive())) {
                    watchdog.restarting(elapsedTime()); recordEvent("watchdog_reconnect");
                    publish("CONNECTING", "VPN не отвечает. Восстанавливаем соединение…", latest.rx, latest.tx, 0);
                    connect(true);
                }
            });
        });
        if (!desired || destroying || generation != transportGeneration.get()) cancelHealthProbe();
    }
    private void submitWorker(Runnable action) {
        if (destroying) return;
        try { worker.execute(action); } catch (RejectedExecutionException closing) { }
    }
    private void cancelHealthProbe() {
        transportGeneration.incrementAndGet();
        DnsHealthProbe probe = activeProbe; activeProbe = null; if (probe != null) probe.cancel();
        Future<?> future = probeFuture; probeFuture = null; if (future != null) future.cancel(true);
    }
    private void recordEvent(String kind) {
        synchronized (events) {
            events.addLast(elapsedTime() / 1000 + "s " + kind);
            while (events.size() > 40) events.removeFirst();
        }
    }
    private void tearDown() {
        rebuilding = true;
        try { if (backend != null) backend.setState(tunnel, Tunnel.State.DOWN, null); }
        catch (Exception ignored) { }
        finally { rebuilding = false; }
    }
    private void publish(String phase, String message, long rx, long tx, long handshake) {
        synchronized (STATE_LOCK) {
            if (currentOwner != this || destroying) return;
            latest = new State(phase, activeId, message, rx, tx, handshake);
        }
        VpnTileService.refresh(this);
        if (desired && !message.equals(notificationText)) {
            notificationText = message;
            main.post(() -> {
                boolean allowed = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
                if (!destroying && desired && allowed) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message));
            });
        }
    }
    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent disconnect = PendingIntent.getService(this, 1,
                new Intent(this, RouteVpnService.class).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_route).setContentTitle(profileName)
                .setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "Отключить", disconnect).build()).build();
    }
    @Override public void onDestroy() {
        destroying = true; desired = false; stopping = true;
        cancelHealthProbe(); probeWorker.shutdownNow();
        if (wakeReceiverRegistered) { unregisterReceiver(wakeReceiver); wakeReceiverRegistered = false; }
        if (networkMonitor != null) networkMonitor.close();
        worker.execute(() -> {
            cancelReconnect(); tearDown();
            // Let the old Android VpnService process stopSelf before a new owner creates its TUN.
            try { Thread.sleep(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            synchronized (STATE_LOCK) {
                if (currentOwner == this) {
                    latest = terminalError == null ? new State("DISCONNECTED", "", "VPN выключен", 0, 0, 0)
                            : new State("ERROR", activeId, terminalError, 0, 0, 0);
                    currentOwner = null;
                }
            }
            VpnTileService.refresh(this);
            cleanupComplete.complete(null);
        });
        worker.shutdown(); main.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    /** System dumpsys diagnostics deliberately omit profile names, IDs, keys and all addresses. */
    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (args != null && java.util.Arrays.asList(args).contains("--probe")) requestHealthCheck(true);
        State state = latest;
        writer.println("AWG VPN connection diagnostics");
        writer.println("phase=" + state.phase + " wanted=" + desired + " autoReconnect=" + preferences.autoReconnect());
        writer.println("stopping=" + stopping + " destroying=" + destroying + " rebuilding=" + rebuilding);
        writer.println("networkMissing=" + networkMissing + " physicalNetwork=" + (observedNetwork == null ? "none" : observedNetwork.getNetworkHandle()));
        writer.println("rxBytes=" + state.rx + " txBytes=" + state.tx);
        writer.println("handshakeAgeSeconds=" + (state.handshake <= 0 ? -1 : Math.max(0, System.currentTimeMillis() / 1000 - state.handshake)));
        writer.println("tunnelAgeSeconds=" + (startedAt <= 0 ? -1 : (elapsedTime() - startedAt) / 1000));
        writer.println("routingMode=" + routingMode + " selectedApps=" + selectedPackageCount);
        writer.println("peers=" + peerCount + " dnsServers=" + dnsCount + " mtu=" + runtimeMtu + " keepaliveConfigured=" + keepaliveConfigured);
        writer.println("startupFailures=" + failures);
        writer.println("interactive=" + screenInteractive() + " probe=" + lastProbeResult + " probeRunning=" + (activeProbe != null));
        writer.println("probeFailures=" + watchdog.failedProbes() + " watchdogRestarts=" + watchdog.totalRestarts());
        synchronized (events) { for (String event : events) writer.println("event=" + event); }
    }
}
