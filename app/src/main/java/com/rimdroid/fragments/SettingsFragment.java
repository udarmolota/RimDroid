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
        RadioButton rbSoftpipe = view.findViewById(R.id.rb_softpipe);
        Switch swDebug        = view.findViewById(R.id.sw_debug);
        Switch swStrict       = view.findViewById(R.id.sw_strict_barriers);

        // Usable renderers: ZINK_ZFA (GPU, default) and SOFTPIPE (CPU fallback for
        // non-Adreno GPUs). Hide the non-working GL4ES / ZINK_OSMESA options.
        rbGl4es.setVisibility(View.GONE);
        rbZinkOsmesa.setVisibility(View.GONE);
        // "Software" hidden for now: the zfa frontend hardcodes a Zink screen and ignores
        // GALLIUM_DRIVER, so softpipe isn't actually wired yet (needs an OSMesa+blit path).
        // Showing it would just mislabel a Zink run. Force ZINK_ZFA.
        rbSoftpipe.setVisibility(View.GONE);
        rbZinkZfa.setChecked(true);
        prefs.setRenderer(LauncherPreferences.Renderer.ZINK_ZFA);
        swDebug.setChecked(prefs.isDebug());
        swStrict.setChecked(prefs.isStrictBarriers());
        // Interpreter mode is a developer/tester diagnostic (very slow). Show it only when
        // Debug mode is on, so public users never see it.
        swStrict.setVisibility(prefs.isDebug() ? View.VISIBLE : View.GONE);

        rgRenderer.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_zink_zfa) {
                prefs.setRenderer(LauncherPreferences.Renderer.ZINK_ZFA);
            } else if (checkedId == R.id.rb_softpipe) {
                prefs.setRenderer(LauncherPreferences.Renderer.SOFTPIPE);
            } else if (checkedId == R.id.rb_gl4es) {
                prefs.setRenderer(LauncherPreferences.Renderer.GL4ES);
            } else if (checkedId == R.id.rb_zink_osmesa) {
                prefs.setRenderer(LauncherPreferences.Renderer.ZINK_OSMESA);
            }
        });

        swDebug.setOnCheckedChangeListener((btn, checked) -> {
            prefs.getSharedPrefs().edit().putBoolean("debug_mode", checked).apply();
            swStrict.setVisibility(checked ? View.VISIBLE : View.GONE);   // reveal/hide the tester-only Interpreter toggle
        });

        swStrict.setOnCheckedChangeListener((btn, checked) ->
                prefs.getSharedPrefs().edit().putBoolean("strict_barriers", checked).apply());

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

        // --- Edit on-screen controls ---
        view.findViewById(R.id.btn_edit_controls).setOnClickListener(v ->
                startActivity(new android.content.Intent(requireContext(), com.rimdroid.ControlsEditorActivity.class)));

        // --- Render scale (UI size / GPU load) seek bar: device-floor..100% ---
        // The floor keeps the internal resolution >=1280x720 (RimWorld UI minimum) and
        // is per-device: a 1080p screen floors at ~67%, a 1440p screen at ~50%, so weak
        // high-res phones can scale further down for more FPS. Lower = bigger UI, lighter.
        SeekBar sbScale  = view.findViewById(R.id.sb_render_scale);
        TextView tvScale = view.findViewById(R.id.tv_render_scale_label);
        android.graphics.Rect bounds =
                requireActivity().getWindowManager().getCurrentWindowMetrics().getBounds();
        int sLong  = Math.max(bounds.width(), bounds.height());
        int sShort = Math.min(bounds.width(), bounds.height());
        final int MIN = LauncherPreferences.minRenderScalePercent(sLong, sShort);
        int pct = Math.max(MIN, prefs.getRenderScalePercent());
        sbScale.setMax(100 - MIN);
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
