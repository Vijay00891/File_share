package com.vijay.localfileshare;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.EnumMap;
import java.util.Map;

/** QR codes: the sender shows its link name, the receiver scans it to connect without searching. */
final class Qr {
    private static final String JOIN_PREFIX = "localshare://join?ssid=";

    private Qr() {
    }

    static String joinLink(String networkName) {
        try {
            return JOIN_PREFIX + URLEncoder.encode(networkName, "UTF-8");
        } catch (Exception error) {
            return JOIN_PREFIX + networkName;
        }
    }

    /** Returns the sender's network name from a scanned code, or null if it isn't one of ours. */
    static String networkFrom(String scanned) {
        if (scanned == null) return null;
        String value = scanned.trim();
        if (value.startsWith(JOIN_PREFIX)) {
            try {
                value = URLDecoder.decode(value.substring(JOIN_PREFIX.length()), "UTF-8");
            } catch (Exception error) {
                return null;
            }
        }
        return value.startsWith(P2p.PREFIX) && value.length() > P2p.PREFIX.length() ? value : null;
    }

    static Bitmap encode(String text, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y) ? 0xFF14181F : 0xFFFFFFFF;
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        } catch (Exception error) {
            return null;
        }
    }

    /** Decodes one camera preview frame (NV21); null when no QR code is visible. */
    static String decode(byte[] frame, int width, int height) {
        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(frame, width, height, 0, 0, width, height, false);
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source)), hints).getText();
        } catch (Exception | OutOfMemoryError error) {
            return null;
        }
    }
}
