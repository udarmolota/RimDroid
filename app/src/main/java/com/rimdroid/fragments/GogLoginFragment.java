package com.rimdroid.fragments;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.rimdroid.GogAuth;
import com.rimdroid.GogLibrary;
import com.rimdroid.R;

import java.util.List;

/**
 * GOG sign-in screen: a WebView on GOG's own login page.
 *
 * <p>We never see the password — GOG's page handles it, along with the CAPTCHA, e-mail two-step and
 * TOTP prompts a headless login could not clear. We only watch for the navigation to Galaxy's
 * redirect target and take the authorisation code out of it; the cookies the page leaves behind in
 * Android's store are the other half of the credentials (see {@link GogAuth}).
 *
 * <p>When a session already exists the WebView is skipped and the screen offers the library probe
 * instead. That probe is temporary scaffolding: {@link GogLibrary} is written from GOG's API docs
 * and lgogdownloader's source but has never run against a real account, and building the downloader
 * on top of unverified reads would mean debugging two unknowns at once.
 */
public class GogLoginFragment extends Fragment {
    private static final String TAG = "RimDroid/GOG";

    /** How many products to open in one probe run — a full library would be dozens of requests. */
    private static final int PROBE_PRODUCT_LIMIT = 8;

    private WebView web;
    private ProgressBar progress;
    private TextView status;
    private View signedInBlock;
    /** The redirect fires for the page load AND its sub-resources; only act on the first one. */
    private boolean codeTaken = false;
    /** True while we are loading www.gog.com purely to make GOG hand us a www session cookie. */
    private boolean establishingSession = false;

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

        v.findViewById(R.id.btn_gog_check).setOnClickListener(x -> probeLibrary());
        v.findViewById(R.id.btn_gog_sign_out).setOnClickListener(x -> {
            GogAuth.signOut();
            status.setText("");
            showSignedIn(false);
            codeTaken = false;
            web.loadUrl(GogAuth.authUrl());
        });

        if (GogAuth.isSignedIn()) {
            showSignedIn(true);
            status.setText(getString(R.string.gog_signed_in_as, String.valueOf(GogAuth.userId())));
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

    /** Swap between the login WebView and the signed-in controls. */
    private void showSignedIn(boolean signedIn) {
        signedInBlock.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        web.setVisibility(signedIn ? View.GONE : View.VISIBLE);
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
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
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
        status.setText(getString(R.string.gog_signed_in_as, String.valueOf(GogAuth.userId())));
    }

    /**
     * Read the library and print what came back. Answers the three things we cannot know until a
     * real account replies: whether GOG accepts our cookies, whether we read the platform bitmask
     * correctly, and whether Linux installers are visible at all.
     */
    private void probeLibrary() {
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.gog_checking);

        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            boolean linkChecked = false;
            try {
                List<Long> owned = GogLibrary.ownedIds();
                sb.append(getString(R.string.gog_probe_owned, owned.size())).append("\n\n");

                int shown = 0;
                for (Long id : owned) {
                    if (shown >= PROBE_PRODUCT_LIMIT) {
                        sb.append("… ").append(owned.size() - shown).append(" more\n");
                        break;
                    }
                    shown++;
                    GogLibrary.Product p = GogLibrary.details(id);
                    if (p == null) { sb.append(id).append(": (not readable)\n"); continue; }
                    sb.append(p.title).append('\n')
                      .append("   files=").append(p.files.size())
                      .append("  linux installers=").append(p.linuxInstallers().size())
                      .append("  dlc=").append(p.dlcs.size()).append('\n');
                    for (GogLibrary.File f : p.linuxInstallers())
                        sb.append("   • ").append(f.name).append(" [").append(f.size).append("]\n");

                    // Resolve exactly one link — the first Linux installer we meet. The listing
                    // above rides on the cookies; this is the only thing that exercises the token
                    // half of the split, and one request proves it without pulling half a gigabyte.
                    if (!linkChecked && !p.linuxInstallers().isEmpty()) {
                        linkChecked = true;
                        GogLibrary.File f = p.linuxInstallers().get(0);
                        try {
                            // Host only: the resolved link is signed and account-bound.
                            sb.append("   link OK → ")
                              .append(hostOf(GogLibrary.resolveDownload(f))).append('\n');
                        } catch (Exception linkError) {
                            sb.append("   link FAILED: ").append(linkError.getMessage()).append('\n');
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "library probe failed", t);
                sb.append(getString(R.string.gog_probe_failed, String.valueOf(t.getMessage())));
            }
            final String text = sb.toString();
            Log.i(TAG, "library probe:\n" + text);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                progress.setVisibility(View.GONE);
                status.setText(text);
            });
        }, "rd-gog-probe").start();
    }

    /** Host of a resolved download link — the rest of it is a credential and is not shown. */
    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "(unparseable host)";
        }
    }

    @Override
    public void onDestroyView() {
        if (web != null) {
            web.stopLoading();
            web.setWebViewClient(new WebViewClient());
            web.destroy();
            web = null;
        }
        super.onDestroyView();
    }
}
