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
        // Load native libraries built by CMake
        System.loadLibrary("rimdroid");
        System.loadLibrary("rimdroidlinker");
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
