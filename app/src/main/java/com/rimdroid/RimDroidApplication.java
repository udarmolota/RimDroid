package com.rimdroid;

import android.app.Application;
import android.util.Log;

import java.security.Security;

public class RimDroidApplication extends Application {

    /** Name of the file the crash logger appends uncaught stack traces to (in getFilesDir()). */
    public static final String CRASH_LOG = "crash_uncaught.log";

    /** App context for code paths that have no Context handy (e.g. the Steam download finalizer). */
    public static Application APP;

    @Override
    public void onCreate() {
        super.onCreate();
        APP = this;
        installCrashLogger();          // FIRST — so even early-startup crashes get recorded
        installFullBouncyCastle();
        AppStorage.init(this);
        LauncherPreferences.init(this);
        // Fix up any already-installed 1.6 instances whose UnityPlayer.so is a known-bad build
        // (see RimWorldInstanceSetup / memory unityplayer_4871_swap). Off the UI thread: this
        // hashes a ~33MB file per instance. Game-fix reference assets (e.g. the known-good
        // libsteam_api.so) are extracted FIRST on the same thread so the reconcile can use them.
        new Thread(() -> {
            RimWorldInstanceSetup.ensureGameFixAssets(RimDroidApplication.this);
            RimWorldInstanceSetup.reconcileExistingInstances(
                    AppStorage.requireSingleton().getInstancesDir());
        }, "rd-player-reconcile").start();
        // Apply the user's theme choice (System / Light / Dark) before any activity is shown.
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                LauncherPreferences.requireSingleton().getThemeMode());
        // Load native libraries built by CMake — ONLY in the main process. The ":fmoddec"
        // process (the offline FMOD audio decoder) must NOT load librimdroidlinker, because it
        // interposes dlopen process-wide and loads normal arm64 libs (libfmod) into box64's
        // namespace, crashing them. In :fmoddec, dlopen stays the real bionic one. See
        // FmodDecodeService / [[audio_fmod_plan]].
        String proc = getProcessName();
        boolean mainProcess = (proc == null) || proc.equals(getPackageName());
        if (mainProcess) {
            System.loadLibrary("rimdroid");
            System.loadLibrary("rimdroidlinker");
        } else {
            Log.i("RimDroid", "Secondary process '" + proc + "' — skipping box64 native load");
        }
        // Audio: preload the PulseAudio "simple" shim. Its DT_SONAME is "libpulse-simple.so.0", so once
        // loaded here the dynamic linker registers it under that soname — box64's wrappedpulsesimple
        // dlopen("libpulse-simple.so.0") then resolves to it and RimWorld's FMOD output gets sound via
        // AAudio. Best-effort: if it fails the game just stays silent (as before).
        // Audio backend = ALSA→AAudio. We deliberately do NOT preload the pulse shims: with no native
        // libpulse*, FMOD's PulseAudio output fails to load and FMOD falls back to its ALSA output, which
        // uses our libasound.so.2 shim (soname-registered by this preload) → AAudio. The pulse shims
        // (pulse_shim.c / pulse_simple_shim.c) are kept in the tree/build but inert unless preloaded.
        // Audio: the libasound→AAudio shim is NOT preloaded here. It is loaded on demand in
        // GameLauncher.launch() only when the (experimental, default-off) audio toggle is enabled,
        // so flipping the toggle takes effect on the next game launch without an app restart.
        // Default = no shim → FMOD finds no audio device → clean silence (current FMOD output under
        // box64 is garbled noise).
    }

    /**
     * Replace Android's built-in, STRIPPED-DOWN "BC" security provider with the full
     * bcprov-jdk18on one (same name "BC") so JavaSteam's depot code can do
     * {@code MessageDigest.getInstance("SHA-1", "BC")} when saving manifests.
     *
     * Android ships a trimmed BouncyCastle as provider "BC" (com.android.org.bouncycastle) that
     * lacks SHA-1 MessageDigest; {@code Security.addProvider} is then a no-op because a provider
     * named "BC" already exists, so our added bcprov never takes effect. We remove the system one
     * and append the full provider under the same name. Appending (addProvider) keeps it LOWEST
     * priority so it never overrides Conscrypt/AndroidOpenSSL for default (no-provider) lookups —
     * it only answers when code explicitly asks for provider "BC".
     */
    /**
     * Record EVERY uncaught exception (on ANY thread) to a file before the process dies.
     *
     * The in-app Steam downloader runs network work on threads owned by JavaSteam/ktor; if one of
     * those throws (e.g. a socket dies when the user switches Wi-Fi↔mobile mid-download) the
     * exception is uncaught and Android hard-crashes the app — and nothing lands in our own logs,
     * so a regular user (not a tester) can't tell us what happened. This handler appends the full
     * stack + thread name to {@link #CRASH_LOG} in the app's private files dir (picked up by
     * Settings → "Export logs"), then chains to the platform's default handler so crash behaviour is
     * otherwise unchanged. Best-effort: any failure writing the file is swallowed.
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        final java.io.File logFile = new java.io.File(getFilesDir(), CRASH_LOG);
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try (java.io.PrintWriter w =
                         new java.io.PrintWriter(new java.io.FileWriter(logFile, /* append */ true))) {
                w.println("=== UNCAUGHT " + new java.util.Date() + "  thread='" + thread.getName() + "' ===");
                ex.printStackTrace(w);
                w.println();
            } catch (Throwable ignored) { /* never make crash-logging itself crash */ }
            Log.e("RimDroid", "Uncaught exception on thread '" + thread.getName() + "'", ex);
            if (prev != null) prev.uncaughtException(thread, ex);   // keep default crash behaviour
        });
    }

    private static void installFullBouncyCastle() {
        try {
            Security.removeProvider("BC");
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            Log.i("RimDroid", "Installed full BouncyCastle as provider BC");
        } catch (Throwable t) {
            Log.e("RimDroid", "Failed to install full BouncyCastle provider", t);
        }
    }
}
