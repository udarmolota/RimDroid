package com.rimdroid.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Steam Cloud saves — move saves between an instance on the phone and the copy Steam keeps in sync
 * with a PC. Sits beside "Steam Downloads" in the drawer and shares its session model: credentials
 * plus a Steam-Mobile approval, token in memory only, never stored.
 *
 * Deliberately NO per-file picking. Getting saves fetches everything in one connection into a scratch
 * folder and then hangs up; only afterwards — offline, unhurried — are the files moved into Saves/,
 * asking only where a name already exists. Sending mirrors that: new names go up silently, existing
 * ones are asked about once. Choosing files up front would mean holding the Steam session open while
 * a human deliberates (the connection dies if the app is backgrounded) and a second sign-in approval.
 */
public class CloudSavesFragment extends Fragment {

    /** RimWorld's Steam app id — the only game this launcher manages. */
    private static final int RIMWORLD_APP_ID = 294100;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextInputEditText etUser, etPass;
    private Spinner spInstance;
    private Button btnGo;
    private TextView goNote, log;
    private ProgressBar progress;

    private List<GameInstance> instances;
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
        btnGo = view.findViewById(R.id.btn_cloud_go);
        goNote = view.findViewById(R.id.tv_cloud_go_note);
        progress = view.findViewById(R.id.pb_cloud);
        log = view.findViewById(R.id.tv_cloud_log);
        log.setMovementMethod(new ScrollingMovementMethod());

        // Position 0 is a PROMPT, never a real instance: a pre-selected default lets the user move
        // saves in or out of the wrong game without ever touching the spinner.
        instances = GameInstanceManager.requireSingleton().getInstances();
        List<String> names = new ArrayList<>();
        names.add(getString(instances.isEmpty()
                ? R.string.cloud_saves_no_instances : R.string.choose_instance_prompt));
        for (GameInstance gi : instances) names.add(gi.getName());
        spInstance.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, names));

        MaterialButtonToggleGroup toggle = view.findViewById(R.id.toggle_cloud_dir);
        toggle.check(R.id.btn_dir_pull);
        toggle.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            pullDirection = (checkedId == R.id.btn_dir_pull);
            btnGo.setText(pullDirection ? R.string.cloud_saves_pull : R.string.cloud_saves_push);
            goNote.setText(pullDirection ? R.string.cloud_saves_pull_note : R.string.cloud_saves_push_note);
        });
        btnGo.setEnabled(true);
        btnGo.setOnClickListener(v -> onGo());
    }

    private GameInstance chosenInstance() {
        int pos = spInstance.getSelectedItemPosition();
        return (pos >= 1 && pos <= instances.size()) ? instances.get(pos - 1) : null;
    }

    private File savesDirOf(GameInstance gi) {
        return new File(AppStorage.requireSingleton().getInstanceDir(gi.getName()),
                "unity3d/Ludeon Studios/RimWorld by Ludeon Studios/Saves");
    }

    /** Scratch folder the cloud copy lands in before anything touches the real saves. */
    private File pullTempDir(GameInstance gi) {
        return new File(AppStorage.requireSingleton().getCachePath(), "cloud_pull/" + gi.getName());
    }

    private void onGo() {
        GameInstance gi = chosenInstance();
        if (gi == null) {
            Toast.makeText(requireContext(), R.string.choose_instance_first, Toast.LENGTH_SHORT).show();
            return;
        }
        String u = text(etUser), p = text(etPass);
        if (u.isEmpty() || p.isEmpty()) {
            Toast.makeText(requireContext(), R.string.cloud_saves_need_login, Toast.LENGTH_SHORT).show();
            return;
        }
        busy(true);
        log.setText("");
        if (pullDirection) {
            final File temp = pullTempDir(gi);
            appendLog(getString(R.string.cloud_saves_log_connecting));
            new Thread(SteamCloudSpike.forPull(u, p, RIMWORLD_APP_ID, temp, savesDirOf(gi), new Callbacks() {
                @Override public void onDone(String message) {
                    ui.post(() -> {
                        appendLog("— " + message);
                        // Connection is closed by now: place the files at leisure.
                        placeAll(temp, savesDirOf(gi));
                    });
                }
            }), "CloudSavesPull").start();
        } else {
            appendLog(getString(R.string.cloud_saves_log_connecting));
            new Thread(SteamCloudSpike.forPush(u, p, RIMWORLD_APP_ID, gi.getName(), new Callbacks()),
                    "CloudSavesPush").start();
        }
    }

    // ===== placement: runs offline, after the Steam session is gone =====

    /** Move every downloaded file into Saves/, asking only where the name already exists. */
    private void placeAll(File temp, File savesDir) {
        List<File> pending = new ArrayList<>();
        File[] fs = temp.listFiles();
        if (fs != null) for (File f : fs) pending.add(f);
        if (pending.isEmpty()) { busy(false); return; }
        if (!savesDir.isDirectory() && !savesDir.mkdirs()) {
            appendLog("Cannot create " + savesDir);
            busy(false);
            return;
        }
        placeNext(pending, 0, savesDir, new int[]{0, 0});   // {copied, kept}
    }

    /** One file at a time, because a clash needs an answer before the next one is touched. */
    private void placeNext(List<File> pending, int i, File savesDir, int[] tally) {
        if (!isAdded()) return;
        if (i >= pending.size()) {
            appendLog(getString(R.string.cloud_saves_place_done, tally[0], tally[1]));
            busy(false);
            return;
        }
        File src = pending.get(i);
        File dest = new File(savesDir, src.getName());
        if (!dest.exists()) {
            copyInto(src, dest, tally);
            placeNext(pending, i + 1, savesDir, tally);
            return;
        }
        // Same name on both sides — show both dates and let the user decide, like Steam does.
        boolean cloudNewer = src.lastModified() > dest.lastModified();
        String msg = getString(R.string.cloud_saves_clash_msg,
                fmtDate(dest.lastModified()), fmtDate(src.lastModified()),
                getString(cloudNewer ? R.string.cloud_saves_clash_cloud_newer
                                     : R.string.cloud_saves_clash_local_newer));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.cloud_saves_clash_title, src.getName()))
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(R.string.cloud_saves_clash_replace, (d, w) -> {
                    copyInto(src, dest, tally);
                    placeNext(pending, i + 1, savesDir, tally);
                })
                .setNegativeButton(R.string.cloud_saves_clash_keep, (d, w) -> {
                    appendLog(getString(R.string.cloud_saves_log_kept, src.getName()));
                    tally[1]++;
                    placeNext(pending, i + 1, savesDir, tally);
                })
                .setNeutralButton(R.string.cloud_saves_clash_both, (d, w) -> {
                    copyInto(src, uniqueName(savesDir, src.getName()), tally);
                    placeNext(pending, i + 1, savesDir, tally);
                })
                .show();
    }

    /** "Colony.rws" -> "Colony (from cloud).rws", and "… 2" etc. if that is taken too. */
    private File uniqueName(File dir, String filename) {
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String ext = dot > 0 ? filename.substring(dot) : "";
        String suffix = getString(R.string.cloud_saves_from_cloud_suffix);
        File f = new File(dir, base + " " + suffix + ext);
        for (int n = 2; f.exists(); n++) f = new File(dir, base + " " + suffix + " " + n + ext);
        return f;
    }

    private void copyInto(File src, File dest, int[] tally) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            dest.setLastModified(src.lastModified());   // keep the cloud's date, for later comparisons
            tally[0]++;
            appendLog(getString(R.string.cloud_saves_log_placed, dest.getName()));
            src.delete();
        } catch (Throwable t) {
            appendLog("FAILED " + dest.getName() + ": " + t);
        }
    }

    // ===== shared Steam plumbing =====

    private class Callbacks implements SteamCloudSpike.CloudListener {
        @Override public void onFileList(List<SteamCloudSpike.CloudFile> files) { /* unused */ }

        /** Sending would overwrite these on the PC — ask once, naming them with both dates. */
        @Override
        public CompletableFuture<Integer> resolvePushConflicts(List<SteamCloudSpike.CloudFile> clashes) {
            final CompletableFuture<Integer> fut = new CompletableFuture<>();
            ui.post(() -> {
                if (!isAdded()) { fut.complete(SteamCloudSpike.PUSH_CANCEL); return; }
                GameInstance gi = chosenInstance();
                File savesDir = gi == null ? null : savesDirOf(gi);
                StringBuilder sb = new StringBuilder();
                for (SteamCloudSpike.CloudFile cf : clashes) {
                    File local = savesDir == null ? null : new File(savesDir, cf.filename);
                    sb.append("\n• ").append(cf.filename)
                      .append("\n   ").append(getString(R.string.cloud_saves_clash_in_cloud,
                              fmtDate(cf.timestampMs)));
                    if (local != null && local.isFile())
                        sb.append("\n   ").append(getString(R.string.cloud_saves_clash_on_phone,
                                fmtDate(local.lastModified())));
                }
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.cloud_saves_push_confirm_title)
                        .setMessage(getString(R.string.cloud_saves_push_confirm_replace, sb.toString()))
                        .setCancelable(false)
                        .setPositiveButton(R.string.cloud_saves_push_replace,
                                (d, w) -> fut.complete(SteamCloudSpike.PUSH_REPLACE))
                        .setNeutralButton(R.string.cloud_saves_push_only_new,
                                (d, w) -> fut.complete(SteamCloudSpike.PUSH_ONLY_NEW))
                        .setNegativeButton(android.R.string.cancel,
                                (d, w) -> fut.complete(SteamCloudSpike.PUSH_CANCEL))
                        .show();
            });
            return fut;
        }

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
            ui.post(() -> { appendLog("— " + message); busy(false); });
        }
    }

    private void busy(boolean b) {
        if (!isAdded()) return;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        btnGo.setEnabled(!b);
        // Hold the process at foreground-service priority for the whole operation. Android freezes a
        // backgrounded app and kills its Steam connection mid-transfer — the failure the game
        // downloader already hit — and signing in deliberately sends the user to Steam Mobile.
        android.content.Context appCtx = requireContext().getApplicationContext();
        if (b) com.rimdroid.DownloadKeepAliveService.start(appCtx, getString(R.string.cloud_saves_keepalive));
        else com.rimdroid.DownloadKeepAliveService.stop(appCtx);
    }

    private String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
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
