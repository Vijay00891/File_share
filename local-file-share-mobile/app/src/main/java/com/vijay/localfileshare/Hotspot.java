package com.vijay.localfileshare;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseIntArray;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Executor;

/**
 * The sender's hotspot. Android starts a "local-only" hotspot for the app: it works with Wi-Fi
 * switched off and gives no internet. Android always chooses its name.
 *
 * Band: Android 16 added a public way to ask for 5 GHz. Before that the band cannot be chosen
 * and stock Android uses 2.4 GHz. A hotspot started with a chosen band has no password,
 * because the public API offers no way to set one.
 */
@TargetApi(29)
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
class Hotspot {
    interface Listener {
        /** password is empty when the hotspot is open. */
        void onStarted(String ssid, String password, boolean fiveGhz);

        void onFailed(String message);

        void onStopped();
    }

    /** First Android version where an app may choose the hotspot's band. */
    static final int BAND_CHOICE_SDK = 36;
    // android.net.wifi.SoftApConfiguration.BAND_5GHZ, public from Android 16.
    private static final int BAND_5GHZ = 1 << 1;
    private static final int MAX_BUSY_RETRIES = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiManager wifi;
    private Listener listener;
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private boolean askFiveGhz;
    private boolean stopped;
    private int busyRetries;
    /** Changes whenever a new start attempt begins, so answers to older attempts are ignored. */
    private int attempt;

    static boolean canChooseBand() {
        return Build.VERSION.SDK_INT >= BAND_CHOICE_SDK;
    }

    void start(Context context, boolean fiveGhz, Listener events) {
        listener = events;
        wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            events.onFailed("This phone has no Wi-Fi.");
            return;
        }
        askFiveGhz = fiveGhz && canChooseBand();
        begin();
    }

    void stop() {
        stopped = true;
        attempt++;
        main.removeCallbacksAndMessages(null);
        if (reservation != null) {
            try {
                reservation.close();
            } catch (RuntimeException ignored) {
            }
            reservation = null;
        }
    }

    private void begin() {
        if (stopped) return;
        final int mine = ++attempt;
        final boolean fiveGhz = askFiveGhz;
        WifiManager.LocalOnlyHotspotCallback callback = new WifiManager.LocalOnlyHotspotCallback() {
            @Override
            public void onStarted(WifiManager.LocalOnlyHotspotReservation started) {
                if (stopped || mine != attempt) {
                    started.close();
                    return;
                }
                reservation = started;
                String[] credentials = credentials(started);
                if (credentials == null) {
                    started.close();
                    reservation = null;
                    listener.onFailed("The hotspot started without a usable name or password.");
                } else {
                    listener.onStarted(credentials[0], credentials[1], fiveGhz);
                }
            }

            @Override
            public void onStopped() {
                if (stopped || mine != attempt) return;
                reservation = null;
                listener.onStopped();
            }

            @Override
            public void onFailed(int reason) {
                if (stopped || mine != attempt) return;
                if (fiveGhz) {
                    // This phone or region refused 5 GHz: use the standard hotspot instead.
                    askFiveGhz = false;
                    main.postDelayed(Hotspot.this::begin, 600);
                } else {
                    listener.onFailed(describe(reason));
                }
            }
        };

        try {
            if (fiveGhz) {
                startOnBand(BAND_5GHZ, callback);
            } else {
                wifi.startLocalOnlyHotspot(callback, main);
            }
        } catch (IllegalStateException busy) {
            // An earlier request from this app is still being torn down.
            if (busyRetries++ < MAX_BUSY_RETRIES) {
                main.postDelayed(this::begin, 1500);
            } else {
                listener.onFailed("The hotspot is busy. Wait a few seconds and try again.");
            }
        } catch (Exception error) {
            if (fiveGhz) {
                askFiveGhz = false;
                main.post(this::begin);
            } else {
                listener.onFailed("The hotspot couldn't be started. Check that the app has its permissions.");
            }
        }
    }

    /**
     * Calls Android 16's WifiManager.startLocalOnlyHotspotWithConfiguration with a band.
     * Done by name because the app is built against the Android 15 SDK; both are public APIs.
     */
    private void startOnBand(int band, WifiManager.LocalOnlyHotspotCallback callback) throws Exception {
        Class<?> builderClass = Class.forName("android.net.wifi.SoftApConfiguration$Builder");
        Object builder = builderClass.getConstructor().newInstance();
        SparseIntArray channels = new SparseIntArray(1);
        channels.put(band, 0); // 0 lets Android pick the best channel in that band
        builderClass.getMethod("setChannels", SparseIntArray.class).invoke(builder, channels);
        Object config = builderClass.getMethod("build").invoke(builder);
        Executor executor = main::post;
        try {
            WifiManager.class.getMethod("startLocalOnlyHotspotWithConfiguration",
                            Class.forName("android.net.wifi.SoftApConfiguration"), Executor.class,
                            WifiManager.LocalOnlyHotspotCallback.class)
                    .invoke(wifi, config, executor, callback);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception) throw (Exception) wrapped.getCause();
            throw wrapped;
        }
    }

    /** Returns {name, password}; the password is "" for an open hotspot. Null when unusable. */
    private static String[] credentials(WifiManager.LocalOnlyHotspotReservation started) {
        String ssid = null;
        String password = null;
        boolean open = false;
        if (Build.VERSION.SDK_INT >= 30) {
            SoftApConfiguration config = started.getSoftApConfiguration();
            ssid = config.getSsid();
            if (ssid == null && Build.VERSION.SDK_INT >= 33 && config.getWifiSsid() != null) {
                ssid = config.getWifiSsid().toString();
            }
            password = config.getPassphrase();
            open = config.getSecurityType() == SoftApConfiguration.SECURITY_TYPE_OPEN;
        } else {
            WifiConfiguration config = started.getWifiConfiguration();
            if (config != null) {
                ssid = config.SSID;
                password = config.preSharedKey;
            }
        }
        return usable(unquote(ssid), unquote(password), open);
    }

    static String[] usable(String ssid, String password, boolean open) {
        if (ssid == null || ssid.isEmpty()) return null;
        if (open && (password == null || password.isEmpty())) return new String[]{ssid, ""};
        if (password == null || password.length() < 8) return null;
        return new String[]{ssid, password};
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
