package com.vijay.localfileshare;

/** Turns Wi-Fi link numbers into words, and says what speed a link can realistically carry. */
final class Link {
    private Link() {
    }

    static String band(int frequencyMhz) {
        if (frequencyMhz >= 5925) return "6 GHz";
        if (frequencyMhz >= 4900) return "5 GHz";
        if (frequencyMhz >= 2400 && frequencyMhz < 2500) return "2.4 GHz";
        return frequencyMhz + " MHz";
    }

    /** Matches android.net.wifi.ScanResult.WIFI_STANDARD_*; empty when unknown. */
    static String standard(int wifiStandard) {
        switch (wifiStandard) {
            case 4:
                return "Wi-Fi 4";
            case 5:
                return "Wi-Fi 5";
            case 6:
                return "Wi-Fi 6";
            case 8:
                return "Wi-Fi 7";
            default:
                return "";
        }
    }

    /**
     * For example "5 GHz  ·  866 Mbps  ·  Wi-Fi 5  ·  up to about 65 MB/s".
     * linkSpeedMbps is what the two radios negotiated; files move at roughly 60% of it at best.
     */
    static String describe(int frequencyMhz, int linkSpeedMbps, int wifiStandard) {
        StringBuilder text = new StringBuilder(band(frequencyMhz));
        if (linkSpeedMbps > 0) text.append("  ·  ").append(linkSpeedMbps).append(" Mbps");
        String standard = standard(wifiStandard);
        if (!standard.isEmpty()) text.append("  ·  ").append(standard);
        if (linkSpeedMbps > 0) text.append("  ·  up to about ").append(expectedMegabytesPerSecond(linkSpeedMbps)).append(" MB/s");
        return text.toString();
    }

    /** Megabits to megabytes is a division by 8; protocol overhead and retries take about 40% more. */
    static int expectedMegabytesPerSecond(int linkSpeedMbps) {
        return Math.max(1, Math.round(linkSpeedMbps * 0.6f / 8f));
    }
}
