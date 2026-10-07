package com.vijay.localfileshare;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.text.format.DateFormat;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The app's own chooser: browse phone storage or pick installed apps, with multi-select. */
class PickerView {
    interface Host {
        boolean hasStorageAccess();

        void requestStorageAccess();

        void onPicked(List<Item> items);

        void onPickerClosed();
    }

    static class Item {
        final String name;
        final File file;

        Item(String name, File file) {
            this.name = name;
            this.file = file;
        }
    }

    private static class AppRow {
        String label;
        File apk;
        Drawable icon;
    }

    private static final Set<String> IMAGE_TYPES = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "webp", "gif", "bmp"));

    final LinearLayout root;
    private final Activity activity;
    private final Host host;
    private final File storageRoot = Environment.getExternalStorageDirectory();
    private final Map<String, Item> selected = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(120);

    private final TextView filesTab;
    private final TextView appsTab;
    private final LinearLayout filesPane;
    private final LinearLayout browser;
    private final LinearLayout permissionPane;
    private final LinearLayout appsPane;
    private final TextView pathText;
    private final TextView emptyText;
    private final TextView appsStatus;
    private final TextView summary;
    private final TextView sendButton;
    private final FileAdapter fileAdapter = new FileAdapter();
    private final AppAdapter appAdapter = new AppAdapter();

    private File currentDir = storageRoot;
    private List<File> files = new ArrayList<>();
    private List<AppRow> apps = new ArrayList<>();
    private boolean appsLoaded;
    private int listToken;

    PickerView(Activity activity, Host host, String actionLabel) {
        this.activity = activity;
        this.host = host;

        root = Ui.column(activity);
        root.setBackgroundColor(Ui.BG);
        root.setClickable(true);

        LinearLayout bar = Ui.row(activity);
        bar.setPadding(dp(8), dp(8), dp(20), dp(4));
        ImageView close = Ui.icon(activity, R.drawable.ic_close, Ui.INK);
        close.setPadding(dp(12), dp(12), dp(12), dp(12));
        close.setBackground(Ui.ripple(activity, 0x00FFFFFF, 24));
        close.setOnClickListener(v -> host.onPickerClosed());
        bar.addView(close, Ui.lp(dp(48), dp(48)));
        bar.addView(Ui.text(activity, "Choose what to send", 20, Ui.INK, true));
        root.addView(bar);

        LinearLayout tabs = Ui.row(activity);
        tabs.setPadding(dp(20), dp(8), dp(20), dp(8));
        filesTab = tab("Files");
        appsTab = tab("Apps");
        tabs.addView(filesTab);
        tabs.addView(Ui.margin(activity, appsTab, 8, 0, 0, 0));
        root.addView(tabs);
        filesTab.setOnClickListener(v -> showTab(true));
        appsTab.setOnClickListener(v -> showTab(false));

        FrameLayout content = new FrameLayout(activity);
        root.addView(content, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));

        // Files tab
        filesPane = Ui.column(activity);
        content.addView(filesPane);

        browser = Ui.column(activity);
        filesPane.addView(browser, Ui.lp(Ui.MATCH, Ui.MATCH));

        HorizontalScrollView chipScroll = new HorizontalScrollView(activity);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(activity);
        chips.setPadding(dp(20), dp(4), dp(12), dp(8));
        chipScroll.addView(chips);
        addChip(chips, "Internal storage", storageRoot);
        addChip(chips, "Downloads", publicDir(Environment.DIRECTORY_DOWNLOADS));
        addChip(chips, "Camera", new File(publicDir(Environment.DIRECTORY_DCIM), "Camera"));
        addChip(chips, "Pictures", publicDir(Environment.DIRECTORY_PICTURES));
        addChip(chips, "Videos", publicDir(Environment.DIRECTORY_MOVIES));
        addChip(chips, "Music", publicDir(Environment.DIRECTORY_MUSIC));
        addChip(chips, "Documents", publicDir(Environment.DIRECTORY_DOCUMENTS));
        browser.addView(chipScroll);

        pathText = Ui.oneLine(Ui.text(activity, "", 13, Ui.MUTED, false));
        pathText.setPadding(dp(24), dp(2), dp(24), dp(8));
        browser.addView(pathText);

        FrameLayout listFrame = new FrameLayout(activity);
        browser.addView(listFrame, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));
        ListView fileList = listView();
        fileList.setAdapter(fileAdapter);
        fileList.setOnItemClickListener((parent, view, position, id) -> onFileClick(files.get(position)));
        listFrame.addView(fileList);
        emptyText = Ui.text(activity, "", 15, Ui.MUTED, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(dp(32), dp(48), dp(32), 0);
        listFrame.addView(emptyText);

        permissionPane = Ui.column(activity);
        permissionPane.setGravity(Gravity.CENTER);
        permissionPane.setPadding(dp(32), dp(32), dp(32), dp(32));
        permissionPane.addView(Ui.iconCircle(activity, R.drawable.ic_folder, 72, Ui.PRIMARY_SOFT, Ui.PRIMARY));
        TextView permissionTitle = Ui.text(activity, "Allow access to your files", 20, Ui.INK, true);
        permissionTitle.setGravity(Gravity.CENTER);
        permissionPane.addView(Ui.margin(activity, permissionTitle, 0, 20, 0, 8));
        TextView permissionBody = Ui.text(activity,
                "Local Share needs permission to show the files on this phone so you can pick what to send. "
                        + "Nothing leaves the phone until you choose it.", 15, Ui.MUTED, false);
        permissionBody.setGravity(Gravity.CENTER);
        permissionPane.addView(permissionBody);
        TextView allow = Ui.button(activity, "Allow file access", true);
        allow.setOnClickListener(v -> host.requestStorageAccess());
        LinearLayout.LayoutParams allowParams = Ui.lp(Ui.WRAP, Ui.WRAP);
        allowParams.topMargin = dp(24);
        permissionPane.addView(allow, allowParams);
        filesPane.addView(permissionPane, Ui.lp(Ui.MATCH, Ui.MATCH));

        // Apps tab
        appsPane = Ui.column(activity);
        appsPane.setVisibility(View.GONE);
        content.addView(appsPane);
        appsStatus = Ui.text(activity, "Loading apps…", 15, Ui.MUTED, false);
        appsStatus.setGravity(Gravity.CENTER);
        appsStatus.setPadding(dp(32), dp(48), dp(32), dp(16));
        appsPane.addView(appsStatus);
        ListView appList = listView();
        appList.setAdapter(appAdapter);
        appList.setOnItemClickListener((parent, view, position, id) -> {
            AppRow app = apps.get(position);
            toggle(app.apk, app.label + ".apk");
            appAdapter.notifyDataSetChanged();
        });
        appsPane.addView(appList, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1));

        // Bottom bar
        LinearLayout bottom = Ui.row(activity);
        bottom.setBackgroundColor(Ui.SURFACE);
        bottom.setPadding(dp(24), dp(12), dp(20), dp(12));
        summary = Ui.text(activity, "", 14, Ui.MUTED, false);
        bottom.addView(summary, Ui.weighted());
        sendButton = Ui.button(activity, actionLabel, true);
        sendButton.setOnClickListener(v -> {
            if (!selected.isEmpty()) host.onPicked(new ArrayList<>(selected.values()));
        });
        bottom.addView(sendButton, Ui.lp(Ui.WRAP, Ui.WRAP));
        root.addView(bottom);

        showTab(true);
        updateSummary();
    }

    /** Called when the activity resumes, e.g. after the permission screen. */
    void refresh() {
        boolean allowed = host.hasStorageAccess();
        permissionPane.setVisibility(allowed ? View.GONE : View.VISIBLE);
        browser.setVisibility(allowed ? View.VISIBLE : View.GONE);
        if (allowed) open(currentDir);
    }

    /** Back steps up one folder first; returns false when the picker should close. */
    boolean onBack() {
        boolean inFiles = filesPane.getVisibility() == View.VISIBLE;
        if (inFiles && host.hasStorageAccess() && !currentDir.equals(storageRoot)) {
            File parent = currentDir.getParentFile();
            open(parent != null && parent.getPath().startsWith(storageRoot.getPath()) ? parent : storageRoot);
            return true;
        }
        return false;
    }

    void destroy() {
        worker.shutdownNow();
    }

    private void showTab(boolean showFiles) {
        filesPane.setVisibility(showFiles ? View.VISIBLE : View.GONE);
        appsPane.setVisibility(showFiles ? View.GONE : View.VISIBLE);
        styleTab(filesTab, showFiles);
        styleTab(appsTab, !showFiles);
        if (showFiles) {
            refresh();
        } else if (!appsLoaded) {
            appsLoaded = true;
            loadApps();
        }
    }

    private TextView tab(String label) {
        TextView view = Ui.text(activity, label, 14, Ui.INK, true);
        view.setPadding(dp(20), dp(9), dp(20), dp(9));
        view.setClickable(true);
        view.setLayoutParams(Ui.lp(Ui.WRAP, Ui.WRAP));
        return view;
    }

    private void styleTab(TextView view, boolean active) {
        view.setBackground(Ui.ripple(activity, active ? Ui.PRIMARY : Ui.SURFACE, 20));
        view.setTextColor(active ? Ui.ON_PRIMARY : Ui.INK);
    }

    private void addChip(LinearLayout chips, String label, File dir) {
        TextView chip = Ui.text(activity, label, 13, Ui.PRIMARY, true);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.setBackground(Ui.ripple(activity, Ui.PRIMARY_SOFT, 18));
        chip.setClickable(true);
        chip.setOnClickListener(v -> open(dir));
        LinearLayout.LayoutParams params = Ui.lp(Ui.WRAP, Ui.WRAP);
        params.rightMargin = dp(8);
        chips.addView(chip, params);
    }

    private ListView listView() {
        ListView list = new ListView(activity);
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(12));
        return list;
    }

    // ---- Files ----

    private void open(File dir) {
        currentDir = dir;
        String shown = dir.getPath().replace(storageRoot.getPath(), "Internal storage").replace("/", "  ›  ");
        pathText.setText(shown);
        final int token = ++listToken;
        worker.execute(() -> {
            File[] children = dir.listFiles();
            List<File> listed = new ArrayList<>();
            if (children != null) {
                for (File child : children) {
                    if (!child.getName().startsWith(".")) listed.add(child);
                }
            }
            Collections.sort(listed, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            activity.runOnUiThread(() -> {
                if (token != listToken) return;
                files = listed;
                fileAdapter.notifyDataSetChanged();
                emptyText.setText(children == null ? "This folder can't be opened." : listed.isEmpty() ? "This folder is empty." : "");
            });
        });
    }

    private void onFileClick(File file) {
        if (file.isDirectory()) {
            open(file);
        } else {
            toggle(file, file.getName());
            fileAdapter.notifyDataSetChanged();
        }
    }

    private void toggle(File file, String name) {
        String key = file.getPath();
        if (selected.remove(key) == null) selected.put(key, new Item(name, file));
        updateSummary();
    }

    private void updateSummary() {
        long total = 0;
        for (Item item : selected.values()) total += item.file.length();
        summary.setText(selected.isEmpty()
                ? "Nothing selected"
                : selected.size() + " selected  ·  " + Ui.formatBytes(total));
        Ui.setEnabled(sendButton, !selected.isEmpty());
    }

    private File publicDir(String type) {
        return Environment.getExternalStoragePublicDirectory(type);
    }

    // ---- Apps ----

    private void loadApps() {
        worker.execute(() -> {
            PackageManager pm = activity.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<AppRow> loaded = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
                ApplicationInfo app = info.activityInfo.applicationInfo;
                boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (system || app.sourceDir == null || !seen.add(app.packageName)) continue;
                AppRow row = new AppRow();
                row.label = String.valueOf(pm.getApplicationLabel(app));
                row.apk = new File(app.sourceDir);
                row.icon = pm.getApplicationIcon(app);
                loaded.add(row);
            }
            Collections.sort(loaded, (a, b) -> a.label.compareToIgnoreCase(b.label));
            activity.runOnUiThread(() -> {
                apps = loaded;
                appAdapter.notifyDataSetChanged();
                appsStatus.setVisibility(loaded.isEmpty() ? View.VISIBLE : View.GONE);
                appsStatus.setText("No installed apps found.");
            });
        });
    }

    // ---- Rows ----

    private static class Holder {
        ImageView image;
        TextView title;
        TextView meta;
        CheckBox check;
    }

    private View rowView(View recycled) {
        if (recycled != null) return recycled;
        LinearLayout row = Ui.row(activity);
        row.setPadding(dp(20), dp(9), dp(16), dp(9));
        Holder holder = new Holder();

        holder.image = new ImageView(activity);
        holder.image.setClipToOutline(true);
        row.addView(holder.image, Ui.lp(dp(46), dp(46)));

        LinearLayout texts = Ui.column(activity);
        texts.setPadding(dp(14), 0, dp(8), 0);
        holder.title = Ui.oneLine(Ui.text(activity, "", 15, Ui.INK, true));
        holder.meta = Ui.text(activity, "", 13, Ui.MUTED, false);
        texts.addView(holder.title);
        texts.addView(holder.meta);
        row.addView(texts, Ui.weighted());

        holder.check = new CheckBox(activity);
        holder.check.setClickable(false);
        holder.check.setFocusable(false);
        holder.check.setButtonTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Ui.PRIMARY, Ui.MUTED}));
        row.addView(holder.check);

        row.setTag(holder);
        return row;
    }

    private void showIcon(ImageView view, int res, int background, int tint) {
        view.setTag(null);
        view.setBackground(Ui.shape(activity, background, 14));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setPadding(dp(11), dp(11), dp(11), dp(11));
        view.setImageResource(res);
        view.setColorFilter(tint);
    }

    private void showPicture(ImageView view, Bitmap bitmap) {
        view.setBackground(Ui.shape(activity, Ui.LINE, 14));
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setPadding(0, 0, 0, 0);
        view.clearColorFilter();
        view.setImageBitmap(bitmap);
    }

    private void loadThumb(ImageView view, File file) {
        String key = file.getPath();
        Bitmap cached = thumbs.get(key);
        if (cached != null) {
            view.setTag(key);
            showPicture(view, cached);
            return;
        }
        showIcon(view, R.drawable.ic_file, Ui.LINE, Ui.MUTED);
        view.setTag(key);
        int size = dp(46);
        worker.execute(() -> {
            Bitmap bitmap = decode(file, size);
            if (bitmap == null) return;
            thumbs.put(key, bitmap);
            view.post(() -> {
                if (key.equals(view.getTag())) showPicture(view, bitmap);
            });
        });
    }

    private static Bitmap decode(File file, int size) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf(".");
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private class FileAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return files.size();
        }

        @Override
        public Object getItem(int position) {
            return files.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            View row = rowView(recycled);
            Holder holder = (Holder) row.getTag();
            File file = files.get(position);
            holder.title.setText(file.getName());
            if (file.isDirectory()) {
                showIcon(holder.image, R.drawable.ic_folder, Ui.PRIMARY_SOFT, Ui.PRIMARY);
                holder.meta.setText("Folder");
                holder.check.setVisibility(View.INVISIBLE);
            } else {
                if (IMAGE_TYPES.contains(extension(file.getName()))) {
                    loadThumb(holder.image, file);
                } else {
                    showIcon(holder.image, R.drawable.ic_file, Ui.LINE, Ui.MUTED);
                }
                holder.meta.setText(Ui.formatBytes(file.length()) + "  ·  "
                        + DateFormat.format("d MMM yyyy", file.lastModified()));
                holder.check.setVisibility(View.VISIBLE);
                holder.check.setChecked(selected.containsKey(file.getPath()));
            }
            return row;
        }
    }

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            View row = rowView(recycled);
            Holder holder = (Holder) row.getTag();
            AppRow app = apps.get(position);
            holder.image.setTag(null);
            holder.image.setBackground(null);
            holder.image.setPadding(0, 0, 0, 0);
            holder.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            holder.image.clearColorFilter();
            holder.image.setImageDrawable(app.icon);
            holder.title.setText(app.label);
            holder.meta.setText(Ui.formatBytes(app.apk.length()));
            holder.check.setVisibility(View.VISIBLE);
            holder.check.setChecked(selected.containsKey(app.apk.getPath()));
            return row;
        }
    }

    private int dp(float value) {
        return Ui.dp(activity, value);
    }
}
