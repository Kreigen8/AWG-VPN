package ru.routevpn.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen editor. Raw secrets are kept out of intents, saved-instance bundles and logs. */
public class ConfigEditorActivity extends Activity {
    public static final String EXTRA_ID = "profile_id";
    private static final int INK = 0xff1b2a27, TEAL = 0xff12685e, BG = 0xfff6f5f0;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Profile original;
    private String baseRaw = "";
    private boolean advanced, busy;
    private EditText server, port, dns, mtu, raw;
    private LinearLayout basic;
    private TextView status;
    private Button save, mode, undo, previous;
    private static final class Draft {
        String raw; boolean advanced;
        String server, port, dns, mtu;
        Draft(String raw, boolean advanced) { this.raw = raw; this.advanced = advanced; }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        buildScreen();
        if (android.os.Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::closeEditor);
        String id = getIntent().getStringExtra(EXTRA_ID);
        worker.execute(() -> {
            try {
                Profile found = null;
                for (Profile profile : createStore().load()) if (profile.id.equals(id)) found = profile;
                if (found == null) throw new IllegalStateException();
                Profile loaded = found;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    original = loaded;
                    Draft draft = (Draft) getLastNonConfigurationInstance();
                    baseRaw = draft == null ? loaded.config : draft.raw;
                    advanced = draft != null && draft.advanced;
                    raw.setText(baseRaw); readFields(); showMode(); setBusy(false);
                    if (draft != null && !advanced) {
                        server.setText(draft.server); port.setText(draft.port); dns.setText(draft.dns); mtu.setText(draft.mtu);
                    }
                    status.setText(loaded.name + " · " + loaded.protocol + "\nПравила приложений сохранятся.");
                });
            } catch (RuntimeException unavailable) {
                runOnUiThread(() -> status.setText("Не удалось открыть профиль. Закройте редактор и повторите попытку."));
            }
        });
    }
    protected ProfileStore createStore() { return new ProfileStore(this); }
    private void buildScreen() {
        LinearLayout page = column(); page.setPadding(dp(20), dp(8), dp(20), dp(12)); page.setBackgroundColor(BG);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom()); return insets;
        });
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        Button close = button("Назад"); close.setOnClickListener(view -> closeEditor());
        header.addView(close, new LinearLayout.LayoutParams(-2, dp(48)));
        TextView title = label("Редактор конфига", 20); title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); page.addView(header);
        status = label("Открываем профиль…", 13); status.setPadding(0, dp(12), 0, dp(10)); page.addView(status);
        mode = button("Полный текст .conf"); mode.setTag("editor_mode"); mode.setOnClickListener(view -> switchMode()); page.addView(mode);
        ScrollView form = new ScrollView(this); form.setFillViewport(true);
        LinearLayout body = column(); form.addView(body);
        basic = column(); body.addView(basic);
        server = field(basic, "Сервер (первый [Peer])", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port = field(basic, "Порт", InputType.TYPE_CLASS_NUMBER);
        dns = field(basic, "DNS — адреса через запятую", InputType.TYPE_CLASS_TEXT);
        mtu = field(basic, "MTU — пустое поле использует значение по умолчанию", InputType.TYPE_CLASS_NUMBER);
        raw = new EditText(this); protect(raw);
        raw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        raw.setTypeface(Typeface.MONOSPACE); raw.setTextSize(14); raw.setTextColor(INK); raw.setGravity(Gravity.TOP | Gravity.START);
        raw.setMinLines(16); raw.setTag("editor_raw"); body.addView(raw, new LinearLayout.LayoutParams(-1, -1));
        page.addView(form, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        undo = button("Отменить правки"); undo.setOnClickListener(view -> {
            if (original == null) return;
            new AlertDialog.Builder(this).setMessage("Отменить несохранённые изменения?")
                    .setPositiveButton("Отменить правки", (dialog, which) -> reset(original.config)).setNegativeButton("Продолжить", null).show();
        });
        previous = button("Предыдущая версия"); previous.setOnClickListener(view -> {
            if (original == null || original.previousConfig.isEmpty()) return;
            new AlertDialog.Builder(this).setMessage("Открыть предыдущую сохранённую версию? Для применения нажмите «Сохранить».")
                    .setPositiveButton("Открыть", (dialog, which) -> reset(original.previousConfig)).setNegativeButton("Отмена", null).show();
        });
        actions.addView(undo, new LinearLayout.LayoutParams(0, dp(56), 1));
        actions.addView(previous, new LinearLayout.LayoutParams(0, dp(56), 1)); page.addView(actions);
        save = button("Проверить и сохранить"); save.setTag("editor_save"); save.setTextColor(Color.WHITE); save.setBackgroundTintList(android.content.res.ColorStateList.valueOf(TEAL));
        save.setOnClickListener(view -> saveDraft()); page.addView(save, new LinearLayout.LayoutParams(-1, dp(54)));
        setContentView(page); page.requestApplyInsets(); showMode(); setBusy(true);
    }
    private void protect(EditText field) {
        field.setSaveEnabled(false); field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    }
    private EditText field(LinearLayout parent, String title, int type) {
        TextView text = label(title, 13); text.setPadding(0, dp(18), 0, dp(3)); parent.addView(text);
        EditText field = new EditText(this); field.setSingleLine(true); field.setTextSize(16); field.setTextColor(INK);
        field.setInputType(type | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); protect(field); parent.addView(field);
        return field;
    }
    private void readFields() {
        ConfigFields.Values values = ConfigFields.read(baseRaw);
        server.setText(values.server); port.setText(values.port); dns.setText(values.dns); mtu.setText(values.mtu);
    }
    private String draft() {
        if (advanced) return raw.getText().toString();
        ConfigFields.Values values = new ConfigFields.Values();
        values.server = server.getText().toString(); values.port = port.getText().toString();
        values.dns = dns.getText().toString(); values.mtu = mtu.getText().toString();
        return ConfigFields.apply(baseRaw, values);
    }
    private void switchMode() {
        if (busy || original == null) return;
        try { baseRaw = draft(); }
        catch (IllegalArgumentException invalid) { showError(invalid.getMessage()); return; }
        advanced = !advanced; raw.setText(baseRaw); readFields(); showMode();
    }
    private void showMode() {
        basic.setVisibility(advanced ? View.GONE : View.VISIBLE); raw.setVisibility(advanced ? View.VISIBLE : View.GONE);
        mode.setText(advanced ? "Основные параметры" : "Полный текст .conf");
    }
    private void reset(String config) { baseRaw = config; raw.setText(config); readFields(); }
    private void setBusy(boolean value) {
        busy = value; save.setEnabled(!value); mode.setEnabled(!value); undo.setEnabled(!value);
        previous.setEnabled(!value && original != null && !original.previousConfig.isEmpty());
    }
    private void saveDraft() {
        if (busy || original == null) return;
        if (RouteVpnService.isActive(RouteVpnService.snapshot())) { showError("Сначала отключите VPN."); return; }
        final String config;
        try { config = draft(); ConfigEdits.apply(original, config); }
        catch (IllegalArgumentException invalid) { showError(invalid.getMessage()); return; }
        setBusy(true);
        worker.execute(() -> {
            try {
                ProfileStore store = createStore(); List<Profile> profiles = store.load();
                boolean found = false;
                for (int i = 0; i < profiles.size(); i++) if (original.id.equals(profiles.get(i).id)) {
                    if (!original.config.equals(profiles.get(i).config)) throw new IllegalArgumentException("Профиль уже изменён. Откройте редактор заново.");
                    profiles.set(i, ConfigEdits.apply(profiles.get(i), config)); found = true;
                }
                if (!found) throw new IllegalArgumentException("Профиль больше не существует.");
                if (RouteVpnService.isActive(RouteVpnService.snapshot())) throw new IllegalArgumentException("VPN уже запущен. Сначала отключите его.");
                store.save(profiles);
                runOnUiThread(() -> { setResult(RESULT_OK); finish(); });
            } catch (Exception failure) {
                String message = failure instanceof IllegalArgumentException ? failure.getMessage() : "Не удалось сохранить профиль. Попробуйте снова.";
                runOnUiThread(() -> { if (!isDestroyed()) { setBusy(false); showError(message); } });
            }
        });
    }
    private void showError(String message) { new AlertDialog.Builder(this).setTitle("Проверьте конфиг").setMessage(message).setPositiveButton("Понятно", null).show(); }
    private void closeEditor() {
        if (busy && original != null) return;
        boolean changed = false;
        if (original != null) {
            try { changed = !original.config.equals(draft()); } catch (IllegalArgumentException invalid) { changed = true; }
        }
        if (!changed) { finish(); return; }
        new AlertDialog.Builder(this).setMessage("Закрыть редактор без сохранения изменений?")
                .setPositiveButton("Не сохранять", (dialog, which) -> finish()).setNegativeButton("Продолжить", null).show();
    }
    @Override public void onBackPressed() { closeEditor(); }
    @Override public Object onRetainNonConfigurationInstance() {
        if (original == null) return null;
        // In-memory only, so rotation preserves a draft without writing keys into Android's Bundle.
        Draft draft = new Draft(advanced ? raw.getText().toString() : baseRaw, advanced);
        draft.server = server.getText().toString(); draft.port = port.getText().toString();
        draft.dns = dns.getText().toString(); draft.mtu = mtu.getText().toString();
        return draft;
    }
    @Override protected void onDestroy() { worker.shutdown(); raw.setText(""); super.onDestroy(); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(INK); return view; }
    private Button button(String text) { Button button = new Button(this); button.setText(text); button.setTextSize(13); button.setAllCaps(false); button.setTextColor(TEAL); return button; }
}
