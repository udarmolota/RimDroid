package com.rimdroid;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import com.rimdroid.game.DotnetEngine;
import com.rimdroid.game.GameInstance;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * RimWorld 1.7 on .NET (experimental): runs the game's RimWorld.dll on the stock .NET 10 runtime for
 * Android in this own process (":clr"). The SurfaceView is the game's window: our libglfw.so hands
 * its surface to the engine for Vulkan, and touches, keys and the Back button go through it as
 * GLFW events. The log grows in a strip on top and in the instance's dotnet.log, for reports.
 */
public class ClrActivity extends Activity {
    public static final String EXTRA_INSTANCE = "rimdroid.instance";
    private static final String TAG = "RimDroid/clr";
    private static boolean started;   // one runtime per process

    private TextView text;
    private android.view.View logStrip;
    /** Opens the log strip after a long press in the top-left corner (see the surface's touch listener). */
    private final Runnable showLogStrip = () -> { if (logStrip != null) logStrip.setVisibility(android.view.View.VISIBLE); };
    private File logFile;
    private final Handler ui = new Handler(Looper.getMainLooper());

    static native int runClr(String coreclrPath, String assemblyPath, String logFile,
                             String[] keys, String[] values, String[] args);

    // libglfw.so (glfw_android.c)
    static native void glfwSurface(android.view.Surface surface, int width, int height);
    static native void glfwTouch(int action, float x, float y);
    static native void glfwKey(int glfwKey, int pressed, int codepoint);
    static native void glfwScroll(float dx, float dy);
    /** A mouse button: 0 left, 1 right, 2 middle; down 1/0. */
    static native void glfwMouse(int button, int down, float x, float y);
    /** One typed Unicode character. */
    static native void glfwChar(int codepoint);
    static native void glfwClose();
    /** The display the game is told about (size in landscape, refresh rate in Hz). */
    static native void glfwDisplayMode(int width, int height, int refreshHz);
    /** Frames presented so far (counted at vkQueuePresentKHR by the libvulkan.so wrapper). */
    static native long frameCount();
    /** Where the game last moved the pointer itself (glfwSetCursorPos), in game pixels; false if it didn't. */
    static native boolean glfwTakeCursorWarp(float[] out);

    // In-game overlay, the same global setting as 1.5/1.6 (Settings → Frame rate).
    private ClrInput input;   // on-screen controls, soft and physical keyboard
    private com.rimdroid.input.GamepadHandler gamepad;   // a physical controller, as on 1.6
    private android.view.SurfaceView surface;
    private int bufferW, bufferH;   // the game's window in game pixels (the surface buffer)
    private TextView fpsText;
    private PerfOverlayView perfView;
    private PerfSampler perfSampler;
    private android.os.HandlerThread perfThread;
    private Handler perfHandler;
    private long fpsLastCount, fpsLastTimeMs;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        System.loadLibrary("glfw");
        String instanceName = getIntent().getStringExtra(EXTRA_INSTANCE);
        InstanceSettings settings = instanceName != null ? new InstanceSettings(instanceName) : null;
        dragPan = settings == null || settings.isDragPan();
        float refresh = useFastestRefreshRate(settings != null ? settings.getFpsCap() : 0);
        surface = new android.view.SurfaceView(this);
        // The instance's render size (Settings: render scale or a black-margin preset), as on the 1.6
        // native engine: the game draws into a smaller buffer that the compositor scales up; a preset
        // also gets its own shape, centred with black margins.
        android.graphics.Point real = new android.graphics.Point();
        getWindowManager().getDefaultDisplay().getRealSize(real);
        int sw = Math.max(real.x, real.y), sh = Math.min(real.x, real.y);
        int[] renderSize = settings != null ? com.rimdroid.game.NativeEngine.renderSize(this, settings) : null;
        float boxAspect = settings != null ? com.rimdroid.game.NativeEngine.boxAspect(settings) : 0f;
        int boxW = sw, boxH = sh;
        if (boxAspect > 0f) {
            boxW = Math.round(sh * boxAspect);
            if (boxW > sw) { boxW = sw; boxH = Math.round(sw / boxAspect); }
        }
        renderW = renderSize != null ? renderSize[0] : boxW;
        renderH = renderSize != null ? renderSize[1] : boxH;
        if (renderSize != null) surface.getHolder().setFixedSize(renderSize[0], renderSize[1]);
        surface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateInputGeometry());
        surface.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            @Override public void surfaceCreated(android.view.SurfaceHolder h) {}
            @Override public void surfaceChanged(android.view.SurfaceHolder h, int format, int w, int hh) {
                // The game's frames come at the display's fastest rate; say so for this surface too.
                if (android.os.Build.VERSION.SDK_INT >= 30 && refresh > 0) {
                    try { h.getSurface().setFrameRate(refresh, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT); }
                    catch (RuntimeException ignored) {}
                }
                bufferW = w; bufferH = hh;
                updateInputGeometry();
                glfwSurface(h.getSurface(), w, hh);
            }
            @Override public void surfaceDestroyed(android.view.SurfaceHolder h) { glfwSurface(null, 0, 0); }
        });
        TouchInput touch = new TouchInput();
        android.view.ScaleGestureDetector pinch = new android.view.ScaleGestureDetector(this, touch);
        surface.setOnTouchListener((v, e) -> {
            // The log strip opens on a 2 s press in the top-left corner: a tap there opened it by
            // accident (the game's resource list sits there), and the open strip covered the corner,
            // so the same tap could never close it (player report, 2026-11-12). A tap on the strip
            // itself closes it.
            int action = e.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN && logStrip != null && e.getX() < 60 && e.getY() < 60)
                v.postDelayed(showLogStrip, 2000);
            else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL
                    || (action == android.view.MotionEvent.ACTION_MOVE && (e.getX() > 120 || e.getY() > 120)))
                v.removeCallbacks(showLogStrip);
            if (touch.onMouse(e)) return true;
            pinch.onTouchEvent(e);
            touch.onTouch(e);
            return true;
        });
        // A mouse that moves with no button down and its wheel come as generic (hover/scroll) events.
        surface.setOnGenericMotionListener((v, e) -> touch.onMouse(e));
        text = new TextView(this);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(9);
        text.setTextColor(0xFFE0E0E0);
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0x88000000);
        scroll.addView(text);
        scroll.setFillViewport(true);   // the text fills the strip, so a tap anywhere on it closes it
        text.setOnClickListener(x -> scroll.setVisibility(android.view.View.GONE));
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        if (boxAspect > 0f) root.addView(surface, new android.widget.FrameLayout.LayoutParams(boxW, boxH, android.view.Gravity.CENTER));
        else root.addView(surface);
        root.setBackgroundColor(0xFF000000);
        // On-screen controls above the picture (below the log strip and the FPS overlay); touches
        // they do not take fall through to the surface (TouchInput).
        input = new ClrInput(this, root, instanceName);
        // A physical controller drives the on-screen controls' cursor and keys, as on the 1.6 engines
        // (GamepadHandler); 1.7 had no gamepad at all. Sticks and triggers come as generic motion to
        // the focused view only, so the surface takes focus.
        gamepad = new com.rimdroid.input.GamepadHandler(this, input.controls);
        surface.setFocusable(true);
        surface.setFocusableInTouchMode(true);
        surface.requestFocus();
        root.addView(scroll, new android.widget.FrameLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.45f), android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        addOverlay(root);
        // A physical mouse: Android's own pointer hidden, the drawn cursor is the only one
        // (TouchInput.mouseTo moves it); it doubled up with the drawn one (Mali tester, 2026-10-16).
        android.view.PointerIcon noPointer =
                android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL);
        root.setPointerIcon(noPointer);
        surface.setPointerIcon(noPointer);
        input.controls.setPointerIcon(noPointer);
        // A game: the screen stays on while it runs (it went dark mid-menu).
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // The log strip starts hidden (it covered the loading screen for 8 s; dotnet.log and the game's
        // own Logs/Player.log keep everything). A long press on the top-left corner shows it, e.g. on a
        // hang; a tap on it hides it.
        logStrip = scroll;
        scroll.setVisibility(android.view.View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        String name = getIntent().getStringExtra(EXTRA_INSTANCE);
        GameInstance gi = name != null ? new GameInstance(name) : null;
        if (gi == null) { finish(); return; }
        logFile = new File(gi.getGamePath(), "dotnet.log");
        text.setText("RimWorld 1.7 on .NET (experimental)\n" + gi.getGamePath() + "\n\n");
        if (started) { tail(); return; }
        started = true;
        new Thread(() -> run(gi), "rd-clr").start();
        tail();
    }

    private void run(GameInstance gi) {
        try {
            System.loadLibrary("clrhost");
            // .NET's Android crypto goes through Java (JNI): its JNI_OnLoad must see the VM, which only
            // happens when Java loads it. Loaded by .NET alone it crashed on the first hash (null JavaVM).
            System.loadLibrary("System.Security.Cryptography.Native.Android");
            DotnetEngine.setGpuBc(getIntent().getIntExtra(DotnetEngine.EXTRA_GPU_BC, -1));
            List<String[]> props = DotnetEngine.propertiesFor(this, gi);
            String[] keys = new String[props.size()], values = new String[props.size()];
            for (int i = 0; i < props.size(); i++) { keys[i] = props.get(i)[0]; values[i] = props.get(i)[1]; }
            String coreclr = new File(getApplicationInfo().nativeLibraryDir, "libcoreclr.so").getAbsolutePath();
            // Canonical game path, like DotnetEngine.propertiesFor (the engine compares paths as strings).
            String gamePath = new File(gi.getGamePath()).getCanonicalPath();
            String assembly = new File(gamePath, "RimWorld.dll").getAbsolutePath();
            // The game keeps its saves and settings where Linux does, under $XDG_CONFIG_HOME
            // (~/.config): the instance folder, as for 1.6 (unity3d/Ludeon Studios/...). Unset, it
            // tried /data/.config.
            android.system.Os.setenv("HOME", gamePath, true);
            android.system.Os.setenv("XDG_CONFIG_HOME", gamePath, true);
            android.system.Os.setenv("XDG_DATA_HOME", gamePath, true);
            android.system.Os.setenv("TMPDIR", getCacheDir().getCanonicalPath(), true);
            // Harmony's MonoMod drops a native helper into /tmp, which Android does not have
            // (mkstemp: permission denied, every patch failed); librdglibc swaps the helper there.
            File monomodDrop = new File(getCacheDir(), "monomod");
            if (!monomodDrop.isDirectory()) monomodDrop.mkdirs();
            android.system.Os.setenv("MONOMOD_HelperDropPath", monomodDrop.getCanonicalPath(), true);
            // Texture compression tier (Settings → Video), on this engine the smallest texture side
            // that libvulkan.so's shrink takes (see vulkan_wrapper.c): No = only the giants, the
            // 8192 atlas pages and such; Low = the 1024 sprites too; Ultra low = from 512.
            int tier = gi.settings().getTexTier();
            android.system.Os.setenv("RIMDROID_TEX_SHRINK", "1", true);
            // Text size (Settings, 120 % by default; libimgui.so, see imgui_brutal.h): at phone size the
            // game's text was too small to read; windows and boxes keep their size.
            android.system.Os.setenv("RIMDROID_TEXT_SCALE", String.valueOf(gi.settings().getTextScale() / 100f), true);
            android.system.Os.setenv("RIMDROID_TEX_SHRINK_MIN", tier == com.rimdroid.InstanceSettings.TEX_ULTRA ? "512"
                    : tier == com.rimdroid.InstanceSettings.TEX_LOW ? "1024" : "2048", true);
            // GPUs without BC textures: libvulkan.so re-encodes them to ETC2/EAC; DotnetEngine has
            // turned the engine's GPU encoder off for it (propertiesFor, above).
            if (DotnetEngine.bcConversion(gi)) android.system.Os.setenv("RIMDROID_BC_CONVERT", "1", true);
            DotnetEngine.defaultUiScale(gi, gi.settings(), renderW, renderH);
            // The 1.6 built-in mod is no use on 1.7 (see BuiltinControllerUiMod.install): off if on.
            com.rimdroid.BuiltinControllerUiMod.setActive(new File(gamePath), false);
            int code = runClr(coreclr, assembly, logFile.getAbsolutePath(), keys, values, new String[0]);
            Log.i(TAG, "runClr returned " + code);
            append("\n=== finished: " + code + " ===\n");
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
            append("\n=== could not start: " + t + " ===\n");
        }
    }

    private void append(String s) { ui.post(() -> text.append(s)); }

    // Back is Escape, as on a PC: the game's menu (quitting is there), not an instant close.
    @Override public void onBackPressed() { ClrInput.tapKey(ClrInput.ESCAPE); }

    // A physical keyboard (and the soft keyboard's key events): keys and the characters they type.
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) ClrInput.tapKey(ClrInput.ESCAPE);
            return true;
        }
        // A controller's buttons and D-pad go to the gamepad first: the keyboard path would take its
        // D-pad for arrow keys. Everything else is the keyboard's first, as on 1.6.
        if (gamepad != null && e.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD)
                && (android.view.KeyEvent.isGamepadButton(e.getKeyCode()) || isDpad(e.getKeyCode()))
                && gamepad.onKey(e)) return true;
        if (ClrInput.onKeyEvent(e)) return true;
        if (gamepad != null && gamepad.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private static boolean isDpad(int code) {
        return code == android.view.KeyEvent.KEYCODE_DPAD_UP || code == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                || code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                || code == android.view.KeyEvent.KEYCODE_DPAD_CENTER;
    }

    // A controller's sticks and triggers (a mouse is the surface's own, TouchInput.onMouse).
    @Override public boolean onGenericMotionEvent(android.view.MotionEvent e) {
        if (gamepad != null && gamepad.onMotion(e)) return true;
        return super.onGenericMotionEvent(e);
    }

    // The on-screen controls hide while a controller is connected and come back when it goes, as on
    // 1.6; only on a change, so the manual hide toggle is left alone.
    private android.hardware.input.InputManager inputManager;
    private boolean lastPadConnected;
    private final android.hardware.input.InputManager.InputDeviceListener padListener =
            new android.hardware.input.InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int id)   { refreshGamepadControls(); }
                @Override public void onInputDeviceRemoved(int id) { refreshGamepadControls(); }
                @Override public void onInputDeviceChanged(int id) { refreshGamepadControls(); }
            };

    private void refreshGamepadControls() {
        boolean pad = com.rimdroid.input.GamepadHandler.hasConnectedGamepad();
        if (pad == lastPadConnected) return;
        lastPadConnected = pad;
        if (input != null) input.controls.setControlsHidden(pad);
    }

    private long shown;

    /** Shows what the runtime writes to dotnet.log, twice a second. */
    private void tail() {
        ui.postDelayed(() -> {
            if (isFinishing()) return;
            try (RandomAccessFile f = new RandomAccessFile(logFile, "r")) {
                long len = f.length();
                if (len > shown) {
                    f.seek(shown);
                    byte[] b = new byte[(int) Math.min(len - shown, 256 * 1024)];
                    f.readFully(b);
                    shown += b.length;
                    text.append(new String(b, java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {}
            tail();
        }, 500);
    }

    private int renderW, renderH;   // the size the game renders at (for the UI scale default)

    /**
     * Where the game is on screen and how big its pixels are: the on-screen controls hand the game
     * game pixels, and the touches on the map go through the same scale (TouchInput).
     */
    private void updateInputGeometry() {
        if (input == null || surface == null || surface.getWidth() <= 0 || bufferW <= 0) return;
        input.controls.setRenderScale(bufferW / (float) surface.getWidth());
        input.controls.setGameRect(surface.getLeft(), surface.getTop(), surface.getWidth(), surface.getHeight());
    }

    /**
     * Asks for the display's fastest refresh rate at its current resolution (120 Hz on most recent
     * phones; Android otherwise may keep a game at 60), and tells the game: RimWorld caps its frame
     * rate at the refresh rate it reads from GLFW. Returns that rate, 0 if unknown.
     */
    private float useFastestRefreshRate(int fpsCap) {
        android.view.Display display = getWindowManager().getDefaultDisplay();
        android.view.Display.Mode current = display.getMode();
        android.view.Display.Mode best = current;
        for (android.view.Display.Mode m : display.getSupportedModes())
            if (m.getPhysicalWidth() == current.getPhysicalWidth() && m.getPhysicalHeight() == current.getPhysicalHeight()
                    && m.getRefreshRate() > best.getRefreshRate()) best = m;
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(lp);
        int w = Math.max(best.getPhysicalWidth(), best.getPhysicalHeight());
        int h = Math.min(best.getPhysicalWidth(), best.getPhysicalHeight());
        // The instance's frame-rate cap (Settings) is the refresh rate the game hears: RimWorld sets its
        // own frame limit to it.
        int hz = Math.round(best.getRefreshRate());
        glfwDisplayMode(w, h, fpsCap > 0 ? Math.min(fpsCap, hz) : hz);
        Log.i(TAG, "display mode " + w + "x" + h + " @ " + best.getRefreshRate() + " Hz (was " + current.getRefreshRate() + ")");
        return best.getRefreshRate();
    }

    private void addOverlay(android.widget.FrameLayout root) {
        LauncherPreferences prefs = LauncherPreferences.getSingleton();
        int hud = prefs != null ? prefs.getHudMode() : LauncherPreferences.HUD_OFF;
        float dp = getResources().getDisplayMetrics().density;
        if (hud == LauncherPreferences.HUD_FPS) {
            // The classic green "FPS: XX", top-left, moved right by its own width like on 1.6.
            fpsText = new TextView(this);
            fpsText.setText("FPS: --");
            fpsText.setTextColor(0xFF00FF66);
            fpsText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            fpsText.setShadowLayer(4f, 0f, 0f, 0xFF000000);
            int m = Math.round(8 * dp);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.setMargins(m, m, 0, 0);
            root.addView(fpsText, lp);
            fpsText.post(() -> {
                android.widget.FrameLayout.LayoutParams l = (android.widget.FrameLayout.LayoutParams) fpsText.getLayoutParams();
                l.leftMargin = m + fpsText.getWidth();
                fpsText.setLayoutParams(l);
            });
        } else if (hud == LauncherPreferences.HUD_FULL) {
            // The full bar, top-centre; 1.7 draws with Vulkan directly.
            perfView = new PerfOverlayView(this, "VK");
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            lp.setMargins(0, Math.round(4 * dp), 0, 0);
            root.addView(perfView, lp);
            perfSampler = new PerfSampler(this);
            perfThread = new android.os.HandlerThread("PerfOverlay");
            perfThread.start();
            perfHandler = new Handler(perfThread.getLooper());
        }
    }

    private final Runnable fpsTextTick = new Runnable() {
        @Override public void run() {
            if (fpsText == null) return;
            long now = android.os.SystemClock.elapsedRealtime(), count = frameCount();
            if (fpsLastTimeMs != 0 && now > fpsLastTimeMs)
                fpsText.setText("FPS: " + Math.round((count - fpsLastCount) * 1000.0 / (now - fpsLastTimeMs)));
            fpsLastCount = count;
            fpsLastTimeMs = now;
            ui.postDelayed(this, 1000);
        }
    };

    private final Runnable perfTick = new Runnable() {
        @Override public void run() {
            if (perfView == null || perfSampler == null || perfHandler == null) return;
            final PerfSampler.Stats stats = perfSampler.sample(frameCount());
            ui.post(() -> { if (perfView != null) perfView.setStats(stats); });
            perfHandler.postDelayed(this, 1000);
        }
    };

    /**
     * When the game moves the pointer itself (a mod calling glfwSetCursorPos, as on desktop), the drawn
     * cursor follows on the next frame, so the stick and the next tap carry on from there.
     */
    private final float[] cursorWarpPos = new float[2];
    private final android.view.Choreographer.FrameCallback cursorWarp = new android.view.Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (input != null && glfwTakeCursorWarp(cursorWarpPos)) input.controls.setCursorFromGame(cursorWarpPos[0], cursorWarpPos[1]);
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        if (fpsText != null) { fpsLastTimeMs = 0; ui.removeCallbacks(fpsTextTick); ui.postDelayed(fpsTextTick, 1000); }
        if (perfHandler != null) { perfHandler.removeCallbacks(perfTick); perfHandler.post(perfTick); }
        if (gamepad != null) gamepad.start();
        android.view.Choreographer.getInstance().postFrameCallback(cursorWarp);
        if (surface != null) surface.requestFocus();
        inputManager = (android.hardware.input.InputManager) getSystemService(INPUT_SERVICE);
        if (inputManager != null) inputManager.registerInputDeviceListener(padListener, null);
        refreshGamepadControls();
    }

    @Override protected void onPause() {
        super.onPause();
        if (gamepad != null) gamepad.stop();
        android.view.Choreographer.getInstance().removeFrameCallback(cursorWarp);
        if (inputManager != null) inputManager.unregisterInputDeviceListener(padListener);
        if (input != null) input.controls.resetAll();   // nothing stays held while away
        ui.removeCallbacks(fpsTextTick);
        if (perfHandler != null) perfHandler.removeCallbacks(perfTick);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (perfThread != null) perfThread.quitSafely();
    }

    /**
     * One finger is the mouse. A tap is a left click; a finger held still is a right click (orders
     * menu). A finger that moves pans the map with the middle button held, as RimWorld does with a
     * mouse, so the map follows the finger one to one (Settings → "Drag the map to pan", on by
     * default; 1.7 had no finger panning at all, 2026-10-22); with that setting off it drags with
     * the left button held (box selection, sliders).
     * Two fingers pinch-zoom: RimWorld zooms with the mouse wheel, so the pinch turns into wheel
     * steps, with the cursor between the fingers. The left button goes down only once the finger
     * moves or lifts, so a second finger landing first makes a clean pinch with no click.
     * Touches act where the finger is, but the drawn cursor (the on-screen controls' stick and
     * trackpad) stays where it was, as on the other engines: players who aim with the stick lost the
     * pointer after every tap (1.6 native engine, 2026-10-13).
     */
    /** Settings → "Drag the map to pan" (on by default), as on the 1.6 engines. */
    private boolean dragPan = true;

    private final class TouchInput extends android.view.ScaleGestureDetector.SimpleOnScaleGestureListener {
        private static final long LONG_PRESS_MS = 350;   // as on the other engines
        private static final float STEP = 1.12f;   // pinch scale change per wheel step
        private boolean pending, pressed, gesture, rightClicked;
        private int dragButton;   // the button a moving finger holds: 2 middle (pan) or 0 left
        private float downX, downY, scaleAcc = 1f;
        private final Runnable longPress = () -> {
            if (!pending || gesture) return;
            pending = false;
            rightClicked = true;
            glfwMouse(1, 1, gx(downX), gy(downY));
            glfwMouse(1, 0, gx(downX), gy(downY));
            input.controls.maybeHaptic();
        };

        /** The game's pointer to the finger; the drawn cursor stays. */
        private void pointerTo(float x, float y) { glfwTouch(2, gx(x), gy(y)); }

        // Touch positions are the surface's view pixels; the game's are its buffer's.
        private float gx(float x) { return surface.getWidth() > 0 && bufferW > 0 ? x * bufferW / surface.getWidth() : x; }
        private float gy(float y) { return surface.getHeight() > 0 && bufferH > 0 ? y * bufferH / surface.getHeight() : y; }

        private void pressLeft() { press(0); }

        private void press(int button) {
            pending = false;
            pressed = true;
            dragButton = button;
            pointerTo(downX, downY);
            glfwMouse(button, 1, gx(downX), gy(downY));
        }

        void onTouch(android.view.MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    gesture = false; pressed = false; rightClicked = false; pending = true;
                    downX = x; downY = y;                     // the clicks carry the finger's position
                    ui.postDelayed(longPress, LONG_PRESS_MS);
                    break;
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                    ui.removeCallbacks(longPress);
                    pending = false;
                    if (pressed) { glfwMouse(dragButton, 0, gx(downX), gy(downY)); pressed = false; }
                    gesture = true;
                    scaleAcc = 1f;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (gesture || rightClicked) break;
                    if (pending && Math.hypot(x - downX, y - downY) > 12 * getResources().getDisplayMetrics().density) {
                        ui.removeCallbacks(longPress);
                        press(dragPan ? 2 : 0);
                    }
                    if (pressed) pointerTo(x, y);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(longPress);
                    if (!gesture && !rightClicked) {
                        if (pending) pressLeft();             // a tap
                        if (pressed) glfwMouse(dragButton, 0, gx(x), gy(y));
                    }
                    pending = false; pressed = false; gesture = false; rightClicked = false;
                    break;
            }
        }

        private int mouseButtons;   // MotionEvent.BUTTON_* the game was told are down

        /**
         * A physical mouse: the pointer follows it with no button down, the wheel zooms, and its
         * buttons are the game's buttons as they are, with none of the finger gestures (long press,
         * pinch). Only 1.6 had this; on 1.7 a mouse did nothing (Mali tester, 2026-10-16).
         * Returns false for anything that is not a mouse.
         */
        boolean onMouse(android.view.MotionEvent e) {
            if (!e.isFromSource(android.view.InputDevice.SOURCE_MOUSE)
                    || e.getToolType(0) != android.view.MotionEvent.TOOL_TYPE_MOUSE) return false;
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_SCROLL:
                    mouseTo(x, y);
                    float v = e.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL);
                    float h = e.getAxisValue(android.view.MotionEvent.AXIS_HSCROLL);
                    if (v != 0 || h != 0) glfwScroll(h, v);
                    return true;
                case android.view.MotionEvent.ACTION_HOVER_ENTER:
                case android.view.MotionEvent.ACTION_HOVER_MOVE:
                case android.view.MotionEvent.ACTION_MOVE:
                    mouseTo(x, y);
                    break;
                case android.view.MotionEvent.ACTION_HOVER_EXIT:
                    return true;
            }
            // Buttons from the state, not the action: press and release events differ by device.
            int now = e.getButtonState();
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP
                    || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) now = 0;
            mouseButton(now, android.view.MotionEvent.BUTTON_PRIMARY, 0, x, y);
            mouseButton(now, android.view.MotionEvent.BUTTON_SECONDARY, 1, x, y);
            mouseButton(now, android.view.MotionEvent.BUTTON_TERTIARY, 2, x, y);
            return true;
        }

        /**
         * The drawn cursor (the on-screen controls') and the game's pointer to the mouse: one cursor
         * on screen, as on 1.6. The controls work in the root's pixels, the mouse in the surface's;
         * the game's pointer is set last from the surface position, the exact one.
         */
        private void mouseTo(float x, float y) {
            if (input != null) input.controls.moveCursorTo(x + surface.getLeft(), y + surface.getTop());
            pointerTo(x, y);
        }

        private void mouseButton(int now, int mask, int button, float x, float y) {
            boolean down = (now & mask) != 0;
            if (down == ((mouseButtons & mask) != 0)) return;
            mouseButtons = down ? mouseButtons | mask : mouseButtons & ~mask;
            mouseTo(x, y);
            glfwMouse(button, down ? 1 : 0, gx(x), gy(y));
        }

        @Override public boolean onScale(android.view.ScaleGestureDetector d) {
            if (!gesture) return true;
            scaleAcc *= d.getScaleFactor();
            pointerTo(d.getFocusX(), d.getFocusY());          // zoom where the fingers are
            while (scaleAcc >= STEP) { glfwScroll(0, 1); scaleAcc /= STEP; }     // spread: zoom in
            while (scaleAcc <= 1f / STEP) { glfwScroll(0, -1); scaleAcc *= STEP; } // pinch: zoom out
            return true;
        }
    }
}
