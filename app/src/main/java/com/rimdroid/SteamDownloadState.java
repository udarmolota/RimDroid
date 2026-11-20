package com.rimdroid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CompletableFuture;

/**
 * Process-wide state for an in-progress Steam download. The download itself runs on a background
 * thread that outlives the DownloadFragment, so this singleton holds the log buffer / progress /
 * running flag and re-attaches whichever {@link View} (fragment) is currently on screen. That lets
 * the user leave the download screen and come back without losing the log or the "still downloading"
 * state — and gives a single place to cancel the running download.
 *
 * It is the {@link SteamDownloadSpike.Listener} (game + DLC) AND {@link SteamAnonModDownloader.Listener}
 * (anonymous mods) passed to the downloaders, so all their callbacks land here first, then forward to
 * the attached view.
 */
public final class SteamDownloadState
        implements SteamDownloadSpike.Listener, SteamAnonModDownloader.Listener {

    private static final SteamDownloadState INSTANCE = new SteamDownloadState();
    public static SteamDownloadState get() { return INSTANCE; }
    private SteamDownloadState() {}

    /** The currently-attached UI (fragment). Null when the screen isn't shown. */
    public interface View {
        void onLog(CharSequence fullLog);
        void onPercent(int percent, boolean indeterminate);
        void onFinished(String message);
        CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private volatile boolean downloading;
    private volatile int percent = -1;
    private volatile boolean indeterminate = true;
    private View view;
    private Context appCtx;
    private volatile Cancellable active;
    private volatile boolean cancelling;
    /** Instance to auto-set the recommended GPU driver on after a successful GAME download (else null). */
    private volatile String adviseInstance;
    /** Completion must survive a detached/recreated DownloadFragment: driver setup is triggered there. */
    private String pendingFinishedMessage;
    /** Driver selection is applied by the worker; the UI only consumes this result for its dialog. */
    private GpuDriverAdvisor.Result pendingDriverResult;
    /** The zip of the last GAME download (instances are created from it), else null. */
    private volatile java.io.File gameZip;
    /** Mods to install into an instance once their download is over (see {@link ModInstall}), else null. */
    private ModInstall modInstall;

    /**
     * "Install missing" from the mod sorter: the Workshop items to download and the instance they go
     * into. The downloader fills {@link #zips}; whatever it could not fetch anonymously (a paid or
     * private item) is reported for the player to get by hand, with what was never downloadable.
     */
    public static final class ModInstall {
        public final String instance;
        /** Workshop ID → the name to report it by, in the order asked for. */
        public final java.util.Map<Long, String> wanted;
        /** Lines for the report that need no download attempt to know (DLCs, no Workshop ID). */
        public final java.util.List<String> manual;
        /** The packageIds the dependent mods asked for: switched on once installed. */
        public final java.util.List<String> packageIds;
        /** Workshop ID → its downloaded zip. */
        public final java.util.Map<Long, java.io.File> zips = new java.util.LinkedHashMap<>();

        public ModInstall(String instance, java.util.Map<Long, String> wanted, java.util.List<String> manual,
                          java.util.List<String> packageIds) {
            this.instance = instance;
            this.wanted = wanted;
            this.manual = manual;
            this.packageIds = packageIds;
        }

        /** The report lines of everything that is not going to be installed. */
        public java.util.List<String> notInstalled() {
            java.util.List<String> out = new java.util.ArrayList<>(manual);
            for (java.util.Map.Entry<Long, String> e : wanted.entrySet())
                if (!zips.containsKey(e.getKey())) out.add(e.getValue() + "  (" + e.getKey() + ")");
            return out;
        }
    }

    public boolean isDownloading() { return downloading; }
    public boolean isCancelling() { return cancelling; }
    public CharSequence getLog() { synchronized (log) { return log.toString(); } }
    public int getPercent() { return percent; }
    public boolean isIndeterminate() { return indeterminate; }
    public String getAdviseInstance() { return adviseInstance; }
    public java.io.File getGameZip() { return gameZip; }

    /** Install the mods of this download into an instance when it is over. Call right after {@link #begin}. */
    public synchronized void setModInstall(ModInstall job) { modInstall = job; }

    /** The finished download's mods to install, once; null when there is nothing to do. */
    public synchronized ModInstall takeModInstall() {
        ModInstall job = downloading ? null : modInstall;
        if (job != null) modInstall = null;
        return job;
    }

    public synchronized GpuDriverAdvisor.Result takeDriverResult() {
        GpuDriverAdvisor.Result result = pendingDriverResult;
        pendingDriverResult = null;
        return result;
    }

    /** Register the running downloader so the user can cancel it. */
    public void setActive(Cancellable c) { active = c; cancelling = false; }

    /** Stop the running download: the downloader flags itself to bail and interrupts its blocking I/O. */
    public void cancel() {
        if (!downloading) return;
        cancelling = true;
        synchronized (this) { modInstall = null; }   // a cancelled download installs nothing
        appendLine("Cancelling…");
        main.post(() -> { if (view != null) view.onLog(getLog()); });
        Cancellable c = active;
        if (c != null) c.cancel();
    }

    /** Called on the main thread by the fragment when it (re)appears or goes away. */
    public synchronized void setView(View v) {
        this.view = v;
        if (pendingFinishedMessage != null) main.post(this::deliverPendingFinished);
    }
    public synchronized void clearView(View v) { if (this.view == v) this.view = null; }

    /** Start a new download session: clears the log and raises the keep-alive service.
     *  @param adviseInstance instance to auto-set the recommended driver on (GAME download), else null. */
    public void begin(Context context, String adviseInstance) {
        appCtx = context.getApplicationContext();
        synchronized (log) { log.setLength(0); }
        downloading = true;
        indeterminate = true;
        percent = -1;
        this.adviseInstance = adviseInstance;
        gameZip = null;
        synchronized (this) {
            pendingFinishedMessage = null;
            pendingDriverResult = null;
            modInstall = null;
        }
        DownloadKeepAliveService.start(appCtx);
    }

    private void appendLine(String line) {
        synchronized (log) {
            log.append(line).append('\n');
            if (log.length() > 40000) log.delete(0, log.length() - 30000);
        }
    }

    // ---- downloader callbacks (background threads) ----
    @Override
    public void onProgress(String message) {
        appendLine(message);
        main.post(() -> { if (view != null) view.onLog(getLog()); });
    }

    @Override
    public void onPercent(int p) {
        percent = Math.max(0, Math.min(100, p));
        indeterminate = false;
        main.post(() -> { if (view != null) view.onPercent(percent, false); });
    }

    @Override
    public void onGameZip(java.io.File zip) {
        gameZip = zip;
    }

    @Override
    public synchronized void onModZip(long workshopId, java.io.File zip) {
        if (modInstall != null) modInstall.zips.put(workshopId, zip);
    }

    @Override
    public void onDone(String message) {
        appendLine((isError(message) ? "✗ " : "✓ ") + message);
        downloading = false;
        cancelling = false;
        active = null;
        GpuDriverAdvisor.Result driverResult = null;
        String instance = adviseInstance;
        if (instance != null && !isError(message)) {
            driverResult = GpuDriverAdvisor.applyRecommendedDriver(instance);
        }
        synchronized (this) {
            pendingFinishedMessage = message;
            pendingDriverResult = driverResult;
        }
        if (appCtx != null) DownloadKeepAliveService.stop(appCtx);
        main.post(this::deliverPendingFinished);
    }

    /** Deliver completion exactly once, now or when the download screen is attached again. */
    private void deliverPendingFinished() {
        final View target;
        final String message;
        synchronized (this) {
            target = view;
            message = pendingFinishedMessage;
            if (target == null || message == null) return;
            pendingFinishedMessage = null;
        }
        target.onLog(getLog());
        target.onFinished(message);
    }

    @Override
    public CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email) {
        View v = this.view;
        if (v != null) return v.requestSteamGuardCode(previousWrong, email);
        return CompletableFuture.completedFuture("");
    }

    public static boolean isError(String m) {
        if (m == null) return true;
        String s = m.toLowerCase();
        return s.contains("error") || s.contains("fail") || s.contains("crash")
                || s.contains("lost") || s.contains("cannot") || s.contains("could not")
                || s.contains("cancel");
    }
}
