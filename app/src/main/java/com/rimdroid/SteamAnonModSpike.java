/*
 * SPDX-License-Identifier: MIT
 *
 * Anonymous Steam Workshop depot-key spike — original work by udarmolota for RimDroid.
 * Copyright (c) 2026 udarmolota
 *
 * This single file is licensed under the MIT License (not the GPL-3.0 of the rest of RimDroid),
 * matching SteamAnonModDownloader.java, so the author can reuse it across her own projects
 * (e.g. Zomdroid) and others may reuse it too, as long as this notice is preserved. MIT is
 * GPL-compatible, so this does not affect the GPL-3.0 licensing of the rest of the project.
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

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSTokensCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.types.AsyncJobMultiple;
import in.dragonbra.javasteam.types.KeyValue;
import in.dragonbra.javasteam.util.log.DefaultLogListener;
import in.dragonbra.javasteam.util.log.LogManager;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * SPIKE — de-risk ANONYMOUS Workshop mod download (memory in_app_game_downloader.md "ANONYMOUS mods").
 *
 * Anonymous Workshop UGC is possible at the protocol level (the reference app Apricityx does it), but
 * JavaSteam's high-level DepotDownloader GATES anonymous UGC, so we must drive the lower-level pieces
 * ourselves. The WHOLE thing hinges on ONE unknown: can an ANONYMOUS account obtain the DEPOT KEY for
 * RimWorld's workshop depot (workshop UGC chunks are AES-encrypted with it)? RimWorld is paid, so an
 * anon account has no game license — but Workshop content is publicly accessible, so the key MAY be
 * granted anyway. This spike tests exactly that BEFORE we write the full manifest+CDN+assemble pipeline:
 *
 *   1. connect + logOnAnonymous
 *   2. PICS product info for app 294100 → depots.workshopdepot (the UGC depot id)
 *   3. getDepotDecryptionKey(workshopDepot, 294100)  ← make-or-break
 *
 * OK  → anonymous mod download is viable → build the full pipeline (getPublishedFileDetails → manifest
 *       request code → CDN auth token → download manifest + chunks → decrypt(depotKey)/decompress → zip).
 * FAIL→ anonymous is not possible for RimWorld; keep the ggntw browser path for no-login mods.
 *
 * Run on a background thread (blocks in the callback loop). Pure network client, no UI.
 */
public class SteamAnonModSpike implements Runnable {

    private static final String TAG = "RimDroid/SteamAnon";
    public static final int RIMWORLD_APP_ID = 294100;

    public interface Listener {
        void onProgress(String message);
        void onDone(String message);
    }

    private final Listener listener;
    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;
    private volatile boolean running;

    public SteamAnonModSpike(Listener listener) { this.listener = listener; }

    private void progress(String m) { Log.i(TAG, m); if (listener != null) listener.onProgress(m); }
    private void done(String m)     { Log.i(TAG, "DONE: " + m); if (listener != null) listener.onDone(m); }

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
            Log.i(TAG, "Anon spike loop ended");
        } catch (Throwable t) {
            Log.e(TAG, "SteamAnonModSpike crashed", t);
            done("crash: " + t);
        }
    }

    private void onConnected(ConnectedCallback cb) {
        progress("Connected. Logging on ANONYMOUSLY (no account)...");
        steamUser.logOnAnonymous();
    }

    private void onDisconnected(DisconnectedCallback cb) {
        if (running) progress("Disconnected.");
        running = false;
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("Anonymous logon FAILED: " + cb.getResult());
            running = false;
            return;
        }
        progress("Anonymous logon OK. Probing workshop depot + depot key...");
        new Thread(this::probe, "rd-anon-probe").start();
    }

    private void probe() {
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);

            // Public access token for the app's product info (anon may still get the public token).
            long appToken = 0L;
            try {
                PICSTokensCallback tk = apps.picsGetAccessTokens(
                        Collections.singletonList(RIMWORLD_APP_ID), Collections.<Integer>emptyList())
                        .toFuture().get(30, TimeUnit.SECONDS);
                Long t = tk.getAppTokens().get(RIMWORLD_APP_ID);
                if (t != null) appToken = t;
                progress("App access token: " + (appToken != 0 ? "got" : "none (anon)"));
            } catch (Throwable ignored) { progress("App access token: unavailable (trying without)"); }

            // PICS product info → depots.workshopdepot
            List<PICSRequest> appReqs =
                    Collections.singletonList(new PICSRequest(RIMWORLD_APP_ID, appToken));
            AsyncJobMultiple.ResultSet<PICSProductInfoCallback> rs =
                    apps.picsGetProductInfo(appReqs, Collections.<PICSRequest>emptyList())
                        .toFuture().get(60, TimeUnit.SECONDS);

            int workshopDepot = -1;
            for (PICSProductInfoCallback c : rs.getResults()) {
                PICSProductInfo app = c.getApps().get(RIMWORLD_APP_ID);
                if (app != null) {
                    KeyValue wd = app.getKeyValues().get("depots").get("workshopdepot");
                    workshopDepot = wd.asInteger(-1);
                }
            }
            progress("RimWorld workshopdepot = " + workshopDepot);
            if (workshopDepot <= 0) {
                done("Could not resolve workshopdepot from PICS as anon (PICS may need a license/token).");
                running = false;
                return;
            }

            // === THE make-or-break: can anon get the workshop depot's decryption key? ===
            progress("Requesting depot key for depot " + workshopDepot + " (ANONYMOUS)...");
            DepotKeyCallback dk = apps.getDepotDecryptionKey(workshopDepot, RIMWORLD_APP_ID)
                    .toFuture().get(30, TimeUnit.SECONDS);
            if (dk.getResult() == EResult.OK && dk.getDepotKey() != null && dk.getDepotKey().length > 0) {
                done("✅ ANON DEPOT KEY OK (depot " + workshopDepot + ", keyLen=" + dk.getDepotKey().length
                        + ") → anonymous mod download is VIABLE. Building the full pipeline is the next step.");
            } else {
                done("❌ Anon depot key DENIED: " + dk.getResult()
                        + " → anonymous download not possible for RimWorld; keep the ggntw browser path.");
            }
        } catch (Throwable t) {
            Log.e(TAG, "probe failed", t);
            done("probe error: " + t);
        } finally {
            running = false;
            try { steamUser.logOff(); } catch (Throwable ignored) {}
        }
    }
}
