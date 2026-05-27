package com.rimdroid;

import android.app.Application;

public class RimDroidApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppStorage.init(this);
        LauncherPreferences.init(this);
        // Load native libraries built by CMake
        System.loadLibrary("rimdroid");
        System.loadLibrary("rimdroidlinker");
    }
}
