package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class AetherProxyService extends Service {
    public static final String ACTION_START = "org.telegram.messenger.AETHER_START";
    public static final String ACTION_STOP = "org.telegram.messenger.AETHER_STOP";
    private static final String CHANNEL_ID = "aether_proxy";
    private static final int NOTIFICATION_ID = 0x41455448; // "AETH"
    private final Object notificationLock = new Object();
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            AetherProxyManager.stopProcess();
            AetherProxyManager.setProgressListener(null);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        updateNotificationStarting();
        AetherProxyManager.setProgressListener(new AetherProxyManager.ProgressListener() {
            @Override
            public void onDownloadStart(long totalBytes) {
                updateNotificationProgress(0, totalBytes);
            }

            @Override
            public void onDownloadProgress(long downloadedBytes, long totalBytes) {
                updateNotificationProgress(downloadedBytes, totalBytes);
            }

            @Override
            public void onDownloadDone(boolean success) {
                if (!success) {
                    updateNotificationFailed();
                } else {
                    updateNotificationStarting();
                }
            }
        });
        new Thread(() -> {
            try {
                AetherProxyManager.ensureRunning(SharedConfig.currentProxy);
            } finally {
                AetherProxyManager.setProgressListener(null);
                if (AetherProxyManager.waitForSocksReady(10_000)) {
                    AetherProxyManager.markRunning();
                    updateNotificationRunning();
                } else {
                    AetherProxyManager.markFailed("socks not ready");
                    updateNotificationFailed();
                }
            }
        }, "AetherStart").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AetherProxyManager.stopProcess();
        AetherProxyManager.setProgressListener(null);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.AetherProxy), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.AetherProxyRunning));
            notificationManager.createNotificationChannel(channel);
        }
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.AetherProxy))
                .setContentText(getString(R.string.AetherProxyStarting))
                .setSmallIcon(R.drawable.msg_policy)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE);
        return notificationBuilder.build();
    }

    private void updateNotificationProgress(long downloadedBytes, long totalBytes) {
        synchronized (notificationLock) {
            if (notificationManager == null || notificationBuilder == null) {
                return;
            }
            if (totalBytes > 0) {
                int percent = (int) Math.min(100, (downloadedBytes * 100) / totalBytes);
                notificationBuilder
                        .setContentText(getString(R.string.AetherProxyDownloadingPercent, percent))
                        .setProgress(100, percent, false);
            } else {
                notificationBuilder
                        .setContentText(getString(R.string.AetherProxyDownloading))
                        .setProgress(0, 0, true);
            }
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    private void updateNotificationStarting() {
        synchronized (notificationLock) {
            if (notificationManager == null || notificationBuilder == null) {
                return;
            }
            notificationBuilder
                    .setContentText(getString(R.string.AetherProxyStarting))
                    .setProgress(0, 0, true);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    private void updateNotificationRunning() {
        synchronized (notificationLock) {
            if (notificationManager == null || notificationBuilder == null) {
                return;
            }
            notificationBuilder
                    .setContentText(getString(R.string.AetherProxyRunning))
                    .setProgress(0, 0, false);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    private void updateNotificationFailed() {
        synchronized (notificationLock) {
            if (notificationManager == null || notificationBuilder == null) {
                return;
            }
            notificationBuilder
                    .setContentText(getString(R.string.AetherProxyFailed))
                    .setProgress(0, 0, false);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }
}
