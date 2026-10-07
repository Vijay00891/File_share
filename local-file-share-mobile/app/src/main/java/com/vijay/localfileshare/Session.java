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
        void run(PeerClient.Progress progress) throws Exception;
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

        Transfer(String name, boolean upload) {
            this.name = name;
            this.upload = upload;
        }
    }

    static volatile Kind kind = Kind.NONE;
    static LocalShareServer server;
    static P2p p2p;
    static File saveDir;

    // Sender / desktop host
    static volatile String hostTitle = "";
    static volatile String hostDetail = "";
    static volatile String hostError;
    /** What the sender's QR code holds; null until the link is up. */
    static volatile String hostQr;

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

    /** Status text for the sender screen, updated from Wi-Fi Direct callbacks. */
    static P2p.Events hostEvents(boolean askedFiveGhz) {
        return new P2p.Events() {
            private String network = "";

            @Override
            void onHostReady(String networkName, String password, boolean onFiveGhz) {
                String visibleAs = networkName.startsWith(P2p.PREFIX)
                        ? networkName.substring(P2p.PREFIX.length()) : networkName;
                network = "Visible as " + visibleAs + "  ·  " + (onFiveGhz ? "5 GHz" : "2.4 GHz")
                        + (askedFiveGhz && !onFiveGhz ? " (5 GHz wasn't available)" : "")
                        + "\nWi-Fi name: " + networkName + "\nPassword: " + password;
                hostQr = Qr.joinLink(networkName);
            }

            @Override
            void onClients(int count) {
                hostTitle = count == 0 ? "Waiting for the other phone"
                        : count == 1 ? "1 phone connected" : count + " phones connected";
                hostDetail = count == 0
                        ? "On the other phone tap Share to phone, then Receive.\n\n" + network
                        : network;
            }

            @Override
            void onError(String message) {
                hostTitle = "Couldn't start the link";
                hostDetail = message;
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
                job.run((done, total) -> {
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
        if (p2p != null) {
            p2p.stop();
            p2p = null;
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
        ShareService.stop(context);
    }
}
