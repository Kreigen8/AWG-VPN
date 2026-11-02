package ru.routevpn.app;

import android.app.Service;
import android.content.Intent;
import android.net.Network;
import android.os.Looper;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.amnezia.awg.backend.Backend;
import org.amnezia.awg.backend.Statistics;
import org.amnezia.awg.backend.StatusCallback;
import org.amnezia.awg.backend.Tunnel;
import org.amnezia.awg.config.Config;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.android.controller.ServiceController;
import static org.junit.Assert.*;

/** Real service lifecycle with an explicit backend fake; no claim of actual VPN packet forwarding. */
@RunWith(RobolectricTestRunner.class) @org.robolectric.annotation.Config(sdk = 35)
public final class ConnectionServiceTest {
    static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    static Profile profile() {
        return ConfigPolicy.parseImported("Synthetic", "[Interface]\nPrivateKey = " + KEY
                + "\nAddress = 10.99.0.2/32\n[Peer]\nPublicKey = " + KEY
                + "\nEndpoint = 192.0.2.1:51820\nAllowedIPs = 0.0.0.0/0\n");
    }
    public static class TestService extends RouteVpnService {
        final FakeBackend fake = new FakeBackend();
        boolean allowed = true;
        @Override protected Backend createBackend() { return fake; }
        @Override protected boolean permissionGranted() { return allowed; }
        @Override protected Profile loadProfile(String id) { Profile profile = profile(); profile.id = id; return profile; }
        @Override protected Set<String> installedPackages() { return Collections.emptySet(); }
        void network(Network value) { onUnderlyingNetworkChanged(value); }
    }
    static class FakeBackend implements Backend {
        volatile Tunnel.State state = Tunnel.State.DOWN;
        volatile Tunnel tunnel;
        volatile int ups;
        final CopyOnWriteArrayList<Tunnel.State> calls = new CopyOnWriteArrayList<>();
        @Override public Set<String> getRunningTunnelNames() { return Collections.emptySet(); }
        @Override public Tunnel.State getState(Tunnel tunnel) { return state; }
        @Override public long getLastHandshake(Tunnel tunnel) { return 0; }
        @Override public Statistics getStatistics(Tunnel tunnel) { return org.robolectric.util.ReflectionHelpers.callConstructor(Statistics.class); }
        @Override public String getVersion() { return "test-only"; }
        @Override public void setStatusCallback(StatusCallback callback) { }
        @Override public Tunnel.State setState(Tunnel tunnel, Tunnel.State value, Config config) {
            this.tunnel = tunnel; state = value; calls.add(value); if (value == Tunnel.State.UP) ups++;
            tunnel.onStateChange(value); return value;
        }
    }
    private Intent connect() { return new Intent().setAction(RouteVpnService.ACTION_CONNECT).putExtra(RouteVpnService.EXTRA_PROFILE_ID, "profile-1"); }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 6_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10);
        }
        assertTrue("Background service did not settle", condition.getAsBoolean());
    }
    @Test public void duplicateConnectDoesNotCreateSecondTunnel() throws Exception {
        ServiceController<TestService> controller = Robolectric.buildService(TestService.class).create();
        try {
            TestService service = controller.get(); assertEquals(Service.START_STICKY, service.onStartCommand(connect(), 0, 1));
            await(() -> service.fake.ups == 1); service.onStartCommand(connect(), 0, 2);
            assertEquals(1, service.fake.ups); assertEquals("CONNECTING", RouteVpnService.snapshot().phase);
            assertTrue(new ConnectionPreferences(service).shouldRestore());
        } finally { controller.destroy(); }
    }
    @Test public void networkSwitchRebuildsAndManualStopCancelsQueuedRebuild() throws Exception {
        ServiceController<TestService> controller = Robolectric.buildService(TestService.class).create();
        try {
            TestService service = controller.get(); service.onStartCommand(connect(), 0, 1); await(() -> service.fake.ups == 1);
            service.network(ShadowNetwork.newInstance(101)); await(() -> service.fake.ups == 2);
            assertTrue(new ConnectionPreferences(service).wanted());
            service.network(ShadowNetwork.newInstance(102));
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(new Intent().setAction(RouteVpnService.ACTION_DISCONNECT), 0, 2));
            await(() -> service.fake.state == Tunnel.State.DOWN);
            Thread.sleep(1400);
            assertEquals(2, service.fake.ups); assertFalse(new ConnectionPreferences(service).shouldRestore());
        } finally { controller.destroy(); }
    }
    @Test public void nullStickyIntentRestoresThePreviouslyRequestedProfile() throws Exception {
        ServiceController<TestService> controller = Robolectric.buildService(TestService.class).create();
        try {
            TestService service = controller.get(); new ConnectionPreferences(service).request("previous-profile");
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1)); await(() -> service.fake.ups == 1);
            assertEquals("previous-profile", RouteVpnService.snapshot().id);
        } finally { controller.destroy(); }
    }
    @Test public void revokedPermissionPreventsBackgroundReconnect() throws Exception {
        ServiceController<TestService> controller = Robolectric.buildService(TestService.class).create();
        try {
            TestService service = controller.get(); service.onStartCommand(connect(), 0, 1); await(() -> service.fake.ups == 1);
            service.fake.tunnel.onStateChange(Tunnel.State.DOWN);
            assertFalse(new ConnectionPreferences(service).wanted()); assertEquals("DISCONNECTING", RouteVpnService.snapshot().phase);
            service.network(ShadowNetwork.newInstance(103)); Thread.sleep(1000); assertEquals(1, service.fake.ups);
        } finally { controller.destroy(); }
    }
    @Test public void retryBackoffIsBounded() {
        assertEquals(2, RouteVpnService.retryDelaySeconds(0)); assertEquals(4, RouteVpnService.retryDelaySeconds(1));
        assertEquals(60, RouteVpnService.retryDelaySeconds(100));
    }
}
