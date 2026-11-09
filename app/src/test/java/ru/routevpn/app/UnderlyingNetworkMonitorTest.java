package ru.routevpn.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @org.robolectric.annotation.Config(sdk = {29, 35})
public final class UnderlyingNetworkMonitorTest {
    private NetworkCapabilities wifi() {
        NetworkCapabilities value = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(value).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        Shadows.shadowOf(value).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        Shadows.shadowOf(value).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED); return value;
    }
    @Test public void onlyCallbackNetworksAreSelectedAndLossClearsSelection() {
        Context context = RuntimeEnvironment.getApplication();
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network ghost = ShadowNetwork.newInstance(91), actual = ShadowNetwork.newInstance(92);
        Shadows.shadowOf(manager).addNetwork(ghost, ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true));
        Shadows.shadowOf(manager).setNetworkCapabilities(ghost, wifi());
        List<Network> changes = new ArrayList<>(); UnderlyingNetworkMonitor monitor = new UnderlyingNetworkMonitor(context, changes::add);
        monitor.start();
        try {
            assertTrue(changes.isEmpty());
            ConnectivityManager.NetworkCallback callback = Shadows.shadowOf(manager).getNetworkCallbacks().iterator().next();
            callback.onCapabilitiesChanged(actual, wifi()); assertEquals(List.of(actual), changes);
            callback.onLost(actual); assertEquals(2, changes.size()); assertNull(changes.get(1));
        } finally { monitor.close(); }
    }
    @Test public void addressChangeOnSameNetworkNotifiesOnceWithoutCapabilityChurn() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        List<Network> changes = new ArrayList<>(); UnderlyingNetworkMonitor monitor = new UnderlyingNetworkMonitor(context, changes::add);
        monitor.start();
        try {
            ConnectivityManager.NetworkCallback callback = Shadows.shadowOf(manager).getNetworkCallbacks().iterator().next();
            Network network = ShadowNetwork.newInstance(93); callback.onCapabilitiesChanged(network, wifi());
            LinkProperties first = new LinkProperties(); first.setInterfaceName("wlan0");
            ReflectionHelpers.callInstanceMethod(first, "addLinkAddress", ClassParameter.from(LinkAddress.class,
                    ReflectionHelpers.callConstructor(LinkAddress.class,
                            ClassParameter.from(java.net.InetAddress.class, java.net.InetAddress.getByName("192.0.2.2")), ClassParameter.from(int.class, 24))));
            callback.onLinkPropertiesChanged(network, first); callback.onCapabilitiesChanged(network, wifi());
            assertEquals(1, changes.size());
            LinkProperties changed = new LinkProperties(); changed.setInterfaceName("wlan0");
            ReflectionHelpers.callInstanceMethod(changed, "addLinkAddress", ClassParameter.from(LinkAddress.class,
                    ReflectionHelpers.callConstructor(LinkAddress.class,
                            ClassParameter.from(java.net.InetAddress.class, java.net.InetAddress.getByName("192.0.2.3")), ClassParameter.from(int.class, 24))));
            callback.onLinkPropertiesChanged(network, changed); callback.onLinkPropertiesChanged(network, changed);
            assertEquals(List.of(network, network), changes);
        } finally { monitor.close(); }
    }
}
