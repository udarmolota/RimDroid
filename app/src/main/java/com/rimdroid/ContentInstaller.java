package com.rimdroid;

import android.app.Activity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.rimdroid.game.GameInstance;
import com.rimdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Installs a picked file (mod or DLC) into a chosen instance. One unified flow: the user picks the
 * file from anywhere on the phone (SAF — no storage permission needed, works for the /Download/RimDroid
 * zips too), then picks the target instance and Mod/DLC.
 *   • Mod zip → instance/Mods/&lt;folder&gt;
 *   • DLC zip → instance/Data/&lt;folder&gt;   (where RimWorld expects official expansions)
 * Both reuse {@link ModImporter} (finds the About/About.xml root and strips wrappers like the depot's
 * {@code Data/} folder).
 *   • GOG DLC installer (.sh) → extracted by {@link GogInstallerExtractor} into the instance root,
 *     since its payload is already game-relative (carries a ready-made {@code Data/<Expansion>}).
 *     It has no About/About.xml, so the ModImporter path can't handle it.
 */
public final class ContentInstaller {
    private static final String TAG = "RimDroid/Content";

    private ContentInstaller() {}

    /** After the user has picked a zip (SAF), choose target instance + Mod/DLC, then install. */
    public static void showTargetDialog(Activity act, Uri zipUri) {
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

        box.addView(label(act, "File"));
        TextView fileTv = new TextView(act);
        fileTv.setText(displayName(act, zipUri));
        box.addView(fileTv);

        box.addView(label(act, "Install into instance"));
        final Spinner spInst = new Spinner(act);
        List<String> names = new ArrayList<>();
        for (GameInstance gi : instances) names.add(gi.getName());
        spInst.setAdapter(adapter(act, names));
        box.addView(spInst);

        box.addView(label(act, "Type"));
        final RadioGroup rg = new RadioGroup(act);
        final RadioButton rbMod = new RadioButton(act); rbMod.setText("Mod → Mods/"); rbMod.setId(1);
        final RadioButton rbDlc = new RadioButton(act); rbDlc.setText("DLC → Data/"); rbDlc.setId(2);
        rg.addView(rbMod); rg.addView(rbDlc); rg.check(1);
        box.addView(rg);

        // A GOG .sh installer places its own content (Data/<Expansion>), so the choice above is
        // ignored for it — say so rather than let the picked radio look meaningful.
        TextView hint = new TextView(act);
        hint.setText("A GOG DLC installer (.sh) is detected automatically and installs itself into Data/.");
        hint.setPadding(0, (int) (8 * dp), 0, 0);
        box.addView(hint);

        new MaterialAlertDialogBuilder(act)
                .setTitle("Install mod / DLC")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Install", (d, w) -> {
                    GameInstance inst = instances.get(spInst.getSelectedItemPosition());
                    boolean intoData = rg.getCheckedRadioButtonId() == 2;
                    install(act, zipUri, inst, intoData);
                })
                .show();
    }

    /** Copy the picked zip to cache, then import it into the instance's Mods or Data folder. */
    public static void install(Activity act, Uri zipUri, GameInstance instance, boolean intoData) {
        final Handler main = new Handler(Looper.getMainLooper());
        final File destDir = new File(instance.getGamePath(), intoData ? "Data" : "Mods");
        final String fallback = stripExt(displayName(act, zipUri));
        Toast.makeText(act, "Installing…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            File cache = new File(act.getCacheDir(), "import_content.zip");
            try (InputStream in = act.getContentResolver().openInputStream(zipUri);
                 FileOutputStream out = new FileOutputStream(cache)) {
                byte[] b = new byte[65536];
                int n;
                // Stop only on EOF (-1) — read() may return 0 without EOF, and ">0" would truncate
                // the cached copy of a large pack (caused a patch file to land as 0 bytes).
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            } catch (Exception e) {
                main.post(() -> Toast.makeText(act, "Read failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }
            // A GOG .sh installer is NOT a mod zip: it has no About/About.xml (ModImporter would fail
            // with "missing About/About.xml"), and its payload is already game-root-relative — a DLC
            // installer carries a ready-made Data/<Expansion>. So extract it into the instance ROOT
            // and let its own layout place the content; the Mod/DLC choice doesn't apply.
            if (GogInstallerExtractor.looksLikeGogBundle(cache)) {
                installGogInstaller(act, cache, instance, main);
                return;
            }

            ModImporter.Result r = ModImporter.importZip(cache, destDir, fallback);
            //noinspection ResultOfMethodCallIgnored
            cache.delete();
            main.post(() -> {
                String msg;
                if (r.ok()) {
                    msg = "Installed: " + String.join(", ", r.imported)
                            + " → " + (intoData ? "Data" : "Mods") + " of " + instance.getName();
                } else if (!r.imported.isEmpty()) {
                    msg = "Installed " + r.imported + ", with errors: " + String.join("; ", r.errors);
                } else {
                    msg = "Install failed: " + String.join("; ", r.errors);
                }
                new MaterialAlertDialogBuilder(act).setMessage(msg).setPositiveButton("OK", null).show();
            });
        }, "rd-content-install").start();
    }

    /** Install a GOG installer (a single {@code .sh}, or a zip bundling installers) into
     *  {@code instance}: extract into the game root — its payload is game-relative, so a DLC's
     *  Data/&lt;Expansion&gt; lands where RimWorld expects it — and report the folders that appeared.
     *  Runs on the caller's worker thread. */
    private static void installGogInstaller(Activity act, File src, GameInstance instance, Handler main) {
        File gameRoot = new File(instance.getGamePath());
        File dataDir  = new File(gameRoot, "Data");
        try {
            java.util.Set<String> before = listNames(dataDir);
            GogInstallerExtractor.extract(src, gameRoot, act.getCacheDir(), m -> Log.i(TAG, m));
            java.util.Set<String> added = listNames(dataDir);
            added.removeAll(before);
            String what = added.isEmpty()
                    ? "existing files updated (no new expansion folder)"
                    : String.join(", ", added);
            alert(act, main, "Installed: " + what + " → Data of " + instance.getName());
        } catch (Exception e) {
            Log.e(TAG, "GOG install failed", e);
            alert(act, main, "Install failed: " + e.getMessage());
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

    private static void alert(Activity act, Handler main, String msg) {
        main.post(() -> new MaterialAlertDialogBuilder(act)
                .setMessage(msg).setPositiveButton("OK", null).show());
    }

    private static String displayName(Activity act, Uri uri) {
        try (Cursor c = act.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {}
        String seg = uri.getLastPathSegment();
        return seg != null ? seg : "mod.zip";
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
