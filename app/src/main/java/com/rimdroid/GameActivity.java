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
    // FPS overlay: total presented frames so far (counted in box64's SwapWindow).
    public static native long nativeGetFrameCount();
    public static native void nativeSetFpsCap(int fps);   // 0 = uncapped, else cap presents to fps

    // Input wrappers: the native path feeds synthetic SDL events (RimWorld 1.5's SDL video
    // driver). Under the 1.6 X11 path Unity's SDL takes input from CORE X EVENTS instead, so
    // taps produced zero ButtonPress on the wire and nothing was clickable at the menu. Mirror
    // every pointer action into the in-process X server whenever one is running (1.6 sessions
    // only — getXServer() is null for 1.5, making the mirror a no-op there).
    private static com.rimdroid.xserver.Pointer.Button xBtn(int button) {
        switch (button) {
            case 2:  return com.rimdroid.xserver.Pointer.Button.BUTTON_MIDDLE;
            case 3:  return com.rimdroid.xserver.Pointer.Button.BUTTON_RIGHT;
            default: return com.rimdroid.xserver.Pointer.Button.BUTTON_LEFT;
        }
    }
    public static void touchInput(int action, int x, int y) {
        try { nativeTouch(action, x, y); } catch (UnsatisfiedLinkError ignored) {}
        com.rimdroid.xserver.XServer xs = com.rimdroid.xserver.XServerRunner.getXServer();
        if (xs == null) return;
        xs.injectPointerMove(x, y);
        if (action == 1) xs.injectPointerButtonPress(com.rimdroid.xserver.Pointer.Button.BUTTON_LEFT);
        else if (action == 2) xs.injectPointerButtonRelease(com.rimdroid.xserver.Pointer.Button.BUTTON_LEFT);
    }
    public static void buttonInput(int button, int down, int x, int y) {
        try { nativeButton(button, down, x, y); } catch (UnsatisfiedLinkError ignored) {}
        com.rimdroid.xserver.XServer xs = com.rimdroid.xserver.XServerRunner.getXServer();
        if (xs == null) return;
        xs.injectPointerMove(x, y);
        if (down != 0) xs.injectPointerButtonPress(xBtn(button));
        else xs.injectPointerButtonRelease(xBtn(button));
    }
    /** SDL scancode → XKeycode for the on-screen KEY buttons (physical keyboards go through
     *  Keyboard.onKeyEvent with the full Android map; this covers only what layouts use). */
    private static com.rimdroid.xserver.XKeycode xKey(int sdlScancode) {
        switch (sdlScancode) {
            case 26: return com.rimdroid.xserver.XKeycode.KEY_W;
            case 4:  return com.rimdroid.xserver.XKeycode.KEY_A;
            case 22: return com.rimdroid.xserver.XKeycode.KEY_S;
            case 7:  return com.rimdroid.xserver.XKeycode.KEY_D;
            case 20: return com.rimdroid.xserver.XKeycode.KEY_Q;
            case 8:  return com.rimdroid.xserver.XKeycode.KEY_E;
            case 6:  return com.rimdroid.xserver.XKeycode.KEY_C;
            case 9:  return com.rimdroid.xserver.XKeycode.KEY_F;
            case 44: return com.rimdroid.xserver.XKeycode.KEY_SPACE;
            case 41: return com.rimdroid.xserver.XKeycode.KEY_ESC;
            case 40: return com.rimdroid.xserver.XKeycode.KEY_ENTER;
            case 43: return com.rimdroid.xserver.XKeycode.KEY_TAB;
            case 225: return com.rimdroid.xserver.XKeycode.KEY_SHIFT_L;
            case 224: return com.rimdroid.xserver.XKeycode.KEY_CTRL_L;
            case 30: return com.rimdroid.xserver.XKeycode.KEY_1;
            case 31: return com.rimdroid.xserver.XKeycode.KEY_2;
            case 32: return com.rimdroid.xserver.XKeycode.KEY_3;
            case 33: return com.rimdroid.xserver.XKeycode.KEY_4;
            case 34: return com.rimdroid.xserver.XKeycode.KEY_5;
            case 35: return com.rimdroid.xserver.XKeycode.KEY_6;
            case 36: return com.rimdroid.xserver.XKeycode.KEY_7;
            case 37: return com.rimdroid.xserver.XKeycode.KEY_8;
            case 38: return com.rimdroid.xserver.XKeycode.KEY_9;
            case 39: return com.rimdroid.xserver.XKeycode.KEY_0;
            // arrows — the on-screen pan stick emits these scancodes (camera panning)
            case 79: return com.rimdroid.xserver.XKeycode.KEY_RIGHT;
            case 80: return com.rimdroid.xserver.XKeycode.KEY_LEFT;
            case 81: return com.rimdroid.xserver.XKeycode.KEY_DOWN;
            case 82: return com.rimdroid.xserver.XKeycode.KEY_UP;
            // function row (RimWorld: F1 help etc.; SDL scancodes 58..69 = F1..F12)
            case 58: return com.rimdroid.xserver.XKeycode.KEY_F1;
            case 59: return com.rimdroid.xserver.XKeycode.KEY_F2;
            case 60: return com.rimdroid.xserver.XKeycode.KEY_F3;
            case 61: return com.rimdroid.xserver.XKeycode.KEY_F4;
            case 62: return com.rimdroid.xserver.XKeycode.KEY_F5;
            case 63: return com.rimdroid.xserver.XKeycode.KEY_F6;
            case 64: return com.rimdroid.xserver.XKeycode.KEY_F7;
            case 65: return com.rimdroid.xserver.XKeycode.KEY_F8;
            case 66: return com.rimdroid.xserver.XKeycode.KEY_F9;
            case 67: return com.rimdroid.xserver.XKeycode.KEY_F10;
            case 68: return com.rimdroid.xserver.XKeycode.KEY_F11;
            case 69: return com.rimdroid.xserver.XKeycode.KEY_F12;
            case 74: return com.rimdroid.xserver.XKeycode.KEY_HOME;
            case 77: return com.rimdroid.xserver.XKeycode.KEY_END;
            case 76: return com.rimdroid.xserver.XKeycode.KEY_DEL;
            default: return null;
        }
    }
    public static void keyInput(int scancode, int keycode, int down) {
        try { nativeKey(scancode, keycode, down); } catch (UnsatisfiedLinkError ignored) {}
        com.rimdroid.xserver.XServer xs = com.rimdroid.xserver.XServerRunner.getXServer();
        if (xs == null) return;
        com.rimdroid.xserver.XKeycode xk = xKey(scancode);
        if (xk == null) return;
        // TEXT-INPUT EXPERIMENT (2026-07-23): carry the real keysym on the X11 KeyPress (was 0), so
        // SDL-under-box64 can turn it into text itself the way it does for a hardware keyboard —
        // instead of us synthesising an SDL_TEXTINPUT (nativeText), which RimWorld ignores. `keycode`
        // here is the SDL keysym; for printable ASCII it equals the X keysym. Logged so we can see it
        // land. If this types into a rename field, the whole soft-keyboard idea is unblocked.
        int keysym = (keycode >= 32 && keycode < 127) ? keycode : 0;
        android.util.Log.i(TAG, "keyInput X11: sc=" + scancode + " keysym=" + keysym + " down=" + down);
        if (down != 0) xs.injectKeyPress(xk, keysym);
        else xs.injectKeyRelease(xk);
    }
    // === Soft keyboard (text input) ===
    private KeyboardCatcher keyboardCatcher;

    /** Show the keyboard if hidden, hide it if shown. Bound to the on-screen TOGGLE_KEYBOARD button. */
    public void toggleSoftKeyboard() {
        if (keyboardCatcher == null) return;
        keyboardCatcher.toggle();
    }

    /** Invisible view that owns the IME connection and forwards typed text into the X server. */
    private static class KeyboardCatcher extends android.view.View {
        private boolean accepting;
        KeyboardCatcher(android.content.Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }
        void toggle() {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getContext()
                            .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            accepting = !accepting;
            requestFocus();
            imm.restartInput(this);
            if (accepting) imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
            else imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
        @Override public boolean onCheckIsTextEditor() { return accepting; }
        @Override
        public android.view.inputmethod.InputConnection onCreateInputConnection(
                android.view.inputmethod.EditorInfo outAttrs) {
            if (!accepting) return null;
            outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT;
            outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
                    | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            return new android.view.inputmethod.BaseInputConnection(this, false) {
                @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                    if (text == null) return true;
                    com.rimdroid.xserver.XServer xs = com.rimdroid.xserver.XServerRunner.getXServer();
                    if (xs != null) {
                        // 1.6: type through the X server (the proven path — SDL makes the text itself).
                        xs.injectText(text.toString());
                    } else {
                        // 1.5 has NO X server (different SDL video driver); the only channel is the
                        // synthetic SDL_TEXTINPUT. 1.6 ignored it, but 1.5's driver differs, so try it.
                        try { nativeText(text.toString()); } catch (UnsatisfiedLinkError ignored) {}
                    }
                    return true;
                }
                @Override public boolean deleteSurroundingText(int before, int after) {
                    com.rimdroid.xserver.XServer xs = com.rimdroid.xserver.XServerRunner.getXServer();
                    if (xs != null) {
                        for (int i = 0; i < before; i++) xs.injectBackspace();
                    } else {
                        // 1.5: backspace as a synthetic SDL key (control keys ARE consumed there).
                        for (int i = 0; i < before; i++) {
                            try { nativeKey(42, 8, 1); nativeKey(42, 8, 0); } catch (UnsatisfiedLinkError ignored) {}
                        }
                    }
                    return true;
                }
            };
        }
    }

    public static void scrollInput(int x, int y, int dy) {
        try { nativeScroll(x, y, dy); } catch (UnsatisfiedLinkError ignored) {}
        com.rimdroid.xserver.XServer xs = com.rimdroid.xserver.XServerRunner.getXServer();
        if (xs == null) return;
        xs.injectPointerMove(x, y);
        com.rimdroid.xserver.Pointer.Button b = dy > 0
                ? com.rimdroid.xserver.Pointer.Button.BUTTON_SCROLL_UP
                : com.rimdroid.xserver.Pointer.Button.BUTTON_SCROLL_DOWN;
        for (int i = 0, n = Math.min(Math.abs(dy), 5); i < n; i++) {
            xs.injectPointerButtonPress(b);
            xs.injectPointerButtonRelease(b);
        }
    }

    private float renderScale = 0.72f;
    // Fixed monitor resolution (per-instance, Settings → Render resolution). Players coming from PC
    // emulators asked for 720p by name: a real monitor resolution, letterboxed, instead of stretching
    // to the device's aspect. 16:9 for ordinary phones, 4:3 for near-square foldables where 16:9
    // would waste a third of the screen. See InstanceSettings.FIXED_*. NONE = fill the screen.
    private int fixedRes;
    // The game rect (screen px). Filling the screen, this is the whole screen.
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
    private android.widget.TextView fpsView;          // top-left "FPS: XX" overlay (optional)
    private long fpsLastCount = 0, fpsLastTimeMs = 0;  // for computing the per-second delta
    private com.rimdroid.input.GamepadHandler gamepad;   // physical controller -> MNK injection
    private com.rimdroid.input.MouseKeyboardHandler mouseKb;  // physical mouse + keyboard -> SDL injection
    private String instanceName;   // the launched instance (null for the smoke test)
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private float tapDownX, tapDownY; private long tapDownT; private boolean tapMoved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        smokeTest = getIntent().getBooleanExtra(EXTRA_SMOKETEST, false);
        instanceName = getIntent().getStringExtra(EXTRA_INSTANCE_NAME);

        // adb-driven test runs (debug builds export this activity): "autolaunch" makes this
        // activity ALSO start the game itself — normally LauncherFragment does that half.
        //   am start -n com.rimdroid/.GameActivity --es instance_name X --ez autolaunch true
        if (getIntent().getBooleanExtra("autolaunch", false) && instanceName != null) {
            final com.rimdroid.game.GameInstance gi =
                    com.rimdroid.game.GameInstanceManager.requireSingleton().getByName(instanceName);
            if (gi != null) {
                new Thread(() -> {
                    try {
                        // Wait for the Android surface AND for its size to settle: surfaceChanged
                        // fires twice (2340x1080 full-window, then the 1685x778 fixed-size buffer
                        // ~20 ms later). Launching on the first value made the Vulkan swapchain
                        // extent mismatch the buffer. Settle = no change for 500 ms.
                        int lastW = 0, lastH = 0; long stableSince = 0;
                        for (int i = 0; i < 200; ++i) {
                            int w = GameLauncher.lastSurfaceWidth, h = GameLauncher.lastSurfaceHeight;
                            if (w > 0 && w == lastW && h == lastH) {
                                if (stableSince == 0) stableSince = System.currentTimeMillis();
                                else if (System.currentTimeMillis() - stableSince >= 500) break;
                            } else { lastW = w; lastH = h; stableSince = 0; }
                            Thread.sleep(50);
                        }
                        GameLauncher.launch(gi);
                    }
                    catch (Throwable t) { android.util.Log.e("RimDroid", "autolaunch failed", t); }
                }, "rd-autolaunch").start();
            }
        }

        // Window policy FIRST, measurement AFTER. LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS tells every
        // firmware to lay the window out over the FULL physical screen, including the display-cutout
        // (camera) strip. Without it, some devices keep the window INSIDE the safe area in landscape
        // while we size the SurfaceView to the full getBounds() — the CENTER-gravity child is then
        // wider than its parent and overflow-clips on BOTH sides (black strip by the camera + cropped
        // right edge; three testers reported exactly that). Forcing ALWAYS puts parent and child in
        // one coordinate system on every device, instead of guessing with bounds-minus-insets (which
        // would SHRINK the image on devices that already draw under the cutout — a non-zero cutout
        // inset does NOT imply the window avoids it). minSdk 30 = R, so no version check needed.
        WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(wlp);
        // True immersive fullscreen: hide BOTH status and navigation bars. The old
        // FLAG_FULLSCREEN only hid the status bar, so on devices with a 3-button
        // navigation bar (e.g. tablets) it stayed visible and ate the bottom of the game.
        // (Must run BEFORE the metrics read below — it can relayout the window.)
        getWindow().setDecorFitsSystemWindows(false);

        android.view.WindowMetrics wmx = getWindowManager().getCurrentWindowMetrics();
        android.graphics.Rect b = wmx.getBounds();
        android.graphics.Insets cut = wmx.getWindowInsets()
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.displayCutout());
        int usableW = Math.max(1, b.width());
        int usableH = Math.max(1, b.height());
        Log.i(TAG, "screen bounds=" + b.width() + "x" + b.height()
                + " cutout(l,t,r,b)=" + cut.left + "," + cut.top + "," + cut.right + "," + cut.bottom
                + " cutoutMode=ALWAYS -> full bounds used");
        int sw = Math.max(usableW, usableH);   // landscape width  (full screen)
        int sh = Math.min(usableW, usableH);   // landscape height (full screen)
        // Effective render scale = stored value raised to the per-device floor, so RimWorld's UI
        // never drops below 1280x720. Per-instance when launched from a card; global as a fallback
        // (e.g. the smoke test, which has no instance).
        if (instanceName != null) {
            com.rimdroid.InstanceSettings is = new com.rimdroid.InstanceSettings(instanceName);
            renderScale = is.getEffectiveRenderScale(sw, sh);
            dragPanEnabled = is.isDragPan();
            reverseLandscape = is.isReverseLandscape();
            fixedRes = is.getFixedResMode();
            try { nativeSetFpsCap(is.getFpsCap()); } catch (UnsatisfiedLinkError ignored) {}
            // 1.6/X11 render scale ENABLED (2026-07-11): the bring-up force-1.0 is gone. The old
            // race is covered — GameLauncher's settle loop waits for the FIXED-SIZE surfaceChanged
            // before starting the X server, so the buffer, the X screen and -screen-width/-height
            // all agree; on the GL/ZFA path kopper sizes the swapchain from the ANativeWindow
            // directly. Lower scale = fewer pixels for the GPU AND a bigger-looking game UI.
        } else {
            LauncherPreferences lp = LauncherPreferences.getSingleton();
            if (lp != null) renderScale = lp.getEffectiveRenderScale(sw, sh);
        }

        if (fixedRes != com.rimdroid.InstanceSettings.FIXED_NONE) {
            // A rect of the chosen shape centred on the screen, then a buffer of exactly N x 720
            // inside it. Because the rect has that exact aspect, scale (targetW / boxW) lands the
            // height on 720 with no rounding — so the existing setFixedSize / touch-mapping /
            // Prefs-pinning paths need no changes at all. Whatever is left over stays black: that
            // is the margin the on-screen buttons can sit on.
            boolean wide = (fixedRes == com.rimdroid.InstanceSettings.FIXED_720_16_9);
            final float ASPECT  = wide ? 16f / 9f : 4f / 3f;
            final int   TARGET_W = wide ? 1280 : 960;
            boxH = sh;
            boxW = Math.round(sh * ASPECT);
            if (boxW > sw) { boxW = sw; boxH = Math.round(sw / ASPECT); }
            boxLeft = (sw - boxW) / 2;
            boxTop  = (sh - boxH) / 2;
            // Overrides the render-scale setting on purpose — this mode IS the resolution. 720 lines
            // is also our readability floor, so pinning it can't make the UI too small.
            renderScale = (float) TARGET_W / boxW;
            Log.i(TAG, "fixed " + (wide ? "16:9" : "4:3") + ": box=" + boxW + "x" + boxH
                    + " scale=" + renderScale + " -> buffer "
                    + Math.round(boxW * renderScale) + "x" + Math.round(boxH * renderScale));
        } else {
            // Full screen (game stretched to fill — in-game world stays aspect-correct via
            // RimWorld's camera; only the loading screen/menus stretch). No black bars.
            boxLeft = 0; boxTop = 0; boxW = sw; boxH = sh;
        }

        // Lock to a single fixed landscape and IGNORE the rotation sensor. Previously this was
        // SENSOR_LANDSCAPE, which let the device flip 180° (landscape <-> reverse-landscape) whenever it
        // was held unsteadily; each flip fired surfaceChanged and could leave the game stuck in a stretched
        // menu (resolution drift). Fixed landscape = no flips, no sensor reaction, no stretch trigger.
        // Opt-in escape hatch (Settings → "Mirror the picture (180°)"): a USB-C gamepad cradle can hold
        // the phone in the OPPOSITE landscape, making the game unplayable for those users. This stays a
        // FIXED orientation — just the mirrored one — so the no-flip guarantee above is preserved.
        setRequestedOrientation(reverseLandscape
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // (cutout mode + setDecorFitsSystemWindows moved ABOVE the metrics read — see the window
        // policy block before getCurrentWindowMetrics().)

        surfaceView = new SurfaceView(this);
        // RGBA_8888 explicitly — the default logged as RGB_565 (format=4); Vulkan WSI replaces the
        // format anyway, but keep the BufferQueue consistent from the start (hygiene, Codex rec).
        surfaceView.getHolder().setFormat(android.graphics.PixelFormat.RGBA_8888);
        surfaceView.getHolder().addCallback(this);

        scaleDetector = new android.view.ScaleGestureDetector(this, new ScaleListener());
        controls = new com.rimdroid.input.InputControlsView(this, renderScale, instanceName);
        gamepad = new com.rimdroid.input.GamepadHandler(this, controls);
        mouseKb = new com.rimdroid.input.MouseKeyboardHandler(controls);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);   // black letterbox bars
        FrameLayout.LayoutParams svLp = new FrameLayout.LayoutParams(boxW, boxH);
        svLp.gravity = Gravity.CENTER;
        root.addView(surfaceView, svLp);   // game: centered 4:3 (not full screen)
        root.addView(controls, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        controls.setGameRect(boxLeft, boxTop, boxW, boxH);   // so cursor/tap map into the game rect

        // Text-input catcher: an invisible focusable view that opens Android's soft keyboard and
        // receives FINISHED text from any IME (commitText — handles CJK, accents, autocorrect). Each
        // character is typed into the game as a real X11 key event (XServer.injectText), the path we
        // are testing for RimWorld text fields. Toggled by the on-screen TOGGLE_KEYBOARD button.
        keyboardCatcher = new KeyboardCatcher(this);
        root.addView(keyboardCatcher, new FrameLayout.LayoutParams(1, 1));   // 1px, invisible

        // Post-layout diagnostic for the cropped-screen reports: the fact that matters is whether
        // the laid-out root ACTUALLY matches getBounds() now that cutout mode is ALWAYS. If root is
        // still narrower than bounds on some firmware, this log line proves it from a tester zip.
        root.post(() -> Log.i(TAG, "post-layout root=" + root.getWidth() + "x" + root.getHeight()
                + " surface=" + surfaceView.getWidth() + "x" + surfaceView.getHeight()
                + " (window bounds=" + b.width() + "x" + b.height() + ")"));

        // Physical mouse: hide Android's OWN pointer (TYPE_NULL) so it doesn't double up with our overlay
        // cursor. Set on every in-game view the pointer can rest on (controls is on top; surface/root for when
        // the controls overlay is hidden). Our InputControlsView cursor remains the single visible cursor.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.view.PointerIcon nullIcon =
                    android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL);
            root.setPointerIcon(nullIcon);
            controls.setPointerIcon(nullIcon);
            surfaceView.setPointerIcon(nullIcon);
        }

        // Optional FPS overlay ("FPS: XX", top-left). Global toggle in Settings → Video.
        // Counts real presented frames (box64 SwapWindow), so it's the true on-screen rate.
        if (LauncherPreferences.getSingleton() != null
                && LauncherPreferences.getSingleton().isShowFps()) {
            fpsView = new android.widget.TextView(this);
            fpsView.setText("FPS: --");
            fpsView.setTextColor(0xFF00FF66);                 // green, readable over any scene
            fpsView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            fpsView.setShadowLayer(4f, 0f, 0f, 0xFF000000);   // outline so it reads on light scenes
            fpsView.setPadding(0, 0, 0, 0);
            final int m = Math.round(8 * getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams fpsLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            fpsLp.gravity = Gravity.TOP | Gravity.START;
            fpsLp.setMargins(m, m, 0, 0);
            root.addView(fpsView, fpsLp);   // on top of surface + controls
            // Shift it RIGHT by its own width once laid out, so it clears RimWorld's top-left resource
            // readout (the top-right corner has the early-game tutorial text we don't want to cover).
            fpsView.post(() -> {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fpsView.getLayoutParams();
                lp.leftMargin = m + fpsView.getWidth();
                fpsView.setLayoutParams(lp);
            });
        }
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
        if (e.getPointerCount() >= 2 || scaling) {   // pinch-zoom
            ui.removeCallbacks(longPressRunnable);   // a 2nd finger cancels the pending right-click
            releasePan(); panGestureOwned = false; return true;
        }
        float d = getResources().getDisplayMetrics().density;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tapDownX = e.getX(); tapDownY = e.getY(); tapDownT = System.currentTimeMillis(); tapMoved = false;
                lastPanX = e.getX(); lastPanY = e.getY();
                panGestureOwned = true;     // we received this gesture's DOWN → it's a real map touch
                longPressFired = false;
                ui.removeCallbacks(longPressRunnable);
                ui.postDelayed(longPressRunnable, LONG_PRESS_MS);   // held-still finger → right-click
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!panGestureOwned) return true;   // leaked MOVE from a button press → never pan
                float dx = e.getX() - tapDownX, dy = e.getY() - tapDownY;
                // Raised tap→drag threshold: a tap (even a slightly imprecise one, e.g. aiming for an
                // on-screen button) stays a CLICK and does NOT pan the map. Only a deliberate drag pans.
                if (Math.abs(dx) + Math.abs(dy) > 22 * d) {
                    if (!tapMoved) ui.removeCallbacks(longPressRunnable);   // it's a drag, not a long-press
                    tapMoved = true;
                }
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
                ui.removeCallbacks(longPressRunnable);   // released → no long-press
                // A quick tap = left click. If the long-press already fired a right-click, do NOT
                // also left-click (that would double-act). tapMoved/scaling still exclude drags/pinch.
                if (panGestureOwned && !tapMoved && !scaling && !longPressFired
                        && System.currentTimeMillis() - tapDownT < 250) {
                    final int gx = gameX(e.getX()), gy = gameY(e.getY());
                    try {
                        buttonInput(1, 1, gx, gy);   // direct tap = left click at finger
                        ui.postDelayed(() -> buttonInput(1, 0, gx, gy), 50);
                    } catch (UnsatisfiedLinkError ig) {}
                }
                panGestureOwned = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                releasePan();
                ui.removeCallbacks(longPressRunnable);
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
    private boolean reverseLandscape;        // per-instance (Settings → "Mirror the picture"); default OFF

    // Long-press = right-click. RimWorld uses right-click for context orders (move here, prioritise…),
    // and a held finger did NOTHING before (the tap→left-click path requires release < 250 ms). After
    // LONG_PRESS_MS with the finger still (no pan, no pinch) we fire a right-click at the touch-down
    // point and suppress the release's left-click. A haptic tick confirms it (no cursor to see).
    private static final long LONG_PRESS_MS = 350;
    private boolean longPressFired;          // this gesture already sent a right-click
    private final Runnable longPressRunnable = () -> {
        if (!panGestureOwned || tapMoved || scaling || longPressFired) return;
        longPressFired = true;
        final int gx = gameX(tapDownX), gy = gameY(tapDownY);
        try {
            buttonInput(3, 1, gx, gy);   // right button down
            ui.postDelayed(() -> buttonInput(3, 0, gx, gy), 50);
        } catch (UnsatisfiedLinkError ig) {}
        if (controls != null) controls.maybeHaptic();
    };

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
    private void safeScroll(int x, int y, int dy) { scrollInput(x, y, dy); }

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

    // === FPS overlay: poll the native presented-frame counter once a second ===
    private final Runnable fpsTick = new Runnable() {
        @Override public void run() {
            if (fpsView == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            long count;
            try { count = nativeGetFrameCount(); }
            catch (UnsatisfiedLinkError e) { return; }   // native not ready yet
            if (fpsLastTimeMs != 0) {
                long dFrames = count - fpsLastCount;
                long dMs = now - fpsLastTimeMs;
                if (dMs > 0) {
                    int fps = (int) Math.round(dFrames * 1000.0 / dMs);
                    fpsView.setText("FPS: " + fps);
                }
            }
            fpsLastCount = count;
            fpsLastTimeMs = now;
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (surfaceView != null) surfaceView.requestFocus();   // re-grab focus so sticks keep working
        if (fpsView != null) {                         // (re)start the 1 Hz FPS poll
            fpsLastTimeMs = 0;                          // reset so the first interval isn't skewed
            ui.removeCallbacks(fpsTick);
            ui.postDelayed(fpsTick, 1000);
        }
        if (gamepad != null) gamepad.start();         // resume the gamepad analog frame loop
        // Auto-hide the on-screen controls while a physical gamepad is connected (like Zomdroid).
        inputManager = (android.hardware.input.InputManager) getSystemService(INPUT_SERVICE);
        if (inputManager != null) inputManager.registerInputDeviceListener(deviceListener, null);
        refreshGamepadControls();                     // apply current connection state
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(fpsTick);                   // stop the FPS poll while backgrounded
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
        // Keyboard FIRST: a key with an SDL scancode (letters/space/digits/arrows) is injected as a key; only
        // if it has no scancode does it fall through to the gamepad handler. This stops the gamepad handler
        // (which swallows everything from a gamepad-ish source) from eating a combo keyboard+touchpad's keys.
        // 1.6/X11: ALSO mirror the key into the in-process X server (Unity's SDL x11 driver only sees
        // core X KeyPress/KeyRelease; the SDL injection below is invisible to it — same as pointer).
        // Winlator's Keyboard.onKeyEvent carries the full Android→XKeycode map and was never wired.
        com.rimdroid.xserver.XServer xs = com.rimdroid.xserver.XServerRunner.getXServer();
        if (xs != null) {
            try { xs.keyboard.onKeyEvent(event); } catch (Throwable ignored) {}
        }
        if (mouseKb != null && mouseKb.onKey(event)) return true;   // physical keyboard
        if (gamepad != null && gamepad.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (gamepad != null && gamepad.onMotion(event)) return true;
        if (mouseKb != null && mouseKb.onGenericMotion(event)) return true;   // mouse move/wheel/buttons
        return super.onGenericMotionEvent(event);
    }

    // A physical mouse press+drag streams as TOUCH events (TOOL_TYPE_MOUSE). Intercept them HERE (before the
    // on-screen controls view) so they move the cursor instead of panning the map; finger touches fall through.
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mouseKb != null && mouseKb.onTouch(event)) return true;
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        GameLauncher.destroyRimDroidWindow();
    }

    // === SurfaceHolder.Callback ==================================================
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // At scale 1.0 skip setFixedSize entirely — it would fire a second surfaceChanged and
        // recycle the BufferQueue for no gain (1.6/X11 path runs at native size; Codex: early
        // Surface/BufferQueue generation churn makes Unity's Vulkan WSI leak swapchains).
        if (renderScale >= 0.999f) {
            Log.i(TAG, "surfaceCreated: scale=1.0, native size " + surfaceView.getWidth() + "x" + surfaceView.getHeight());
            return;
        }
        int bw = Math.max(1, Math.round(surfaceView.getWidth()  * renderScale));
        int bh = Math.max(1, Math.round(surfaceView.getHeight() * renderScale));
        Log.i(TAG, "surfaceCreated: scale=" + renderScale + " buffer=" + bw + "x" + bh);
        holder.setFixedSize(bw, bh);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height);
        GameLauncher.setSurfaceTracked(holder.getSurface(), width, height);
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
            // 1.6/X11 route: WINDOWED at surface size (fullscreen triggers SDL's WM-less
            // legacy-fullscreen dance → window loses SHOWN → Unity never presents).
            if (new java.io.File(gi.getGamePath(), "rd_x11").exists())
                PrefsXml.forceWindowed(new java.io.File(gi.getUserDataDir(), "Config"), width, height);
            else
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
