package ru.routevpn.app;

import android.app.Application;
import org.amnezia.awg.backend.GoBackend;

/** The same Always-on callback entry point used by the official AmneziaWG Application. */
public final class VpnApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        GoBackend.setAlwaysOnCallback(() -> RouteVpnService.restore(this, true));
    }
}
