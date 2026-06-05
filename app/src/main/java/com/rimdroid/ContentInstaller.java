package com.rimdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
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
 * Installs a picked .zip (mod or DLC) into a chosen instance. One unified flow: the user picks the
 * file from anywhere on the phone (SAF — no storage permission needed, works for the /Download/RimDroid
 * zips too), then picks the target instance and Mod/DLC. Reuses {@link ModImporter} (finds the
 * About/About.xml root and strips wrappers like the depot's {@code Data/} folder).
 *   • Mod  → instance/Mods/&lt;folder&gt;
 *   • DLC  → instance/Data/&lt;folder&gt;   (where RimWorld expects official expansions)
 */
public final class ContentInstaller {

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

        new AlertDialog.Builder(act)
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
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } catch (Exception e) {
                main.post(() -> Toast.makeText(act, "Read failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
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
                new AlertDialog.Builder(act).setMessage(msg).setPositiveButton("OK", null).show();
            });
        }, "rd-content-install").start();
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
