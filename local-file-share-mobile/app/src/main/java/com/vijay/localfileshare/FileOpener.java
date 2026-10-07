package com.vijay.localfileshare;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens a shared or received file in whichever app handles its type.
 *
 * Android does not allow handing raw file paths to other apps, so this is also the small
 * content provider that serves the file. Only files explicitly passed to {@link #open} are
 * reachable, and only by the app chosen to open them.
 */
public class FileOpener extends ContentProvider {
    private static final String AUTHORITY = "com.vijay.localfileshare.files";
    private static final Map<String, File> OPENED = new ConcurrentHashMap<>();

    static void open(Activity activity, File file) {
        if (file == null || !file.isFile()) {
            Toast.makeText(activity, "This file is no longer on the phone.", Toast.LENGTH_SHORT).show();
            return;
        }
        String id = Integer.toHexString(file.getPath().hashCode()) + Long.toHexString(file.length());
        OPENED.put(id, file);
        Uri uri = new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(id).appendPath(file.getName()).build();
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType(file.getName()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(activity, "No app on this phone can open this type of file.", Toast.LENGTH_LONG).show();
        }
    }

    private static String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (extension.equals("apk")) return "application/vnd.android.package-archive";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type != null ? type : "*/*";
    }

    private static File fileFor(Uri uri) {
        List<String> segments = uri.getPathSegments();
        return segments.isEmpty() ? null : OPENED.get(segments.get(0));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        File file = fileFor(uri);
        return file == null ? null : mimeType(file.getName());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = fileFor(uri);
        String[] columns = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        if (file == null) return cursor;
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = fileFor(uri);
        if (file == null || !file.isFile()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
