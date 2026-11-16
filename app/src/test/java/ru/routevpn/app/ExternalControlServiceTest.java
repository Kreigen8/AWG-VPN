package ru.routevpn.app;

import android.content.Intent;
import android.os.Looper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @org.robolectric.annotation.Config(sdk = 35)
public final class ExternalControlServiceTest {
    public static class Service extends ConnectionServiceTest.TestService {
        @Override protected Profile loadProfile(String id) { return "missing".equals(id) ? null : super.loadProfile(id); }
        String diagnostics() { StringWriter text = new StringWriter(); dump(null, new PrintWriter(text), new String[0]); return text.toString(); }
    }
    private Intent request(Service service, String action, String id) {
        Intent value = new Intent().setAction(action).putExtra(RouteVpnService.EXTRA_EXTERNAL_GENERATION,
                new ConnectionPreferences(service).externalControlGeneration());
        if (id != null) value.putExtra(RouteVpnService.EXTRA_PROFILE_ID, id); return value;
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 6_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10);
        }
        assertTrue("Service did not settle", condition.getAsBoolean());
    }
    @Test public void staleCommandCannotConnectAfterCheckboxWasDisabledAndReenabled() throws Exception {
        ServiceController<Service> controller = Robolectric.buildService(Service.class).create();
        try {
            Service service = controller.get(); ConnectionPreferences preferences = new ConnectionPreferences(service);
            preferences.setExternalControl(true); Intent stale = request(service, RouteVpnService.ACTION_EXTERNAL_CONNECT, "alpha");
            preferences.setExternalControl(false); preferences.setExternalControl(true);
            service.onStartCommand(stale, 0, 1);
            assertEquals(0, service.fake.ups); assertFalse(preferences.wanted());
            assertTrue(service.diagnostics().contains("external_command_rejected_disabled"));
        } finally { controller.destroy(); }
    }
    @Test public void authorizedStartSwitchAndStopKeepSelectionAndClearRestoreIntent() throws Exception {
        ServiceController<Service> controller = Robolectric.buildService(Service.class).create();
        try {
            Service service = controller.get(); ConnectionPreferences preferences = new ConnectionPreferences(service);
            preferences.setExternalControl(true);
            service.onStartCommand(request(service, RouteVpnService.ACTION_EXTERNAL_CONNECT, "alpha"), 0, 1);
            await(() -> service.fake.ups == 1 && "alpha".equals(new ProfileStore(service).getSelectedId()));
            service.onStartCommand(request(service, RouteVpnService.ACTION_EXTERNAL_CONNECT, "bravo"), 0, 2);
            await(() -> service.fake.ups == 2 && "bravo".equals(new ProfileStore(service).getSelectedId()));
            assertEquals("bravo", preferences.profileId()); assertTrue(preferences.wanted());
            service.onStartCommand(request(service, RouteVpnService.ACTION_EXTERNAL_DISCONNECT, null), 0, 3);
            await(() -> service.fake.state == org.amnezia.awg.backend.Tunnel.State.DOWN);
            assertFalse(preferences.wanted()); assertTrue(service.diagnostics().contains("external_disconnect"));
            assertFalse(service.diagnostics().contains("manual_disconnect"));
            assertEquals("bravo", new ProfileStore(service).getSelectedId());
        } finally { controller.destroy(); }
    }
    @Test public void invalidSwitchAndStopForAnotherProfileLeaveCurrentTunnelAlone() throws Exception {
        ServiceController<Service> controller = Robolectric.buildService(Service.class).create();
        try {
            Service service = controller.get(); new ConnectionPreferences(service).setExternalControl(true);
            service.onStartCommand(request(service, RouteVpnService.ACTION_EXTERNAL_CONNECT, "alpha"), 0, 1);
            await(() -> service.fake.ups == 1);
            service.onStartCommand(request(service, RouteVpnService.ACTION_EXTERNAL_CONNECT, "missing"), 0, 2);
            await(() -> service.diagnostics().contains("external_connect_rejected_missing_profile"));
            service.onStartCommand(request(service, RouteVpnService.ACTION_EXTERNAL_DISCONNECT, "bravo"), 0, 3);
            assertEquals(1, service.fake.ups); assertTrue(new ConnectionPreferences(service).wanted());
            assertEquals("alpha", new ConnectionPreferences(service).profileId());
            assertEquals(org.amnezia.awg.backend.Tunnel.State.UP, service.fake.state);
        } finally { controller.destroy(); }
    }
}
