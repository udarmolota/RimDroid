package com.rimdroid.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rimdroid.InstallerService;
import com.rimdroid.InstanceShortcuts;
import com.rimdroid.InstanceUpdater;
import com.rimdroid.R;
import com.rimdroid.SteamDownloadState;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.Date;

/**
 * Update the game of a RimWorld 1.7 instance from a newer build's zip, keeping a backup of the
 * previous build and the saves, and go back to that backup (InstanceUpdater, 2026-11). Opened from
 * the instance card's menu. The game zip the Steam download just made is offered first.
 */
public class UpdateInstanceFragment extends Fragment {
    public static final String ARG_INSTANCE = "instance";

    private final Handler main = new Handler(Looper.getMainLooper());
    private String instance;
    private Uri zipUri;
    private TextView tvVersion, tvFile, tvBackup, tvLog;
    private Button btnUpdate, btnRestore, btnPick;
    private View cardBackup;
    private boolean working;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                zipUri = uri;
                tvFile.setText(uri.getLastPathSegment());
                refresh();
            });

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            String msg = intent.getStringExtra(InstallerService.EXTRA_MESSAGE);
            String action = intent.getAction();
            if (InstallerService.BROADCAST_PROGRESS.equals(action)) {
                log(msg);
            } else {
                log(InstallerService.BROADCAST_ERROR.equals(action) ? "ERROR: " + msg : msg);
                working = false;
                zipUri = null;
                tvFile.setText(R.string.update_no_file);
                refresh();
            }
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_update_instance, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        instance = getArguments() == null ? null : getArguments().getString(ARG_INSTANCE);
        tvVersion = v.findViewById(R.id.tv_update_version);
        tvFile = v.findViewById(R.id.tv_update_file);
        tvBackup = v.findViewById(R.id.tv_update_backup);
        tvLog = v.findViewById(R.id.tv_update_log);
        btnPick = v.findViewById(R.id.btn_update_pick);
        btnUpdate = v.findViewById(R.id.btn_update_go);
        btnRestore = v.findViewById(R.id.btn_update_restore);
        cardBackup = v.findViewById(R.id.card_update_backup);

        // The game zip the Steam download just made, if there is one: what the player came for.
        File downloaded = SteamDownloadState.get().getGameZip();
        if (downloaded != null && downloaded.isFile()) {
            zipUri = Uri.fromFile(downloaded);
            tvFile.setText(downloaded.getName());
        }
        btnPick.setOnClickListener(x -> picker.launch(com.rimdroid.C.mime.GAME_ARCHIVE));
        btnUpdate.setOnClickListener(x -> confirmUpdate());
        btnRestore.setOnClickListener(x -> confirmRestore());

        IntentFilter f = new IntentFilter();
        f.addAction(InstallerService.BROADCAST_PROGRESS);
        f.addAction(InstallerService.BROADCAST_DONE);
        f.addAction(InstallerService.BROADCAST_ERROR);
        requireContext().registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        requireContext().unregisterReceiver(receiver);
    }

    private File instanceDir() {
        return com.rimdroid.AppStorage.requireSingleton().getInstanceDir(instance);
    }

    private void refresh() {
        if (getView() == null || instance == null) return;
        String version = InstanceUpdater.versionOf(instanceDir());
        tvVersion.setText(getString(R.string.update_current, instance, version.isEmpty() ? "?" : version));
        InstanceUpdater.Backup b = InstanceUpdater.backup(instance);
        cardBackup.setVisibility(b == null ? View.GONE : View.VISIBLE);
        if (b != null) {
            String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(b.created));
            tvBackup.setText(getString(R.string.update_backup_info, b.version.isEmpty() ? "?" : b.version, when));
            btnRestore.setText(getString(R.string.update_restore, b.version.isEmpty() ? "?" : b.version));
        }
        btnPick.setEnabled(!working);
        btnUpdate.setEnabled(!working && zipUri != null);
        btnRestore.setEnabled(!working);
    }

    private void log(String line) {
        if (line == null || tvLog == null) return;
        tvLog.append((tvLog.length() > 0 ? "\n" : "") + line);
    }

    private boolean gameRunning() {
        if (!InstanceShortcuts.isGameRunning(requireContext())) return false;
        Toast.makeText(requireContext(), R.string.update_game_running, Toast.LENGTH_LONG).show();
        return true;
    }

    private void confirmUpdate() {
        if (zipUri == null || gameRunning()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.update_confirm_title)
                .setMessage(R.string.update_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.update_go, (d, w) -> startUpdate())
                .show();
    }

    private void startUpdate() {
        working = true;
        refresh();
        tvLog.setText("");
        log(getString(R.string.update_preparing));
        final Context app = requireContext().getApplicationContext();
        final Uri uri = zipUri;
        final String name = instance;
        new Thread(() -> {
            try {
                String path;
                if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                    path = uri.getPath();   // our own download: used where it is, never deleted
                } else {
                    // A picked document arrives as a content link; the extractor needs a file. The
                    // installer removes this copy when it is done, either way.
                    File cache = new File(app.getCacheDir(), "instance.zip");
                    InstallerService.busy = true;
                    try (InputStream in = app.getContentResolver().openInputStream(uri);
                         FileOutputStream out = new FileOutputStream(cache)) {
                        if (in == null) throw new java.io.IOException("cannot open the file");
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    path = cache.getAbsolutePath();
                }
                InstallerService.startUpdateInstance(app, path, name);
            } catch (Exception e) {
                InstallerService.busy = false;
                main.post(() -> {
                    working = false;
                    log("ERROR: " + e.getMessage());
                    refresh();
                });
            }
        }, "rd-update-copy").start();
    }

    private void confirmRestore() {
        InstanceUpdater.Backup b = InstanceUpdater.backup(instance);
        if (b == null || gameRunning()) return;
        String current = InstanceUpdater.versionOf(instanceDir());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.update_restore, b.version.isEmpty() ? "?" : b.version))
                .setMessage(getString(R.string.update_restore_message, current.isEmpty() ? "?" : current))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.update_restore_go, (d, w) -> {
                    working = true;
                    refresh();
                    tvLog.setText("");
                    InstallerService.startRestoreInstance(requireContext().getApplicationContext(), instance);
                })
                .show();
    }
}
