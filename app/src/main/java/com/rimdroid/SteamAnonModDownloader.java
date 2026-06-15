/*
 * SPDX-License-Identifier: MIT
 *
 * Anonymous Steam Workshop mod downloader — original mechanism by udarmolota for RimDroid.
 * Copyright (c) 2026 udarmolota
 *
 * NOTE: This single file is licensed under the MIT License, NOT under the GPL-3.0 that covers the
 * rest of RimDroid. This is intentional: the author reuses this mechanism across her own projects
 * (e.g. Zomdroid), and others are welcome to reuse it too — the only condition is that this
 * copyright and permission notice is preserved. MIT is GPL-compatible, so keeping this file MIT
 * does not affect the GPL-3.0 licensing of the rest of the project.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following condition: the above copyright notice and this
 * permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.rimdroid;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import in.dragonbra.javasteam.enums.EDepotFileFlag;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.cdn.Client;
import in.dragonbra.javasteam.steam.cdn.Server;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSTokensCallback;
import in.dragonbra.javasteam.steam.handlers.steamcontent.CDNAuthToken;
import in.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.types.AsyncJobMultiple;
import in.dragonbra.javasteam.types.ChunkData;
import in.dragonbra.javasteam.types.DepotManifest;
import in.dragonbra.javasteam.types.FileData;
import in.dragonbra.javasteam.util.log.DefaultLogListener;
import in.dragonbra.javasteam.util.log.LogManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import kotlinx.coroutines.Deferred;
import kotlinx.coroutines.GlobalScope;

/**
 * ANONYMOUS in-app Workshop mod downloader (no Steam login). De-risked by SteamAnonModSpike: an
 * anonymous account CAN obtain the workshop depot key (confirmed keyLen=32 on RimWorld).
 *
 * App-AGNOSTIC: we read the mod's owning game from the item itself (GetPublishedFileDetails →
 * consumer_app_id), so this downloads ANY public Workshop item, not just RimWorld — same reach as the
 * ggntw browser path, but in-app and no login. (Items that require ownership return EResult != OK on
 * the depot key → those fall back to the ggntw browser path.)
 *
 * <p><b>Why this is a manual SteamPipe download instead of JavaSteam's high-level DepotDownloader:</b>
 * DepotDownloader 1.8.0's {@code processPublishedFile} skips any item whose EWorkshopFileType isn't in
 * its supported set — but the set holds {@code EWorkshopFileType.Community} while {@code from(0)} returns
 * the DISTINCT enum constant {@code First} (same code 0). Community is the type of essentially every
 * normal mod, so DepotDownloader logs "unsupported file type First. Skipping file" and downloads
 * nothing. We therefore drive the lower-level pieces ourselves (resolve workshop depot via PICS, get the
 * depot key, pick a CDN server, get the manifest request code, download + decrypt the manifest, then
 * download + decrypt + decompress each chunk) — all reusing JavaSteam's CDN {@link Client} and crypto,
 * just skipping the buggy file-type gate.
 *
 * Output: each item packed into /Download/RimDroid/workshop_&lt;id&gt;.zip (the smart ModImporter unwraps
 * it into an instance later). Run on a background thread (blocks in the callback loop + awaits).
 */
public class SteamAnonModDownloader implements Runnable, Cancellable {

    private static final String TAG = "RimDroid/AnonMod";

    public interface Listener {
        void onProgress(String message);
        default void onPercent(int percent) {}
        void onDone(String message);
    }

    /** Minimal resolved info from GetPublishedFileDetails. */
    private static final class PubInfo {
        int consumerAppId;
        long hcontentFile;     // SteamPipe content manifest GID
        String title = "(workshop item)";
        boolean ok;
    }

    private final List<Long> workshopIds;
    private final Listener listener;

    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;
    private volatile boolean running;
    private volatile boolean started;       // anonymous logon succeeded → real work began
    private int connectAttempts = 0;
    private static final int MAX_CONNECT_ATTEMPTS = 5;
    private volatile Thread workerThread;   // the download thread (interrupted on cancel)
    private volatile boolean doneEmitted;   // ensure exactly one terminal onDone
    private volatile String lastTitle;   // resolved mod title, used for an informative zip name

    public SteamAnonModDownloader(List<Long> workshopIds, Listener listener) {
        this.workshopIds = workshopIds;
        this.listener = listener;
    }

    private void progress(String m) { Log.i(TAG, m); if (listener != null) listener.onProgress(m); }
    private void done(String m)     {
        if (doneEmitted) return;    // exactly one terminal message (cancel can race the natural finish)
        doneEmitted = true;
        Log.i(TAG, "DONE: " + m);
        if (listener != null) listener.onDone(m);
    }
    private void percent(int p)     { if (listener != null) listener.onPercent(p); }

    /** {@link Cancellable}: stop the running download — break the chunk loop + its blocking I/O. */
    @Override
    public void cancel() {
        running = false;                     // chunk/file loops check this and bail
        Thread w = workerThread;
        if (w != null) w.interrupt();        // break the chunk .get() / Deferred wait
        try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        done("Download cancelled.");
    }

    @Override
    public void run() {
        try {
            LogManager.addListener(new DefaultLogListener());
            steamClient = new SteamClient();
            manager = new CallbackManager(steamClient);
            steamUser = steamClient.getHandler(SteamUser.class);
            manager.subscribe(ConnectedCallback.class, this::onConnected);
            manager.subscribe(DisconnectedCallback.class, this::onDisconnected);
            manager.subscribe(LoggedOnCallback.class, this::onLoggedOn);
            running = true;
            progress("Connecting to Steam (anonymous)...");
            steamClient.connect();
            while (running) manager.runWaitCallbacks(1000L);
            Log.i(TAG, "Anon downloader loop ended");
            // Safety net: if the loop ended without any terminal result (e.g. Steam dropped the
            // connection before anonymous logon), still fire done() so the UI leaves the
            // "downloading" state and the keep-alive service is stopped — otherwise it looks frozen.
            if (!doneEmitted) done("Stopped before finishing — please try again.");
        } catch (Throwable t) {
            if (running) {
                Log.e(TAG, "SteamAnonModDownloader crashed", t);
                done("crash: " + t);
            } else {
                Log.i(TAG, "Callback loop stopped: " + t);
                if (!doneEmitted) done("Stopped.");
            }
        }
    }

    private void onConnected(ConnectedCallback cb) {
        progress("Connected. Logging on anonymously (no account)...");
        steamUser.logOnAnonymous();
    }

    private void onDisconnected(DisconnectedCallback cb) {
        if (!running) return;
        // Steam often drops the first connect attempt before logon — retry a few times before
        // giving up, otherwise a transient drop kills the whole download.
        if (!started && connectAttempts < MAX_CONNECT_ATTEMPTS) {
            connectAttempts++;
            progress("Connection dropped — retrying (" + connectAttempts + "/" + MAX_CONNECT_ATTEMPTS + ")...");
            try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
            if (!running) return;   // cancelled while waiting to reconnect
            steamClient.connect();
            return;
        }
        if (!started) {
            done("Could not connect to Steam after " + connectAttempts
                    + " attempts. Check your connection and try again.");
        } else {
            progress("Disconnected.");
        }
        running = false;
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("Anonymous logon failed: " + cb.getResult());
            running = false;
            return;
        }
        started = true;              // real work begins; stop treating disconnects as pre-logon retries
        Thread t = new Thread(this::downloadAll, "rd-anon-mod-dl");
        workerThread = t;            // tracked so cancel() can interrupt the chunk download
        t.start();
    }

    private void downloadAll() {
        AppStorage storage = AppStorage.requireSingleton();
        File downloadsDir = storage.getDownloadsDir();
        File tmpRoot = new File(storage.getCachePath(), "anon_mod_work");
        Client cdn = new Client(steamClient);
        int ok = 0, skipped = 0;
        try {
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                done("Cannot create downloads folder: " + downloadsDir + " (grant All-files access?)");
                return;
            }
            for (Long id : workshopIds) {
                if (!running) { progress("Aborted (disconnected)."); break; }
                progress("=== Workshop item " + id + " (anonymous) ===");
                File work = new File(tmpRoot, String.valueOf(id));
                deleteRecursive(work);
                if (!work.mkdirs()) { progress("✗ " + id + ": cannot create work dir"); skipped++; continue; }

                try {
                    if (downloadOne(cdn, id, work) && containsAboutXml(work)) {
                        // Informative name: "<Mod Title>_<id>.zip" (id kept for uniqueness/traceability).
                        File zip = new File(downloadsDir, sanitizeFileName(lastTitle) + "_" + id + ".zip");
                        ZipUtil.zipDir(work, zip);
                        progress("✓ " + id + " → " + zip.getAbsolutePath());
                        ok++;
                    } else {
                        progress("✗ " + id + " — nothing usable downloaded "
                                + "(if it needs ownership, use the browser/ggntw option).");
                        skipped++;
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "item " + id + " failed", t);
                    progress("✗ " + id + " — " + describe(t)
                            + " (if it needs ownership, use the browser/ggntw option).");
                    skipped++;
                }
                deleteRecursive(work);
            }
            done("Mods done: " + ok + " downloaded, " + skipped + " skipped. Saved to " + downloadsDir);
        } catch (Throwable t) {
            Log.e(TAG, "downloadAll crashed", t);
            done("error: " + describe(t));
        } finally {
            running = false;
            try { steamUser.logOff(); } catch (Throwable ignored) {}
        }
    }

    /** Full manual SteamPipe download of one Workshop item into {@code work}. */
    private boolean downloadOne(Client cdn, long id, File work) throws Exception {
        PubInfo info = resolvePublishedFile(id);
        lastTitle = info.title;
        if (!info.ok || info.consumerAppId <= 0 || info.hcontentFile == 0) {
            progress("✗ " + id + ": not found / no content manifest.");
            return false;
        }
        int appId = info.consumerAppId;
        progress("item '" + info.title + "' → app " + appId + ", manifest " + info.hcontentFile);

        int depot = resolveWorkshopDepot(appId);
        if (depot <= 0) { progress("✗ could not resolve workshop depot for app " + appId); return false; }

        byte[] depotKey = getDepotKey(depot, appId);
        if (depotKey == null) {
            progress("✗ no depot key (item likely needs game ownership).");
            return false;
        }

        SteamContent content = steamClient.getHandler(SteamContent.class);
        List<Server> servers = awaitDeferred(
                content.getServersForSteamPipe(null, null, GlobalScope.INSTANCE), 30000);
        if (servers == null || servers.isEmpty()) { progress("✗ no CDN servers"); return false; }

        long requestCode = awaitDeferred(
                content.getManifestRequestCode(depot, appId, info.hcontentFile, "public", null,
                        GlobalScope.INSTANCE), 30000);

        // Try servers in order until one serves the manifest; remember which one worked.
        DepotManifest manifest = null;
        Exception lastErr = null;
        Map<String, String> tokenCache = new HashMap<>();
        int firstGood = -1;
        for (int i = 0; i < servers.size(); i++) {
            Server s = servers.get(i);
            try {
                manifest = cdn.downloadManifestFuture(depot, info.hcontentFile, requestCode, s,
                        depotKey, null, cdnTokenFor(content, appId, depot, s, tokenCache))
                        .get(90, TimeUnit.SECONDS);
                firstGood = i;
                break;
            } catch (Exception e) {
                lastErr = e;
                Log.w(TAG, "manifest via " + s.getHost() + " failed: " + describe(e));
            }
        }
        if (manifest == null) {
            progress("✗ manifest download failed: " + describe(lastErr));
            return false;
        }

        List<FileData> files = manifest.getFiles();
        long totalBytes = 0;
        for (FileData f : files) {
            if (!f.getFlags().contains(EDepotFileFlag.Directory)) totalBytes += f.getTotalSize();
        }
        progress("manifest OK: " + files.size() + " entries, "
                + (totalBytes / (1024 * 1024)) + " MB — downloading...");

        // CDN edge nodes throw transient 503s under load; rotate across the server list and retry per chunk.
        int serverIdx = Math.max(firstGood, 0);
        long doneBytes = 0;
        int lastPct = -1;
        for (FileData f : files) {
            if (!running) return false;
            String rel = sanitizeRel(f.getFileName());
            if (rel == null) continue;
            File out = new File(work, rel);
            if (f.getFlags().contains(EDepotFileFlag.Directory)) {
                //noinspection ResultOfMethodCallIgnored
                out.mkdirs();
                continue;
            }
            File parent = out.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();

            try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
                if (f.getTotalSize() > 0) raf.setLength(f.getTotalSize());
                for (ChunkData chunk : f.getChunks()) {
                    if (!running) return false;
                    // Buffer must be >= compressedLength (Future precondition); the decompressor writes
                    // uncompressedLength. Tiny/incompressible chunks can store larger than raw → take max.
                    byte[] dest = new byte[Math.max(chunk.getCompressedLength(), chunk.getUncompressedLength())];
                    int written = -1;
                    Exception chunkErr = null;
                    int tries = Math.min(Math.max(servers.size(), 4), 8);
                    for (int t = 0; t < tries && written < 0; t++) {
                        Server s = servers.get(serverIdx % servers.size());
                        try {
                            written = cdn.downloadDepotChunkFuture(depot, chunk, s, dest, depotKey, null,
                                    cdnTokenFor(content, appId, depot, s, tokenCache)).get(180, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            chunkErr = e;
                            Log.w(TAG, "chunk via " + s.getHost() + " failed (try " + (t + 1) + "): " + describe(e));
                            serverIdx++;                 // rotate to the next CDN server
                            Thread.sleep(400);
                        }
                    }
                    if (written < 0) {
                        throw chunkErr != null ? chunkErr : new java.io.IOException("chunk download failed");
                    }
                    raf.seek(chunk.getOffset());
                    raf.write(dest, 0, written);
                    doneBytes += written;
                    int pct = totalBytes > 0 ? (int) (doneBytes * 100 / totalBytes) : 0;
                    if (pct != lastPct) { percent(pct); lastPct = pct; }
                }
            }
        }
        progress("content fetched (" + (doneBytes / (1024 * 1024)) + " MB)");
        return true;
    }

    /** CDN auth token is optional for public content; fetch best-effort + cache per host, null on failure. */
    private String cdnTokenFor(SteamContent content, int appId, int depot, Server s, Map<String, String> cache) {
        String host = s.getHost() != null ? s.getHost() : s.getVHost();
        if (host == null) return null;
        if (cache.containsKey(host)) return cache.get(host);
        String token = null;
        try {
            CDNAuthToken tok = awaitDeferred(
                    content.getCDNAuthToken(appId, depot, host, GlobalScope.INSTANCE), 15000);
            if (tok != null && tok.getResult() == EResult.OK) token = tok.getToken();
        } catch (Throwable ignored) { /* public content usually doesn't need a token */ }
        cache.put(host, token);
        return token;
    }

    /** Get the AES depot key anonymously (proven viable for workshop depots). */
    private byte[] getDepotKey(int depot, int appId) {
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            DepotKeyCallback dk = apps.getDepotDecryptionKey(depot, appId)
                    .toFuture().get(30, TimeUnit.SECONDS);
            if (dk.getResult() == EResult.OK && dk.getDepotKey() != null && dk.getDepotKey().length == 32) {
                return dk.getDepotKey();
            }
            Log.w(TAG, "depot key result=" + dk.getResult());
        } catch (Throwable t) {
            Log.e(TAG, "getDepotKey failed", t);
        }
        return null;
    }

    /** PICS product info → depots.workshopdepot for the given app (anonymous public read). */
    private int resolveWorkshopDepot(int appId) {
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            long token = 0L;
            try {
                PICSTokensCallback tk = apps.picsGetAccessTokens(
                        Collections.singletonList(appId), Collections.<Integer>emptyList())
                        .toFuture().get(30, TimeUnit.SECONDS);
                Long t = tk.getAppTokens().get(appId);
                if (t != null) token = t;
            } catch (Throwable ignored) {}
            List<PICSRequest> reqs = Collections.singletonList(new PICSRequest(appId, token));
            AsyncJobMultiple.ResultSet<PICSProductInfoCallback> rs =
                    apps.picsGetProductInfo(reqs, Collections.<PICSRequest>emptyList())
                        .toFuture().get(60, TimeUnit.SECONDS);
            for (PICSProductInfoCallback cb : rs.getResults()) {
                PICSProductInfo app = cb.getApps().get(appId);
                if (app != null) {
                    return app.getKeyValues().get("depots").get("workshopdepot").asInteger(-1);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "resolveWorkshopDepot failed", t);
        }
        return -1;
    }

    /**
     * Resolve consumer_app_id + content-manifest GID via the public Web API — no login, no CM needed.
     * Keeps the downloader app-agnostic (we feed the right appId to the depot/manifest lookups).
     */
    private PubInfo resolvePublishedFile(long id) throws Exception {
        URL url = new URL("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String body = "itemcount=1&publishedfileids%5B0%5D=" + id;   // publishedfileids[0]=id
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
            }
            PubInfo pf = new PubInfo();
            JSONObject root = new JSONObject(sb.toString());
            JSONObject resp = root.optJSONObject("response");
            if (resp == null) return pf;
            JSONArray arr = resp.optJSONArray("publishedfiledetails");
            if (arr == null || arr.length() == 0) return pf;
            JSONObject d = arr.getJSONObject(0);
            pf.ok = d.optInt("result", 0) == 1;
            pf.consumerAppId = (int) d.optLong("consumer_app_id", 0L);
            pf.title = d.optString("title", "(workshop item)");
            String h = d.optString("hcontent_file", "");
            if (!h.isEmpty()) {
                try { pf.hcontentFile = Long.parseUnsignedLong(h); } catch (NumberFormatException ignored) {}
            }
            return pf;
        } finally {
            c.disconnect();
        }
    }

    /** Await a Kotlin Deferred from Java without coroutine interop: poll completion, then read it. */
    @SuppressWarnings("unchecked")
    private static <T> T awaitDeferred(Deferred<T> d, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!d.isCompleted()) {
            if (System.currentTimeMillis() > deadline) throw new TimeoutException("deferred timed out");
            Thread.sleep(40);
        }
        return (T) d.getCompleted();
    }

    /** Normalize a manifest path and reject traversal/absolute escapes. */
    private static String sanitizeRel(String name) {
        if (name == null) return null;
        String rel = name.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty() || rel.contains("../")) return null;
        return rel;
    }

    /** Make a Workshop title safe + tidy for a filename (drop illegal chars, collapse spaces, cap length). */
    private static String sanitizeFileName(String title) {
        if (title == null) return "workshop";
        String s = title.replaceAll("[\\\\/:*?\"<>|]", " ")   // illegal on common filesystems
                        .replaceAll("\\s+", " ")
                        .trim();
        if (s.length() > 60) s = s.substring(0, 60).trim();
        return s.isEmpty() ? "workshop" : s;
    }

    private static boolean containsAboutXml(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) {
            if (f.isDirectory()) {
                if (new File(f, "About/About.xml").exists()) return true;
                if (containsAboutXml(f)) return true;
            } else if (f.getName().equalsIgnoreCase("About.xml")) {
                return true;
            }
        }
        return false;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String describe(Throwable t) {
        if (t == null) return "null";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m != null ? (": " + m) : "");
    }
}
