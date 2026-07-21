package com.rimdroid.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.rimdroid.AppStorage;
import com.rimdroid.R;
import com.rimdroid.SteamCloudSpike;
import com.rimdroid.SteamDownloadSpike;
import com.rimdroid.game.GameInstance;
import com.rimdroid.game.GameInstanceManager;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Steam Cloud saves — move saves between an instance on the phone and the Steam Cloud copy that a
 * PC keeps in sync. Sits beside "Steam Downloads" in the drawer and shares its session model:
 * credentials + a Steam-Mobile approval, token in memory only, never stored.
 *
 * Two directions, picked with a toggle (mirroring the download screen's Game/DLC/Mods):
 *   • FROM CLOUD — needs a sign-in before anything can be listed, so the list appears after
 *     "show cloud saves". Already-present files are skipped, never overwritten.
 *   • TO CLOUD — the local list needs no sign-in, so it fills in as soon as an instance is chosen.
 *     (Upload itself is not implemented yet; it replaces the PC's copy of the same name, so it
 *     needs its own confirmation flow.)
 *
 * Whichever side is listed, rows show what the OTHER side has: if a copy of the same name is newer
 * there, the row says so — that's the raw material for the eventual Steam-style conflict prompt.
 */
public class CloudSavesFragment extends Fragment {

    /** RimWorld's Steam app id — the only game this launcher manages. */
    private static final int RIMWORLD_APP_ID = 294100;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextInputEditText etUser, etPass;
    private Spinner spInstance;
    private MaterialButtonToggleGroup toggleDir;
    private Button btnRefresh, btnGo;
    private TextView listNote, goNote, log;
    private LinearLayout listBox;
    private ProgressBar progress;

    private List<GameInstance> instances;
    /** Cloud listing from the last sign-in (null = not fetched yet). */
    private List<SteamCloudSpike.CloudFile> cloudFiles;
    /** filename → checkbox, for whichever side is currently listed. */
    private final Map<String, CheckBox> rows = new HashMap<>();

    private boolean pullDirection = true;   // true = from cloud, false = to cloud

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_cloud_saves, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etUser = view.findViewById(R.id.et_cloud_user);
        etPass = view.findViewById(R.id.et_cloud_pass);
        spInstance = view.findViewById(R.id.sp_cloud_instance);
        toggleDir = view.findViewById(R.id.toggle_cloud_dir);
        btnRefresh = view.findViewById(R.id.btn_cloud_refresh);
        btnGo = view.findViewById(R.id.btn_cloud_go);
        listNote = view.findViewById(R.id.tv_cloud_list_note);
        goNote = view.findViewById(R.id.tv_cloud_go_note);
        listBox = view.findViewById(R.id.box_cloud_list);
        progress = view.findViewById(R.id.pb_cloud);
        log = view.findViewById(R.id.tv_cloud_log);
        log.setMovementMethod(new ScrollingMovementMethod());

        // Instance list. Position 0 is a PROMPT, never a real instance: a pre-selected default lets
        // the user pull saves into the wrong instance without ever touching the spinner.
        instances = GameInstanceManager.requireSingleton().getInstances();
        List<String> names = new ArrayList<>();
        names.add(getString(instances.isEmpty()
                ? R.string.cloud_saves_no_instances : R.string.choose_instance_prompt));
        for (GameInstance gi : instances) names.add(gi.getName());
        spInstance.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, names));
        spInstance.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { refreshList(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });

        toggleDir.check(R.id.btn_dir_pull);
        toggleDir.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            pullDirection = (checkedId == R.id.btn_dir_pull);
            applyDirection();
        });
        applyDirection();

        btnRefresh.setOnClickListener(v -> fetchCloudList());
        btnGo.setOnClickListener(v -> onGo());
    }

    /** Swap the labels/visibility for the chosen direction and re-list that side. */
    private void applyDirection() {
        btnRefresh.setVisibility(pullDirection ? View.VISIBLE : View.GONE);
        btnGo.setText(pullDirection ? R.string.cloud_saves_pull : R.string.cloud_saves_push);
        goNote.setText(pullDirection ? R.string.cloud_saves_pull_note : R.string.cloud_saves_push_note);
        refreshList();
    }

    private GameInstance chosenInstance() {
        int pos = spInstance.getSelectedItemPosition();
        return (pos >= 1 && pos <= instances.size()) ? instances.get(pos - 1) : null;
    }

    private File savesDirOf(GameInstance gi) {
        return new File(AppStorage.requireSingleton().getInstanceDir(gi.getName()),
                "unity3d/Ludeon Studios/RimWorld by Ludeon Studios/Saves");
    }

    /** Local .rws files of the chosen instance (empty list if none / no instance). */
    private List<File> localSaves() {
        GameInstance gi = chosenInstance();
        List<File> out = new ArrayList<>();
        if (gi == null) return out;
        File[] fs = savesDirOf(gi).listFiles((d, n) -> n.endsWith(".rws"));
        if (fs != null) for (File f : fs) out.add(f);
        return out;
    }

    /** Rebuild the checkbox list for the current direction. */
    private void refreshList() {
        if (!isAdded()) return;
        listBox.removeAllViews();
        rows.clear();
        btnGo.setEnabled(false);

        GameInstance gi = chosenInstance();
        if (gi == null) {
            listNote.setVisibility(View.VISIBLE);
            listNote.setText(pullDirection ? R.string.cloud_saves_list_empty_cloud
                                           : R.string.cloud_saves_list_empty_local);
            return;
        }

        if (pullDirection) {
            if (cloudFiles == null) {   // not signed in yet
                listNote.setVisibility(View.VISIBLE);
                listNote.setText(R.string.cloud_saves_list_empty_cloud);
                return;
            }
            if (cloudFiles.isEmpty()) {
                listNote.setVisibility(View.VISIBLE);
                listNote.setText(R.string.cloud_saves_list_none_cloud);
                return;
            }
            listNote.setVisibility(View.GONE);
            File dir = savesDirOf(gi);
            for (SteamCloudSpike.CloudFile cf : cloudFiles) {
                File local = new File(dir, cf.filename);
                String label;
                if (local.isFile() && local.lastModified() > cf.timestampMs) {
                    // The phone's copy is newer — taking the cloud one would be a step back.
                    label = getString(R.string.cloud_saves_row_newer_local, cf.filename,
                            fmtDate(cf.timestampMs), fmtSize(cf.rawSize), fmtDate(local.lastModified()));
                } else {
                    label = getString(R.string.cloud_saves_row, cf.filename,
                            fmtDate(cf.timestampMs), fmtSize(cf.rawSize));
                }
                addRow(cf.filename, label);
            }
        } else {
            List<File> local = localSaves();
            if (local.isEmpty()) {
                listNote.setVisibility(View.VISIBLE);
                listNote.setText(R.string.cloud_saves_list_none_local);
                return;
            }
            listNote.setVisibility(View.GONE);
            for (File f : local) {
                SteamCloudSpike.CloudFile cf = cloudFileNamed(f.getName());
                String label;
                if (cf != null && cf.timestampMs > f.lastModified()) {
                    // Sending would replace a cloud copy that is NEWER than this one.
                    label = getString(R.string.cloud_saves_row_newer_cloud, f.getName(),
                            fmtDate(f.lastModified()), fmtSize(f.length()), fmtDate(cf.timestampMs));
                } else {
                    label = getString(R.string.cloud_saves_row, f.getName(),
                            fmtDate(f.lastModified()), fmtSize(f.length()));
                }
                addRow(f.getName(), label);
            }
        }
    }

    @Nullable
    private SteamCloudSpike.CloudFile cloudFileNamed(String name) {
        if (cloudFiles == null) return null;
        for (SteamCloudSpike.CloudFile cf : cloudFiles) if (cf.filename.equals(name)) return cf;
        return null;
    }

    private void addRow(String filename, String label) {
        CheckBox cb = new CheckBox(requireContext());
        cb.setText(label);
        cb.setTextSize(13f);
        cb.setOnCheckedChangeListener((b, c) -> btnGo.setEnabled(anyChecked()));
        listBox.addView(cb);
        rows.put(filename, cb);
    }

    private boolean anyChecked() {
        for (CheckBox cb : rows.values()) if (cb.isChecked()) return true;
        return false;
    }

    private Set<String> checkedNames() {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, CheckBox> e : rows.entrySet())
            if (e.getValue().isChecked()) out.add(e.getKey());
        return out;
    }

    /** Sign in and list what the cloud holds (downloads nothing). */
    private void fetchCloudList() {
        String u = text(etUser), p = text(etPass);
        if (u.isEmpty() || p.isEmpty()) {
            Toast.makeText(requireContext(), R.string.cloud_saves_need_login, Toast.LENGTH_SHORT).show();
            return;
        }
        busy(true);
        log.setText("");
        appendLog("Connecting to Steam…");
        new Thread(SteamCloudSpike.forList(u, p, RIMWORLD_APP_ID, new CloudCallbacks() {
            @Override public void onFileList(List<SteamCloudSpike.CloudFile> files) {
                ui.post(() -> { cloudFiles = files; refreshList(); });
            }
        }), "CloudSavesList").start();
    }

    private void onGo() {
        GameInstance gi = chosenInstance();
        if (gi == null) {
            Toast.makeText(requireContext(), R.string.choose_instance_first, Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> picked = checkedNames();
        if (picked.isEmpty()) {
            Toast.makeText(requireContext(), R.string.cloud_saves_select_none, Toast.LENGTH_SHORT).show();
            return;
        }
        String u = text(etUser), p = text(etPass);
        if (u.isEmpty() || p.isEmpty()) {
            Toast.makeText(requireContext(), R.string.cloud_saves_need_login, Toast.LENGTH_SHORT).show();
            return;
        }
        if (pullDirection) {
            busy(true);
            appendLog("Getting " + picked.size() + " save(s)…");
            new Thread(SteamCloudSpike.forPull(u, p, RIMWORLD_APP_ID, gi.getName(), picked,
                    new CloudCallbacks()), "CloudSavesPull").start();
            return;
        }
        // Sending REPLACES the cloud copy — i.e. what the PC picks up next. Always confirm, and name
        // the files that already exist up there so an accidental overwrite can't happen silently.
        List<String> willReplace = new ArrayList<>();
        for (String n : picked) if (cloudFileNamed(n) != null) willReplace.add(n);
        String msg = willReplace.isEmpty()
                ? getString(R.string.cloud_saves_push_confirm_new, picked.size())
                : getString(R.string.cloud_saves_push_confirm_replace,
                        android.text.TextUtils.join("\n• ", willReplace));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cloud_saves_push_confirm_title)
                .setMessage(msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.cloud_saves_push, (d, w) -> {
                    busy(true);
                    appendLog("Sending " + picked.size() + " save(s)…");
                    new Thread(SteamCloudSpike.forPush(u, p, RIMWORLD_APP_ID, gi.getName(), picked,
                            new CloudCallbacks()), "CloudSavesPush").start();
                })
                .show();
    }

    /** Shared progress/done/Steam-Guard plumbing for both spike modes. */
    private class CloudCallbacks implements SteamCloudSpike.CloudListener {
        @Override public void onFileList(List<SteamCloudSpike.CloudFile> files) { /* only the list mode */ }

        @Override
        public CompletableFuture<String> requestSteamGuardCode(boolean prevWrong, String email) {
            final CompletableFuture<String> fut = new CompletableFuture<>();
            ui.post(() -> {
                if (!isAdded()) { fut.complete(""); return; }
                final android.widget.EditText et = new android.widget.EditText(requireContext());
                et.setHint(email != null ? getString(R.string.cloud_saves_code_email, email)
                                         : getString(R.string.cloud_saves_code));
                et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(prevWrong ? R.string.cloud_saves_code_wrong : R.string.cloud_saves_code_title)
                        .setView(et)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> fut.complete(et.getText().toString().trim()))
                        .show();
            });
            return fut;
        }

        @Override public void onProgress(String message) { ui.post(() -> appendLog(message)); }

        @Override public void onDone(String message) {
            ui.post(() -> {
                appendLog("— " + message);
                busy(false);
                if (pullDirection) refreshList();   // pulled files now exist locally
            });
        }
    }

    private void busy(boolean b) {
        if (!isAdded()) return;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        btnRefresh.setEnabled(!b);
        btnGo.setEnabled(!b && anyChecked());
        // Hold the process at foreground-service priority for the whole operation. Without this
        // Android freezes a backgrounded app and kills its Steam connection mid-transfer — the exact
        // failure the game downloader already hit, which is why this service exists. It matters here
        // in particular because signing in SENDS THE USER AWAY to the Steam Mobile app to approve.
        android.content.Context appCtx = requireContext().getApplicationContext();
        if (b) com.rimdroid.DownloadKeepAliveService.start(appCtx,
                getString(R.string.cloud_saves_keepalive));
        else com.rimdroid.DownloadKeepAliveService.stop(appCtx);
    }

    private String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private static String fmtSize(long bytes) {
        return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)) + " MB" : Math.max(1, bytes / 1024) + " KB";
    }

    private String fmtDate(long ms) {
        if (ms <= 0) return "?";
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(ms));
    }

    /** Append a line and keep the console scrolled to the bottom (so it never looks frozen). */
    private void appendLog(String line) {
        if (!isAdded() || log == null) return;
        log.append(line + "\n");
        final int scroll = log.getLayout() == null ? 0
                : log.getLayout().getLineTop(log.getLineCount()) - log.getHeight();
        if (scroll > 0) log.scrollTo(0, scroll);
    }
}
