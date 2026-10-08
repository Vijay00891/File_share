package com.vijay.localfileshare;

import android.content.Context;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The running share, kept outside the activity so it survives the user leaving the app.
 * ShareService keeps the process alive while a session is active; the activity only draws it.
 */
final class Session {
    enum Kind { NONE, DESKTOP, PHONE_SEND, PHONE_RECEIVE }

    interface Job {
        /** Returns the saved file for a download, or null for an upload. */
        File run(PeerClient.Progress progress) throws Exception;
    }

    static class Transfer {
        static final int WAITING = 0;
        static final int RUNNING = 1;
        static final int DONE = 2;
        static final int FAILED = 3;

        final String name;
        final boolean upload;
        volatile long done;
        volatile long total;
        volatile int state = WAITING;
        /** Where a finished download was saved. */
        volatile File file;

        Transfer(String name, boolean upload) {
            this.name = name;
            this.upload = upload;
        }
    }

    static volatile Kind kind = Kind.NONE;
    static LocalShareServer server;
    static File saveDir;

    // The radios behind a phone-to-phone share. A sender uses either the hotspot or Wi-Fi Direct.
    static P2p p2p;
    static Hotspot hotspot;
    static Beacon beacon;
    static WifiJoin join;

    // Sender / desktop host
    static volatile String hostTitle = "";
    static volatile String hostDetail = "";
    static volatile String hostError;
    /** What the sender's QR code holds; null until the link is up. */
    static volatile String hostQr;
    private static volatile String hotspotInfo;

    // Receiver, once connected to a sender
    static String peerBase;
    static String peerName = "";
    static volatile boolean peerLost;
    static final List<Transfer> transfers = new CopyOnWriteArrayList<>();
    private static ExecutorService transferQueue;

    private Session() {
    }

    static boolean connectedToPeer() {
        return kind == Kind.PHONE_RECEIVE && peerBase != null;
    }

    static String summary() {
        switch (kind) {
            case DESKTOP:
                return "Sharing with your computer";
            case PHONE_SEND:
                return "Sending to nearby phones";
            case PHONE_RECEIVE:
                return peerBase != null ? "Connected to " + peerName : "Looking for senders";
            default:
                return "Not sharing";
        }
    }

    // ---------------------------------------------------------------- Sending to a phone

    /**
     * Brings up the sender's link. Normally that is the phone's hotspot, which needs no Wi-Fi;
     * high speed uses Wi-Fi Direct instead because only that lets an app ask for 5 GHz.
     */
    static void startSend(Context context, String deviceName, boolean highSpeed) {
        Context app = context.getApplicationContext();
        String name = Target.cleanName(deviceName);
        hostTitle = "Starting…";
        hostDetail = "";
        if (highSpeed) {
            P2p link = new P2p(app);
            p2p = link;
            link.startHost(name, true, directEvents(app));
            return;
        }
        Hotspot started = new Hotspot();
        hotspot = started;
        started.start(app, new Hotspot.Listener() {
            @Override
            public void onStarted(String ssid, String password) {
                if (hotspot != started) return;
                boolean nearby = announce(app, new Target(Target.HOTSPOT, ssid, password, name));
                hotspotInfo = "Visible as " + name + "\nHotspot: " + ssid + "\nPassword: " + password
                        + (nearby ? "" : "\n\nBluetooth is off or unavailable, so this phone won't appear in the "
                        + "other phone's list. Use the QR code instead.");
                pollHost();
            }

            @Override
            public void onFailed(String message) {
                if (hotspot != started) return;
                hostTitle = "Couldn't start the hotspot";
                hostDetail = message;
            }

            @Override
            public void onStopped() {
                if (hotspot != started) return;
                hotspotInfo = null;
                hostQr = null;
                hostTitle = "The hotspot was turned off";
                hostDetail = "Stop sending and start again to share more files.";
            }
        });
    }

    /** Publishes how to join: the QR link, and the Bluetooth beacon when Bluetooth allows it. */
    private static boolean announce(Context app, Target target) {
        hostQr = target.toLink();
        if (beacon != null) beacon.stop();
        beacon = new Beacon(app);
        return beacon.advertise(target);
    }

    private static P2p.Events directEvents(Context app) {
        return new P2p.Events() {
            private String network = "";

            @Override
            void onHostReady(String networkName, String password, boolean onFiveGhz) {
                Target target = Target.direct(networkName);
                announce(app, target);
                network = "Visible as " + target.displayName() + "  ·  " + (onFiveGhz ? "5 GHz" : "2.4 GHz")
                        + (onFiveGhz ? "" : " (5 GHz wasn't available)")
                        + "\nWi-Fi name: " + networkName + "\nPassword: " + password;
            }

            @Override
            void onClients(int count) {
                showClients(count, network);
            }

            @Override
            void onError(String message) {
                hostTitle = "Couldn't start the link";
                hostDetail = message;
            }
        };
    }

    private static void showClients(int count, String network) {
        hostTitle = count == 0 ? "Waiting for the other phone"
                : count == 1 ? "1 phone connected" : count + " phones connected";
        hostDetail = count == 0
                ? "On the other phone tap Share to phone, then Receive.\n\n" + network
                : network;
    }

    /** Called every couple of seconds while sending, from the service and the open screen. */
    static void pollHost() {
        if (kind != Kind.PHONE_SEND) return;
        if (p2p != null) p2p.pollHost();
        // A hotspot does not report who joined, so count phones that recently talked to the server.
        String info = hotspotInfo;
        LocalShareServer running = server;
        if (hotspot != null && info != null && running != null) showClients(running.activeClients(7000), info);
    }

    // ---------------------------------------------------------------- Receiving

    /** A listener for the hotspot link that no longer points at a screen. */
    static WifiJoin.Listener joinEvents() {
        return new WifiJoin.Listener() {
            @Override
            public void onConnected(String hostAddress) {
            }

            @Override
            public void onFailed(String message) {
                peerLost = true;
            }

            @Override
            public void onLost() {
                peerLost = true;
            }
        };
    }

    static P2p.Events peerEvents() {
        return new P2p.Events() {
            @Override
            void onLost() {
                peerLost = true;
            }
        };
    }

    /** Queues an upload or download; progress is read from the returned model by the screen. */
    static void addTransfer(String name, boolean upload, Job job) {
        if (transferQueue == null) transferQueue = Executors.newSingleThreadExecutor();
        Transfer transfer = new Transfer(name, upload);
        transfers.add(transfer);
        transferQueue.execute(() -> {
            transfer.state = Transfer.RUNNING;
            try {
                transfer.file = job.run((done, total) -> {
                    transfer.done = done;
                    transfer.total = total;
                });
                transfer.state = Transfer.DONE;
            } catch (Exception error) {
                transfer.state = Transfer.FAILED;
            }
        });
    }

    static void stop(Context context) {
        kind = Kind.NONE;
        if (beacon != null) {
            beacon.stop();
            beacon = null;
        }
        if (p2p != null) {
            p2p.stop();
            p2p = null;
        }
        if (hotspot != null) {
            hotspot.stop();
            hotspot = null;
        }
        if (join != null) {
            join.release();
            join = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        if (transferQueue != null) {
            transferQueue.shutdownNow();
            transferQueue = null;
        }
        transfers.clear();
        peerBase = null;
        peerName = "";
        peerLost = false;
        hostTitle = "";
        hostDetail = "";
        hostError = null;
        hostQr = null;
        hotspotInfo = null;
        ShareService.stop(context);
    }
}
