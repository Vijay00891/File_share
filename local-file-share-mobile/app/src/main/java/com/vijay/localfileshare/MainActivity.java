package com.vijay.localfileshare;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.location.LocationManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Draws the screens. The share itself lives in {@link Session} and keeps running in the
 * background, so every screen here can be rebuilt from it when the app is reopened.
 */
public class MainActivity extends Activity implements PickerView.Host, ScannerView.Listener {
    private static final int PORT = 3478;
    private static final int REQ_PERMISSIONS = 51;
    private static final int REQ_WIFI_PANEL = 52;
    private static final int REQ_STORAGE = 53;
    private static final int REQ_NOTIFICATIONS = 54;

    private enum Screen { HOME, PHONE, HOST, FIND, PEER }

    private static class TransferViews {
        ProgressBar bar;
        TextView state;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private FrameLayout frame;
    private Screen screen = Screen.HOME;
    private PickerView picker;
    private ScannerView scanner;
    private Runnable afterPermission;
    private Runnable afterWifi;

    // Host screen (share to desktop, or send to phone)
    private TextView hostTitle;
    private TextView hostDetail;
    private LinearLayout hostQrBox;
    private ImageView hostQrImage;
    private String shownQr;
    private LinearLayout hostFiles;
    private String hostSignature;
    private String hostAddress;

    // Receive: looking for senders
    private TextView findStatus;
    private LinearLayout findList;
    private ValueAnimator pulse;
    private boolean joining;
    private String joiningName = "";

    // Receive: connected to a sender
    private TextView peerStatus;
    private TextView transfersLabel;
    private LinearLayout transfersBox;
    private LinearLayout peerFiles;
    private TextView saveAll;
    private String peerSignature;
    private List<PeerClient.RemoteFile> remoteFiles = Collections.emptyList();
    private int peerFailures;
    private boolean peerPolling;
    private final Map<Session.Transfer, TransferViews> transferViews = new IdentityHashMap<>();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (screen != Screen.HOST && screen != Screen.PEER) return;
            if (Session.kind == Session.Kind.NONE) {
                // Stopped from the notification while this screen was open.
                closeOverlays();
                showHome();
                return;
            }
            if (screen == Screen.HOST) {
                refreshHostFiles();
                if (Session.p2p != null) Session.p2p.pollHost();
                showHostStatus();
            } else {
                pollPeer();
            }
            handler.postDelayed(this, 2000);
        }
    };

    private final Runnable transferTick = new Runnable() {
        @Override
        public void run() {
            if (screen != Screen.PEER) return;
            renderTransfers();
            handler.postDelayed(this, 300);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyTheme(this);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);
        // Keep content clear of the status and navigation bars (edge-to-edge on Android 15+).
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(frame);

        // Reopened while a share is still running in the background: go straight back to it.
        if (Session.kind == Session.Kind.DESKTOP || Session.kind == Session.Kind.PHONE_SEND) {
            showHostScreen();
        } else if (Session.connectedToPeer()) {
            showPeerScreen();
        } else {
            if (Session.kind != Session.Kind.NONE) Session.stop(this);
            showHome();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (picker != null) picker.refresh();
    }

    @Override
    protected void onPause() {
        closeScanner();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopPulse();
        closeOverlays();
        // A search with no connection yet has nothing worth keeping alive.
        if (Session.kind == Session.Kind.PHONE_RECEIVE && !Session.connectedToPeer()) Session.stop(this);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (scanner != null) {
            closeScanner();
            return;
        }
        if (picker != null) {
            if (!picker.onBack()) closePicker();
            return;
        }
        switch (screen) {
            case PHONE:
                showHome();
                break;
            case FIND:
                endSession();
                showPhone();
                break;
            case HOST:
                boolean phone = Session.kind == Session.Kind.PHONE_SEND;
                confirmLeave("Stop sharing?", "Other devices will lose access to the files you shared.", () -> {
                    endSession();
                    if (phone) showPhone(); else showHome();
                });
                break;
            case PEER:
                confirmLeave("Disconnect?", "Transfers that are still running will be cancelled.", () -> {
                    endSession();
                    showPhone();
                });
                break;
            default:
                super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- Home

    private void showHome() {
        screen = Screen.HOME;
        keepAwake(false);
        LinearLayout body = page(null);
        body.setPadding(dp(20), dp(44), dp(20), dp(28));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.app_logo);
        body.addView(logo, Ui.lp(dp(64), dp(64)));
        body.addView(Ui.margin(this, Ui.text(this, "Local Share", 30, Ui.INK, true), 0, 16, 0, 2));
        body.addView(Ui.margin(this, Ui.text(this, "Where do you want to share?", 16, Ui.MUTED, false), 0, 0, 0, 28));

        body.addView(choice(R.drawable.ic_desktop, "Share to desktop",
                "Open a link in the computer's browser. Both on the same Wi-Fi.",
                () -> startHost(false, false)));
        body.addView(choice(R.drawable.ic_phone, "Share to phone",
                "A direct Wi-Fi link between two phones. No internet needed.",
                this::showPhone));
    }

    private void showPhone() {
        screen = Screen.PHONE;
        keepAwake(false);
        LinearLayout body = page("Share to phone");
        body.addView(Ui.margin(this, Ui.text(this, "What does this phone do?", 16, Ui.MUTED, false), 4, 4, 0, 20));
        body.addView(choice(R.drawable.ic_up, "Send",
                "Create a direct Wi-Fi link and choose files or apps to send.",
                this::showSendDialog));
        body.addView(choice(R.drawable.ic_down, "Receive",
                "Find the sending phone nearby, or scan its QR code, and connect.",
                this::beginReceive));
    }

    private View choice(int icon, String title, String description, Runnable action) {
        LinearLayout card = Ui.row(this);
        card.setBackground(Ui.ripple(this, Ui.SURFACE, 24));
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setClickable(true);
        card.setOnClickListener(v -> action.run());
        card.addView(Ui.iconCircle(this, icon, 52, Ui.PRIMARY_SOFT, Ui.PRIMARY));
        LinearLayout texts = Ui.column(this);
        texts.setPadding(dp(16), 0, 0, 0);
        texts.addView(Ui.text(this, title, 18, Ui.INK, true));
        texts.addView(Ui.margin(this, Ui.text(this, description, 14, Ui.MUTED, false), 0, 3, 0, 0));
        card.addView(texts, Ui.weighted());
        return Ui.margin(this, card, 0, 0, 0, 12);
    }

    // ---------------------------------------------------------------- Send / desktop host

    private void showSendDialog() {
        if (!phoneLinkSupported()) return;
        boolean fiveGhzSupported = wifiManager().is5GHzBandSupported();

        LinearLayout box = Ui.column(this);
        box.setPadding(dp(24), dp(12), dp(24), 0);
        box.addView(Ui.text(this,
                "This phone creates a direct Wi-Fi link. On the other phone, open Local Share and tap Share to phone, then Receive.",
                15, Ui.MUTED, false));
        CheckBox highSpeed = new CheckBox(this);
        highSpeed.setText("High-speed transfer (5 GHz)");
        highSpeed.setTextSize(16);
        highSpeed.setTextColor(Ui.INK);
        highSpeed.setButtonTintList(ColorStateList.valueOf(Ui.PRIMARY));
        highSpeed.setEnabled(fiveGhzSupported);
        box.addView(Ui.margin(this, highSpeed, -6, 16, 0, 0));
        box.addView(Ui.margin(this, Ui.text(this, fiveGhzSupported
                ? "Faster, with a shorter range. Both phones need 5 GHz Wi-Fi."
                : "This phone's Wi-Fi doesn't support 5 GHz.", 13, Ui.MUTED, false), 26, 0, 0, 0));

        new AlertDialog.Builder(this)
                .setTitle("Send to a phone")
                .setView(box)
                .setPositiveButton("Start", (dialog, which) ->
                        withNearbyPermission(() -> withWifiOn(() -> startHost(true, highSpeed.isChecked()))))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Starts the share in {@link Session}, then shows it. */
    private void startHost(boolean phone, boolean fiveGhz) {
        endSession();
        File saveDir = receiveDir();
        LocalShareServer started = new LocalShareServer(getApplicationContext(), PORT, saveDir);
        Session.server = started;
        Session.saveDir = saveDir;
        Session.hostTitle = phone ? "Starting…" : "";
        Session.hostDetail = "";
        Session.kind = phone ? Session.Kind.PHONE_SEND : Session.Kind.DESKTOP;
        new Thread(() -> {
            try {
                started.start();
            } catch (Exception error) {
                if (Session.server == started) Session.hostError = String.valueOf(error.getMessage());
            }
        }, "server-start").start();

        if (phone) {
            P2p link = new P2p(this);
            Session.p2p = link;
            link.startHost(deviceName(), fiveGhz, Session.hostEvents(fiveGhz));
        }
        runInBackground();
        showHostScreen();
    }

    private void showHostScreen() {
        boolean phone = Session.kind == Session.Kind.PHONE_SEND;
        screen = Screen.HOST;
        hostAddress = null;
        shownQr = null;
        keepAwake(true);

        LinearLayout body = page(phone ? "Send to phone" : "Share to desktop");

        LinearLayout status = Ui.card(this);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.iconCircle(this, phone ? R.drawable.ic_wifi : R.drawable.ic_desktop, 52, Ui.PRIMARY_SOFT, Ui.PRIMARY));
        LinearLayout texts = Ui.column(this);
        texts.setPadding(dp(16), 0, 0, 0);
        hostTitle = Ui.text(this, "", 18, Ui.INK, true);
        hostDetail = Ui.text(this, "", 14, Ui.MUTED, false);
        texts.addView(hostTitle);
        texts.addView(Ui.margin(this, hostDetail, 0, 3, 0, 0));
        head.addView(texts, Ui.weighted());
        status.addView(head);

        hostQrBox = Ui.column(this);
        hostQrBox.setGravity(Gravity.CENTER_HORIZONTAL);
        hostQrBox.setVisibility(View.GONE);
        hostQrImage = new ImageView(this);
        hostQrImage.setBackground(Ui.shape(this, 0xFFFFFFFF, 16));
        hostQrImage.setContentDescription("QR code for connecting to this phone");
        LinearLayout.LayoutParams qrParams = Ui.lp(dp(200), dp(200));
        qrParams.topMargin = dp(20);
        hostQrBox.addView(hostQrImage, qrParams);
        TextView qrCaption = Ui.text(this, phone
                ? "On the other phone tap Receive, then Scan QR code."
                : "A phone or tablet on the same Wi-Fi can scan this to open the page.", 13, Ui.MUTED, false);
        qrCaption.setGravity(Gravity.CENTER);
        hostQrBox.addView(Ui.margin(this, qrCaption, 0, 8, 0, 0));
        status.addView(hostQrBox);

        LinearLayout actions = Ui.row(this);
        if (!phone) {
            TextView copy = Ui.button(this, "Copy address", false);
            copy.setOnClickListener(v -> copyAddress());
            LinearLayout.LayoutParams copyParams = Ui.lp(Ui.WRAP, Ui.WRAP);
            copyParams.rightMargin = dp(8);
            actions.addView(copy, copyParams);
        }
        TextView stop = Ui.button(this, phone ? "Stop sending" : "Stop server", false);
        stop.setTextColor(Ui.DANGER);
        stop.setOnClickListener(v -> {
            endSession();
            toast(phone ? "Sending stopped" : "Server stopped");
            if (phone) showPhone(); else showHome();
        });
        actions.addView(stop, Ui.lp(Ui.WRAP, Ui.WRAP));
        status.addView(Ui.margin(this, actions, 0, 16, 0, 0));
        body.addView(status);

        if (!phone) {
            LinearLayout tip = Ui.row(this);
            tip.setBackground(Ui.shape(this, Ui.PRIMARY_SOFT, 20));
            tip.setPadding(dp(16), dp(14), dp(16), dp(14));
            tip.addView(Ui.icon(this, R.drawable.ic_wifi, Ui.PRIMARY), Ui.lp(dp(22), dp(22)));
            TextView tipText = Ui.text(this,
                    "For high-speed sharing, turn on this phone's hotspot, set its band to 5 GHz in the hotspot settings, and connect the computer to it.",
                    14, Ui.INK, false);
            tipText.setPadding(dp(12), 0, 0, 0);
            tip.addView(tipText, Ui.weighted());
            body.addView(Ui.margin(this, tip, 0, 12, 0, 0));
        }

        TextView add = Ui.button(this, "Add files or apps", true);
        add.setOnClickListener(v -> openPicker("Share"));
        body.addView(Ui.margin(this, add, 0, 16, 0, 0));

        body.addView(sectionLabel("Shared in this session"));
        hostFiles = Ui.card(this);
        hostFiles.setPadding(dp(8), dp(8), dp(8), dp(8));
        body.addView(hostFiles);

        File saveDir = Session.saveDir != null ? Session.saveDir : receiveDir();
        body.addView(Ui.margin(this, Ui.text(this,
                "Tap a file to open it. Files sent to this phone are saved in " + describeDir(saveDir) + ".\n"
                        + "Sharing keeps running if you leave the app. Stop it here or from the notification.",
                13, Ui.MUTED, false), 6, 14, 6, 0));

        hostSignature = null;
        refreshHostFiles();
        showHostStatus();
        startTicker();
    }

    private void showHostStatus() {
        String qr;
        if (Session.hostError != null) {
            hostAddress = null;
            hostTitle.setText("Couldn't start sharing");
            hostDetail.setText(Session.hostError);
            qr = null;
        } else if (Session.kind == Session.Kind.PHONE_SEND) {
            hostTitle.setText(Session.hostTitle);
            hostDetail.setText(Session.hostDetail);
            qr = Session.hostQr;
        } else {
            String ip = localIpAddress();
            if (ip == null) {
                hostAddress = null;
                hostTitle.setText("Connect to Wi-Fi");
                hostDetail.setText("Join the same Wi-Fi as your computer, or turn on this phone's hotspot and connect the computer to it.");
            } else {
                hostAddress = "http://" + ip + ":" + PORT;
                hostTitle.setText(hostAddress);
                hostDetail.setText("Type this address into the browser on your computer. Both devices must be on the same Wi-Fi.");
            }
            qr = hostAddress;
        }
        showQr(qr);
    }

    private void showQr(String content) {
        if (content == null) {
            shownQr = null;
            hostQrBox.setVisibility(View.GONE);
            return;
        }
        if (content.equals(shownQr)) return;
        shownQr = content;
        hostQrImage.setImageBitmap(Qr.encode(content, dp(200)));
        hostQrBox.setVisibility(View.VISIBLE);
    }

    private void copyAddress() {
        if (hostAddress == null) {
            toast("No address yet. Connect to Wi-Fi first.");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Local Share address", hostAddress));
        toast("Address copied");
    }

    private void refreshHostFiles() {
        LocalShareServer server = Session.server;
        if (server == null || hostFiles == null) return;
        List<LocalShareServer.Entry> entries = server.entries();
        StringBuilder signature = new StringBuilder();
        for (LocalShareServer.Entry entry : entries) signature.append(entry.name).append('|').append(entry.file.length()).append(';');
        if (signature.toString().equals(hostSignature)) return;
        hostSignature = signature.toString();

        hostFiles.removeAllViews();
        if (entries.isEmpty()) {
            hostFiles.addView(emptyRow("Nothing shared yet. Add files or apps to start."));
            return;
        }
        for (LocalShareServer.Entry entry : entries) {
            LinearLayout row = fileRow(entry.received ? R.drawable.ic_down : R.drawable.ic_file, entry.name,
                    Ui.formatBytes(entry.file.length()) + (entry.received ? "  ·  received" : ""));
            ImageView remove = Ui.icon(this, R.drawable.ic_close, Ui.MUTED);
            remove.setPadding(dp(10), dp(10), dp(10), dp(10));
            remove.setBackground(Ui.ripple(this, 0x00FFFFFF, 20));
            remove.setContentDescription("Stop sharing " + entry.name);
            remove.setOnClickListener(v -> {
                if (Session.server != null) Session.server.remove(entry.name);
                refreshHostFiles();
            });
            row.addView(remove, Ui.lp(dp(40), dp(40)));
            row.setBackground(Ui.ripple(this, Ui.SURFACE, 16));
            row.setClickable(true);
            row.setOnClickListener(v -> FileOpener.open(this, entry.file));
            hostFiles.addView(row);
        }
    }

    // ---------------------------------------------------------------- Receive: find a sender

    private void beginReceive() {
        if (!phoneLinkSupported()) return;
        withNearbyPermission(() -> withWifiOn(this::startFind));
    }

    private void startFind() {
        endSession();
        screen = Screen.FIND;
        joining = false;
        keepAwake(true);

        LinearLayout body = page("Receive");
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout radar = new FrameLayout(this);
        View ring = new View(this);
        ring.setBackground(Ui.shape(this, Ui.PRIMARY_SOFT, 80));
        radar.addView(ring, new FrameLayout.LayoutParams(dp(160), dp(160), Gravity.CENTER));
        ImageView center = Ui.icon(this, R.drawable.ic_wifi, Ui.ON_PRIMARY);
        center.setPadding(dp(18), dp(18), dp(18), dp(18));
        center.setBackground(Ui.shape(this, Ui.PRIMARY, 36));
        radar.addView(center, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
        body.addView(radar, Ui.lp(dp(160), dp(160)));

        pulse = ObjectAnimator.ofPropertyValuesHolder(ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.45f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.45f, 1f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f));
        pulse.setDuration(1800);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();

        findStatus = Ui.text(this, "Looking for nearby senders…", 18, Ui.INK, true);
        findStatus.setGravity(Gravity.CENTER);
        body.addView(Ui.margin(this, findStatus, 0, 8, 0, 4));
        TextView hint = Ui.text(this, "On the other phone tap Share to phone, then Send.", 14, Ui.MUTED, false);
        hint.setGravity(Gravity.CENTER);
        body.addView(hint);

        TextView scan = Ui.button(this, "Scan QR code", false);
        scan.setOnClickListener(v -> openScanner());
        LinearLayout.LayoutParams scanParams = Ui.lp(Ui.WRAP, Ui.WRAP);
        scanParams.setMargins(0, dp(16), 0, dp(20));
        body.addView(scan, scanParams);

        findList = Ui.column(this);
        body.addView(findList, Ui.lp(Ui.MATCH, Ui.WRAP));

        P2p link = new P2p(this);
        Session.kind = Session.Kind.PHONE_RECEIVE;
        Session.p2p = link;
        link.startFind(new P2p.Events() {
            @Override
            void onFound(String networkName, String displayName) {
                if (screen == Screen.FIND && !joining) addSender(networkName, displayName);
            }

            @Override
            void onConnected(String hostAddress) {
                if (screen == Screen.FIND && Session.p2p == link) connectedTo(hostAddress);
            }

            @Override
            void onError(String message) {
                if (screen != Screen.FIND || Session.p2p != link) return;
                joining = false;
                findList.removeAllViews();
                findList.setAlpha(1f);
                findStatus.setText("Looking for nearby senders…");
                toast(message);
            }
        });
    }

    private void addSender(String networkName, String displayName) {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.ripple(this, Ui.SURFACE, 24));
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setClickable(true);

        TextView avatar = Ui.text(this, displayName.substring(0, 1).toUpperCase(Locale.ROOT), 18, Ui.ON_PRIMARY, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(Ui.shape(this, Ui.PRIMARY, 24));
        row.addView(avatar, Ui.lp(dp(48), dp(48)));

        LinearLayout texts = Ui.column(this);
        texts.setPadding(dp(14), 0, 0, 0);
        texts.addView(Ui.oneLine(Ui.text(this, displayName, 17, Ui.INK, true)));
        texts.addView(Ui.text(this, "Tap to connect", 14, Ui.MUTED, false));
        row.addView(texts, Ui.weighted());

        row.setOnClickListener(v -> join(networkName, displayName));
        findList.addView(Ui.margin(this, row, 0, 0, 0, 10));
    }

    /** Joins a sender picked from the list or read from its QR code. */
    private void join(String networkName, String displayName) {
        if (joining || Session.p2p == null || screen != Screen.FIND) return;
        joining = true;
        joiningName = displayName;
        findStatus.setText("Connecting to " + displayName + "…");
        findList.setAlpha(0.5f);
        Session.p2p.connect(networkName);
    }

    private void connectedTo(String hostAddress) {
        Session.peerBase = "http://" + hostAddress + ":" + PORT;
        Session.peerName = joiningName;
        Session.peerLost = false;
        Session.saveDir = receiveDir();
        // From here on the link reports to the session, not to this screen.
        Session.p2p.setEvents(Session.peerEvents());
        runInBackground();
        showPeerScreen();
    }

    // ---------------------------------------------------------------- QR scanner

    private void openScanner() {
        if (scanner != null || screen != Screen.FIND || joining) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = this::openScanner;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERMISSIONS);
            return;
        }
        scanner = new ScannerView(this, this);
        frame.addView(scanner.root);
    }

    private void closeScanner() {
        if (scanner == null) return;
        frame.removeView(scanner.root);
        scanner.destroy();
        scanner = null;
    }

    @Override
    public void onScannerClosed() {
        closeScanner();
    }

    @Override
    public boolean onScanned(String text) {
        String networkName = Qr.networkFrom(text);
        if (networkName == null) return false;
        closeScanner();
        join(networkName, networkName.substring(P2p.PREFIX.length()));
        return true;
    }

    // ---------------------------------------------------------------- Receive: connected

    private void showPeerScreen() {
        screen = Screen.PEER;
        stopPulse();
        keepAwake(true);
        peerSignature = null;
        peerFailures = 0;
        peerPolling = false;
        remoteFiles = Collections.emptyList();
        transferViews.clear();
        String peerName = Session.peerName;

        LinearLayout body = page("Connected");

        LinearLayout status = Ui.card(this);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.iconCircle(this, R.drawable.ic_phone, 52, Ui.PRIMARY_SOFT, Ui.PRIMARY));
        LinearLayout texts = Ui.column(this);
        texts.setPadding(dp(16), 0, 0, 0);
        texts.addView(Ui.text(this, "Connected to " + peerName, 18, Ui.INK, true));
        peerStatus = Ui.text(this, "", 14, Ui.MUTED, false);
        texts.addView(Ui.margin(this, peerStatus, 0, 3, 0, 0));
        head.addView(texts, Ui.weighted());
        status.addView(head);
        body.addView(status);
        showPeerStatus(false);

        TextView send = Ui.button(this, "Send files or apps", true);
        send.setOnClickListener(v -> openPicker("Send"));
        body.addView(Ui.margin(this, send, 0, 16, 0, 0));

        transfersLabel = sectionLabel("Transfers");
        transfersLabel.setVisibility(View.GONE);
        body.addView(transfersLabel);
        transfersBox = Ui.column(this);
        body.addView(transfersBox);

        LinearLayout labelRow = Ui.row(this);
        labelRow.addView(sectionLabel("Files from " + peerName), Ui.weighted());
        saveAll = Ui.text(this, "Save all", 14, Ui.PRIMARY, true);
        saveAll.setPadding(dp(12), dp(20), dp(8), dp(8));
        saveAll.setVisibility(View.GONE);
        saveAll.setOnClickListener(v -> {
            for (PeerClient.RemoteFile file : remoteFiles) save(file);
        });
        labelRow.addView(saveAll);
        body.addView(labelRow);

        peerFiles = Ui.card(this);
        peerFiles.setPadding(dp(8), dp(8), dp(8), dp(8));
        peerFiles.addView(emptyRow("Waiting for " + peerName + " to share something."));
        body.addView(peerFiles);

        startTicker();
        handler.removeCallbacks(transferTick);
        handler.post(transferTick);
    }

    private void showPeerStatus(boolean unreachable) {
        if (unreachable || Session.peerLost) {
            peerStatus.setText("Can't reach " + Session.peerName + ". Go back and connect again.");
            peerStatus.setTextColor(Ui.DANGER);
        } else {
            File dir = Session.saveDir != null ? Session.saveDir : receiveDir();
            peerStatus.setText("Files you save go to " + describeDir(dir) + ". Transfers keep running if you leave the app.");
            peerStatus.setTextColor(Ui.MUTED);
        }
    }

    private void pollPeer() {
        String base = Session.peerBase;
        if (peerPolling || base == null) return;
        peerPolling = true;
        io.execute(() -> {
            List<PeerClient.RemoteFile> listed = null;
            try {
                listed = PeerClient.list(base);
            } catch (Exception ignored) {
            }
            List<PeerClient.RemoteFile> result = listed;
            runOnUiThread(() -> {
                peerPolling = false;
                if (screen != Screen.PEER || !base.equals(Session.peerBase)) return;
                if (result == null) {
                    if (++peerFailures >= 4) showPeerStatus(true);
                    return;
                }
                peerFailures = 0;
                Session.peerLost = false;
                showPeerStatus(false);
                showRemoteFiles(result);
            });
        });
    }

    private void showRemoteFiles(List<PeerClient.RemoteFile> files) {
        StringBuilder signature = new StringBuilder();
        for (PeerClient.RemoteFile file : files) signature.append(file.name).append('|').append(file.size).append(';');
        if (signature.toString().equals(peerSignature)) return;
        peerSignature = signature.toString();
        remoteFiles = files;

        peerFiles.removeAllViews();
        saveAll.setVisibility(files.size() > 1 ? View.VISIBLE : View.GONE);
        if (files.isEmpty()) {
            peerFiles.addView(emptyRow("Waiting for " + Session.peerName + " to share something."));
            return;
        }
        for (PeerClient.RemoteFile file : files) {
            LinearLayout row = fileRow(R.drawable.ic_file, file.name, Ui.formatBytes(file.size));
            TextView saveButton = Ui.button(this, "Save", false);
            saveButton.setPadding(dp(18), dp(9), dp(18), dp(9));
            saveButton.setOnClickListener(v -> save(file));
            row.addView(saveButton, Ui.lp(Ui.WRAP, Ui.WRAP));
            peerFiles.addView(row);
        }
    }

    // Transfer jobs outlive this screen, so they must not hold on to the activity.
    private void save(PeerClient.RemoteFile file) {
        String base = Session.peerBase;
        if (base == null) return;
        File dir = Session.saveDir != null ? Session.saveDir : receiveDir();
        Context app = getApplicationContext();
        Session.addTransfer(file.name, false, progress -> {
            File saved = PeerClient.download(base, file, dir, progress);
            MediaScannerConnection.scanFile(app, new String[]{saved.getPath()}, null, null);
            return saved;
        });
        renderTransfers();
    }

    /** Draws one row per queued transfer and keeps their progress up to date. */
    private void renderTransfers() {
        if (transfersBox == null) return;
        List<Session.Transfer> transfers = Session.transfers;
        transfersLabel.setVisibility(transfers.isEmpty() ? View.GONE : View.VISIBLE);
        for (Session.Transfer transfer : transfers) {
            TransferViews views = transferViews.get(transfer);
            if (views == null) {
                views = addTransferRow(transfer);
                transferViews.put(transfer, views);
            }
            switch (transfer.state) {
                case Session.Transfer.RUNNING:
                    long total = transfer.total;
                    long done = transfer.done;
                    views.bar.setProgress(total > 0 ? (int) (done * 1000 / total) : 0);
                    views.state.setText(total > 0 ? Ui.formatBytes(done) + " / " + Ui.formatBytes(total) : "Starting");
                    break;
                case Session.Transfer.DONE:
                    views.bar.setProgress(1000);
                    views.state.setText(transfer.upload ? "Sent" : "Saved  ·  tap to open");
                    views.state.setTextColor(Ui.OK);
                    break;
                case Session.Transfer.FAILED:
                    views.state.setText("Failed");
                    views.state.setTextColor(Ui.DANGER);
                    break;
                default:
                    views.state.setText("Waiting");
            }
        }
    }

    private TransferViews addTransferRow(Session.Transfer transfer) {
        LinearLayout row = Ui.card(this);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout top = Ui.row(this);
        top.addView(Ui.icon(this, transfer.upload ? R.drawable.ic_up : R.drawable.ic_down, Ui.PRIMARY), Ui.lp(dp(18), dp(18)));
        TextView title = Ui.oneLine(Ui.text(this, transfer.name, 15, Ui.INK, true));
        title.setPadding(dp(10), 0, dp(10), 0);
        top.addView(title, Ui.weighted());
        TransferViews views = new TransferViews();
        views.state = Ui.text(this, "Waiting", 13, Ui.MUTED, false);
        top.addView(views.state);
        row.addView(top);

        views.bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        views.bar.setMax(1000);
        views.bar.setProgressTintList(ColorStateList.valueOf(Ui.PRIMARY));
        row.addView(Ui.margin(this, views.bar, 0, 8, 0, 0));
        row.setBackground(Ui.ripple(this, Ui.SURFACE, 24));
        row.setClickable(true);
        row.setOnClickListener(v -> {
            if (transfer.state == Session.Transfer.DONE && transfer.file != null) FileOpener.open(this, transfer.file);
        });
        // Newest on top.
        transfersBox.addView(Ui.margin(this, row, 0, 0, 0, 8), 0);
        return views;
    }

    // ---------------------------------------------------------------- Picker

    private void openPicker(String actionLabel) {
        if (picker != null) return;
        picker = new PickerView(this, this, actionLabel);
        frame.addView(picker.root);
    }

    private void closePicker() {
        if (picker == null) return;
        frame.removeView(picker.root);
        picker.destroy();
        picker = null;
    }

    private void closeOverlays() {
        closeScanner();
        closePicker();
    }

    @Override
    public void onPickerClosed() {
        closePicker();
    }

    @Override
    public void onPicked(List<PickerView.Item> items) {
        closePicker();
        if (screen == Screen.HOST && Session.server != null) {
            for (PickerView.Item item : items) Session.server.link(item.name, item.file);
            refreshHostFiles();
            toast(items.size() == 1 ? "1 item shared" : items.size() + " items shared");
        } else if (screen == Screen.PEER && Session.peerBase != null) {
            String base = Session.peerBase;
            for (PickerView.Item item : items) {
                String name = item.name;
                File file = item.file;
                Session.addTransfer(name, true, progress -> {
                    PeerClient.upload(base, name, file, progress);
                    return null;
                });
            }
            renderTransfers();
        }
    }

    @Override
    public boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception error) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    // ---------------------------------------------------------------- Permissions and radios

    private boolean phoneLinkSupported() {
        if (P2p.supported(this)) return true;
        new AlertDialog.Builder(this)
                .setTitle("Not available on this phone")
                .setMessage("Phone to phone sharing needs Android 10 or newer with Wi-Fi Direct. You can still use Share to desktop.")
                .setPositiveButton("OK", null)
                .show();
        return false;
    }

    /** Wi-Fi Direct needs the nearby-devices permission (Android 13+) or location (older). */
    private void withNearbyPermission(Runnable then) {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
        Runnable next = () -> {
            if (Build.VERSION.SDK_INT < 33 && !locationEnabled()) {
                new AlertDialog.Builder(this)
                        .setTitle("Turn on Location")
                        .setMessage("Android only lets apps find nearby Wi-Fi devices while Location is switched on. Turn it on, then try again.")
                        .setPositiveButton("Open settings", (dialog, which) ->
                                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
            then.run();
        };
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            next.run();
        } else {
            afterPermission = next;
            requestPermissions(new String[]{permission}, REQ_PERMISSIONS);
        }
    }

    private boolean locationEnabled() {
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) return true;
        if (Build.VERSION.SDK_INT >= 28) return manager.isLocationEnabled();
        return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, 0) != 0;
    }

    /** Android 10+ no longer lets apps switch Wi-Fi on silently, so show the system Wi-Fi panel. */
    @SuppressWarnings("deprecation")
    private void withWifiOn(Runnable then) {
        WifiManager wifi = wifiManager();
        if (wifi.isWifiEnabled()) {
            then.run();
        } else if (Build.VERSION.SDK_INT >= 29) {
            afterWifi = then;
            toast("Turn on Wi-Fi to continue");
            startActivityForResult(new Intent(Settings.Panel.ACTION_WIFI), REQ_WIFI_PANEL);
        } else {
            wifi.setWifiEnabled(true);
            toast("Turning on Wi-Fi…");
            handler.postDelayed(then, 2500);
        }
    }

    /** Starts the foreground service that keeps the share alive once the app is left. */
    private void runInBackground() {
        ShareService.start(this);
        // The share runs either way; the permission only decides whether its notification is visible.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_STORAGE) {
            if (picker != null) picker.refresh();
            return;
        }
        if (requestCode != REQ_PERMISSIONS) return;
        Runnable next = afterPermission;
        afterPermission = null;
        boolean granted = results.length > 0;
        for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (granted && next != null) {
            next.run();
        } else if (!granted) {
            toast("That permission is needed for this step.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_WIFI_PANEL) return;
        Runnable next = afterWifi;
        afterWifi = null;
        if (next == null) return;
        if (wifiManager().isWifiEnabled()) {
            handler.postDelayed(next, 800);
        } else {
            toast("Wi-Fi is still off.");
        }
    }

    // ---------------------------------------------------------------- Shared pieces

    /** Ends the running share for good (as opposed to just leaving the app). */
    private void endSession() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(transferTick);
        stopPulse();
        closeOverlays();
        joining = false;
        transferViews.clear();
        Session.stop(this);
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
    }

    private void startTicker() {
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 600);
    }

    /** Replaces the screen with a new scrolling page; a title adds a top bar with a back arrow. */
    private LinearLayout page(String title) {
        closeOverlays();
        LinearLayout outer = Ui.column(this);
        if (title != null) {
            LinearLayout bar = Ui.row(this);
            bar.setPadding(dp(8), dp(8), dp(20), dp(4));
            ImageView back = Ui.icon(this, R.drawable.ic_back, Ui.INK);
            back.setPadding(dp(12), dp(12), dp(12), dp(12));
            back.setBackground(Ui.ripple(this, 0x00FFFFFF, 24));
            back.setContentDescription("Back");
            back.setOnClickListener(v -> onBackPressed());
            bar.addView(back, Ui.lp(dp(48), dp(48)));
            bar.addView(Ui.text(this, title, 20, Ui.INK, true));
            outer.addView(bar);
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = Ui.column(this);
        body.setPadding(dp(20), dp(8), dp(20), dp(28));
        scroll.addView(body);
        outer.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        frame.removeAllViews();
        frame.addView(outer);
        return body;
    }

    private TextView sectionLabel(String value) {
        TextView label = Ui.text(this, value, 14, Ui.MUTED, true);
        label.setPadding(dp(6), dp(24), dp(6), dp(8));
        return label;
    }

    private TextView emptyRow(String message) {
        TextView view = Ui.text(this, message, 14, Ui.MUTED, false);
        view.setPadding(dp(12), dp(14), dp(12), dp(14));
        return view;
    }

    private LinearLayout fileRow(int icon, String name, String meta) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(8), dp(8), dp(4), dp(8));
        ImageView image = Ui.icon(this, icon, Ui.MUTED);
        image.setPadding(dp(10), dp(10), dp(10), dp(10));
        image.setBackground(Ui.shape(this, Ui.BG, 14));
        row.addView(image, Ui.lp(dp(42), dp(42)));
        LinearLayout texts = Ui.column(this);
        texts.setPadding(dp(12), 0, dp(8), 0);
        texts.addView(Ui.oneLine(Ui.text(this, name, 15, Ui.INK, true)));
        texts.addView(Ui.text(this, meta, 13, Ui.MUTED, false));
        row.addView(texts, Ui.weighted());
        return row;
    }

    /** Back on a live share: stop it, or leave it running and go to the home screen of the phone. */
    private void confirmLeave(String title, String message, Runnable stop) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message + "\n\nYou can also keep it running in the background.")
                .setPositiveButton("Stop", (dialog, which) -> stop.run())
                .setNeutralButton("Keep running", (dialog, which) -> moveTaskToBack(true))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void keepAwake(boolean on) {
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** Downloads/LocalShare when file access is allowed, otherwise the app's own folder. */
    private File receiveDir() {
        if (hasStorageAccess()) {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LocalShare");
            if (dir.isDirectory() || dir.mkdirs()) return dir;
        }
        File dir = new File(getExternalFilesDir(null), "shared-files");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String describeDir(File dir) {
        String root = Environment.getExternalStorageDirectory().getPath();
        String path = dir.getPath();
        return path.startsWith(root) ? "Internal storage" + path.substring(root.length()) : path;
    }

    private WifiManager wifiManager() {
        return (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
    }

    private String deviceName() {
        String name = null;
        try {
            name = Settings.Global.getString(getContentResolver(), "device_name");
        } catch (Exception ignored) {
        }
        return name == null || name.trim().isEmpty() ? Build.MODEL : name;
    }

    /** The phone's Wi-Fi (or hotspot) address; mobile-data and VPN interfaces are skipped. */
    private String localIpAddress() {
        String fallback = null;
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun")
                        || name.startsWith("dummy") || name.startsWith("p2p")) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    String host = address.getHostAddress();
                    if (address.isLoopbackAddress() || host == null || host.contains(":")) continue;
                    if (name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap")) return host;
                    if (fallback == null) fallback = host;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
