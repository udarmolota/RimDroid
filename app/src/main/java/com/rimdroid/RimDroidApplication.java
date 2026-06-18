package com.rimdroid;

import android.app.Application;
import android.util.Log;

import java.security.Security;

public class RimDroidApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        installFullBouncyCastle();
        AppStorage.init(this);
        LauncherPreferences.init(this);
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
