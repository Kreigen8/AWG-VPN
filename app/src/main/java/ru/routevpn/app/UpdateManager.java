package ru.routevpn.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-owned worker: checks/downloads survive rotation; Activities attach only while visible. */
public class UpdateManager {
    public interface Listener { void changed(State state); }
    public static final class State {
        public final String phase, message;
        public final UpdateManifest update;
        public final int percent;
        State(String phase, String message, UpdateManifest update, int percent) {
            this.phase = phase; this.message = message; this.update = update; this.percent = percent;
        }
        public boolean busy() { return phase.equals("CHECKING") || phase.equals("DOWNLOADING") || phase.equals("VERIFYING"); }
    }
    private static volatile UpdateManager instance;
    public static UpdateManager get(Context context) {
        if (instance == null) synchronized (UpdateManager.class) {
            if (instance == null) instance = new UpdateManager(context, new UpdateHttp(), new UpdateApkVerifier(context));
        }
        return instance;
    }
    private final Context context;
    private final SharedPreferences preferences;
    private final UpdateHttp http;
    private final UpdateApkVerifier verifier;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final AtomicBoolean running = new AtomicBoolean(), canceled = new AtomicBoolean();
    private volatile State state = new State("IDLE", "Проверка обновлений ещё не выполнялась.", null, 0);
    private final File directory, apk, partial;
    private final long installedCode;
    private final String installedVersion;
    UpdateManager(Context context, UpdateHttp http, UpdateApkVerifier verifier) {
        this.context = context.getApplicationContext(); this.http = http; this.verifier = verifier;
        preferences = this.context.getSharedPreferences("routevpn_updates", Context.MODE_PRIVATE);
        directory = new File(this.context.getCacheDir(), "updates");
        apk = new File(directory, "pending.apk"); partial = new File(directory, "download.part");
        long code = 0; String version = "";
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            code = info.getLongVersionCode(); version = info.versionName;
        } catch (Exception ignored) { }
        installedCode = code; installedVersion = version == null ? "" : version;
        // A Settings round trip may recreate the process. Restore only a fully verified cached APK.
        String saved = preferences.getString("pending", "");
        if (!saved.isEmpty()) {
            running.set(true);
            worker.execute(() -> {
                try {
                    UpdateManifest update = UpdateManifest.restore(saved);
                    if (update.versionCode <= installedCode) { clearCache(); return; }
                    verifier.verify(apk, update);
                    publish(new State("READY", "Обновление загружено и проверено.", update, 100));
                } catch (Exception invalid) { clearCache(); }
                finally { running.set(false); }
            });
        }
    }
    public State state() { return state; }
    public String installedVersion() { return installedVersion; }
    public long installedCode() { return installedCode; }
    public boolean autoCheck() { return preferences.getBoolean("auto_check", true); }
    public void setAutoCheck(boolean enabled) { preferences.edit().putBoolean("auto_check", enabled).apply(); }
    public void listen(Listener listener) { listeners.add(listener); listener.changed(state); }
    public void stopListening(Listener listener) { listeners.remove(listener); }
    private void publish(State value) {
        state = value;
        main.post(() -> { for (Listener listener : listeners) listener.changed(value); });
    }
    public void check(boolean automatic) {
        State before = state;
        long now = System.currentTimeMillis();
        if (automatic && (!autoCheck() || now - preferences.getLong("last_success", 0) < 24L * 60 * 60 * 1000
                || now - preferences.getLong("last_attempt", 0) < 60L * 60 * 1000 || state.update != null)) return;
        if (!running.compareAndSet(false, true)) return;
        canceled.set(false);
        preferences.edit().putLong("last_attempt", now).apply();
        publish(new State("CHECKING", "Проверяем релизы GitHub…", null, 0));
        worker.execute(() -> {
            try {
                UpdateManifest newest = null;
                for (UpdateManifest.Release release : UpdateManifest.releases(http.text(UpdateManifest.RELEASES_API, true, 2 * 1024 * 1024, canceled))) {
                    UpdateHttp.checkCanceled(canceled);
                    UpdateManifest candidate = UpdateManifest.parse(http.text(release.manifestUrl, false, 16384, canceled), release);
                    if (candidate.minSdk <= Build.VERSION.SDK_INT && candidate.versionCode > installedCode
                            && (newest == null || candidate.versionCode > newest.versionCode)) newest = candidate;
                }
                UpdateHttp.checkCanceled(canceled);
                preferences.edit().putLong("last_success", System.currentTimeMillis()).apply();
                if (newest == null) publish(new State("CURRENT", "Установлена последняя доступная версия.", null, 0));
                else {
                    boolean cached = false;
                    try { if (apk.isFile()) { verifier.verify(apk, newest); cached = true; } } catch (Exception ignored) { }
                    publish(new State(cached ? "READY" : "AVAILABLE", cached ? "Обновление загружено и проверено."
                            : "Доступна новая версия " + newest.versionName + ".", newest, cached ? 100 : 0));
                }
            } catch (Exception failure) {
                if (before.phase.equals("READY") && apk.isFile()) publish(new State("READY", "Проверка GitHub не удалась. Загруженное обновление доступно для установки.", before.update, 100));
                else fail("Не удалось проверить обновления. Проверьте интернет и повторите.", failure, before.update);
            }
            finally { running.set(false); }
        });
    }
    public void download() {
        UpdateManifest update = state.update;
        if (update == null || !running.compareAndSet(false, true)) return;
        canceled.set(false); publish(new State("DOWNLOADING", "Загружаем APK с GitHub…", update, 0));
        worker.execute(() -> {
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Нет места для обновления.");
                clearFiles();
                final long[] last = {0};
                http.download(update, partial, canceled, received -> {
                    long time = System.nanoTime();
                    if (time - last[0] > 200_000_000L) {
                        last[0] = time;
                        publish(new State("DOWNLOADING", "Загружаем APK с GitHub…", update, (int) (received * 100 / update.apkSize)));
                    }
                });
                UpdateHttp.checkCanceled(canceled);
                publish(new State("VERIFYING", "Проверяем подпись и совместимость APK…", update, 100));
                verifier.verify(partial, update);
                UpdateHttp.checkCanceled(canceled);
                if (!partial.renameTo(apk)) throw new IOException("Не удалось сохранить файл обновления.");
                preferences.edit().putString("pending", update.savedJson()).apply();
                publish(new State("READY", "Обновление загружено и проверено.", update, 100));
            } catch (Exception failure) { clearCache(); fail("Не удалось загрузить обновление. Повторите попытку.", failure, update); }
            finally { running.set(false); }
        });
    }
    public void cancel() { canceled.set(true); http.cancel(); }
    public void prepareInstall(java.util.function.Consumer<File> success) {
        UpdateManifest update = state.update;
        if (update == null || !state.phase.equals("READY") || !running.compareAndSet(false, true)) return;
        publish(new State("VERIFYING", "Повторно проверяем APK перед установкой…", update, 100));
        worker.execute(() -> {
            try {
                verifier.verify(apk, update);
                publish(new State("READY", "Подтвердите обновление в установщике Android.", update, 100));
                main.post(() -> success.accept(apk));
            } catch (Exception failure) { clearCache(); fail("Установка отменена: файл не прошёл проверку.", failure, update); }
            finally { running.set(false); }
        });
    }
    private void fail(String fallback, Exception failure, UpdateManifest update) {
        String message = canceled.get() ? "Проверка или загрузка отменена." : failure instanceof IllegalArgumentException || failure instanceof UpdateHttp.Failure
                ? failure.getMessage() : fallback;
        publish(new State("ERROR", message == null ? fallback : message, update, 0));
    }
    private void clearFiles() {
        if (partial.exists()) partial.delete();
        if (apk.exists()) apk.delete();
    }
    private void clearCache() { clearFiles(); preferences.edit().remove("pending").apply(); }
    void close() { cancel(); listeners.clear(); worker.shutdownNow(); }
}
