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
import com.rimdroid.SteamAnonModDownloader;
import com.rimdroid.SteamDownloadSpike;
import com.rimdroid.SteamDownloadState;
import com.rimdroid.StorageAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * "Download game (Steam)" screen — front-end for the in-app Steam downloader (SteamDownloadSpike).
 * One page, three tabs sharing the Steam login + console log:
 *   • Game — downloads RimWorld 1.6 or 1.7 as a .zip into /Download/RimDroid, then offers to create
 *     an instance from it (needs All-files access).
 *   • DLC  — downloads each owned DLC as a portable .zip into /Download/RimDroid (needs All-files access).
 *   • Mods — Workshop (anonymous) — coming soon.
 * Login is sent to Steam (like DepotDownloader) and never stored; the token lives only in memory.
 */
public class DownloadFragment extends Fragment implements SteamDownloadState.View {
    /** Workshop ids to download right away, space-separated (from the Steam Workshop screen). */
    public static final String ARG_MOD_IDS = "mod_ids";

    private final Handler mainH = new Handler(Looper.getMainLooper());

    private EditText etUser, etPass, etManifest, etModsIds, etModsBrowserId;
    private Button btnStart, btnDlcStart, btnModsStart, btnModsBrowser, btnCancel, btnCreateInstance;
    private CheckBox cbRoyalty, cbIdeology, cbBiotech, cbAnomaly, cbOdyssey, cbUnstable;
    private android.widget.RadioGroup rgVersion;
    private ProgressBar progress;
    private TextView tvStatus;
    private View blockLogin, sectionGame, sectionDlc, sectionMods, sectionModsBrowser, tvLoginNote, btnInstallContent;
    private android.content.Context appCtx;   // for the keep-alive service (valid even if detached)

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        appCtx = requireContext().getApplicationContext();
        etUser     = v.findViewById(R.id.et_dl_user);
        etPass     = v.findViewById(R.id.et_dl_pass);
        etManifest = v.findViewById(R.id.et_dl_manifest);
        cbUnstable = v.findViewById(R.id.cb_dl_unstable);
        rgVersion  = v.findViewById(R.id.rg_dl_version);
        btnStart   = v.findViewById(R.id.btn_dl_start);
        btnCreateInstance = v.findViewById(R.id.btn_dl_create_instance);
        btnDlcStart = v.findViewById(R.id.btn_dlc_start);
        cbRoyalty  = v.findViewById(R.id.cb_dlc_royalty);
        cbIdeology = v.findViewById(R.id.cb_dlc_ideology);
        cbBiotech  = v.findViewById(R.id.cb_dlc_biotech);
        cbAnomaly  = v.findViewById(R.id.cb_dlc_anomaly);
        cbOdyssey  = v.findViewById(R.id.cb_dlc_odyssey);
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
        sectionModsBrowser = v.findViewById(R.id.section_mods_browser);
        tvLoginNote = v.findViewById(R.id.tv_login_note);

        btnInstallContent = v.findViewById(R.id.btn_install_content);
        btnCancel = v.findViewById(R.id.btn_dl_cancel);
        tvStatus.setMovementMethod(new ScrollingMovementMethod());   // make the log area scrollable
        btnStart.setOnClickListener(view -> startGame());
        btnCreateInstance.setOnClickListener(view -> createInstance());
        btnDlcStart.setOnClickListener(view -> startDlc());
        btnModsStart.setOnClickListener(view -> startMods());
        btnModsBrowser.setOnClickListener(view -> openModInBrowser());
        btnCancel.setOnClickListener(view -> confirmCancel());
        btnInstallContent.setOnClickListener(view ->
                androidx.navigation.Navigation.findNavController(view).navigate(R.id.action_open_install));

        // Game / DLC / Mods selector → swap the visible section. Login block shown for Game + DLC.
        // The "install downloaded content" button is for DLC/Mods only (Game has its own "Create an
        // instance" button for the zip it downloaded).
        com.google.android.material.button.MaterialButtonToggleGroup toggle =
                v.findViewById(R.id.toggle_dl_type);
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean game = checkedId == R.id.btn_type_game;
            boolean mods = checkedId == R.id.btn_type_mods;
            sectionGame.setVisibility(game ? View.VISIBLE : View.GONE);
            sectionDlc.setVisibility(checkedId == R.id.btn_type_dlc ? View.VISIBLE : View.GONE);
            sectionMods.setVisibility(mods ? View.VISIBLE : View.GONE);
            sectionModsBrowser.setVisibility(mods ? View.VISIBLE : View.GONE);
            // Login (and its privacy preamble) is needed for game + DLC; mods download anonymously.
            blockLogin.setVisibility(mods ? View.GONE : View.VISIBLE);
            tvLoginNote.setVisibility(mods ? View.GONE : View.VISIBLE);
            btnInstallContent.setVisibility(game ? View.GONE : View.VISIBLE);
        });
        toggle.check(R.id.btn_type_game);   // default to Game
        String queued = getArguments() == null ? null : getArguments().getString(ARG_MOD_IDS);
        if (queued != null && !queued.trim().isEmpty()) {
            toggle.check(R.id.btn_type_mods);
            etModsIds.setText(queued);
            getArguments().remove(ARG_MOD_IDS);   // once: coming back here must not start it again
            if (!SteamDownloadState.get().isDownloading()) v.post(this::startMods);
        }

        // 1.7 only in APKs that carry its .NET engine; its note says where the build comes from.
        View rb17 = v.findViewById(R.id.rb_dl_17), note17 = v.findViewById(R.id.tv_dl_17_note);
        if (!com.rimdroid.game.DotnetEngine.isAvailable(appCtx)) rb17.setVisibility(View.GONE);
        rgVersion.setOnCheckedChangeListener((group, checkedId) ->
                note17.setVisibility(checkedId == R.id.rb_dl_17 ? View.VISIBLE : View.GONE));
        // The branch checkbox belongs to a manifest number: shown once one is typed.
        etManifest.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                cbUnstable.setVisibility(s.toString().trim().isEmpty() ? View.GONE : View.VISIBLE);
            }
        });

        // Re-attach to a download already running in the background (the worker thread outlives this
        // fragment via SteamDownloadState), restoring the log + progress + "busy" state on return.
        SteamDownloadState st = SteamDownloadState.get();
        st.setView(this);
        tvStatus.setText(st.getLog());
        scrollLogToBottom();
        boolean busy = st.isDownloading();
        setControlsEnabled(!busy);
        btnCancel.setVisibility(busy ? View.VISIBLE : View.GONE);
        showCreateInstance(!busy);
        if (busy) {
            progress.setVisibility(View.VISIBLE);
            if (st.isIndeterminate() || st.getPercent() < 0) {
                progress.setIndeterminate(true);
            } else {
                progress.setIndeterminate(false);
                progress.setProgress(st.getPercent());
            }
        } else {
            progress.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        SteamDownloadState.get().clearView(this);
        super.onDestroyView();
    }

    // ---- Game ----
    private void startGame() {
        if (SteamDownloadState.get().isDownloading()) return;
        if (!loginFilled()) return;

        // The game zip lands in the public /Download folder → needs All-files access on Android 11+.
        if (!StorageAccess.hasAllFilesAccess()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                    .setTitle("Storage access needed")
                    .setMessage("The game is saved as a zip into the public Download folder, and instances "
                            + "are created from it. Please grant \"All files access\" to RimDroid, "
                            + "then tap Download again.")
                    .setPositiveButton("Grant", (d, w) -> StorageAccess.requestAllFilesAccess(requireActivity()))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        long manifestId = 0L;   // 0 = the chosen version's default build
        try {
            String mt = text(etManifest);
            if (!mt.isEmpty()) manifestId = Long.parseUnsignedLong(mt);
        } catch (NumberFormatException ignored) { /* blank/invalid → default */ }

        SteamDownloadState st = SteamDownloadState.get();
        SteamDownloadSpike dl = new SteamDownloadSpike(text(etUser), etPass.getText().toString(),
                manifestId, cbUnstable.isChecked(), selectedVersion(), st);
        st.begin(appCtx, null);          // the instance (and its driver) comes later, from the zip
        st.setActive(dl);
        beginUi();
        new Thread(dl, "rd-download").start();
    }

    // ---- DLC ----
    private void startDlc() {
        if (SteamDownloadState.get().isDownloading()) return;
        if (!loginFilled()) return;

        List<SteamDownloadSpike.Dlc> dlcs = new ArrayList<>();
        if (cbRoyalty.isChecked())  dlcs.add(new SteamDownloadSpike.Dlc(1149640, "Royalty"));
        if (cbIdeology.isChecked()) dlcs.add(new SteamDownloadSpike.Dlc(1392840, "Ideology"));
        if (cbBiotech.isChecked())  dlcs.add(new SteamDownloadSpike.Dlc(1826140, "Biotech"));
        if (cbAnomaly.isChecked())  dlcs.add(new SteamDownloadSpike.Dlc(2380740, "Anomaly"));
        if (cbOdyssey.isChecked())  dlcs.add(new SteamDownloadSpike.Dlc(3022790, "Odyssey"));
        if (dlcs.isEmpty()) {
            Toast.makeText(requireContext(), "Pick at least one DLC", Toast.LENGTH_SHORT).show();
            return;
        }

        // DLC zip lands in the public /Download folder → needs All-files access on Android 11+.
        if (!StorageAccess.hasAllFilesAccess()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                    .setTitle("Storage access needed")
                    .setMessage("DLC are saved as zips into the public Download folder so you can see, "
                            + "share and reuse them. Please grant \"All files access\" to RimDroid, "
                            + "then tap Download again.")
                    .setPositiveButton("Grant", (d, w) -> StorageAccess.requestAllFilesAccess(requireActivity()))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        SteamDownloadState st = SteamDownloadState.get();
        SteamDownloadSpike dl = SteamDownloadSpike.forDlc(text(etUser), etPass.getText().toString(), dlcs,
                selectedVersion(), st);
        st.begin(appCtx, null);
        st.setActive(dl);
        beginUi();
        new Thread(dl, "rd-download").start();
    }

    // ---- Mods (by Workshop ID) — ANONYMOUS, no login. We resolve each item's owning game
    //      (consumer_app_id) ourselves, so this works for ANY public Workshop item, not just
    //      RimWorld. Items that require ownership fall back to the browser/ggntw option below. ----
    private void startMods() {
        if (SteamDownloadState.get().isDownloading()) return;
        // Numbers, Workshop links (…?id=NUMBER) of mods or of collections: a collection is unfolded
        // into its mods by the downloader (SteamAnonModDownloader.expandCollections).
        List<Long> ids = SteamAnonModDownloader.parseWorkshopIds(text(etModsIds));
        if (ids.isEmpty()) {
            Toast.makeText(requireContext(), "Enter a Workshop ID or link (mod or collection)", Toast.LENGTH_SHORT).show();
            return;
        }

        // Mods zip into the public /Download folder → needs All-files access on Android 11+.
        if (!StorageAccess.hasAllFilesAccess()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                    .setTitle("Storage access needed")
                    .setMessage("Mods are saved as zips into the public Download folder so you can see, "
                            + "share and reuse them. Please grant \"All files access\" to RimDroid, "
                            + "then tap Download again.")
                    .setPositiveButton("Grant", (d, w) -> StorageAccess.requestAllFilesAccess(requireActivity()))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        SteamDownloadState st = SteamDownloadState.get();
        SteamAnonModDownloader dl = new SteamAnonModDownloader(ids, st);
        st.begin(appCtx, null);
        st.setActive(dl);
        beginUi();
        new Thread(dl, "rd-anon-mod").start();
    }

    private void confirmCancel() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                .setMessage(R.string.download_cancel_confirm)
                .setPositiveButton(R.string.download_cancel, (d, w) -> SteamDownloadState.get().cancel())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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

    // ---- Create an instance from the downloaded game zip (same screen the GOG downloader uses) ----
    private void createInstance() {
        java.io.File zip = SteamDownloadState.get().getGameZip();
        if (zip == null || !zip.isFile()) { showCreateInstance(false); return; }
        Bundle args = new Bundle();
        args.putString(NewInstanceFragment.ARG_PRESELECTED_FILE, zip.getAbsolutePath());
        androidx.navigation.Navigation.findNavController(requireView()).navigate(R.id.action_install_instance, args);
    }

    /** The button shows while a downloaded game zip is there to create an instance from. */
    private void showCreateInstance(boolean allowed) {
        if (btnCreateInstance == null) return;
        java.io.File zip = SteamDownloadState.get().getGameZip();
        btnCreateInstance.setVisibility(allowed && zip != null && zip.isFile() ? View.VISIBLE : View.GONE);
    }

    // ---- shared ----
    private SteamDownloadSpike.Version selectedVersion() {
        return (rgVersion != null && rgVersion.getCheckedRadioButtonId() == R.id.rb_dl_17)
                ? SteamDownloadSpike.Version.V1_7 : SteamDownloadSpike.Version.V1_6;
    }

    private boolean loginFilled() {
        if (text(etUser).isEmpty()) { etUser.setError("Required"); return false; }
        if (etPass.getText().toString().isEmpty()) { etPass.setError("Required"); return false; }
        return true;
    }

    private void beginUi() {
        setControlsEnabled(false);
        btnCancel.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);   // until the first real percent arrives
        if (btnCreateInstance != null) btnCreateInstance.setVisibility(View.GONE);
        tvStatus.setText("");              // SteamDownloadState already cleared its log buffer in begin()
    }

    /** Enable/disable the start controls (everything except Cancel) while a download runs. */
    private void setControlsEnabled(boolean enabled) {
        if (btnStart != null) btnStart.setEnabled(enabled);
        if (btnDlcStart != null) btnDlcStart.setEnabled(enabled);
        if (btnModsStart != null) btnModsStart.setEnabled(enabled);
        if (btnModsBrowser != null) btnModsBrowser.setEnabled(enabled);
        if (etUser != null) etUser.setEnabled(enabled);
        if (etPass != null) etPass.setEnabled(enabled);
        if (etManifest != null) etManifest.setEnabled(enabled);
        if (cbUnstable != null) cbUnstable.setEnabled(enabled);
        if (etModsIds != null) etModsIds.setEnabled(enabled);
    }

    // ---- SteamDownloadState.View (callbacks already posted to the main thread by the state) ----
    @Override
    public void onLog(CharSequence fullLog) {
        if (tvStatus == null) return;
        tvStatus.setText(fullLog);
        scrollLogToBottom();
    }

    @Override
    public void onPercent(int percent, boolean indeterminate) {
        if (progress == null) return;
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(indeterminate);
        if (!indeterminate) progress.setProgress(Math.max(0, Math.min(100, percent)));
    }

    @Override
    public void onFinished(String message) {
        setControlsEnabled(true);
        if (progress != null) progress.setVisibility(View.GONE);
        if (btnCancel != null) btnCancel.setVisibility(View.GONE);
        showCreateInstance(true);
        if (isAdded()) {
            Toast.makeText(requireContext().getApplicationContext(), message, Toast.LENGTH_LONG).show();
        }
        // The worker already applied the recommendation, even if this fragment was detached when
        // the download finished. Here we only show its one-time explanation.
        com.rimdroid.GpuDriverAdvisor.Result driver = SteamDownloadState.get().takeDriverResult();
        if (driver != null && driver.announce && isAdded()) showDriverResult(driver);
    }

    @Override
    public CompletableFuture<String> requestSteamGuardCode(boolean prevWrong, String email) {
        final CompletableFuture<String> fut = new CompletableFuture<>();
        mainH.post(() -> {
            if (!isAdded()) { fut.complete(""); return; }
            final EditText etCode = new EditText(requireActivity());
            etCode.setHint(email != null ? ("Code emailed to " + email) : "Steam Guard code");
            etCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(prevWrong ? "Code incorrect — try again" : "Enter Steam Guard code")
                    .setView(etCode)
                    .setCancelable(false)
                    .setPositiveButton("OK", (d, w) -> fut.complete(etCode.getText().toString().trim()))
                    .show();
        });
        return fut;
    }

    /** Off-thread GPU detect → set the instance's recommended driver → inform the user. */
    private void showDriverResult(com.rimdroid.GpuDriverAdvisor.Result r) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.driver_auto_set_title)
                .setMessage(getString(R.string.driver_auto_set, r.gpuName, r.driverLabel))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void scrollLogToBottom() {
        if (tvStatus == null) return;
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
