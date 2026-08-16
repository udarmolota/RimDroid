package com.rimdroid;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.widget.TextView;
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

    /** Which user-data parts the pending export/import targets — saves vs. settings (set per menu item). */
    private String[] pendingDataParts = { GameDataTransfer.SAVES, GameDataTransfer.CONFIG };
    private static final String[] ZIP_MIME =
            { "application/zip", "application/x-zip-compressed", "application/octet-stream" };

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
        // Refresh the toolbar menu on navigation so the "+" (add instance) shows only on the main screen.
        navController.addOnDestinationChangedListener((c, dest, args) -> invalidateOptionsMenu());

        binding.launcherNv.setNavigationItemSelectedListener(item -> {
            binding.drawerLayout.close();
            int id = item.getItemId();
            if (id == R.id.action_settings) {
                navController.navigate(R.id.action_app_settings);   // global app settings (theme); per-instance is via the card gear
                return true;
            } else if (id == R.id.action_download_game) {
                navController.navigate(R.id.action_download_game);
            } else if (id == R.id.action_cloud_saves) {
                navController.navigate(R.id.action_cloud_saves);   // pull PC saves from Steam Cloud
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
                chooseInstanceThen(gi -> { pendingInstance = gi;
                        pendingDataParts = new String[]{ GameDataTransfer.SAVES };
                        exportDataLauncher.launch(dataFileName("saves", gi)); });
                return true;
            } else if (id == R.id.action_import_saves) {
                chooseInstanceThen(gi -> { pendingInstance = gi;
                        pendingDataParts = new String[]{ GameDataTransfer.SAVES };
                        importDataLauncher.launch(ZIP_MIME); });
                return true;
            } else if (id == R.id.action_export_settings) {
                chooseInstanceThen(gi -> { pendingInstance = gi;
                        pendingDataParts = new String[]{ GameDataTransfer.CONFIG };
                        exportDataLauncher.launch(dataFileName("settings", gi)); });
                return true;
            } else if (id == R.id.action_import_settings) {
                chooseInstanceThen(gi -> { pendingInstance = gi;
                        pendingDataParts = new String[]{ GameDataTransfer.CONFIG };
                        importDataLauncher.launch(ZIP_MIME); });
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
            } else if (id == R.id.action_bug_report) {
                sendBugReport();
                return true;
            }
            // GitHub / X / Reddit / Support are no longer menu rows — they're the icon row in the
            // drawer header, wired in wireHeaderLinks().
            // "Manage storage" is gone from the drawer: every instance card already has it in its own
            // menu, scoped to that instance (LauncherFragment.openInstanceStorage), so the global copy
            // was a duplicate that only made an already-long drawer longer.
            return NavigationUI.onNavDestinationSelected(item, navController)
                    || super.onOptionsItemSelected(item);
        });

        wireHeaderLinks();
        maybeDailyUpdateCheck();
    }

    /** The four external links pinned at the bottom of the drawer (icon row, not menu rows). */
    private void wireHeaderLinks() {
        findViewById(R.id.link_github).setOnClickListener(v -> { binding.drawerLayout.close(); checkForUpdates(); });
        findViewById(R.id.link_x).setOnClickListener(v -> openLink(R.string.url_x));
        findViewById(R.id.link_reddit).setOnClickListener(v -> openLink(R.string.url_reddit_sub));
        findViewById(R.id.link_support).setOnClickListener(v -> { binding.drawerLayout.close(); showDonateDialog(); });
        findViewById(R.id.link_zomdroid).setOnClickListener(v -> { binding.drawerLayout.close(); showZomdroidDialog(); });
    }

    /** Cross-promo dialog for Zomdroid (sibling launcher, same author). Link is clickable. */
    private void showZomdroidDialog() {
        android.text.SpannableString s =
                new android.text.SpannableString(getString(R.string.zomdroid_dialog_message));
        android.text.util.Linkify.addLinks(s, android.text.util.Linkify.WEB_URLS);
        androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.nav_label_zomdroid)
                        .setMessage(s)
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
        dialog.show();
        TextView mv = dialog.findViewById(android.R.id.message);
        if (mv != null) mv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    }

    /** Open a link, or say "coming soon" if that URL hasn't been set yet (empty string resource). */
    private void openLink(int urlRes) {
        String url = getString(urlRes);
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, R.string.link_soon, Toast.LENGTH_SHORT).show();
            return;
        }
        binding.drawerLayout.close();
        openUrl(url);
    }

    /** Support dialog — the ko-fi link is clickable (matches how Zomdroid asks for support). */
    private void showDonateDialog() {
        android.text.SpannableString s =
                new android.text.SpannableString(getString(R.string.donate_message));
        android.text.util.Linkify.addLinks(s, android.text.util.Linkify.WEB_URLS);
        androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.donate_title)
                        .setMessage(s)
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
        dialog.show();
        TextView mv = dialog.findViewById(android.R.id.message);
        if (mv != null) mv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    }

    /**
     * Open the user's email app pre-filled with a bug report to the maintainer — with the same log
     * bundle that "Export logs" produces attached, so a report arrives diagnosable. Building the zip
     * can touch multi-MB Player.logs, so it runs off the UI thread; the intent fires once it's ready.
     * If there's no instance (nothing to log) it falls back to a text-only mailto.
     */
    private void sendBugReport() {
        final java.util.Date now = new java.util.Date();
        final String date = new java.text.SimpleDateFormat("ddMMyyyy", java.util.Locale.US)
                .format(now);
        final String reportName = "rimdroid_report_"
                + new java.text.SimpleDateFormat("ddMMyyyy_HHmm", java.util.Locale.US).format(now)
                + ".zip";
        final String device = "Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + "\nAndroid: " + android.os.Build.VERSION.RELEASE
                + "\nRimDroid: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
        final GameInstance instance = currentInstance();
        if (instance == null) { startBugReportEmail(date, device, null); return; }

        toast("Preparing bug report…");
        new Thread(() -> {
            Uri attach = null;
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "reports");
                if (dir.isDirectory() || dir.mkdirs()) {
                    java.io.File zip = new java.io.File(dir, reportName);
                    try (OutputStream out = new java.io.FileOutputStream(zip)) {
                        LogExporter.export(this, instance, out);
                    }
                    if (zip.length() > 0)
                        attach = androidx.core.content.FileProvider.getUriForFile(
                                this, "com.rimdroid.fileprovider", zip);
                }
            } catch (Throwable t) { attach = null; }   // no logs → still send the text report
            final Uri fAttach = attach;
            ui.post(() -> startBugReportEmail(date, device, fAttach));
        }).start();
    }

    private void startBugReportEmail(String date, String device, Uri attachment) {
        String[] to = { getString(R.string.bug_report_email) };
        String subject = getString(R.string.bug_report_subject, date);
        String body = getString(R.string.bug_report_body, device);
        Intent i;
        if (attachment != null) {
            // ACTION_SEND carries an attachment (mailto/SENDTO can't). A chooser lets the user pick
            // their mail app; to/subject/body/zip are all prefilled.
            i = new Intent(Intent.ACTION_SEND);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_EMAIL, to);
            i.putExtra(Intent.EXTRA_SUBJECT, subject);
            i.putExtra(Intent.EXTRA_TEXT, body);
            i.putExtra(Intent.EXTRA_STREAM, attachment);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(Intent.createChooser(i, getString(R.string.nav_bug_report))); return; }
            catch (android.content.ActivityNotFoundException ignored) { /* fall through to mailto */ }
        }
        // No attachment (or no app took the SEND) → plain mailto, text only.
        i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        i.putExtra(Intent.EXTRA_EMAIL, to);
        i.putExtra(Intent.EXTRA_SUBJECT, subject);
        i.putExtra(Intent.EXTRA_TEXT, body);
        try {
            startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.bug_report_no_mail, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.launcher_toolbar, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // The "+" lives only on the launcher main screen (sub-screens show the back arrow + their title).
        MenuItem add = menu.findItem(R.id.action_install_instance);
        if (add != null) {
            add.setVisible(navController != null
                    && navController.getCurrentDestination() != null
                    && navController.getCurrentDestination().getId() == R.id.launcher_fragment);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_install_instance) {
            navController.navigate(R.id.action_install_instance);   // global action -> new_instance_fragment
            return true;
        }
        return NavigationUI.onNavDestinationSelected(item, navController)
                || super.onOptionsItemSelected(item);
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
        new MaterialAlertDialogBuilder(this)
                .setTitle("Which instance?")
                .setItems(names, (d, which) -> cont.accept(all.get(which)))
                .show();
    }

    // ---- Update check (installed vs latest GitHub release) ---------------------

    /** Fetch the latest GitHub release tag and compare it to the installed version. */
    private void checkForUpdates() {
        final String installed = installedVersion();
        toast("Checking for updates…");
        new Thread(() -> {
            String[] res = fetchLatestTag();   // {tag, error}
            final String fLatest = res[0], fErr = res[1];
            if (fLatest != null) {
                LauncherPreferences lp = LauncherPreferences.getSingleton();
                if (lp != null) lp.setLatestSeenTag(fLatest);   // keep the badge state in sync
            }
            ui.post(() -> { showUpdateDialog(installed, fLatest, fErr); refreshUpdateBadge(); });
        }, "rd-update-check").start();
    }

    private String installedVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    /** Fetch the latest release tag from GitHub. Returns {tag|null, error|null}. */
    private static String[] fetchLatestTag() {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("https://api.github.com/repos/udarmolota/rimdroid/releases/latest")
                            .openConnection();
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            try {
                if (code == 200) {
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String ln;
                    while ((ln = r.readLine()) != null) sb.append(ln);
                    r.close();
                    com.google.gson.JsonObject o =
                            com.google.gson.JsonParser.parseString(sb.toString()).getAsJsonObject();
                    if (o.has("tag_name") && !o.get("tag_name").isJsonNull())
                        return new String[]{ o.get("tag_name").getAsString(), null };
                    return new String[]{ null, "no tag in response" };
                } else if (code == 404) {
                    return new String[]{ null, "no releases published yet" };
                }
                return new String[]{ null, "GitHub returned " + code };
            } finally { c.disconnect(); }
        } catch (Exception e) {
            return new String[]{ null, e.getMessage() };
        }
    }

    /** True only if the seen GitHub tag is STRICTLY NEWER than the installed version. A plain
     *  "differs" check falsely badged dev builds that run AHEAD of the public release. */
    private boolean updateAvailable() {
        LauncherPreferences lp = LauncherPreferences.getSingleton();
        if (lp == null) return false;
        String tag = lp.getLatestSeenTag();
        if (tag == null || tag.trim().isEmpty()) return false;
        return compareVersions(tag.replaceFirst("^[vV]", ""), installedVersion()) > 0;
    }

    /** Compare dotted version strings numerically ("0.2.10" > "0.2.3"). >0 if a is newer than b. */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-+ ]"), pb = b.split("[.\\-+ ]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private void refreshUpdateBadge() {
        View dot = findViewById(R.id.update_badge);
        if (dot != null) dot.setVisibility(updateAvailable() ? View.VISIBLE : View.GONE);
    }

    /**
     * Once per day, on launcher start, ask GitHub for the latest release and remember it so the
     * drawer can badge the GitHub icon. The attempt DAY is recorded before the network call, so a
     * phone with no internet still tries at most once a day. The stored tag is compared to the live
     * installed version in {@link #updateAvailable()}, so the badge clears itself after an update.
     */
    private void maybeDailyUpdateCheck() {
        refreshUpdateBadge();   // reflect whatever we already know, every start
        LauncherPreferences lp = LauncherPreferences.getSingleton();
        if (lp == null) return;
        String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
        if (today.equals(lp.getUpdateCheckDay())) return;   // already tried today
        lp.setUpdateCheckDay(today);                        // count the attempt now (even if it fails)
        new Thread(() -> {
            String[] res = fetchLatestTag();
            if (res[0] == null) return;                     // offline/failed — try again tomorrow
            LauncherPreferences p = LauncherPreferences.getSingleton();
            if (p != null) p.setLatestSeenTag(res[0]);
            ui.post(this::refreshUpdateBadge);
        }, "rd-daily-update-check").start();
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
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle("App version")
                .setMessage(msg)
                .setNegativeButton("Close", null);
        if (latest == null || !upToDate) {
            b.setPositiveButton("Open GitHub", (d, w) -> openUrl(getString(R.string.url_github_releases)));
        }
        b.show();
    }

    // ---- Save / settings backup & restore -------------------------------------

    /** Export the selected instance's saves OR settings (per {@link #pendingDataParts}) into a picked .zip. */
    private void exportGameData(Uri uri) {
        final GameInstance instance = pendingInstance != null ? pendingInstance : currentInstance();
        if (instance == null) { toast("Create a game instance first."); return; }
        final File userDir = instance.getUserDataDir();
        final String[] parts = pendingDataParts;
        toast("Exporting…");

        new Thread(() -> {
            GameDataTransfer.Result res;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) { ui.post(() -> toast("Export failed: cannot open file")); return; }
                res = GameDataTransfer.export(userDir, out, parts);
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

    /** Restore saves OR settings (per {@link #pendingDataParts}) from a picked .zip into the selected instance. */
    private void importGameData(Uri uri) {
        final GameInstance instance = pendingInstance != null ? pendingInstance : currentInstance();
        if (instance == null) { toast("Create a game instance first."); return; }
        final File userDir = instance.getUserDataDir();
        final String[] parts = pendingDataParts;
        toast("Importing…");

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
            GameDataTransfer.Result res = GameDataTransfer.importZip(cacheZip, userDir, parts);
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
                res = LogExporter.export(this, instance, out);
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

    /** rimdroid_&lt;kind&gt;_&lt;instance&gt;_&lt;yyyyMMdd_HHmmss&gt;.zip — dated + descriptive, never overwrites. */
    private static String dataFileName(String kind, GameInstance gi) {
        String n = (gi == null || gi.getName() == null) ? "instance"
                : gi.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        return "rimdroid_" + kind + "_" + n + "_" + timestamp() + ".zip";
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
