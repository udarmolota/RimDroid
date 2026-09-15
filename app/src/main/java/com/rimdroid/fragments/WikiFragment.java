package com.rimdroid.fragments;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/**
 * The in-app wiki: one HTML page per language in {@code assets/wiki/}, the same arrangement
 * Zomdroid uses. Opened from the drawer header, from the toolbar's "?" (at the section for the
 * screen it was pressed on) and from dialogs that point to a specific section.
 */
public class WikiFragment extends Fragment {
    /** Optional anchor ("renderers", "mods", ...) to open the page at that section. */
    public static final String ARG_SECTION = "section";

    /** Navigation arguments that open the wiki at {@code section}. */
    public static Bundle section(String section) {
        Bundle args = new Bundle();
        args.putString(ARG_SECTION, section);
        return args;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Context context = requireContext();
        WebView webView = new WebView(context);

        // The language the app's own strings resolved to, not the device default: the page names
        // buttons, and it must name them the way the screens around it do.
        String language = getResources().getConfiguration().getLocales().get(0).getLanguage();
        String wikiFile;
        switch (language) {
            case "ru": wikiFile = "index_ru.html"; break;
            case "es": wikiFile = "index_es.html"; break;
            case "pt": wikiFile = "index_pt.html"; break;
            default:   wikiFile = "index.html";
        }
        String section = getArguments() != null ? getArguments().getString(ARG_SECTION) : null;
        // A page without that anchor simply opens at the top.
        final String url = "file:///android_asset/wiki/" + wikiFile
                + (section != null && section.matches("[a-z0-9-]+") ? "#" + section : "");
        // Load only once the WebView has its real width. The page comes from assets and finishes
        // loading before the view is laid out; Chromium then works out the #section offset on a
        // layout of almost no width — every word on its own line, the page several times taller —
        // and does not re-apply it after the resize. The stale offset lands far below the section
        // (seen on device 2026-09-15: "Quick start" opened at the GOG section, every "?" too low).
        webView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right - left <= 0) return;
                v.removeOnLayoutChangeListener(this);
                ((WebView) v).loadUrl(url);
            }
        });

        // Match the app's background so the page does not flash white in the dark theme. The
        // attribute can hold a colour resource or a literal colour; handle both.
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true);
        webView.setBackgroundColor(tv.resourceId != 0
                ? ContextCompat.getColor(context, tv.resourceId) : tv.data);

        return webView;
    }
}
