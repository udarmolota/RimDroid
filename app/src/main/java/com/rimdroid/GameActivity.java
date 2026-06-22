package com.rimdroid;

import android.app.Activity;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.util.Log;

public class GameActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "RimDroid/GameActivity";

    private SurfaceView surfaceView;

    // When launched with this extra = true, GameActivity does NOT start the game: it just
    // provides a surface and runs the OSMesa software-renderer smoke test on it (dev/tester).
    public static final String EXTRA_SMOKETEST = "rimdroid_osmesa_smoketest";
    /** Which instance is launching — selects its per-instance render scale + controls layout. */
    public static final String EXTRA_INSTANCE_NAME = "instance_name";
    private boolean smokeTest;

    // Native input injection (rimdroid_jni.c → box64). action 0=move,1=Ldown,2=Lup.
    public static native void nativeTouch(int action, int x, int y);
    public static native void nativeButton(int button, int down, int x, int y); // 1=L,2=M,3=R
    public static native void nativeScroll(int x, int y, int dy);
    public static native void nativeKey(int scancode, int keycode, int down);
    public static native void nativeText(String text);

    private float renderScale = 0.72f;
    // Letterbox the game at its native 4:3 (black side bars, no stretch). DEFAULT OFF: in-game the
    // world is already aspect-correct (RimWorld's camera adapts); only the loading screen/menus
    // stretch, and 4:3 bars cost ~1/3 of a wide screen — bad trade. Kept behind this flag (could
    // become an optional setting later). OFF = full-screen (boxLeft/Top=0, boxW/H=screen).
    private static final boolean LETTERBOX_4_3 = false;
    // The game rect (screen px). With LETTERBOX off this is the whole screen.
    private int boxLeft, boxTop, boxW, boxH;
    // Auto-pin RimWorld's Prefs.xml to fullscreen at our render resolution.
    // DISABLED for now: forcing fullscreen at surface*scale raised the internal
    // resolution on weak GPUs (e.g. 1024x768 → ~1953x878 on Mali) and broke
    // boot-without-Debug — the heavier load flips a fragile box64 startup timing
    // into a hang. Keep the code; re-enable once it's made safe for weak devices.
    private static final boolean PIN_GAME_PREFS = false;
    private android.view.ScaleGestureDetector scaleDetector;
    private boolean scaling = false;
    private boolean prefsPinned = false;   // Prefs.xml resolution is pinned ONCE per launch (not per surface-change → no ping-pong)
    private com.rimdroid.input.InputControlsView controls;
    private com.rimdroid.input.GamepadHandler gamepad;   // physical controller -> MNK injection
    private String instanceName;   // the launched instance (null for the smoke test)
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private float tapDownX, tapDownY; private long tapDownT; private boolean tapMoved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        smokeTest = getIntent().getBooleanExtra(EXTRA_SMOKETEST, false);
        instanceName = getIntent().getStringExtra(EXTRA_INSTANCE_NAME);

        android.graphics.Rect b = getWindowManager().getCurrentWindowMetrics().getBounds();
        int sw = Math.max(b.width(), b.height());   // landscape width
        int sh = Math.min(b.width(), b.height());   // landscape height
        // Effective render scale = stored value raised to the per-device floor, so RimWorld's UI
        // never drops below 1280x720. Per-instance when launched from a card; global as a fallback
        // (e.g. the smoke test, which has no instance).
        if (instanceName != null) {
            com.rimdroid.InstanceSettings is = new com.rimdroid.InstanceSettings(instanceName);
            renderScale = is.getEffectiveRenderScale(sw, sh);
            dragPanEnabled = is.isDragPan();
        } else {
            LauncherPreferences lp = LauncherPreferences.getSingleton();
            if (lp != null) renderScale = lp.getEffectiveRenderScale(sw, sh);
        }

        if (LETTERBOX_4_3) {
            // Native 4:3, fit by HEIGHT, centered, black side bars → no stretch (buffer aspect
            // matches the game, so Unity's 4:3 maps 1:1).
            final float GAME_ASPECT = 4f / 3f;
            boxH = sh;
            boxW = Math.round(sh * GAME_ASPECT);
            if (boxW > sw) { boxW = sw; boxH = Math.round(sw / GAME_ASPECT); }
            boxLeft = (sw - boxW) / 2;
            boxTop  = (sh - boxH) / 2;
        } else {
            // Full screen (game stretched to fill — in-game world stays aspect-correct via
            // RimWorld's camera; only the loading screen/menus stretch). No black bars.
            boxLeft = 0; boxTop = 0; boxW = sw; boxH = sh;
        }

        // Lock to a single fixed landscape and IGNORE the rotation sensor. Previously this was
        // SENSOR_LANDSCAPE, which let the device flip 180° (landscape <-> reverse-landscape) whenever it
        // was held unsteadily; each flip fired surfaceChanged and could leave the game stuck in a stretched
        // menu (resolution drift). Fixed landscape = no flips, no sensor reaction, no stretch trigger.
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // True immersive fullscreen: hide BOTH status and navigation bars. The old
        // FLAG_FULLSCREEN only hid the status bar, so on devices with a 3-button
        // navigation bar (e.g. tablets) it stayed visible and ate the bottom of the game.
        getWindow().setDecorFitsSystemWindows(false);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);

        scaleDetector = new android.view.ScaleGestureDetector(this, new ScaleListener());
        controls = new com.rimdroid.input.InputControlsView(this, renderScale, instanceName);
        gamepad = new com.rimdroid.input.GamepadHandler(this, controls);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);   // black letterbox bars
        FrameLayout.LayoutParams svLp = new FrameLayout.LayoutParams(boxW, boxH);
        svLp.gravity = Gravity.CENTER;
        root.addView(surfaceView, svLp);   // game: centered 4:3 (not full screen)
        root.addView(controls, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        controls.setGameRect(boxLeft, boxTop, boxW, boxH);   // so cursor/tap map into the game rect
        setContentView(root);
        hideSystemBars();   // after setContentView — the decor view / insets controller now exist
        // CRITICAL for gamepad: analog joystick MotionEvents (sticks/triggers) are delivered only to
        // a focused View, then bubble to Activity.onGenericMotionEvent. Without a focusable+focused
        // view, Android silently drops them (buttons still arrive via dispatchKeyEvent, but sticks
        // don't). So make the game surface focusable and grab focus (re-grabbed in onResume).
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.requestFocus();
    }

    private void hideSystemBars() {
        android.view.WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(android.view.WindowInsets.Type.systemBars());
            // Keep them hidden; a swipe shows them transiently, then they auto-hide again.
            c.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();   // re-hide after dialogs / focus regain
    }

    // Pinch-zoom: only non-stick touches reach here (overlay returns false for them).
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (scaleDetector != null) scaleDetector.onTouchEvent(e);
        if (e.getPointerCount() >= 2 || scaling) { releasePan(); panGestureOwned = false; return true; }   // pinch-zoom
        float d = getResources().getDisplayMetrics().density;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tapDownX = e.getX(); tapDownY = e.getY(); tapDownT = System.currentTimeMillis(); tapMoved = false;
                lastPanX = e.getX(); lastPanY = e.getY();
                panGestureOwned = true;     // we received this gesture's DOWN → it's a real map touch
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!panGestureOwned) return true;   // leaked MOVE from a button press → never pan
                float dx = e.getX() - tapDownX, dy = e.getY() - tapDownY;
                // Raised tap→drag threshold: a tap (even a slightly imprecise one, e.g. aiming for an
                // on-screen button) stays a CLICK and does NOT pan the map. Only a deliberate drag pans.
                if (Math.abs(dx) + Math.abs(dy) > 22 * d) tapMoved = true;
                if (tapMoved && dragPanEnabled) {
                    // Only pan if the finger actually MOVED since last frame (gate out jitter); a
                    // displaced-but-still finger must NOT keep the camera accelerating.
                    boolean sliding = Math.abs(e.getX() - lastPanX) + Math.abs(e.getY() - lastPanY) > 2 * d;
                    if (sliding) {
                        updateDragPan(dx, dy, 22 * d);     // direction from the touch-down anchor
                        lastPanX = e.getX(); lastPanY = e.getY();
                        ui.removeCallbacks(panIdleRelease);
                        ui.postDelayed(panIdleRelease, 90);   // finger stops → stop the camera
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                releasePan();
                if (panGestureOwned && !tapMoved && !scaling && System.currentTimeMillis() - tapDownT < 250) {
                    final int gx = gameX(e.getX()), gy = gameY(e.getY());
                    try {
                        nativeButton(1, 1, gx, gy);   // direct tap = left click at finger
                        ui.postDelayed(() -> { try { nativeButton(1, 0, gx, gy); } catch (UnsatisfiedLinkError ig) {} }, 50);
                    } catch (UnsatisfiedLinkError ig) {}
                }
                panGestureOwned = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                releasePan();
                panGestureOwned = false;
                return true;
        }
        return true;
    }

    // ===== Single-finger drag = pan the camera (held arrow keys; RimWorld has no native drag-pan) =====
    // Replaces the dead single-finger-drag gesture (a quick tap still left-clicks; two fingers still
    // pinch-zoom). "Grab the map": the world follows the finger (inverted from a joystick). The drag is
    // anchored at touch-down; past the deadzone in a direction we hold that camera arrow.
    private static final boolean DRAG_PAN_GRAB = true;
    private final boolean[] panHeld = new boolean[4];   // 0=up,1=right,2=down,3=left (camera arrows)
    private static final com.rimdroid.input.Binding[] PAN_KEYS = {
        com.rimdroid.input.Binding.KEY_UP, com.rimdroid.input.Binding.KEY_RIGHT,
        com.rimdroid.input.Binding.KEY_DOWN, com.rimdroid.input.Binding.KEY_LEFT };
    // We only hold the arrow WHILE the finger is actively sliding. RimWorld's camera accelerates as
    // long as a movement key is held and coasts after release, so holding a displaced-but-still finger
    // made it "run away" + drift. lastPanX/Y = last position past the jitter gate; if no real motion
    // arrives within panIdleRelease's window we release the keys, so the camera stops with the finger.
    private float lastPanX, lastPanY;
    private final Runnable panIdleRelease = this::releasePan;
    // True only for a gesture whose ACTION_DOWN this Activity actually received (i.e. a touch on the
    // bare map, NOT on an overlay button). On-screen buttons consume DOWN/UP but NOT MOVE, so their
    // MOVE events leak here; without this guard we'd pan from a stale/zero anchor (the "every button
    // press nudges the map to the top-left" bug). We only pan when we own the gesture from its DOWN.
    private boolean panGestureOwned;
    private boolean dragPanEnabled = true;   // per-instance (Settings → "Drag the map to pan")

    private void updateDragPan(float dx, float dy, float dead) {
        boolean fUp = dy < -dead, fDown = dy > dead, fLeft = dx < -dead, fRight = dx > dead;
        if (DRAG_PAN_GRAB) {        // world follows finger → camera moves the opposite way
            setPan(0, fDown);      // finger down  → camera up   (north)
            setPan(2, fUp);        // finger up    → camera down (south)
            setPan(1, fLeft);      // finger left  → camera right(east)
            setPan(3, fRight);     // finger right → camera left (west)
        } else {                   // joystick: push direction = camera direction
            setPan(0, fUp); setPan(2, fDown); setPan(1, fRight); setPan(3, fLeft);
        }
    }

    private void setPan(int i, boolean want) {
        if (panHeld[i] == want) return;
        panHeld[i] = want;
        if (controls != null) controls.inject(PAN_KEYS[i], want);
    }

    private void releasePan() {
        ui.removeCallbacks(panIdleRelease);
        for (int i = 0; i < 4; i++) setPan(i, false);
    }

    private class ScaleListener extends android.view.ScaleGestureDetector.SimpleOnScaleGestureListener {
        private float accum = 0f;
        @Override public boolean onScaleBegin(android.view.ScaleGestureDetector dt) { scaling = true; accum = 0f; return true; }
        @Override public boolean onScale(android.view.ScaleGestureDetector dt) {
            accum += dt.getScaleFactor() - 1f;
            int fx = gameX(dt.getFocusX()), fy = gameY(dt.getFocusY());
            while (accum >  0.15f) { accum -= 0.15f; safeScroll(fx, fy, +1); }
            while (accum < -0.15f) { accum += 0.15f; safeScroll(fx, fy, -1); }
            return true;
        }
        @Override public void onScaleEnd(android.view.ScaleGestureDetector dt) { scaling = false; }
    }
    private void safeScroll(int x, int y, int dy) { try { nativeScroll(x, y, dy); } catch (UnsatisfiedLinkError ignored) {} }

    // Map a screen coordinate (px) to a game/buffer coordinate, accounting for the letterbox
    // offset + render scale, clamped to the buffer (taps in the black bars clamp to the edge).
    private int gameX(float screenX) {
        int max = Math.max(1, Math.round(boxW * renderScale)) - 1;
        int v = Math.round((screenX - boxLeft) * renderScale);
        return v < 0 ? 0 : (v > max ? max : v);
    }
    private int gameY(float screenY) {
        int max = Math.max(1, Math.round(boxH * renderScale)) - 1;
        int v = Math.round((screenY - boxTop) * renderScale);
        return v < 0 ? 0 : (v > max ? max : v);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surfaceView != null) surfaceView.requestFocus();   // re-grab focus so sticks keep working
        if (gamepad != null) gamepad.start();         // resume the gamepad analog frame loop
        // Auto-hide the on-screen controls while a physical gamepad is connected (like Zomdroid).
        inputManager = (android.hardware.input.InputManager) getSystemService(INPUT_SERVICE);
        if (inputManager != null) inputManager.registerInputDeviceListener(deviceListener, null);
        refreshGamepadControls();                     // apply current connection state
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gamepad != null) gamepad.stop();          // stop the loop, release held gamepad inputs
        if (inputManager != null) inputManager.unregisterInputDeviceListener(deviceListener);
        releasePan();                                 // release held camera-pan arrows
        if (controls != null) controls.resetAll();   // release any held buttons/keys
    }

    // === physical gamepad detection -> auto hide/show on-screen controls ===
    private android.hardware.input.InputManager inputManager;
    private boolean lastPadConnected = false;
    private final android.hardware.input.InputManager.InputDeviceListener deviceListener =
        new android.hardware.input.InputManager.InputDeviceListener() {
            @Override public void onInputDeviceAdded(int id)   { refreshGamepadControls(); }
            @Override public void onInputDeviceRemoved(int id) { refreshGamepadControls(); }
            @Override public void onInputDeviceChanged(int id) { refreshGamepadControls(); }
        };

    /** Hide the on-screen controls when a gamepad connects, show them when it disconnects.
     *  Acts only on a connect/disconnect TRANSITION, so it never clobbers the manual hide toggle. */
    private void refreshGamepadControls() {
        boolean pad = isGamepadConnected();
        if (pad == lastPadConnected) return;
        lastPadConnected = pad;
        if (controls != null) controls.setControlsHidden(pad);
    }

    private boolean isGamepadConnected() {
        for (int id : android.view.InputDevice.getDeviceIds()) {
            // A transient/virtual device (e.g. a screen recorder) can throw while being queried.
            try {
                android.view.InputDevice d = android.view.InputDevice.getDevice(id);
                if (d == null || d.isVirtual()) continue;
                int s = d.getSources();
                boolean gamepadSource =
                       (s & android.view.InputDevice.SOURCE_GAMEPAD)  == android.view.InputDevice.SOURCE_GAMEPAD
                    || (s & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
                // Require analog motion ranges too (like Zomdroid) so a keyboard/remote that merely
                // reports a DPAD source isn't mistaken for a gamepad.
                boolean hasMotion = d.getMotionRanges() != null && !d.getMotionRanges().isEmpty();
                if (gamepadSource && hasMotion) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    // Physical gamepad: buttons/D-pad arrive as key events, analog sticks/triggers as generic
    // motion. Route both to GamepadHandler (maps to the same MNK injection as on-screen controls);
    // if it consumes the event, don't let the system treat it as focus/back navigation.
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (gamepad != null && gamepad.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (gamepad != null && gamepad.onMotion(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        GameLauncher.destroyRimDroidWindow();
    }

    // === SurfaceHolder.Callback ==================================================
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        int bw = Math.max(1, Math.round(surfaceView.getWidth()  * renderScale));
        int bh = Math.max(1, Math.round(surfaceView.getHeight() * renderScale));
        Log.i(TAG, "surfaceCreated: scale=" + renderScale + " buffer=" + bw + "x" + bh);
        holder.setFixedSize(bw, bh);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height);
        GameLauncher.setSurface(holder.getSurface(), width, height);
        if (smokeTest) {
            // Software-renderer smoke test: render+blit one OSMesa frame, no game launch.
            try {
                String osmesa = com.rimdroid.AppStorage.requireSingleton().getGl4esLibsPath()
                        + "/libOSMesa.so";
                int rc = GameLauncher.nativeOsmesaSmokeTest(osmesa);
                Log.i(TAG, "OSMesa smoke test rc=" + rc + " (" + osmesa + ")");
            } catch (Throwable t) {
                Log.w(TAG, "OSMesa smoke test failed: " + t.getMessage());
            }
            return;
        }
        // Pin RimWorld's Prefs.xml to fullscreen at EXACTLY the buffer resolution we
        // pass the game here (= surface * render scale). RimWorld re-applies its saved
        // Prefs resolution shortly after launch, overriding -screen-width; if that saved
        // value is the Unity default 1024x768 (4:3) the game renders stretched into our
        // surface. Writing the matching size + fullscreen=True before RimWorld reads
        // Prefs (during its init, seconds later) keeps the render 1:1 with correct aspect.
        // SOFTPIPE (OSMesa) renders into a CPU buffer sized to THIS surface and
        // blits it 1:1. If RimWorld re-applies a saved Prefs resolution (e.g. the
        // 1024x768 default) its glViewport would no longer match the buffer →
        // image in a corner. Pinning Prefs to the buffer size keeps softpipe 1:1.
        boolean softpipe = false;
        try {
            if (instanceName != null) {
                softpipe = new com.rimdroid.InstanceSettings(instanceName).getRenderer()
                        == LauncherPreferences.Renderer.SOFTPIPE;
            } else {
                LauncherPreferences lp = LauncherPreferences.getSingleton();
                softpipe = lp != null && lp.getRenderer() == LauncherPreferences.Renderer.SOFTPIPE;
            }
        } catch (Throwable ignored) {}
        // Pin RimWorld's Prefs.xml ONCE per launch (NOT on every surface-change — per-change rewrites
        // were what caused the resolution PING-PONG) to fullscreen at our render-buffer resolution, so
        // RimWorld doesn't override -screen-width with its own saved/default resolution (the cause of
        // the small / doubled / "warping" render seen on the 725 and flagships). ZFA/Zink renders into
        // a surface*renderScale buffer; softpipe into a surface-sized CPU buffer.
        if (!prefsPinned) {
            prefsPinned = true;
            if (softpipe) pinGamePrefs(width, height);
            else pinGamePrefs(Math.max(1, Math.round(boxW * renderScale)),
                              Math.max(1, Math.round(boxH * renderScale)));
        }
    }

    /** Force the selected instance's Config/Prefs.xml to fullscreen at the render
     *  resolution we use, so RimWorld doesn't override us with its 4:3 default. */
    private void pinGamePrefs(int width, int height) {
        try {
            String name = instanceName;
            if (name == null || name.isEmpty()) {
                LauncherPreferences lp = LauncherPreferences.getSingleton();
                name = lp != null ? lp.getLastInstanceName() : null;
            }
            if (name == null || name.isEmpty()) return;
            com.rimdroid.game.GameInstanceManager mgr =
                    com.rimdroid.game.GameInstanceManager.requireSingleton();
            mgr.reload();
            com.rimdroid.game.GameInstance gi = mgr.getByName(name);
            if (gi == null) return;
            PrefsXml.forceFullscreen(new java.io.File(gi.getUserDataDir(), "Config"), width, height);
        } catch (Throwable t) {
            Log.w(TAG, "pinGamePrefs failed: " + t.getMessage());
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        GameLauncher.destroySurface();
    }
}
