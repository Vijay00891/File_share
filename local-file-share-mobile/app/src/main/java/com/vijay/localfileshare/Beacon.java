package com.vijay.localfileshare;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;

/**
 * "Find nearby" over Bluetooth Low Energy. The sender broadcasts a tiny packet tagged for this
 * app that says how to join it; receivers listen for that tag. Phones without the app ignore it,
 * and this app ignores everything else, so only Local Share users see each other.
 */
@SuppressLint("MissingPermission")
class Beacon {
    interface Listener {
        void onFound(Target target);
    }

    // The two halves of the announcement travel under separate tags (advertisement and scan response).
    private static final ParcelUuid PART1 = ParcelUuid.fromString("0000f5a1-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid PART2 = ParcelUuid.fromString("0000f5a2-0000-1000-8000-00805f9b34fb");

    private final BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private Listener listener;
    private volatile boolean advertiseFailed;

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            advertiseFailed = true;
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            ScanRecord record = result.getScanRecord();
            if (record == null || listener == null) return;
            byte[] part1 = record.getServiceData(PART1);
            if (part1 == null) return;
            Target target = Target.fromBeacon(part1, record.getServiceData(PART2));
            if (target != null) listener.onFound(target);
        }
    };

    Beacon(Context context) {
        BluetoothManager manager = (BluetoothManager) context.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    static boolean bluetoothOn(Context context) {
        try {
            BluetoothManager manager = (BluetoothManager) context.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            return adapter != null && adapter.isEnabled();
        } catch (RuntimeException error) {
            return false;
        }
    }

    static boolean bluetoothPresent(Context context) {
        BluetoothManager manager = (BluetoothManager) context.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return manager != null && manager.getAdapter() != null;
    }

    /** Starts announcing the sender. False when Bluetooth is off, unsupported or not permitted. */
    boolean advertise(Target target) {
        byte[][] parts = target.toBeacon();
        if (parts == null) return false;
        try {
            if (adapter == null || !adapter.isEnabled()) return false;
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) return false;
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .setTimeout(0)
                    .build();
            AdvertiseData first = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceData(PART1, parts[0])
                    .build();
            AdvertiseData.Builder second = new AdvertiseData.Builder().setIncludeDeviceName(false);
            if (parts[1].length > 0) second.addServiceData(PART2, parts[1]);
            advertiseFailed = false;
            advertiser.startAdvertising(settings, first, second.build(), advertiseCallback);
            return true;
        } catch (RuntimeException error) {
            advertiser = null;
            return false;
        }
    }

    boolean advertising() {
        return advertiser != null && !advertiseFailed;
    }

    /** Starts listening for senders. False when Bluetooth is off, unsupported or not permitted. */
    boolean scan(Listener found) {
        try {
            if (adapter == null || !adapter.isEnabled()) return false;
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return false;
            listener = found;
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, settings, scanCallback);
            return true;
        } catch (RuntimeException error) {
            scanner = null;
            return false;
        }
    }

    void stop() {
        listener = null;
        try {
            if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        } catch (RuntimeException ignored) {
        }
        try {
            if (scanner != null) scanner.stopScan(scanCallback);
        } catch (RuntimeException ignored) {
        }
        advertiser = null;
        scanner = null;
    }
}
