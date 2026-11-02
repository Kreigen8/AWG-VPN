package ru.routevpn.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.Locale;

/** Foreground, user-initiated download/install flow. Android performs the final signature/install checks. */
public class UpdateActivity extends Activity {
    private static final int UNKNOWN_SOURCES = 41, INSTALL_APK = 42;
    private UpdateManager manager;
    private TextView status, detail;
    private ProgressBar progress;
    private Button check, download, install, cancel;
    private boolean visible, waitingPermission;
    private final UpdateManager.Listener listener = this::render;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        manager = createManager();
        waitingPermission = state != null && state.getBoolean("waitingUpdatePermission", false);
        buildScreen(); render(manager.state());
    }
    protected UpdateManager createManager() { return UpdateManager.get(this); }
    private void buildScreen() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xfff6f5f0);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(22), dp(16), dp(22), dp(24));
        Button back = button("Назад"); back.setOnClickListener(view -> finish()); page.addView(back);
        page.addView(text("Обновления AWG VPN", 24));
        TextView version = text("Установлена версия " + manager.installedVersion(), 14); version.setPadding(0, dp(10), 0, dp(18)); page.addView(version);
        status = text("", 17); status.setTag("update_status"); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); page.addView(status);
        detail = text("", 14); detail.setPadding(0, dp(10), 0, dp(12)); page.addView(detail);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); page.addView(progress, new LinearLayout.LayoutParams(-1, dp(10)));
        check = button("Проверить обновления"); check.setTag("update_check"); check.setOnClickListener(view -> manager.check(false)); add(page, check, 18);
        download = button("Загрузить APK"); download.setTag("update_download"); download.setOnClickListener(view -> manager.download()); add(page, download, 10);
        install = button("Установить обновление"); install.setTag("update_install"); install.setTextColor(Color.WHITE);
        install.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff12685e)); install.setOnClickListener(view -> installUpdate()); add(page, install, 10);
        cancel = button("Отменить загрузку"); cancel.setOnClickListener(view -> manager.cancel()); add(page, cancel, 8);
        Switch auto = new Switch(this); auto.setText("Проверять при запуске приложения"); auto.setTextColor(0xff1b2a27); auto.setTextSize(14);
        auto.setChecked(manager.autoCheck()); auto.setOnCheckedChangeListener((button, enabled) -> manager.setAutoCheck(enabled)); add(page, auto, 22);
        add(page, text("Автоматическая проверка — не чаще раза в сутки. Загрузка и установка начинаются по вашему нажатию.", 13), 8);
        add(page, text("Источник: github.com/Kreigen8/AWG-VPN\nУчитываются обычные и preview-релизы.", 13), 20);
        add(page, text("Обновление устанавливается поверх приложения. Конфиги, выбранные приложения и настройки сохраняются. Удалять AWG VPN не нужно.", 14), 18);
        Button releases = button("Открыть релизы GitHub"); releases.setOnClickListener(view -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManifest.RELEASES_PAGE))); }
            catch (RuntimeException unavailable) { status.setText("Браузер недоступен. Используйте проверку обновлений выше."); }
        }); add(page, releases, 10);
        scroll.addView(page); setContentView(scroll); scroll.requestApplyInsets();
    }
    @Override protected void onStart() { super.onStart(); visible = true; manager.listen(listener); if (manager.state().phase.equals("IDLE")) manager.check(false); }
    @Override protected void onStop() { visible = false; manager.stopListening(listener); super.onStop(); }
    @Override protected void onResume() {
        super.onResume();
        if (waitingPermission && getPackageManager().canRequestPackageInstalls()) {
            waitingPermission = false; installUpdate();
        }
    }
    private void render(UpdateManager.State state) {
        status.setText(state.message);
        String description = state.update == null ? "Проверяются релизы вашего GitHub." : "Версия " + state.update.versionName + " · "
                + String.format(Locale.forLanguageTag("ru"), "%.1f МБ", state.update.apkSize / (1024d * 1024d));
        if (state.phase.equals("READY")) description += "\nSHA-256 и подпись совпадают. Можно обновить приложение.";
        detail.setText(description);
        boolean busy = state.busy();
        progress.setVisibility(busy ? View.VISIBLE : View.GONE); progress.setIndeterminate(!state.phase.equals("DOWNLOADING")); progress.setProgress(state.percent);
        check.setEnabled(!busy);
        boolean available = state.update != null && !state.phase.equals("READY");
        download.setVisibility(available ? View.VISIBLE : View.GONE); download.setEnabled(!busy);
        install.setVisibility(state.phase.equals("READY") ? View.VISIBLE : View.GONE); install.setEnabled(!busy);
        cancel.setVisibility(state.phase.equals("CHECKING") || state.phase.equals("DOWNLOADING") ? View.VISIBLE : View.GONE);
    }
    private void installUpdate() {
        if (!manager.state().phase.equals("READY")) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            waitingPermission = true;
            status.setText("Разрешите установку обновлений от AWG VPN в следующем системном окне.");
            try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())), UNKNOWN_SOURCES); }
            catch (RuntimeException unavailable) { waitingPermission = false; status.setText("Откройте настройки Android → Установка неизвестных приложений → AWG VPN."); }
            return;
        }
        manager.prepareInstall(file -> {
            if (!visible || isFinishing() || isDestroyed()) return;
            try { launchInstaller(file); }
            catch (RuntimeException unavailable) { status.setText("Не удалось открыть установщик Android. Проверьте разрешение установки и повторите."); }
        });
    }
    protected void launchInstaller(File apk) {
        startActivityForResult(installerIntent(this, apk), INSTALL_APK);
    }
    static Intent installerIntent(android.content.Context context, File apk) {
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", apk);
        return new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == UNKNOWN_SOURCES) {
            boolean requested = waitingPermission; waitingPermission = false;
            if (requested && getPackageManager().canRequestPackageInstalls()) installUpdate();
            else if (requested) status.setText("Разрешение на установку не выдано. Загруженный APK сохранён, можно повторить установку.");
        } else if (request == INSTALL_APK) render(manager.state());
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putBoolean("waitingUpdatePermission", waitingPermission); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(0xff1b2a27); return view; }
    private Button button(String text) { Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextSize(14); button.setTextColor(0xff12685e); return button; }
    private void add(LinearLayout parent, View view, int top) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(view, params); }
}
