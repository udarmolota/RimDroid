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

    public boolean isDownloading() { return downloading; }
    public boolean isCancelling() { return cancelling; }
    public CharSequence getLog() { synchronized (log) { return log.toString(); } }
    public int getPercent() { return percent; }
    public boolean isIndeterminate() { return indeterminate; }
    public String getAdviseInstance() { return adviseInstance; }

    /** Register the running downloader so the user can cancel it. */
    public void setActive(Cancellable c) { active = c; cancelling = false; }

    /** Stop the running download: the downloader flags itself to bail and interrupts its blocking I/O. */
    public void cancel() {
        if (!downloading) return;
        cancelling = true;
        appendLine("Cancelling…");
        main.post(() -> { if (view != null) view.onLog(getLog()); });
        Cancellable c = active;
        if (c != null) c.cancel();
    }

    /** Called on the main thread by the fragment when it (re)appears or goes away. */
    public void setView(View v) { this.view = v; }
    public void clearView(View v) { if (this.view == v) this.view = null; }

    /** Start a new download session: clears the log and raises the keep-alive service.
     *  @param adviseInstance instance to auto-set the recommended driver on (GAME download), else null. */
    public void begin(Context context, String adviseInstance) {
        appCtx = context.getApplicationContext();
        synchronized (log) { log.setLength(0); }
        downloading = true;
        indeterminate = true;
        percent = -1;
        this.adviseInstance = adviseInstance;
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
    public void onDone(String message) {
        appendLine((isError(message) ? "✗ " : "✓ ") + message);
        downloading = false;
        cancelling = false;
        active = null;
        if (appCtx != null) DownloadKeepAliveService.stop(appCtx);
        main.post(() -> {
            if (view != null) { view.onLog(getLog()); view.onFinished(message); }
        });
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
