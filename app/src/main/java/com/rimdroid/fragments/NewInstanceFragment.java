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

    private EditText etInstanceName;
    private Button   btnPickZip;
    private Button   btnInstall;
    private TextView tvSelectedZip;

    private Uri selectedZipUri;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver installerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (InstallerService.BROADCAST_DONE.equals(action)) {
                Navigation.findNavController(requireView()).popBackStack();
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
                // Auto-fill instance name from filename
                String seg = uri.getLastPathSegment();
                if (seg != null) {
                    if (seg.contains("/")) seg = seg.substring(seg.lastIndexOf('/') + 1);
                    if (seg.toLowerCase().endsWith(".zip")) seg = seg.substring(0, seg.length() - 4);
                    etInstanceName.setText(seg);
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

        btnPickZip.setOnClickListener(v ->
                zipPicker.launch(new String[]{"application/zip", "application/x-zip-compressed"}));

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

    private void startInstall() {
        if (selectedZipUri == null) return;
        String instanceName = etInstanceName.getText().toString().trim();
        if (instanceName.isEmpty()) {
            etInstanceName.setError(getString(R.string.error_name_required));
            return;
        }

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
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
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
