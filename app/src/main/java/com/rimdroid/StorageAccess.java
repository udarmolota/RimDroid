package com.rimdroid;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;

/**
 * All-files access (MANAGE_EXTERNAL_STORAGE) helper.
 *
 * DLC and Workshop mods download into the PUBLIC /Download/RimDroid folder (see
 * {@link AppStorage#getDownloadsDir()}) so the user can see, move, share and reuse them across
 * instances with any file manager. Writing raw file paths there on Android 11+ requires All-files
 * access, which the user grants once on a system settings screen. RimDroid is sideloaded (no Play
 * Store review), so the broad permission is acceptable.
 *
 * minSdk is 30, so {@link Environment#isExternalStorageManager()} and the settings action are always
 * available — no version guards needed.
 */
public final class StorageAccess {

    private StorageAccess() {}

    /** True if the user has granted All-files access (so we can write into public /Download). */
    public static boolean hasAllFilesAccess() {
        return Environment.isExternalStorageManager();
    }

    /**
     * Open the system "All files access" screen for this app. Use from an Activity/Fragment so the
     * user can flip the toggle, then re-check {@link #hasAllFilesAccess()} on resume.
     */
    public static void requestAllFilesAccess(Context ctx) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(intent);
        } catch (Exception e) {
            // Fallback: the generic all-files-access list (some OEMs don't honor the per-app action).
            Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(fallback);
        }
    }
}
