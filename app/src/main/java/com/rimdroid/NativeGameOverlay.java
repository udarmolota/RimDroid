package com.rimdroid;

import android.app.Activity;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.rimdroid.game.NativeEngine;
import com.rimdroid.game.NativeInput;
import com.rimdroid.input.Binding;
import com.rimdroid.input.GamepadHandler;
import com.rimdroid.input.InputControlsView;
import com.rimdroid.input.InputSink;

/**
 * What the native engine shows over Unity's player (NativeUnityActivity creates it by name, in the
 * ":unity" process): the instance's on-screen controls, the bare-map gestures, the soft keyboard and
 * the in-game HUD, as GameActivity has them. The controls' input becomes Android mouse and keyboard
 * events injected into the player ({@link InputSink.Backend}): the game's legacy Input and IMGUI
 * read them like a desktop mouse and keyboard. The wheel goes through the managed bridge. Every
 * touch is taken here, so Unity's own touchscreen never sees one.
 */
public class NativeGameOverlay extends FrameLayout
        implements View.OnKeyListener, View.OnGenericMotionListener, InputSink.Backend {
    private static final String TAG = "RimDroid/NativeOverlay";

    private final InputControlsView controls;
    private final GamepadHandler gamepad;
    private final KeyboardCatcher keyboardCatcher;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private java.lang.reflect.Method inject;   // NativeUnityActivity.injectInputEvent(InputEvent)
    private boolean dragPanEnabled = true;     // per-instance (Settings → "Drag the map to pan")
    private float boxAspect;                   // the player's box for the 720p presets, 0 = the whole screen
    private int boxLeft, boxTop, boxW, boxH;   // where the player draws, in this view's coordinates

    // HUD, global setting (Settings → Frame rate): off, the classic FPS counter or the full bar.
    private TextView fpsText;
    private long fpsLastCount, fpsLastTimeMs;
    private PerfOverlayView perfView;
    private PerfSampler perfSampler;
    private HandlerThread perfThread;
    private Handler perfHandler;

    public NativeGameOverlay(Context context) {
        super(context);
        String instanceName = context instanceof Activity
                ? ((Activity) context).getIntent().getStringExtra(NativeEngine.EXTRA_INSTANCE) : null;
        // The game's own log for "Report a bug": first, so it has as much of the start as possible.
        AppStorage storage = AppStorage.getSingleton();
        if (storage != null && instanceName != null && context instanceof Activity) {
            com.rimdroid.game.NativeLog.start(storage.getInstanceDir(instanceName),
                    ((Activity) context).getIntent().getStringExtra(NativeEngine.EXTRA_LAUNCH_CONFIG));
        }
        if (context instanceof Activity) {
            try {
                inject = context.getClass().getMethod("injectInputEvent", InputEvent.class);
            } catch (NoSuchMethodException e) {
                Log.e(TAG, "the activity cannot inject input events", e);
            }
        }
        if (instanceName != null) {
            InstanceSettings is = new InstanceSettings(instanceName);
            dragPanEnabled = is.isDragPan();
            boxAspect = NativeEngine.boxAspect(is);
        }

        InputSink.setBackend(this);
        controls = new InputControlsView(context, 1f, instanceName);   // the player fills the screen
        addView(controls, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        keyboardCatcher = new KeyboardCatcher(context);
        addView(keyboardCatcher, new LayoutParams(1, 1));   // 1 px, invisible
        gamepad = context instanceof Activity ? new GamepadHandler((Activity) context, controls) : null;

        int hud = LauncherPreferences.getSingleton() != null
                ? LauncherPreferences.getSingleton().getHudMode() : LauncherPreferences.HUD_OFF;
        if (hud == LauncherPreferences.HUD_FPS) addFpsText(context);
        if (hud == LauncherPreferences.HUD_FULL) addPerfBar(context);
    }

    // The same box NativeUnityActivity gives the player: the cursor stays inside it and taps on the
    // margins clamp to its edge, as GameActivity does for the box64 engine.
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        boxLeft = 0; boxTop = 0; boxW = w; boxH = h;
        if (boxAspect > 0f && w > 0 && h > 0) {
            boxH = h; boxW = Math.round(h * boxAspect);
            if (boxW > w) { boxW = w; boxH = Math.round(w / boxAspect); }
            boxLeft = (w - boxW) / 2;
            boxTop = (h - boxH) / 2;
        }
        controls.setGameRect(boxLeft, boxTop, boxW, boxH);
    }

    // ------------------------------------------------------------------ input into the player

    private int buttonState;   // MotionEvent.BUTTON_* held
    private int metaState;     // KeyEvent.META_* held (Shift from the on-screen button)
    private long downTime;

    private void send(InputEvent e) {
        if (inject == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {   // the gamepad's loop runs on its own thread
            ui.post(() -> send(e));
            return;
        }
        try {
            inject.invoke(getContext(), e);
        } catch (ReflectiveOperationException ex) {
            Log.w(TAG, "inject failed", ex);
        }
    }

    private MotionEvent mouse(int action, float x, float y, int buttons, float vscroll) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
        p.id = 0;
        p.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = buttons != 0 ? 1f : 0f;
        c.size = 1f;
        if (vscroll != 0) c.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        return MotionEvent.obtain(downTime, now, action, 1, new MotionEvent.PointerProperties[] { p },
                new MotionEvent.PointerCoords[] { c }, metaState, buttons, 1f, 1f,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_MOUSE, 0);
    }

    @Override public void touch(int x, int y) {
        send(mouse(buttonState != 0 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE, x, y, buttonState, 0));
    }

    private static int buttonBit(int button) {
        switch (button) {
            case 1: return MotionEvent.BUTTON_PRIMARY;
            case 2: return MotionEvent.BUTTON_TERTIARY;
            case 3: return MotionEvent.BUTTON_SECONDARY;
            default: return 0;
        }
    }

    @Override public void button(int button, boolean down, int x, int y) {
        int bit = buttonBit(button);
        if (bit == 0) return;
        if (down) {
            boolean first = buttonState == 0;
            buttonState |= bit;
            if (first) downTime = SystemClock.uptimeMillis();
            send(mouse(first ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_MOVE, x, y, buttonState, 0));
        } else {
            buttonState &= ~bit;
            send(mouse(buttonState == 0 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, x, y, buttonState, 0));
        }
    }

    // Android's legacy Unity input drops the wheel. The bridge routes it to lists or cameras. Until
    // it reports ready, use the zoom keys pinned by PrefsXml; queued taps are kept separate.
    private static final int SDL_SCANCODE_MINUS = 45, SDL_SCANCODE_EQUALS = 46;
    private static final long ZOOM_TAP_MS = 40, ZOOM_GAP_MS = 80;
    private long zoomQueueUntil;
    private float fallbackScroll;

    @Override public void scroll(int x, int y, int dy) {
        scrollWheel(x, y, dy);
    }

    private void scrollWheel(int x, int y, float notches) {
        if (notches == 0 || !Float.isFinite(notches)) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(() -> scrollWheel(x, y, notches));
            return;
        }
        touch(x, y);
        if (boxW > 0 && boxH > 0 && NativeInput.scroll(x / (float) boxW, y / (float) boxH, notches)) {
            fallbackScroll = 0;
            return;
        }
        fallbackScroll += notches;
        while (Math.abs(fallbackScroll) >= 1f) {
            int sc = fallbackScroll > 0 ? SDL_SCANCODE_EQUALS : SDL_SCANCODE_MINUS;
            fallbackScroll += fallbackScroll > 0 ? -1f : 1f;
            long at = Math.max(SystemClock.uptimeMillis(), zoomQueueUntil);
            zoomQueueUntil = at + ZOOM_GAP_MS;
            ui.postAtTime(() -> key(sc, 0, true), at);
            ui.postAtTime(() -> key(sc, 0, false), at + ZOOM_TAP_MS);
        }
    }

    @Override public void key(int sdlScancode, int sdlKeycode, boolean down) {
        int code = androidKeyCode(sdlScancode);
        if (code == KeyEvent.KEYCODE_UNKNOWN) return;
        int meta = metaFor(code);
        if (meta != 0) metaState = down ? (metaState | meta) : (metaState & ~meta);
        long now = SystemClock.uptimeMillis();
        send(new KeyEvent(now, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    @Override public void text(String text) {
        // The key event carries the character: the player makes the text from it.
    }

    @Override public void toggleKeyboard() {
        keyboardCatcher.toggle();
    }

    private static int metaFor(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_SHIFT_LEFT: return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_RIGHT_ON;
            case KeyEvent.KEYCODE_CTRL_LEFT: return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON;
            case KeyEvent.KEYCODE_ALT_LEFT: return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            case KeyEvent.KEYCODE_ALT_RIGHT: return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON;
            default: return 0;
        }
    }

    /** SDL scancode (Binding.code) → Android key code; the player maps those to Unity's KeyCode. */
    static int androidKeyCode(int sc) {
        if (sc >= 4 && sc <= 29) return KeyEvent.KEYCODE_A + (sc - 4);
        if (sc >= 30 && sc <= 38) return KeyEvent.KEYCODE_1 + (sc - 30);
        if (sc >= 58 && sc <= 69) return KeyEvent.KEYCODE_F1 + (sc - 58);
        switch (sc) {
            case 39: return KeyEvent.KEYCODE_0;
            case 40: return KeyEvent.KEYCODE_ENTER;
            case 41: return KeyEvent.KEYCODE_ESCAPE;
            case 42: return KeyEvent.KEYCODE_DEL;
            case 43: return KeyEvent.KEYCODE_TAB;
            case 44: return KeyEvent.KEYCODE_SPACE;
            case 45: return KeyEvent.KEYCODE_MINUS;
            case 46: return KeyEvent.KEYCODE_EQUALS;
            case 47: return KeyEvent.KEYCODE_LEFT_BRACKET;
            case 48: return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case 49: return KeyEvent.KEYCODE_BACKSLASH;
            case 51: return KeyEvent.KEYCODE_SEMICOLON;
            case 52: return KeyEvent.KEYCODE_APOSTROPHE;
            case 53: return KeyEvent.KEYCODE_GRAVE;
            case 54: return KeyEvent.KEYCODE_COMMA;
            case 55: return KeyEvent.KEYCODE_PERIOD;
            case 56: return KeyEvent.KEYCODE_SLASH;
            case 57: return KeyEvent.KEYCODE_CAPS_LOCK;
            case 73: return KeyEvent.KEYCODE_INSERT;
            case 74: return KeyEvent.KEYCODE_MOVE_HOME;
            case 75: return KeyEvent.KEYCODE_PAGE_UP;
            case 76: return KeyEvent.KEYCODE_FORWARD_DEL;
            case 77: return KeyEvent.KEYCODE_MOVE_END;
            case 78: return KeyEvent.KEYCODE_PAGE_DOWN;
            case 79: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 80: return KeyEvent.KEYCODE_DPAD_LEFT;
            case 81: return KeyEvent.KEYCODE_DPAD_DOWN;
            case 82: return KeyEvent.KEYCODE_DPAD_UP;
            case 224: return KeyEvent.KEYCODE_CTRL_LEFT;
            case 225: return KeyEvent.KEYCODE_SHIFT_LEFT;
            case 226: return KeyEvent.KEYCODE_ALT_LEFT;
            case 228: return KeyEvent.KEYCODE_CTRL_RIGHT;
            case 229: return KeyEvent.KEYCODE_SHIFT_RIGHT;
            case 230: return KeyEvent.KEYCODE_ALT_RIGHT;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    // ------------------------------------------------------------------ touches on the bare map

    // As GameActivity: a quick tap is a left click, a finger held still a right click, a one-finger
    // drag pans the camera (held arrow keys; the world follows the finger), a pinch is the wheel.
    private static final long LONG_PRESS_MS = 350;
    private ScaleGestureDetector scaleDetector;
    private boolean scaling, tapMoved, longPressFired, panGestureOwned;
    private float tapDownX, tapDownY, lastPanX, lastPanY;
    private long tapDownT;
    private final boolean[] panHeld = new boolean[4];   // 0 = up, 1 = right, 2 = down, 3 = left
    private static final Binding[] PAN_KEYS = { Binding.KEY_UP, Binding.KEY_RIGHT, Binding.KEY_DOWN, Binding.KEY_LEFT };
    private final Runnable panIdleRelease = this::releasePan;
    private final Runnable longPressRunnable = this::longPress;
    private boolean twoPanning, twoDown;   // twoDown: the middle button really went down (it waits)
    private float twoDownX, twoDownY, twoLastX, twoLastY;
    // The pointer moves to the fingers first and the middle button goes down a few frames later, or the
    // camera jumps by the pointer's move from the last touch (see ClrActivity.PAN_PRESS_DELAY_MS).
    private final Runnable delayedTwoPress = () -> {
        if (!twoPanning || twoDown) return;
        button(2, true, gameX(twoLastX) - boxLeft, gameY(twoLastY) - boxTop);
        twoDown = true;
    };

    private void endTwoPan() {
        ui.removeCallbacks(delayedTwoPress);
        if (!twoPanning) return;
        if (twoDown) button(2, false, gameX(twoLastX) - boxLeft, gameY(twoLastY) - boxTop);
        twoPanning = false;
        twoDown = false;
    }

    private int gameX(float x) { return Math.round(Math.max(boxLeft, Math.min(boxLeft + boxW - 1, x))); }
    private int gameY(float y) { return Math.round(Math.max(boxTop, Math.min(boxTop + boxH - 1, y))); }

    // Positions handed to the player are relative to its box (InputControlsView's gx/gy give them that
    // way for the on-screen controls); the gestures here work in this view's coordinates and take
    // the margins off the same way.
    // A tap acts where the finger is, but the drawn cursor stays where the stick left it, as on the
    // box64 engine: players who aim with the stick lost the pointer after every tap (player, 2026-10-13).
    private void click(int button, int x, int y) {
        final int bx = x - boxLeft, by = y - boxTop;
        touch(bx, by);   // the game's pointer to the finger, then the press there
        button(button, true, bx, by);
        ui.postDelayed(() -> button(button, false, bx, by), 50);
    }

    private void longPress() {
        if (!panGestureOwned || tapMoved || scaling || longPressFired) return;
        longPressFired = true;
        click(3, gameX(tapDownX), gameY(tapDownY));
        controls.maybeHaptic();
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        private float accum;
        @Override public boolean onScaleBegin(ScaleGestureDetector dt) { scaling = true; accum = 0f; return true; }
        @Override public boolean onScale(ScaleGestureDetector dt) {
            accum += dt.getScaleFactor() - 1f;
            int fx = gameX(dt.getFocusX()), fy = gameY(dt.getFocusY());
            while (accum >  0.15f) { accum -= 0.15f; pinchScroll(fx, fy, +1); }
            while (accum < -0.15f) { accum += 0.15f; pinchScroll(fx, fy, -1); }
            return true;
        }
        @Override public void onScaleEnd(ScaleGestureDetector dt) { scaling = false; }
    }

    // Zooms where the fingers are (scroll moves the game's pointer there); the drawn cursor stays.
    private void pinchScroll(int x, int y, int notches) {
        scroll(x - boxLeft, y - boxTop, notches);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (isMouse(e)) return mouseTouch(e);
        if (scaleDetector == null) scaleDetector = new ScaleGestureDetector(getContext(), new ScaleListener());
        scaleDetector.onTouchEvent(e);
        if (e.getPointerCount() >= 2 || scaling) {   // pinch + two-finger pan: never a tap or a long press
            ui.removeCallbacks(longPressRunnable);
            releasePan();
            panGestureOwned = false;
            // Two-finger pan (from the Wishing-Qi fork, 2026-11-19): once the fingers' midpoint moves,
            // it drags the map with the middle button held; the pinch still zooms.
            int ma = e.getActionMasked();
            if (ma == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() >= 2) {
                endTwoPan();
                twoDownX = twoLastX = (e.getX(0) + e.getX(1)) * 0.5f;
                twoDownY = twoLastY = (e.getY(0) + e.getY(1)) * 0.5f;
            } else if (ma == MotionEvent.ACTION_MOVE && e.getPointerCount() >= 2) {
                twoLastX = (e.getX(0) + e.getX(1)) * 0.5f;
                twoLastY = (e.getY(0) + e.getY(1)) * 0.5f;
                int bx = gameX(twoLastX) - boxLeft, by = gameY(twoLastY) - boxTop;
                if (ClrActivity.TWO_FINGER_PAN && !twoPanning && Math.hypot(twoLastX - twoDownX, twoLastY - twoDownY) > 6 * getResources().getDisplayMetrics().density) {
                    touch(bx, by);
                    twoPanning = true;
                    twoDown = false;
                    ui.postDelayed(delayedTwoPress, ClrActivity.PAN_PRESS_DELAY_MS);
                } else if (twoPanning) {
                    touch(bx, by);
                }
            } else if (ma == MotionEvent.ACTION_POINTER_UP || ma == MotionEvent.ACTION_UP || ma == MotionEvent.ACTION_CANCEL) {
                endTwoPan();
            }
            return true;
        }
        float d = getResources().getDisplayMetrics().density;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tapDownX = e.getX(); tapDownY = e.getY(); tapDownT = System.currentTimeMillis();
                lastPanX = e.getX(); lastPanY = e.getY();
                tapMoved = false; longPressFired = false; panGestureOwned = true;
                ui.removeCallbacks(longPressRunnable);
                ui.postDelayed(longPressRunnable, LONG_PRESS_MS);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!panGestureOwned) return true;   // a MOVE leaked from an on-screen button: never pan
                float dx = e.getX() - tapDownX, dy = e.getY() - tapDownY;
                if (Math.abs(dx) + Math.abs(dy) > 22 * d) {
                    if (!tapMoved) ui.removeCallbacks(longPressRunnable);   // a drag, not a long press
                    tapMoved = true;
                }
                if (tapMoved && dragPanEnabled) {
                    // Only while the finger really slides: a displaced but still finger must not keep
                    // the camera accelerating.
                    boolean sliding = Math.abs(e.getX() - lastPanX) + Math.abs(e.getY() - lastPanY) > 2 * d;
                    if (sliding) {
                        updateDragPan(dx, dy, 22 * d);
                        lastPanX = e.getX(); lastPanY = e.getY();
                        ui.removeCallbacks(panIdleRelease);
                        ui.postDelayed(panIdleRelease, 90);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                releasePan();
                ui.removeCallbacks(longPressRunnable);
                if (panGestureOwned && !tapMoved && !scaling && !longPressFired
                        && System.currentTimeMillis() - tapDownT < 250)
                    click(1, gameX(e.getX()), gameY(e.getY()));
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

    // The world follows the finger: finger down → camera north, and so on.
    private void updateDragPan(float dx, float dy, float dead) {
        setPan(0, dy > dead);
        setPan(2, dy < -dead);
        setPan(1, dx < -dead);
        setPan(3, dx > dead);
    }

    private void setPan(int i, boolean want) {
        if (panHeld[i] == want) return;
        panHeld[i] = want;
        controls.inject(PAN_KEYS[i], want);
    }

    private void releasePan() {
        ui.removeCallbacks(panIdleRelease);
        for (int i = 0; i < 4; i++) setPan(i, false);
    }

    // ------------------------------------------------------------------ physical gamepad

    @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
        return gamepad != null && gamepad.onKey(event);
    }

    @Override public boolean onGenericMotion(View v, MotionEvent event) {
        if (gamepad != null && gamepad.onMotion(event)) return true;
        return isMouse(event) && mouseMotion(event);
    }

    // ------------------------------------------------------------------ physical mouse

    // A real mouse is taken whole, as the touches are: its moves drive our cursor, its wheel the
    // managed scroll bridge, its buttons our clicks. Left to the player, its events arrive in window
    // coordinates (off by the margins of a boxed player) and in between our injected clicks,
    // which then get lost (S25, 2026-10-11).
    private static boolean isMouse(MotionEvent e) {
        return (e.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;
    }

    // A mouse press must not land on the on-screen buttons under it (GameActivity intercepts the
    // same way): the mouse is the game's, the buttons are for fingers.
    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        return isMouse(e) || super.onInterceptTouchEvent(e);
    }

    private boolean mouseMotion(MotionEvent e) {
        controls.setCursorVisible(false);   // the game draws its own cursor for a real mouse
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                controls.moveCursorTo(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_SCROLL: {
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                controls.moveCursorTo(e.getX(), e.getY());
                scrollWheel(gameX(e.getX()) - boxLeft, gameY(e.getY()) - boxTop, v);
                return true;
            }
            default:
                return true;   // button press/release come as touch events; nothing else is the player's
        }
    }

    private int mouseButtonsDown;   // MotionEvent.BUTTON_* we reported pressed

    private boolean mouseTouch(MotionEvent e) {
        controls.setCursorVisible(false);
        int x = gameX(e.getX()), y = gameY(e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                controls.moveCursorTo(e.getX(), e.getY());
                boolean up = e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                // A synthetic mouse tap (adb "input mouse tap") carries no button state: the left one.
                int now = up ? 0 : e.getButtonState() != 0 ? e.getButtonState() : MotionEvent.BUTTON_PRIMARY;
                int changed = now ^ mouseButtonsDown;
                for (int bit : new int[] { MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY }) {
                    if ((changed & bit) == 0) continue;
                    int button = bit == MotionEvent.BUTTON_PRIMARY ? 1 : bit == MotionEvent.BUTTON_TERTIARY ? 2 : 3;
                    button(button, (now & bit) != 0, x - boxLeft, y - boxTop);
                }
                mouseButtonsDown = now;
                return true;
            }
            default:
                return true;
        }
    }

    // As GameActivity: the on-screen controls hide while a gamepad is connected, on the transition
    // only, so the manual hide toggle is never undone.
    private InputManager inputManager;
    private boolean lastPadConnected;
    private final InputManager.InputDeviceListener deviceListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { refreshGamepadControls(); }
        @Override public void onInputDeviceRemoved(int id) { refreshGamepadControls(); }
        @Override public void onInputDeviceChanged(int id) { refreshGamepadControls(); }
    };

    private void refreshGamepadControls() {
        boolean pad = GamepadHandler.hasConnectedGamepad();
        if (pad == lastPadConnected) return;
        lastPadConnected = pad;
        controls.setControlsHidden(pad);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            startHud();
            if (gamepad != null) gamepad.start();
            if (inputManager == null)
                inputManager = (InputManager) getContext().getSystemService(Context.INPUT_SERVICE);
            if (inputManager != null) inputManager.registerInputDeviceListener(deviceListener, null);
            refreshGamepadControls();
        } else {
            stopHud();
            if (inputManager != null) inputManager.unregisterInputDeviceListener(deviceListener);
            if (gamepad != null) gamepad.stop();
            releasePan();
            controls.resetAll();   // nothing stays held while the game is in the background
        }
    }

    @Override protected void onDetachedFromWindow() {
        stopHud();
        if (inputManager != null) inputManager.unregisterInputDeviceListener(deviceListener);
        if (gamepad != null) gamepad.stop();
        releasePan();
        controls.resetAll();
        if (perfThread != null) perfThread.quitSafely();
        InputSink.setBackend(null);
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ HUD

    /** Classic counter ("FPS: XX", top-left), as in GameActivity. */
    private void addFpsText(Context context) {
        fpsText = new TextView(context);
        fpsText.setText("FPS: --");
        fpsText.setTextColor(0xFF00FF66);                 // green, readable over any scene
        fpsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        fpsText.setShadowLayer(4f, 0f, 0f, 0xFF000000);   // outline so it reads on light scenes
        fpsText.setPadding(0, 0, 0, 0);
        final int m = Math.round(8 * getResources().getDisplayMetrics().density);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins(m, m, 0, 0);
        addView(fpsText, lp);
        // Shift it right by its own width once laid out, so it clears the game's resource readout.
        fpsText.post(() -> {
            LayoutParams p = (LayoutParams) fpsText.getLayoutParams();
            p.leftMargin = m + fpsText.getWidth();
            fpsText.setLayoutParams(p);
        });
    }

    /** Full performance bar, top-centre; the native engine always renders with Vulkan. */
    private void addPerfBar(Context context) {
        perfView = new PerfOverlayView(context, "VK");
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, Math.round(4 * getResources().getDisplayMetrics().density), 0, 0);
        addView(perfView, lp);
        perfSampler = new PerfSampler(context);
        perfThread = new HandlerThread("PerfOverlay");
        perfThread.start();
        perfHandler = new Handler(perfThread.getLooper());
    }

    private static long frameCount() {
        try {
            return NativeInput.frameCount();
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    private final Runnable fpsTextTick = new Runnable() {
        @Override public void run() {
            if (fpsText == null) return;
            long now = SystemClock.elapsedRealtime();
            long count = frameCount();
            if (count < 0) return;
            if (fpsLastTimeMs != 0) {
                long dFrames = count - fpsLastCount;
                long dMs = now - fpsLastTimeMs;
                if (dMs > 0) fpsText.setText("FPS: " + Math.round(dFrames * 1000.0 / dMs));
            }
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

    private void startHud() {
        if (fpsText != null) {
            fpsLastTimeMs = 0;                          // the first interval would be skewed
            ui.removeCallbacks(fpsTextTick);
            ui.postDelayed(fpsTextTick, 1000);
        }
        if (perfHandler != null) {
            perfHandler.removeCallbacks(perfTick);
            perfHandler.post(perfTick);                 // the first sample only primes the deltas
        }
    }

    private void stopHud() {
        ui.removeCallbacks(fpsTextTick);
        if (perfHandler != null) perfHandler.removeCallbacks(perfTick);
    }

    // ------------------------------------------------------------------ soft keyboard

    /**
     * Invisible view that owns the IME connection, a "dummy" editor. Every character goes to the
     * player as its own key press (KeyCharacterMap): Android's own dummy-editor behaviour sends a
     * whole word as one ACTION_MULTIPLE event when the keyboard composes (Gboard and most keyboards
     * with suggestions), and the player drops those — "no text input at all" on a tester's phone
     * while a per-character keyboard (Samsung's) worked. Composing is followed as it goes: added
     * characters are typed, removed ones are Backspaces. Latin only, as before.
     */
    private static final class KeyboardCatcher extends View {
        private boolean accepting;

        KeyboardCatcher(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        /** Show the keyboard if hidden, hide it if shown. Bound to the on-screen TOGGLE_KEYBOARD button. */
        void toggle() {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            accepting = !accepting;
            requestFocus();
            imm.restartInput(this);
            if (accepting) imm.showSoftInput(this, InputMethodManager.SHOW_FORCED);
            else imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }

        @Override public boolean onCheckIsTextEditor() { return accepting; }

        @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            if (!accepting) return null;
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT;
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            return new BaseInputConnection(this, false) {
                private String composing = "";
                private final KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

                private void backspace(int n) {
                    for (int i = 0; i < n; i++) {
                        sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                        sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
                    }
                }

                private void type(String s) {
                    for (int i = 0; i < s.length(); i++) {
                        KeyEvent[] events = map.getEvents(new char[] { s.charAt(i) });
                        if (events == null) continue;   // no key for this character (non-Latin)
                        for (KeyEvent e : events) sendKeyEvent(e);
                    }
                }

                /** The keyboard's current text replaces what it composed before: common prefix kept. */
                private void edit(String text) {
                    int keep = 0;
                    while (keep < composing.length() && keep < text.length() && composing.charAt(keep) == text.charAt(keep)) keep++;
                    backspace(composing.length() - keep);
                    type(text.substring(keep));
                }

                @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                    String s = text == null ? "" : text.toString();
                    edit(s);
                    composing = s;
                    return true;
                }

                @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                    edit(text == null ? "" : text.toString());
                    composing = "";
                    return true;
                }

                @Override public boolean finishComposingText() {
                    composing = "";
                    return true;
                }

                // The dummy editor holds no text, so a delete would remove nothing: send Backspace.
                @Override public boolean deleteSurroundingText(int before, int after) {
                    backspace(before);
                    return true;
                }
            };
        }
    }
}
