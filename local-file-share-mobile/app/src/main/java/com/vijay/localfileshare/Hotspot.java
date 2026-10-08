package com.vijay.localfileshare;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * The sender's hotspot. Android starts a "local-only" hotspot for the app: it works with
 * Wi-Fi switched off and gives no internet, but Android chooses its name, password and band.
 */
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
class Hotspot {
    interface Listener {
        void onStarted(String ssid, String password);

        void onFailed(String message);

        void onStopped();
    }

    private WifiManager.LocalOnlyHotspotReservation reservation;
    private boolean stopped;

    void start(Context context, Listener listener) {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            listener.onFailed("This phone has no Wi-Fi.");
            return;
        }
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation started) {
                    if (stopped) {
                        started.close();
                        return;
                    }
                    reservation = started;
                    String[] credentials = credentials(started);
                    if (credentials == null) {
                        listener.onFailed("The hotspot started without a usable name or password.");
                    } else {
                        listener.onStarted(credentials[0], credentials[1]);
                    }
                }

                @Override
                public void onStopped() {
                    reservation = null;
                    if (!stopped) listener.onStopped();
                }

                @Override
                public void onFailed(int reason) {
                    if (!stopped) listener.onFailed(describe(reason));
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (RuntimeException error) {
            // Thrown when a hotspot request is already active or a permission is missing.
            listener.onFailed("The hotspot couldn't be started. Close other sharing apps and try again.");
        }
    }

    void stop() {
        stopped = true;
        if (reservation != null) {
            try {
                reservation.close();
            } catch (RuntimeException ignored) {
            }
            reservation = null;
        }
    }

    private static String[] credentials(WifiManager.LocalOnlyHotspotReservation started) {
        String ssid = null;
        String password = null;
        if (Build.VERSION.SDK_INT >= 30) {
            SoftApConfiguration config = started.getSoftApConfiguration();
            ssid = config.getSsid();
            if (ssid == null && Build.VERSION.SDK_INT >= 33 && config.getWifiSsid() != null) {
                ssid = config.getWifiSsid().toString();
            }
            password = config.getPassphrase();
        } else {
            WifiConfiguration config = started.getWifiConfiguration();
            if (config != null) {
                ssid = config.SSID;
                password = config.preSharedKey;
            }
        }
        ssid = unquote(ssid);
        password = unquote(password);
        return ssid == null || ssid.isEmpty() || password == null || password.length() < 8
                ? null : new String[]{ssid, password};
    }

    static String unquote(String value) {
        if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String describe(int reason) {
        switch (reason) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                return "Turn off this phone's own hotspot or tethering, then try again.";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "Hotspots are not allowed on this phone.";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "No free Wi-Fi channel for a hotspot right now. Try again in a moment.";
            default:
                return "The hotspot couldn't be started.";
        }
    }
}
