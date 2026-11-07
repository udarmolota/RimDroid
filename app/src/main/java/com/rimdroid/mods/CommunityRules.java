package com.rimdroid.mods;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Load-order rules players collected for mods whose About.xml lacks them: RimSort's Community Rules
 * Database (github.com/RimSort/Community-Rules-Database, CC0). A snapshot ships in the APK
 * (assets/mods/communityRules.json); {@link #refreshInBackground} keeps a newer copy in the app's
 * files once a day when there is a network, and {@link #load} prefers that copy.
 */
public final class CommunityRules {
    private static final String TAG = "RimDroid/Mods";
    private static final String URL_RULES =
            "https://raw.githubusercontent.com/RimSort/Community-Rules-Database/main/communityRules.json";
    private static final String ASSET = "mods/communityRules.json";
    private static final long REFRESH_MS = 24L * 60 * 60 * 1000;

    /** One mod's rules, packageIds in lower case. */
    public static final class Rule {
        public final Set<String> loadBefore = new LinkedHashSet<>(), loadAfter = new LinkedHashSet<>();
        public final Set<String> incompatibleWith = new LinkedHashSet<>();
        public boolean loadTop, loadBottom;
    }

    private static volatile boolean refreshing;

    private static File cacheFile(Context context) {
        return new File(context.getFilesDir(), "mods/communityRules.json");
    }

    /** packageId → rule; empty when neither the downloaded copy nor the snapshot can be read. */
    public static Map<String, Rule> load(Context context) {
        File cached = cacheFile(context);
        if (cached.isFile()) {
            try (InputStream in = new FileInputStream(cached)) {
                return parse(read(in));
            } catch (Exception e) {
                Log.w(TAG, "Downloaded community rules unreadable, using the bundled copy: " + e);
            }
        }
        try (InputStream in = context.getAssets().open(ASSET)) {
            return parse(read(in));
        } catch (Exception e) {
            Log.w(TAG, "Bundled community rules unreadable: " + e);
            return Collections.emptyMap();
        }
    }

    /** Downloads a newer database when the kept one is a day old; silent on any failure. */
    public static void refreshInBackground(Context context) {
        File cached = cacheFile(context);
        if (refreshing || (cached.isFile() && System.currentTimeMillis() - cached.lastModified() < REFRESH_MS)) return;
        refreshing = true;
        new Thread(() -> {
            try {
                byte[] data = download();
                parse(data);   // keep only a copy that reads
                File dir = cached.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);
                File tmp = new File(dir, cached.getName() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); }
                if (!tmp.renameTo(cached)) throw new java.io.IOException("cannot replace " + cached);
                Log.i(TAG, "Community rules updated (" + data.length + " bytes)");
            } catch (Exception e) {
                Log.i(TAG, "Community rules not updated: " + e);
            } finally {
                refreshing = false;
            }
        }, "community-rules").start();
    }

    static Map<String, Rule> parse(byte[] json) throws Exception {
        JSONObject rules = new JSONObject(new String(json, StandardCharsets.UTF_8)).getJSONObject("rules");
        Map<String, Rule> out = new HashMap<>();
        for (Iterator<String> it = rules.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject o = rules.optJSONObject(id);
            if (o == null) continue;
            Rule r = new Rule();
            keys(o.optJSONObject("loadBefore"), r.loadBefore);
            keys(o.optJSONObject("loadAfter"), r.loadAfter);
            keys(o.optJSONObject("incompatibleWith"), r.incompatibleWith);
            r.loadTop = flag(o, "loadTop");
            r.loadBottom = flag(o, "loadBottom");
            out.put(id.toLowerCase(Locale.ROOT), r);
        }
        return out;
    }

    private static void keys(JSONObject o, Set<String> into) {
        if (o == null) return;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) into.add(it.next().trim().toLowerCase(Locale.ROOT));
    }

    /** {@code "loadBottom": {"value": true}} */
    private static boolean flag(JSONObject o, String name) {
        JSONObject f = o.optJSONObject(name);
        return f != null && f.optBoolean("value", false);
    }

    private static byte[] download() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_RULES).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            return read(in);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private CommunityRules() {}
}
