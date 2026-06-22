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

    public static void start(Context ctx) {
        ctx.startService(new Intent(ctx, FmodDecodeService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            String result;
            boolean ok = false;
            try {
                File inst = FmodDecodeSpike.findFirstInstance(getApplicationContext());
                if (inst == null) {
                    result = "no installed instance with game files found";
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
            // User-visible completion message: the decode runs silently in this background :fmoddec
            // process, so without this the user never knows when the sound pack is ready. Toast is
            // system-level → shows over the app even from a separate process.
            final boolean done = ok;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(getApplicationContext(),
                    getString(done ? com.rimdroid.R.string.sound_done : com.rimdroid.R.string.sound_failed),
                    android.widget.Toast.LENGTH_LONG).show());
            stopSelf(startId);
        }, "FmodDecodeSvc").start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
