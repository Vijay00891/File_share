package com.vijay.localfileshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PICK_FILES = 41;
    private static final int SAVE_FILE = 42;
    private static final int PORT = 3478;

    private LocalShareServer server;
    private File sharedDir;
    private File pendingDownloadFile;
    private TextView statusText;
    private TextView addressText;
    private LinearLayout fileListView;
    private Button toggleButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedDir = new File(getExternalFilesDir(null), "shared-files");
        if (!sharedDir.exists()) sharedDir.mkdirs();
        buildUi();
        refreshState();
        refreshFiles();
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == SAVE_FILE) {
            savePendingDownload(data.getData());
            return;
        }

        if (requestCode != PICK_FILES) return;

        try {
            if (data.getClipData() != null) {
                ClipData clipData = data.getClipData();
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    copyUriToSharedFolder(clipData.getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                copyUriToSharedFolder(data.getData());
            }
            refreshFiles();
            Toast.makeText(this, "Files added", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "Could not add file: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(color("#0B1020"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        scrollView.addView(root);

        TextView eyebrow = label("LOCAL NETWORK");
        TextView title = text("File Share", 36, "#EEF1FB", true);
        statusText = text("", 16, "#97A1BF", true);
        addressText = text("", 17, "#EEF1FB", true);

        LinearLayout panel = panel();
        panel.addView(text("Server", 22, "#EEF1FB", true));
        panel.addView(statusText);
        panel.addView(addressText);

        toggleButton = primaryButton("Start server");
        Button copyButton = secondaryButton("Copy address");
        Button pickButton = secondaryButton("Add phone files");
        Button appsButton = secondaryButton("Add installed apps");
        Button refreshButton = secondaryButton("Refresh files");

        toggleButton.setOnClickListener(v -> toggleServer());
        copyButton.setOnClickListener(v -> copyAddress());
        pickButton.setOnClickListener(v -> openFilePicker());
        appsButton.setOnClickListener(v -> showAppPicker());
        refreshButton.setOnClickListener(v -> refreshFiles());

        panel.addView(toggleButton);
        panel.addView(copyButton);
        panel.addView(pickButton);
        panel.addView(appsButton);
        panel.addView(refreshButton);

        LinearLayout filesPanel = panel();
        filesPanel.addView(text("Shared files", 22, "#EEF1FB", true));
        fileListView = new LinearLayout(this);
        fileListView.setOrientation(LinearLayout.VERTICAL);
        filesPanel.addView(fileListView);

        root.addView(eyebrow);
        root.addView(title);
        root.addView(panel);
        root.addView(filesPanel);
        setContentView(scrollView);
    }

    private LinearLayout panel() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        view.setBackground(rounded("#181E36", "#00000000", 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(18), 0, 0);
        view.setLayoutParams(params);
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 12, "#19C3B1", true);
        view.setGravity(Gravity.START);
        return view;
    }

    private TextView text(String value, int sp, String hex, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color(hex));
        view.setPadding(0, dp(4), 0, dp(4));
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setBackground(gradient(14));
        button.setStateListAnimator(null);
        button.setAllCaps(false);
        button.setPadding(0, dp(8), 0, dp(8));
        button.setLayoutParams(buttonParams());
        return button;
    }

    private Button secondaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(color("#EEF1FB"));
        button.setBackground(rounded("#252D4D", "#00000000", 14));
        button.setStateListAnimator(null);
        button.setAllCaps(false);
        button.setPadding(0, dp(8), 0, dp(8));
        button.setLayoutParams(buttonParams());
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(10), 0, 0);
        return params;
    }

    private void toggleServer() {
        if (server != null && server.isRunning()) {
            server.stop();
            refreshState();
            return;
        }

        toggleButton.setEnabled(false);
        statusText.setText("Starting...");
        new Thread(() -> {
            try {
                LocalShareServer nextServer = new LocalShareServer(getApplicationContext(), PORT, sharedDir);
                nextServer.start();
                server = nextServer;
                runOnUiThread(() -> {
                    toggleButton.setEnabled(true);
                    refreshState();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    toggleButton.setEnabled(true);
                    refreshState();
                    Toast.makeText(this, "Could not start server: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "server-start").start();
    }

    private void refreshState() {
        boolean running = server != null && server.isRunning();
        String address = getServerAddress();
        toggleButton.setText(running ? "Stop server" : "Start server");
        statusText.setText(running ? "Online. Keep this app open while sharing." : "Offline");
        addressText.setText(running ? address : "Start the server to get a local address.");
    }

    private void refreshFiles() {
        File[] files = sharedDir.listFiles(file -> file.isFile());
        fileListView.removeAllViews();
        if (files == null || files.length == 0) {
            fileListView.addView(text("No files shared yet.", 15, "#97A1BF", false));
            return;
        }

        for (File file : files) {
            fileListView.addView(fileRow(file));
        }
    }

    private LinearLayout fileRow(File file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(8));

        TextView name = text(file.getName(), 16, "#EEF1FB", true);
        TextView meta = text(formatBytes(file.length()), 14, "#97A1BF", false);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.START);

        Button downloadButton = compactButton("Download");
        Button deleteButton = compactButton("Delete");

        downloadButton.setOnClickListener(v -> downloadToDevice(file));
        deleteButton.setOnClickListener(v -> {
            if (file.delete()) {
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Could not delete file", Toast.LENGTH_SHORT).show();
            }
            refreshFiles();
        });

        actions.addView(downloadButton);
        actions.addView(deleteButton);
        row.addView(name);
        row.addView(meta);
        row.addView(actions);
        return row;
    }

    private Button compactButton(String value) {
        Button button = secondaryButton(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        );
        params.setMargins(0, dp(8), dp(8), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void downloadToDevice(File file) {
        pendingDownloadFile = file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, file.getName());
        startActivityForResult(intent, SAVE_FILE);
    }

    private void savePendingDownload(Uri targetUri) {
        if (pendingDownloadFile == null || targetUri == null) return;

        try (InputStream input = new java.io.FileInputStream(pendingDownloadFile);
             OutputStream output = getContentResolver().openOutputStream(targetUri)) {
            if (output == null) throw new IllegalStateException("No output stream");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            Toast.makeText(this, "Downloaded", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "Download failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            pendingDownloadFile = null;
        }
    }

    private static class AppEntry {
        String label;
        String sourcePath;
    }

    // Lists launchable user apps, lets the person tick some, and copies their APKs
    // into the shared folder so other devices can download them.
    private void showAppPicker() {
        Toast.makeText(this, "Loading apps...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<AppEntry> apps = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
                ApplicationInfo app = info.activityInfo.applicationInfo;
                boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (system || !seen.add(app.packageName)) continue;
                AppEntry entry = new AppEntry();
                entry.label = String.valueOf(pm.getApplicationLabel(app));
                entry.sourcePath = app.sourceDir;
                apps.add(entry);
            }
            Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> showAppDialog(apps));
        }, "app-list").start();
    }

    private void showAppDialog(List<AppEntry> apps) {
        if (apps.isEmpty()) {
            Toast.makeText(this, "No installed apps found", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = apps.get(i).label + "  (" + formatBytes(new File(apps.get(i).sourcePath).length()) + ")";
        }
        boolean[] checked = new boolean[names.length];
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Share installed apps")
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Add", (dialog, which) -> {
                    List<AppEntry> chosen = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) chosen.add(apps.get(i));
                    copyApps(chosen);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copyApps(List<AppEntry> chosen) {
        if (chosen.isEmpty()) return;
        Toast.makeText(this, "Copying " + chosen.size() + " app(s)...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int ok = 0;
            for (AppEntry app : chosen) {
                File target = uniqueFile(sharedDir, LocalShareServer.safeFileName(app.label + ".apk"));
                try (InputStream input = new java.io.FileInputStream(app.sourcePath);
                     FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    ok++;
                } catch (Exception error) {
                    target.delete();
                }
            }
            final int done = ok;
            runOnUiThread(() -> {
                refreshFiles();
                Toast.makeText(this, done + " of " + chosen.size() + " app(s) added", Toast.LENGTH_LONG).show();
            });
        }, "app-copy").start();
    }

    private void copyAddress() {
        String address = getServerAddress();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Local file share address", address));
        Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_FILES);
    }

    private void copyUriToSharedFolder(Uri uri) throws Exception {
        String name = displayName(uri);
        File target = uniqueFile(sharedDir, LocalShareServer.safeFileName(name));
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        }
        String value = uri.getLastPathSegment();
        return value == null ? "file" : value;
    }

    private File uniqueFile(File dir, String name) {
        File target = new File(dir, name);
        if (!target.exists()) return target;

        String base = name;
        String ext = "";
        int dot = name.lastIndexOf(".");
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }

        int count = 1;
        while (target.exists()) {
            target = new File(dir, base + " (" + count + ")" + ext);
            count++;
        }
        return target;
    }

    private String getServerAddress() {
        String ip = localIpAddress();
        return "http://" + ip + ":" + PORT;
    }

    private String localIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface networkInterface : interfaces) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                List<java.net.InetAddress> addresses = Collections.list(networkInterface.getInetAddresses());
                for (java.net.InetAddress address : addresses) {
                    String host = address.getHostAddress();
                    if (!address.isLoopbackAddress() && host.indexOf(":") < 0) return host;
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        int unit = -1;
        do {
            value = value / 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unit]);
    }

    private GradientDrawable gradient(int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{color("#6D5EFC"), color("#19A7E0")});
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable rounded(String fill, String stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(fill));
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int color(String hex) {
        return Color.parseColor(hex);
    }
}
