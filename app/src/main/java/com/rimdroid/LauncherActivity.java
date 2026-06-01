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
import java.util.List;

public class LauncherActivity extends AppCompatActivity {

    private ActivityLauncherBinding binding;
    private NavController navController;
    private AppBarConfiguration appBarConfiguration;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> modZipPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importModsFromZip(uri); });

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
        GameInstanceManager mgr = GameInstanceManager.requireSingleton();
        mgr.reload();
        String last = LauncherPreferences.requireSingleton().getLastInstanceName();
        GameInstance gi = (last != null && !last.isEmpty()) ? mgr.getByName(last) : null;
        if (gi == null) {
            List<GameInstance> all = mgr.getInstances();
            if (!all.isEmpty()) gi = all.get(0);
        }
        if (gi == null) { toast("Create a game instance first, then import mods."); return; }

        final GameInstance instance = gi;
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
