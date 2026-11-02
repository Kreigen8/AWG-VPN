package ru.routevpn.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.amnezia.awg.backend.Backend;
import org.amnezia.awg.backend.GoBackend;
import org.amnezia.awg.backend.Statistics;
import org.amnezia.awg.backend.Tunnel;
import org.amnezia.awg.config.Config;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

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
    private final Handler main = new Handler(Looper.getMainLooper());
    private Backend backend;
    private ConnectionPreferences preferences;
    private UnderlyingNetworkMonitor networkMonitor;
    private ScheduledFuture<?> reconnect;
    private volatile boolean desired, destroying, stopping, rebuilding;
    private volatile Network observedNetwork;
    private volatile boolean networkMissing;
    private volatile String terminalError;
    private String profileName = "AWG VPN", activeId = "", notificationText = "";
    private long startedAt;
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
        worker.scheduleWithFixedDelay(this::refreshStatistics, 2, 2, TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESTORE : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            desired = false; stopping = true; preferences.clearRequest();
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
            config = ConfigPolicy.build(profile, installedPackages());
        } catch (IllegalArgumentException validation) {
            failPermanently(validation.getMessage()); return;
        } catch (RuntimeException inaccessible) {
            retry("Профили временно недоступны. Разблокируйте телефон."); return;
        }
        if (!permissionGranted()) { failPermanently("Разрешите VPN-подключение в приложении."); return; }
        try {
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
            startedAt = SystemClock.elapsedRealtime(); failures = 0;
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
        Network previous = observedNetwork; observedNetwork = network;
        networkMissing = network == null;
        if (!desired || stopping || destroying || !preferences.autoReconnect()) return;
        worker.execute(() -> {
            if (!desired || stopping || destroying) return;
            cancelReconnect();
            if (network == null) {
                publish("CONNECTING", "Сеть недоступна. Ждём подключения…", latest.rx, latest.tx, latest.handshake);
            } else if (previous == null || !network.equals(previous)) {
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
            long handshake = backend.getLastHandshake(tunnel);
            String message;
            if (handshake > 0) {
                long age = Math.max(0, System.currentTimeMillis() / 1000 - handshake);
                message = age < 180 ? "VPN подключён" : "Туннель активен · ответ сервера " + age / 60 + " мин назад";
            } else if (SystemClock.elapsedRealtime() - startedAt > 25000) message = "Нет ответа сервера. Проверьте конфиг и сеть.";
            else message = "Ожидаем ответ сервера…";
            // An idle old handshake is not proof of failure; rebuild only on network events/errors.
            publish(handshake > 0 ? "CONNECTED" : "CONNECTING", message,
                    statistics.totalRx(), statistics.totalTx(), Math.max(0, handshake));
        } catch (Exception ignored) { }
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
}
