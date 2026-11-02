package ru.routevpn.app;

import android.content.Context;
import android.service.quicksettings.Tile;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = {29, 35})
public final class VpnTileServiceTest {
    @Test public void tileIsInactiveWhenDisconnectedAndDoesNotReadEncryptedProfiles() {
        ServiceController<VpnTileService> controller = Robolectric.buildService(VpnTileService.class).create();
        try {
            VpnTileService service = controller.get();
            service.onStartListening();
            assertEquals("AWG VPN", service.getQsTile().getLabel());
            assertEquals(Tile.STATE_INACTIVE, service.getQsTile().getState());
            Context context = RuntimeEnvironment.getApplication();
            assertFalse(new java.io.File(context.getNoBackupFilesDir(), "profiles.enc").exists());
        } finally {
            // Robolectric 4.16's ShadowTileService is not a ShadowService; Android's base
            // onDestroy type-casts that shadow and fails. This test covers tile state only.
            controller.get().onStopListening();
        }
    }
}
