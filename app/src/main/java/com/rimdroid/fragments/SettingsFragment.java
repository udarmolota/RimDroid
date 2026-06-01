package com.rimdroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.rimdroid.LauncherPreferences;
import com.rimdroid.LauncherPreferences.VulkanDriverOption;
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

        // Only ZINK_ZFA works right now — hide GL4ES / ZINK_OSMESA and force ZFA.
        rbGl4es.setVisibility(View.GONE);
        rbZinkOsmesa.setVisibility(View.GONE);
        rbZinkZfa.setChecked(true);
        prefs.setRenderer(LauncherPreferences.Renderer.ZINK_ZFA);
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

        // --- Vulkan driver picker ---
        Spinner spDriver = view.findViewById(R.id.spinner_vulkan_driver);
        ArrayAdapter<VulkanDriverOption> driverAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_item,
                LauncherPreferences.VULKAN_DRIVERS);
        driverAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDriver.setAdapter(driverAdapter);
        spDriver.setSelection(prefs.getVulkanDriverIndex());
        spDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                prefs.setVulkanDriverSo(LauncherPreferences.VULKAN_DRIVERS.get(pos).soName);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // --- Render scale (UI size) seek bar: 30..100%, lower = bigger UI ---
        SeekBar sbScale  = view.findViewById(R.id.sb_render_scale);
        TextView tvScale = view.findViewById(R.id.tv_render_scale_label);
        final int MIN = LauncherPreferences.RENDER_SCALE_MIN;   // 67; seek max=33 → 67..100
        int pct = prefs.getRenderScalePercent();
        sbScale.setProgress(pct - MIN);
        tvScale.setText("Render scale: " + pct + "%");
        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                tvScale.setText("Render scale: " + (progress + MIN) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                prefs.setRenderScalePercent(s.getProgress() + MIN);
            }
        });
    }
}
