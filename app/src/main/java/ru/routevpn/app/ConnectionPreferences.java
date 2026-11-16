package ru.routevpn.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Only IDs and connection intent; key material stays in the existing encrypted profile store. */
public final class ConnectionPreferences {
    private final SharedPreferences preferences;
    public ConnectionPreferences(Context context) {
        preferences = context.getSharedPreferences("routevpn_connection", Context.MODE_PRIVATE);
    }
    public boolean autoReconnect() { return preferences.getBoolean("auto_reconnect", true); }
    public boolean externalControl() { return preferences.getBoolean("external_control", false); }
    public long externalControlGeneration() { return preferences.getLong("external_control_generation", 0); }
    public void setExternalControl(boolean enabled) {
        boolean previous = externalControl();
        long generation = externalControlGeneration() + 1;
        if (!preferences.edit().putBoolean("external_control", enabled)
                .putLong("external_control_generation", generation).commit()) {
            preferences.edit().putBoolean("external_control", previous)
                    .putLong("external_control_generation", generation + 1).apply();
            throw new IllegalStateException("Не удалось сохранить разрешение внешнего управления.");
        }
    }
    public void setAutoReconnect(boolean enabled) { preferences.edit().putBoolean("auto_reconnect", enabled).commit(); }
    public boolean wanted() { return preferences.getBoolean("wanted", false); }
    public String profileId() { return preferences.getString("profile_id", ""); }
    public void request(String id) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Выберите конфиг VPN.");
        if (!preferences.edit().putString("profile_id", id).putBoolean("wanted", true).commit())
            throw new IllegalStateException("Не удалось сохранить состояние подключения.");
    }
    public void clearRequest() { preferences.edit().putBoolean("wanted", false).commit(); }
    public boolean shouldRestore() { return autoReconnect() && wanted() && !profileId().isEmpty(); }
}
