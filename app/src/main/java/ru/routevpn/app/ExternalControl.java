package ru.routevpn.app;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import java.util.List;

/** Permission enforcement belongs to the manifest receiver; this is its setting/command policy. */
class ExternalControl {
    static final String PERMISSION = "ru.routevpn.app.permission.CONTROL_TUNNELS";
    static final String UP = "ru.routevpn.app.action.SET_TUNNEL_UP";
    static final String DOWN = "ru.routevpn.app.action.SET_TUNNEL_DOWN";
    static final String EXTRA_TUNNEL = "tunnel", EXTRA_ID = "profile_id";
    enum Result {
        ACCEPTED(-1), DISABLED(1), BAD_REQUEST(2), PROFILE_NOT_FOUND(3),
        AMBIGUOUS_PROFILE(4), VPN_PERMISSION_REQUIRED(5), BUSY(6), UNAVAILABLE(7);
        final int code;
        Result(int code) { this.code = code; }
    }
    private final Context context;
    ExternalControl(Context context) { this.context = context.getApplicationContext(); }
    protected List<Profile> profiles() { return new ProfileStore(context).load(); }
    protected String selectedId() { return new ProfileStore(context).getSelectedId(); }
    protected boolean permissionGranted() { return VpnService.prepare(context) == null; }
    protected RouteVpnService.State state() { return RouteVpnService.snapshot(); }
    protected void dispatch(Intent command, boolean connect) {
        if (connect) context.startForegroundService(command); else context.startService(command);
    }
    Result handle(Intent request) {
        return handle(request, new ConnectionPreferences(context).externalControlGeneration());
    }
    Result handle(Intent request, long generation) {
        ConnectionPreferences preferences = new ConnectionPreferences(context);
        if (!preferences.externalControl() || generation != preferences.externalControlGeneration()) return Result.DISABLED;
        if (request == null || (!UP.equals(request.getAction()) && !DOWN.equals(request.getAction()))) return Result.BAD_REQUEST;
        boolean up = UP.equals(request.getAction());
        final String id, name;
        try {
            id = selector(request, EXTRA_ID, 128); name = selector(request, EXTRA_TUNNEL, 80);
        } catch (RuntimeException malformed) { return Result.BAD_REQUEST; }
        if (id != null && name != null) return Result.BAD_REQUEST;
        String target = null;
        try {
            if (up && !permissionGranted()) return Result.VPN_PERMISSION_REQUIRED;
            if (up && "DISCONNECTING".equals(state().phase)) return Result.BUSY;
            if (up || id != null || name != null) {
                String wantedId = id != null ? id : name == null ? selectedId() : null;
                for (Profile profile : profiles()) {
                    if (name != null ? name.equals(profile.name) : profile.id.equals(wantedId)) {
                        if (target != null) return Result.AMBIGUOUS_PROFILE;
                        target = profile.id;
                    }
                }
                if (target == null) return Result.PROFILE_NOT_FOUND;
            }
            // A queued command received before the checkbox changed is not revived by re-enabling it.
            if (!preferences.externalControl() || generation != preferences.externalControlGeneration()) return Result.DISABLED;
            Intent command = new Intent(context, RouteVpnService.class)
                    .setAction(up ? RouteVpnService.ACTION_EXTERNAL_CONNECT : RouteVpnService.ACTION_EXTERNAL_DISCONNECT)
                    .putExtra(RouteVpnService.EXTRA_EXTERNAL_GENERATION, generation);
            if (target != null) command.putExtra(RouteVpnService.EXTRA_PROFILE_ID, target);
            dispatch(command, up);
            return Result.ACCEPTED;
        } catch (RuntimeException restrictedOrLocked) {
            // Framework/Keystore exceptions can contain private config details. Never return them.
            return Result.UNAVAILABLE;
        }
    }
    private static String selector(Intent intent, String key, int max) {
        if (!intent.hasExtra(key)) return null;
        String value = intent.getStringExtra(key);
        if (value == null || value.isEmpty() || value.length() > max || value.matches("(?s).*[\\p{Cntrl}].*"))
            throw new IllegalArgumentException();
        return value;
    }
}
