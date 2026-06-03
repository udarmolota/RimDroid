package com.rimdroid;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Toast;
import android.graphics.Insets;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.activity.EdgeToEdge;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rimdroid.databinding.ActivityLauncherBinding;
import com.rimdroid.game.GameInstance;
import com.rimdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class LauncherActivity extends AppCompatActivity {

    private ActivityLauncherBinding binding;
    private NavController navController;
    private AppBarConfiguration appBarConfiguration;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> modZipPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importModsFromZip(uri); });

    private final ActivityResultLauncher<String> exportDataLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"),
                    uri -> { if (uri != null) exportGameData(uri); });

    private final ActivityResultLauncher<String[]> importDataLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importGameData(uri); });

    private final ActivityResultLauncher<String> exportLogsLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"),
                    uri -> { if (uri != null) exportLogs(uri); });

    private final ActivityResultLauncher<String> exportLayoutLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                    uri -> { if (uri != null) exportLayout(uri); });

    private final ActivityResultLauncher<String[]> importLayoutLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importLayout(uri); });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);

        binding = ActivityLauncherBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.appbarLayout.setOnApplyWindowInsetsListener((v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), insets.top, v.getPaddingRight(), insets.bottom);
            return windowInsets;
        });

        setSupportActionBar(binding.appbar);

        appBarConfiguration = new AppBarConfiguration.Builder(R.id.launcher_fragment)
                .setOpenableLayout(binding.drawerLayout)
                .build();

        navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        NavigationUI.setupWithNavController(binding.launcherNv, navController);

        binding.launcherNv.setNavigationItemSelectedListener(item -> {
            binding.drawerLayout.close();
            int id = item.getItemId();
            if (id == R.id.action_settings) {
                navController.navigate(R.id.action_settings);
                return true;
            } else if (id == R.id.action_install_instance) {
                navController.navigate(R.id.action_install_instance);
                return true;
            } else if (id == R.id.action_import_mods) {
                modZipPicker.launch(new String[]{
                        "application/zip", "application/x-zip-compressed", "application/octet-stream"});
                return true;
            } else if (id == R.id.action_export_saves) {
                exportDataLauncher.launch("rimdroid_backup.zip");
                return true;
            } else if (id == R.id.action_import_saves) {
                importDataLauncher.launch(new String[]{
                        "application/zip", "application/x-zip-compressed", "application/octet-stream"});
                return true;
            } else if (id == R.id.action_export_logs) {
                exportLogsLauncher.launch("rimdroid_logs_" + timestamp() + ".zip");
                return true;
            } else if (id == R.id.action_export_layout) {
                exportLayoutLauncher.launch("rimdroid_controls.json");
                return true;
            } else if (id == R.id.action_import_layout) {
                importLayoutLauncher.launch(new String[]{
                        "application/json", "text/plain", "application/octet-stream"});
                return true;
            } else if (id == R.id.action_updates) {
                openUrl(getString(R.string.url_github_releases));
                return true;
            } else if (id == R.id.action_reddit) {
                openUrl(getString(R.string.url_reddit));
                return true;
            } else if (id == R.id.action_manage_storage) {
                Uri rootUri = DocumentsContract.buildRootsUri(C.STORAGE_PROVIDER_AUTHORITY);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(rootUri, "vnd.android.document/root");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            }
            return NavigationUI.onNavDestinationSelected(item, navController)
                    || super.onOptionsItemSelected(item);
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    // ---- Smart mod import -----------------------------------------------------

    /** Import mods from a picked .zip into the selected instance's Mods folder. */
    private void importModsFromZip(Uri uri) {
        final GameInstance instance = currentInstance();
        if (instance == null) { toast("Create a game instance first, then import mods."); return; }

        final File modsDir = new File(instance.getGamePath(), "Mods");
        final String fallback = guessZipName(uri);
        toast("Importing mods…");

        new Thread(() -> {
            File cacheZip = new File(getCacheDir(), "import_mods.zip");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(cacheZip)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception ex) {
                ui.post(() -> toast("Read failed: " + ex.getMessage()));
                return;
            }
            ModImporter.Result res = ModImporter.importZip(cacheZip, modsDir, fallback);
            //noinspection ResultOfMethodCallIgnored
            cacheZip.delete();
            ui.post(() -> {
                if (res.ok()) {
                    toast("Imported " + res.imported.size() + " mod(s) into " + instance.getName()
                            + ": " + android.text.TextUtils.join(", ", res.imported));
                } else {
                    String msg = res.errors.isEmpty() ? "no mod found in zip" : res.errors.get(0);
                    toast("Import failed: " + msg);
                }
            });
        }).start();
    }

    /** The instance to act on: the last-launched one, else the first available, else null. */
    private GameInstance currentInstance() {
        GameInstanceManager mgr = GameInstanceManager.requireSingleton();
        mgr.reload();
        String last = LauncherPreferences.requireSingleton().getLastInstanceName();
        GameInstance gi = (last != null && !last.isEmpty()) ? mgr.getByName(last) : null;
        if (gi == null) {
            List<GameInstance> all = mgr.getInstances();
            if (!all.isEmpty()) gi = all.get(0);
        }
        return gi;
    }

    // ---- Save / settings backup & restore -------------------------------------

    /** Export the selected instance's saves + settings into a picked .zip. */
    private void exportGameData(Uri uri) {
        final GameInstance instance = currentInstance();
        if (instance == null) { toast("Create a game instance first."); return; }
        final File userDir = instance.getUserDataDir();
        toast("Exporting saves + settings…");

        new Thread(() -> {
            GameDataTransfer.Result res;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) { ui.post(() -> toast("Export failed: cannot open file")); return; }
                res = GameDataTransfer.export(userDir, out);
            } catch (Exception ex) {
                ui.post(() -> toast("Export failed: " + ex.getMessage()));
                return;
            }
            final GameDataTransfer.Result fr = res;
            ui.post(() -> toast(fr.ok()
                    ? "Exported " + android.text.TextUtils.join(" + ", fr.items)
                        + " (" + (fr.bytes / 1024) + " KB)"
                    : "Export failed: " + fr.error));
        }).start();
    }

    /** Restore saves + settings from a picked .zip into the selected instance. */
    private void importGameData(Uri uri) {
        final GameInstance instance = currentInstance();
        if (instance == null) { toast("Create a game instance first."); return; }
        final File userDir = instance.getUserDataDir();
        toast("Importing saves + settings…");

        new Thread(() -> {
            File cacheZip = new File(getCacheDir(), "import_data.zip");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(cacheZip)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception ex) {
                ui.post(() -> toast("Read failed: " + ex.getMessage()));
                return;
            }
            GameDataTransfer.Result res = GameDataTransfer.importZip(cacheZip, userDir);
            //noinspection ResultOfMethodCallIgnored
            cacheZip.delete();
            final GameDataTransfer.Result fr = res;
            ui.post(() -> toast(fr.ok()
                    ? "Restored " + android.text.TextUtils.join(" + ", fr.items) + " → " + instance.getName()
                    : "Import failed: " + fr.error));
        }).start();
    }

    /** Export the selected instance's diagnostic logs into a picked, date-stamped .zip. */
    private void exportLogs(Uri uri) {
        final GameInstance instance = currentInstance();
        if (instance == null) { toast("Create a game instance first."); return; }
        toast("Exporting logs…");

        new Thread(() -> {
            LogExporter.Result res;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) { ui.post(() -> toast("Export failed: cannot open file")); return; }
                res = LogExporter.export(instance, out);
            } catch (Exception ex) {
                ui.post(() -> toast("Export failed: " + ex.getMessage()));
                return;
            }
            final LogExporter.Result fr = res;
            ui.post(() -> toast(fr.ok()
                    ? "Logs: " + android.text.TextUtils.join(", ", fr.items)
                        + " (" + (fr.bytes / 1024) + " KB)"
                    : "Export failed: " + fr.error));
        }).start();
    }

    // ---- On-screen controls layout backup --------------------------------------

    /** Export the current on-screen controls layout (JSON) to a picked file. Falls back
     *  to the bundled default layout if the user has never customized it. */
    private void exportLayout(Uri uri) {
        new Thread(() -> {
            String json = LauncherPreferences.requireSingleton().getControlsJson();
            try {
                if (json == null || json.trim().isEmpty()) {
                    try (InputStream in = getAssets().open(
                            com.rimdroid.input.InputControlsView.DEFAULT_ASSET);
                         java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                        byte[] b = new byte[8192]; int n;
                        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                        json = bos.toString("UTF-8");
                    }
                }
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) { ui.post(() -> toast("Export failed: cannot open file")); return; }
                    out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                ui.post(() -> toast("Controls layout exported"));
            } catch (Exception ex) {
                ui.post(() -> toast("Export failed: " + ex.getMessage()));
            }
        }).start();
    }

    /** Import an on-screen controls layout (JSON) from a picked file. */
    private void importLayout(Uri uri) {
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                String json = bos.toString("UTF-8");
                // Validate it parses as JSON before saving (rejects garbage files).
                new com.google.gson.Gson().fromJson(json, com.google.gson.JsonElement.class);
                LauncherPreferences.requireSingleton().setControlsJson(json);
                ui.post(() -> toast("Controls layout imported — reopen the game to apply"));
            } catch (Exception ex) {
                ui.post(() -> toast("Import failed: "
                        + (ex.getMessage() == null ? "invalid layout file" : ex.getMessage())));
            }
        }).start();
    }

    /** yyyyMMdd_HHmmss — keeps each exported log zip uniquely named (no overwrite). */
    private static String timestamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    /** Open a URL in the user's browser (community / updates links). */
    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Couldn't open link: " + url);
        }
    }

    private String guessZipName(Uri uri) {
        String s = uri.getLastPathSegment();
        if (s == null) return "mod";
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        if (s.toLowerCase().endsWith(".zip")) s = s.substring(0, s.length() - 4);
        return s.isEmpty() ? "mod" : s;
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }
}
