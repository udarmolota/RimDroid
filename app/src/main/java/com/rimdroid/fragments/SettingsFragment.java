package com.rimdroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;

import androidx.fragment.app.Fragment;

import com.rimdroid.LauncherPreferences;
import com.rimdroid.R;

public class SettingsFragment extends Fragment {

    private LauncherPreferences prefs;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = LauncherPreferences.requireSingleton();

        RadioGroup rgRenderer = view.findViewById(R.id.rg_renderer);
        RadioButton rbGl4es   = view.findViewById(R.id.rb_gl4es);
        RadioButton rbZinkZfa = view.findViewById(R.id.rb_zink_zfa);
        RadioButton rbZinkOsmesa = view.findViewById(R.id.rb_zink_osmesa);
        Switch swDebug        = view.findViewById(R.id.sw_debug);

        // Restore current selection
        switch (prefs.getRenderer()) {
            case ZINK_ZFA:    rbZinkZfa.setChecked(true);    break;
            case ZINK_OSMESA: rbZinkOsmesa.setChecked(true); break;
            default:          rbGl4es.setChecked(true);       break;
        }
        swDebug.setChecked(prefs.isDebug());

        rgRenderer.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_gl4es) {
                prefs.setRenderer(LauncherPreferences.Renderer.GL4ES);
            } else if (checkedId == R.id.rb_zink_zfa) {
                prefs.setRenderer(LauncherPreferences.Renderer.ZINK_ZFA);
            } else if (checkedId == R.id.rb_zink_osmesa) {
                prefs.setRenderer(LauncherPreferences.Renderer.ZINK_OSMESA);
            }
        });

        swDebug.setOnCheckedChangeListener((btn, checked) ->
                prefs.getSharedPrefs().edit().putBoolean("debug_mode", checked).apply());
    }
}
