package com.vijay.localfileshare;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Large files are fetched as several byte ranges at once; every byte must still land in the right place. */
public class SplitDownloadTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private LocalShareServer server;
    private File sourceDir;
    private File saveDir;
    private int port;
    private String base;

    @Before
    public void startServer() throws Exception {
        sourceDir = temp.newFolder("source");
        saveDir = temp.newFolder("saved");
        port = freePort();
        server = new LocalShareServer(null, port, temp.newFolder("server"));
        server.start();
        base = "http://127.0.0.1:" + port;
    }

    @After
    public void stopServer() {
        server.stop();
    }

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private File randomFile(String name, long size) throws Exception {
        byte[] bytes = new byte[(int) size];
        new Random(size).nextBytes(bytes);
        File file = new File(sourceDir, name);
        Files.write(file.toPath(), bytes);
        return file;
    }

    private PeerClient.RemoteFile remote(String name) throws Exception {
        for (PeerClient.RemoteFile file : PeerClient.list(base)) {
            if (file.name.equals(name)) return file;
        }
        fail(name + " is not listed");
        return null;
    }

    private byte[] rawGet(String path, String rangeHeader, StringBuilder statusLine) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String request = "GET " + path + " HTTP/1.1\r\nHost: x\r\n"
                    + (rangeHeader == null ? "" : "Range: " + rangeHeader + "\r\n") + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) all.write(buffer, 0, read);
            byte[] bytes = all.toByteArray();
            int split = new String(bytes, StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n");
            statusLine.append(new String(bytes, 0, split, StandardCharsets.ISO_8859_1));
            return Arrays.copyOfRange(bytes, split + 4, bytes.length);
        }
    }

    // ---- The server's side ----

    @Test
    public void rangeHeadersAreUnderstood() {
        assertNull(LocalShareServer.parseRange(null, 1000));
        assertArrayEquals(new long[]{0, 499}, LocalShareServer.parseRange("bytes=0-499", 1000));
        assertArrayEquals(new long[]{500, 999}, LocalShareServer.parseRange("bytes=500-", 1000));
        assertArrayEquals(new long[]{900, 999}, LocalShareServer.parseRange("bytes=-100", 1000));
        assertArrayEquals(new long[]{0, 999}, LocalShareServer.parseRange("bytes=-5000", 1000));
        assertArrayEquals("an end past the file is trimmed", new long[]{990, 999}, LocalShareServer.parseRange("bytes=990-2000", 1000));
        assertArrayEquals(new long[]{0, 0}, LocalShareServer.parseRange(" Bytes = 0-0 ".replace(" = ", "="), 1000));
        assertSame(LocalShareServer.RANGE_INVALID, LocalShareServer.parseRange("bytes=1000-", 1000));
        assertSame(LocalShareServer.RANGE_INVALID, LocalShareServer.parseRange("bytes=600-500", 1000));
        assertSame(LocalShareServer.RANGE_INVALID, LocalShareServer.parseRange("bytes=0-", 0));
        // Forms this server doesn't split are answered with the whole file rather than an error.
        assertNull(LocalShareServer.parseRange("bytes=0-10,20-30", 1000));
        assertNull(LocalShareServer.parseRange("items=0-10", 1000));
        assertNull(LocalShareServer.parseRange("bytes=abc-def", 1000));
    }

    @Test
    public void theServerSendsExactlyTheBytesAskedFor() throws Exception {
        File original = randomFile("data.bin", 100_000);
        byte[] all = Files.readAllBytes(original.toPath());
        server.link("data.bin", original);

        StringBuilder head = new StringBuilder();
        byte[] middle = rawGet("/download/data.bin", "bytes=1000-1999", head);
        assertTrue(head.toString(), head.toString().startsWith("HTTP/1.1 206"));
        assertTrue(head.toString().contains("Content-Range: bytes 1000-1999/100000"));
        assertArrayEquals(Arrays.copyOfRange(all, 1000, 2000), middle);

        head.setLength(0);
        byte[] tail = rawGet("/download/data.bin", "bytes=99990-", head);
        assertArrayEquals(Arrays.copyOfRange(all, 99990, 100000), tail);

        head.setLength(0);
        byte[] whole = rawGet("/download/data.bin", null, head);
        assertTrue(head.toString().startsWith("HTTP/1.1 200"));
        assertTrue("browsers and newer apps need to know ranges work", head.toString().contains("Accept-Ranges: bytes"));
        assertArrayEquals(all, whole);

        head.setLength(0);
        byte[] none = rawGet("/download/data.bin", "bytes=100000-", head);
        assertTrue(head.toString().startsWith("HTTP/1.1 416"));
        assertEquals(0, none.length);
    }

    // ---- The receiver's side ----

    @Test
    public void aLargeFileArrivesIntactOverSeveralConnections() throws Exception {
        File original = randomFile("video.mkv", 40L * 1024 * 1024 + 13);
        server.link("video.mkv", original);

        AtomicLong lastDone = new AtomicLong();
        AtomicLong highest = new AtomicLong();
        File saved = PeerClient.download(base, remote("video.mkv"), saveDir, (done, total) -> {
            assertEquals(original.length(), total);
            lastDone.set(done);
            highest.accumulateAndGet(done, Math::max);
        });
        assertEquals(original.length(), saved.length());
        assertEquals("progress must end at 100%", original.length(), highest.get());
        assertArrayEquals(Files.readAllBytes(original.toPath()), Files.readAllBytes(saved.toPath()));
    }

    @Test
    public void sizesThatDoNotDivideEvenlyLoseNoBytes() throws Exception {
        for (long extra : new long[]{0, 1, 2, 3, 5, 4097}) {
            long size = PeerClient.SPLIT_FROM_BYTES + extra;
            File original = randomFile("edge-" + extra + ".bin", size);
            server.link(original.getName(), original);
            File saved = PeerClient.download(base, remote(original.getName()), saveDir, (done, total) -> {
            });
            assertArrayEquals("size " + size, Files.readAllBytes(original.toPath()), Files.readAllBytes(saved.toPath()));
        }
    }

    @Test
    public void filesJustUnderTheLimitUseOneConnection() throws Exception {
        File original = randomFile("small.bin", PeerClient.SPLIT_FROM_BYTES - 1);
        server.link("small.bin", original);
        File saved = PeerClient.download(base, remote("small.bin"), saveDir, (done, total) -> {
        });
        assertArrayEquals(Files.readAllBytes(original.toPath()), Files.readAllBytes(saved.toPath()));
    }

    /** A stand-in sender. Mode decides how it treats Range requests. */
    private Thread fakeSender(ServerSocket listener, byte[] content, boolean ignoreRanges, AtomicInteger requests) {
        Thread thread = new Thread(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket socket = listener.accept();
                    new Thread(() -> {
                        try (Socket s = socket) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                            String line;
                            String range = null;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                                if (line.toLowerCase().startsWith("range:")) range = line.substring(6).trim();
                            }
                            int number = requests.incrementAndGet();
                            OutputStream out = s.getOutputStream();
                            if (ignoreRanges || range == null) {
                                out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: " + content.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                                out.write(content);
                            } else {
                                long[] wanted = LocalShareServer.parseRange(range, content.length);
                                int length = (int) (wanted[1] - wanted[0] + 1);
                                out.write(("HTTP/1.1 206 Partial Content\r\nConnection: close\r\nContent-Length: " + length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                                // The second connection dies halfway, like a phone walking out of range.
                                int send = number == 2 ? length / 2 : length;
                                out.write(content, (int) wanted[0], send);
                            }
                            out.flush();
                        } catch (Exception ignored) {
                        }
                    }).start();
                } catch (Exception closed) {
                    return;
                }
            }
        });
        thread.start();
        return thread;
    }

    private static PeerClient.RemoteFile fakeRemote(int size) {
        PeerClient.RemoteFile file = new PeerClient.RemoteFile();
        file.name = "fake.bin";
        file.url = "/download/fake.bin";
        file.size = size;
        return file;
    }

    @Test
    public void anOlderSenderWithoutRangesStillWorks() throws Exception {
        byte[] content = new byte[(int) PeerClient.SPLIT_FROM_BYTES + 777];
        new Random(5).nextBytes(content);
        AtomicInteger requests = new AtomicInteger();
        try (ServerSocket listener = new ServerSocket(0)) {
            fakeSender(listener, content, true, requests);
            AtomicLong lastDone = new AtomicLong();
            File saved = PeerClient.download("http://127.0.0.1:" + listener.getLocalPort(), fakeRemote(content.length), saveDir,
                    (done, total) -> lastDone.set(done));
            assertArrayEquals(content, Files.readAllBytes(saved.toPath()));
            assertEquals(content.length, lastDone.get());
        }
    }

    @Test
    public void aConnectionDroppingMidwayFailsCleanly() throws Exception {
        byte[] content = new byte[(int) PeerClient.SPLIT_FROM_BYTES + 100];
        new Random(6).nextBytes(content);
        AtomicInteger requests = new AtomicInteger();
        try (ServerSocket listener = new ServerSocket(0)) {
            fakeSender(listener, content, false, requests);
            try {
                PeerClient.download("http://127.0.0.1:" + listener.getLocalPort(), fakeRemote(content.length), saveDir,
                        (done, total) -> {
                        });
                fail("a file with a missing part must not be reported as saved");
            } catch (java.io.IOException expected) {
                assertEquals("the broken file must be removed", 0, saveDir.listFiles().length);
            }
        }
    }

    // ---- What the screen says about the link ----

    @Test
    public void theLinkIsDescribedInPlainWords() {
        assertEquals("2.4 GHz", Link.band(2437));
        assertEquals("5 GHz", Link.band(5180));
        assertEquals("5 GHz", Link.band(5745));
        assertEquals("6 GHz", Link.band(5955));
        assertEquals("2.4 GHz  ·  144 Mbps  ·  Wi-Fi 4  ·  up to about 11 MB/s", Link.describe(2462, 144, 4));
        assertEquals("5 GHz  ·  866 Mbps  ·  Wi-Fi 5  ·  up to about 65 MB/s", Link.describe(5180, 866, 5));
        assertEquals("when only the band is known", "5 GHz", Link.describe(5745, 0, 0));
        assertEquals(1, Link.expectedMegabytesPerSecond(6));
    }
}
