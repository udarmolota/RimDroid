package com.rimdroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.rimdroid.AppStorage;
import com.rimdroid.LauncherPreferences;
import com.rimdroid.R;

import java.io.File;

/**
 * Global app settings: the theme (Light / Dark / Follow system) and the ETC2 texture cache that
 * every MobileGlues launch shares. Per-instance settings (renderer / driver / debug / scale /
 * controls) live behind each launcher card's gear; the device-global custom Vulkan driver import
 * is in the drawer menu (DriverImportFragment).
 */
public class AppSettingsFragment extends Fragment {

    /** Written by box64's rd_etc2cache.c in RIMDROID_CACHE_DIR; one pack file, see that source. */
    private static final String ETC2_CACHE_FILE = "etc2cache.bin";

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_app_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        LauncherPreferences prefs = LauncherPreferences.requireSingleton();
        RadioGroup rg = v.findViewById(R.id.rg_theme);

        int mode = prefs.getThemeMode();
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) rg.check(R.id.rb_theme_light);
        else if (mode == AppCompatDelegate.MODE_NIGHT_YES) rg.check(R.id.rb_theme_dark);
        else rg.check(R.id.rb_theme_system);

        rg.setOnCheckedChangeListener((group, checkedId) -> {
            int m;
            if (checkedId == R.id.rb_theme_light) m = AppCompatDelegate.MODE_NIGHT_NO;
            else if (checkedId == R.id.rb_theme_dark) m = AppCompatDelegate.MODE_NIGHT_YES;
            else m = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            prefs.setThemeMode(m);
            AppCompatDelegate.setDefaultNightMode(m);   // recreates the activity to apply
        });

        Switch swCache = v.findViewById(R.id.sw_etc2_cache);
        TextView tvSize = v.findViewById(R.id.tv_etc2_cache_size);
        Button btnClear = v.findViewById(R.id.btn_etc2_cache_clear);
        swCache.setChecked(prefs.isEtc2Cache());
        swCache.setOnCheckedChangeListener((b, on) -> prefs.setEtc2Cache(on));
        showCacheSize(tvSize, btnClear);
        btnClear.setOnClickListener(b -> {
            File f = etc2CacheFile();
            // The game holds the file open only while it runs; deleting it under a running game
            // just leaves that run writing to an unlinked file, harmless.
            if (f.exists() && !f.delete()) {
                Toast.makeText(requireContext(), R.string.etc2_cache_clear_failed, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), R.string.etc2_cache_cleared, Toast.LENGTH_SHORT).show();
            }
            showCacheSize(tvSize, btnClear);
        });
    }

    private static File etc2CacheFile() {
        return new File(AppStorage.requireSingleton().getCachePath(), ETC2_CACHE_FILE);
    }

    private void showCacheSize(TextView tv, Button clear) {
        File f = etc2CacheFile();
        long bytes = f.exists() ? f.length() : 0;
        String size = bytes < (1L << 20)
                ? (bytes / 1024) + " KB"
                : String.format(java.util.Locale.US, "%.0f MB", bytes / (double) (1L << 20));
        tv.setText(getString(R.string.etc2_cache_size, size));
        clear.setEnabled(bytes > 0);
    }
}
