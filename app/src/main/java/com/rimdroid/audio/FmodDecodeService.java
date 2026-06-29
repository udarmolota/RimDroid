package com.rimdroid.audio;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;

/**
 * SPIKE: runs the offline FMOD FSB5-Vorbis -> PCM WAV decode in a SEPARATE process
 * ({@code android:process=":fmoddec"} in the manifest) that does NOT load
 * librimdroidlinker. In that process dlopen is the real bionic one, so the bundled
 * native libfmod.so loads in the normal app namespace (with libc++_shared/libaaudio)
 * instead of box64's namespace — fixing the in-process load crash.
 *
 * Result (and the decoded WAVs) land in /sdcard/Download for inspection:
 *   /sdcard/Download/rd_spike_entry.wav, rd_spike_toggle.wav, _fmod_spike_result.txt
 */
public class FmodDecodeService extends Service {
    private static final String TAG = "RimDroid/FmodSpike";
    private static final String EXTRA_INSTANCE = "instance";

    /** Generate the sound pack for a SPECIFIC instance (the one the user opened Settings for). */
    public static void start(Context ctx, String instanceName) {
        Intent i = new Intent(ctx, FmodDecodeService.class);
        if (instanceName != null) i.putExtra(EXTRA_INSTANCE, instanceName);
        ctx.startService(i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String instanceName = (intent != null) ? intent.getStringExtra(EXTRA_INSTANCE) : null;
        new Thread(() -> {
            String result;
            boolean ok = false;
            try {
                // Target the instance the user is actually in — NOT just the first game instance. Generating
                // into a different instance than the one being played = the mod never shows up in the game.
                File inst = (instanceName != null && !instanceName.isEmpty())
                        ? com.rimdroid.AppStorage.requireSingleton().getInstanceDir(instanceName)
                        : FmodDecodeSpike.findFirstInstance(getApplicationContext());
                if (inst == null || !new File(inst, "RimWorldLinux_Data/resources.assets").isFile()) {
                    result = "instance '" + (instanceName != null ? instanceName : "(auto)")
                           + "' has no game files — nothing to decode";
                } else {
                    result = "Generating sound pack for: " + inst.getName() + "\n"
                           + FmodDecodeSpike.generatePack(getApplicationContext(), inst);
                    ok = true;
                }
            } catch (Throwable t) {
                result = "FmodDecodeService crashed: " + t;
                Log.e(TAG, result, t);
            }
            Log.i(TAG, "RESULT:\n" + result);
            try {
                File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                try (FileWriter w = new FileWriter(new File(dl, "_fmod_spike_result.txt"))) {
                    w.write(result);
                }
            } catch (Throwable t) {
                Log.e(TAG, "could not write result file", t);
            }
            // User-visible completion message. The decode runs silently in this background :fmoddec
            // process and the user is usually IN THE GAME when it finishes — a Toast (2 s, easy to miss)
            // isn't enough. A notification persists in the shade until read → guaranteed seen.
            notifyDone(ok);
            stopSelf(startId);
        }, "FmodDecodeSvc").start();
        return START_NOT_STICKY;
    }

    /** Persistent completion notification — survives the user being in-game (unlike a 2 s toast), so the
     *  "sound pack ready, turn on Game sound" message is guaranteed to be seen and read. */
    private void notifyDone(boolean ok) {
        try {
            String ch = "rimdroid_sound";
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            nm.createNotificationChannel(new android.app.NotificationChannel(
                    ch, "Sound pack", android.app.NotificationManager.IMPORTANCE_HIGH));
            String text = getString(ok ? com.rimdroid.R.string.sound_done : com.rimdroid.R.string.sound_failed);
            Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            android.app.PendingIntent pi = (open == null) ? null : android.app.PendingIntent.getActivity(
                    this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.Notification n = new android.app.Notification.Builder(this, ch)
                    .setSmallIcon(ok ? android.R.drawable.stat_sys_download_done
                                     : android.R.drawable.stat_notify_error)
                    .setContentTitle(getString(com.rimdroid.R.string.app_name))
                    .setContentText(text)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify(9100, n);
        } catch (Throwable t) {
            Log.e(TAG, "notifyDone failed", t);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
