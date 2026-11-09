package ru.routevpn.app;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.amnezia.awg.config.InetNetwork;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

/** UDP codec/socket tests use loopback explicitly; they do not model Android VPN routing. */
@RunWith(RobolectricTestRunner.class) @org.robolectric.annotation.Config(sdk = 35)
public final class DnsHealthProbeTest {
    private InetAddress loopback() throws Exception { return InetAddress.getByName("127.0.0.1"); }
    @Test public void rootQueryRejectsUnrelatedReplyThenAcceptsMatchingDnsError() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (DatagramSocket server = new DatagramSocket(0, loopback())) {
            server.setSoTimeout(2_000);
            Future<Boolean> exchange = worker.submit(() -> {
                DatagramPacket request = new DatagramPacket(new byte[100], 100); server.receive(request);
                assertEquals(17, request.getLength()); assertEquals(0, request.getData()[12]); // root, no user's domain
                assertEquals(2, request.getData()[14]); assertEquals(1, request.getData()[16]);
                byte[] wrong = request.getData().clone(); wrong[0] ^= 1; wrong[2] |= (byte) 0x80;
                server.send(new DatagramPacket(wrong, 17, request.getSocketAddress()));
                byte[] reply = request.getData().clone(); reply[2] |= (byte) 0x80; reply[3] = 2; // SERVFAIL still proves a reply traversed the tunnel
                server.send(new DatagramPacket(reply, 17, request.getSocketAddress())); return true;
            });
            DnsHealthProbe probe = new DnsHealthProbe(List.of(loopback()), socket -> { }, server.getLocalPort(), 1_000);
            assertEquals(TunnelWatchdog.Result.SUCCESS, probe.run()); assertTrue(exchange.get(3, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
    }
    @Test public void failedBindingDoesNotSendThroughDefaultNetwork() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, loopback())) {
            server.setSoTimeout(150);
            DnsHealthProbe probe = new DnsHealthProbe(List.of(loopback()), socket -> { throw new IOException("Synthetic binding denial"); }, server.getLocalPort(), 100);
            assertEquals(TunnelWatchdog.Result.UNAVAILABLE, probe.run());
            try { server.receive(new DatagramPacket(new byte[100], 100)); fail("Probe leaked onto the default network"); }
            catch (SocketTimeoutException expected) { }
        }
    }
    @Test public void actualTimeoutIsFailureAndEmptyTargetsAreUnavailable() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, loopback())) {
            DnsHealthProbe probe = new DnsHealthProbe(List.of(loopback()), socket -> { }, server.getLocalPort(), 100);
            assertEquals(TunnelWatchdog.Result.FAILURE, probe.run());
        }
        assertEquals(TunnelWatchdog.Result.UNAVAILABLE, new DnsHealthProbe(Collections.emptyList(), socket -> { throw new AssertionError(); }).run());
    }
    @Test public void cancellationClosesWaitingSocketInsteadOfWaitingForTimeout() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(); CountDownLatch bound = new CountDownLatch(1);
        try (DatagramSocket server = new DatagramSocket(0, loopback())) {
            DnsHealthProbe probe = new DnsHealthProbe(List.of(loopback()), socket -> bound.countDown(), server.getLocalPort(), 5_000);
            Future<TunnelWatchdog.Result> result = worker.submit(probe::run);
            assertTrue(bound.await(1, TimeUnit.SECONDS)); probe.cancel();
            assertEquals(TunnelWatchdog.Result.UNAVAILABLE, result.get(1, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
    }
    @Test public void routeMatchingCoversBothFamiliesAndPartialPrefixes() throws Exception {
        assertTrue(DnsHealthProbe.contains(InetNetwork.parse("192.0.2.0/25"), InetAddress.getByName("192.0.2.127")));
        assertFalse(DnsHealthProbe.contains(InetNetwork.parse("192.0.2.0/25"), InetAddress.getByName("192.0.2.128")));
        assertTrue(DnsHealthProbe.contains(InetNetwork.parse("2001:db8::/33"), InetAddress.getByName("2001:db8:1::1")));
        assertFalse(DnsHealthProbe.contains(InetNetwork.parse("2001:db8::/33"), InetAddress.getByName("2001:db8:8000::1")));
        assertFalse(DnsHealthProbe.contains(InetNetwork.parse("0.0.0.0/0"), InetAddress.getByName("2001:db8::1")));
    }
    @Test public void onlyDnsAddressesCoveredByPeerRoutesCanBeProbed() throws Exception {
        String key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        Profile profile = ConfigPolicy.parseImported("Synthetic", "[Interface]\nPrivateKey = " + key
                + "\nAddress = 192.0.2.2/32\nDNS = 192.0.2.53, 198.51.100.53\n[Peer]\nPublicKey = " + key
                + "\nEndpoint = 203.0.113.1:51820\nAllowedIPs = 192.0.2.0/24\n");
        assertEquals(List.of(InetAddress.getByName("192.0.2.53")), DnsHealthProbe.routedDns(ConfigPolicy.build(profile, Collections.emptySet())));
    }
}
