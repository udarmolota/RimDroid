package com.rimdroid.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.rimdroid.AppStorage;
import com.rimdroid.GameActivity;
import com.rimdroid.GameLauncher;
import com.rimdroid.InstallerService;
import com.rimdroid.LauncherPreferences;
import com.rimdroid.R;
import com.rimdroid.game.GameInstance;
import com.rimdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class LauncherFragment extends Fragment {

    private static final String TAG = "RimDroid/LauncherFrag";
    private static final int MAX_LOG_LINES = 500;

    private Spinner spinnerInstances;
    private Button btnLaunch;
    private Button btnInstallInstance;
    private Button btnClearLog;
    private TextView tvLog;
    private ScrollView scrollLog;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int logLineCount = 0;

    private final ActivityResultLauncher<String[]> zipPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) onZipSelected(uri); });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_launcher, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        spinnerInstances   = view.findViewById(R.id.spinner_instances);
        btnLaunch          = view.findViewById(R.id.btn_launch);
        btnInstallInstance = view.findViewById(R.id.btn_install_instance);
        btnClearLog        = view.findViewById(R.id.btn_clear_log);
        tvLog              = view.findViewById(R.id.tv_log);
        scrollLog          = view.findViewById(R.id.scroll_log);

        btnLaunch.setOnClickListener(v -> onLaunchClicked());
        btnInstallInstance.setOnClickListener(v ->
                zipPicker.launch(new String[]{"application/zip", "application/x-zip-compressed"}));
        btnClearLog.setOnClickListener(v -> clearLog());

        // Подключаем callback для лога из GameLauncher
        GameLauncher.setLogCallback(line -> appendLog(line));

        refreshInstances();
        registerInstallerReceiver();

        if (!LauncherPreferences.requireSingleton().areDependenciesInstalled()) {
            appendLog("Installing renderer libraries...");
            InstallerService.startInstallDeps(requireContext());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        GameLauncher.setLogCallback(null);
        requireContext().unregisterReceiver(installerReceiver);
    }

    // ---- Instances ----------------------------------------------------------

    private void refreshInstances() {
        GameInstanceManager.requireSingleton().reload();
        List<GameInstance> instances = GameInstanceManager.requireSingleton().getInstances();

        List<String> names = new ArrayList<>();
        for (GameInstance gi : instances) names.add(gi.getName());

        if (names.isEmpty()) {
            names.add(getString(R.string.no_instances));
            btnLaunch.setEnabled(false);
        } else {
            btnLaunch.setEnabled(true);
            String last = LauncherPreferences.requireSingleton().getLastInstanceName();
            int idx = names.indexOf(last);
            if (idx >= 0) spinnerInstances.setSelection(idx);
        }

        spinnerInstances.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, names));
    }

    // ---- Launch -------------------------------------------------------------

    private void onLaunchClicked() {
        String name = (String) spinnerInstances.getSelectedItem();
        if (name == null || name.equals(getString(R.string.no_instances))) return;

        GameInstance gi = GameInstanceManager.requireSingleton().getByName(name);
        if (gi == null) { appendLog("Instance not found: " + name); return; }

        LauncherPreferences.requireSingleton().setLastInstanceName(name);
        clearLog();

        requireContext().startActivity(
                new android.content.Intent(requireContext(), GameActivity.class));

        new Thread(() -> {
            try {
                GameLauncher.launch(gi);
            } catch (ErrnoException e) {
                Log.e(TAG, "Launch failed", e);
                appendLog("ERROR: " + e.getMessage());
            }
        }).start();
    }

    // ---- Install from ZIP ---------------------------------------------------

    private void onZipSelected(Uri uri) {
        new Thread(() -> {
            try {
                File cacheZip = new File(requireContext().getCacheDir(), "instance.zip");
                try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(cacheZip)) {
                    byte[] buf = new byte[65536];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                String instanceName = guessInstanceName(uri);
                mainHandler.post(() -> appendLog("Installing: " + instanceName + "..."));
                InstallerService.startInstallInstance(requireContext(),
                        cacheZip.getAbsolutePath(), instanceName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to copy zip", e);
                appendLog("ERROR: " + e.getMessage());
            }
        }).start();
    }

    private String guessInstanceName(Uri uri) {
        try (android.database.Cursor c = requireContext().getContentResolver().query(
                uri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null) {
                    if (name.toLowerCase().endsWith(".zip"))
                        name = name.substring(0, name.length() - 4);
                    return name;
                }
            }
        } catch (Exception ignored) {}
        return "rimworld";
    }

    // ---- Installer broadcast ------------------------------------------------

    private final BroadcastReceiver installerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String msg = intent.getStringExtra(InstallerService.EXTRA_MESSAGE);
            String action = intent.getAction();
            if (InstallerService.BROADCAST_PROGRESS.equals(action)) {
                appendLog(msg);
            } else if (InstallerService.BROADCAST_DONE.equals(action)) {
                appendLog(msg);
                refreshInstances();
            } else if (InstallerService.BROADCAST_ERROR.equals(action)) {
                appendLog("ERROR: " + msg);
            }
        }
    };

    private void registerInstallerReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(InstallerService.BROADCAST_PROGRESS);
        f.addAction(InstallerService.BROADCAST_DONE);
        f.addAction(InstallerService.BROADCAST_ERROR);
        requireContext().registerReceiver(installerReceiver, f, Context.RECEIVER_NOT_EXPORTED);
    }

    // ---- Log ----------------------------------------------------------------

    public void appendLog(String line) {
        if (line == null) return;
        mainHandler.post(() -> {
            // Ограничиваем количество строк чтобы не замедлять UI
            if (logLineCount >= MAX_LOG_LINES) {
                String current = tvLog.getText().toString();
                int newline = current.indexOf('\n');
                if (newline >= 0) {
                    tvLog.setText(current.substring(newline + 1));
                    logLineCount--;
                }
            }
            tvLog.append(line + "\n");
            logLineCount++;
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void clearLog() {
        mainHandler.post(() -> {
            tvLog.setText("");
            logLineCount = 0;
        });
    }
}
