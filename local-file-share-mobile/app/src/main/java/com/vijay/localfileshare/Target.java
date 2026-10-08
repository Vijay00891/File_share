package com.vijay.localfileshare;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * A sender that a receiver can join: which kind of Wi-Fi link it runs and how to get on it.
 *
 * It travels two ways. As a link inside the sender's QR code, and as a compact Bluetooth
 * beacon so nearby phones running this app (and only those) can list the sender.
 * This class is plain Java so the encoding can be unit tested off the phone.
 */
final class Target {
    /** The sender runs a hotspot; name and password are picked by Android. */
    static final int HOTSPOT = 0;
    /** The sender runs a Wi-Fi Direct group named DIRECT-LS-&lt;name&gt;; the password follows from the name. */
    static final int DIRECT = 1;

    static final String DIRECT_PREFIX = "DIRECT-LS-";
    private static final String LINK = "localshare://join?";

    // Beacon layout. A legacy Bluetooth advertisement carries 31 bytes, minus headers.
    static final int BEACON_PART1_MAX = 23;
    static final int BEACON_PART2_MAX = 27;
    private static final int MAGIC = 0xA7;
    private static final int VERSION = 1;
    private static final int FLAG_HAS_PART2 = 0x08;
    /** Common name prefixes are sent as one number instead of their text. */
    private static final String[] PREFIXES = {"", "AndroidShare_", DIRECT_PREFIX};

    final int kind;
    final String ssid;
    final String password;
    final String name;

    Target(int kind, String ssid, String password, String name) {
        this.kind = kind;
        this.ssid = ssid;
        this.password = kind == DIRECT ? directPassword(ssid) : password;
        this.name = name == null ? "" : name;
    }

    static Target direct(String ssid) {
        return new Target(DIRECT, ssid, null, "");
    }

    /** What to show in the list of nearby senders. */
    String displayName() {
        if (!name.isEmpty()) return name;
        for (int i = 1; i < PREFIXES.length; i++) {
            if (ssid.startsWith(PREFIXES[i]) && ssid.length() > PREFIXES[i].length()) {
                return i == 2 ? ssid.substring(PREFIXES[i].length()) : ssid;
            }
        }
        return ssid;
    }

    private boolean valid() {
        if (ssid == null || ssid.isEmpty() || utf8(ssid).length > 32) return false;
        if (kind == DIRECT) return ssid.startsWith(DIRECT_PREFIX) && ssid.length() > DIRECT_PREFIX.length();
        return kind == HOTSPOT && password != null && password.length() >= 8 && password.length() <= 63;
    }

    // ---- QR link ----

    String toLink() {
        StringBuilder link = new StringBuilder(LINK);
        link.append("k=").append(kind == DIRECT ? "d" : "h");
        link.append("&ssid=").append(encode(ssid));
        if (kind == HOTSPOT) link.append("&pass=").append(encode(password));
        if (!name.isEmpty()) link.append("&name=").append(encode(name));
        return link.toString();
    }

    /** Reads a scanned QR code; null when it is not one of ours. */
    static Target fromLink(String scanned) {
        if (scanned == null) return null;
        String value = scanned.trim();
        if (value.startsWith(DIRECT_PREFIX)) return checked(direct(value));
        if (!value.startsWith(LINK)) return null;

        String kindText = null;
        String ssid = null;
        String pass = null;
        String name = "";
        for (String pair : value.substring(LINK.length()).split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            String key = pair.substring(0, equals);
            String decoded = decode(pair.substring(equals + 1));
            if (decoded == null) return null;
            if (key.equals("k")) kindText = decoded;
            else if (key.equals("ssid")) ssid = decoded;
            else if (key.equals("pass")) pass = decoded;
            else if (key.equals("name")) name = decoded;
        }
        if (ssid == null) return null;
        // Links from before hotspot support carried only a Wi-Fi Direct name.
        boolean isDirect = kindText == null ? ssid.startsWith(DIRECT_PREFIX) : kindText.equals("d");
        return checked(new Target(isDirect ? DIRECT : HOTSPOT, ssid, pass, name));
    }

    // ---- Bluetooth beacon ----

    /**
     * Packs this sender into two small packets (advertisement and scan response).
     * Returns null when the Wi-Fi name and password are too long to fit; the QR code still works then.
     */
    byte[][] toBeacon() {
        if (!valid()) return null;
        int prefix = 0;
        for (int i = 1; i < PREFIXES.length; i++) {
            if (ssid.startsWith(PREFIXES[i])) prefix = i;
        }
        byte[] ssidBytes = utf8(ssid.substring(PREFIXES[prefix].length()));
        byte[] passBytes = kind == HOTSPOT ? utf8(password) : new byte[0];
        byte[] nameBytes = kind == HOTSPOT ? utf8(asciiOnly(name)) : new byte[0];

        int capacity = (BEACON_PART1_MAX - 1) + BEACON_PART2_MAX;
        int fixed = 3 + ssidBytes.length + passBytes.length;
        if (fixed > capacity || ssidBytes.length > 255 || passBytes.length > 255) return null;
        if (fixed + nameBytes.length > capacity) nameBytes = Arrays.copyOf(nameBytes, capacity - fixed);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int total = fixed + nameBytes.length;
        boolean split = total > BEACON_PART1_MAX - 1;
        body.write((VERSION << 4) | (split ? FLAG_HAS_PART2 : 0) | (prefix << 1) | kind);
        body.write(ssidBytes.length);
        body.write(ssidBytes, 0, ssidBytes.length);
        body.write(passBytes.length);
        body.write(passBytes, 0, passBytes.length);
        body.write(nameBytes, 0, nameBytes.length);
        byte[] bytes = body.toByteArray();

        int firstLength = Math.min(bytes.length, BEACON_PART1_MAX - 1);
        byte[] part1 = new byte[firstLength + 1];
        part1[0] = (byte) MAGIC;
        System.arraycopy(bytes, 0, part1, 1, firstLength);
        byte[] part2 = Arrays.copyOfRange(bytes, firstLength, bytes.length);
        return new byte[][]{part1, part2};
    }

    /**
     * Rebuilds a sender from received packets. Returns null for anything that is not a complete,
     * well-formed beacon from this app, including when the second packet has not arrived yet.
     */
    static Target fromBeacon(byte[] part1, byte[] part2) {
        if (part1 == null || part1.length < 4 || (part1[0] & 0xFF) != MAGIC) return null;
        int head = part1[1] & 0xFF;
        if (head >> 4 != VERSION) return null;
        boolean split = (head & FLAG_HAS_PART2) != 0;
        if (split && (part2 == null || part2.length == 0)) return null;

        int extra = split ? part2.length : 0;
        byte[] bytes = new byte[part1.length - 1 + extra];
        System.arraycopy(part1, 1, bytes, 0, part1.length - 1);
        if (split) System.arraycopy(part2, 0, bytes, part1.length - 1, extra);

        int kind = head & 1;
        int prefix = (head >> 1) & 3;
        if (prefix >= PREFIXES.length) return null;
        int at = 1;
        int ssidLength = bytes[at++] & 0xFF;
        if (at + ssidLength >= bytes.length) return null;
        String ssid = PREFIXES[prefix] + new String(bytes, at, ssidLength, StandardCharsets.UTF_8);
        at += ssidLength;
        int passLength = bytes[at++] & 0xFF;
        if (at + passLength > bytes.length) return null;
        String pass = new String(bytes, at, passLength, StandardCharsets.UTF_8);
        at += passLength;
        String name = new String(bytes, at, bytes.length - at, StandardCharsets.UTF_8);
        return checked(new Target(kind, ssid, pass, name));
    }

    // ---- Helpers ----

    private static Target checked(Target target) {
        return target.valid() ? target : null;
    }

    /** The Wi-Fi Direct group password, derived from its name so a receiver can join with one tap. */
    static String directPassword(String ssid) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(utf8("localshare:" + ssid));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 6; i++) builder.append(String.format("%02x", hash[i]));
            return builder.toString();
        } catch (Exception error) {
            return "localshare";
        }
    }

    /** A phone name that is safe inside a Wi-Fi name: plain characters, at most 18 long. */
    static String cleanName(String name) {
        String cleaned = asciiOnly(name).trim();
        if (cleaned.length() > 18) cleaned = cleaned.substring(0, 18).trim();
        return cleaned.isEmpty() ? "Phone" : cleaned;
    }

    private static String asciiOnly(String value) {
        return (value == null ? "" : value).replaceAll("[^A-Za-z0-9 _-]", "");
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception error) {
            return value;
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception error) {
            return null;
        }
    }
}
