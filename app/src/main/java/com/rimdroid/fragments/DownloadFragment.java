package com.rimdroid.fragments;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.rimdroid.R;
import com.rimdroid.SteamDownloadSpike;
import com.rimdroid.StorageAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * "Download game (Steam)" screen — front-end for the in-app Steam downloader (SteamDownloadSpike).
 * One page, three tabs sharing the Steam login + console log:
 *   • Game — downloads RimWorld 1.5 into instances/&lt;name&gt; (launchable).
 *   • DLC  — downloads each owned DLC as a portable .zip into /Download/RimDroid (needs All-files access).
 *   • Mods — Workshop (anonymous) — coming soon.
 * Login is sent to Steam (like DepotDownloader) and never stored; the token lives only in memory.
 */
public class DownloadFragment extends Fragment {

    private final Handler mainH = new Handler(Looper.getMainLooper());

    private EditText etInstance, etUser, etPass, etManifest, etModsIds, etModsBrowserId;
    private Button btnStart, btnDlcStart, btnModsStart, btnModsBrowser;
    private CheckBox cbRoyalty, cbIdeology, cbBiotech, cbAnomaly;
    private ProgressBar progress;
    private TextView tvStatus;
    private View blockLogin, sectionGame, sectionDlc, sectionMods, btnInstallContent;
    private android.content.Context appCtx;   // for the keep-alive service (valid even if detached)
    private volatile boolean downloading;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        appCtx = requireContext().getApplicationContext();
        etInstance = v.findViewById(R.id.et_dl_instance);
        etUser     = v.findViewById(R.id.et_dl_user);
        etPass     = v.findViewById(R.id.et_dl_pass);
        etManifest = v.findViewById(R.id.et_dl_manifest);
        btnStart   = v.findViewById(R.id.btn_dl_start);
        btnDlcStart = v.findViewById(R.id.btn_dlc_start);
        cbRoyalty  = v.findViewById(R.id.cb_dlc_royalty);
        cbIdeology = v.findViewById(R.id.cb_dlc_ideology);
        cbBiotech  = v.findViewById(R.id.cb_dlc_biotech);
        cbAnomaly  = v.findViewById(R.id.cb_dlc_anomaly);
        etModsIds  = v.findViewById(R.id.et_mods_ids);
        btnModsStart = v.findViewById(R.id.btn_mods_start);
        etModsBrowserId = v.findViewById(R.id.et_mods_browser_id);
        btnModsBrowser = v.findViewById(R.id.btn_mods_browser);
        progress   = v.findViewById(R.id.progress_dl);
        tvStatus   = v.findViewById(R.id.tv_dl_status);
        blockLogin  = v.findViewById(R.id.block_login);
        sectionGame = v.findViewById(R.id.section_game);
        sectionDlc  = v.findViewById(R.id.section_dlc);
        sectionMods = v.findViewById(R.id.section_mods);

        btnInstallContent = v.findViewById(R.id.btn_install_content);
        tvStatus.setMovementMethod(new ScrollingMovementMethod());   // make the log area scrollable
        btnStart.setOnClickListener(view -> startGame());
        btnDlcStart.setOnClickListener(view -> startDlc());
        btnModsStart.setOnClickListener(view -> startMods());
        btnModsBrowser.setOnClickListener(view -> openModInBrowser());
        btnInstallContent.setOnClickListener(view ->
                androidx.navigation.Navigation.findNavController(view).navigate(R.id.action_open_install));

        // Game / DLC / Mods selector → swap the visible section. Login block shown for Game + DLC.
        // The "install downloaded content" button is for DLC/Mods only (Game makes an instance directly).
        com.google.android.material.button.MaterialButtonToggleGroup toggle =
                v.findViewById(R.id.toggle_dl_type);
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean game = checkedId == R.id.btn_type_game;
            sectionGame.setVisibility(game ? View.VISIBLE : View.GONE);
            sectionDlc.setVisibility(checkedId == R.id.btn_type_dlc ? View.VISIBLE : View.GONE);
            sectionMods.setVisibility(checkedId == R.id.btn_type_mods ? View.VISIBLE : View.GONE);
            blockLogin.setVisibility(View.VISIBLE);   // login needed for game, DLC and mods
            btnInstallContent.setVisibility(game ? View.GONE : View.VISIBLE);
        });
        toggle.check(R.id.btn_type_game);   // default to Game
    }

    // ---- Game ----
    private void startGame() {
        if (downloading) return;
        final String name = text(etInstance);
        if (name.isEmpty()) { etInstance.setError(getString(R.string.error_name_required)); return; }
        if (!loginFilled()) return;

        long manifestId = 0L;   // 0 = recommended 1.5 build
        try {
            String mt = text(etManifest);
            if (!mt.isEmpty()) manifestId = Long.parseLong(mt);
        } catch (NumberFormatException ignored) { /* blank/invalid → default */ }

        beginUi();
        new Thread(new SteamDownloadSpike(text(etUser), etPass.getText().toString(), name,
                /* manifestOnly */ false, manifestId, makeListener(name)), "rd-download").start();
    }

    // ---- DLC ----
    private void startDlc() {
        if (downloading) return;
        if (!loginFilled()) return;

        List<SteamDownloadSpike.Dlc> dlcs = new ArrayList<>();
        if (cbRoyalty.isChecked())  dlcs.add(new SteamDownloadSpike.Dlc(1149640, "Royalty"));
        if (cbIdeology.isChecked()) dlcs.add(new SteamDownloadSpike.Dlc(1392840, "Ideology"));
        if (cbBiotech.isChecked())  dlcs.add(new SteamDownloadSpike.Dlc(1826140, "Biotech"));
        if (cbAnomaly.isChecked())  dlcs.add(new SteamDownloadSpike.Dlc(2380740, "Anomaly"));
        if (dlcs.isEmpty()) {
            Toast.makeText(requireContext(), "Pick at least one DLC", Toast.LENGTH_SHORT).show();
            return;
        }

        // DLC zip lands in the public /Download folder → needs All-files access on Android 11+.
        if (!StorageAccess.hasAllFilesAccess()) {
            new AlertDialog.Builder(requireActivity())
                    .setTitle("Storage access needed")
                    .setMessage("DLC are saved as zips into the public Download folder so you can see, "
                            + "share and reuse them. Please grant \"All files access\" to RimDroid, "
                            + "then tap Download again.")
                    .setPositiveButton("Grant", (d, w) -> StorageAccess.requestAllFilesAccess(requireActivity()))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        beginUi();
        new Thread(SteamDownloadSpike.forDlc(text(etUser), etPass.getText().toString(), dlcs, makeListener(null)),
                "rd-download").start();
    }

    // ---- Mods (by Workshop ID; needs login — Workshop UGC isn't downloadable anonymously) ----
    private void startMods() {
        if (downloading) return;
        if (!loginFilled()) return;
        List<Long> ids = new ArrayList<>();
        for (String tok : text(etModsIds).split("[\\s,]+")) {
            if (tok.isEmpty()) continue;
            try { ids.add(Long.parseLong(tok)); }
            catch (NumberFormatException ignored) { /* skip non-numeric */ }
        }
        if (ids.isEmpty()) {
            Toast.makeText(requireContext(), "Enter a Workshop ID (the number from the URL)", Toast.LENGTH_SHORT).show();
            return;
        }

        // Mods zip into the public /Download folder → needs All-files access on Android 11+.
        if (!StorageAccess.hasAllFilesAccess()) {
            new AlertDialog.Builder(requireActivity())
                    .setTitle("Storage access needed")
                    .setMessage("Mods are saved as zips into the public Download folder so you can see, "
                            + "share and reuse them. Please grant \"All files access\" to RimDroid, "
                            + "then tap Download again.")
                    .setPositiveButton("Grant", (d, w) -> StorageAccess.requestAllFilesAccess(requireActivity()))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        beginUi();
        new Thread(SteamDownloadSpike.forMods(text(etUser), etPass.getText().toString(), ids, makeListener(null)),
                "rd-download").start();
    }

    // ---- Mods without login: ask the ggntw.com third-party service for a download link, open it in the
    //      browser (same approach as Zomdroid). The user downloads the .zip there, then Installs it. ----
    private void openModInBrowser() {
        final String id = text(etModsBrowserId);
        if (id.isEmpty()) {
            Toast.makeText(requireContext(), "Enter a Workshop ID", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(requireContext(), "Requesting download link…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String resultUrl = null, err = null;
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.ggntw.com/steam.request").openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json, text/plain, */*");
                conn.setRequestProperty("Origin", "https://ggntw.com");
                conn.setRequestProperty("Referer", "https://ggntw.com/");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);
                String body = new org.json.JSONObject().put("url",
                        "https://steamcommunity.com/sharedfiles/filedetails/?id=" + id).toString();
                conn.getOutputStream().write(body.getBytes("UTF-8"));
                int code = conn.getResponseCode();
                java.io.InputStream is = code == 200 ? conn.getInputStream() : conn.getErrorStream();
                java.util.Scanner sc = new java.util.Scanner(is).useDelimiter("\\A");
                String resp = sc.hasNext() ? sc.next() : "";
                sc.close();
                conn.disconnect();
                if (resp.startsWith("http")) {
                    resultUrl = resp.trim();
                } else {
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(resp);
                        resultUrl = o.optString("url", o.optString("link", null));
                    } catch (Exception ignored) {}
                }
                if (resultUrl == null || resultUrl.isEmpty()) err = "service returned: " + resp;
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fUrl = resultUrl, fErr = err;
            mainH.post(() -> {
                if (!isAdded()) return;
                if (fUrl != null && !fUrl.isEmpty()) {
                    try {
                        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(fUrl)));
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), "Can't open browser: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(requireContext(), "Download link failed — " + (fErr != null ? fErr : "no link"),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "rd-ggntw").start();
    }

    // ---- shared ----
    private boolean loginFilled() {
        if (text(etUser).isEmpty()) { etUser.setError("Required"); return false; }
        if (etPass.getText().toString().isEmpty()) { etPass.setError("Required"); return false; }
        return true;
    }

    private void beginUi() {
        downloading = true;
        // Keep the process at foreground priority so backgrounding doesn't drop the CM connection.
        com.rimdroid.DownloadKeepAliveService.start(appCtx);
        btnStart.setEnabled(false);
        btnDlcStart.setEnabled(false);
        btnModsStart.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);   // until the first real percent arrives
        tvStatus.setText("");              // clear previous run's log
        appendLog("Connecting to Steam…");
    }

    // adviseInstance != null (game download) → on success, auto-set the GPU-recommended driver on it.
    private SteamDownloadSpike.Listener makeListener(final String adviseInstance) {
        return new SteamDownloadSpike.Listener() {
            @Override
            public CompletableFuture<String> requestSteamGuardCode(boolean prevWrong, String email) {
                final CompletableFuture<String> fut = new CompletableFuture<>();
                mainH.post(() -> {
                    if (!isAdded()) { fut.complete(""); return; }
                    final EditText etCode = new EditText(requireActivity());
                    etCode.setHint(email != null ? ("Code emailed to " + email) : "Steam Guard code");
                    etCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                    new AlertDialog.Builder(requireActivity())
                            .setTitle(prevWrong ? "Code incorrect — try again" : "Enter Steam Guard code")
                            .setView(etCode)
                            .setCancelable(false)
                            .setPositiveButton("OK", (d, w) -> fut.complete(etCode.getText().toString().trim()))
                            .show();
                });
                return fut;
            }

            @Override
            public void onProgress(String message) {
                mainH.post(() -> appendLog(message));
            }

            @Override
            public void onPercent(int percent) {
                mainH.post(() -> {
                    if (progress == null) return;
                    progress.setIndeterminate(false);
                    progress.setProgress(Math.max(0, Math.min(100, percent)));
                });
            }

            @Override
            public void onDone(String message) {
                mainH.post(() -> {
                    downloading = false;
                    com.rimdroid.DownloadKeepAliveService.stop(appCtx);
                    appendLog((isError(message) ? "✗ " : "✓ ") + message);
                    if (progress != null) progress.setVisibility(View.GONE);
                    if (btnStart != null) btnStart.setEnabled(true);
                    if (btnDlcStart != null) btnDlcStart.setEnabled(true);
                    if (btnModsStart != null) btnModsStart.setEnabled(true);
                    if (isAdded()) {
                        Toast.makeText(requireContext().getApplicationContext(), message, Toast.LENGTH_LONG).show();
                    }
                    // After a successful GAME download, detect the GPU and set the recommended
                    // Vulkan driver on the new instance, then tell the user (they can change it).
                    if (adviseInstance != null && !isError(message) && isAdded()) {
                        adviseDriverFor(adviseInstance);
                    }
                });
            }
        };
    }

    /** Off-thread GPU detect → set the instance's recommended driver → inform the user. */
    private void adviseDriverFor(final String instanceName) {
        new Thread(() -> {
            final com.rimdroid.GpuDriverAdvisor.Result r =
                    com.rimdroid.GpuDriverAdvisor.applyRecommendedDriver(instanceName);
            mainH.post(() -> {
                if (!isAdded() || !r.applied) return;
                new AlertDialog.Builder(requireActivity())
                        .setTitle(R.string.driver_auto_set_title)
                        .setMessage(getString(R.string.driver_auto_set, r.gpuName, r.driverLabel))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "rd-gpu-advise").start();
    }

    /** Heuristic: a terminal message is an error if it mentions failure words. */
    private static boolean isError(String m) {
        if (m == null) return true;
        String s = m.toLowerCase();
        return s.contains("error") || s.contains("fail") || s.contains("crash")
                || s.contains("lost") || s.contains("cannot");
    }

    /** Append one line to the console log and auto-scroll to the bottom. */
    private void appendLog(String line) {
        if (tvStatus == null) return;
        tvStatus.append(line + "\n");
        android.text.Layout layout = tvStatus.getLayout();
        if (layout != null) {
            int y = layout.getLineTop(tvStatus.getLineCount()) - tvStatus.getHeight();
            tvStatus.scrollTo(0, Math.max(0, y));
        }
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }
}
