package com.rimdroid;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rimdroid.game.GameInstance;
import com.rimdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Installs picked files (mods or DLC) into a chosen instance. One unified flow: the user picks
 * one or multiple files from anywhere on the phone (SAF — no storage permission needed, works
 * for the /Download/RimDroid zips too), then picks the target instance and Mod/DLC.
 *   • Mod zip → instance/Mods/&lt;folder&gt;
 *   • DLC zip → instance/Data/&lt;folder&gt;   (where RimWorld expects official expansions)
 * Both reuse {@link ModImporter} (finds the About/About.xml root and strips wrappers like the depot's
 * {@code Data/} folder).
 *   • zipped GOG DLC installer (a {@code .sh} inside the zip) → extracted by
 *     {@link GogInstallerExtractor} into the instance root, since its payload is already
 *     game-relative (carries a ready-made {@code Data/<Expansion>}). It has no About/About.xml, so
 *     the ModImporter path can't handle it. (We only accept zips — see {@link C.mime#GAME_ARCHIVE}.)
 */
public final class ContentInstaller {
    private static final String TAG = "RimDroid/Content";

    private ContentInstaller() {}

    /** After the user has picked a zip (SAF), choose target instance + Mod/DLC, then install. */
    public static void showTargetDialog(Activity act, Uri zipUri) {
        showTargetDialog(act, Collections.singletonList(zipUri));
    }

    /** After the user has picked zip file(s) (SAF), choose target instance + Mod/DLC, then install. */
    public static void showTargetDialog(Activity act, List<Uri> zipUris) {
        if (zipUris == null || zipUris.isEmpty()) return;
        final List<GameInstance> instances = GameInstanceManager.requireSingleton().getInstances();
        if (instances.isEmpty()) {
            Toast.makeText(act, "No instances yet — download or install the game first.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        float dp = act.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);

        box.addView(label(act, zipUris.size() > 1 ? "Files" : "File"));
        TextView fileTv = new TextView(act);
        fileTv.setText(displayNames(act, zipUris));
        box.addView(fileTv);

        box.addView(label(act, "Install into instance"));
        final Spinner spInst = new Spinner(act);
        List<String> names = new ArrayList<>();
        for (GameInstance gi : instances) names.add(gi.getName());
        spInst.setAdapter(adapter(act, names));
        box.addView(spInst);

        box.addView(label(act, "Type"));
        final RadioGroup rg = new RadioGroup(act);

        final RadioButton rbMod = new RadioButton(act);
        rbMod.setId(View.generateViewId());
        rbMod.setText("Mod → Mods/");

        final RadioButton rbDlc = new RadioButton(act);
        rbDlc.setId(View.generateViewId());
        rbDlc.setText("DLC → Data/");

        rg.addView(rbMod);
        rg.addView(rbDlc);
        rg.check(rbMod.getId());

        box.addView(rg);

        // A zipped GOG installer places its own content (Data/<Expansion>), so the choice above is
        // ignored for it — say so rather than let the picked radio look meaningful.
        TextView hint = new TextView(act);
        hint.setText("A zipped GOG DLC installer (.sh inside a .zip) is detected automatically "
                + "and installs itself into Data/.");
        hint.setPadding(0, (int) (8 * dp), 0, 0);
        box.addView(hint);

        new MaterialAlertDialogBuilder(act)
                .setTitle("Install mod / DLC")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Install", (d, w) -> {
                    GameInstance inst = instances.get(spInst.getSelectedItemPosition());
                    boolean intoData = rg.getCheckedRadioButtonId() == rbDlc.getId();
                    install(act, zipUris, inst, intoData);
                })
                .show();
    }

    /** Copy the picked zip to cache, then import it into the instance's Mods or Data folder. */
    public static void install(Activity act, Uri zipUri, GameInstance instance, boolean intoData) {
        install(act, Collections.singletonList(zipUri), instance, intoData);
    }

    /** Copy the picked zip file(s) to cache, then import them into the instance's Mods or Data folder. */
    public static void install(Activity act, List<Uri> zipUris, GameInstance instance, boolean intoData) {
        if (zipUris == null || zipUris.isEmpty()) return;
        final Handler main = new Handler(Looper.getMainLooper());
        final File destDir = new File(instance.getGamePath(), intoData ? "Data" : "Mods");
        final int totalFiles = zipUris.size();
        Toast.makeText(act, totalFiles > 1 ? "Installing " + totalFiles + " files…" : "Installing…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            final List<String> allImported = new ArrayList<>();
            final List<String> allErrors = new ArrayList<>();

            int index = 0;
            for (Uri zipUri : zipUris) {
                String fileName = displayName(act, zipUri);
                String fallback = stripExt(fileName);
                File cache = new File(act.getCacheDir(), "import_content_" + (index++) + ".zip");
                try (InputStream in = act.getContentResolver().openInputStream(zipUri);
                     FileOutputStream out = new FileOutputStream(cache)) {
                    if (in == null) throw new java.io.IOException("Unable to open input stream");
                    byte[] b = new byte[65536];
                    int n;
                    // Stop only on EOF (-1) — read() may return 0 without EOF, and ">0" would truncate
                    // the cached copy of a large pack (caused a patch file to land as 0 bytes).
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                } catch (Exception e) {
                    allErrors.add(fileName + ": Read failed - " + e.getMessage());
                    //noinspection ResultOfMethodCallIgnored
                    cache.delete();
                    continue;
                }

                if (GogInstallerExtractor.looksLikeGogBundle(cache)) {
                    installGogInstaller(act, cache, instance, fileName, allImported, allErrors);
                    continue;
                }

                ModImporter.Result r = ModImporter.importZip(cache, destDir, fallback);
                //noinspection ResultOfMethodCallIgnored
                cache.delete();

                if (r.ok()) {
                    allImported.addAll(r.imported);
                } else {
                    if (!r.imported.isEmpty()) {
                        allImported.addAll(r.imported);
                    }
                    for (String err : r.errors) {
                        allErrors.add(totalFiles > 1 ? fileName + ": " + err : err);
                    }
                }
            }

            main.post(() -> {
                String msg;
                if (allErrors.isEmpty() && !allImported.isEmpty()) {
                    msg = "Installed: " + String.join(", ", allImported)
                            + " → " + (intoData ? "Data" : "Mods") + " of " + instance.getName();
                } else if (!allImported.isEmpty()) {
                    msg = "Installed " + String.join(", ", allImported)
                            + "\n\nErrors:\n" + String.join("\n", allErrors);
                } else {
                    msg = "Install failed:\n" + String.join("\n", allErrors.isEmpty() ? Collections.singletonList("Unknown error") : allErrors);
                }
                new MaterialAlertDialogBuilder(act)
                        .setTitle(totalFiles > 1 ? "Installation Complete" : "Install mod / DLC")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show();
            });
        }, "rd-content-install").start();
    }

    /** Install a GOG installer (a single {@code .sh}, or a zip bundling installers) into
     *  {@code instance}: extract into the game root — its payload is game-relative, so a DLC's
     *  Data/&lt;Expansion&gt; lands where RimWorld expects it — and report the folders that appeared.
     *  Runs on the caller's worker thread. */
    private static void installGogInstaller(Activity act, File src, GameInstance instance,
                                           String fileName, List<String> allImported, List<String> allErrors) {
        File gameRoot = new File(instance.getGamePath());
        File dataDir  = new File(gameRoot, "Data");
        try {
            java.util.Set<String> before = listNames(dataDir);
            GogInstallerExtractor.extract(src, gameRoot, act.getCacheDir(), m -> Log.i(TAG, m));
            java.util.Set<String> added = listNames(dataDir);
            added.removeAll(before);
            if (added.isEmpty()) {
                allImported.add(fileName + " (updated files)");
            } else {
                allImported.addAll(added);
            }
        } catch (Exception e) {
            Log.e(TAG, "GOG install failed", e);
            allErrors.add(fileName + ": GOG install failed - " + e.getMessage());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            src.delete();
        }
    }

    /** Names directly inside {@code dir} (empty if absent) — used to diff what an installer added. */
    private static java.util.Set<String> listNames(File dir) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        String[] list = dir.list();
        if (list != null) java.util.Collections.addAll(names, list);
        return names;
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {}
        String seg = uri.getLastPathSegment();
        return seg != null ? seg : "mod.zip";
    }

    private static String displayNames(Context context, List<Uri> zipUris) {
        if (zipUris.size() == 1) return displayName(context, zipUris.get(0));
        StringBuilder sb = new StringBuilder();
        sb.append(zipUris.size()).append(" files selected:\n");
        for (Uri uri : zipUris) {
            sb.append("• ").append(displayName(context, uri)).append("\n");
        }
        return sb.toString().trim();
    }

    private static TextView label(Activity act, String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setPadding(0, (int) (12 * act.getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private static ArrayAdapter<String> adapter(Activity act, List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(act, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
