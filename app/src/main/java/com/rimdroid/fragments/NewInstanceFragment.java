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
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.rimdroid.InstallerService;
import com.rimdroid.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class NewInstanceFragment extends Fragment {

    // The instance name is a directory name inside the built-in X server's Unix-socket path
    // (<home>/instances/<name>/tmp/.X11-unix/X0). Android's sun_path is only 108 bytes and our
    // native binder silently truncates an over-long path, so a long name makes the X server fail
    // to bind and the whole launch crashes with "Failed to allocate XConnectorEpoll" — the game
    // never starts (seen on a Mi 10T Pro, 2026-07-23, whose name was auto-filled from a long zip
    // filename). Cap the name well under the byte budget: the fixed prefix+suffix take ~59 bytes,
    // leaving ~48; 40 keeps a margin for work-profile/cloned-app user dirs (/data/user/<n>/...).
    private static final int MAX_NAME_LEN = 40;

    private EditText etInstanceName;
    private Button   btnPickZip;
    private Button   btnInstall;
    private TextView tvSelectedZip;

    private Uri selectedZipUri;
    private String lastInstanceName;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver installerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (InstallerService.BROADCAST_DONE.equals(action)) {
                adviseDriverThenLeave();
            } else if (InstallerService.BROADCAST_ERROR.equals(action)) {
                mainHandler.post(() -> {
                    btnInstall.setEnabled(true);
                    btnInstall.setText(R.string.install);
                    String msg = intent.getStringExtra(InstallerService.EXTRA_MESSAGE);
                    etInstanceName.setError(msg != null ? msg : getString(R.string.error_name_required));
                });
            }
        }
    };

    private final ActivityResultLauncher<String[]> zipPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                selectedZipUri = uri;
                tvSelectedZip.setText(uri.getLastPathSegment());
                // Deliberately do NOT auto-fill the name from the zip filename: repack zips carry
                // very long names that blow the X-socket path budget (see MAX_NAME_LEN). The field
                // is pre-filled with a short free default in onViewCreated; the user can still edit
                // it, and startInstall enforces the length + collision checks.
                if (etInstanceName.getText().toString().trim().isEmpty()) {
                    etInstanceName.setText(freeDefaultName());
                }
                btnInstall.setEnabled(true);
            });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_new_instance, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etInstanceName = view.findViewById(R.id.et_instance_name);
        btnPickZip     = view.findViewById(R.id.btn_pick_zip);
        btnInstall     = view.findViewById(R.id.btn_install);
        tvSelectedZip  = view.findViewById(R.id.tv_selected_zip);

        btnInstall.setEnabled(false);

        // Pre-fill a short, always-fits default so most users just tap Install and never hit the
        // name-length limit; the field stays editable for anyone who wants a custom name.
        if (etInstanceName.getText().toString().trim().isEmpty()) {
            etInstanceName.setText(freeDefaultName());
        }

        // A RimWorld .zip, or a zip wrapping GOG .sh installers (base + DLC) — GogInstallerExtractor
        // sniffs the content and unpacks the .sh files found inside.
        btnPickZip.setOnClickListener(v -> zipPicker.launch(com.rimdroid.C.mime.GAME_ARCHIVE));

        btnInstall.setOnClickListener(v -> startInstall());

        IntentFilter f = new IntentFilter();
        f.addAction(InstallerService.BROADCAST_DONE);
        f.addAction(InstallerService.BROADCAST_ERROR);
        requireContext().registerReceiver(installerReceiver, f, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        requireContext().unregisterReceiver(installerReceiver);
    }

    /** On install success: detect the GPU, set the recommended driver on the new instance, show a
     *  one-time dialog, then return to the launcher. */
    private void adviseDriverThenLeave() {
        final String inst = lastInstanceName;
        if (inst == null) { Navigation.findNavController(requireView()).popBackStack(); return; }
        new Thread(() -> {
            final com.rimdroid.GpuDriverAdvisor.Result r =
                    com.rimdroid.GpuDriverAdvisor.applyRecommendedDriver(inst);
            mainHandler.post(() -> {
                if (!isAdded() || getView() == null) return;
                if (!r.applied) { Navigation.findNavController(requireView()).popBackStack(); return; }
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.driver_auto_set_title)
                        .setMessage(getString(R.string.driver_auto_set, r.gpuName, r.driverLabel))
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> Navigation.findNavController(requireView()).popBackStack())
                        .show();
            });
        }, "rd-gpu-advise").start();
    }

    /** "RimWorld", or "RimWorld-2"/"-3"/... — the first name with no existing instance directory.
     *  Lives in AppStorage so the Steam download screen pre-fills the same default. */
    private static String freeDefaultName() {
        return com.rimdroid.AppStorage.freeDefaultInstanceName();
    }

    private void startInstall() {
        if (selectedZipUri == null) return;
        String rawName = etInstanceName.getText().toString().trim();
        // Instance name = directory name = part of every game path. RimWorld 1.6 loads mod audio
        // via UnityWebRequest/curl "file://" URLs WITHOUT escaping, so a space (or other URL-hostile
        // char) in the path silently kills all mod sounds ("Curl error 3: URL rejected", found
        // 2026-07-11). Sanitize to a URL/path-safe name up front.
        final String instanceName = rawName.replaceAll("[^A-Za-z0-9._-]+", "-")
                                           .replaceAll("^-+|-+$", "");
        if (instanceName.isEmpty()) {
            etInstanceName.setError(getString(R.string.error_name_required));
            return;
        }
        // Cap the length so the X-socket path can't overflow sun_path (see MAX_NAME_LEN).
        if (instanceName.length() > MAX_NAME_LEN) {
            etInstanceName.setError(getString(R.string.error_name_too_long, MAX_NAME_LEN));
            return;
        }
        // Refuse a name whose instance directory already exists, so we never install over (or beside)
        // an existing instance. InstallerService double-checks, but catching it here gives a clear
        // field error instead of a late broadcast failure.
        if (com.rimdroid.AppStorage.requireSingleton().getInstanceDir(instanceName).exists()) {
            etInstanceName.setError(getString(R.string.error_name_exists));
            return;
        }

        lastInstanceName = instanceName;
        btnInstall.setEnabled(false);
        btnInstall.setText(R.string.installing);

        new Thread(() -> {
            try {
                File cacheZip = new File(requireContext().getCacheDir(), "instance.zip");
                try (InputStream in = requireContext().getContentResolver()
                        .openInputStream(selectedZipUri);
                     FileOutputStream out = new FileOutputStream(cacheZip)) {
                    byte[] buf = new byte[65536];
                    int len;
                    while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                }
                InstallerService.startInstallInstance(
                        requireContext(), cacheZip.getAbsolutePath(), instanceName);
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    btnInstall.setEnabled(true);
                    btnInstall.setText(R.string.install);
                });
            }
        }).start();
    }
}
