package ru.routevpn.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Observes physical internet networks, never the VPN itself (which would cause a restart loop). */
final class UnderlyingNetworkMonitor {
    private final ConnectivityManager manager;
    private final Consumer<Network> listener;
    private final Map<Network, NetworkCapabilities> networks = new HashMap<>();
    private Network selected;
    private boolean registered;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            networks.put(network, capabilities);
            choose();
        }
        @Override public void onLost(Network network) { networks.remove(network); choose(); }
    };

    UnderlyingNetworkMonitor(Context context, Consumer<Network> listener) {
        manager = context.getSystemService(ConnectivityManager.class);
        this.listener = listener;
    }
    void start() {
        if (manager == null || registered) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
        if (Build.VERSION.SDK_INT >= 31)
            manager.registerBestMatchingNetworkCallback(request, callback, new Handler(Looper.getMainLooper()));
        else manager.registerNetworkCallback(request, callback, new Handler(Looper.getMainLooper()));
        registered = true;
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps != null) networks.put(network, caps);
        }
        choose();
    }
    private void choose() {
        Network best = null;
        int score = -1;
        for (Map.Entry<Network, NetworkCapabilities> entry : networks.entrySet()) {
            NetworkCapabilities caps = entry.getValue();
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            int rank = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 10 : 0;
            rank += caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 3
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? 4 : 1;
            if (rank > score || (rank == score && entry.getKey().equals(selected))) {
                best = entry.getKey(); score = rank;
            }
        }
        if (best == null ? selected != null : !best.equals(selected)) {
            selected = best;
            listener.accept(selected);
        }
    }
    void close() {
        if (registered) {
            try { manager.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) { }
        }
        registered = false; networks.clear(); selected = null;
    }
}
