package com.vijay.localfileshare;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.os.SystemClock;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements PickerView.Host {
    private static final int PORT = 3478;
    private static final int REQ_PERMISSIONS = 51;
    private static final int REQ_WIFI_PANEL = 52;
    private static final int REQ_STORAGE = 53;

    private enum Screen { HOME, PHONE, HOST, FIND, PEER }

    private interface Job {
        void run(PeerClient.Progress progress) throws Exception;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private FrameLayout frame;
    private Screen screen = Screen.HOME;
    private PickerView picker;
    private Runnable afterPermission;
    private Runnable afterWifi;

    private LocalShareServer server;
    private P2p p2p;

    // Host screen (share to desktop, or send to phone)
    private boolean hostIsPhone;
    private TextView hostTitle;
    private TextView hostDetail;
    private LinearLayout hostFiles;
    private String hostSignature;
    private String hostAddress;

    // Receive: looking for senders
    private TextView findStatus;
    private LinearLayout findList;
    private ValueAnimator pulse;
    private boolean joining;

    // Receive: connected to a sender
    private String peerBase;
    private String peerName = "";
    private TextView peerStatus;
    private TextView transfersLabel;
    private LinearLayout transfersBox;
    private LinearLayout peerFiles;
    private TextView saveAll;
    private String peerSignature;
    private List<PeerClient.RemoteFile> remoteFiles = Collections.emptyList();
    private int peerFailures;
    private boolean peerPolling;
    private ExecutorService transferQueue;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (screen == Screen.HOST) {
                refreshHostFiles();
                if (p2p != null) p2p.pollHost();
                if (!hostIsPhone) showDesktopAddress();
            } else if (screen == Screen.PEER) {
                pollPeer();
            } else {
                return;
            }
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);
        // Keep content clear of the status and navigation bars (edge-to-edge on Android 15+).
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(frame);
        showHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (picker != null) picker.refresh();
    }

    @Override
    protected void onDestroy() {
        stopSessions();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (picker != null) {
            if (!picker.onBack()) closePicker();
            return;
        }
        switch (screen) {
            case PHONE:
                showHome();
                break;
            case FIND:
                stopSessions();
                showPhone();
                break;
            case HOST:
                confirmLeave("Stop sharing?", "Other devices will lose access to the files you shared.",
                        () -> {
                            boolean phone = hostIsPhone;
                            stopSessions();
                            if (phone) showPhone(); else showHome();
                        });
                break;
            case PEER:
                confirmLeave("Disconnect?", "Transfers that are still running will be cancelled.", () -> {
                    stopSessions();
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
                "Find the sending phone nearby and connect to it.",
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

    private void startHost(boolean phone, boolean fiveGhz) {
        stopSessions();
        screen = Screen.HOST;
        hostIsPhone = phone;
        hostAddress = null;
        keepAwake(true);

        LinearLayout body = page(phone ? "Send to phone" : "Share to desktop");

        LinearLayout status = Ui.card(this);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.iconCircle(this, phone ? R.drawable.ic_wifi : R.drawable.ic_desktop, 52, Ui.PRIMARY_SOFT, Ui.PRIMARY));
        LinearLayout texts = Ui.column(this);
        texts.setPadding(dp(16), 0, 0, 0);
        hostTitle = Ui.text(this, phone ? "Starting…" : "", 18, Ui.INK, true);
        hostDetail = Ui.text(this, "", 14, Ui.MUTED, false);
        texts.addView(hostTitle);
        texts.addView(Ui.margin(this, hostDetail, 0, 3, 0, 0));
        head.addView(texts, Ui.weighted());
        status.addView(head);
        if (!phone) {
            TextView copy = Ui.button(this, "Copy address", false);
            copy.setOnClickListener(v -> copyAddress());
            LinearLayout.LayoutParams params = Ui.lp(Ui.WRAP, Ui.WRAP);
            params.topMargin = dp(16);
            status.addView(copy, params);
        }
        body.addView(status);

        TextView add = Ui.button(this, "Add files or apps", true);
        add.setOnClickListener(v -> openPicker("Share"));
        body.addView(Ui.margin(this, add, 0, 16, 0, 0));

        body.addView(sectionLabel("Shared in this session"));
        hostFiles = Ui.card(this);
        hostFiles.setPadding(dp(8), dp(8), dp(8), dp(8));
        body.addView(hostFiles);

        File saveDir = receiveDir();
        body.addView(Ui.margin(this, Ui.text(this,
                "Files sent to this phone are saved in " + describeDir(saveDir) + ".", 13, Ui.MUTED, false), 6, 14, 6, 0));

        LocalShareServer started = new LocalShareServer(getApplicationContext(), PORT, saveDir);
        server = started;
        io.execute(() -> {
            try {
                started.start();
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (server != started) return;
                    hostTitle.setText("Couldn't start sharing");
                    hostDetail.setText(String.valueOf(error.getMessage()));
                });
            }
        });

        if (phone) {
            p2p = new P2p(this);
            p2p.startHost(deviceName(), fiveGhz, new P2p.Events() {
                private String visibleAs = "";
                private String network = "";

                @Override
                void onHostReady(String networkName, String password, boolean onFiveGhz) {
                    visibleAs = networkName.startsWith(P2p.PREFIX) ? networkName.substring(P2p.PREFIX.length()) : networkName;
                    network = "Visible as " + visibleAs + "  ·  " + (onFiveGhz ? "5 GHz" : "2.4 GHz")
                            + "\nWi-Fi name: " + networkName + "\nPassword: " + password;
                    if (fiveGhz && !onFiveGhz) toast("5 GHz isn't available right now, using 2.4 GHz.");
                }

                @Override
                void onClients(int count) {
                    hostTitle.setText(count == 0 ? "Waiting for the other phone"
                            : count == 1 ? "1 phone connected" : count + " phones connected");
                    hostDetail.setText(count == 0
                            ? "On the other phone tap Share to phone, then Receive.\n\n" + network
                            : network);
                }

                @Override
                void onError(String message) {
                    hostTitle.setText("Couldn't start the link");
                    hostDetail.setText(message);
                }
            });
        } else {
            showDesktopAddress();
        }

        hostSignature = null;
        refreshHostFiles();
        startTicker();
    }

    private void showDesktopAddress() {
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
                if (server != null) server.remove(entry.name);
                refreshHostFiles();
            });
            row.addView(remove, Ui.lp(dp(40), dp(40)));
            hostFiles.addView(row);
        }
    }

    // ---------------------------------------------------------------- Receive

    private void beginReceive() {
        if (!phoneLinkSupported()) return;
        withNearbyPermission(() -> withWifiOn(this::startFind));
    }

    private void startFind() {
        stopSessions();
        screen = Screen.FIND;
        joining = false;
        keepAwake(true);

        LinearLayout body = page("Receive");
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout radar = new FrameLayout(this);
        View ring = new View(this);
        ring.setBackground(Ui.shape(this, Ui.PRIMARY_SOFT, 80));
        radar.addView(ring, new FrameLayout.LayoutParams(dp(160), dp(160), Gravity.CENTER));
        ImageView center = Ui.icon(this, R.drawable.ic_wifi, 0xFFFFFFFF);
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
        body.addView(Ui.margin(this, hint, 0, 0, 0, 20));

        findList = Ui.column(this);
        body.addView(findList, Ui.lp(Ui.MATCH, Ui.WRAP));

        p2p = new P2p(this);
        p2p.startFind(new P2p.Events() {
            @Override
            void onFound(String networkName, String displayName) {
                if (screen == Screen.FIND && !joining) addSender(networkName, displayName);
            }

            @Override
            void onConnected(String hostAddress) {
                if (screen == Screen.FIND) showPeer(hostAddress);
            }

            @Override
            void onLost() {
                if (screen == Screen.PEER && peerStatus != null) {
                    peerStatus.setText("Connection lost. Go back and connect again.");
                    peerStatus.setTextColor(Ui.DANGER);
                }
            }

            @Override
            void onError(String message) {
                if (screen != Screen.FIND) return;
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

        TextView avatar = Ui.text(this, displayName.substring(0, 1).toUpperCase(java.util.Locale.ROOT), 18, 0xFFFFFFFF, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(Ui.shape(this, Ui.PRIMARY, 24));
        row.addView(avatar, Ui.lp(dp(48), dp(48)));

        LinearLayout texts = Ui.column(this);
        texts.setPadding(dp(14), 0, 0, 0);
        texts.addView(Ui.oneLine(Ui.text(this, displayName, 17, Ui.INK, true)));
        texts.addView(Ui.text(this, "Tap to connect", 14, Ui.MUTED, false));
        row.addView(texts, Ui.weighted());

        row.setOnClickListener(v -> {
            if (joining || p2p == null) return;
            joining = true;
            peerName = displayName;
            findStatus.setText("Connecting to " + displayName + "…");
            findList.setAlpha(0.5f);
            p2p.connect(networkName);
        });
        findList.addView(Ui.margin(this, row, 0, 0, 0, 10));
    }

    private void showPeer(String hostAddress) {
        screen = Screen.PEER;
        stopPulse();
        peerBase = "http://" + hostAddress + ":" + PORT;
        peerSignature = null;
        peerFailures = 0;
        peerPolling = false;
        remoteFiles = Collections.emptyList();
        transferQueue = Executors.newSingleThreadExecutor();

        LinearLayout body = page("Connected");

        LinearLayout status = Ui.card(this);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.iconCircle(this, R.drawable.ic_phone, 52, Ui.PRIMARY_SOFT, Ui.PRIMARY));
        LinearLayout texts = Ui.column(this);
        texts.setPadding(dp(16), 0, 0, 0);
        texts.addView(Ui.text(this, "Connected to " + peerName, 18, Ui.INK, true));
        peerStatus = Ui.text(this, "Files you save go to " + describeDir(receiveDir()) + ".", 14, Ui.MUTED, false);
        texts.addView(Ui.margin(this, peerStatus, 0, 3, 0, 0));
        head.addView(texts, Ui.weighted());
        status.addView(head);
        body.addView(status);

        TextView send = Ui.button(this, "Send files or apps", true);
        send.setOnClickListener(v -> openPicker("Send"));
        body.addView(Ui.margin(this, send, 0, 16, 0, 0));

        transfersLabel = sectionLabel("Transfers");
        transfersLabel.setVisibility(View.GONE);
        body.addView(transfersLabel);
        transfersBox = Ui.column(this);
        body.addView(transfersBox);

        LinearLayout labelRow = Ui.row(this);
        TextView label = sectionLabel("Files from " + peerName);
        labelRow.addView(label, Ui.weighted());
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
    }

    private void pollPeer() {
        if (peerPolling || peerBase == null) return;
        peerPolling = true;
        String base = peerBase;
        io.execute(() -> {
            List<PeerClient.RemoteFile> listed = null;
            try {
                listed = PeerClient.list(base);
            } catch (Exception ignored) {
            }
            List<PeerClient.RemoteFile> result = listed;
            runOnUiThread(() -> {
                peerPolling = false;
                if (screen != Screen.PEER || !base.equals(peerBase)) return;
                if (result == null) {
                    if (++peerFailures >= 4) {
                        peerStatus.setText("Can't reach " + peerName + ". Go back and connect again.");
                        peerStatus.setTextColor(Ui.DANGER);
                    }
                    return;
                }
                if (peerFailures >= 4) {
                    peerStatus.setText("Files you save go to " + describeDir(receiveDir()) + ".");
                    peerStatus.setTextColor(Ui.MUTED);
                }
                peerFailures = 0;
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
            peerFiles.addView(emptyRow("Waiting for " + peerName + " to share something."));
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

    private void save(PeerClient.RemoteFile file) {
        String base = peerBase;
        addTransfer(file.name, false, progress -> {
            File saved = PeerClient.download(base, file, receiveDir(), progress);
            MediaScannerConnection.scanFile(getApplicationContext(), new String[]{saved.getPath()}, null, null);
        });
    }

    /** Queues one upload or download and shows a row with its progress. */
    private void addTransfer(String name, boolean upload, Job job) {
        if (transferQueue == null || transfersBox == null) return;
        transfersLabel.setVisibility(View.VISIBLE);

        LinearLayout row = Ui.card(this);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout top = Ui.row(this);
        top.addView(Ui.icon(this, upload ? R.drawable.ic_up : R.drawable.ic_down, Ui.PRIMARY), Ui.lp(dp(18), dp(18)));
        TextView title = Ui.oneLine(Ui.text(this, name, 15, Ui.INK, true));
        title.setPadding(dp(10), 0, dp(10), 0);
        top.addView(title, Ui.weighted());
        TextView state = Ui.text(this, "Waiting", 13, Ui.MUTED, false);
        top.addView(state);
        row.addView(top);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.PRIMARY));
        row.addView(Ui.margin(this, bar, 0, 8, 0, 0));
        transfersBox.addView(Ui.margin(this, row, 0, 0, 0, 8), 0);

        long[] lastUpdate = {0};
        transferQueue.execute(() -> {
            try {
                job.run((done, total) -> {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastUpdate[0] < 120 && done < total) return;
                    lastUpdate[0] = now;
                    handler.post(() -> {
                        bar.setProgress(total > 0 ? (int) (done * 1000 / total) : 0);
                        state.setText(Ui.formatBytes(done) + " / " + Ui.formatBytes(total));
                    });
                });
                handler.post(() -> {
                    bar.setProgress(1000);
                    state.setText(upload ? "Sent" : "Saved");
                    state.setTextColor(Ui.OK);
                });
            } catch (Exception error) {
                handler.post(() -> {
                    state.setText("Failed");
                    state.setTextColor(Ui.DANGER);
                });
            }
        });
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

    @Override
    public void onPickerClosed() {
        closePicker();
    }

    @Override
    public void onPicked(List<PickerView.Item> items) {
        closePicker();
        if (screen == Screen.HOST && server != null) {
            for (PickerView.Item item : items) server.link(item.name, item.file);
            refreshHostFiles();
            toast(items.size() == 1 ? "1 item shared" : items.size() + " items shared");
        } else if (screen == Screen.PEER) {
            String base = peerBase;
            for (PickerView.Item item : items) {
                addTransfer(item.name, true, progress -> PeerClient.upload(base, item.name, item.file, progress));
            }
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
            toast("Permission is needed to find and connect to nearby phones.");
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

    private void stopSessions() {
        handler.removeCallbacks(tick);
        stopPulse();
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
        peerBase = null;
        joining = false;
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
        if (picker != null) {
            picker.destroy();
            picker = null;
        }
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

    private void confirmLeave(String title, String message, Runnable leave) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Yes", (dialog, which) -> leave.run())
                .setNegativeButton("Stay", null)
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
                String name = network.getName().toLowerCase(java.util.Locale.ROOT);
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
