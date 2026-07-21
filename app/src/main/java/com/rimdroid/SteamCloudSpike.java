package com.rimdroid;

import android.util.Log;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.FileDownloadInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.HttpHeaders;
import in.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.util.log.DefaultLogListener;
import in.dragonbra.javasteam.util.log.LogManager;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * SPIKE — Steam Cloud cross-save, milestone 0: READ-ONLY enumeration.
 *
 * RimWorld DOES sync saves through Steam Cloud (store-page feature; confirmed working on PC),
 * which our June notes wrongly wrote off — see memory saves_settings_backup.md (corrected
 * 2026-07-21). JavaSteam 1.8.0 (already shipped for the in-app downloader) carries the full
 * Cloud service: changelist enumeration, file download, block upload.
 *
 * This spike answers the ONE open design question that gates the real feature: HOW does
 * RimWorld lay its files out in the cloud — what path prefixes/roots ({@code pathPrefixes}),
 * which files (Saves/*.rws? Config? ModsConfig.xml?), what sizes and timestamps. It logs the
 * complete file list and touches NOTHING: no downloads, no uploads, no instance writes.
 *
 * Auth = the proven credentials + Steam-Mobile-approval flow copied from
 * {@link SteamDownloadSpike} (same constraints: token kept in memory only, never persisted).
 * Run on a background thread (blocks in the callback loop).
 */
public class SteamCloudSpike implements Runnable, Cancellable {

    private static final String TAG = "RimDroid/SteamCloud";

    private static final int MAX_AUTH_ATTEMPTS = 3;

    /** One cloud file, in plain terms the UI can render without touching JavaSteam types. */
    public static final class CloudFile {
        public final String filename;      // e.g. "Autosave-1.rws" (no path prefix)
        public final long rawSize;         // bytes of the real .rws
        public final long timestampMs;     // when the cloud copy was written
        CloudFile(String filename, long rawSize, long timestampMs) {
            this.filename = filename; this.rawSize = rawSize; this.timestampMs = timestampMs;
        }
    }

    /** Adds the file listing to the shared progress/done listener. */
    public interface CloudListener extends SteamDownloadSpike.Listener {
        void onFileList(java.util.List<CloudFile> files);
    }

    private final String username, password;
    private final int appId;                              // which game's cloud to enumerate
    private final String instanceName;                    // null/empty = cache-only test; else pull into
                                                          // instances/<name>/…/Saves/
    private final boolean listOnly;                       // just enumerate and report, download nothing
    private final boolean upload;                         // push local saves TO the cloud instead
    private final java.util.Set<String> selected;         // null = every file; else only these filenames
    private final SteamDownloadSpike.Listener listener;   // same shape → same UI wiring

    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;

    // In-memory session (never persisted) — lets a transient reconnect skip the re-approval.
    private volatile String accountName;
    private volatile String refreshToken;

    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile boolean doneEmitted;
    private volatile boolean enumerationStarted;
    private volatile boolean enumerationCompleted;
    private int authAttempts;

    private SteamCloudSpike(String username, String password, int appId, String instanceName,
                            boolean listOnly, boolean upload, java.util.Set<String> selected,
                            SteamDownloadSpike.Listener listener) {
        this.username = username;
        this.password = password;
        this.appId = appId;
        this.instanceName = (instanceName == null || instanceName.trim().isEmpty())
                ? null : instanceName.trim();
        this.listOnly = listOnly;
        this.upload = upload;
        this.selected = selected;
        this.listener = listener;
    }

    /** Sign in and report what is in the cloud — downloads nothing. */
    public static SteamCloudSpike forList(String user, String pass, int appId, CloudListener l) {
        return new SteamCloudSpike(user, pass, appId, null, true, false, null, l);
    }

    /** Pull the named saves into the instance's Saves/ (existing files are never overwritten). */
    public static SteamCloudSpike forPull(String user, String pass, int appId, String instanceName,
                                          java.util.Set<String> filenames,
                                          SteamDownloadSpike.Listener l) {
        return new SteamCloudSpike(user, pass, appId, instanceName, false, false, filenames, l);
    }

    /** Push the named saves from the instance's Saves/ up to the cloud (replaces same-named files). */
    public static SteamCloudSpike forPush(String user, String pass, int appId, String instanceName,
                                          java.util.Set<String> filenames,
                                          SteamDownloadSpike.Listener l) {
        return new SteamCloudSpike(user, pass, appId, instanceName, false, true, filenames, l);
    }

    private void progress(String m) {
        Log.i(TAG, m);
        if (listener != null) listener.onProgress(m);
    }

    private void done(String m) {
        if (doneEmitted) return;
        doneEmitted = true;
        Log.i(TAG, "DONE: " + m);
        if (listener != null) listener.onDone(m);
    }

    @Override
    public void cancel() {
        cancelled = true;
        running = false;
        enumerationCompleted = true;   // stop the disconnect handler from reconnecting
        try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        done("Cancelled.");
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
            progress("Connecting to Steam...");
            steamClient.connect();
            while (running) {
                manager.runWaitCallbacks(1000L);
            }
            if (!doneEmitted) done("Stopped before finishing — please try again.");
        } catch (Throwable t) {
            Log.e(TAG, "SteamCloudSpike crashed", t);
            done("crash: " + t);
        } finally {
            // Same lesson as the downloader: never leave an orphaned SteamClient whose ktor
            // thread dies uncaught on a later network change and takes the whole app with it.
            try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void onConnected(ConnectedCallback cb) {
        try {
            if (refreshToken != null) {
                progress("Reconnected. Logging on with cached session...");
                logOnWithToken();
                return;
            }
            progress("Connected. Authenticating '" + username + "'...");
            authAttempts++;
            AuthSessionDetails details = new AuthSessionDetails();
            details.username = username;
            details.password = password;
            details.persistentSession = false;
            details.deviceFriendlyName = "RimDroid";
            details.authenticator = new PushAuthenticator();

            CredentialsAuthSession session =
                    new SteamAuthentication(steamClient).beginAuthSessionViaCredentials(details).get();
            progress("Approve the sign-in in your Steam Mobile app...");

            AuthPollResult poll = session.pollingWaitForResult().get();
            accountName = poll.getAccountName();
            refreshToken = poll.getRefreshToken();
            Log.i(TAG, "AUTH OK: account=" + accountName);
            logOnWithToken();
        } catch (Throwable t) {
            Log.e(TAG, "Auth failed", t);
            boolean connectionDropped =
                    (t instanceof java.util.concurrent.CancellationException)
                            || (t.getCause() instanceof java.util.concurrent.CancellationException);
            if (connectionDropped && !cancelled && authAttempts < MAX_AUTH_ATTEMPTS) {
                progress("Sign-in interrupted by a connection drop — reconnecting, approve again "
                        + "(attempt " + authAttempts + "/" + MAX_AUTH_ATTEMPTS + ")…");
            } else {
                done("auth error: " + t.getMessage());
                running = false;
            }
        }
    }

    private void logOnWithToken() {
        LogOnDetails lod = new LogOnDetails();
        lod.setUsername(accountName);
        lod.setAccessToken(refreshToken);
        lod.setLoginID(151);   // distinct from the downloader's 149 so the two can't collide
        progress("Logging in...");
        steamUser.logOn(lod);
    }

    private void onDisconnected(DisconnectedCallback cb) {
        Log.i(TAG, "Disconnected (userInitiated=" + cb.isUserInitiated() + ")");
        if (cb.isUserInitiated() || enumerationCompleted
                || (refreshToken == null && authAttempts >= MAX_AUTH_ATTEMPTS)) {
            running = false;
            if (!enumerationCompleted && !cb.isUserInitiated()) done("connection lost.");
            return;
        }
        progress("Connection lost — reconnecting...");
        enumerationStarted = false;
        try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
        if (!running) return;
        steamClient.connect();
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("logOn failed: " + cb.getResult());
            running = false;
            return;
        }
        // Start the pull EXACTLY once. onLoggedOn fires again on every reconnect (the CM websocket
        // drops mid-pull), and a plain volatile check-then-set let two threads both pass and run the
        // whole file loop concurrently (bogus "N failed" summary; double writes deduped only by
        // skip-if-exists). Guard the check+set atomically so a reconnect can never spawn a second run.
        synchronized (this) {
            if (enumerationStarted || enumerationCompleted) return;
            enumerationStarted = true;
        }
        new Thread(this::enumerate, "rd-cloud-enum").start();
    }

    /** The read-only payload: fetch the full cloud changelist for RimWorld and log every file. */
    private void enumerate() {
        try {
            progress("Logged on. Requesting cloud file list for app " + appId + "...");
            SteamCloud cloud = steamClient.getHandler(SteamCloud.class);
            // Kotlin default args are not visible from Java — pass the scope explicitly.
            AppFileChangeList list = cloud.getAppFileListChange(
                            appId, 0L,
                            kotlinx.coroutines.CoroutineScopeKt.CoroutineScope(
                                    kotlinx.coroutines.Dispatchers.getIO()))
                    .get(60, TimeUnit.SECONDS);

            List<String> prefixes = list.getPathPrefixes();
            List<AppFileInfo> files = list.getFiles();
            progress("Cloud changelist #" + list.getCurrentChangeNumber()
                    + ": " + files.size() + " files, "
                    + prefixes.size() + " path prefixes " + prefixes);

            long totalBytes = 0;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < files.size(); i++) {
                AppFileInfo f = files.get(i);
                int pi = f.getPathPrefixIndex();
                String prefix = (pi >= 0 && pi < prefixes.size()) ? prefixes.get(pi) : ("prefix#" + pi);
                totalBytes += f.getRawFileSize();
                String line = String.format(java.util.Locale.ROOT, "[%d] %s%s  %,d B  %s",
                        i, prefix, f.getFilename(), f.getRawFileSize(), f.getTimestamp());
                Log.i(TAG, line);
                // The on-screen status view only shows the last line — batch a readable summary.
                if (i < 40) sb.append(line).append('\n');
            }
            if (files.size() > 40) sb.append("… +").append(files.size() - 40).append(" more (full list in logcat)\n");
            progress(sb.toString());

            if (listOnly) {
                // Report the listing to the UI and stop — this mode never downloads anything.
                java.util.List<CloudFile> out = new java.util.ArrayList<>();
                for (AppFileInfo f : files)
                    out.add(new CloudFile(f.getFilename(), f.getRawFileSize(),
                            f.getTimestamp() == null ? 0L : f.getTimestamp().getTime()));
                if (listener instanceof CloudListener) ((CloudListener) listener).onFileList(out);
                enumerationCompleted = true;
                done(files.isEmpty() ? "Nothing in the cloud for this game."
                                     : ("Found " + files.size() + " save(s) in the cloud."));
                return;
            }

            if (upload) {
                // The changelist we just fetched doubles as the "what's already up there" reference,
                // so the log can say whether each push creates a new cloud file or replaces one.
                java.util.Set<String> inCloud = new java.util.HashSet<>();
                for (AppFileInfo f : files) inCloud.add(f.getFilename());
                uploadSelected(cloud, prefixes.isEmpty() ? DEFAULT_CLOUD_PREFIX : prefixes.get(0), inCloud);
                return;
            }

            if (files.isEmpty()) {
                enumerationCompleted = true;
                done("Cloud enumeration OK: 0 files. Nothing to download.");
                return;
            }

            if (instanceName == null) {
                // CACHE TEST: pull just the SMALLEST file to cache/cloud_test/ to prove the pipeline
                // with the least data. Touches no instance.
                AppFileInfo smallest = files.get(0);
                for (AppFileInfo f : files) if (f.getRawFileSize() < smallest.getRawFileSize()) smallest = f;
                String path = prefixOf(smallest, prefixes) + smallest.getFilename();
                byte[] raw = fetchAndDecode(cloud, path, smallest.getRawFileSize(), smallest.getShaFile());
                if (raw == null) return;   // fetchAndDecode already called done()
                File saved = writeTestFile(smallest.getFilename(), raw, "");
                enumerationCompleted = true;
                done("Download OK: " + raw.length + " B, SHA verified. Saved to " + saved
                        + " — no instance touched.");
                return;
            }

            // PULL INTO INSTANCE: download EVERY cloud save into instances/<name>/…/Saves/. Existing
            // files are SKIPPED (never overwrite the user's data). The cloud path tail is just the
            // filename; the prefix maps onto our own Saves/ dir.
            File saveDir = new File(AppStorage.requireSingleton().getInstanceDir(instanceName),
                    "unity3d/Ludeon Studios/RimWorld by Ludeon Studios/Saves");
            if (!saveDir.isDirectory() && !saveDir.mkdirs()) {
                done("Cannot create Saves dir: " + saveDir + " (does instance '" + instanceName + "' exist?)");
                return;
            }
            int ok = 0, skipped = 0, failed = 0;
            for (AppFileInfo f : files) {
                if (!running) break;
                if (selected != null && !selected.contains(f.getFilename())) continue;   // user's picks only
                File dest = new File(saveDir, f.getFilename());
                if (dest.exists()) {
                    progress("Skipping (already exists): " + f.getFilename());
                    skipped++;
                    continue;
                }
                String path = prefixOf(f, prefixes) + f.getFilename();
                byte[] raw = fetchAndDecode(cloud, path, f.getRawFileSize(), f.getShaFile());
                if (raw == null) { failed++; continue; }   // fetchAndDecode logged the reason
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(dest)) {
                    os.write(raw);
                    ok++;
                    progress("Saved: " + f.getFilename() + " (" + raw.length + " B)");
                } catch (Throwable t) {
                    Log.w(TAG, "write failed for " + dest + ": " + t);
                    failed++;
                }
            }
            enumerationCompleted = true;
            done("Pull complete → instance '" + instanceName + "': " + ok + " saved, "
                    + skipped + " skipped (existed), " + failed + " failed. "
                    + "Open RimWorld " + instanceName + " → Load game to try them.");
            return;
        } catch (Throwable t) {
            Log.e(TAG, "enumeration failed", t);
            done("cloud enumeration failed: " + t);
        } finally {
            running = false;   // one-shot: end the callback loop either way
        }
    }

    /** Where RimWorld's saves live in the cloud when the account has never synced from a PC yet.
     *  (Observed prefix on a real account: this exact string.) */
    private static final String DEFAULT_CLOUD_PREFIX =
            "%WinAppDataLocalLow%Ludeon Studios/RimWorld by Ludeon Studios/Saves/";

    /**
     * Push the selected local saves up to the cloud.
     *
     * Steam takes an upload as a BATCH: open it, then per file declare name/sizes/SHA and receive a
     * list of blocks to PUT, then commit the file, then close the batch. Each block either carries a
     * slice of our payload ({@code blockOffset}/{@code blockLength}) or a body Steam supplies itself
     * ({@code explicitBodyData}).
     *
     * The payload is built the same way the cloud hands files back: a ZIP with a single entry, where
     * {@code fileSize} is the archive and {@code rawFileSize} the real .rws — that symmetry is why the
     * download side could be decoded, so we mirror it here.
     *
     * Uploading REPLACES the cloud file of the same name, i.e. what the PC will next pick up, so the
     * log states for every file whether it creates or replaces.
     */
    private void uploadSelected(SteamCloud cloud, String cloudPrefix, java.util.Set<String> alreadyInCloud) {
        File saveDir = new File(AppStorage.requireSingleton().getInstanceDir(instanceName),
                "unity3d/Ludeon Studios/RimWorld by Ludeon Studios/Saves");
        java.util.List<File> picked = new java.util.ArrayList<>();
        File[] all = saveDir.listFiles((d, n) -> n.endsWith(".rws"));
        if (all != null)
            for (File f : all) if (selected == null || selected.contains(f.getName())) picked.add(f);
        if (picked.isEmpty()) {
            enumerationCompleted = true;
            done("Nothing to send: no matching saves in " + saveDir);
            return;
        }

        long batchId = 0;
        int ok = 0, failed = 0;
        try {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (File f : picked) names.add(cloudPrefix + f.getName());
            progress("Opening upload batch for " + picked.size() + " file(s)…");
            batchId = cloud.beginAppUploadBatch(
                            appId, "RimDroid", names, java.util.Collections.emptyList(),
                            steamClient.getSteamID().convertToUInt64(),   // clientId (undocumented; SteamID works as an id)
                            0L,                                           // appBuildId — we don't track the game's build
                            ioScope())
                    .get(60, TimeUnit.SECONDS)
                    .getBatchID();
            Log.i(TAG, "upload batch id=" + batchId);

            for (File f : picked) {
                if (!running) break;
                try {
                    byte[] raw = readFile(f);
                    byte[] zip = zipSingleEntry(raw);
                    byte[] sha = java.security.MessageDigest.getInstance("SHA-1").digest(raw);
                    String cloudPath = cloudPrefix + f.getName();
                    progress((alreadyInCloud.contains(f.getName()) ? "Replacing " : "Creating ")
                            + f.getName() + " (" + zip.length + " B zipped / " + raw.length + " B raw)…");

                    // Mobile transfers drop mid-flight (the same "connection abort" the download side
                    // hit). Retry the whole file — the block URLs are one-shot, so each attempt asks
                    // beginFileUpload for a FRESH set. Commit only once the blocks are all through.
                    boolean sent = false;
                    final int MAX_UP = 4;
                    for (int attempt = 1; attempt <= MAX_UP && !sent && running; attempt++) {
                        if (attempt > 1) {
                            progress("Retrying " + f.getName() + " (" + attempt + "/" + MAX_UP + ")…");
                            try { Thread.sleep(1500L); } catch (InterruptedException ignored) {}
                        }
                        FileUploadInfo up = cloud.beginFileUpload(
                                        appId, zip.length, raw.length, sha, new java.util.Date(f.lastModified()),
                                        cloudPath,
                                        -1,                                   // platformsToSync = all
                                        steamClient.getCellID() == null ? 0 : steamClient.getCellID(),
                                        false,                                // canEncrypt — keep the payload plain
                                        false,                                // isSharedFile
                                        null,                                 // deprecatedRealm
                                        batchId, ioScope())
                                .get(60, TimeUnit.SECONDS);
                        sent = putBlocks(up, zip);
                    }
                    boolean committed = cloud.commitFileUpload(sent, appId, sha, cloudPath, ioScope())
                            .get(60, TimeUnit.SECONDS);
                    if (sent && committed) { ok++; progress("Sent: " + f.getName()); }
                    else { failed++; progress("FAILED " + f.getName() + " (sent=" + sent + " committed=" + committed + ")"); }
                } catch (Throwable t) {
                    Log.e(TAG, "upload failed for " + f, t);
                    progress("FAILED " + f.getName() + ": " + t);
                    failed++;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "upload batch failed", t);
            progress("Upload batch error: " + t);
            failed = picked.size() - ok;
        } finally {
            if (batchId != 0) {
                try { cloud.completeAppUploadBatch(appId, batchId, EResult.OK, ioScope()).get(60, TimeUnit.SECONDS); }
                catch (Throwable t) { Log.w(TAG, "completeAppUploadBatch: " + t); }
            }
            enumerationCompleted = true;
            done("Send complete: " + ok + " uploaded, " + failed + " failed."
                    + (ok > 0 ? " Your PC will pick them up next time Steam syncs RimWorld." : ""));
            running = false;
        }
    }

    /** PUT/POST every block Steam asked for. Returns false on the first block that doesn't take. */
    private boolean putBlocks(FileUploadInfo up, byte[] payload) {
        java.util.List<in.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadBlockDetails> blocks =
                up.getBlockRequests();
        if (blocks == null || blocks.isEmpty()) {
            Log.w(TAG, "no upload blocks returned");
            return false;
        }
        for (int i = 0; i < blocks.size(); i++) {
            in.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadBlockDetails b = blocks.get(i);
            String url = (b.getUseHttps() ? "https://" : "http://") + b.getUrlHost() + b.getUrlPath();
            // Steam either supplies the body itself, or wants a slice of our payload.
            byte[] body = (b.getExplicitBodyData() != null && b.getExplicitBodyData().length > 0)
                    ? b.getExplicitBodyData()
                    : java.util.Arrays.copyOfRange(payload, (int) b.getBlockOffset(),
                            Math.min(payload.length, (int) b.getBlockOffset() + b.getBlockLength()));
            try {
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setRequestMethod(httpMethodName(b.getHttpMethod()));
                c.setDoOutput(true);
                c.setConnectTimeout(30000);
                c.setReadTimeout(120000);
                c.setFixedLengthStreamingMode(body.length);
                if (b.getRequestHeaders() != null)
                    for (HttpHeaders h : b.getRequestHeaders()) c.setRequestProperty(h.getName(), h.getValue());
                try (java.io.OutputStream os = c.getOutputStream()) { os.write(body); }
                int code = c.getResponseCode();
                c.disconnect();
                Log.i(TAG, "block " + (i + 1) + "/" + blocks.size() + " " + body.length + " B -> HTTP " + code);
                if (code / 100 != 2) return false;
            } catch (Throwable t) {
                Log.e(TAG, "block " + (i + 1) + " failed", t);
                return false;
            }
        }
        return true;
    }

    /** EHTTPMethod (SteamKit ordering) → the verb HttpURLConnection needs. */
    private static String httpMethodName(int m) {
        switch (m) {
            case 1: return "GET";
            case 2: return "HEAD";
            case 3: return "POST";
            case 5: return "DELETE";
            case 6: return "OPTIONS";
            default: return "PUT";   // 4, and the sane default for a block upload
        }
    }

    /** Wrap the bytes exactly like the cloud does: one ZIP entry (its name is irrelevant — the
     *  download side sees entries called "z"). */
    private static byte[] zipSingleEntry(byte[] raw) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(raw.length / 8 + 1024);
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            zos.setLevel(9);
            zos.putNextEntry(new java.util.zip.ZipEntry("z"));
            zos.write(raw);
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private static byte[] readFile(File f) throws java.io.IOException {
        byte[] out = new byte[(int) f.length()];
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f))) {
            in.readFully(out);
        }
        return out;
    }

    private static kotlinx.coroutines.CoroutineScope ioScope() {
        return kotlinx.coroutines.CoroutineScopeKt.CoroutineScope(kotlinx.coroutines.Dispatchers.getIO());
    }

    private static String prefixOf(AppFileInfo f, List<String> prefixes) {
        int i = f.getPathPrefixIndex();
        return (i >= 0 && i < prefixes.size()) ? prefixes.get(i) : "";
    }

    /**
     * Fetch one cloud file, decode it (Steam wraps files in a ZIP), and verify SHA-1 against the
     * cloud's {@code shaFile}. Returns the reconstructed raw bytes, or {@code null} on any failure
     * (network aborts after retries, encrypted-without-key, undecodable, or SHA mismatch — a
     * mismatch is a corruption we must NOT write). Uses {@link #progress} for per-file status; the
     * CALLER decides where to write and emits the terminal {@link #done}. Retries the whole fetch a
     * few times with a FRESH download URL each attempt (the Azure SAS url is short-lived/one-shot and
     * mobile networks drop mid-transfer).
     */
    private byte[] fetchAndDecode(SteamCloud cloud, String cloudPath, int rawFileSize, byte[] expectSha) {
        try {
            // The Azure blob URL is SAS-signed and short-lived, and the first run hit a mid-transfer
            // "connection abort" (mobile networks drop). Retry the WHOLE fetch — a FRESH download URL
            // each attempt (an expired/one-shot SAS can't be re-GET'd) — a few times before giving up.
            byte[] wire = null;
            FileDownloadInfo info = null;
            final int MAX = 4;
            for (int attempt = 1; attempt <= MAX && wire == null; attempt++) {
                try {
                    progress("Requesting download URL (attempt " + attempt + "/" + MAX + ")…");
                    // Kotlin default args (realm, forceProxy, parentScope) aren't visible from Java — pass all.
                    info = cloud.clientFileDownload(
                                    appId, cloudPath,
                                    in.dragonbra.javasteam.enums.ESteamRealm.SteamGlobal,
                                    false,
                                    kotlinx.coroutines.CoroutineScopeKt.CoroutineScope(
                                            kotlinx.coroutines.Dispatchers.getIO()))
                            .get(60, TimeUnit.SECONDS);

                    String url = (info.getUseHttps() ? "https://" : "http://")
                            + info.getUrlHost() + info.getUrlPath();
                    Log.i(TAG, "download info: url=" + url + " encrypted=" + info.getEncrypted()
                            + " fileSize=" + info.getFileSize() + " rawFileSize=" + info.getRawFileSize()
                            + " headers=" + (info.getRequestHeaders() == null ? 0 : info.getRequestHeaders().size()));
                    progress("Downloading " + String.format(java.util.Locale.ROOT, "%,d", info.getFileSize())
                            + " B (raw " + String.format(java.util.Locale.ROOT, "%,d", info.getRawFileSize())
                            + ", encrypted=" + info.getEncrypted() + ")…");

                    java.net.HttpURLConnection conn =
                            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(60000);
                    if (info.getRequestHeaders() != null)
                        for (HttpHeaders h : info.getRequestHeaders())
                            conn.setRequestProperty(h.getName(), h.getValue());

                    int code = conn.getResponseCode();
                    if (code != 200) {
                        Log.w(TAG, "attempt " + attempt + ": HTTP " + code + " " + conn.getResponseMessage());
                        conn.disconnect();
                        Thread.sleep(1500L);
                        continue;
                    }
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                            Math.max(1024, info.getFileSize()));
                    try (java.io.InputStream in = conn.getInputStream()) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                    } finally {
                        conn.disconnect();
                    }
                    byte[] got = bos.toByteArray();
                    // A truncated transfer (abort) leaves fewer bytes than fileSize — treat as a retry.
                    if (info.getFileSize() > 0 && got.length < info.getFileSize()) {
                        Log.w(TAG, "attempt " + attempt + ": short read " + got.length + "/" + info.getFileSize());
                        Thread.sleep(1500L);
                        continue;
                    }
                    wire = got;
                } catch (Throwable t) {
                    Log.w(TAG, "attempt " + attempt + " failed: " + t);
                    if (attempt < MAX) { try { Thread.sleep(1500L); } catch (InterruptedException ignored) {} }
                }
            }
            if (wire == null) {
                progress("FAILED " + baseName(cloudPath) + ": network aborts after " + MAX + " attempts.");
                return null;
            }
            String magic = wire.length >= 4
                    ? String.format("%02x %02x %02x %02x", wire[0], wire[1], wire[2], wire[3]) : "(short)";
            Log.i(TAG, "downloaded " + wire.length + " bytes, magic=" + magic);

            if (info.getEncrypted()) {
                progress("FAILED " + baseName(cloudPath) + ": ENCRYPTED (magic " + magic
                        + ") — need the cloud key; skipping.");
                return null;
            }

            // Reconstruct the original bytes. Steam Cloud wraps each file in a ZIP (observed magic
            // "PK\3\4" 50 4b 03 04) whose single entry IS the real file; the fileSize/rawFileSize pair
            // is the zip vs contained size. So: sizes-match → plain; PK magic → unzip first entry;
            // else fall back to raw zlib inflate (some files may differ).
            byte[] raw = null;
            String method = null;
            boolean isZip = wire.length >= 4 && wire[0] == 0x50 && wire[1] == 0x4b
                    && wire[2] == 0x03 && wire[3] == 0x04;
            if (wire.length == rawFileSize) {
                raw = wire; method = "plain (no compression)";
            } else if (isZip) {
                try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                        new java.io.ByteArrayInputStream(wire))) {
                    java.util.zip.ZipEntry e = zin.getNextEntry();
                    if (e != null) {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(rawFileSize);
                        byte[] buf = new byte[1 << 16];
                        int m;
                        while ((m = zin.read(buf)) != -1) out.write(buf, 0, m);
                        raw = out.toByteArray();
                        method = "zip-entry '" + e.getName() + "'";
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "zip extract failed: " + t);
                }
            } else {
                try {
                    java.util.zip.Inflater inf = new java.util.zip.Inflater();   // zlib fallback
                    inf.setInput(wire);
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(rawFileSize);
                    byte[] buf = new byte[1 << 16];
                    while (!inf.finished()) {
                        int m = inf.inflate(buf);
                        if (m == 0) { if (inf.finished() || inf.needsInput()) break; }
                        out.write(buf, 0, m);
                    }
                    inf.end();
                    raw = out.toByteArray();
                    method = "zlib-inflate";
                } catch (Throwable t) {
                    Log.w(TAG, "zlib inflate failed: " + t);
                }
            }
            if (raw == null) {
                progress("FAILED " + baseName(cloudPath) + ": undecodable (magic " + magic + ").");
                return null;
            }

            // A SHA mismatch = corruption. Never write a corrupt save into an instance.
            String sha = sha1Hex(raw);
            String expect = expectSha == null ? null : hex(expectSha);
            if (expect != null && !expect.equalsIgnoreCase(sha)) {
                progress("FAILED " + baseName(cloudPath) + ": SHA-1 mismatch (got " + sha + ").");
                return null;
            }
            Log.i(TAG, "decoded " + baseName(cloudPath) + " via " + method + ": " + raw.length
                    + " B, SHA-1 " + (expect == null ? "(no reference)" : "OK"));
            return raw;
        } catch (Throwable t) {
            Log.e(TAG, "fetch/decode failed for " + cloudPath, t);
            progress("FAILED " + baseName(cloudPath) + ": " + t);
            return null;
        }
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Cache-test writer (used only when no instance is targeted). */
    private File writeTestFile(String filename, byte[] data, String suffix) {
        try {
            File dir = new File(AppStorage.requireSingleton().getCachePath(), "cloud_test");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File out = new File(dir, baseName(filename) + suffix);
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(out)) { os.write(data); }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "writeTestFile failed: " + t);
            return null;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String sha1Hex(byte[] data) throws java.security.NoSuchAlgorithmException {
        return hex(java.security.MessageDigest.getInstance("SHA-1").digest(data));
    }

    private class PushAuthenticator implements IAuthenticator {
        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            Log.i(TAG, "acceptDeviceConfirmation -> true (Steam Mobile approval push)");
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, null)
                    : CompletableFuture.completedFuture("");
        }
        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, email)
                    : CompletableFuture.completedFuture("");
        }
    }
}
