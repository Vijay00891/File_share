package com.vijay.localfileshare;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * The receiver's side of a hotspot link: joins the sender's hotspot for this app only.
 * Android shows one system confirmation, then all of the app's traffic uses that network.
 */
@TargetApi(29)
class WifiJoin {
    interface Listener {
        void onConnected(String hostAddress);

        void onFailed(String message);

        void onLost();
    }

    private final ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private boolean reported;
    private boolean released;

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            connectivity.bindProcessToNetwork(network);
            LinkProperties properties = connectivity.getLinkProperties(network);
            main.post(() -> resolve(properties));
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            main.post(() -> resolve(properties));
        }

        @Override
        public void onUnavailable() {
            main.post(() -> {
                if (!released && !reported) listener.onFailed("Couldn't join the sender's hotspot. Move closer and try again.");
            });
        }

        @Override
        public void onLost(Network network) {
            main.post(() -> {
                if (released) return;
                if (reported) listener.onLost();
                else listener.onFailed("The connection to the sender dropped.");
            });
        }
    };

    WifiJoin(Context context) {
        connectivity = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    void setListener(Listener next) {
        listener = next;
    }

    void join(String ssid, String password, Listener events) {
        listener = events;
        try {
            WifiNetworkSpecifier.Builder wanted = new WifiNetworkSpecifier.Builder().setSsid(ssid);
            if (password != null && !password.isEmpty()) wanted.setWpa2Passphrase(password);
            WifiNetworkSpecifier specifier = wanted.build();
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build();
            // The wait includes the time the person takes to answer Android's "Connect?" prompt.
            connectivity.requestNetwork(request, callback, 90000);
        } catch (RuntimeException error) {
            events.onFailed("Couldn't start connecting. Check that Wi-Fi is on.");
        }
    }

    void release() {
        released = true;
        main.removeCallbacksAndMessages(null);
        try {
            connectivity.unregisterNetworkCallback(callback);
        } catch (RuntimeException ignored) {
        }
        connectivity.bindProcessToNetwork(null);
    }

    private void resolve(LinkProperties properties) {
        if (released || reported || properties == null) return;
        String host = hostAddress(properties);
        if (host == null) return;
        reported = true;
        listener.onConnected(host);
    }

    /** The sender is the hotspot itself, so its address is this network's gateway. */
    private static String hostAddress(LinkProperties properties) {
        for (RouteInfo route : properties.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (route.isDefaultRoute() && gateway instanceof Inet4Address && !gateway.isAnyLocalAddress()) {
                return gateway.getHostAddress();
            }
        }
        if (Build.VERSION.SDK_INT >= 30 && properties.getDhcpServerAddress() != null) {
            return properties.getDhcpServerAddress().getHostAddress();
        }
        for (LinkAddress link : properties.getLinkAddresses()) {
            if (link.getAddress() instanceof Inet4Address) return gatewayGuess(link.getAddress().getHostAddress());
        }
        return null;
    }

    /** Hotspots hand out addresses from x.y.z.1, so fall back to that when no route says so. */
    static String gatewayGuess(String ownAddress) {
        int dot = ownAddress.lastIndexOf('.');
        return dot < 0 ? null : ownAddress.substring(0, dot) + ".1";
    }
}
