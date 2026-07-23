package com.rimdroid;

import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * What this instance last had in sync with Steam Cloud — one small json per instance.
 *
 * It exists to answer a question that is otherwise UNANSWERABLE: a save that is in the cloud but not
 * in this instance's Saves/ folder can mean two opposite things.
 *   • the user deleted it here  → the deletion should travel to the cloud (what Steam does)
 *   • it was never here         → it belongs to another instance or to the PC, and must be left alone
 *
 * Without this record the two are indistinguishable, and guessing "deleted" would let a sync from an
 * instance holding two saves wipe every other colony out of the cloud — and then off the PC on its
 * next sync. Guessing "never here" is the safe half, but then a save deleted on the PC comes back
 * from the phone every time (the zombie-save problem).
 *
 * So: only names recorded here are ever considered for deletion, and only while they are missing
 * locally. Anything unknown to us is somebody else's and stays untouched. Losing this file is
 * harmless — it degrades to additive-only sync, never to deletion.
 */
public final class CloudSyncState {

    private static final String TAG = "RimDroid/CloudSync";
    private static final String FILE = "rd_cloud_sync.json";

    /** filename -> SHA-1 (hex) of the copy that both sides agreed on at the last sync. */
    private final Map<String, String> synced;
    private final File file;

    private CloudSyncState(File file, Map<String, String> synced) {
        this.file = file;
        this.synced = synced;
    }

    /** Read the record for an instance (an empty one if there is none yet, or it is unreadable). */
    public static CloudSyncState load(String instanceName) {
        File f = new File(AppStorage.requireSingleton().getInstanceDir(instanceName), FILE);
        Map<String, String> m = new HashMap<>();
        if (f.isFile()) {
            try (java.io.Reader r = new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                java.lang.reflect.Type t =
                        new com.google.gson.reflect.TypeToken<HashMap<String, String>>() {}.getType();
                Map<String, String> parsed = new com.google.gson.Gson().fromJson(r, t);
                if (parsed != null) m.putAll(parsed);
            } catch (Throwable t) {
                // A corrupt record must never block a sync — fall back to "we know nothing",
                // which is the additive-only behaviour, i.e. nothing gets deleted.
                Log.w(TAG, "unreadable sync state, starting fresh: " + t);
            }
        }
        return new CloudSyncState(f, m);
    }

    /** Names this instance is known to have had in sync with the cloud. */
    public Set<String> knownNames() { return synced.keySet(); }

    /** Did we sync this exact content before? */
    public boolean matches(String filename, String sha1Hex) {
        String s = synced.get(filename);
        return s != null && s.equalsIgnoreCase(sha1Hex);
    }

    public void remember(String filename, String sha1Hex) {
        if (filename != null && sha1Hex != null) synced.put(filename, sha1Hex);
    }

    public void forget(String filename) { synced.remove(filename); }

    public void save() {
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8)) {
            new com.google.gson.Gson().toJson(synced, w);
        } catch (Throwable t) {
            // Best effort: a state we failed to write just means the next sync is additive-only.
            Log.w(TAG, "could not write sync state: " + t);
        }
    }
}
