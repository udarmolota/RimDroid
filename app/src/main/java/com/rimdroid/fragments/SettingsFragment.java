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

import com.rimdroid.InstanceSettings;
import com.rimdroid.LauncherPreferences;
import com.rimdroid.LauncherPreferences.VulkanDriverOption;
import com.rimdroid.R;
import com.rimdroid.game.GameInstance;
import com.rimdroid.game.GameInstanceManager;

import java.util.List;

public class SettingsFragment extends Fragment {

    /** Pass the instance name via arguments so this page edits THAT instance's settings. */
    public static final String ARG_INSTANCE = "instance";

    private LauncherPreferences prefs;
    private InstanceSettings inst;   // per-instance: renderer / driver / debug / interpreter / scale / controls
    private String instanceName;     // the instance this page edits

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = LauncherPreferences.requireSingleton();

        // Which instance are we editing? Argument (from the launcher card's gear) → last launched →
        // first installed → a "default" profile (degenerate case when no instances exist yet).
        String instName = getArguments() != null ? getArguments().getString(ARG_INSTANCE) : null;
        if (instName == null || instName.isEmpty()) instName = prefs.getLastInstanceName();
        if (instName == null || instName.isEmpty()) {
            List<GameInstance> all = GameInstanceManager.requireSingleton().getInstances();
            if (!all.isEmpty()) instName = all.get(0).getName();
        }
        if (instName == null || instName.isEmpty()) instName = "default";
        instanceName = instName;
        inst = new InstanceSettings(instName);
        requireActivity().setTitle(getString(R.string.nav_settings) + " — " + instName);

        RadioGroup rgRenderer = view.findViewById(R.id.rg_renderer);
        RadioButton rbGl4es   = view.findViewById(R.id.rb_gl4es);
        RadioButton rbZinkZfa = view.findViewById(R.id.rb_zink_zfa);
        RadioButton rbZinkOsmesa = view.findViewById(R.id.rb_zink_osmesa);
        RadioButton rbSoftpipe = view.findViewById(R.id.rb_softpipe);
        Switch swDebug        = view.findViewById(R.id.sw_debug);
        Switch swStrict       = view.findViewById(R.id.sw_strict_barriers);
        Switch swDragPan      = view.findViewById(R.id.sw_drag_pan);
        final android.widget.Button btnSmoke = view.findViewById(R.id.btn_smoketest);
        final android.widget.Button btnSteamSpike = view.findViewById(R.id.btn_steam_spike);
        final android.widget.Button btnSteamDl = view.findViewById(R.id.btn_steam_dl);
        final TextView tvSteamDlStatus = view.findViewById(R.id.tv_steam_dl_status);

        // Usable renderers: ZINK_ZFA (GPU, default) and SOFTPIPE (CPU fallback for
        // non-Adreno GPUs / devices where Zink fails). Hide the non-working
        // GL4ES / ZINK_OSMESA options.
        rbGl4es.setVisibility(View.GONE);
        rbZinkOsmesa.setVisibility(View.GONE);
        // "Software" (SOFTPIPE) is wired + proven to run Unity (Milestone 2), but NOT
        // yet usable for real play: large textures render BLACK on softpipe and the
        // box64/Mono load is too slow (see memory/software_renderer_plan.md). FULLY HIDDEN
        // (even in Debug) — all the code stays (GameLauncher SOFTPIPE case + enum + native
        // OSMesa path), it's just not selectable. A user with SOFTPIPE saved falls back to
        // ZINK_ZFA so nobody is stuck on the hidden renderer.
        boolean showSoftpipe = false;
        rbSoftpipe.setVisibility(View.GONE);
        switch (inst.getRenderer()) {
            case SOFTPIPE:
                if (showSoftpipe) { rbSoftpipe.setChecked(true); break; }
                // else fall through → reset to the GPU default
            case ZINK_ZFA:
            default:        rbZinkZfa.setChecked(true);
                            inst.setRenderer(LauncherPreferences.Renderer.ZINK_ZFA); break;
        }
        swDebug.setChecked(inst.isDebug());
        swStrict.setChecked(inst.isInterpreter());
        swDragPan.setChecked(inst.isDragPan());
        swDragPan.setOnCheckedChangeListener((btn, checked) -> inst.setDragPan(checked));
        // Interpreter mode (BOX64_DYNAREC=0) is FULLY HIDDEN: on a Mali/MediaTek device it took ~1.5h
        // just to load the APP (not even the menu) — impractical to test, so we don't expose it. The
        // code (toggle + InstanceSettings.interpreter + GameLauncher BOX64_DYNAREC=0) is KEPT for later.
        swStrict.setVisibility(View.GONE);
        // Software-renderer OSMesa smoke test — FULLY HIDDEN (software renderer not user-ready). Click logic kept.
        btnSmoke.setVisibility(View.GONE);
        btnSmoke.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(requireContext(),
                    com.rimdroid.GameActivity.class);
            i.putExtra(com.rimdroid.GameActivity.EXTRA_SMOKETEST, true);
            startActivity(i);
        });

        // Milestone-0 auth-only spike (tag RimDroid/SteamSpike): PASSED, now redundant and easy to
        // confuse with the download spike below (it just logs in then logs off → "nothing happens").
        // Hidden to avoid mis-taps; the download spike does auth + download itself. Click logic kept.
        btnSteamSpike.setVisibility(View.GONE);
        btnSteamSpike.setOnClickListener(v -> {
            final android.content.Context appCtx = requireContext().getApplicationContext();
            final android.app.Activity act = requireActivity();
            final android.os.Handler mainH = new android.os.Handler(android.os.Looper.getMainLooper());
            final float dp = getResources().getDisplayMetrics().density;

            android.widget.LinearLayout box = new android.widget.LinearLayout(act);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (16 * dp);
            box.setPadding(pad, pad, pad, 0);
            final android.widget.EditText etUser = new android.widget.EditText(act);
            etUser.setHint("Steam username");
            etUser.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            final android.widget.EditText etPass = new android.widget.EditText(act);
            etPass.setHint("Steam password");
            etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            box.addView(etUser);
            box.addView(etPass);

            new android.app.AlertDialog.Builder(act)
                    .setTitle("Steam login (spike)")
                    .setMessage("Sent to Steam like DepotDownloader. Then approve in Steam Mobile, or enter the Steam Guard code.")
                    .setView(box)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Sign in", (d, w) -> {
                        String u = etUser.getText().toString().trim();
                        String p = etPass.getText().toString();
                        mainH.post(() -> android.widget.Toast.makeText(appCtx,
                                "Steam: connecting…", android.widget.Toast.LENGTH_SHORT).show());
                        new Thread(new com.rimdroid.SteamSpike(u, p, new com.rimdroid.SteamSpike.Listener() {
                            @Override public java.util.concurrent.CompletableFuture<String> requestSteamGuardCode(boolean prevWrong, String email) {
                                final java.util.concurrent.CompletableFuture<String> fut = new java.util.concurrent.CompletableFuture<>();
                                mainH.post(() -> {
                                    final android.widget.EditText etCode = new android.widget.EditText(act);
                                    etCode.setHint(email != null ? ("Code emailed to " + email) : "Steam Guard code");
                                    etCode.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                                            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                                    new android.app.AlertDialog.Builder(act)
                                            .setTitle(prevWrong ? "Code incorrect — try again" : "Enter Steam Guard code")
                                            .setView(etCode)
                                            .setCancelable(false)
                                            .setPositiveButton("OK", (dd, ww) -> fut.complete(etCode.getText().toString().trim()))
                                            .show();
                                });
                                return fut;
                            }
                            @Override public void onResult(String account, String token) {
                                mainH.post(() -> android.widget.Toast.makeText(appCtx,
                                        "Steam auth OK: " + account, android.widget.Toast.LENGTH_LONG).show());
                            }
                            @Override public void onDone(String message) {
                                mainH.post(() -> android.widget.Toast.makeText(appCtx,
                                        "Steam spike: " + message, android.widget.Toast.LENGTH_LONG).show());
                            }
                        }), "SteamSpike").start();
                    })
                    .show();
        });

        // Steam downloader SPIKE buttons FULLY HIDDEN — superseded by the real "Download game (Steam)"
        // screen (drawer). Click logic kept but unreachable.
        btnSteamDl.setVisibility(View.GONE);
        tvSteamDlStatus.setVisibility(View.GONE);
        btnSteamDl.setOnClickListener(v -> {
            final android.content.Context appCtx = requireContext().getApplicationContext();
            final android.app.Activity act = requireActivity();
            final android.os.Handler mainH = new android.os.Handler(android.os.Looper.getMainLooper());
            final float dp = getResources().getDisplayMetrics().density;

            final boolean manifestOnly = false;  // manifest-only PASSED on device — now do the real download

            android.widget.LinearLayout box = new android.widget.LinearLayout(act);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (16 * dp);
            box.setPadding(pad, pad, pad, 0);
            // Instance name → the game downloads into instances/<name> and becomes launchable.
            final android.widget.EditText etInstance = new android.widget.EditText(act);
            etInstance.setHint("Instance name (e.g. RimWorld 1.5)");
            etInstance.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            final android.widget.EditText etUser = new android.widget.EditText(act);
            etUser.setHint("Steam username");
            etUser.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            final android.widget.EditText etPass = new android.widget.EditText(act);
            etPass.setHint("Steam password");
            etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            // Manifest = game version. RimDroid currently supports RimWorld 1.5 only (Steam "public"
            // is already 1.6). Blank → recommended 1.5 build; a number → pin a specific build.
            final android.widget.EditText etManifest = new android.widget.EditText(act);
            etManifest.setHint("Manifest ID — blank = recommended 1.5");
            etManifest.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            box.addView(etInstance);
            box.addView(etUser);
            box.addView(etPass);
            box.addView(etManifest);

            new android.app.AlertDialog.Builder(act)
                    .setTitle("Steam download spike")
                    .setMessage((manifestOnly ? "MANIFEST-ONLY test. " : "")
                            + "RimDroid currently works with RimWorld 1.5 (Steam 'latest' is already 1.6). "
                            + "Logs in (approve in Steam Mobile / enter code), then downloads the RimWorld 1.5 "
                            + "Linux build into the named instance.")
                    .setView(box)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Start", (d, w) -> {
                        String u = etUser.getText().toString().trim();
                        String p = etPass.getText().toString();
                        String name = etInstance.getText().toString().trim();
                        if (name.isEmpty()) {
                            android.widget.Toast.makeText(appCtx, "Enter an instance name", android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        long manifestId = 0L;   // 0 = latest
                        try {
                            String mt = etManifest.getText().toString().trim();
                            if (!mt.isEmpty()) manifestId = Long.parseLong(mt);
                        } catch (NumberFormatException ignored) { /* blank/invalid → latest */ }
                        final long manifestIdF = manifestId;
                        mainH.post(() -> {
                            tvSteamDlStatus.setVisibility(View.VISIBLE);
                            tvSteamDlStatus.setText("Steam: connecting…");
                        });
                        new Thread(new com.rimdroid.SteamDownloadSpike(u, p, name, manifestOnly, manifestIdF,
                                new com.rimdroid.SteamDownloadSpike.Listener() {
                            @Override public java.util.concurrent.CompletableFuture<String> requestSteamGuardCode(boolean prevWrong, String email) {
                                final java.util.concurrent.CompletableFuture<String> fut = new java.util.concurrent.CompletableFuture<>();
                                mainH.post(() -> {
                                    final android.widget.EditText etCode = new android.widget.EditText(act);
                                    etCode.setHint(email != null ? ("Code emailed to " + email) : "Steam Guard code");
                                    etCode.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                                            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                                    new android.app.AlertDialog.Builder(act)
                                            .setTitle(prevWrong ? "Code incorrect — try again" : "Enter Steam Guard code")
                                            .setView(etCode)
                                            .setCancelable(false)
                                            .setPositiveButton("OK", (dd, ww) -> fut.complete(etCode.getText().toString().trim()))
                                            .show();
                                });
                                return fut;
                            }
                            @Override public void onProgress(String message) {
                                mainH.post(() -> tvSteamDlStatus.setText(message));
                            }
                            @Override public void onDone(String message) {
                                mainH.post(() -> tvSteamDlStatus.setText("Done: " + message));
                            }
                        }), "SteamDownloadSpike").start();
                    })
                    .show();
        });

        rgRenderer.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_zink_zfa) {
                inst.setRenderer(LauncherPreferences.Renderer.ZINK_ZFA);
            } else if (checkedId == R.id.rb_softpipe) {
                inst.setRenderer(LauncherPreferences.Renderer.SOFTPIPE);
            } else if (checkedId == R.id.rb_gl4es) {
                inst.setRenderer(LauncherPreferences.Renderer.GL4ES);
            } else if (checkedId == R.id.rb_zink_osmesa) {
                inst.setRenderer(LauncherPreferences.Renderer.ZINK_OSMESA);
            }
        });

        swDebug.setOnCheckedChangeListener((btn, checked) -> {
            inst.setDebug(checked);   // per-instance debug
            // swStrict (Interpreter) stays fully hidden — impractical to test (1.5h app load on Mali).
            // btnSmoke (OSMesa smoke), rbSoftpipe (software renderer), and the Steam spike buttons
            // (btnSteamDl/btnSteamSpike — superseded by the Download screen) all stay fully hidden.
        });

        swStrict.setOnCheckedChangeListener((btn, checked) -> inst.setInterpreter(checked));

        // --- Vulkan driver picker ---
        Spinner spDriver = view.findViewById(R.id.spinner_vulkan_driver);
        // The "Custom driver (imported)" entry is only usable once a driver has been imported
        // (App settings → Import custom Vulkan driver). Until then it is shown greyed-out and
        // cannot be selected.
        final boolean customAvailable = com.rimdroid.CustomDriverInstaller.isInstalled();
        ArrayAdapter<VulkanDriverOption> driverAdapter = new ArrayAdapter<VulkanDriverOption>(
                requireContext(), android.R.layout.simple_spinner_item,
                LauncherPreferences.VULKAN_DRIVERS) {
            private boolean isCustom(int pos) {
                return com.rimdroid.C.deps.CUSTOM_DRIVER_FILENAME.equals(
                        LauncherPreferences.VULKAN_DRIVERS.get(pos).soName);
            }
            @Override public boolean areAllItemsEnabled() { return customAvailable; }
            @Override public boolean isEnabled(int pos) { return customAvailable || !isCustom(pos); }
            @Override public View getDropDownView(int pos, View convertView, android.view.ViewGroup parent) {
                View v = super.getDropDownView(pos, convertView, parent);
                boolean enabled = isEnabled(pos);
                if (v instanceof TextView) ((TextView) v).setEnabled(enabled);
                v.setEnabled(enabled);
                v.setAlpha(enabled ? 1f : 0.4f);
                return v;
            }
        };
        driverAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDriver.setAdapter(driverAdapter);

        // If the saved choice was the custom driver but none is imported, fall back to System.
        int driverIdx = inst.getVulkanDriverIndex();
        if (!customAvailable && com.rimdroid.C.deps.CUSTOM_DRIVER_FILENAME.equals(
                LauncherPreferences.VULKAN_DRIVERS.get(driverIdx).soName)) {
            driverIdx = 0;
            inst.setVulkanDriverSo(LauncherPreferences.VULKAN_DRIVERS.get(0).soName);
        }
        spDriver.setSelection(driverIdx);
        spDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (!customAvailable && com.rimdroid.C.deps.CUSTOM_DRIVER_FILENAME.equals(
                        LauncherPreferences.VULKAN_DRIVERS.get(pos).soName)) {
                    spDriver.setSelection(0);   // disabled entry — revert to System
                    return;
                }
                inst.setVulkanDriverSo(LauncherPreferences.VULKAN_DRIVERS.get(pos).soName);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // --- Recommended driver: detect the GPU and pick the matching bundled driver ---
        View btnRecommend = view.findViewById(R.id.btn_recommend_driver);
        btnRecommend.setOnClickListener(v -> {
            btnRecommend.setEnabled(false);
            new Thread(() -> {
                com.rimdroid.GpuInfo gpu = com.rimdroid.GpuInfo.query();
                String so = gpu.recommendedDriverSo();
                int idx = 0;
                for (int i = 0; i < LauncherPreferences.VULKAN_DRIVERS.size(); i++) {
                    if (LauncherPreferences.VULKAN_DRIVERS.get(i).soName.equals(so)) { idx = i; break; }
                }
                final int fIdx = idx;
                final String label = LauncherPreferences.VULKAN_DRIVERS.get(idx).label;
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    spDriver.setSelection(fIdx);   // fires onItemSelected → persists the choice
                    btnRecommend.setEnabled(true);
                    android.widget.Toast.makeText(requireContext(),
                            getString(R.string.driver_recommend_result, gpu.displayName(), label),
                            android.widget.Toast.LENGTH_LONG).show();
                });
            }, "rd-gpu-detect").start();
        });

        // --- Edit on-screen controls ---
        view.findViewById(R.id.btn_edit_controls).setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(requireContext(), com.rimdroid.ControlsEditorActivity.class);
            i.putExtra(com.rimdroid.ControlsEditorActivity.EXTRA_INSTANCE_NAME, instanceName);
            startActivity(i);
        });

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
        int pct = Math.max(MIN, inst.getRenderScalePercent());
        sbScale.setMax(100 - MIN);
        sbScale.setProgress(pct - MIN);
        tvScale.setText("Render scale: " + pct + "%");
        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                tvScale.setText("Render scale: " + (progress + MIN) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                inst.setRenderScalePercent(s.getProgress() + MIN);
            }
        });
    }
}
