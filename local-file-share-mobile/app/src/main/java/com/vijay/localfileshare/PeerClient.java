package com.vijay.localfileshare;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Talks to the sender phone's file server from the receiver phone. */
final class PeerClient {
    interface Progress {
        void on(long done, long total);
    }

    static class RemoteFile {
        String name;
        String url;
        long size;
    }

    private PeerClient() {
    }

    static java.util.List<RemoteFile> list(String base) throws Exception {
        HttpURLConnection connection = open(base + "/api/files");
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
            JSONArray array = new JSONObject(bytes.toString("UTF-8")).getJSONArray("files");
            java.util.List<RemoteFile> files = new java.util.ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                RemoteFile file = new RemoteFile();
                file.name = item.getString("name");
                file.url = item.getString("url");
                file.size = item.optLong("size");
                files.add(file);
            }
            return files;
        } finally {
            connection.disconnect();
        }
    }

    /** Files at least this big are fetched over several connections at once. */
    static final long SPLIT_FROM_BYTES = 16L * 1024 * 1024;
    static final int CONNECTIONS = 4;

    /** Thrown when the sender is an older app version that cannot serve parts of a file. */
    private static class NoRangeSupport extends java.io.IOException {
        NoRangeSupport() {
            super("Sender does not support partial downloads");
        }
    }

    static File download(String base, RemoteFile remote, File dir, Progress progress) throws Exception {
        if (!dir.exists()) dir.mkdirs();
        File target = uniqueFile(dir, LocalShareServer.safeFileName(remote.name));
        boolean complete = false;
        try {
            if (remote.size >= SPLIT_FROM_BYTES) {
                try {
                    downloadSplit(base + remote.url, remote.size, target, progress);
                } catch (NoRangeSupport older) {
                    progress.on(0, remote.size);
                    downloadWhole(base + remote.url, remote.size, target, progress);
                }
            } else {
                downloadWhole(base + remote.url, remote.size, target, progress);
            }
            complete = true;
            return target;
        } finally {
            if (!complete) target.delete();
        }
    }

    private static void downloadWhole(String url, long total, File target, Progress progress) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(target)) {
            long done = 0;
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                done += read;
                progress.on(done, total);
            }
            if (total > 0 && done < total) throw new java.io.IOException("Connection dropped");
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Fetches one file as several byte ranges in parallel, each written straight to its place.
     * Wi-Fi links usually carry more in total over a few connections than over a single one.
     */
    private static void downloadSplit(String url, long total, File target, Progress progress) throws Exception {
        try (java.io.RandomAccessFile reserve = new java.io.RandomAccessFile(target, "rw")) {
            reserve.setLength(total);
        }
        final java.util.concurrent.atomic.AtomicLong done = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicReference<Exception> failure = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.List<HttpURLConnection> connections = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        long part = total / CONNECTIONS;
        Thread[] workers = new Thread[CONNECTIONS];
        for (int i = 0; i < CONNECTIONS; i++) {
            final long first = i * part;
            final long last = i == CONNECTIONS - 1 ? total - 1 : first + part - 1;
            workers[i] = new Thread(() -> {
                HttpURLConnection connection = null;
                try {
                    if (failure.get() != null) return;
                    connection = open(url);
                    connection.setRequestProperty("Range", "bytes=" + first + "-" + last);
                    connections.add(connection);
                    int status = connection.getResponseCode();
                    if (status == 200) throw new NoRangeSupport();
                    if (status != 206) throw new java.io.IOException("Sender answered " + status);
                    try (InputStream input = connection.getInputStream();
                         java.io.RandomAccessFile output = new java.io.RandomAccessFile(target, "rw")) {
                        output.seek(first);
                        long remaining = last - first + 1;
                        byte[] buffer = new byte[1024 * 1024];
                        while (remaining > 0) {
                            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            if (read == -1) throw new java.io.IOException("Connection dropped");
                            output.write(buffer, 0, read);
                            remaining -= read;
                            long soFar = done.addAndGet(read);
                            synchronized (done) {
                                progress.on(soFar, total);
                            }
                        }
                    }
                } catch (Exception error) {
                    // The first failure wins; cut the other connections so they stop too.
                    if (failure.compareAndSet(null, error)) {
                        synchronized (connections) {
                            for (HttpURLConnection other : connections) other.disconnect();
                        }
                    }
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }, "download-part-" + i);
            workers[i].start();
        }
        try {
            for (Thread worker : workers) worker.join();
        } catch (InterruptedException cancelled) {
            failure.compareAndSet(null, cancelled);
            synchronized (connections) {
                for (HttpURLConnection other : connections) other.disconnect();
            }
            for (Thread worker : workers) worker.interrupt();
            Thread.currentThread().interrupt();
        }
        if (failure.get() != null) throw failure.get();
        if (done.get() != total) throw new java.io.IOException("Connection dropped");
    }

    static void upload(String base, String name, File file, Progress progress) throws Exception {
        HttpURLConnection connection = open(base + "/api/upload");
        try {
            long total = file.length();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(total);
            connection.setRequestProperty("x-file-name", URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            try (FileInputStream input = new FileInputStream(file);
                 OutputStream output = connection.getOutputStream()) {
                long done = 0;
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    done += read;
                    progress.on(done, total);
                }
            }
            int status = connection.getResponseCode();
            if (status >= 400) throw new java.io.IOException("Server answered " + status);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(30000);
        connection.setUseCaches(false);
        return connection;
    }

    static File uniqueFile(File dir, String name) {
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
            target = new File(dir, base + " (" + count++ + ")" + ext);
        }
        return target;
    }
}
