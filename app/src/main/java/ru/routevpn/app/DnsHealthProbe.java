package ru.routevpn.app;

import org.amnezia.awg.config.Config;
import org.amnezia.awg.config.InetNetwork;
import org.amnezia.awg.config.Peer;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** A root NS query to configured, routed DNS servers; never resolves a user's domain or falls back. */
final class DnsHealthProbe {
    interface SocketBinder { void bind(DatagramSocket socket) throws IOException; }
    private final List<InetAddress> servers;
    private final SocketBinder binder;
    private final int port, timeout;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile DatagramSocket current;

    DnsHealthProbe(List<InetAddress> servers, SocketBinder binder) { this(servers, binder, 53, 2_000); }
    DnsHealthProbe(List<InetAddress> servers, SocketBinder binder, int port, int timeout) {
        this.servers = new ArrayList<>(servers); this.binder = binder; this.port = port; this.timeout = timeout;
    }
    void cancel() { cancelled.set(true); DatagramSocket socket = current; if (socket != null) socket.close(); }
    TunnelWatchdog.Result run() {
        boolean timedOut = false;
        for (InetAddress server : servers) {
            if (cancelled.get()) return TunnelWatchdog.Result.UNAVAILABLE;
            try (DatagramSocket socket = new DatagramSocket()) {
                current = socket;
                if (cancelled.get()) return TunnelWatchdog.Result.UNAVAILABLE;
                // Binding is mandatory and happens before connecting or sending. Failure never uses Wi-Fi.
                binder.bind(socket);
                socket.connect(server, port); socket.setSoTimeout(timeout);
                int id = new SecureRandom().nextInt(65536);
                byte[] request = query(id);
                socket.send(new DatagramPacket(request, request.length));
                byte[] reply = new byte[4096];
                long deadline = System.nanoTime() + timeout * 1_000_000L;
                while (!cancelled.get()) {
                    long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                    if (remaining <= 0) throw new SocketTimeoutException();
                    socket.setSoTimeout((int) Math.max(1, remaining));
                    DatagramPacket packet = new DatagramPacket(reply, reply.length);
                    socket.receive(packet);
                    if (validReply(reply, packet.getLength(), id)) return TunnelWatchdog.Result.SUCCESS;
                }
            } catch (SocketTimeoutException timeoutFailure) { timedOut = true; }
            catch (PortUnreachableException refused) { return TunnelWatchdog.Result.UNAVAILABLE; }
            catch (IOException | SecurityException unavailable) { return TunnelWatchdog.Result.UNAVAILABLE; }
            finally { current = null; }
        }
        return !cancelled.get() && timedOut ? TunnelWatchdog.Result.FAILURE : TunnelWatchdog.Result.UNAVAILABLE;
    }
    static byte[] query(int id) {
        return new byte[] {(byte) (id >>> 8), (byte) id, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1};
    }
    static boolean validReply(byte[] reply, int length, int id) {
        return length >= 12 && length <= reply.length && (reply[0] & 255) == (id >>> 8)
                && (reply[1] & 255) == (id & 255) && (reply[2] & 0x80) != 0;
    }
    static List<InetAddress> routedDns(Config config) {
        List<InetAddress> result = new ArrayList<>();
        for (InetAddress address : config.getInterface().getDnsServers()) {
            boolean routed = false;
            for (Peer peer : config.getPeers()) for (InetNetwork route : peer.getAllowedIps())
                if (contains(route, address)) routed = true;
            if (routed) result.add(address);
            if (result.size() == 2) break;
        }
        return result;
    }
    static boolean contains(InetNetwork route, InetAddress address) {
        byte[] network = route.getAddress().getAddress(), target = address.getAddress();
        if (network.length != target.length) return false;
        int bits = route.getMask();
        for (int i = 0; bits > 0; i++, bits -= 8) {
            int mask = 0xff << Math.max(0, 8 - bits);
            if ((network[i] & mask) != (target[i] & mask)) return false;
        }
        return true;
    }
}
