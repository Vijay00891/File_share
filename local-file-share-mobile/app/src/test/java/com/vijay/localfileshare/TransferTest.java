package com.vijay.localfileshare;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the phone's real file server and the receiver's real client against each other over
 * local sockets: the same code path two phones use once they are on the same link.
 */
public class TransferTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private LocalShareServer server;
    private File serverDir;
    private File sourceDir;
    private File saveDir;
    private String base;

    @Before
    public void startServer() throws Exception {
        serverDir = temp.newFolder("server");
        sourceDir = temp.newFolder("source");
        saveDir = temp.newFolder("saved");
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        server = new LocalShareServer(null, port, serverDir);
        server.start();
        base = "http://127.0.0.1:" + port;
    }

    @After
    public void stopServer() {
        server.stop();
    }

    private File randomFile(String name, int size) throws Exception {
        byte[] bytes = new byte[size];
        new Random(size * 31L + name.hashCode()).nextBytes(bytes);
        File file = new File(sourceDir, name);
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static void assertSameContent(File expected, File actual) throws Exception {
        assertEquals(expected.length(), actual.length());
        assertArrayEquals(Files.readAllBytes(expected.toPath()), Files.readAllBytes(actual.toPath()));
    }

    private PeerClient.RemoteFile remote(String name) throws Exception {
        for (PeerClient.RemoteFile file : PeerClient.list(base)) {
            if (file.name.equals(name)) return file;
        }
        fail(name + " is not listed by the server");
        return null;
    }

    private static final PeerClient.Progress NO_PROGRESS = (done, total) -> {
    };

    @Test
    public void receiverSendsAFileToTheSender() throws Exception {
        File original = randomFile("holiday.jpg", 3 * 1024 * 1024 + 123);
        AtomicLong lastDone = new AtomicLong();
        AtomicLong lastTotal = new AtomicLong();
        PeerClient.upload(base, "holiday.jpg", original, (done, total) -> {
            assertTrue("progress went backwards", done >= lastDone.get());
            lastDone.set(done);
            lastTotal.set(total);
        });

        assertEquals(original.length(), lastDone.get());
        assertEquals(original.length(), lastTotal.get());
        assertSameContent(original, new File(serverDir, "holiday.jpg"));
        List<LocalShareServer.Entry> entries = server.entries();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).received);
        assertEquals(original.length(), remote("holiday.jpg").size);
    }

    @Test
    public void senderSharesAFileInPlaceAndReceiverSavesIt() throws Exception {
        File original = randomFile("movie.mp4", 5 * 1024 * 1024);
        server.link("movie.mp4", original);
        assertEquals("sharing must not copy the file", 0, serverDir.listFiles().length);

        AtomicLong lastDone = new AtomicLong();
        File saved = PeerClient.download(base, remote("movie.mp4"), saveDir, (done, total) -> lastDone.set(done));
        assertEquals("movie.mp4", saved.getName());
        assertEquals(original.length(), lastDone.get());
        assertSameContent(original, saved);
    }

    @Test
    public void appsAreSharedUnderTheirReadableName() throws Exception {
        File apk = randomFile("base.apk", 200_000);
        server.link("WhatsApp.apk", apk);
        File saved = PeerClient.download(base, remote("WhatsApp.apk"), saveDir, NO_PROGRESS);
        assertEquals("WhatsApp.apk", saved.getName());
        assertSameContent(apk, saved);
    }

    @Test
    public void namesWithSpacesAccentsAndSymbolsSurvive() throws Exception {
        String name = "résumé final (2) — हिंदी & more.pdf";
        File original = randomFile("plain.pdf", 50_000);
        PeerClient.upload(base, name, original, NO_PROGRESS);
        assertTrue(new File(serverDir, name).isFile());

        File saved = PeerClient.download(base, remote(name), saveDir, NO_PROGRESS);
        assertEquals(name, saved.getName());
        assertSameContent(original, saved);
    }

    @Test
    public void sameNameTwiceKeepsBothFiles() throws Exception {
        File first = randomFile("one.txt", 1000);
        File second = randomFile("two.txt", 2000);
        PeerClient.upload(base, "notes.txt", first, NO_PROGRESS);
        PeerClient.upload(base, "notes.txt", second, NO_PROGRESS);

        assertSameContent(first, new File(serverDir, "notes.txt"));
        assertSameContent(second, new File(serverDir, "notes (1).txt"));
        assertEquals(2, PeerClient.list(base).size());

        // Saving the same file twice on the receiver must not overwrite the first copy either.
        File savedOnce = PeerClient.download(base, remote("notes.txt"), saveDir, NO_PROGRESS);
        File savedTwice = PeerClient.download(base, remote("notes.txt"), saveDir, NO_PROGRESS);
        assertEquals("notes.txt", savedOnce.getName());
        assertEquals("notes (1).txt", savedTwice.getName());
    }

    @Test
    public void sharingTheSameFileTwiceListsItOnce() throws Exception {
        File original = randomFile("song.mp3", 1000);
        server.link("song.mp3", original);
        server.link("song.mp3", original);
        assertEquals(1, PeerClient.list(base).size());
    }

    @Test
    public void emptyFilesTransfer() throws Exception {
        File empty = randomFile("empty.txt", 0);
        PeerClient.upload(base, "empty.txt", empty, NO_PROGRESS);
        assertEquals(0, new File(serverDir, "empty.txt").length());
        File saved = PeerClient.download(base, remote("empty.txt"), saveDir, NO_PROGRESS);
        assertEquals(0, saved.length());
    }

    @Test
    public void aFileNameCannotEscapeTheSaveFolder() throws Exception {
        File original = randomFile("payload.txt", 500);
        PeerClient.upload(base, "../../escaped.txt", original, NO_PROGRESS);
        PeerClient.upload(base, "..\\..\\escaped2.txt", original, NO_PROGRESS);

        assertTrue(new File(serverDir, "escaped.txt").isFile());
        assertTrue(new File(serverDir, "escaped2.txt").isFile());
        assertFalse(new File(serverDir.getParentFile().getParentFile(), "escaped.txt").exists());
        assertFalse(new File(serverDir.getParentFile(), "escaped.txt").exists());
    }

    @Test
    public void onlySharedFilesCanBeDownloaded() throws Exception {
        File secret = new File(serverDir, "secret.txt");
        Files.write(secret.toPath(), "not shared".getBytes(StandardCharsets.UTF_8));
        PeerClient.RemoteFile guess = new PeerClient.RemoteFile();
        guess.name = "secret.txt";
        guess.url = "/download/secret.txt";
        guess.size = secret.length();
        try {
            PeerClient.download(base, guess, saveDir, NO_PROGRESS);
            fail("a file that was never shared must not be downloadable");
        } catch (java.io.IOException expected) {
            assertEquals("no partial file may be left behind", 0, saveDir.listFiles().length);
        }
    }

    @Test
    public void removingASharedFileKeepsItButRemovingAReceivedFileDeletesIt() throws Exception {
        File mine = randomFile("mine.doc", 1000);
        server.link("mine.doc", mine);
        PeerClient.upload(base, "theirs.doc", randomFile("theirs-source.doc", 1000), NO_PROGRESS);
        assertEquals(2, PeerClient.list(base).size());

        server.remove("mine.doc");
        server.remove("theirs.doc");
        assertTrue("the user's own file must never be deleted", mine.isFile());
        assertFalse(new File(serverDir, "theirs.doc").exists());
        assertEquals(0, PeerClient.list(base).size());
    }

    @Test
    public void anInterruptedUploadLeavesNothingBehind() throws Exception {
        int port = Integer.parseInt(base.substring(base.lastIndexOf(':') + 1));
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST /api/upload HTTP/1.1\r\nHost: x\r\nx-file-name: half.bin\r\nContent-Length: 100000\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.write(new byte[4000]);
            out.flush();
        }
        // The connection is now closed with 96% of the file missing.
        Thread.sleep(400);
        long deadline = System.currentTimeMillis() + 5000;
        while (new File(serverDir, "half.bin").exists() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertFalse("a half-received file must be deleted", new File(serverDir, "half.bin").exists());
        assertEquals(0, PeerClient.list(base).size());
    }

    @Test
    public void severalPhonesCanSendAtOnce() throws Exception {
        int count = 6;
        List<File> originals = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            File original = randomFile("parallel-" + i + ".bin", 700_000 + i * 1000);
            originals.add(original);
            Thread thread = new Thread(() -> {
                try {
                    PeerClient.upload(base, original.getName(), original, NO_PROGRESS);
                } catch (Throwable error) {
                    synchronized (failures) {
                        failures.add(error);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join(30000);
        assertTrue(failures.toString(), failures.isEmpty());
        assertEquals(count, PeerClient.list(base).size());
        for (File original : originals) assertSameContent(original, new File(serverDir, original.getName()));
    }

    @Test
    public void connectedPhonesAreCountedButThisPhoneIsNot() throws Exception {
        PeerClient.list(base);
        // Requests from the phone itself (loopback) must not show up as "1 phone connected".
        assertEquals(0, server.activeClients(60000));
    }

    @Test
    public void theClientReportsAStoppedSender() throws Exception {
        server.stop();
        try {
            PeerClient.list(base);
            fail("listing a stopped sender must fail so the screen can say the connection was lost");
        } catch (java.io.IOException expected) {
            // good
        }
    }
}
