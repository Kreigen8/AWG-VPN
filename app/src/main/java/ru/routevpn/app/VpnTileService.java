package ru.routevpn.app;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings follows the selected profile and requires unlock before changing VPN state. */
public final class VpnTileService extends TileService {
    public static final String EXTRA_CONNECT = "tile_connect";
    public static void refresh(Context context) {
        try { requestListeningState(context, new ComponentName(context, VpnTileService.class)); }
        catch (RuntimeException ignored) { }
    }
    @Override public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        RouteVpnService.State state = RouteVpnService.snapshot();
        tile.setLabel("AWG VPN");
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_vpn_tile));
        tile.setState("DISCONNECTING".equals(state.phase) ? Tile.STATE_UNAVAILABLE
                : RouteVpnService.isActive(state) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle("CONNECTED".equals(state.phase) ? "Подключён"
                : "CONNECTING".equals(state.phase) ? "Подключение…" : "Выбранный профиль");
        tile.setContentDescription("AWG VPN. " + state.message);
        tile.updateTile();
    }
    @Override public void onClick() { super.onClick(); unlockAndRun(this::toggle); }
    private void toggle() {
        RouteVpnService.State state = RouteVpnService.snapshot();
        if ("DISCONNECTING".equals(state.phase)) return;
        if (RouteVpnService.isActive(state)) {
            try { startService(new Intent(this, RouteVpnService.class).setAction(RouteVpnService.ACTION_DISCONNECT)); }
            catch (RuntimeException restricted) { openApp(false); }
        } else {
            String id = new ProfileStore(this).getSelectedId();
            if (id == null || id.isEmpty() || VpnService.prepare(this) != null) { openApp(true); return; }
            try {
                startForegroundService(new Intent(this, RouteVpnService.class)
                        .setAction(RouteVpnService.ACTION_CONNECT).putExtra(RouteVpnService.EXTRA_PROFILE_ID, id));
            } catch (RuntimeException restricted) { openApp(true); }
        }
        onStartListening();
    }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // Intent overload is required below API 34.
    private void openApp(boolean connect) {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_CONNECT, connect);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 20, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        else startActivityAndCollapse(intent);
    }
}
