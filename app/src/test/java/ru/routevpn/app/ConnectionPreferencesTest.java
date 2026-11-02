package ru.routevpn.app;

import android.content.Context;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = {29, 35})
public final class ConnectionPreferencesTest {
    @Test public void manualStopSurvivesNewInstanceAndBootDoesNotReconnect() {
        Context context = RuntimeEnvironment.getApplication();
        ConnectionPreferences preferences = new ConnectionPreferences(context);
        assertFalse(preferences.shouldRestore()); preferences.request("saved-profile"); assertTrue(preferences.shouldRestore());
        preferences.clearRequest();
        new RestoreReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertFalse(new ConnectionPreferences(context).shouldRestore());
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }
    @Test public void updateAndBootRestoreOnlyWhenExplicitConnectionWasRequested() {
        Context context = RuntimeEnvironment.getApplication(); new ConnectionPreferences(context).request("saved-profile");
        new RestoreReceiver().onReceive(context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        Intent started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
        assertNotNull(started); assertEquals(RouteVpnService.ACTION_RESTORE, started.getAction());
        assertEquals("saved-profile", started.getStringExtra(RouteVpnService.EXTRA_PROFILE_ID));
    }
    @Test public void disablingRecoveryDoesNotEraseProfilesOrUserIntent() {
        ConnectionPreferences preferences = new ConnectionPreferences(RuntimeEnvironment.getApplication());
        preferences.request("saved-profile"); preferences.setAutoReconnect(false);
        assertFalse(preferences.shouldRestore()); assertEquals("saved-profile", preferences.profileId());
        preferences.setAutoReconnect(true); assertTrue(preferences.shouldRestore());
    }
}
