package com.rimdroid.fragments;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.rimdroid.ContentInstaller;
import com.rimdroid.R;
import com.rimdroid.game.GameInstance;
import com.rimdroid.game.GameInstanceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated "Install mod / DLC" page: pick a .zip from anywhere on the phone (SAF), choose the target
 * instance + Mod/DLC, then Install. A persistent page (not a dialog-from-callback) so the selection
 * survives the file-picker round trip reliably. Install itself reuses {@link ContentInstaller}.
 */
public class InstallContentFragment extends Fragment {

    private Spinner spInstance;
    private RadioGroup rgType;
    private TextView tvFile;
    private List<GameInstance> instances = new ArrayList<>();
    private Uri selectedZip;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                selectedZip = uri;
                tvFile.setText(displayName(uri));
            });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_install_content, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spInstance = v.findViewById(R.id.sp_install_instance);
        rgType     = v.findViewById(R.id.rg_install_type);
        tvFile     = v.findViewById(R.id.tv_install_file);
        Button btnPick = v.findViewById(R.id.btn_install_pick);
        Button btnGo   = v.findViewById(R.id.btn_install_go);

        GameInstanceManager.requireSingleton().reload();
        instances = GameInstanceManager.requireSingleton().getInstances();
        List<String> names = new ArrayList<>();
        for (GameInstance gi : instances) names.add(gi.getName());
        if (names.isEmpty()) {
            names.add(getString(R.string.no_instances));
            btnGo.setEnabled(false);
        }
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spInstance.setAdapter(a);

        // Mod/DLC .zip, or a zip wrapping a GOG DLC .sh installer (ContentInstaller sniffs and routes it).
        btnPick.setOnClickListener(x -> picker.launch(com.rimdroid.C.mime.GAME_ARCHIVE));

        btnGo.setOnClickListener(x -> {
            if (instances.isEmpty()) return;
            if (selectedZip == null) {
                Toast.makeText(requireContext(), "Choose a file first", Toast.LENGTH_SHORT).show();
                return;
            }
            int pos = spInstance.getSelectedItemPosition();
            if (pos < 0 || pos >= instances.size()) return;
            GameInstance inst = instances.get(pos);
            boolean intoData = rgType.getCheckedRadioButtonId() == R.id.rb_install_dlc;
            ContentInstaller.install(requireActivity(), selectedZip, inst, intoData);
        });
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = requireContext().getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {}
        String seg = uri.getLastPathSegment();
        return seg != null ? seg : "selected.zip";
    }
}
