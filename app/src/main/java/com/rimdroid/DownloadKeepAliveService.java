package com.rimdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Tiny foreground service that runs ONLY to keep the app process at foreground-service priority while a
 * Steam download is in progress. The actual download runs on its own thread (SteamDownloadSpike) in the
 * same process; this service stops Android from freezing/killing that process (and its CM websocket)
 * when the app is backgrounded — which previously dropped the connection mid-download.
 *
 * Start it when a download begins, stop it when it finishes. Uses the DATA_SYNC foreground type
 * (declared in the manifest) + a low-importance ongoing notification.
 */
public class DownloadKeepAliveService extends Service {

    private static final String CHANNEL_ID = "rd_download";
    private static final int NOTIF_ID = 4242;

    /** Intent extra: what the ongoing notification should say (the service is shared by the
     *  game downloader and the Steam Cloud save sync, which are different activities to the user). */
    private static final String EXTRA_TEXT = "text";
    private static final String DEFAULT_TEXT = "Downloading from Steam — keep the app open";

    /**
     * A start() whose onStartCommand has not run yet. A stop() can land in that window — a download
     * that fails the instant it begins (no network, say) calls it within milliseconds of start() —
     * and stopping a service started with startForegroundService() before it has called
     * startForeground() is not a no-op: Android crashes the app ("Context.startForegroundService()
     * did not then call Service.startForeground()"). So an early stop is only recorded, and the
     * service carries it out itself once it has gone foreground.
     */
    private static boolean startPending;
    /** A stop() that arrived while {@link #startPending}. */
    private static boolean stopPending;

    public static void start(Context ctx) { start(ctx, DEFAULT_TEXT); }

    public static void start(Context ctx, String text) {
        synchronized (DownloadKeepAliveService.class) {
            startPending = true;
            stopPending = false;
        }
        Intent i = new Intent(ctx.getApplicationContext(), DownloadKeepAliveService.class);
        i.putExtra(EXTRA_TEXT, text);
        try {
            ContextCompat.startForegroundService(ctx.getApplicationContext(), i);
        } catch (RuntimeException e) {
            // Refused (e.g. asked from the background): no onStartCommand is coming to clear it.
            synchronized (DownloadKeepAliveService.class) { startPending = false; }
            throw e;
        }
    }

    public static void stop(Context ctx) {
        synchronized (DownloadKeepAliveService.class) {
            if (startPending) { stopPending = true; return; }
        }
        ctx.getApplicationContext().stopService(
                new Intent(ctx.getApplicationContext(), DownloadKeepAliveService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        String text = (intent != null && intent.getStringExtra(EXTRA_TEXT) != null)
                ? intent.getStringExtra(EXTRA_TEXT) : DEFAULT_TEXT;
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("RimDroid")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        boolean stopNow;
        synchronized (DownloadKeepAliveService.class) {
            startPending = false;
            stopNow = stopPending;
            stopPending = false;
        }
        // Foreground as promised, so a stop that came early is now safe to honour.
        if (stopNow) stopSelf();
        return START_NOT_STICKY;   // don't auto-restart if killed
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
