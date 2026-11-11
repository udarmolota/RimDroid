package com.rimdroid.fragments;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rimdroid.ContentInstaller;
import com.rimdroid.GameDataTransfer;
import com.rimdroid.InstanceSettings;
import com.rimdroid.R;
import com.rimdroid.game.GameInstance;
import com.rimdroid.game.GameInstanceManager;
import com.rimdroid.input.InputControlsView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Every import and export in one screen (as in Zomdroid, 2026-11). It used to be three drawer
 * screens (saves, game settings, controls) plus the mod installer, each with its own instance picker.
 * Now the instance is picked once at the top and each card has an Import and an Export button; Import
 * picks the file and asks once before it writes anything. The formats are unchanged: saves and
 * settings are GameDataTransfer's zips, controls the layout .json, mods any zip the mod installer
 * takes (an exported one holds every downloaded mod of the instance). The custom Vulkan driver and
 * the icon packs are for the whole device and keep their own screens; the last card leads there.
 */
public class TransferFragment extends Fragment {

    private static final int NONE = 0, SAVES = 1, MODS = 2, SETTINGS = 3, CONTROLS = 4;
    private static final String STATE_PENDING = "pending";
    private static final String[] ZIP_TYPES = {"application/zip", "application/x-zip-compressed", "application/octet-stream"};

    private Spinner spInstance;
    private final List<GameInstance> instances = new ArrayList<>();
    /** What the open system picker is for; kept across a recreation of this screen. */
    private int pending = NONE;
    /** The instance an export in flight belongs to. */
    private GameInstance exportFor;

    private final ActivityResultLauncher<String[]> pickImport =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::confirmImport);
    private final ActivityResultLauncher<String[]> pickMods =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::importMods);
    private final ActivityResultLauncher<String> exportZip =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), this::export);
    private final ActivityResultLauncher<String> exportJson =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::export);

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_transfer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        if (savedInstanceState != null) pending = savedInstanceState.getInt(STATE_PENDING, NONE);

        spInstance = v.findViewById(R.id.sp_transfer_instance);
        GameInstanceManager.requireSingleton().reload();
        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        List<String> names = new ArrayList<>();
        if (instances.isEmpty()) names.add(getString(R.string.no_instances));
        for (GameInstance gi : instances) names.add(gi.getName());
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spInstance.setAdapter(a);
        String last = com.rimdroid.LauncherPreferences.requireSingleton().getLastInstanceName();
        for (int i = 0; i < instances.size(); i++)
            if (instances.get(i).getName().equals(last)) spInstance.setSelection(i);

        v.findViewById(R.id.btn_transfer_saves_import).setOnClickListener(b -> startImport(SAVES));
        v.findViewById(R.id.btn_transfer_saves_export).setOnClickListener(b -> startExport(SAVES));
        v.findViewById(R.id.btn_transfer_mods_import).setOnClickListener(b -> startImport(MODS));
        v.findViewById(R.id.btn_transfer_mods_export).setOnClickListener(b -> startExport(MODS));
        v.findViewById(R.id.btn_transfer_settings_import).setOnClickListener(b -> startImport(SETTINGS));
        v.findViewById(R.id.btn_transfer_settings_export).setOnClickListener(b -> startExport(SETTINGS));
        v.findViewById(R.id.btn_transfer_controls_import).setOnClickListener(b -> startImport(CONTROLS));
        v.findViewById(R.id.btn_transfer_controls_export).setOnClickListener(b -> startExport(CONTROLS));
        v.findViewById(R.id.btn_transfer_driver).setOnClickListener(b ->
                Navigation.findNavController(v).navigate(R.id.action_open_custom_driver));
        v.findViewById(R.id.btn_transfer_icon_packs).setOnClickListener(b ->
                Navigation.findNavController(v).navigate(R.id.action_open_icon_packs));
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PENDING, pending);
    }

    @Nullable
    private GameInstance current() {
        int i = spInstance == null ? -1 : spInstance.getSelectedItemPosition();
        return (i >= 0 && i < instances.size()) ? instances.get(i) : null;
    }

    @Nullable
    private GameInstance currentOrToast() {
        GameInstance gi = current();
        if (gi == null) Toast.makeText(requireContext(), R.string.no_instances, Toast.LENGTH_SHORT).show();
        return gi;
    }

    // ---- import ----

    private void startImport(int what) {
        if (currentOrToast() == null) return;
        pending = what;
        if (what == MODS) pickMods.launch(ZIP_TYPES);
        else if (what == CONTROLS) pickImport.launch(new String[]{"application/json", "text/plain", "application/octet-stream"});
        else pickImport.launch(ZIP_TYPES);
    }

    /** Importing overwrites what the instance has: asked once, a stray tap must not do it. */
    private void confirmImport(@Nullable Uri uri) {
        GameInstance gi = current();
        int what = pending;
        pending = NONE;
        if (uri == null || gi == null || !isAdded()) return;
        String file = displayName(uri);
        if (file == null) file = uri.getLastPathSegment();
        int message = what == SAVES ? R.string.saves_transfer_import_confirm
                : what == SETTINGS ? R.string.settings_transfer_import_confirm : 0;
        String text = what == CONTROLS ? getString(R.string.controls_transfer_import_confirm, gi.getName(), file)
                : getString(message, file, gi.getName());
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(text)
                .setPositiveButton(R.string.transfer_import, (d, w) -> {
                    if (what == CONTROLS) importControls(uri, gi.getName());
                    else importData(uri, gi, what == SAVES ? GameDataTransfer.SAVES : GameDataTransfer.CONFIG);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Mods add to the instance and replace nothing else, so no question: the installer reports. */
    private void importMods(@Nullable List<Uri> uris) {
        GameInstance gi = current();
        pending = NONE;
        if (uris == null || uris.isEmpty() || gi == null || !isAdded()) return;
        ContentInstaller.install(requireActivity(), uris, gi, false);
    }

    private void importData(Uri uri, GameInstance gi, String part) {
        final Context app = requireContext().getApplicationContext();
        final File userDir = gi.getUserDataDir();
        new Thread(() -> {
            String msg;
            File cacheZip = new File(app.getCacheDir(), "import_data.zip");
            try (InputStream in = app.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(cacheZip)) {
                if (in == null) throw new java.io.IOException("cannot open the file");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                GameDataTransfer.Result r = GameDataTransfer.importZip(cacheZip, userDir, new String[]{part});
                msg = r.ok() ? app.getString(R.string.transfer_imported, android.text.TextUtils.join(" + ", r.items), gi.getName())
                        : app.getString(R.string.transfer_import_failed, r.error);
            } catch (Exception e) {
                msg = app.getString(R.string.transfer_import_failed, String.valueOf(e.getMessage()));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                cacheZip.delete();
            }
            toastLater(msg);
        }, "data-import").start();
    }

    private void importControls(Uri uri, String instance) {
        final Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            String msg;
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("cannot open the file");
                String json = new String(readAll(in), StandardCharsets.UTF_8);
                // Only a layout that parses is stored, so a wrong file cannot leave the instance empty.
                if (InputControlsView.parse(json) == null) throw new java.io.IOException("not a controls layout");
                new InstanceSettings(instance).setControlsJson(json);
                msg = app.getString(R.string.controls_imported, instance);
            } catch (Exception e) {
                msg = app.getString(R.string.controls_import_failed,
                        e.getMessage() == null ? "invalid layout file" : e.getMessage());
            }
            toastLater(msg);
        }, "controls-import").start();
    }

    // ---- export ----

    private void startExport(int what) {
        GameInstance gi = currentOrToast();
        if (gi == null) return;
        exportFor = gi;
        pending = what;
        String kind = what == SAVES ? "saves" : what == MODS ? "mods" : what == SETTINGS ? "settings" : "controls";
        String name = "rimdroid_" + kind + "_" + gi.getName().replaceAll("[^A-Za-z0-9._-]", "_") + "_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
        if (what == CONTROLS) exportJson.launch(name + ".json");
        else exportZip.launch(name + ".zip");
    }

    private void export(@Nullable Uri uri) {
        final GameInstance gi = exportFor;
        final int what = pending;
        pending = NONE;
        if (uri == null || gi == null || !isAdded()) return;
        final Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            String msg;
            try (OutputStream out = app.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("cannot open the file");
                if (what == CONTROLS) {
                    // An instance with no layout of its own plays the bundled default: export that.
                    String json = new InstanceSettings(gi.getName()).getControlsJson();
                    if (json == null || json.trim().isEmpty()) {
                        try (InputStream in = app.getAssets().open(InputControlsView.DEFAULT_ASSET)) {
                            json = new String(readAll(in), StandardCharsets.UTF_8);
                        }
                    }
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    msg = app.getString(R.string.controls_exported);
                } else if (what == MODS) {
                    int count = exportMods(new File(gi.getGamePath()), out);
                    msg = app.getString(R.string.transfer_mods_exported, count);
                } else {
                    String part = what == SAVES ? GameDataTransfer.SAVES : GameDataTransfer.CONFIG;
                    GameDataTransfer.Result r = GameDataTransfer.export(gi.getUserDataDir(), out, new String[]{part});
                    msg = r.ok() ? app.getString(R.string.transfer_exported, android.text.TextUtils.join(" + ", r.items),
                            (int) (r.bytes / 1024))
                            : app.getString(R.string.transfer_export_failed, r.error);
                }
            } catch (Exception e) {
                msg = app.getString(R.string.transfer_export_failed, String.valueOf(e.getMessage()));
            }
            toastLater(msg);
        }, "transfer-export").start();
    }

    /**
     * Every downloaded mod of the instance (its Mods/ folders with About/About.xml), each under its
     * own folder in one zip: the mod installer takes such a zip back whole, here or on another phone.
     * Symbolic links are skipped.
     */
    private static int exportMods(File instance, OutputStream raw) throws java.io.IOException {
        int count = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new java.io.BufferedOutputStream(raw))) {
            for (com.rimdroid.mods.ModList.Mod m : com.rimdroid.mods.ModList.installed(instance)) {
                if (m.official) continue;
                addTree(zip, m.folder, m.folder.getName() + "/");
                count++;
            }
        }
        return count;
    }

    private static void addTree(ZipOutputStream zip, File f, String path) throws java.io.IOException {
        if (java.nio.file.Files.isSymbolicLink(f.toPath())) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) addTree(zip, k, path + k.getName() + (k.isDirectory() ? "/" : ""));
            return;
        }
        if (!f.isFile()) return;
        zip.putNextEntry(new ZipEntry(path));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
        }
        zip.closeEntry();
    }

    // ---- helpers ----

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        return bos.toByteArray();
    }

    private void toastLater(String msg) {
        android.app.Activity a = getActivity();
        if (a != null) a.runOnUiThread(() -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
    }

    @Nullable
    private String displayName(Uri uri) {
        try (Cursor c = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
