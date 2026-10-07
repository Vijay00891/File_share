package com.vijay.localfileshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Foreground service that keeps a share running after the user leaves the app.
 * It owns nothing itself: it shows the ongoing notification, holds the CPU and Wi-Fi awake,
 * and keeps the sender discoverable. The share lives in {@link Session}.
 */
public class ShareService extends Service {
    private static final String CHANNEL = "sharing";
    private static final String ACTION_STOP = "com.vijay.localfileshare.STOP";
    private static final int NOTIFICATION_ID = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private String shownSummary;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (Session.kind == Session.Kind.NONE) {
                stopSelf();
                return;
            }
            if (Session.kind == Session.Kind.PHONE_SEND && Session.p2p != null) Session.p2p.pollHost();
            if (!Session.summary().equals(shownSummary)) {
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
            }
            handler.postDelayed(this, 2000);
        }
    };

    static void start(Context context) {
        Intent intent = new Intent(context, ShareService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException ignored) {
            // Sharing still works while the app is open; it just won't be kept alive in the background.
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, ShareService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Session.stop(getApplicationContext());
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException error) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Session.kind == Session.Kind.NONE) {
            // The process was restarted without a share to resume.
            stopSelf();
            return START_NOT_STICKY;
        }
        holdAwake();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 2000);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    // Without these the CPU and Wi-Fi go to sleep soon after the screen turns off.
    @SuppressWarnings("deprecation")
    private void holdAwake() {
        if (wakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power != null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "localshare:sharing");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null) wakeLock.acquire(6 * 60 * 60 * 1000L);
        if (wifiLock == null) {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "localshare:sharing");
                wifiLock.setReferenceCounted(false);
            }
        }
        if (wifiLock != null) wifiLock.acquire();
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "Sharing", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Shown while Local Share is sharing files");
                manager.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            builder = new Notification.Builder(this);
        }

        Intent open = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(this, 1,
                new Intent(this, ShareService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        shownSummary = Session.summary();
        return builder
                .setSmallIcon(R.drawable.ic_up)
                .setColor(Ui.PRIMARY)
                .setContentTitle("Local Share is running")
                .setContentText(shownSummary + ". Tap to open.")
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(R.drawable.ic_close, "Stop sharing", stopIntent)
                .build();
    }
}
