package com.rimdroid.fragments;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
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

import com.rimdroid.CustomDriverInstaller;
import com.rimdroid.R;

/**
 * Device-global custom Vulkan driver import (AdrenoTools-style). Opened from the drawer menu
 * (NOT app settings — the driver is shared by all instances, not an app-wide preference). Imports
 * a raw .so or an AdrenoTools .zip into custom_driver.so; instances then pick "Custom driver
 * (imported)" in their per-instance Vulkan-driver spinner.
 */
public class DriverImportFragment extends Fragment {

    private TextView status;
    private Button removeBtn;

    private final ActivityResultLauncher<String[]> pickDriver =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onDriverPicked);

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_driver_import, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        status = v.findViewById(R.id.tv_custom_driver_status);
        removeBtn = v.findViewById(R.id.btn_custom_driver_remove);
        v.findViewById(R.id.btn_custom_driver_import).setOnClickListener(b ->
                pickDriver.launch(new String[]{"application/zip", "application/octet-stream", "*/*"}));
        removeBtn.setOnClickListener(b -> {
            if (CustomDriverInstaller.remove()) toast(getString(R.string.custom_driver_removed));
            refreshStatus();
        });
        refreshStatus();
    }

    private void onDriverPicked(@Nullable Uri uri) {
        if (uri == null) return;
        String name = queryDisplayName(uri);
        toast(getString(R.string.custom_driver_importing));
        new Thread(() -> {
            String result;
            try {
                long bytes = CustomDriverInstaller.importFrom(requireContext(), uri, name);
                result = getString(R.string.custom_driver_imported, humanSize(bytes));
            } catch (Exception e) {
                result = getString(R.string.custom_driver_import_failed,
                        e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String msg = result;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> { toast(msg); refreshStatus(); });
        }, "rd-driver-import").start();
    }

    private void refreshStatus() {
        if (status == null) return;
        if (CustomDriverInstaller.isInstalled()) {
            status.setText(getString(R.string.custom_driver_status_installed,
                    humanSize(CustomDriverInstaller.driverFile().length())));
            removeBtn.setEnabled(true);
        } else {
            status.setText(R.string.custom_driver_status_none);
            removeBtn.setEnabled(false);
        }
    }

    @Nullable
    private String queryDisplayName(Uri uri) {
        try (Cursor c = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        String s = uri.getLastPathSegment();
        return s == null ? null : s.substring(s.lastIndexOf('/') + 1);
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        if (bytes >= 1024) return String.format("%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private void toast(String msg) {
        if (isAdded()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
