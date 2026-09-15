package com.rimdroid.fragments;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.rimdroid.AppStorage;
import com.rimdroid.GogAuth;
import com.rimdroid.GogDownloadQueue;
import com.rimdroid.GogDownloader;
import com.rimdroid.GogLibrary;
import com.rimdroid.R;
import com.rimdroid.StorageAccess;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * GOG sign-in screen: a WebView on GOG's own login page.
 *
 * <p>We never see the password — GOG's page handles it, along with the CAPTCHA, e-mail two-step and
 * TOTP prompts a headless login could not clear. We only watch for the navigation to Galaxy's
 * redirect target and take the authorisation code out of it; the cookies the page leaves behind in
 * Android's store are the other half of the credentials (see {@link GogAuth}).
 *
 * <p>When a session already exists the WebView is skipped and the screen lists what the account owns
 * that RimDroid can install: one card per installer, a game's expansions indented beneath it. A
 * card's button follows its file — download, queued, percentage, then the next step: create an
 * instance from a game, install an expansion into one. The transfers belong to
 * {@link GogDownloadQueue}, not to this screen, so leaving it and coming back loses nothing.
 */
public class GogLoginFragment extends Fragment implements GogDownloadQueue.Listener {
    private static final String TAG = "RimDroid/GOG";

    /**
     * How many products one scan will open. Titles are only knowable by opening each product (see
     * {@link #probeLibrary}), so this is a ceiling on requests, not on what the user owns.
     */
    private static final int SCAN_LIMIT = 100;

    /**
     * The last scan's result, kept for the life of the process: coming back to this screen then
     * shows the cards — and any download still running — straight away, instead of an empty list
     * and another round of requests.
     */
    private static volatile List<GogLibrary.Product> lastScan;
    private static volatile boolean lastScanFellBack;

    private WebView web;
    private ProgressBar progress;
    private TextView status;
    private View signedInBlock;
    /** "User ID: …" under "Signed in to GOG" in the signed-in block. */
    private TextView userLine;
    /** The redirect fires for the page load AND its sub-resources; only act on the first one. */
    private boolean codeTaken = false;
    /** True while we are loading www.gog.com purely to make GOG hand us a www session cookie. */
    private boolean establishingSession = false;
    /** Holds one item_gog_game card per installer. */
    private ViewGroup gameList;
    /** The scroll around gameList; visible only while signed in (see showSignedIn). */
    private View gameScroll;
    /** The cards on screen, so that a queue update can restyle them in place. */
    private final List<Card> cards = new ArrayList<>();
    /**
     * Worker threads reach the UI only through this (see {@link #onUi}). requireActivity() and
     * getString() both throw once the user has left the screen, and a scan can easily outlive it.
     */
    private final Handler main = new Handler(Looper.getMainLooper());

    /** One card on screen and the installer behind it. */
    private static final class Card {
        /** The game this card belongs to — for an expansion, the game it expands. */
        final GogLibrary.Product game;
        final GogLibrary.File file;
        final boolean dlc;
        final TextView subtitle;
        final Button button;
        /** Size and version: what the subtitle says whenever there is no error to report. */
        final String details;

        Card(GogLibrary.Product game, GogLibrary.File file, boolean dlc,
             TextView subtitle, Button button, String details) {
            this.game = game;
            this.file = file;
            this.dlc = dlc;
            this.subtitle = subtitle;
            this.button = button;
            this.details = details;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_gog_login, container, false);
    }

    @Override
    @SuppressLint("SetJavaScriptEnabled")   // GOG's login page does not work without it
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        web           = v.findViewById(R.id.web_gog);
        progress      = v.findViewById(R.id.progress_gog);
        status        = v.findViewById(R.id.tv_gog_status);
        signedInBlock = v.findViewById(R.id.block_gog_signed_in);
        userLine      = v.findViewById(R.id.tv_gog_user);
        gameList      = v.findViewById(R.id.gog_game_list);
        gameScroll    = v.findViewById(R.id.scroll_gog_games);

        // Disclaimer card: collapsed by default; tapping the header expands or collapses it and the
        // arrow flips to match — the same behaviour as Settings → Advanced. Wired before the
        // signed-in early return below, because the card is on screen in both states.
        final TextView discHeader = v.findViewById(R.id.tv_gog_disclaimer_header);
        final View discText = v.findViewById(R.id.tv_gog_disclaimer_text);
        discHeader.setOnClickListener(x -> {
            boolean show = discText.getVisibility() != View.VISIBLE;
            discText.setVisibility(show ? View.VISIBLE : View.GONE);
            discHeader.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                    show ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float, 0);
        });

        v.findViewById(R.id.btn_gog_check).setOnClickListener(x -> probeLibrary());
        v.findViewById(R.id.btn_gog_sign_out).setOnClickListener(x -> {
            GogAuth.signOut();
            lastScan = null;
            status.setText("");
            clearCards();
            showSignedIn(false);
            codeTaken = false;
            web.loadUrl(GogAuth.authUrl());
        });

        GogDownloadQueue.get().setListener(this);

        if (GogAuth.isSignedIn()) {
            showSignedIn(true);
            List<GogLibrary.Product> shown = lastScan;
            // Who is signed in is shown in the signed-in block; the status line is for notes only.
            status.setText(shown != null && lastScanFellBack ? getString(R.string.gog_no_rimworld) : "");
            if (shown != null) render(shown);
            return;
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handle(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // The redirect can arrive as a plain navigation rather than one we get to override,
                // so check here too. handle() is idempotent.
                handle(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (establishingSession && url != null && url.contains("gog.com")) finishSignIn();
            }
        });

        web.loadUrl(GogAuth.authUrl());
    }

    /**
     * Swap between the login WebView and the signed-in controls. The game list and the WebView share
     * the leftover height, so exactly one of them may be visible — both at once split the screen in
     * half and pushed the sign-in page to the bottom under an empty list.
     */
    private void showSignedIn(boolean signedIn) {
        signedInBlock.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        gameScroll.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        web.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        if (signedIn) userLine.setText(getString(R.string.gog_user_id, String.valueOf(GogAuth.userId())));
    }

    /** @return true when the URL was the login callback and we consumed it. */
    private boolean handle(String url) {
        String code = GogAuth.codeFrom(url);
        if (code == null || codeTaken) return false;
        codeTaken = true;

        web.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.gog_login_finishing);

        new Thread(() -> {
            String error = null;
            try {
                // Flush now: the tokens are useless without the cookies the page just set, and
                // Android writes them out lazily.
                CookieManager.getInstance().flush();
                GogAuth.signInWithCode(code);
            } catch (Exception e) {
                Log.e(TAG, "sign-in failed", e);
                error = e.getMessage();
            }
            final String err = error;
            onUi(() -> {
                if (err == null) {
                    establishSession();
                } else {
                    progress.setVisibility(View.GONE);
                    status.setText(getString(R.string.gog_login_failed, err));
                }
            });
        }, "rd-gog-token").start();
        return true;
    }

    /**
     * Having the tokens is not enough to read the library: those endpoints live on www.gog.com and
     * authorise by cookie, but the OAuth flow only ever touched auth/login/embed, so no www cookie
     * exists yet (device check, 2026-09-14: the store held galaxy-login-al and galaxy-login-s on
     * login.gog.com and nothing else). Load one www page so GOG turns that login into a www
     * session. If it never finishes we carry on anyway — GogAuth.cookieHeader() falls back to
     * sending the login-host cookies, which may well be accepted.
     */
    private void establishSession() {
        establishingSession = true;
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.gog_login_finishing);
        web.loadUrl("https://www.gog.com/account");
        status.postDelayed(() -> { if (establishingSession) finishSignIn(); }, 15000);
    }

    /** Settle the sign-in once the www page has loaded (or we gave up waiting for it). */
    private void finishSignIn() {
        if (!establishingSession || !isAdded()) return;
        establishingSession = false;
        CookieManager.getInstance().flush();
        progress.setVisibility(View.GONE);
        Toast.makeText(requireContext(), R.string.gog_login_ok, Toast.LENGTH_SHORT).show();
        showSignedIn(true);
        status.setText("");
    }

    /**
     * List what the account owns that RimDroid can install.
     *
     * <p>GOG's owned-games endpoint returns bare ids, so a title is only knowable by opening each
     * product — one request per game, which is why the scan reports its progress and stops at
     * {@link #SCAN_LIMIT}. Everything with a Linux installer is collected on the way through, so
     * that an account with no RimWorld still has something to show rather than an empty screen.
     */
    private void probeLibrary() {
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.gog_checking);
        clearCards();

        new Thread(() -> {
            List<GogLibrary.Product> rimworld = new ArrayList<>();
            List<GogLibrary.Product> anyLinux = new ArrayList<>();
            Throwable error = null;
            try {
                List<Long> owned = GogLibrary.ownedIds();
                int of = Math.min(owned.size(), SCAN_LIMIT);
                int scanned = 0;
                for (Long id : owned) {
                    if (scanned >= SCAN_LIMIT) break;
                    // Resolved on the UI side: a fragment the user has left has no resources.
                    final int at = ++scanned;
                    onUi(() -> status.setText(getString(R.string.gog_scanning, at, of)));

                    GogLibrary.Product p = GogLibrary.details(id);
                    if (p == null || p.linuxInstallers().isEmpty()) continue;
                    anyLinux.add(p);
                    // A game's expansions come nested in its own details — a DLC's own product id
                    // answers with [] (seen 2026-09-15: Prison Architect carried Gangs inside it) —
                    // so matching the game brings them along.
                    if (isRimWorld(p.title)) rimworld.add(p);
                }
            } catch (Throwable t) {
                Log.e(TAG, "library scan failed", t);
                error = t;
            }

            final List<GogLibrary.Product> show = rimworld.isEmpty() ? anyLinux : rimworld;
            final boolean fellBack = rimworld.isEmpty() && !anyLinux.isEmpty();
            final Throwable err = error;
            if (err == null && !show.isEmpty()) {
                lastScan = show;
                lastScanFellBack = fellBack;
            }
            Log.i(TAG, "library scan: " + rimworld.size() + " rimworld, "
                    + anyLinux.size() + " with a linux installer");
            onUi(() -> {
                progress.setVisibility(View.GONE);
                if (err != null) {
                    status.setText(getString(R.string.gog_probe_failed,
                            String.valueOf(err.getMessage())));
                    return;
                }
                if (show.isEmpty()) { status.setText(R.string.gog_nothing_linux); return; }
                status.setText(fellBack ? getString(R.string.gog_no_rimworld) : "");
                render(show);
            });
        }, "rd-gog-probe").start();
    }

    /** Lay out the cards: each game, then its expansions indented beneath it. */
    private void render(List<GogLibrary.Product> games) {
        clearCards();
        for (GogLibrary.Product game : games) {
            for (GogLibrary.File f : oneLanguage(game.linuxInstallers()))
                addCard(game, game.title, f, false);
            for (GogLibrary.Product dlc : game.dlcs)
                for (GogLibrary.File f : oneLanguage(dlc.linuxInstallers()))
                    addCard(game, dlc.title, f, true);
        }
        refreshCards();
    }

    private void addCard(GogLibrary.Product game, String title, GogLibrary.File f, boolean dlc) {
        View row = getLayoutInflater().inflate(R.layout.item_gog_game, gameList, false);
        if (dlc) {
            // Indented under its game, so the list reads as "this game, and what expands it".
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
            lp.setMarginStart(Math.round(24 * getResources().getDisplayMetrics().density));
            row.setLayoutParams(lp);
        }
        TextView titleView = row.findViewById(R.id.gog_item_title);
        titleView.setText(title == null || title.isEmpty() ? f.name : title);

        String version = f.version == null ? "" : f.version.trim();
        String details = version.isEmpty()
                ? f.size : getString(R.string.gog_item_installer, f.size, version);
        if (dlc) details = getString(R.string.gog_item_dlc, details);

        Card card = new Card(game, f, dlc, row.findViewById(R.id.gog_item_subtitle),
                row.findViewById(R.id.gog_item_download), details);
        card.button.setOnClickListener(x -> onCardButton(card));
        cards.add(card);
        gameList.addView(row);
    }

    private void clearCards() {
        cards.clear();
        gameList.removeAllViews();
    }

    @Override
    public void onQueueChanged() {
        if (getView() != null) refreshCards();
    }

    /** Bring every card's button and subtitle in line with the state of its file. */
    private void refreshCards() {
        File destDir = AppStorage.requireSingleton().getDownloadsDir();
        GogDownloadQueue queue = GogDownloadQueue.get();
        for (Card c : cards) {
            GogDownloadQueue.Entry e = queue.entryFor(c.file, destDir);
            GogDownloadQueue.Phase phase = e == null ? null : e.phase;
            c.subtitle.setText(c.details);
            if (phase == GogDownloadQueue.Phase.QUEUED) {
                c.button.setText(R.string.gog_queued);
                c.button.setEnabled(false);
            } else if (phase == GogDownloadQueue.Phase.RUNNING) {
                c.button.setText(e.percent < 0
                        ? getString(R.string.gog_downloading)
                        : getString(R.string.gog_percent, e.percent));
                c.button.setEnabled(false);
            } else if (GogDownloader.completed(c.file, destDir) != null) {
                c.button.setText(c.dlc ? R.string.gog_install_dlc : R.string.gog_create_instance);
                // Only RimWorld goes further. Another game is listed when the account has no
                // RimWorld, so downloads can still be tried, but its next step stays shut: the
                // installer would reject the game, and the DLC screen cannot tell whose expansion
                // it is given — it would unpack it into a RimWorld instance.
                c.button.setEnabled(isRimWorld(c.game.title));
            } else {
                // Never asked for, downloaded and since deleted, or failed: offer it (again).
                if (phase == GogDownloadQueue.Phase.FAILED) c.subtitle.setText(failureText(e.failure));
                c.button.setText(R.string.gog_download);
                c.button.setEnabled(true);
            }
        }
    }

    /** A card's button: the next step for a file already on the phone, otherwise download it. */
    private void onCardButton(Card c) {
        File destDir = AppStorage.requireSingleton().getDownloadsDir();
        File done = GogDownloader.completed(c.file, destDir);
        if (done != null) {
            if (!isRimWorld(c.game.title)) return;   // shut for other games — see refreshCards
            if (c.dlc) openInstallDlc(done);
            else openNewInstance(c.game, done, destDir);
            return;
        }
        // The public Download/RimDroid folder is the point (the user can see and reuse the file),
        // and writing raw paths there needs All-files access on Android 11+.
        if (!StorageAccess.hasAllFilesAccess()) {
            status.setText(R.string.gog_needs_all_files);
            StorageAccess.requestAllFilesAccess(requireContext());
            return;
        }
        GogDownloadQueue.get().enqueue(requireContext(), c.file, destDir);
    }

    /**
     * Hand the game — and every expansion of it already on the phone — to the instance-creation
     * screen, so a new instance comes up complete in one step rather than one trip through the
     * install screen per expansion. The path goes over directly: that screen's own picker is
     * ZIP-only, and most file explorers will not show it a bare .sh anyway.
     */
    private void openNewInstance(GogLibrary.Product game, File gameFile, File destDir) {
        ArrayList<String> extras = new ArrayList<>();
        for (Card c : cards) {
            if (!c.dlc || c.game != game) continue;
            File dlc = GogDownloader.completed(c.file, destDir);
            if (dlc != null) extras.add(dlc.getAbsolutePath());
        }
        Bundle args = new Bundle();
        args.putString(NewInstanceFragment.ARG_PRESELECTED_FILE, gameFile.getAbsolutePath());
        args.putStringArrayList(NewInstanceFragment.ARG_EXTRA_FILES, extras);
        NavHostFragment.findNavController(this).navigate(R.id.action_install_instance, args);
    }

    /** Hand an expansion to the install screen. Which instance it goes into stays the user's call. */
    private void openInstallDlc(File dlcFile) {
        Bundle args = new Bundle();
        args.putString(InstallContentFragment.ARG_PRESELECTED_FILE, dlcFile.getAbsolutePath());
        NavHostFragment.findNavController(this).navigate(R.id.action_open_install, args);
    }

    /**
     * Matches the game by title, loosely about spacing and suffixes: GOG's titles carry edition
     * names, and missing the game over one is worse than showing a card the user can ignore.
     */
    private static boolean isRimWorld(String title) {
        return title != null
                && title.toLowerCase(java.util.Locale.US).replace(" ", "").contains("rimworld");
    }

    /**
     * GOG lists the same installer once per language, which would put a dozen identical cards on
     * screen. Keep a single language — English when the account has it, otherwise whichever came
     * first — so that a multi-part installer still shows every one of its parts.
     */
    private static List<GogLibrary.File> oneLanguage(List<GogLibrary.File> installers) {
        String want = null;
        for (GogLibrary.File f : installers)
            if ("English".equalsIgnoreCase(f.language)) { want = f.language; break; }
        if (want == null && !installers.isEmpty()) want = installers.get(0).language;

        List<GogLibrary.File> out = new ArrayList<>();
        for (GogLibrary.File f : installers)
            if (want == null || want.equals(f.language)) out.add(f);
        return out;
    }

    /**
     * The line to show when a download stops early. A dropped connection gets its own message: it
     * is the everyday failure on a phone, and a recoverable one — what already arrived stays in the
     * part file for the next attempt — so the user is told to tap again rather than shown a socket
     * error. The exception itself is in the log either way. UI thread only: it reads the fragment's
     * resources.
     */
    private String failureText(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            boolean dropped = c instanceof java.net.SocketException           // incl. refused connects
                    || c instanceof java.net.SocketTimeoutException
                    || c instanceof java.net.UnknownHostException             // no network at all
                    || (c instanceof javax.net.ssl.SSLException
                        && !(c instanceof javax.net.ssl.SSLHandshakeException));
            if (dropped)
                return getString(R.string.gog_download_interrupted, getString(R.string.gog_download));
        }
        return getString(R.string.gog_download_failed, t == null ? "?" : String.valueOf(t.getMessage()));
    }

    /** Run {@code r} on the UI thread — or drop it, if the screen is gone by the time it gets there. */
    private void onUi(Runnable r) {
        main.post(() -> { if (isAdded()) r.run(); });
    }

    @Override
    public void onDestroyView() {
        GogDownloadQueue.get().clearListener(this);
        cards.clear();
        if (web != null) {
            web.stopLoading();
            web.setWebViewClient(new WebViewClient());
            web.destroy();
            web = null;
        }
        super.onDestroyView();
    }
}
