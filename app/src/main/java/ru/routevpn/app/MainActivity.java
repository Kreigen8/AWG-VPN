package ru.routevpn.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small, native client. Private config contents never appear in the dashboard or logs. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(246, 245, 240);
    private static final int INK = Color.rgb(27, 42, 39);
    private static final int MUTED = Color.rgb(106, 117, 111);
    private static final int TEAL = Color.rgb(18, 104, 94);
    private static final int DARK = Color.rgb(15, 67, 60);
    private static final int LIME = Color.rgb(212, 242, 140);
    private static final int LINE = Color.rgb(226, 230, 220);
    private static final int IMPORT_CONFIG = 11;
    private static final int VPN_PERMISSION = 12;
    private static final int NOTIFICATIONS = 13;
    private static final int IMPORT_QR_IMAGE = 14;
    private static final int SCAN_QR_CAMERA = 15;
    private static final int EDIT_CONFIG = 16;
    private static final int MAX_CONFIG_BYTES = ConfigPolicy.MAX_CONFIG_BYTES;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Profile> profiles = new ArrayList<>();
    private ProfileStore store;
    private String selectedId;
    private String pendingConnectId;
    private boolean importing;
    private boolean storageReady = true;
    private TextView connectionLabel, connectionTitle, connectionDescription, stats;
    private TextView profileName, profileDetail, routingSummary, editNotice;
    private Button connectButton, importButton, manageButton, appButton;
    private LinearLayout profileRow;
    private TextView updateSummary;
    private final UpdateManager.Listener updateListener = state -> {
        if (updateSummary == null) return;
        updateSummary.setText(state.update == null ? "Версия " + UpdateManager.get(this).installedVersion() : "Доступна версия " + state.update.versionName);
    };
    private final List<TextView> modeViews = new ArrayList<>();
    private final String[] modes = {"ALL", "ONLY_SELECTED", "EXCEPT_SELECTED"};
    private Dialog appsDialog;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (state != null) pendingConnectId = state.getString("pendingConnectId");
        store = new ProfileStore(this);
        try {
            profiles.addAll(store.load());
            selectedId = store.getSelectedId();
            if (selected() == null && !profiles.isEmpty()) {
                selectedId = profiles.get(0).id;
                store.setSelectedId(selectedId);
            }
        } catch (Exception e) {
            storageReady = false;
        }
        buildScreen();
        render();
        handleTileRequest();
        if (!storageReady) handler.post(() -> error("Не удалось прочитать сохранённые профили. Закройте приложение и попробуйте снова. Существующие данные не изменены."));
    }

    @Override protected void onStart() {
        super.onStart();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        if (!active(RouteVpnService.snapshot())) RouteVpnService.restore(this, false);
        UpdateManager.get(this).listen(updateListener);
        UpdateManager.get(this).check(true);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTileRequest();
    }

    private void handleTileRequest() {
        if (getIntent().getBooleanExtra(VpnTileService.EXTRA_CONNECT, false)) {
            getIntent().removeExtra(VpnTileService.EXTRA_CONNECT);
            handler.post(() -> { if (!active(RouteVpnService.snapshot())) onConnect(); });
        }
    }

    @Override protected void onStop() {
        UpdateManager.get(this).stopListening(updateListener);
        handler.removeCallbacks(ticker);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (appsDialog != null) appsDialog.dismiss();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pendingConnectId", pendingConnectId);
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(BG);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout page = column();
        page.setPadding(dp(22), dp(24), dp(22), dp(28));
        scroll.addView(page);
        setContentView(scroll);
        scroll.requestApplyInsets();

        LinearLayout header = row();
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_route);
        header.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout brand = column();
        brand.setPadding(dp(11), 0, 0, 0);
        brand.addView(text("AWG VPN", 23, INK, true));
        TextView tagline = text("ВАШИ ПРАВИЛА", 10, MUTED, true);
        tagline.setLetterSpacing(.13f);
        brand.addView(tagline);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(header);

        LinearLayout connection = card(DARK, 26);
        add(page, connection, 25);
        connectionLabel = text("●  ОТКЛЮЧЕНО", 11, LIME, true);
        connectionLabel.setLetterSpacing(.1f);
        connection.addView(connectionLabel);
        connectionTitle = text("Ваш маршрут.\nПод вашим контролем.", 29, Color.WHITE, true);
        connectionTitle.setLineSpacing(dp(2), 1f);
        add(connection, connectionTitle, 18);
        connectionDescription = text("Добавьте конфиг и выберите, какие приложения пойдут через VPN.", 14, Color.rgb(194, 217, 209), false);
        connectionDescription.setLineSpacing(dp(3), 1f);
        add(connection, connectionDescription, 12);
        connectButton = button("Добавить конфиг", LIME, DARK);
        add(connection, connectButton, 22);
        connectButton.setOnClickListener(view -> onConnect());
        stats = text("", 12, Color.rgb(194, 217, 209), false);
        stats.setGravity(Gravity.CENTER);
        add(connection, stats, 12);

        LinearLayout profileHeading = row();
        profileHeading.addView(text("Конфигурация", 19, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView local = text("НА УСТРОЙСТВЕ", 9, MUTED, true);
        local.setLetterSpacing(.08f);
        profileHeading.addView(local);
        add(page, profileHeading, 28);
        LinearLayout configCard = card(Color.WHITE, 20);
        add(page, configCard, 12);
        profileRow = row();
        LinearLayout profileWords = column();
        profileName = text("Конфиг не добавлен", 17, INK, true);
        profileDetail = text("Импортируйте файл .conf", 12, MUTED, false);
        profileWords.addView(profileName);
        add(profileWords, profileDetail, 5);
        profileRow.addView(profileWords, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("⌄", 23, TEAL, false);
        arrow.setPadding(dp(12), 0, 0, 0);
        profileRow.addView(arrow);
        profileRow.setPadding(0, dp(2), 0, dp(12));
        profileRow.setOnClickListener(view -> chooseProfile());
        configCard.addView(profileRow);
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        configCard.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout actions = row();
        importButton = textButton("＋ Импортировать");
        manageButton = textButton("Изменить");
        actions.addView(importButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(manageButton, new LinearLayout.LayoutParams(-2, dp(48)));
        configCard.addView(actions);
        importButton.setOnClickListener(view -> importMenu());
        manageButton.setOnClickListener(view -> manageProfile());

        LinearLayout routingHeading = row();
        routingHeading.addView(text("Куда идёт трафик", 19, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button routingHelp = textButton("ⓘ");
        routingHelp.setContentDescription("Как работает маршрутизация приложений");
        routingHeading.addView(routingHelp, new LinearLayout.LayoutParams(dp(44), dp(44)));
        routingHelp.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("Правила маршрутизации")
                .setMessage("Выбранные приложения используют VPN или работают напрямую в зависимости от режима. Правила действуют только в текущем профиле Android.\n\nИспользуются маршруты AllowedIPs из конфига: если указаны только отдельные сети, прочий трафик не входит в туннель.\n\nЕсли в настройках Android включено «Блокировать соединения без VPN», приложения «напрямую» останутся без интернета.")
                .setPositiveButton("Понятно", null).show());
        add(page, routingHeading, 21);
        TextView routingIntro = text("Правила сохраняются отдельно для каждого конфига.", 13, MUTED, false);
        routingIntro.setLineSpacing(dp(2), 1f);
        add(page, routingIntro, 6);
        LinearLayout routingCard = card(Color.WHITE, 18);
        add(page, routingCard, 12);
        String[] modeTitles = {"Все приложения через VPN", "Только выбранные через VPN", "Выбранные напрямую"};
        for (int i = 0; i < modes.length; i++) {
            final String mode = modes[i];
            TextView option = text(modeTitles[i], 14, INK, true);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setPadding(dp(14), dp(14), dp(14), dp(14));
            option.setTag(modeTitles[i]);
            option.setContentDescription(modeTitles[i]);
            option.setFocusable(true);
            option.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.RadioButton");
                    info.setCheckable(true);
                    info.setChecked(host.isSelected());
                }
            });
            option.setMinHeight(dp(51));
            add(routingCard, option, i == 0 ? 0 : 7);
            option.setOnClickListener(view -> changeMode(mode));
            modeViews.add(option);
        }
        routingSummary = text("", 13, MUTED, false);
        routingSummary.setLineSpacing(dp(3), 1f);
        add(routingCard, routingSummary, 15);
        appButton = button("Выбрать приложения", Color.rgb(232, 240, 229), TEAL);
        add(routingCard, appButton, 15);
        appButton.setOnClickListener(view -> pickApps());
        editNotice = text("", 12, TEAL, false);
        editNotice.setLineSpacing(dp(3), 1f);
        add(page, editNotice, 14);
        TextView footer = text("AMNEZIAWG 2.0 / 3.1\nТолько ваши конфиги. Без аккаунта.", 11, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setLineSpacing(dp(5), 1f);
        add(page, footer, 25);
        TextView settings = text("Подключение", 19, INK, true);
        add(page, settings, 24);
        ConnectionPreferences connectionOptions = new ConnectionPreferences(this);
        Switch restore = new Switch(this);
        restore.setText("Восстанавливать VPN автоматически");
        restore.setTextColor(INK);
        restore.setTextSize(14);
        restore.setChecked(connectionOptions.autoReconnect());
        restore.setOnCheckedChangeListener((button, enabled) -> connectionOptions.setAutoReconnect(enabled));
        add(page, restore, 12);
        add(page, text("После смены сети и перезапуска приложения восстанавливается последнее включённое подключение. Ручное отключение запоминается.", 12, MUTED, false), 8);
        Button systemVpn = textButton("Автозапуск: настройки VPN Android");
        systemVpn.setOnClickListener(view -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_VPN_SETTINGS)); }
            catch (RuntimeException unavailable) { toast("Откройте настройки Android → VPN → AWG VPN."); }
        });
        add(page, systemVpn, 6);
        add(page, text("Для запуска после перезагрузки можно включить «Постоянная VPN». Опция «Блокировать без VPN» отключает интернет у приложений, выбранных для прямого доступа.", 12, MUTED, false), 5);
        Button tile = textButton("Добавить VPN в шторку");
        tile.setOnClickListener(view -> {
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    getSystemService(android.app.StatusBarManager.class).requestAddTileService(
                            new android.content.ComponentName(this, VpnTileService.class), "AWG VPN",
                            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_vpn_tile),
                            getMainExecutor(), result -> { });
                } catch (RuntimeException unavailable) { toast("Шторка Android → Изменить кнопки → AWG VPN."); }
            } else toast("Шторка Android → Изменить кнопки → AWG VPN.");
        });
        add(page, tile, 8);
        add(page, text("Обновления", 19, INK, true), 24);
        updateSummary = text("", 13, MUTED, false);
        add(page, updateSummary, 10);
        Button updates = textButton("Проверить обновления GitHub");
        updates.setOnClickListener(view -> startActivity(new Intent(this, UpdateActivity.class)));
        add(page, updates, 6);
    }

    private void render() {
        if (connectionLabel == null) return;
        Profile profile = selected();
        RouteVpnService.State state = RouteVpnService.snapshot();
        boolean active = active(state);
        if (active && state.id != null && find(state.id) != null && !state.id.equals(selectedId)) {
            selectedId = state.id;
            profile = selected();
        }
        String phase = state.phase == null ? "DISCONNECTED" : state.phase;
        boolean connecting = "CONNECTING".equals(phase);
        boolean disconnecting = "DISCONNECTING".equals(phase);
        boolean connected = "CONNECTED".equals(phase);
        boolean failed = "ERROR".equals(phase);
        connectionLabel.setText(connected ? "●  VPN ПОДКЛЮЧЁН" : connecting ? "●  ПОДКЛЮЧЕНИЕ" : disconnecting ? "●  ОТКЛЮЧЕНИЕ" : failed ? "●  НЕ УДАЛОСЬ ПОДКЛЮЧИТЬ" : "○  VPN ОТКЛЮЧЁН");
        connectionTitle.setText(connected ? "Маршрут\nустановлен." : connecting ? "Прокладываем\nмаршрут…" : disconnecting ? "Завершаем\nсоединение…" : profile == null ? "Ваш маршрут.\nПод вашим контролем." : "Всё готово\nк подключению.");
        if (failed) connectionDescription.setText(nonEmpty(state.message, "Проверьте конфиг и подключение к интернету."));
        else if (connected) connectionDescription.setText(routeSummary(profile));
        else if (connecting) connectionDescription.setText(nonEmpty(state.message, "Настраиваем защищённое соединение. Это может занять несколько секунд."));
        else if (disconnecting) connectionDescription.setText("Возвращаем приложения на обычное подключение.");
        else if (profile == null) connectionDescription.setText("Добавьте конфиг и выберите, какие приложения пойдут через VPN.");
        else connectionDescription.setText("Подключите «" + profile.name + "», чтобы применить выбранные правила.");
        connectButton.setText(importing ? "Импортируем…" : connected ? "Отключить VPN" : connecting ? "Остановить подключение" : disconnecting ? "Отключаем…" : profile == null ? "Добавить конфиг" : "Подключить VPN");
        connectButton.setEnabled(storageReady && !importing && !disconnecting);
        stats.setVisibility(connected ? View.VISIBLE : View.GONE);
        if (connected) {
            String handshake = nonEmpty(state.message, state.handshake > 0 ? "Обмен с сервером есть" : "Ожидание ответа сервера");
            stats.setText("↓ " + bytes(state.rx) + "     ↑ " + bytes(state.tx) + "\n" + handshake);
        }
        profileName.setText(profile == null ? "Конфиг не добавлен" : profile.name);
        profileDetail.setText(profile == null ? "Импортируйте файл .conf" : nonEmpty(profile.protocol, "AmneziaWG") + "  ·  Профилей: " + profiles.size());
        boolean editable = !active && !importing && storageReady;
        profileRow.setEnabled(editable);
        profileRow.setAlpha(editable ? 1f : .55f);
        importButton.setEnabled(editable);
        manageButton.setEnabled(editable && profile != null);
        for (int i = 0; i < modeViews.size(); i++) {
            TextView option = modeViews.get(i);
            boolean chosen = profile != null && modes[i].equals(profile.mode);
            option.setSelected(chosen);
            option.setText((chosen ? "●  " : "○  ") + option.getTag());
            option.setTextColor(chosen ? TEAL : INK);
            option.setBackground(rounded(chosen ? Color.rgb(233, 243, 229) : BG, 12));
            option.setEnabled(editable && profile != null);
            option.setAlpha(editable && profile != null ? 1f : .5f);
        }
        routingSummary.setText(profile == null ? "Сначала добавьте конфиг." : routeSummary(profile));
        boolean appsRelevant = profile != null && !"ALL".equals(profile.mode);
        appButton.setVisibility(appsRelevant ? View.VISIBLE : View.GONE);
        appButton.setEnabled(editable && appsRelevant);
        appButton.setText(profile == null || profile.packages.isEmpty() ? "Выбрать приложения" : "Изменить список · " + profile.packages.size());
        editNotice.setVisibility(active ? View.VISIBLE : View.GONE);
        editNotice.setText("Чтобы изменить конфиг или правила приложений, отключите VPN, внесите изменения и подключитесь снова.");
        if (active && appsDialog != null && appsDialog.isShowing()) appsDialog.dismiss();
    }

    private String routeSummary(Profile profile) {
        if (profile == null) return "";
        int count = profile.packages.size();
        if ("ONLY_SELECTED".equals(profile.mode)) return count == 0
                ? "Выберите хотя бы одно приложение. Остальные будут работать напрямую."
                : "Через VPN — выбранные приложения (" + count + "). Все остальные — напрямую.";
        if ("EXCEPT_SELECTED".equals(profile.mode)) return count == 0
                ? "Список исключений пуст: все приложения идут через VPN."
                : "Напрямую — выбранные приложения (" + count + "). Все остальные — через VPN.";
        return "Все приложения используют VPN. Список выбранных приложений сохранён и сейчас не применяется.";
    }

    private static boolean active(RouteVpnService.State state) {
        return "CONNECTED".equals(state.phase) || "CONNECTING".equals(state.phase) || "DISCONNECTING".equals(state.phase);
    }

    private boolean ensureEditable() {
        if (!storageReady) { error("Сохранённые профили недоступны. Перезапустите приложение."); return false; }
        if (active(RouteVpnService.snapshot())) { toast("Сначала отключите VPN, чтобы изменить настройки."); return false; }
        if (importing) { toast("Дождитесь завершения импорта."); return false; }
        return true;
    }

    private void onConnect() {
        RouteVpnService.State state = RouteVpnService.snapshot();
        if (active(state)) {
            try { startService(new Intent(this, RouteVpnService.class).setAction(RouteVpnService.ACTION_DISCONNECT)); }
            catch (Exception e) { error("Не удалось отключить VPN. Повторите попытку или отключите его в настройках Android."); }
            handler.postDelayed(this::render, 150);
            return;
        }
        Profile profile = selected();
        if (profile == null) { importMenu(); return; }
        if ("ONLY_SELECTED".equals(profile.mode) && profile.packages.isEmpty()) {
            toast("Выберите приложения, которые должны использовать VPN.");
            pickApps();
            return;
        }
        pendingConnectId = profile.id;
        try {
            Intent permission = VpnService.prepare(this);
            if (permission != null) startActivityForResult(permission, VPN_PERMISSION);
            else startVpn();
        } catch (RuntimeException restriction) {
            pendingConnectId = null;
            error("Не удалось получить разрешение на VPN. Проверьте ограничения VPN в настройках устройства или рабочего профиля Android.");
        }
    }

    private void startVpn() {
        String id = pendingConnectId;
        pendingConnectId = null;
        if (id == null || find(id) == null || active(RouteVpnService.snapshot())) return;
        try {
            startForegroundService(new Intent(this, RouteVpnService.class)
                    .setAction(RouteVpnService.ACTION_CONNECT).putExtra(RouteVpnService.EXTRA_PROFILE_ID, id));
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
            }
            handler.postDelayed(this::render, 150);
        } catch (Exception e) { error("Android не разрешил запустить VPN. Откройте приложение и повторите подключение."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SCAN_QR_CAMERA) {
            String raw = data == null ? null : data.getStringExtra("SCAN_RESULT");
            if (result == RESULT_OK && raw != null && ensureEditable()) acceptQr(raw);
        } else if (request == EDIT_CONFIG && result == RESULT_OK) {
            try {
                List<Profile> updated = store.load();
                profiles.clear(); profiles.addAll(updated);
                render(); toast("Конфиг сохранён");
            } catch (RuntimeException inaccessible) { error("Не удалось перечитать профиль. Перезапустите приложение."); }
        } else if (request == VPN_PERMISSION) {
            if (result == RESULT_OK) startVpn();
            else { pendingConnectId = null; toast("Разрешение на VPN не получено."); }
        } else if (request == IMPORT_CONFIG && result == RESULT_OK && data != null && data.getData() != null && ensureEditable()) {
            importFile(data.getData());
        } else if (request == IMPORT_QR_IMAGE && result == RESULT_OK && data != null && data.getData() != null && ensureEditable()) {
            importQrImage(data.getData());
        }
    }

    private void importMenu() {
        if (!ensureEditable()) return;
        new AlertDialog.Builder(this).setTitle("Добавить конфиг")
                .setItems(new String[]{"Выбрать файл .conf", "Вставить текст конфига", "Сканировать QR камерой", "QR из изображения"}, (dialog, which) -> {
                    if (which == 0) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        try { startActivityForResult(intent, IMPORT_CONFIG); }
                        catch (Exception e) { error("Файловый менеджер недоступен. Используйте вставку текста конфига."); }
                    } else if (which == 1) pasteConfig();
                    else if (which == 2) scanQr();
                    else chooseQrImage();
                }).setNegativeButton("Отмена", null).show();
    }

    private void scanQr() {
        if (!ensureEditable()) return;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            error("Камера недоступна. Выберите «QR из изображения».");
            return;
        }
        try {
            startActivityForResult(new Intent(this, QrCaptureActivity.class), SCAN_QR_CAMERA);
        } catch (RuntimeException e) { error("Не удалось открыть камеру. Используйте импорт QR из изображения."); }
    }

    private void chooseQrImage() {
        if (!ensureEditable()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try { startActivityForResult(intent, IMPORT_QR_IMAGE); }
        catch (RuntimeException e) { error("Не удалось открыть выбор изображения. Попробуйте сканирование камерой."); }
    }

    private void importQrImage(Uri uri) {
        importing = true;
        render();
        worker.execute(() -> {
            try {
                final String raw;
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalArgumentException("Не удалось открыть изображение.");
                    raw = QrPhotoReader.decode(input);
                }
                Profile profile = ConfigPolicy.parseImported("VPN из QR", QrPayload.decode(raw));
                runOnUiThread(() -> finishQr(profile, null));
            } catch (Exception failure) {
                String message = failure instanceof IllegalArgumentException ? nonEmpty(failure.getMessage(), "Не удалось прочитать QR-код.")
                        : "Не удалось прочитать QR-код. Выберите другое изображение или используйте камеру.";
                runOnUiThread(() -> finishQr(null, message));
            } catch (OutOfMemoryError tooLarge) {
                runOnUiThread(() -> finishQr(null, "Недостаточно памяти для изображения. Обрежьте изображение до QR-кода и повторите импорт."));
            }
        });
    }

    private void acceptQr(String raw) {
        importing = true;
        render();
        worker.execute(() -> {
            try {
                Profile profile = ConfigPolicy.parseImported("VPN из QR", QrPayload.decode(raw));
                runOnUiThread(() -> finishQr(profile, null));
            } catch (Exception failure) {
                String message = failure instanceof IllegalArgumentException ? nonEmpty(failure.getMessage(), "QR-код не содержит конфиг AmneziaWG.")
                        : "QR-код не содержит поддерживаемый конфиг AmneziaWG.";
                runOnUiThread(() -> finishQr(null, message));
            }
        });
    }

    private void finishQr(Profile profile, String message) {
        if (isFinishing() || isDestroyed()) return;
        importing = false;
        render();
        if (message != null) { error(message); return; }
        if (!ensureEditable()) return;
        LinearLayout fields = column();
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        TextView valid = text("Конфиг распознан · " + profile.protocol, 13, TEAL, true);
        fields.addView(valid);
        EditText name = input("Название подключения", false);
        name.setText(profile.name);
        name.selectAll();
        add(fields, name, 12);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Профиль из QR-кода").setView(fields)
                .setPositiveButton("Добавить", null).setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!ensureEditable()) return;
            String label = name.getText().toString().trim();
            if (label.isEmpty() || label.length() > 80) { name.setError("От 1 до 80 символов"); return; }
            profile.name = label;
            if (commitImported(profile)) dialog.dismiss();
        }));
        dialog.show();
    }

    private void importFile(Uri uri) {
        importing = true;
        render();
        worker.execute(() -> {
            try {
                String name = "Мой VPN";
                try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                }
                if (name == null || name.trim().isEmpty()) name = "Мой VPN";
                if (name.toLowerCase(Locale.ROOT).endsWith(".conf")) name = name.substring(0, name.length() - 5);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalArgumentException("Не удалось открыть файл.");
                    byte[] block = new byte[8192];
                    int read;
                    while ((read = input.read(block)) != -1) {
                        if (output.size() + read > MAX_CONFIG_BYTES) throw new IllegalArgumentException("Файл слишком большой. Максимум — " + MAX_CONFIG_BYTES / 1024 + " КБ.");
                        output.write(block, 0, read);
                    }
                }
                Profile imported = ConfigPolicy.parseImported(name, new String(output.toByteArray(), StandardCharsets.UTF_8));
                runOnUiThread(() -> finishImport(imported, null));
            } catch (Exception e) {
                String message = e instanceof IllegalArgumentException ? nonEmpty(e.getMessage(), "Не удалось прочитать конфиг.") : "Не удалось прочитать конфиг. Проверьте доступ к файлу и формат AmneziaWG.";
                runOnUiThread(() -> finishImport(null, message));
            }
        });
    }

    private void pasteConfig() {
        if (!ensureEditable()) return;
        LinearLayout fields = column();
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        EditText name = input("Название подключения", false);
        name.setText("Мой VPN");
        fields.addView(name);
        EditText config = input("[Interface]\nPrivateKey = …\n…", true);
        config.setMinLines(6);
        config.setMaxLines(10);
        config.setTypeface(Typeface.MONOSPACE);
        config.setTextSize(12);
        config.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        config.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        config.setImeOptions(config.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        config.setSaveEnabled(false);
        add(fields, config, 12);
        TextView privacy = text("Конфиг содержит приватный ключ. Он сохраняется только на этом устройстве.", 12, MUTED, false);
        add(fields, privacy, 12);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Вставить конфиг").setView(fields)
                .setPositiveButton("Добавить", null).setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (!ensureEditable()) return;
                String raw = config.getText().toString();
                if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) { config.setError("Максимум — " + MAX_CONFIG_BYTES / 1024 + " КБ"); return; }
                String label = name.getText().toString().trim();
                if (label.isEmpty()) { name.setError("Введите название"); return; }
                try {
                    Profile imported = ConfigPolicy.parseImported(label, raw);
                    if (commitImported(imported)) { config.setText(""); dialog.dismiss(); }
                } catch (Exception e) { config.setError(nonEmpty(e.getMessage(), "Проверьте формат конфига AmneziaWG.")); }
            });
        });
        dialog.show();
    }

    private void finishImport(Profile profile, String message) {
        if (isFinishing() || isDestroyed()) return;
        importing = false;
        if (message != null) error(message);
        else if (!active(RouteVpnService.snapshot())) commitImported(profile);
        else error("VPN уже подключён. Отключите его и импортируйте конфиг снова.");
        render();
    }

    private boolean commitImported(Profile profile) {
        List<Profile> updated = copyProfiles();
        updated.add(profile);
        if (save(updated, profile.id)) { toast("Конфиг добавлен"); return true; }
        return false;
    }

    private void chooseProfile() {
        if (!ensureEditable()) return;
        if (profiles.isEmpty()) { importMenu(); return; }
        String[] names = new String[profiles.size()];
        int checked = -1;
        for (int i = 0; i < profiles.size(); i++) {
            Profile profile = profiles.get(i);
            names[i] = profile.name + "\n" + nonEmpty(profile.protocol, "AmneziaWG");
            if (profile.id.equals(selectedId)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("Выберите конфиг")
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    if (ensureEditable()) save(copyProfiles(), profiles.get(which).id);
                    dialog.dismiss();
                }).setNegativeButton("Закрыть", null).show();
    }

    private void manageProfile() {
        if (!ensureEditable() || selected() == null) return;
        final String id = selected().id;
        new AlertDialog.Builder(this).setTitle(selected().name)
                .setItems(new String[]{"Редактировать конфиг", "Переименовать", "Удалить конфиг"}, (dialog, which) -> {
                    if (!ensureEditable() || find(id) == null) return;
                    if (which == 0) editProfileConfig(id);
                    else if (which == 1) renameProfile(id);
                    else new AlertDialog.Builder(this).setTitle("Удалить конфиг?")
                            .setMessage("«" + find(id).name + "» и его правила приложений будут удалены с устройства.")
                            .setPositiveButton("Удалить", (confirm, ignored) -> {
                                if (!ensureEditable()) return;
                                List<Profile> updated = copyProfiles();
                                updated.removeIf(profile -> id.equals(profile.id));
                                save(updated, updated.isEmpty() ? null : updated.get(0).id);
                            }).setNegativeButton("Отмена", null).show();
                }).setNegativeButton("Закрыть", null).show();
    }

    private void editProfileConfig(String id) {
        if (!ensureEditable() || find(id) == null) return;
        startActivityForResult(new Intent(this, ConfigEditorActivity.class)
                .putExtra(ConfigEditorActivity.EXTRA_ID, id), EDIT_CONFIG);
    }
    private void renameProfile(String id) {
        LinearLayout container = column();
        container.setPadding(dp(24), dp(8), dp(24), 0);
        EditText name = input("Название", false);
        name.setText(find(id).name);
        name.selectAll();
        container.addView(name);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Название конфига").setView(container)
                .setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!ensureEditable()) return;
            String value = name.getText().toString().trim();
            if (value.isEmpty() || value.length() > 80) { name.setError("От 1 до 80 символов"); return; }
            List<Profile> updated = copyProfiles();
            for (Profile profile : updated) if (id.equals(profile.id)) profile.name = value;
            if (save(updated, selectedId)) dialog.dismiss();
        }));
        dialog.show();
    }

    private void changeMode(String mode) {
        if (!ensureEditable() || selected() == null) return;
        List<Profile> updated = copyProfiles();
        for (Profile profile : updated) if (selectedId.equals(profile.id)) profile.mode = mode;
        save(updated, selectedId);
    }

    private void pickApps() {
        if (!ensureEditable() || selected() == null) return;
        final String profileId = selectedId;
        final Set<String> draft = new HashSet<>(selected().packages);
        final boolean bypass = "EXCEPT_SELECTED".equals(selected().mode);
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        appsDialog = dialog;
        LinearLayout panel = column();
        panel.setBackgroundColor(BG);
        panel.setPadding(dp(20), dp(20), dp(20), dp(16));
        panel.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(20) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(), dp(16) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout top = row();
        TextView title = text(bypass ? "Напрямую" : "Через VPN", 25, INK, true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = textButton("Отмена");
        top.addView(close);
        close.setOnClickListener(view -> dialog.dismiss());
        panel.addView(top);
        TextView explanation = text(bypass ? "Отметьте приложения, которые должны обходить VPN." : "Отметьте приложения для VPN. Остальные пойдут напрямую.", 13, MUTED, false);
        add(panel, explanation, 4);
        EditText search = input("Поиск по названию или пакету", false);
        search.setTextSize(14);
        search.setCompoundDrawablePadding(dp(8));
        add(panel, search, 15);
        Switch system = new Switch(this);
        system.setText("Показать системные приложения");
        system.setTextSize(13);
        system.setTextColor(INK);
        system.setPadding(0, dp(10), 0, dp(10));
        add(panel, system, 5);
        TextView count = text("Загружаем список приложений…", 12, MUTED, false);
        add(panel, count, 6);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setPadding(0, dp(8), 0, dp(8));
        list.setClipToPadding(false);
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        AppAdapter adapter = new AppAdapter(draft, count);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, rowId) -> adapter.toggle(position));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { adapter.filter(text.toString(), system.isChecked()); }
            @Override public void afterTextChanged(Editable text) { }
        });
        system.setOnCheckedChangeListener((button, checked) -> adapter.filter(search.getText().toString(), checked));
        LinearLayout bottomActions = row();
        Button clear = textButton("Снять выбор");
        bottomActions.addView(clear, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button allVisible = textButton("Выбрать найденные");
        bottomActions.addView(allVisible, new LinearLayout.LayoutParams(0, dp(44), 1));
        panel.addView(bottomActions);
        clear.setOnClickListener(view -> { draft.clear(); adapter.changed(); });
        allVisible.setOnClickListener(view -> { for (AppEntry app : adapter.visible) draft.add(app.packageName); adapter.changed(); });
        Button apply = button("Сохранить выбор", TEAL, Color.WHITE);
        add(panel, apply, 7);
        apply.setOnClickListener(view -> {
            if (!ensureEditable() || find(profileId) == null) return;
            if (!bypass && draft.isEmpty()) { toast("Выберите хотя бы одно приложение для VPN."); return; }
            List<Profile> updated = copyProfiles();
            for (Profile profile : updated) if (profileId.equals(profile.id)) profile.packages = new HashSet<>(draft);
            if (save(updated, selectedId)) { dialog.dismiss(); toast("Правила приложений сохранены"); }
        });
        dialog.setContentView(panel);
        dialog.setOnDismissListener(ignored -> { if (appsDialog == dialog) appsDialog = null; });
        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setStatusBarColor(BG);
            window.setNavigationBarColor(BG);
        }
        dialog.show();
        if (window != null) window.setLayout(-1, -1);
        panel.requestApplyInsets();
        final Set<String> initialSelection = new HashSet<>(draft);
        worker.execute(() -> {
            try {
                List<AppEntry> installed = readApps(initialSelection);
                runOnUiThread(() -> {
                    if (!dialog.isShowing() || isDestroyed()) return;
                    adapter.all.addAll(installed);
                    adapter.filter(search.getText().toString(), system.isChecked());
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    if (!dialog.isShowing() || isDestroyed()) return;
                    count.setText("Не удалось загрузить список. Закройте окно и попробуйте снова.");
                    apply.setEnabled(false);
                    allVisible.setEnabled(false);
                    clear.setEnabled(false);
                });
            }
        });
    }

    private List<AppEntry> readApps(Set<String> saved) {
        PackageManager pm = getPackageManager();
        List<AppEntry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            if (app.packageName.equals(getPackageName()) && !saved.contains(app.packageName)) continue;
            if (pm.checkPermission(Manifest.permission.INTERNET, app.packageName) != PackageManager.PERMISSION_GRANTED && !saved.contains(app.packageName)) continue;
            AppEntry entry = new AppEntry();
            entry.packageName = app.packageName;
            entry.label = app.loadLabel(pm).toString();
            entry.system = (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            try { entry.icon = app.loadIcon(pm); } catch (RuntimeException ignored) { }
            result.add(entry);
            seen.add(entry.packageName);
        }
        for (String packageName : saved) {
            if (seen.contains(packageName)) continue;
            AppEntry entry = new AppEntry();
            entry.packageName = packageName;
            entry.label = "Недоступное приложение";
            entry.missing = true;
            result.add(entry);
        }
        Collator collator = Collator.getInstance(new Locale("ru"));
        result.sort((left, right) -> {
            int selectedFirst = Boolean.compare(saved.contains(right.packageName), saved.contains(left.packageName));
            return selectedFirst != 0 ? selectedFirst : collator.compare(left.label, right.label);
        });
        return result;
    }

    private final class AppAdapter extends BaseAdapter {
        final List<AppEntry> all = new ArrayList<>();
        final List<AppEntry> visible = new ArrayList<>();
        final Set<String> draft;
        final TextView count;
        String query = "";
        boolean showSystem;
        AppAdapter(Set<String> draft, TextView count) { this.draft = draft; this.count = count; }
        void filter(String query, boolean showSystem) {
            this.query = query.trim().toLowerCase(Locale.ROOT);
            this.showSystem = showSystem;
            visible.clear();
            for (AppEntry app : all) {
                // Selected system apps remain visible, so the list never hides an active exception.
                if (app.system && !showSystem && !draft.contains(app.packageName)) continue;
                if (!app.label.toLowerCase(Locale.ROOT).contains(this.query) && !app.packageName.toLowerCase(Locale.ROOT).contains(this.query)) continue;
                visible.add(app);
            }
            changed();
        }
        void toggle(int position) {
            String packageName = visible.get(position).packageName;
            if (!draft.remove(packageName)) draft.add(packageName);
            changed();
        }
        void changed() {
            count.setText("Выбрано: " + draft.size() + "   ·   Показано: " + visible.size());
            notifyDataSetChanged();
        }
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            AppRow holder;
            if (recycled == null) {
                LinearLayout row = row();
                row.setPadding(dp(2), dp(11), 0, dp(11));
                holder = new AppRow();
                holder.icon = new ImageView(MainActivity.this);
                row.addView(holder.icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
                LinearLayout words = column();
                words.setPadding(dp(12), 0, dp(4), 0);
                holder.label = text("", 14, INK, true);
                holder.detail = text("", 10, MUTED, false);
                holder.label.setMaxLines(2);
                holder.detail.setMaxLines(2);
                words.addView(holder.label);
                add(words, holder.detail, 3);
                row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
                holder.check = new CheckBox(MainActivity.this);
                holder.check.setButtonTintList(ColorStateList.valueOf(TEAL));
                holder.check.setFocusable(false);
                holder.check.setClickable(false);
                holder.check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(holder.check);
                row.setTag(holder);
                recycled = row;
            } else holder = (AppRow) recycled.getTag();
            AppEntry app = visible.get(position);
            holder.icon.setImageDrawable(app.icon);
            if (app.icon == null) holder.icon.setImageResource(R.drawable.ic_route);
            holder.label.setText(app.label);
            holder.detail.setText(app.packageName + (app.missing ? " · не установлено" : app.system ? " · системное" : ""));
            holder.check.setChecked(draft.contains(app.packageName));
            recycled.setContentDescription(app.label + ", " + app.packageName + (draft.contains(app.packageName) ? ", выбрано" : ", не выбрано"));
            return recycled;
        }
    }

    private static final class AppEntry {
        String label, packageName;
        Drawable icon;
        boolean system, missing;
    }
    private static final class AppRow {
        ImageView icon;
        TextView label, detail;
        CheckBox check;
    }

    private boolean save(List<Profile> updated, String selection) {
        try {
            store.save(updated);
            profiles.clear();
            profiles.addAll(updated);
            selectedId = selection;
            try { store.setSelectedId(selection); }
            catch (Exception selectionFailure) {
                error("Изменения сохранены, но не удалось запомнить выбранный профиль. При следующем запуске выберите его заново.");
            }
            render();
            return true;
        } catch (Exception e) { error("Не удалось сохранить изменения. Проверьте свободное место на устройстве и повторите попытку."); return false; }
    }

    private List<Profile> copyProfiles() {
        List<Profile> copy = new ArrayList<>();
        for (Profile source : profiles) {
            copy.add(source.copy());
        }
        return copy;
    }

    private Profile selected() { return find(selectedId); }
    private Profile find(String id) {
        for (Profile profile : profiles) if (profile.id.equals(id)) return profile;
        return null;
    }
    private void error(String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle("AWG VPN").setMessage(message).setPositiveButton("Понятно", null).show();
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private static String nonEmpty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value; }
    private static String bytes(long value) {
        if (value < 1024) return value + " Б";
        if (value < 1024 * 1024) return String.format(Locale.ROOT, "%.1f КБ", value / 1024d);
        if (value < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f МБ", value / (1024d * 1024));
        return String.format(Locale.ROOT, "%.1f ГБ", value / (1024d * 1024 * 1024));
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private TextView text(String label, float size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setFontFeatureSettings("kern");
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }
    private LinearLayout card(int color, int padding) {
        LinearLayout layout = column();
        layout.setBackground(rounded(color, 22));
        layout.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        return layout;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(foreground);
        button.setBackgroundTintList(null);
        button.setBackground(rounded(background, 14));
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setStateListAnimator(null);
        return button;
    }
    private Button textButton(String label) {
        Button button = button(label, Color.TRANSPARENT, TEAL);
        button.setTextSize(12);
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setPadding(dp(5), dp(6), dp(5), dp(6));
        return button;
    }
    private EditText input(String hint, boolean multiline) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setTextSize(16);
        field.setSingleLine(!multiline);
        field.setBackgroundTintList(ColorStateList.valueOf(TEAL));
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        if (multiline) field.setGravity(Gravity.TOP | Gravity.START);
        return field;
    }
    private void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }
}
