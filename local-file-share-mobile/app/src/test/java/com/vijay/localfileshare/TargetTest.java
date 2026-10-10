package com.vijay.localfileshare;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

/** How a sender is announced (Bluetooth beacon) and shared (QR link), and how a receiver reads it back. */
public class TargetTest {
    private static final String HOTSPOT_NAME = "AndroidShare_4821";
    private static final String HOTSPOT_PASSWORD = "k3j9x2mq8v7w1zt";

    private static Target viaBeacon(Target target) {
        byte[][] parts = target.toBeacon();
        assertNotNull("should fit in a beacon", parts);
        assertTrue("first packet too big: " + parts[0].length, parts[0].length <= Target.BEACON_PART1_MAX);
        assertTrue("second packet too big: " + parts[1].length, parts[1].length <= Target.BEACON_PART2_MAX);
        return Target.fromBeacon(parts[0], parts[1].length == 0 ? null : parts[1]);
    }

    // ---- Beacon ----

    @Test
    public void hotspotSurvivesTheBeacon() {
        Target read = viaBeacon(new Target(Target.HOTSPOT, HOTSPOT_NAME, HOTSPOT_PASSWORD, "Vijay Pixel 7"));
        assertNotNull(read);
        assertEquals(Target.HOTSPOT, read.kind);
        assertEquals(HOTSPOT_NAME, read.ssid);
        assertEquals(HOTSPOT_PASSWORD, read.password);
        assertEquals("Vijay Pixel 7", read.name);
        assertEquals("Vijay Pixel 7", read.displayName());
    }

    @Test
    public void wifiDirectSurvivesTheBeacon() {
        Target sent = Target.direct("DIRECT-LS-Galaxy S23");
        Target read = viaBeacon(sent);
        assertNotNull(read);
        assertEquals(Target.DIRECT, read.kind);
        assertEquals("DIRECT-LS-Galaxy S23", read.ssid);
        assertEquals(sent.password, read.password);
        assertEquals("Galaxy S23", read.displayName());
    }

    @Test
    public void hotspotWithUnusualNameSurvivesTheBeacon() {
        // Some phones don't use the AndroidShare_ prefix, so nothing can be compressed away.
        Target read = viaBeacon(new Target(Target.HOTSPOT, "MyPhone Hotspot 5G", "abcd1234efgh", "Me"));
        assertNotNull(read);
        assertEquals("MyPhone Hotspot 5G", read.ssid);
        assertEquals("abcd1234efgh", read.password);
    }

    @Test
    public void smallAnnouncementFitsInOnePacket() {
        Target sent = new Target(Target.HOTSPOT, "AndroidShare_1", "12345678", "");
        byte[][] parts = sent.toBeacon();
        assertNotNull(parts);
        assertEquals(0, parts[1].length);
        Target read = Target.fromBeacon(parts[0], null);
        assertNotNull(read);
        assertEquals("12345678", read.password);
    }

    @Test
    public void longPhoneNameIsShortenedNotTheCredentials() {
        Target read = viaBeacon(new Target(Target.HOTSPOT, HOTSPOT_NAME, HOTSPOT_PASSWORD,
                "An extremely long phone name that cannot possibly fit"));
        assertNotNull(read);
        assertEquals(HOTSPOT_NAME, read.ssid);
        assertEquals(HOTSPOT_PASSWORD, read.password);
        assertTrue("An extremely long phone name that cannot possibly fit".startsWith(read.name));
        assertFalse(read.name.isEmpty());
    }

    @Test
    public void credentialsTooLongForABeaconAreRefused() {
        // Better to fall back to the QR code than to announce a cut-off password.
        Target huge = new Target(Target.HOTSPOT, "A-thirty-two-byte-long-wifi-name", "a-password-of-25-characters", "x");
        assertNull(huge.toBeacon());
    }

    @Test
    public void firstPacketAloneIsNotEnoughWhenSplit() {
        byte[][] parts = new Target(Target.HOTSPOT, HOTSPOT_NAME, HOTSPOT_PASSWORD, "Vijay Pixel 7").toBeacon();
        assertTrue(parts[1].length > 0);
        assertNull(Target.fromBeacon(parts[0], null));
        assertNull(Target.fromBeacon(parts[0], new byte[0]));
    }

    @Test
    public void otherAppsBeaconsAreIgnored() {
        assertNull(Target.fromBeacon(null, null));
        assertNull(Target.fromBeacon(new byte[0], null));
        assertNull(Target.fromBeacon(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, null));
        byte[][] parts = new Target(Target.HOTSPOT, HOTSPOT_NAME, HOTSPOT_PASSWORD, "A").toBeacon();
        byte[] wrongTag = parts[0].clone();
        wrongTag[0] = 0x11;
        assertNull(Target.fromBeacon(wrongTag, parts[1]));
        byte[] futureVersion = parts[0].clone();
        futureVersion[1] = (byte) (futureVersion[1] | 0xF0);
        assertNull(Target.fromBeacon(futureVersion, parts[1]));
    }

    @Test
    public void damagedBeaconsNeverCrash() {
        byte[][] parts = new Target(Target.HOTSPOT, HOTSPOT_NAME, HOTSPOT_PASSWORD, "Vijay Pixel 7").toBeacon();
        for (int a = 0; a <= parts[0].length; a++) {
            for (int b = 0; b <= parts[1].length; b++) {
                Target read = Target.fromBeacon(Arrays.copyOf(parts[0], a), Arrays.copyOf(parts[1], b));
                // A cut-off packet must either be rejected or still describe a joinable hotspot.
                if (read != null) assertTrue(read.password.length() >= 8);
            }
        }
        Random random = new Random(42);
        for (int i = 0; i < 200000; i++) {
            byte[] first = new byte[random.nextInt(Target.BEACON_PART1_MAX + 1)];
            byte[] second = new byte[random.nextInt(Target.BEACON_PART2_MAX + 1)];
            random.nextBytes(first);
            random.nextBytes(second);
            if (first.length > 0 && random.nextBoolean()) first[0] = (byte) 0xA7;
            Target.fromBeacon(first, second);
        }
    }

    @Test
    public void everyRealisticHotspotFits() {
        // Android names local hotspots AndroidShare_NNNN with passwords of up to 15 characters.
        Random random = new Random(7);
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 5000; i++) {
            String ssid = "AndroidShare_" + (1000 + random.nextInt(9000));
            StringBuilder password = new StringBuilder();
            int length = 8 + random.nextInt(8);
            for (int c = 0; c < length; c++) password.append(alphabet.charAt(random.nextInt(alphabet.length())));
            Target read = viaBeacon(new Target(Target.HOTSPOT, ssid, password.toString(), "Redmi Note 12 Pro"));
            assertNotNull(read);
            assertEquals(ssid, read.ssid);
            assertEquals(password.toString(), read.password);
        }
    }

    @Test
    public void openHotspotSurvivesBeaconAndQrLink() {
        // Android 16's 5 GHz hotspot has no password.
        Target sent = new Target(Target.HOTSPOT, "AndroidShare_7310", "", "Pixel 9");
        assertTrue(sent.open());

        Target heard = viaBeacon(sent);
        assertNotNull(heard);
        assertEquals("AndroidShare_7310", heard.ssid);
        assertTrue(heard.open());
        assertEquals("Pixel 9", heard.name);

        assertFalse(sent.toLink().contains("pass="));
        Target scanned = Target.fromLink(sent.toLink());
        assertNotNull(scanned);
        assertEquals(Target.HOTSPOT, scanned.kind);
        assertTrue(scanned.open());
        assertEquals("Pixel 9", scanned.displayName());

        Target nullPassword = new Target(Target.HOTSPOT, "AndroidShare_7310", null, "Pixel 9");
        assertTrue(nullPassword.open());
        assertNotNull(nullPassword.toBeacon());
    }

    @Test
    public void protectedHotspotIsNotMistakenForOpen() {
        Target read = viaBeacon(new Target(Target.HOTSPOT, HOTSPOT_NAME, HOTSPOT_PASSWORD, "A"));
        assertNotNull(read);
        assertFalse(read.open());
        assertFalse(Target.direct("DIRECT-LS-Pixel").open());
    }

    // ---- QR link ----

    @Test
    public void hotspotSurvivesTheQrLink() {
        Target read = Target.fromLink(new Target(Target.HOTSPOT, "My Wi-Fi & more=yes", "p@ss word+100%", "Vijay's Phone ✓").toLink());
        assertNotNull(read);
        assertEquals(Target.HOTSPOT, read.kind);
        assertEquals("My Wi-Fi & more=yes", read.ssid);
        assertEquals("p@ss word+100%", read.password);
        assertEquals("Vijay's Phone ✓", read.name);
    }

    @Test
    public void wifiDirectSurvivesTheQrLink() {
        Target sent = Target.direct("DIRECT-LS-Pixel 8");
        Target read = Target.fromLink(sent.toLink());
        assertNotNull(read);
        assertEquals(Target.DIRECT, read.kind);
        assertEquals("DIRECT-LS-Pixel 8", read.ssid);
        assertEquals(sent.password, read.password);
        assertFalse("the derived password must not be printed in the code", sent.toLink().contains(sent.password));
    }

    @Test
    public void qrCodesFromTheOlderAppStillWork() {
        Target fromLink = Target.fromLink("localshare://join?ssid=DIRECT-LS-Pixel+8");
        assertNotNull(fromLink);
        assertEquals(Target.DIRECT, fromLink.kind);
        assertEquals("DIRECT-LS-Pixel 8", fromLink.ssid);
        Target bare = Target.fromLink("  DIRECT-LS-Pixel 8  ");
        assertNotNull(bare);
        assertEquals("DIRECT-LS-Pixel 8", bare.ssid);
    }

    @Test
    public void unrelatedQrCodesAreRejected() {
        assertNull(Target.fromLink(null));
        assertNull(Target.fromLink(""));
        assertNull(Target.fromLink("https://example.com/?ssid=DIRECT-LS-x"));
        assertNull(Target.fromLink("WIFI:S:Home;T:WPA;P:secret123;;"));
        assertNull(Target.fromLink("localshare://join?"));
        assertNull(Target.fromLink("localshare://join?k=h&ssid=AndroidShare_1&pass=short"));
        assertNull(Target.fromLink("localshare://join?k=d&ssid=NotOurs"));
        assertNull(Target.fromLink("localshare://join?k=h&ssid=%ZZ&pass=12345678"));
        assertNull(Target.fromLink("DIRECT-LS-"));
    }

    // ---- Names and passwords ----

    @Test
    public void wifiDirectPasswordIsStableAndValid() {
        String password = Target.directPassword("DIRECT-LS-Pixel 8");
        assertEquals(password, Target.directPassword("DIRECT-LS-Pixel 8"));
        assertNotEquals(password, Target.directPassword("DIRECT-LS-Pixel 9"));
        assertTrue("Wi-Fi passwords need 8 to 63 characters", password.length() >= 8 && password.length() <= 63);
        assertTrue(password.matches("[0-9a-f]+"));
    }

    @Test
    public void phoneNamesAreMadeSafeForWifiNames() {
        assertEquals("Vijays Phone", Target.cleanName("Vijay's Phone"));
        assertEquals("Phone", Target.cleanName(null));
        assertEquals("Phone", Target.cleanName("  📱  "));
        String cleaned = Target.cleanName("A really really long device name here");
        assertTrue(cleaned.length() <= 18);
        assertTrue((Target.DIRECT_PREFIX + cleaned).getBytes().length <= 32);
    }

    @Test
    public void smallHelpers() {
        assertEquals("AndroidShare_1", Hotspot.unquote("\"AndroidShare_1\""));
        assertEquals("plain", Hotspot.unquote("plain"));
        assertNull(Hotspot.unquote(null));
        // What a started hotspot reports is only used when a phone could actually join with it.
        assertArrayEquals(new String[]{"AndroidShare_1", "abcdefgh"}, Hotspot.usable("AndroidShare_1", "abcdefgh", false));
        assertArrayEquals(new String[]{"AndroidShare_1", ""}, Hotspot.usable("AndroidShare_1", null, true));
        assertNull("a protected hotspot without a password cannot be joined", Hotspot.usable("AndroidShare_1", null, false));
        assertNull(Hotspot.usable("AndroidShare_1", "short", false));
        assertNull(Hotspot.usable("", "abcdefgh", false));
        assertNull(Hotspot.usable(null, "abcdefgh", false));
        assertEquals("192.168.43.1", WifiJoin.gatewayGuess("192.168.43.57"));
        assertNull(WifiJoin.gatewayGuess("not-an-address"));
    }
}
