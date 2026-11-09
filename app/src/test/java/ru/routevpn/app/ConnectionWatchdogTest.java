package ru.routevpn.app;

import android.content.Intent;
import android.net.Network;
import android.os.Looper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowNetwork;
import static org.junit.Assert.*;

/** Real lifecycle/worker integration with explicit fake backend and fake network-bound transport. */
@RunWith(RobolectricTestRunner.class) @org.robolectric.annotation.Config(sdk = 35)
public final class ConnectionWatchdogTest {
    public static class HealthService extends ConnectionServiceTest.TestService {
        volatile long now = 1_000_000;
        final AtomicInteger probeCalls = new AtomicInteger();
        volatile CountDownLatch gate;
        @Override protected long elapsedTime() { return now; }
        @Override protected boolean screenInteractive() { return true; }
        @Override protected Network ownVpnNetwork() { return ShadowNetwork.newInstance(501); }
        @Override protected Profile loadProfile(String id) {
            Profile profile = super.loadProfile(id);
            profile.config = profile.config.replace("[Peer]", "DNS = 192.0.2.53\n[Peer]"); return profile;
        }
        @Override protected TunnelWatchdog.Result performHealthProbe(DnsHealthProbe probe) {
            probeCalls.incrementAndGet(); CountDownLatch value = gate;
            if (value != null) try { value.await(2, TimeUnit.SECONDS); } catch (InterruptedException cancelled) { }
            return TunnelWatchdog.Result.FAILURE;
        }
        void check() { requestHealthCheck(false); }
        String diagnostics() { StringWriter text = new StringWriter(); dump(null, new PrintWriter(text), new String[0]); return text.toString(); }
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 6_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10);
        }
        assertTrue("Service did not settle", condition.getAsBoolean());
    }
    private HealthService start(ServiceController<HealthService> controller) throws Exception {
        HealthService service = controller.get(); service.network(ShadowNetwork.newInstance(101));
        service.onStartCommand(new Intent().setAction(RouteVpnService.ACTION_CONNECT).putExtra(RouteVpnService.EXTRA_PROFILE_ID, "profile-1"), 0, 1);
        await(() -> service.fake.ups == 1 && service.diagnostics().contains("tunnel_up")); return service;
    }
    private void failTwice(HealthService service) throws Exception {
        service.now += 15_001; service.check(); await(() -> service.diagnostics().contains("probeFailures=1"));
        service.now += 10_001; service.check(); await(() -> service.diagnostics().contains("probeFailures=2"));
    }
    @Test public void threeFailedBoundProbesRebuildSameProfileAndKeepConnectionIntent() throws Exception {
        ServiceController<HealthService> controller = Robolectric.buildService(HealthService.class).create();
        try {
            HealthService service = start(controller); failTwice(service);
            assertEquals(1, service.fake.ups);
            service.now += 10_001; service.check(); await(() -> service.fake.ups == 2);
            assertTrue(service.diagnostics().contains("watchdogRestarts=1"));
            assertTrue(new ConnectionPreferences(service).wanted());
            assertEquals("profile-1", new ConnectionPreferences(service).profileId());
        } finally { controller.destroy(); }
    }
    @Test public void manualDisconnectCancelsLateFailedProbeAndDoesNotReconnect() throws Exception {
        ServiceController<HealthService> controller = Robolectric.buildService(HealthService.class).create();
        try {
            HealthService service = start(controller); failTwice(service);
            service.gate = new CountDownLatch(1); service.now += 10_001; service.check();
            await(() -> service.probeCalls.get() == 3);
            service.onStartCommand(new Intent().setAction(RouteVpnService.ACTION_DISCONNECT), 0, 2);
            service.gate.countDown(); await(() -> service.fake.state == org.amnezia.awg.backend.Tunnel.State.DOWN);
            assertFalse(new ConnectionPreferences(service).wanted());
            assertEquals(1, service.fake.ups); assertTrue(service.diagnostics().contains("watchdogRestarts=0"));
        } finally { controller.destroy(); }
    }
    @Test public void losingPhysicalNetworkDiscardsPendingProbeFailure() throws Exception {
        ServiceController<HealthService> controller = Robolectric.buildService(HealthService.class).create();
        try {
            HealthService service = start(controller); failTwice(service);
            service.gate = new CountDownLatch(1); service.now += 10_001; service.check();
            await(() -> service.probeCalls.get() == 3); service.network(null); service.gate.countDown();
            await(() -> RouteVpnService.snapshot().message.contains("Сеть недоступна"));
            assertEquals(1, service.fake.ups); assertTrue(service.diagnostics().contains("watchdogRestarts=0"));
        } finally { controller.destroy(); }
    }
    @Test public void disabledAutomaticRecoveryDoesNotLaunchHealthProbes() throws Exception {
        ServiceController<HealthService> controller = Robolectric.buildService(HealthService.class).create();
        try {
            HealthService service = start(controller); new ConnectionPreferences(service).setAutoReconnect(false);
            service.now += 90_000; service.check(); Thread.sleep(200);
            assertEquals(0, service.probeCalls.get()); assertEquals(1, service.fake.ups);
        } finally { controller.destroy(); }
    }
    @Test public void diagnosticsExcludeProfileIdentityAndConfigurationSecrets() throws Exception {
        ServiceController<HealthService> controller = Robolectric.buildService(HealthService.class).create();
        try {
            HealthService service = start(controller); String text = service.diagnostics();
            for (String secret : new String[] {"profile-1", "Synthetic", ConnectionServiceTest.KEY, "192.0.2.1", "192.0.2.53", "PrivateKey", "Endpoint"})
                assertFalse("Diagnostics include secret/identity field", text.contains(secret));
            assertTrue(text.contains("routingMode=ALL")); assertTrue(text.contains("dnsServers=1"));
        } finally { controller.destroy(); }
    }
}
