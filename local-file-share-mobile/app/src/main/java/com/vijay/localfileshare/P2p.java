package com.vijay.localfileshare;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phone-to-phone link over Wi-Fi Direct.
 *
 * The sender creates a group (it behaves like a private hotspot) on 2.4 or 5 GHz.
 * The receiver finds senders nearby and joins the chosen one without any prompt:
 * the group password is derived from the group name, so knowing the name is enough.
 * That makes joining easy, not secret: anyone nearby running this app can join.
 */
@TargetApi(29)
@SuppressLint("MissingPermission")
class P2p {
    static final String PREFIX = Target.DIRECT_PREFIX;
    private static final String SERVICE_TYPE = "_localshare._tcp";

    /** Callbacks arrive on the main thread. */
    static class Events {
        void onHostReady(String networkName, String password, boolean fiveGhz) {
        }

        void onClients(int count) {
        }

        void onFound(String networkName, String displayName) {
        }

        void onConnected(String hostAddress) {
        }

        void onLost() {
        }

        void onError(String message) {
        }
    }

    private enum Mode { IDLE, HOST, FIND }

    private final Context context;
    private final WifiP2pManager manager;
    private final WifiManager wifi;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> seen = new HashSet<>();
    private WifiP2pManager.Channel channel;
    private Events events = new Events();
    private Mode mode = Mode.IDLE;
    private boolean receiverRegistered;

    private String networkName;
    private String hostName;
    private boolean wantFiveGhz;
    private boolean hostReady;
    private boolean triedFallback;
    private int clientCount;
    private int hostPolls;

    private String joining;
    private boolean connected;

    static boolean supported(Context context) {
        return Build.VERSION.SDK_INT >= 29
                && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
    }

    P2p(Context context) {
        this.context = context.getApplicationContext();
        manager = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
        wifi = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        if (manager != null) channel = manager.initialize(this.context, Looper.getMainLooper(), null);
    }

    boolean fiveGhzSupported() {
        return wifi != null && wifi.is5GHzBandSupported();
    }

    // ---- Sender ----

    void startHost(String deviceName, boolean fiveGhz, Events listener) {
        if (!begin(Mode.HOST, listener)) return;
        hostName = Target.cleanName(deviceName);
        networkName = PREFIX + hostName;
        wantFiveGhz = fiveGhz;
        hostReady = false;
        triedFallback = false;
        clientCount = 0;
        hostPolls = 0;
        // Clear any group left over from an earlier session before creating ours.
        manager.removeGroup(channel, after(() -> handler.postDelayed(this::createGroup, 500)));
    }

    private void createGroup() {
        if (mode != Mode.HOST) return;
        WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(networkName)
                .setPassphrase(Target.directPassword(networkName))
                .setGroupOperatingBand(wantFiveGhz
                        ? WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                        : WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .enablePersistentMode(false)
                .build();
        manager.createGroup(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                handler.postDelayed(P2p.this::checkGroupFormed, 7000);
            }

            @Override
            public void onFailure(int reason) {
                fallbackOrFail(reason);
            }
        });
    }

    private void checkGroupFormed() {
        if (mode == Mode.HOST && !hostReady) fallbackOrFail(WifiP2pManager.ERROR);
    }

    // 5 GHz can be refused by the phone or by regional rules; retry once on 2.4 GHz.
    private void fallbackOrFail(int reason) {
        if (mode != Mode.HOST || hostReady) return;
        if (wantFiveGhz && !triedFallback) {
            triedFallback = true;
            wantFiveGhz = false;
            manager.removeGroup(channel, after(() -> handler.postDelayed(this::createGroup, 500)));
        } else {
            events.onError(describe(reason));
        }
    }

    private void refreshGroup() {
        if (mode != Mode.HOST) return;
        manager.requestGroupInfo(channel, group -> {
            if (mode != Mode.HOST || group == null || !group.isGroupOwner()) return;
            if (!hostReady) {
                hostReady = true;
                advertise();
                events.onHostReady(group.getNetworkName(), group.getPassphrase(), group.getFrequency() > 4000);
            }
            clientCount = group.getClientList().size();
            events.onClients(clientCount);
        });
    }

    /** Publishes the group name so receivers can find it without scanning Wi-Fi networks. */
    private void advertise() {
        Map<String, String> record = new HashMap<>();
        record.put("ssid", networkName);
        record.put("name", hostName);
        WifiP2pDnsSdServiceInfo info = WifiP2pDnsSdServiceInfo.newInstance(hostName, SERVICE_TYPE, record);
        manager.clearLocalServices(channel, after(() -> {
            if (mode != Mode.HOST) return;
            manager.addLocalService(channel, info, after(null));
            manager.discoverPeers(channel, after(null));
        }));
    }

    /** Called every couple of seconds while the sender screen is open. */
    void pollHost() {
        refreshGroup();
        // Peer discovery times out after a while; renew it so new receivers can still find us.
        if (mode == Mode.HOST && hostReady && clientCount == 0 && ++hostPolls % 20 == 0) {
            manager.discoverPeers(channel, after(null));
        }
    }

    // ---- Receiver ----

    void startFind(Events listener) {
        if (!begin(Mode.FIND, listener)) return;
        seen.clear();
        joining = null;
        connected = false;
        manager.setDnsSdResponseListeners(channel,
                (instanceName, registrationType, device) -> {
                },
                (fullDomainName, record, device) -> {
                    if (fullDomainName == null || !fullDomainName.contains("_localshare")) return;
                    String ssid = record.get("ssid");
                    if (ssid != null && ssid.startsWith(PREFIX)) found(ssid, record.get("name"));
                });
        manager.removeGroup(channel, after(() -> handler.post(this::findLoop)));
    }

    private void findLoop() {
        if (mode != Mode.FIND || joining != null) return;
        manager.clearServiceRequests(channel, after(() -> {
            if (mode != Mode.FIND || joining != null) return;
            manager.addServiceRequest(channel, WifiP2pDnsSdServiceRequest.newInstance(),
                    after(() -> manager.discoverServices(channel, after(null))));
        }));
        scanWifi();
        handler.postDelayed(this::findLoop, 12000);
    }

    // Second way to spot senders: their group shows up as a Wi-Fi network named DIRECT-LS-<name>.
    private void scanWifi() {
        if (wifi == null || !canReadScanResults()) return;
        try {
            wifi.startScan();
            readScanResults();
        } catch (SecurityException ignored) {
        }
    }

    private void readScanResults() {
        if (mode != Mode.FIND || !canReadScanResults()) return;
        try {
            List<ScanResult> results = wifi.getScanResults();
            if (results == null) return;
            for (ScanResult result : results) {
                String ssid = result.SSID;
                if (ssid != null && ssid.startsWith(PREFIX)) found(ssid, null);
            }
        } catch (SecurityException ignored) {
        }
    }

    private boolean canReadScanResults() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void found(String ssid, String name) {
        if (mode != Mode.FIND || !seen.add(ssid)) return;
        events.onFound(ssid, name != null && !name.isEmpty() ? name : ssid.substring(PREFIX.length()));
    }

    void connect(String ssid) {
        if (mode != Mode.FIND || joining != null) return;
        joining = ssid;
        connected = false;
        manager.clearServiceRequests(channel, after(null));
        WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(Target.directPassword(ssid))
                .build();
        handler.postDelayed(this::joinPoll, 1500);
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                handler.postDelayed(() -> {
                    if (mode == Mode.FIND && ssid.equals(joining) && !connected) {
                        manager.cancelConnect(channel, after(null));
                        joinFailed("Could not connect. Move the phones closer and try again.");
                    }
                }, 35000);
            }

            @Override
            public void onFailure(int reason) {
                joinFailed(describe(reason));
            }
        });
    }

    // The connection broadcast is the main signal; polling covers phones that deliver it late.
    private void joinPoll() {
        if (mode != Mode.FIND || joining == null) return;
        refreshConnection();
        handler.postDelayed(this::joinPoll, 1500);
    }

    private void joinFailed(String message) {
        if (mode != Mode.FIND) return;
        joining = null;
        seen.clear();
        events.onError(message);
        handler.postDelayed(this::findLoop, 1500);
    }

    private void refreshConnection() {
        if (mode != Mode.FIND || joining == null) return;
        manager.requestConnectionInfo(channel, info -> {
            if (mode != Mode.FIND || joining == null) return;
            boolean up = info != null && info.groupFormed && !info.isGroupOwner && info.groupOwnerAddress != null;
            if (up && !connected) {
                connected = true;
                events.onConnected(info.groupOwnerAddress.getHostAddress());
            } else if (!up && connected) {
                connected = false;
                events.onLost();
            }
        });
    }

    // ---- Shared ----

    /** Swaps the listener, e.g. once the screen that started the link is gone. */
    void setEvents(Events listener) {
        events = listener;
    }

    private boolean begin(Mode next, Events listener) {
        stop();
        events = listener;
        if (manager == null || channel == null) {
            listener.onError("This phone does not support Wi-Fi Direct.");
            return false;
        }
        mode = next;
        IntentFilter filter = new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            // Both actions are protected system broadcasts, so other apps cannot send them.
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
        return true;
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
        if (mode != Mode.IDLE && manager != null && channel != null) {
            manager.clearLocalServices(channel, after(null));
            manager.clearServiceRequests(channel, after(null));
            manager.stopPeerDiscovery(channel, after(null));
            manager.removeGroup(channel, after(null));
        }
        mode = Mode.IDLE;
        events = new Events();
        joining = null;
        connected = false;
        hostReady = false;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                if (mode == Mode.HOST) refreshGroup();
                if (mode == Mode.FIND) refreshConnection();
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
                if (joining == null) readScanResults();
            }
        }
    };

    /** An ActionListener that runs the same step whether the call succeeded or not. */
    private WifiP2pManager.ActionListener after(Runnable next) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                if (next != null) next.run();
            }

            @Override
            public void onFailure(int reason) {
                if (next != null) next.run();
            }
        };
    }

    private static String describe(int reason) {
        if (reason == WifiP2pManager.P2P_UNSUPPORTED) return "This phone does not support Wi-Fi Direct.";
        if (reason == WifiP2pManager.BUSY) return "Wi-Fi is busy. Wait a moment and try again.";
        return "Could not start the direct Wi-Fi link. Check that Wi-Fi is on.";
    }

}
