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
    private static final long MAX_UPLOAD_BYTES = 1024L * 1024L * 1024L;
    private final int port;
    private final File sharedDir;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public LocalShareServer(int port, File sharedDir) {
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

            if ("GET".equals(request.method) && "/".equals(request.path)) {
                sendText(output, 200, "text/html; charset=utf-8", html());
            } else if ("GET".equals(request.method) && "/api/files".equals(request.path)) {
                sendText(output, 200, "application/json; charset=utf-8", filesJson());
            } else if ("POST".equals(request.method) && "/api/upload".equals(request.path)) {
                receiveUpload(input, output, request);
            } else if ("GET".equals(request.method) && request.path.startsWith("/download/")) {
                download(output, request.path.substring("/download/".length()));
            } else if ("DELETE".equals(request.method) && request.path.startsWith("/api/files/")) {
                delete(output, request.path.substring("/api/files/".length()));
            } else {
                sendText(output, 404, "application/json; charset=utf-8", "{\"error\":\"Not found\"}");
            }
        } catch (Exception ignored) {
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
        if (contentLength > MAX_UPLOAD_BYTES) {
            sendText(output, 413, "application/json; charset=utf-8", "{\"error\":\"File too large\"}");
            return;
        }

        String fileName = safeFileName(urlDecode(encodedName));
        File target = uniqueFile(sharedDir, fileName);
        long remaining = contentLength;
        byte[] buffer = new byte[64 * 1024];

        try (FileOutputStream fileOutput = new FileOutputStream(target)) {
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read == -1) break;
                fileOutput.write(buffer, 0, read);
                remaining -= read;
            }
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

    private String html() {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>Local File Share</title><style>" +
                "body{margin:0;background:#f6f8fb;color:#17202e;font-family:system-ui,-apple-system,Segoe UI,sans-serif}" +
                "main{max-width:900px;margin:0 auto;padding:22px}h1{font-size:42px;line-height:1;margin:0 0 14px}" +
                ".panel{background:#fff;border:1px solid #d9e0ea;border-radius:8px;padding:16px;margin:14px 0}" +
                ".drop{border:2px dashed #afbdcb;border-radius:8px;padding:34px;text-align:center;background:#fbfdff}" +
                "button,.btn{border:0;border-radius:7px;background:#0d766e;color:#fff;font-weight:800;padding:11px 14px;text-decoration:none;display:inline-block}" +
                ".ghost{background:#fff;color:#17202e;border:1px solid #d9e0ea}.row{display:flex;gap:10px;align-items:center;justify-content:space-between;border:1px solid #d9e0ea;border-radius:8px;padding:12px;margin-top:10px}.meta{color:#687386;font-size:14px}.actions{display:flex;gap:8px;flex-wrap:wrap}@media(max-width:620px){.row{display:block}.actions{margin-top:10px}}" +
                "</style></head><body><main><p style=\"color:#075f59;font-weight:800\">LOCAL NETWORK</p><h1>File Share</h1>" +
                "<section class=\"panel\"><div class=\"drop\"><input id=\"file\" type=\"file\" multiple><p>Choose files to upload to this phone</p><button onclick=\"upload()\">Upload</button><p id=\"progress\" class=\"meta\"></p></div></section>" +
                "<section class=\"panel\"><h2>Available files</h2><button class=\"ghost\" onclick=\"loadFiles()\">Refresh</button><div id=\"files\"></div></section>" +
                "<script>" +
                "const f=document.getElementById('files'),p=document.getElementById('progress');" +
                "function bytes(n){if(!n)return'0 B';const u=['B','KB','MB','GB'];let i=Math.min(Math.floor(Math.log(n)/Math.log(1024)),3);return(n/1024**i).toFixed(i?1:0)+' '+u[i]}" +
                "async function loadFiles(){let r=await fetch('/api/files');let d=await r.json();f.innerHTML=d.files.length?'':'<p class=meta>No files shared yet.</p>';d.files.forEach(x=>{let row=document.createElement('div');row.className='row';row.innerHTML='<div><b>'+x.name+'</b><div class=meta>'+bytes(x.size)+'</div></div><div class=actions><a class=btn href=\"'+x.url+'\">Download</a><button class=ghost data-name=\"'+x.name+'\">Delete</button></div>';row.querySelector('button').onclick=async()=>{await fetch('/api/files/'+encodeURIComponent(x.name),{method:'DELETE'});loadFiles()};f.append(row)})}" +
                "async function upload(){let files=[...document.getElementById('file').files];for(const file of files){p.textContent='Uploading '+file.name;await fetch('/api/upload',{method:'POST',headers:{'x-file-name':encodeURIComponent(file.name)},body:file})}p.textContent='Done';loadFiles()}" +
                "loadFiles();" +
                "</script></main></body></html>";
    }

    private static class Request {
        String method;
        String path;
        Map<String, String> headers;
    }
}
