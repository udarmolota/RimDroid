package com.rimdroid;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pins RimWorld's {@code Config/Prefs.xml} to fullscreen at a specific internal
 * resolution before each launch.
 *
 * <p>Why: RimWorld re-applies its saved Prefs resolution shortly after startup,
 * overriding our {@code -screen-width}/{@code -screen-height} launch args. On a fresh
 * profile that saved value is the Unity default {@code 1024x768} (4:3), so the game
 * renders into a 4:3 sub-rectangle of our (much wider) surface — a tiny corner window
 * when {@code fullscreen=False}, or stretched/squished when {@code fullscreen=True}.
 *
 * <p>Fix: write {@code fullscreen=True} plus {@code screenWidth}/{@code screenHeight}
 * equal to the EXACT render buffer we hand the game (surface size * render scale).
 * Same aspect ratio as the surface, so RimWorld's fullscreen scale-to-fill is 1:1 with
 * no distortion. RimWorld may rewrite {@code fullscreen=False} on exit (we launch
 * windowed), so this must run on EVERY launch, not once.
 *
 * <p>We only touch those three fields: present fields are replaced in place, missing
 * ones are inserted, and everything else RimWorld manages stays untouched. If the file
 * does not exist yet (first run) a minimal {@code <prefs>} file is created — RimWorld's
 * Scribe loader fills every other field with its defaults.
 */
public final class PrefsXml {

    private static final String TAG = "RimDroid/PrefsXml";

    private PrefsXml() {}

    /**
     * @param configDir the instance's {@code .../RimWorld by Ludeon Studios/Config} dir
     * @param width     internal render width  (= surface width  * render scale)
     * @param height    internal render height (= surface height * render scale)
     */
    public static void forceFullscreen(File configDir, int width, int height) {
        if (configDir == null || width <= 0 || height <= 0) return;
        File f = new File(configDir, "Prefs.xml");
        try {
            String xml;
            if (f.isFile()) {
                xml = new String(readAll(f), StandardCharsets.UTF_8);
                xml = setTag(xml, "screenWidth",  Integer.toString(width));
                xml = setTag(xml, "screenHeight", Integer.toString(height));
                xml = setTag(xml, "fullscreen",   "True");
            } else {
                xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<prefs>\n"
                    + "  <screenWidth>"  + width  + "</screenWidth>\n"
                    + "  <screenHeight>" + height + "</screenHeight>\n"
                    + "  <fullscreen>True</fullscreen>\n"
                    + "</prefs>\n";
            }
            writeAll(f, xml.getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "Prefs pinned: " + width + "x" + height + " fullscreen=True (" + f + ")");
        } catch (Exception e) {
            // Non-fatal: at worst the game starts at its own resolution.
            Log.w(TAG, "forceFullscreen failed for " + f + ": " + e.getMessage());
        }
    }

    /**
     * Pin ONLY {@code fullscreen=True} — leave the user's chosen resolution untouched. Run on EVERY
     * launch: changing the window resolution inside RimWorld makes it save {@code fullscreen=False},
     * which drops the NEXT launch into a windowed sub-rectangle / black screen. Unlike
     * {@link #forceFullscreen}, this does NOT force a resolution, so it's safe on weak GPUs (forcing
     * the resolution there raised the internal res and broke boot — why the full pin is GPU-gated).
     */
    public static void forceFullscreenOnly(File configDir) {
        if (configDir == null) return;
        File f = new File(configDir, "Prefs.xml");
        try {
            String xml;
            if (f.isFile()) {
                xml = new String(readAll(f), StandardCharsets.UTF_8);
                xml = setTag(xml, "fullscreen", "True");
            } else {
                xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<prefs>\n  <fullscreen>True</fullscreen>\n</prefs>\n";
            }
            writeAll(f, xml.getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "Prefs pinned: fullscreen=True (" + f + ")");
        } catch (Exception e) {
            Log.w(TAG, "forceFullscreenOnly failed for " + f + ": " + e.getMessage());
        }
    }

    /** Replace {@code <key>...</key>} with the new value, or insert before {@code </prefs>}. */
    private static String setTag(String xml, String key, String value) {
        Pattern p = Pattern.compile("<" + key + ">.*?</" + key + ">", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        String tag = "<" + key + ">" + value + "</" + key + ">";
        if (m.find()) {
            return m.replaceFirst(Matcher.quoteReplacement(tag));
        }
        int idx = xml.lastIndexOf("</prefs>");
        if (idx >= 0) {
            return xml.substring(0, idx) + "  " + tag + "\n" + xml.substring(idx);
        }
        return xml + "\n" + tag;   // malformed/empty file — append as a best effort
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
