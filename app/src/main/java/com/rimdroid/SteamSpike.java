package com.rimdroid;

import android.util.Log;

import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.util.log.DefaultLogListener;
import in.dragonbra.javasteam.util.log.LogManager;

import java.util.concurrent.CompletableFuture;

/**
 * SPIKE — Milestone 0 of the in-app Steam game/mod downloader (memory in_app_game_downloader.md).
 * Proves JavaSteam can log in with username+password ON THE DEVICE — exactly like DepotDownloader:
 * the credentials go to Steam, which verifies and (for mobile-authenticator accounts) sends an
 * "Approve sign-in?" push to the user's Steam Mobile app (acceptDeviceConfirmation→true). For
 * email/code accounts, the Steam Guard code is requested via the UI. On success it logs the tokens
 * and does a real logOn to confirm them usable (needed for depot access next).
 *
 * NO depot download yet — auth only. Modelled on JavaSteam sample _000_authentication. Run on a
 * BACKGROUND thread (blocks in a callback loop). Pure network client — no box64 involved.
 */
public class SteamSpike implements Runnable {

    private static final String TAG = "RimDroid/SteamSpike";

    public interface Listener {
        /** Steam needs a Steam Guard code (only if the account isn't approved via the Steam Mobile
         *  push). email != null → code sent to that email; email == null → authenticator-app code.
         *  Return a future the UI completes when the user types the code. */
        CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email);
        void onResult(String accountName, String refreshToken);
        void onDone(String message);
    }

    private final String username, password;
    private final Listener listener;
    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;
    private volatile boolean running;

    public SteamSpike(String username, String password, Listener listener) {
        this.username = username;
        this.password = password;
        this.listener = listener;
    }

    private void done(String m) { if (listener != null) listener.onDone(m); }

    @Override
    public void run() {
        try {
            LogManager.addListener(new DefaultLogListener());
            steamClient = new SteamClient();
            manager = new CallbackManager(steamClient);
            steamUser = steamClient.getHandler(SteamUser.class);

            manager.subscribe(ConnectedCallback.class, this::onConnected);
            manager.subscribe(DisconnectedCallback.class, cb -> { Log.i(TAG, "Disconnected"); running = false; });
            manager.subscribe(LoggedOnCallback.class, this::onLoggedOn);

            running = true;
            Log.i(TAG, "Connecting to Steam...");
            steamClient.connect();
            while (running) {
                manager.runWaitCallbacks(1000L);
            }
            Log.i(TAG, "Spike loop ended");
        } catch (Throwable t) {
            Log.e(TAG, "SteamSpike crashed", t);
            done("crash: " + t);
        }
    }

    private void onConnected(ConnectedCallback cb) {
        try {
            Log.i(TAG, "Connected. Credentials auth for '" + username + "'...");
            AuthSessionDetails details = new AuthSessionDetails();
            details.username = username;
            details.password = password;
            details.persistentSession = true;          // get a reusable refresh token
            details.authenticator = new SpikeAuthenticator();

            CredentialsAuthSession session =
                    new SteamAuthentication(steamClient).beginAuthSessionViaCredentials(details).get();
            Log.i(TAG, "Auth session started; waiting for Steam Guard / mobile approval...");

            AuthPollResult poll = session.pollingWaitForResult().get();
            String token = poll.getRefreshToken();
            Log.i(TAG, "AUTH OK: account=" + poll.getAccountName()
                    + " refreshTokenLen=" + (token == null ? 0 : token.length()));
            if (listener != null) listener.onResult(poll.getAccountName(), token);

            LogOnDetails lod = new LogOnDetails();
            lod.setUsername(poll.getAccountName());
            lod.setAccessToken(token);
            lod.setLoginID(149);
            steamUser.logOn(lod);
        } catch (Throwable t) {
            Log.e(TAG, "Credentials auth failed", t);
            done("auth error: " + t);
            running = false;
        }
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        Log.i(TAG, "LoggedOn result: " + cb.getResult());
        done("logOn: " + cb.getResult());
        running = false;
        try { steamUser.logOff(); } catch (Throwable ignored) {}
    }

    /** Approves via Steam Mobile push when possible; otherwise asks the UI for a code. */
    private class SpikeAuthenticator implements IAuthenticator {
        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            Log.i(TAG, "acceptDeviceConfirmation -> true (waiting for Steam Mobile approval push)");
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            Log.i(TAG, "getDeviceCode (authenticator app), prevWrong=" + previousCodeWasIncorrect);
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, null)
                    : CompletableFuture.completedFuture("");
        }
        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            Log.i(TAG, "getEmailCode email=" + email + " prevWrong=" + previousCodeWasIncorrect);
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, email)
                    : CompletableFuture.completedFuture("");
        }
    }
}
