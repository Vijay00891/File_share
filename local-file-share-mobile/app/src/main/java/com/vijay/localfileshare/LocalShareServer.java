package com.vijay.localfileshare;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalShareServer {
    private final android.content.Context context;
    private final int port;
    private final File sharedDir;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public LocalShareServer(android.content.Context context, int port, File sharedDir) {
        this.context = context;
        this.port = port;
        this.sharedDir = sharedDir;
    }

    public void start() throws IOException {
        if (!sharedDir.exists()) sharedDir.mkdirs();
        serverSocket = new ServerSocket(port);
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    workers.execute(() -> handle(socket));
                } catch (IOException ignored) {
                    if (running) running = false;
                }
            }
        }, "local-share-server");
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        workers.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }

    private void handle(Socket socket) {
        try (Socket closeable = socket;
             BufferedInputStream input = new BufferedInputStream(closeable.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(closeable.getOutputStream())) {
            Request request = readRequest(input);
            if (request == null) return;

            if ("GET".equals(request.method)) {
                if ("/".equals(request.path) || "/index.html".equals(request.path)) {
                    serveAsset(output, "public/index.html", "text/html; charset=utf-8");
                } else if ("/styles.css".equals(request.path)) {
                    serveAsset(output, "public/styles.css", "text/css; charset=utf-8");
                } else if ("/app.js".equals(request.path)) {
                    serveAsset(output, "public/app.js", "application/javascript; charset=utf-8");
                } else if ("/logo.png".equals(request.path)) {
                    serveAsset(output, "public/logo.png", "image/png");
                } else if ("/qrcode.js".equals(request.path)) {
                    serveAsset(output, "public/qrcode.js", "application/javascript; charset=utf-8");
                } else if ("/api/files".equals(request.path)) {
                    sendText(output, 200, "application/json; charset=utf-8", filesJson());
                } else if ("/api/info".equals(request.path)) {
                    sendText(output, 200, "application/json; charset=utf-8", "{\"addresses\":[]}");
                } else if (request.path.startsWith("/download/")) {
                    download(output, request.path.substring("/download/".length()));
                } else {
                    sendText(output, 404, "application/json; charset=utf-8", "{\"error\":\"Not found\"}");
                }
            } else if ("POST".equals(request.method) && "/api/upload".equals(request.path)) {
                receiveUpload(input, output, request);
            } else if ("DELETE".equals(request.method) && request.path.startsWith("/api/files/")) {
                delete(output, request.path.substring("/api/files/".length()));
            } else {
                sendText(output, 404, "application/json; charset=utf-8", "{\"error\":\"Not found\"}");
            }
        } catch (Exception ignored) {
        }
    }

    private void serveAsset(OutputStream output, String path, String contentType) throws IOException {
        try (InputStream is = context.getAssets().open(path)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int read;
            while((read = is.read(buf)) != -1) baos.write(buf, 0, read);
            byte[] bytes = baos.toByteArray();
            writeHeaders(output, 200, contentType, bytes.length, null);
            output.write(bytes);
            output.flush();
        } catch (Exception e) {
            sendText(output, 404, "application/json; charset=utf-8", "{\"error\":\"Not found\"}");
        }
    }

    private Request readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;

        while ((current = input.read()) != -1) {
            headerBytes.write(current);
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') break;
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
            if (headerBytes.size() > 64 * 1024) return null;
        }

        String header = headerBytes.toString(StandardCharsets.ISO_8859_1.name());
        String[] lines = header.split("\r\n");
        if (lines.length == 0) return null;

        String[] start = lines[0].split(" ");
        if (start.length < 2) return null;

        Request request = new Request();
        request.method = start[0];
        request.path = start[1].split("\\?")[0];
        request.headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(":");
            if (colon > 0) {
                request.headers.put(
                        lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[i].substring(colon + 1).trim()
                );
            }
        }
        return request;
    }

    private void receiveUpload(InputStream input, OutputStream output, Request request) throws IOException {
        String encodedName = request.headers.get("x-file-name");
        long contentLength = parseLong(request.headers.get("content-length"));
        if (encodedName == null || encodedName.trim().isEmpty()) {
            sendText(output, 400, "application/json; charset=utf-8", "{\"error\":\"Missing file name\"}");
            return;
        }

        String fileName = safeFileName(urlDecode(encodedName));
        File target = uniqueFile(sharedDir, fileName);
        // No size limit: the body is streamed straight to disk in 64 KB chunks.
        long remaining = contentLength;
        byte[] buffer = new byte[64 * 1024];
        boolean complete = true;

        try (FileOutputStream fileOutput = new FileOutputStream(target)) {
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read == -1) {
                    complete = false;
                    break;
                }
                fileOutput.write(buffer, 0, read);
                remaining -= read;
            }
        } catch (IOException error) {
            target.delete();
            throw error;
        }

        if (!complete) {
            target.delete();
            return;
        }

        sendText(output, 201, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private void download(OutputStream output, String rawName) throws IOException {
        File file = new File(sharedDir, safeFileName(urlDecode(rawName)));
        if (!file.exists() || !file.isFile()) {
            sendText(output, 404, "application/json; charset=utf-8", "{\"error\":\"File not found\"}");
            return;
        }

        String disposition = "attachment; filename*=UTF-8''" + urlEncode(file.getName());
        writeHeaders(output, 200, "application/octet-stream", file.length(), disposition);
        try (FileInputStream fileInput = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = fileInput.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        output.flush();
    }

    private void delete(OutputStream output, String rawName) throws IOException {
        File file = new File(sharedDir, safeFileName(urlDecode(rawName)));
        if (file.exists() && file.isFile()) file.delete();
        sendText(output, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private String filesJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"files\":[");
        File[] files = sharedDir.listFiles(file -> file.isFile());
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (i > 0) builder.append(",");
                File file = files[i];
                builder.append("{")
                        .append("\"name\":\"").append(jsonEscape(file.getName())).append("\",")
                        .append("\"size\":").append(file.length()).append(",")
                        .append("\"modified\":\"").append(isoDate(file.lastModified())).append("\",")
                        .append("\"url\":\"/download/").append(urlEncode(file.getName())).append("\"")
                        .append("}");
            }
        }
        builder.append("]}");
        return builder.toString();
    }

    private void sendText(OutputStream output, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, status, contentType, bytes.length, null);
        output.write(bytes);
        output.flush();
    }

    private void writeHeaders(OutputStream output, int status, String contentType, long length, String disposition) throws IOException {
        String reason = status == 200 ? "OK" : status == 201 ? "Created" : status == 400 ? "Bad Request" : status == 413 ? "Payload Too Large" : "Not Found";
        StringBuilder builder = new StringBuilder();
        builder.append("HTTP/1.1 ").append(status).append(" ").append(reason).append("\r\n")
                .append("Connection: close\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(length).append("\r\n")
                .append("Access-Control-Allow-Origin: *\r\n");
        if (disposition != null) {
            builder.append("Content-Disposition: ").append(disposition).append("\r\n");
        }
        builder.append("\r\n");
        output.write(builder.toString().getBytes(StandardCharsets.ISO_8859_1));
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

    public static String safeFileName(String name) {
        String value = name == null ? "file" : name;
        value = value.replace("\\", "/");
        int slash = value.lastIndexOf("/");
        if (slash >= 0) value = value.substring(slash + 1);
        value = value.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_").trim();
        return value.isEmpty() ? "file" : value;
    }

    private long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception error) {
            return value;
        }
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception error) {
            return value;
        }
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String isoDate(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private static class Request {
        String method;
        String path;
        Map<String, String> headers;
    }
}
