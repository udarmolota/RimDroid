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
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

    private RecyclerView rvInstances;
    private TextView tvNoInstances;
    private volatile String pendingInstallName;   // ZIP instance being installed → GPU driver advisor
    private Button btnClearLog;
    private TextView tvLog;
    private ScrollView scrollLog;

    private final List<GameInstance> instances = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> instanceAdapter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int logLineCount = 0;
    private boolean receiverRegistered = false;

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

        rvInstances        = view.findViewById(R.id.rv_instances);
        tvNoInstances      = view.findViewById(R.id.tv_no_instances);
        btnClearLog        = view.findViewById(R.id.btn_clear_log);
        tvLog              = view.findViewById(R.id.tv_log);
        scrollLog          = view.findViewById(R.id.scroll_log);

        rvInstances.setLayoutManager(new LinearLayoutManager(requireContext()));
        instanceAdapter = buildInstanceAdapter();
        rvInstances.setAdapter(instanceAdapter);

        // ZIP install is now ONLY in the drawer's "Add Instance" (which lets you name the instance
        // and refuses to overwrite). The launcher's name-less install button was a redundant subset.
        btnClearLog.setOnClickListener(v -> clearLog());

        // Hook up the log callback from GameLauncher
        GameLauncher.setLogCallback(line -> appendLog(line));

        if (!LauncherPreferences.requireSingleton().areDependenciesInstalled()) {
            appendLog("Installing renderer libraries...");
            InstallerService.startInstallDeps(requireContext());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-register the installer receiver and re-read disk state every time the
        // fragment becomes visible. This is what makes the Launch button switch to
        // enabled right after an instance finishes installing — without it, the
        // button only refreshed in onViewCreated and stayed disabled until the app
        // was restarted (no onResume = no refresh on return from other screens).
        registerInstallerReceiver();
        refreshInstances();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterInstallerReceiver();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        GameLauncher.setLogCallback(null);
        unregisterInstallerReceiver();
    }

    // ---- Instances ----------------------------------------------------------

    private void refreshInstances() {
        GameInstanceManager.requireSingleton().reload();
        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        if (instanceAdapter != null) instanceAdapter.notifyDataSetChanged();
        if (tvNoInstances != null) {
            tvNoInstances.setVisibility(instances.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /** One card per instance: name + a settings (gear) button + a Launch button. */
    private RecyclerView.Adapter<RecyclerView.ViewHolder> buildInstanceAdapter() {
        return new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.game_instance_item, parent, false);
                return new RecyclerView.ViewHolder(v) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                GameInstance gi = instances.get(position);
                View v = holder.itemView;
                TextView name = v.findViewById(R.id.instance_item_name);
                ImageButton settings = v.findViewById(R.id.instance_item_settings);
                ImageButton launch = v.findViewById(R.id.instance_item_launch);

                // Flag an incomplete game copy (missing Data/Core etc.) right in the list, so the user
                // sees it before tapping Launch. Cheap (a few File.isFile checks). launchInstance()
                // still explains what's missing if they tap it.
                name.setText(gi.isComplete() ? gi.getName() : gi.getName() + "  ⚠ incomplete");
                // (renderer subtitle is commented out in the layout — keep the binding out too)

                launch.setOnClickListener(x -> launchInstance(gi));
                settings.setOnClickListener(x -> showInstanceMenu(settings, gi));
            }

            @Override
            public int getItemCount() { return instances.size(); }
        };
    }

    /** Gear button → a small menu: Settings / Delete (Zomdroid-style). */
    private void showInstanceMenu(View anchor, GameInstance gi) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(requireContext(), anchor);
        pm.getMenuInflater().inflate(R.menu.menu_game_instance, pm.getMenu());
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_instance_settings) { openInstanceSettings(gi); return true; }
            if (id == R.id.action_instance_delete)   { confirmDeleteInstance(gi); return true; }
            if (id == R.id.action_instance_storage)  { openInstanceStorage(gi); return true; }
            return false;
        });
        pm.show();
    }

    private void openInstanceSettings(GameInstance gi) {
        Bundle b = new Bundle();
        b.putString(SettingsFragment.ARG_INSTANCE, gi.getName());
        Navigation.findNavController(requireView()).navigate(R.id.action_settings, b);
    }

    /** Open the system Documents UI directly at this instance's folder (same provider as the drawer's
     *  Manage Storage, but scoped to this instance). Falls back to the storage root if a file manager
     *  doesn't honour a folder-level view. */
    private void openInstanceStorage(GameInstance gi) {
        String docId = AppStorage.requireSingleton().getInstanceDir(gi.getName()).getAbsolutePath();
        try {
            android.net.Uri uri = android.provider.DocumentsContract.buildDocumentUri(
                    com.rimdroid.C.STORAGE_PROVIDER_AUTHORITY, docId);
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            i.setDataAndType(uri, "vnd.android.document/directory");
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            try {   // fallback: open the storage root (like the drawer's Manage Storage)
                android.net.Uri root = android.provider.DocumentsContract.buildRootsUri(
                        com.rimdroid.C.STORAGE_PROVIDER_AUTHORITY);
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                i.setDataAndType(root, "vnd.android.document/root");
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignore) {
                android.widget.Toast.makeText(requireContext(),
                        "No file manager to open storage", android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void confirmDeleteInstance(GameInstance gi) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.delete_instance))
                .setMessage(getString(R.string.delete_instance_confirm, gi.getName()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_instance, (d, w) -> deleteInstance(gi))
                .show();
    }

    private void deleteInstance(GameInstance gi) {
        final String name = gi.getName();
        final java.io.File dir = AppStorage.requireSingleton().getInstanceDir(name);
        appendLog("Deleting instance: " + name + "…");
        new Thread(() -> {
            java.util.List<String> failed = new java.util.ArrayList<>();
            deleteRecursive(dir, failed);
            final boolean gone = !dir.exists();
            com.rimdroid.InstanceSettings.delete(name);   // drop its per-instance settings keys
            LauncherPreferences lp = LauncherPreferences.requireSingleton();
            if (name.equals(lp.getLastInstanceName())) lp.setLastInstanceName("");
            mainHandler.post(() -> {
                if (gone && failed.isEmpty()) {
                    appendLog("Deleted instance: " + name);
                } else {
                    appendLog("Deleted instance: " + name + " — WARNING: " + failed.size()
                            + " item(s) could not be removed (left in " + dir.getAbsolutePath() + ")");
                    int show = Math.min(failed.size(), 8);
                    for (int i = 0; i < show; i++) appendLog("  ! " + failed.get(i));
                    if (failed.size() > show) appendLog("  … and " + (failed.size() - show) + " more");
                }
                refreshInstances();
            });
        }, "rd-delete-instance").start();
    }

    /**
     * Recursively delete {@code f}, collecting paths that could not be removed into {@code failed}.
     * Symlinks are deleted as links (we never follow them into their target — RimWorld depots
     * contain symlinks, and following them both wastes work and could touch files outside the
     * instance). Read-only entries are force-made-writable first. We attempt EVERY entry even if
     * some fail, so one stubborn file no longer strands its whole parent subtree.
     */
    private static void deleteRecursive(java.io.File f, java.util.List<String> failed) {
        if (f == null) return;
        java.nio.file.Path p = f.toPath();
        boolean isLink = java.nio.file.Files.isSymbolicLink(p);
        if (!isLink && f.isDirectory()) {
            java.io.File[] kids = f.listFiles();
            if (kids != null) for (java.io.File k : kids) deleteRecursive(k, failed);
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true);
        } catch (Exception ignored) { /* best effort */ }
        try {
            java.nio.file.Files.deleteIfExists(p);   // removes the link itself for symlinks
        } catch (Exception e) {
            failed.add(f.getAbsolutePath() + " (" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + ")");
        }
    }

    // ---- Launch -------------------------------------------------------------

    private void launchInstance(GameInstance gi) {
        if (gi == null) return;
        // Block launching a game copy that's missing base content (Data/Core etc.) — it would just
        // reach RimWorld's ModLister and crash to a black screen, looking like an app bug. Tell the
        // user it's their files. isInstalled() stays lenient so the instance still shows in the list.
        java.util.List<String> missing = gi.missingCoreFiles();
        if (!missing.isEmpty()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Incomplete game files")
                    .setMessage("This instance is missing base game content and won't start:\n\n"
                            + String.join("\n", missing) + "\n\nRimWorld needs the Data/Core folder. "
                            + "Install a complete copy of the game — the in-app Download provides a clean one.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        LauncherPreferences.requireSingleton().setLastInstanceName(gi.getName());
        clearLog();

        android.content.Intent gameIntent = new android.content.Intent(requireContext(), GameActivity.class);
        gameIntent.putExtra(GameActivity.EXTRA_INSTANCE_NAME, gi.getName());   // per-instance scale + controls
        requireContext().startActivity(gameIntent);

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
                pendingInstallName = instanceName;
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

    /** After a ZIP instance install from the launcher button, detect the GPU and set the recommended
     *  driver on the new instance, then show a one-time dialog (no navigation — already on launcher). */
    private void adviseDriverForPendingInstall() {
        final String inst = pendingInstallName;
        pendingInstallName = null;
        if (inst == null) return;
        new Thread(() -> {
            final com.rimdroid.GpuDriverAdvisor.Result r =
                    com.rimdroid.GpuDriverAdvisor.applyRecommendedDriver(inst);
            mainHandler.post(() -> {
                if (!isAdded() || getView() == null || !r.applied) return;
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.driver_auto_set_title)
                        .setMessage(getString(R.string.driver_auto_set, r.gpuName, r.driverLabel))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "rd-gpu-advise").start();
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
                adviseDriverForPendingInstall();
            } else if (InstallerService.BROADCAST_ERROR.equals(action)) {
                appendLog("ERROR: " + msg);
            }
        }
    };

    private void registerInstallerReceiver() {
        if (receiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(InstallerService.BROADCAST_PROGRESS);
        f.addAction(InstallerService.BROADCAST_DONE);
        f.addAction(InstallerService.BROADCAST_ERROR);
        requireContext().registerReceiver(installerReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private void unregisterInstallerReceiver() {
        if (!receiverRegistered) return;
        try {
            requireContext().unregisterReceiver(installerReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered — safe to ignore.
        }
        receiverRegistered = false;
    }

    // ---- Log ----------------------------------------------------------------

    public void appendLog(String line) {
        if (line == null) return;
        mainHandler.post(() -> {
            // Cap the number of lines so the UI doesn't slow down
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
