package com.vijay.localfileshare;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.util.Random;

/**
 * Measures how fast the app's own transfer code can move data when no radio is involved.
 * Wi-Fi tops out far below these numbers, so this guards against the code becoming the bottleneck.
 */
public class SpeedTest {
    private static final int SIZE_MB = 200;
    /** Well above what any phone Wi-Fi link delivers, with room for a slow test machine. */
    private static final double MINIMUM_MB_PER_SECOND = 100;

    @Test
    public void speedIsReportedInBytesPerSecond() {
        assertEquals(50L * 1024 * 1024, Session.rate(50L * 1024 * 1024, 1_000_000_000L));
        assertEquals(100L * 1024 * 1024, Session.rate(50L * 1024 * 1024, 500_000_000L));
        assertEquals(0, Session.rate(1000, 0));
    }

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void transferCodeIsMuchFasterThanWifi() throws Exception {
        File serverDir = temp.newFolder("server");
        File saveDir = temp.newFolder("saved");
        File source = new File(temp.newFolder("source"), "big.bin");
        byte[] block = new byte[1024 * 1024];
        new Random(1).nextBytes(block);
        try (FileOutputStream out = new FileOutputStream(source)) {
            for (int i = 0; i < SIZE_MB; i++) out.write(block);
        }

        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        LocalShareServer server = new LocalShareServer(null, port, serverDir);
        server.start();
        String base = "http://127.0.0.1:" + port;
        try {
            long start = System.nanoTime();
            PeerClient.upload(base, "big.bin", source, (done, total) -> {
            });
            double upload = SIZE_MB / ((System.nanoTime() - start) / 1e9);

            PeerClient.RemoteFile remote = PeerClient.list(base).get(0);
            start = System.nanoTime();
            File saved = PeerClient.download(base, remote, saveDir, (done, total) -> {
            });
            double download = SIZE_MB / ((System.nanoTime() - start) / 1e9);

            System.out.printf("SPEED upload=%.0f MB/s download=%.0f MB/s%n", upload, download);
            assertEquals(source.length(), saved.length());
            assertTrue("upload only reached " + upload + " MB/s", upload > MINIMUM_MB_PER_SECOND);
            assertTrue("download only reached " + download + " MB/s", download > MINIMUM_MB_PER_SECOND);
        } finally {
            server.stop();
        }
    }
}
