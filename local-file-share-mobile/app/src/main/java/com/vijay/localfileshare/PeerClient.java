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

    static File download(String base, RemoteFile remote, File dir, Progress progress) throws Exception {
        if (!dir.exists()) dir.mkdirs();
        File target = uniqueFile(dir, LocalShareServer.safeFileName(remote.name));
        HttpURLConnection connection = open(base + remote.url);
        boolean complete = false;
        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(target)) {
            long total = remote.size;
            long done = 0;
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                done += read;
                progress.on(done, total);
            }
            if (total > 0 && done < total) throw new java.io.IOException("Connection dropped");
            complete = true;
            return target;
        } finally {
            connection.disconnect();
            if (!complete) target.delete();
        }
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
                byte[] buffer = new byte[256 * 1024];
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
