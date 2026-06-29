package com.rimdroid;

import android.util.Log;

import androidx.annotation.NonNull;

import in.dragonbra.javasteam.depotdownloader.DepotDownloader;
import in.dragonbra.javasteam.depotdownloader.IDownloadListener;
import in.dragonbra.javasteam.depotdownloader.data.AppItem;
import in.dragonbra.javasteam.depotdownloader.data.DownloadItem;
import in.dragonbra.javasteam.depotdownloader.data.PubFileItem;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSTokensCallback;
import in.dragonbra.javasteam.types.AsyncJobMultiple;
import in.dragonbra.javasteam.types.KeyValue;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.util.log.DefaultLogListener;
import in.dragonbra.javasteam.util.log.LogManager;

import com.rimdroid.game.GameInstanceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * SPIKE — Milestone 1 of the in-app Steam downloader (memory in_app_game_downloader.md).
 *
 * Proves JavaSteam's high-level {@link DepotDownloader} can fetch the RimWorld (Linux) depot
 * ON THE DEVICE: log in (credentials → Steam-Mobile approval push, same as Milestone 0), receive
 * the account's licenses, then run DepotDownloader for the RimWorld Linux depot. The downloader
 * fetches the manifest, the (ownership-gated) depot key, downloads + decompresses the CDN chunks
 * (VZip via xz / VZstd via zstd-jni) and lays them out under an absolute install dir.
 *
 * NO token storage: the refresh token is kept ONLY in memory (a field) for this run, never written
 * to disk — exactly per the project constraint. The in-memory copy lets us re-logon transparently
 * after a transient CM disconnect WITHOUT another Steam Mobile approval.
 *
 * Resilience: the first on-device test failed because the CM websocket dropped (the app was briefly
 * backgrounded for the Steam Mobile approval) and we gave up on the first disconnect. Now we
 * reconnect + retry the download up to {@link #MAX_DOWNLOAD_ATTEMPTS} times. The download params
 * mirror the user's known-good DepotDownloader command exactly (explicit depot + manifest) to
 * remove the DLC-depot iteration and the manifest-version-resolution round trip.
 *
 * Run on a BACKGROUND thread (blocks in a callback loop + awaitCompletion). Pure network client.
 */
public class SteamDownloadSpike implements Runnable, IDownloadListener, Cancellable {

    private static final String TAG = "RimDroid/SteamDL";

    /** RimWorld on Steam, mirroring `DepotDownloader -app 294100 -depot 294103 -manifest …`. */
    public static final int RIMWORLD_APP_ID = 294100;
    private static final int RIMWORLD_LINUX_DEPOT = 294103;
    // RimDroid currently targets RimWorld 1.5 ONLY. The Steam "public" branch is now 1.6, so we do
    // NOT auto-resolve "latest" (that would pull 1.6). Default = the LAST stable (non-unstable) 1.5
    // manifest for depot 294103 (newest 1.5 build, all bugfixes). 1.5 is frozen now that 1.6 shipped,
    // so this stays "latest stable 1.5". Advanced users can override with a specific manifest id
    // (older public 1.5 builds, newest→oldest, are listed in memory/in_app_game_downloader.md).
    private static final long RIMWORLD_1_5_MANIFEST = 2197714010033731403L;

    /**
     * Pin DLC to the last 1.5 build too — otherwise DepotDownloader pulls "latest" (= 1.6), which is
     * a version mismatch with the 1.5 base game. Keyed by the DLC's Linux DEPOT id (resolved from base
     * app 294100; it's printed in the debug log as the "DLC depot map="). Fill each from SteamDB:
     * app 294100 → Depots → the DLC's Linux depot → its last 1.5 manifest (newest manifest dated just
     * before the 1.6 switch). Any depot NOT listed here falls back to latest. RimWorld DLC app ids for
     * reference: Royalty 1149640, Ideology 1392840, Biotech 1826140, Anomaly 2380740.
     */
    private static final java.util.Map<Integer, Long> DLC_DEPOT_1_5_MANIFEST = new java.util.HashMap<>();
    static {
        // Linux depot (under base app 294100) → last 1.5 manifest. Provided by the maintainer from SteamDB.
        DLC_DEPOT_1_5_MANIFEST.put(1149643, 7489971535168711930L);   // Royalty  (app 1149640)
        DLC_DEPOT_1_5_MANIFEST.put(294108,  8829858508882856102L);   // Ideology (app 1392840)
        DLC_DEPOT_1_5_MANIFEST.put(367686,  8944803661678388929L);   // Biotech  (app 1826140)
        DLC_DEPOT_1_5_MANIFEST.put(294112,  6055297988647927242L);   // Anomaly  (app 2380740)
    }

    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    /** Re-tries of the INITIAL sign-in if the CM connection drops mid-approval (Steam-Mobile excursion). */
    private static final int MAX_AUTH_ATTEMPTS = 3;

    public interface Listener {
        /** Steam Guard code prompt (only if the account is not approved via the Steam Mobile push). */
        CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email);
        /** Human-readable progress line for an on-screen status view (NOT a toast). */
        void onProgress(String message);
        /** Overall depot progress 0..100 (for a determinate progress bar). Default no-op so older
         *  listeners (e.g. the debug button) need not implement it. */
        default void onPercent(int percent) {}
        /** Terminal message (success or error). */
        void onDone(String message);
    }

    /** A downloadable DLC: its own Steam app id + a display/file name. */
    public static final class Dlc {
        public final int appId;
        public final String name;
        public Dlc(int appId, String name) { this.appId = appId; this.name = name; }
    }

    private final String username, password;
    private final String instanceName;   // GAME mode: download lands in instances/<name>
    private final String installDir;      // GAME mode: ABSOLUTE path = AppStorage.getInstanceDir(name)
    private final boolean manifestOnly;
    private final long manifestId;        // 0 = default to the recommended 1.5 build; >0 = pin this build
    private final List<Dlc> dlcs;         // DLC mode (non-null) → download these into /Download/RimDroid as zips
    private final List<Long> workshopIds; // MODS mode (non-null) → download these Workshop items (logged-in)
    private final Listener listener;

    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;
    private List<License> licenseList;

    // In-memory session (never persisted) — reused to re-logon after a reconnect without re-approval.
    private volatile String accountName;
    private volatile String refreshToken;

    private volatile boolean running;
    private volatile Thread workerThread;          // the depot-download thread (interrupted on cancel)
    private volatile boolean cancelled;            // user requested cancel
    private volatile boolean doneEmitted;          // ensure exactly one terminal onDone
    private volatile boolean downloadStarted;     // a download attempt is queued/running this connection
    private volatile boolean downloadInProgress;   // a DepotDownloader is actively running
    private volatile boolean downloadCompleted;    // succeeded — stop everything
    private volatile Throwable lastError;          // set by onDownloadFailed; checked after awaitCompletion
    private int downloadAttempts;
    private int authAttempts;                      // initial-sign-in attempts (drops during approval)

    /** GAME mode: download RimWorld into instances/&lt;name&gt; and make it launchable. */
    public SteamDownloadSpike(String username, String password, String instanceName,
                              boolean manifestOnly, long manifestId, Listener listener) {
        this.username = username;
        this.password = password;
        this.instanceName = instanceName;
        // Download straight into the instance's game dir; once RimWorldLinux lands there,
        // GameInstance.isInstalled() is true and it becomes launchable.
        this.installDir = AppStorage.requireSingleton().getInstanceDir(instanceName).getAbsolutePath();
        this.manifestOnly = manifestOnly;
        this.manifestId = manifestId;
        this.dlcs = null;
        this.workshopIds = null;
        this.listener = listener;
    }

    // DLC + MODS share one private ctor (separate public 4-arg ctors would clash on erasure:
    // both List<Dlc> and List<Long> erase to List). Use the factories below.
    private SteamDownloadSpike(String username, String password,
                               List<Dlc> dlcs, List<Long> workshopIds, Listener listener) {
        this.username = username;
        this.password = password;
        this.dlcs = dlcs;
        this.workshopIds = workshopIds;
        this.listener = listener;
        this.instanceName = null;
        this.installDir = null;
        this.manifestOnly = false;
        this.manifestId = 0L;
    }

    /** DLC mode: download each owned DLC and pack it into a zip under /Download/RimDroid. */
    public static SteamDownloadSpike forDlc(String username, String password, List<Dlc> dlcs, Listener listener) {
        return new SteamDownloadSpike(username, password, dlcs, null, listener);
    }

    /**
     * MODS mode: download public Workshop items by id → zips. Needs a LOGGED-IN account (Workshop UGC
     * content download is not available anonymously in JavaSteam's DepotDownloader — it hangs resolving
     * the UGC; same as `depotdownloader -app 294100 -pubfile <id>`, which runs under your account).
     */
    public static SteamDownloadSpike forMods(String username, String password, List<Long> workshopIds, Listener listener) {
        return new SteamDownloadSpike(username, password, null, workshopIds, listener);
    }

    private void progress(String m) {
        Log.i(TAG, m);
        if (listener != null) listener.onProgress(m);
    }

    private void done(String m) {
        if (doneEmitted) return;     // exactly one terminal message (cancel + a late retry-finish can race)
        doneEmitted = true;
        Log.i(TAG, "DONE: " + m);
        if (listener != null) listener.onDone(m);
    }

    /** {@link Cancellable}: stop the running download — break the blocking await + the CM loop. */
    @Override
    public void cancel() {
        cancelled = true;
        running = false;
        downloadCompleted = true;            // prevent the disconnect handler from reconnecting/retrying
        Thread w = workerThread;
        if (w != null) w.interrupt();        // break DepotDownloader's awaitCompletion()
        try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        done("Download cancelled.");
    }

    @Override
    public void run() {
        try {
            LogManager.addListener(new DefaultLogListener());

            // Default client (same as the proven Milestone-0 auth spike). Depot CDN chunks are
            // downloaded by DepotDownloader's own ktor client, so no custom HTTP config is needed.
            steamClient = new SteamClient();
            manager = new CallbackManager(steamClient);
            steamUser = steamClient.getHandler(SteamUser.class);

            manager.subscribe(ConnectedCallback.class, this::onConnected);
            manager.subscribe(DisconnectedCallback.class, this::onDisconnected);
            manager.subscribe(LoggedOnCallback.class, this::onLoggedOn);
            manager.subscribe(LicenseListCallback.class, this::onLicenseList);

            running = true;
            progress("Tip: stay on ONE network (Wi-Fi or mobile) until the download finishes — "
                    + "switching mid-download can drop the sign-in.");
            progress("Connecting to Steam...");
            steamClient.connect();
            while (running) {
                manager.runWaitCallbacks(1000L);
            }
            Log.i(TAG, "Spike loop ended");
            // Safety net: never leave the UI stuck in "downloading" if the loop ended without a
            // terminal result (e.g. a disconnect path that set running=false but did not call done()).
            if (!doneEmitted) done("Stopped before finishing — please try again.");
        } catch (Throwable t) {
            Log.e(TAG, "SteamDownloadSpike crashed", t);
            done("crash: " + t);
        } finally {
            // ALWAYS tear the connection down on the way out. Otherwise a failed/finished session
            // (e.g. auth cancelled when the device switched Wi-Fi↔mobile) leaves an ORPHANED
            // SteamClient whose background ktor/reader thread keeps running; when the network then
            // changes its socket dies and the coroutine throws an UNCAUGHT exception on its own
            // thread — which hard-crashes the whole app (not caught by any try/catch here, never
            // logged). Disconnecting here cancels those coroutines cleanly so nothing is left to die.
            try { if (steamClient != null) steamClient.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void onConnected(ConnectedCallback cb) {
        try {
            // Reconnect path: reuse the in-memory token, no second Steam Mobile approval.
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
            details.persistentSession = false;          // do NOT persist — ephemeral token, no storage
            details.deviceFriendlyName = "RimDroid";
            details.authenticator = new PushAuthenticator();

            CredentialsAuthSession session =
                    new SteamAuthentication(steamClient).beginAuthSessionViaCredentials(details).get();
            progress("Approve the sign-in in your Steam Mobile app...");

            AuthPollResult poll = session.pollingWaitForResult().get();
            accountName = poll.getAccountName();
            refreshToken = poll.getRefreshToken();       // kept in memory only
            Log.i(TAG, "AUTH OK: account=" + accountName
                    + " tokenLen=" + (refreshToken == null ? 0 : refreshToken.length()));
            logOnWithToken();
        } catch (Throwable t) {
            Log.e(TAG, "Auth failed", t);
            // A CancellationException here = the CM connection DROPPED while we waited for the Steam
            // Mobile approval (e.g. the user briefly switched to the Steam app to approve), NOT a real
            // auth failure (wrong password / declined). Treat it as a transient drop: keep `running`
            // true so onDisconnected reconnects and re-runs the sign-in (a fresh approval push), up to
            // MAX_AUTH_ATTEMPTS. Only give up on a genuine failure or once attempts are exhausted.
            boolean connectionDropped =
                    (t instanceof java.util.concurrent.CancellationException)
                            || (t.getCause() instanceof java.util.concurrent.CancellationException);
            if (connectionDropped && !cancelled && authAttempts < MAX_AUTH_ATTEMPTS) {
                progress("Sign-in interrupted by a connection drop — reconnecting and asking you to "
                        + "approve again (attempt " + authAttempts + "/" + MAX_AUTH_ATTEMPTS + ")…");
                // running stays true → onDisconnected reconnects → onConnected re-runs the sign-in.
            } else {
                done("auth error: " + describe(t));
                running = false;
            }
        }
    }

    private void logOnWithToken() {
        LogOnDetails lod = new LogOnDetails();
        lod.setUsername(accountName);
        lod.setAccessToken(refreshToken);
        lod.setLoginID(149);
        progress("Logging in...");
        steamUser.logOn(lod);
    }

    private void onDisconnected(DisconnectedCallback cb) {
        Log.i(TAG, "Disconnected (userInitiated=" + cb.isUserInitiated() + ")");
        // Stop for good on a user-initiated disconnect, on success, or once retries are exhausted.
        if (cb.isUserInitiated() || downloadCompleted || downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS
                || (refreshToken == null && authAttempts >= MAX_AUTH_ATTEMPTS)) {
            running = false;
            if (!downloadCompleted && !cb.isUserInitiated()) {
                done("connection lost; gave up after " + downloadAttempts + " attempt(s). "
                        + (lastError != null ? describe(lastError) : ""));
            }
            return;
        }
        // Transient drop mid-download — reconnect and let onLicenseList re-trigger the attempt.
        progress("Connection lost — reconnecting (attempt " + (downloadAttempts + 1) + ")...");
        downloadStarted = false;
        try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
        if (!running) return;   // cancelled while waiting to reconnect
        steamClient.connect();   // → onConnected reuses the cached token (no re-approval)
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("logOn failed: " + cb.getResult());
            running = false;
            return;
        }
        progress("Logged on. Waiting for license list...");
        // The download starts in onLicenseList (DepotDownloader needs the account's licenses).
    }

    private void onLicenseList(LicenseListCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("license list failed: " + cb.getResult());
            running = false;
            return;
        }
        licenseList = cb.getLicenseList();
        progress("Got " + (licenseList == null ? 0 : licenseList.size()) + " licenses.");

        // Start exactly one download attempt per (re)connect; never while one is in flight or done.
        if (downloadStarted || downloadInProgress || downloadCompleted) return;
        downloadStarted = true;
        Runnable job = (workshopIds != null) ? this::downloadMods
                : (dlcs != null) ? this::downloadDlcs : this::download;
        Thread t = new Thread(job, "rd-depot-dl");
        workerThread = t;            // tracked so cancel() can interrupt the blocking download
        t.start();
    }

    /**
     * DLC mode: resolve ownership + the DLC's base-app (294100) Linux depots from PICS, then for each
     * OWNED selected DLC download its depot(s) (via the base app) into a temp work dir and pack it into
     * /Download/RimDroid/&lt;name&gt;.zip — a portable archive the smart importer later unwraps into an
     * instance. Unowned DLC are skipped WITHOUT attempting a download (so they can't hang the flow).
     */
    private void downloadDlcs() {
        downloadInProgress = true;
        downloadCompleted = true;   // DLC mode is single-pass; disconnect handler won't retry it
        AppStorage storage = AppStorage.requireSingleton();
        File downloadsDir = storage.getDownloadsDir();
        File tmpRoot = new File(storage.getCachePath(), "dlc_work");
        int ok = 0, skipped = 0;
        try {
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                done("Cannot create downloads folder: " + downloadsDir
                        + " (grant All-files access?)");
                running = false;
                return;
            }

            // RimWorld DLC depots live UNDER the base app 294100 (with a dlcappid), NOT under the DLC's
            // own app id (downloading by DLC app id → "Couldn't find any depots"). Resolve from PICS:
            // which app ids the account owns + which base-app linux depots belong to each DLC.
            Set<Integer> ownedAppIds = new HashSet<>();
            Map<Integer, List<Integer>> dlcDepots = new HashMap<>();
            progress("Resolving DLC info from Steam...");
            resolveDlcInfo(ownedAppIds, dlcDepots);

            for (Dlc d : dlcs) {
                if (!running) { progress("Aborted (disconnected)."); break; }
                if (!ownedAppIds.contains(d.appId)) {
                    progress("• " + d.name + " — not owned on this account, skipping.");
                    skipped++;
                    continue;
                }
                List<Integer> depots = dlcDepots.get(d.appId);
                if (depots == null || depots.isEmpty()) {
                    progress("✗ " + d.name + " — no Linux depot found.");
                    skipped++;
                    continue;
                }
                progress("=== " + d.name + " (app " + d.appId + ", depots " + depots + ") ===");
                File work = new File(tmpRoot, String.valueOf(d.appId));
                deleteRecursive(work);
                if (!work.mkdirs()) { progress("✗ " + d.name + ": cannot create work dir"); skipped++; continue; }
                lastError = null;
                boolean got = false;
                // Pin to 1.5 only if we know the 1.5 manifest for EVERY resolved depot (AppItem needs
                // depot[i] ↔ manifest[i] parallel). Otherwise fall back to latest for this DLC.
                List<Long> manifests = new ArrayList<>();
                for (Integer dep : depots) {
                    Long m = DLC_DEPOT_1_5_MANIFEST.get(dep);
                    if (m == null) { manifests = List.of(); break; }
                    manifests.add(m);
                }
                if (manifests.isEmpty()) {
                    progress("  (no pinned 1.5 manifest for depots " + depots + " → downloading LATEST = 1.6)");
                } else {
                    progress("  (pinned to 1.5 manifests " + manifests + ")");
                }
                try (DepotDownloader dd = new DepotDownloader(steamClient, licenseList, /* debug */ true)) {
                    dd.addListener(this);
                    AppItem item = new AppItem(
                            /* appId */ RIMWORLD_APP_ID,             // DLC depots are under the base app
                            /* installToGameNameDirectory */ false,
                            /* installDirectory */ work.getAbsolutePath(),
                            /* branch */ "public",
                            /* branchPassword */ "",
                            /* downloadAllPlatforms */ false,
                            /* os */ "linux",
                            /* downloadAllArchs */ false,
                            /* osArch */ "64",
                            /* downloadAllLanguages */ false,
                            /* language */ "english",
                            /* lowViolence */ false,
                            /* depot */ depots,           // the DLC's linux depot(s), resolved above
                            /* manifest */ manifests,     // pinned 1.5 (parallel to depots) or empty = latest
                            /* verify */ false,
                            /* downloadManifestOnly */ false);
                    dd.add(item);
                    dd.finishAdding();
                    // Bounded wait: an unowned/odd DLC must never hang the whole flow (and lock the UI).
                    // A real DLC is a few hundred MB → minutes; 20 min is a safe ceiling.
                    dd.getCompletion().get(20, java.util.concurrent.TimeUnit.MINUTES);
                    dd.removeListener(this);
                    got = lastError == null && containsAboutXml(work);
                } catch (java.util.concurrent.TimeoutException te) {
                    progress("✗ " + d.name + ": timed out (20 min) — skipping");
                    lastError = te;
                } catch (Throwable t) {
                    Log.e(TAG, "DLC " + d.appId + " download crashed", t);
                    lastError = t;
                }
                if (got) {
                    try {
                        File zip = new File(downloadsDir, sanitizeName(d.name) + ".zip");
                        ZipUtil.zipDir(work, zip);
                        progress("✓ " + d.name + " → " + zip.getAbsolutePath());
                        ok++;
                    } catch (Throwable t) {
                        Log.e(TAG, "zip failed for " + d.name, t);
                        progress("✗ " + d.name + ": zip failed — " + describe(t));
                        skipped++;
                    }
                } else {
                    progress("✗ " + d.name + " — not owned or failed"
                            + (lastError != null ? (": " + describe(lastError)) : ""));
                    skipped++;
                }
                deleteRecursive(work);
            }
            done("DLC done: " + ok + " packed, " + skipped + " skipped. Saved to " + downloadsDir);
        } catch (Throwable t) {
            Log.e(TAG, "downloadDlcs crashed", t);
            done("DLC error: " + describe(t));
        } finally {
            running = false;
            downloadInProgress = false;
            try { steamUser.logOff(); } catch (Throwable ignored) {}
        }
    }

    /**
     * One PICS query to learn (1) which app ids the account OWNS (from its license packages' "appids")
     * and (2) which base-app (294100) Linux depots belong to each DLC (depots carry a "dlcappid").
     * Runs on the download thread; the main callback loop pumps the responses. Bounded so it can't hang.
     */
    private void resolveDlcInfo(Set<Integer> ownedAppIds, Map<Integer, List<Integer>> dlcDepots)
            throws Exception {
        SteamApps apps = steamClient.getHandler(SteamApps.class);

        // Access token for the base app's product info (owned, but Steam usually wants the token).
        long appToken = 0L;
        try {
            PICSTokensCallback tk = apps.picsGetAccessTokens(
                    Collections.singletonList(RIMWORLD_APP_ID), Collections.<Integer>emptyList())
                    .toFuture().get(30, TimeUnit.SECONDS);
            Long t = tk.getAppTokens().get(RIMWORLD_APP_ID);
            if (t != null) appToken = t;
        } catch (Throwable ignored) { /* try without a token */ }

        List<PICSRequest> appReqs = Collections.singletonList(new PICSRequest(RIMWORLD_APP_ID, appToken));
        List<PICSRequest> pkgReqs = new ArrayList<>();
        for (License l : licenseList) pkgReqs.add(new PICSRequest(l.getPackageID(), l.getAccessToken()));

        AsyncJobMultiple.ResultSet<PICSProductInfoCallback> rs =
                apps.picsGetProductInfo(appReqs, pkgReqs).toFuture().get(60, TimeUnit.SECONDS);

        for (PICSProductInfoCallback cb : rs.getResults()) {
            // (1) owned app ids — from each owned package's "appids" list.
            for (PICSProductInfo pkg : cb.getPackages().values()) {
                for (KeyValue a : pkg.getKeyValues().get("appids").getChildren()) {
                    ownedAppIds.add(a.asInteger(0));
                }
            }
            // (2) DLC depots — from the base app's "depots" section (depot → dlcappid + oslist).
            PICSProductInfo app = cb.getApps().get(RIMWORLD_APP_ID);
            if (app != null) {
                for (KeyValue depot : app.getKeyValues().get("depots").getChildren()) {
                    int depotId;
                    try { depotId = Integer.parseInt(depot.getName()); }
                    catch (NumberFormatException e) { continue; }   // skip "branches", "baselanguages", etc.
                    int dlcAppId = depot.get("dlcappid").asInteger(0);
                    if (dlcAppId <= 0) continue;                    // base depot, not a DLC
                    String os = depot.get("config").get("oslist").asString();
                    if (os != null && !os.isEmpty() && !os.contains("linux")) continue;  // wrong OS
                    List<Integer> list = dlcDepots.get(dlcAppId);
                    if (list == null) { list = new ArrayList<>(); dlcDepots.put(dlcAppId, list); }
                    list.add(depotId);
                }
            }
        }
        Log.i(TAG, "PICS: owned apps=" + ownedAppIds.size() + ", DLC depot map=" + dlcDepots);
    }

    /**
     * MODS mode: ANONYMOUS download of public Workshop items (by published-file id) into temp work
     * dirs, each packed into /Download/RimDroid/workshop_&lt;id&gt;.zip. No login, no licenses.
     */
    private void downloadMods() {
        downloadInProgress = true;
        downloadCompleted = true;   // single pass; no reconnect-retry
        AppStorage storage = AppStorage.requireSingleton();
        File downloadsDir = storage.getDownloadsDir();
        File tmpRoot = new File(storage.getCachePath(), "mod_work");
        List<License> licenses = licenseList != null ? licenseList : Collections.emptyList();
        int ok = 0, skipped = 0;
        try {
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                done("Cannot create downloads folder: " + downloadsDir + " (grant All-files access?)");
                running = false;
                return;
            }
            for (Long id : workshopIds) {
                if (!running) { progress("Aborted (disconnected)."); break; }
                progress("=== Workshop mod " + id + " (app " + RIMWORLD_APP_ID + ") ===");
                File work = new File(tmpRoot, String.valueOf(id));
                deleteRecursive(work);
                if (!work.mkdirs()) { progress("✗ " + id + ": cannot create work dir"); skipped++; continue; }
                lastError = null;
                boolean got = false;
                try (DepotDownloader dd = new DepotDownloader(steamClient, licenses, /* debug */ true)) {
                    dd.addListener(this);
                    PubFileItem item = new PubFileItem(
                            /* appId */ RIMWORLD_APP_ID,
                            /* pubFile */ id,
                            /* installToGameNameDirectory */ false,
                            /* installDirectory */ work.getAbsolutePath(),
                            /* verify */ false,
                            /* downloadManifestOnly */ false);
                    dd.add(item);
                    dd.finishAdding();
                    dd.getCompletion().get(20, TimeUnit.MINUTES);
                    dd.removeListener(this);
                    got = lastError == null && containsAboutXml(work);
                } catch (java.util.concurrent.TimeoutException te) {
                    progress("✗ " + id + ": timed out (20 min) — skipping");
                    lastError = te;
                } catch (Throwable t) {
                    Log.e(TAG, "mod " + id + " download crashed", t);
                    lastError = t;
                }
                if (got) {
                    try {
                        File zip = new File(downloadsDir, "workshop_" + id + ".zip");
                        ZipUtil.zipDir(work, zip);
                        progress("✓ mod " + id + " → " + zip.getAbsolutePath());
                        ok++;
                    } catch (Throwable t) {
                        Log.e(TAG, "zip failed for mod " + id, t);
                        progress("✗ " + id + ": zip failed — " + describe(t));
                        skipped++;
                    }
                } else {
                    progress("✗ mod " + id + " — failed"
                            + (lastError != null ? (": " + describe(lastError)) : ""));
                    skipped++;
                }
                deleteRecursive(work);
            }
            done("Mods done: " + ok + " packed, " + skipped + " skipped. Saved to " + downloadsDir);
        } catch (Throwable t) {
            Log.e(TAG, "downloadMods crashed", t);
            done("Mods error: " + describe(t));
        } finally {
            running = false;
            downloadInProgress = false;
            try { steamUser.logOff(); } catch (Throwable ignored) {}
        }
    }

    /** True if {@code dir} contains an About/About.xml anywhere — proof a real mod/DLC was downloaded. */
    private static boolean containsAboutXml(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) {
            if (f.getName().equals(".DepotDownloader")) continue;
            if (f.isDirectory()) {
                File about = new File(f, "About/About.xml");
                if (about.exists()) return true;
                if (containsAboutXml(f)) return true;
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

    private static String sanitizeName(String s) {
        if (s == null) return "dlc";
        String r = s.replaceAll("[^A-Za-z0-9 _.\\-()]", "").trim();
        return r.isEmpty() ? "dlc" : r;
    }

    private void download() {
        downloadInProgress = true;
        downloadAttempts++;
        lastError = null;
        // RESUME-FRIENDLY: we deliberately do NOT wipe installDir/.DepotDownloader. DepotDownloader
        // uses that cache + the partial files to skip already-downloaded chunks, so re-tapping
        // "Download" for the SAME instance + manifest CONTINUES a half-finished download instead of
        // restarting from zero — essential for big multi-GB downloads (start at home, finish later
        // on another network). The earlier hard-crash was the orphaned SteamClient (fixed in run()'s
        // finally), not this cache, so wiping here would only cost the user their progress.
        if (downloadAttempts == 1) {
            File state = new File(installDir, ".DepotDownloader");
            if (state.exists()) progress("Found partial download — resuming where it stopped…");
        }
        // Always pin a manifest: a user-supplied build, else the recommended 1.5 one (NOT "latest",
        // which is now 1.6 / unsupported).
        long mid = manifestId > 0 ? manifestId : RIMWORLD_1_5_MANIFEST;
        List<Long> manifests = List.of(mid);
        progress((manifestOnly ? "[manifest-only] " : "")
                + "[attempt " + downloadAttempts + "] "
                + (manifestId > 0 ? ("manifest " + mid) : ("recommended 1.5 build " + mid))
                + " → " + installDir);
        try (DepotDownloader dd = new DepotDownloader(steamClient, licenseList, /* debug */ true)) {
            dd.addListener(this);

            AppItem rimworld = new AppItem(
                    /* appId */ RIMWORLD_APP_ID,
                    /* installToGameNameDirectory */ false,    // land directly in installDir
                    /* installDirectory */ installDir,          // ABSOLUTE — required on Android
                    /* branch */ "public",
                    /* branchPassword */ "",
                    /* downloadAllPlatforms */ false,
                    /* os */ "linux",
                    /* downloadAllArchs */ false,
                    /* osArch */ "64",
                    /* downloadAllLanguages */ false,
                    /* language */ "english",
                    /* lowViolence */ false,
                    /* depot */ List.of(RIMWORLD_LINUX_DEPOT),  // explicit → skip the DLC-depot iteration
                    /* manifest */ manifests,                   // empty = latest; pinned = a specific version
                    /* verify */ false,
                    /* downloadManifestOnly */ manifestOnly);

            dd.add(rimworld);
            dd.finishAdding();
            dd.awaitCompletion();           // blocks until the queue drains (or fails)
            dd.removeListener(this);

            if (lastError == null) {
                downloadCompleted = true;
                if (!manifestOnly) finalizeInstance();
                done(manifestOnly ? "Manifest fetched OK (no content downloaded)."
                        : "Instance '" + instanceName + "' downloaded — ready to launch.");
                running = false;
                try { steamUser.logOff(); } catch (Throwable ignored) {}
            } else {
                // Failed — most likely the CM dropped. onDisconnected will reconnect + retry (until
                // MAX_DOWNLOAD_ATTEMPTS). If we're still connected (a non-disconnect error) and out
                // of attempts, give up cleanly.
                progress("attempt " + downloadAttempts + " failed: " + describe(lastError));
                if (downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS) {
                    done("download FAILED after " + downloadAttempts + " attempts: " + describe(lastError));
                    running = false;
                    try { steamUser.logOff(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Download attempt crashed", t);
            lastError = t;
            if (downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS) {
                done("download error: " + describe(t));
                running = false;
                try { steamUser.logOff(); } catch (Throwable ignored) {}
            }
        } finally {
            downloadInProgress = false;
        }
    }

    // ---- IDownloadListener ----
    @Override public void onItemAdded(@NonNull DownloadItem item) { Log.i(TAG, "queued app " + item.getAppId()); }
    @Override public void onDownloadStarted(@NonNull DownloadItem item) { progress("Download started (app " + item.getAppId() + ")"); }
    @Override public void onDownloadCompleted(@NonNull DownloadItem item) { progress("Download completed (app " + item.getAppId() + ")"); }
    @Override public void onDownloadFailed(@NonNull DownloadItem item, @NonNull Throwable error) {
        Log.e(TAG, "download failed app " + item.getAppId(), error);
        lastError = error;
        progress("FAILED: " + describe(error));
    }
    @Override public void onStatusUpdate(@NonNull String message) { progress(message); }
    @Override public void onFileCompleted(int depotId, @NonNull String fileName, float depotPercentComplete) {
        progress(String.format("depot %d: %.1f%%  (%s)", depotId, depotPercentComplete * 100f, fileName));
    }
    @Override public void onChunkCompleted(int depotId, float depotPercentComplete, long compressedBytes, long uncompressedBytes) {
        progress(String.format("depot %d: %.1f%%  (%d MB)", depotId, depotPercentComplete * 100f,
                uncompressedBytes / (1024 * 1024)));
        if (listener != null) listener.onPercent(Math.round(depotPercentComplete * 100f));
    }
    @Override public void onDepotCompleted(int depotId, long compressedBytes, long uncompressedBytes) {
        progress("depot " + depotId + " done: " + uncompressedBytes + " bytes (" + compressedBytes + " compressed)");
    }

    /**
     * Make the freshly-downloaded instance launchable: mark the binary executable, set it as the
     * active instance, and refresh the instance list so the launcher picks it up. Mirrors the tail
     * of InstallerService.installInstance (minus the zip-flatten, which depot layout doesn't need).
     */
    private void finalizeInstance() {
        try {
            File bin = new File(installDir, C.files.RIMWORLD_BIN);
            if (bin.exists()) bin.setExecutable(true, false);
            // Install-time save fix (box64 IMT-thunk bypass) if this RimWorld build is covered; else no-op.
            if (RimDroidApplication.APP != null)
                SaveFixInstaller.applyTo(RimDroidApplication.APP, new File(installDir));
            LauncherPreferences.requireSingleton().setLastInstanceName(instanceName);
            GameInstanceManager.requireSingleton().reload();
            progress("Instance '" + instanceName + "' is now installed (" + RIMWORLD_APP_ID + ").");
        } catch (Throwable t) {
            Log.e(TAG, "finalizeInstance failed", t);
        }
    }

    /** Exception class + message + first useful cause/frame — getMessage() alone is often null. */
    private static String describe(Throwable t) {
        if (t == null) return "null";
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        if (c != null && c != t) {
            sb.append("  <- ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
        }
        StackTraceElement[] st = t.getStackTrace();
        if (st != null && st.length > 0) sb.append("  @ ").append(st[0]);
        return sb.toString();
    }

    /** Approves via Steam Mobile push when possible; otherwise asks the UI for a Steam Guard code. */
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
