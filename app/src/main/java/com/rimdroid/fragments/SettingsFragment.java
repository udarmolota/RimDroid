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
        Switch swCompat       = view.findViewById(R.id.sw_compat_mode);
        Switch swHaptic       = view.findViewById(R.id.sw_haptic);
        Switch swAudio        = view.findViewById(R.id.sw_audio);
        Switch swShowFps      = view.findViewById(R.id.sw_show_fps);
        final android.widget.Button btnSmoke = view.findViewById(R.id.btn_smoketest);
        final android.widget.Button btnSteamSpike = view.findViewById(R.id.btn_steam_spike);
        final android.widget.Button btnSteamDl = view.findViewById(R.id.btn_steam_dl);
        final TextView tvSteamDlStatus = view.findViewById(R.id.tv_steam_dl_status);

        // Usable renderers: ZINK_ZFA (GPU, default) and SOFTPIPE (CPU fallback for
        // non-Adreno GPUs / devices where Zink fails). Hide the non-working
        // GL4ES / ZINK_OSMESA options.
        rbGl4es.setVisibility(View.GONE);
        rbZinkOsmesa.setVisibility(View.GONE);
        // "Software" (SOFTPIPE) — CPU renderer that bypasses Vulkan. HIDDEN again: it does not
        // render Unity's scene (only immediate-mode overlays reach FBO 0 → black/dark screen),
        // so it is not user-ready. Code path kept; the radio is GONE and any instance still set to
        // SOFTPIPE falls through to the GPU default (ZINK_ZFA) below.
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
        // Compatibility mode: box64 FP/barrier tuning (WEAKBARRIER=2 + X87DOUBLE=1) that lets the game launch
        // on devices hit by the deep "won't start / black screen" bug (Adreno 610/725, weak-Vulkan Mali).
        swCompat.setChecked(inst.isCompatibilityMode());
        swCompat.setOnCheckedChangeListener((btn, checked) -> {
            inst.setCompatibilityMode(checked);
            if (checked) {
                // Warn that compat mode is a temporary workaround (may not fully work) + point to the
                // save-fix mod if pawns disappear. Dialog (not a toast) so the user actually reads it.
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.compat_mode_dialog_title)
                        .setMessage(R.string.compat_mode_dialog_msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
        swHaptic.setChecked(inst.isHapticFeedback());
        swHaptic.setOnCheckedChangeListener((btn, checked) -> inst.setHapticFeedback(checked));
        // FPS overlay ("FPS: XX", top-left) — GLOBAL. Shows the true presented frame rate; helps
        // compare devices / render scales (e.g. 720p vs native). Takes effect next game launch.
        swShowFps.setChecked(prefs.isShowFps());
        swShowFps.setOnCheckedChangeListener((btn, checked) -> prefs.setShowFps(checked));
        // Audio is GLOBAL (experimental, default off) — the libasound→AAudio shim is loaded at game
        // launch only when this is on. Off = clean silence (FMOD output under box64 is still garbled).
        swAudio.setChecked(LauncherPreferences.requireSingleton().isAudioEnabled());
        // The Sound toggle is the real master switch: it flips BOTH the audio output shim (global) AND
        // the generated sound mod's active state in THIS instance's ModsConfig — so turning sound on/off
        // needs no in-game mod fiddling. (Generate the pack once with the button; then just toggle.)
        swAudio.setOnCheckedChangeListener((btn, checked) -> {
            LauncherPreferences.requireSingleton().setAudioEnabled(checked);
            java.io.File instDir = com.rimdroid.AppStorage.requireSingleton().getInstanceDir(instanceName);
            String r = com.rimdroid.audio.FmodDecodeSpike.setSoundModActive(instDir, checked);
            android.util.Log.i("RimDroid/Settings", "sound mod " + (checked ? "on" : "off") + ": " + r);
        });

        // Advanced: per-instance extra env vars (KEY=VALUE, space-separated). Applied last in
        // GameLauncher so they override the box64 defaults. Diagnostic/perf knob (e.g.
        // BOX64_DYNAREC_ALIGNED_ATOMICS=1 for the Mali/Cortex save bug, or BOX64_DYNAREC_STRONGMEM=2).
        final android.widget.EditText etEnv = view.findViewById(R.id.et_env_vars);
        String envCur = inst.getEnvVars();
        etEnv.setText(envCur != null ? envCur : "");
        etEnv.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                inst.setEnvVars(s.toString().replace('\n', ' '));   // newlines → spaces (KEY=VALUE list)
            }
        });
        // The whole Advanced card is collapsed by default (power-user diagnostics — too many testers
        // wandered in). Tap the header to expand/collapse; the arrow flips to match.
        final TextView advHeader = view.findViewById(R.id.tv_advanced_header);
        final View advContent = view.findViewById(R.id.ll_advanced_content);
        advHeader.setOnClickListener(v -> {
            boolean show = advContent.getVisibility() != View.VISIBLE;
            advContent.setVisibility(show ? View.VISIBLE : View.GONE);
            advHeader.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                    show ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float, 0);
        });
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

            new com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
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
                                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
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

        // Anon-mod-download spike — REMOVED (the real anonymous downloader shipped). Button hidden.
        btnSteamSpike.setVisibility(View.GONE);

        // FMOD decode SPIKE (repurposes the redundant Steam-login spike button). Proves on-device
        // FSB5-Vorbis -> PCM WAV via the bundled native libfmod.so (bypasses box64's broken Vorbis).
        // Test: adb push _spike_entry.fsb / _spike_toggle.fsb to /sdcard/Download, tap, then pull
        // /sdcard/Download/rd_spike_*.wav and compare to the oracle. (Overrides the GONE/onClick above.)
        // Label/caption come from the layout ("Generate / update sound pack"). Decode runs in the
        // ":fmoddec" process (clean dlopen → libfmod loads OK), writes the mod into the instance and
        // enables it. Completion is reported by FmodDecodeService (toast); here we just kick it off.
        btnSteamSpike.setVisibility(View.VISIBLE);
        btnSteamSpike.setOnClickListener(v -> {
            final android.content.Context appCtx = requireContext().getApplicationContext();
            com.rimdroid.audio.FmodDecodeService.start(appCtx, instanceName);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.sound_installing_title)
                    .setMessage(R.string.sound_generate_toast)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });

        // Steam downloader SPIKE button (full game download) HIDDEN — superseded by the real
        // "Download game (Steam)" screen. Click logic kept but unreachable.
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

            new com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
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
                                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
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

        // --- Gamepad button mapping (fix swapped/inverted controllers) ---
        view.findViewById(R.id.btn_gamepad_mapper).setOnClickListener(v ->
            startActivity(new android.content.Intent(requireContext(), com.rimdroid.GamepadMapperActivity.class)));

        // --- Render resolution dropdown (Video card): per-device presets, floor (~720p) .. native ---
        // RE-ENABLED 2026-06-28: the "flicker" that caused the hold was RimWorld's missing-mods grey screen, not a
        // render bug, so reduced res is safe. (Weak FPS lever — CPU-bound — but exposed for user agency + A/B test.)
        // Lower internal resolution = more FPS on weak GPUs + a bigger in-game UI (slightly blurry); native is
        // sharpest. The floor keeps the render >= ~1280x720 (RimWorld UI minimum) and is per-device (a 1440p
        // screen floors at ~50%, a 1080p screen at ~67%). Width auto-fits the panel aspect so the game still
        // fills the whole screen. Stored as a render-scale %, applied at the next launch.
        android.widget.Spinner spRes = view.findViewById(R.id.spinner_render_res);
        android.graphics.Rect bounds =
                requireActivity().getWindowManager().getCurrentWindowMetrics().getBounds();
        final int sLong  = Math.max(bounds.width(), bounds.height());   // landscape width
        final int sShort = Math.min(bounds.width(), bounds.height());   // landscape height = native render height
        final int MIN = LauncherPreferences.minRenderScalePercent(sLong, sShort);
        // Exactly 3 presets on every device: ~720p floor (fastest) · halfway (balanced) · native.
        final int MID = Math.max(MIN + 1, Math.min(99, (MIN + 100) / 2));
        java.util.LinkedHashSet<Integer> pctSet = new java.util.LinkedHashSet<>();
        pctSet.add(MIN); pctSet.add(MID); pctSet.add(100);
        final java.util.List<Integer> pcts = new java.util.ArrayList<>(pctSet);
        java.util.Collections.sort(pcts);
        java.util.List<String> resLabels = new java.util.ArrayList<>();
        for (int p : pcts) {
            int W = Math.round(sLong * p / 100f), H = Math.round(sShort * p / 100f);
            String tag = (p == 100) ? " — native" : (p == MIN) ? " — fastest" : " — balanced";
            resLabels.add(W + "×" + H + tag);
        }
        android.widget.ArrayAdapter<String> resAd = new android.widget.ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_item, resLabels);
        resAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRes.setAdapter(resAd);
        int curPct = Math.max(MIN, inst.getRenderScalePercent());
        int resSel = 0, resBest = Integer.MAX_VALUE;
        for (int i = 0; i < pcts.size(); i++) {
            int d = Math.abs(pcts.get(i) - curPct);
            if (d < resBest) { resBest = d; resSel = i; }
        }
        spRes.setSelection(resSel);
        spRes.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            boolean first = true;   // skip the programmatic initial selection — only react to the user
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                if (first) { first = false; return; }
                inst.setRenderScalePercent(pcts.get(pos));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }
}
