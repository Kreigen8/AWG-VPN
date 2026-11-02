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
