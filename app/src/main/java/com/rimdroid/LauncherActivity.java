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
                    uri -> { if (uri != null) ContentInstaller.showTargetDialog(this, uri); });

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
                navController.navigate(R.id.action_app_settings);   // global app settings (theme); per-instance is via the card gear
                return true;
            } else if (id == R.id.action_download_game) {
                navController.navigate(R.id.action_download_game);
                return true;
            } else if (id == R.id.action_install_instance) {
                navController.navigate(R.id.action_install_instance);
                return true;
            } else if (id == R.id.action_install_content) {
                navController.navigate(R.id.action_open_install);   // dedicated install page (instance + type + file)
                return true;
            } else if (id == R.id.action_custom_driver) {
                navController.navigate(R.id.action_open_custom_driver);   // device-global custom Vulkan driver import
                return true;
            } else if (id == R.id.action_export_saves) {
                chooseInstanceThen(gi -> { pendingInstance = gi; exportDataLauncher.launch("rimdroid_backup.zip"); });
                return true;
            } else if (id == R.id.action_import_saves) {
                chooseInstanceThen(gi -> { pendingInstance = gi; importDataLauncher.launch(new String[]{
                        "application/zip", "application/x-zip-compressed", "application/octet-stream"}); });
                return true;
            } else if (id == R.id.action_export_logs) {
                chooseInstanceThen(gi -> { pendingInstance = gi;
                        exportLogsLauncher.launch("rimdroid_logs_" + timestamp() + ".zip"); });
                return true;
            } else if (id == R.id.action_export_layout) {
                chooseInstanceThen(gi -> { pendingInstance = gi;
                        exportLayoutLauncher.launch("rimdroid_controls.json"); });
                return true;
            } else if (id == R.id.action_import_layout) {
                chooseInstanceThen(gi -> { pendingInstance = gi; importLayoutLauncher.launch(new String[]{
                        "application/json", "text/plain", "application/octet-stream"}); });
                return true;
            } else if (id == R.id.action_updates) {
                checkForUpdates();
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

    /** Instance chosen up-front for the next save export/import, so the destination is explicit. */
    private GameInstance pendingInstance;

    /** Ask which instance to act on (skips the dialog when there's only one), then continue. */
    private void chooseInstanceThen(java.util.function.Consumer<GameInstance> cont) {
        GameInstanceManager mgr = GameInstanceManager.requireSingleton();
        mgr.reload();
        List<GameInstance> all = mgr.getInstances();
        if (all.isEmpty()) { toast("Create a game instance first."); return; }
        if (all.size() == 1) { cont.accept(all.get(0)); return; }
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) names[i] = all.get(i).getName();
        new android.app.AlertDialog.Builder(this)
                .setTitle("Which instance?")
                .setItems(names, (d, which) -> cont.accept(all.get(which)))
                .show();
    }

    // ---- Update check (installed vs latest GitHub release) ---------------------

    /** Fetch the latest GitHub release tag and compare it to the installed version. */
    private void checkForUpdates() {
        String v;
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { v = "?"; }
        final String installed = v;
        toast("Checking for updates…");
        new Thread(() -> {
            String latest = null, err = null;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.github.com/repos/udarmolota/rimdroid/releases/latest")
                                .openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                int code = c.getResponseCode();
                if (code == 200) {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String ln;
                    while ((ln = r.readLine()) != null) sb.append(ln);
                    r.close();
                    com.google.gson.JsonObject o =
                            com.google.gson.JsonParser.parseString(sb.toString()).getAsJsonObject();
                    if (o.has("tag_name") && !o.get("tag_name").isJsonNull())
                        latest = o.get("tag_name").getAsString();
                } else if (code == 404) {
                    err = "no releases published yet";
                } else {
                    err = "GitHub returned " + code;
                }
                c.disconnect();
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fLatest = latest, fErr = err;
            ui.post(() -> showUpdateDialog(installed, fLatest, fErr));
        }, "rd-update-check").start();
    }

    private void showUpdateDialog(String installed, String latest, String err) {
        String norm = latest != null ? latest.replaceFirst("^[vV]", "") : null;
        boolean upToDate = norm != null && norm.equals(installed);
        String msg;
        if (latest == null) {
            msg = "Installed: " + installed + "\n\nCouldn't check the latest version"
                    + (err != null ? " (" + err + ")" : "") + ".";
        } else if (upToDate) {
            msg = "Installed: " + installed + "\nLatest: " + latest + "\n\nYou're up to date.";
        } else {
            msg = "Installed: " + installed + "\nLatest on GitHub: " + latest
                    + "\n\nA newer version is available.";
        }
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("App version")
                .setMessage(msg)
                .setNegativeButton("Close", null);
        if (latest == null || !upToDate) {
            b.setPositiveButton("Open GitHub", (d, w) -> openUrl(getString(R.string.url_github_releases)));
        }
        b.show();
    }

    // ---- Save / settings backup & restore -------------------------------------

    /** Export the selected instance's saves + settings into a picked .zip. */
    private void exportGameData(Uri uri) {
        final GameInstance instance = pendingInstance != null ? pendingInstance : currentInstance();
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
        final GameInstance instance = pendingInstance != null ? pendingInstance : currentInstance();
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
        final GameInstance instance = pendingInstance != null ? pendingInstance : currentInstance();
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
        final GameInstance target = pendingInstance != null ? pendingInstance : currentInstance();
        new Thread(() -> {
            // Controls are per-instance → export the CHOSEN instance's layout
            // (InstanceSettings falls back to the global/default layout if it has none).
            String json = (target != null)
                    ? new InstanceSettings(target.getName()).getControlsJson()
                    : LauncherPreferences.requireSingleton().getControlsJson();
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
        final GameInstance target = pendingInstance != null ? pendingInstance : currentInstance();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                String json = bos.toString("UTF-8");
                // Validate it parses as JSON before saving (rejects garbage files).
                new com.google.gson.Gson().fromJson(json, com.google.gson.JsonElement.class);
                // Import into the CHOSEN instance's layout (per-instance controls).
                if (target != null) new InstanceSettings(target.getName()).setControlsJson(json);
                else LauncherPreferences.requireSingleton().setControlsJson(json);
                ui.post(() -> toast("Controls layout imported into " + (target != null ? target.getName() : "default")
                        + " — reopen the game to apply"));
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
