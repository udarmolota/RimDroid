package com.rimdroid.input;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.rimdroid.GameActivity;

/**
 * Physical keyboard + mouse support. RimWorld is a native mouse+keyboard (SDL) game, so hardware
 * kb/mouse map 1:1 onto RimDroid's existing SDL injection (the same {@link GameActivity}.native*
 * calls and {@link InputControlsView} cursor used by the on-screen controls and the gamepad).
 *
 *   - Mouse move/hover  -> set the on-screen cursor (absolute), which injects SDL motion.
 *   - Mouse drag (button held) -> arrives as TOUCH events with TOOL_TYPE_MOUSE; we move the cursor
 *     and swallow them so they don't pan the map.
 *   - Mouse buttons      -> SDL button 1/2/3 at the cursor (via Binding.MOUSE_*).
 *   - Mouse wheel        -> SDL scroll (Binding.SCROLL_*).
 *   - Keyboard           -> Android keyCode mapped to an SDL scancode + keysym (full layout), plus
 *     SDL_TEXTINPUT for printable characters so RimWorld text fields (rename colonist/save) work.
 *
 * The injection layer is shared with the gamepad ({@link GamepadHandler}); this class is the
 * MNK-from-real-hardware front end. Returns true from the on* methods when it consumed the event,
 * so {@link GameActivity} can stop the system treating it as focus/back navigation or a map pan.
 */
public class MouseKeyboardHandler {

    private final InputControlsView controls;

    public MouseKeyboardHandler(InputControlsView controls) {
        this.controls = controls;
    }

    // ============================== mouse ==============================

    private static boolean isMouse(MotionEvent e) {
        return e.isFromSource(InputDevice.SOURCE_MOUSE)
            || e.isFromSource(InputDevice.SOURCE_TOUCHPAD)
            || (e.getPointerCount() > 0 && e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE);
    }

    /** Generic-motion path: hover-move, wheel, and button press/release. */
    public boolean onGenericMotion(MotionEvent e) {
        if (!isMouse(e)) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_MOVE:
                controls.moveCursorTo(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_SCROLL: {
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (v == 0) v = e.getAxisValue(MotionEvent.AXIS_WHEEL);
                if (v != 0) controls.inject(v > 0 ? Binding.SCROLL_UP : Binding.SCROLL_DOWN, true);
                return true;
            }
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE: {
                boolean pressed = e.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS;
                controls.moveCursorTo(e.getX(), e.getY());
                Binding b = bindingForButton(e.getActionButton());
                if (b != null) controls.inject(b, pressed);
                return true;
            }
        }
        return false;
    }

    /** Touch path for a mouse: a press+drag streams as TOUCH events (TOOL_TYPE_MOUSE). Keep the cursor
     *  following and SWALLOW them so the on-screen controls don't pan the map. Buttons come via the
     *  generic-motion ACTION_BUTTON_* path above. @return true if this was a mouse touch we consumed. */
    public boolean onTouch(MotionEvent e) {
        if (!isMouse(e)) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                controls.moveCursorTo(e.getX(), e.getY());
                break;
            default:
                break;
        }
        return true;   // consume all mouse touches (no map-pan from a mouse)
    }

    private static Binding bindingForButton(int actionButton) {
        switch (actionButton) {
            case MotionEvent.BUTTON_PRIMARY:   return Binding.MOUSE_LEFT;
            case MotionEvent.BUTTON_SECONDARY: return Binding.MOUSE_RIGHT;
            case MotionEvent.BUTTON_TERTIARY:  return Binding.MOUSE_MIDDLE;
            default:                           return null;
        }
    }

    // ============================== keyboard ==============================

    /** @return true if this came from a physical keyboard and we injected it. */
    public boolean onKey(KeyEvent e) {
        final int src = e.getSource();
        // Gamepads are handled upstream; never treat their buttons as keyboard keys.
        if ((src & InputDevice.SOURCE_GAMEPAD)  == InputDevice.SOURCE_GAMEPAD
         || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;

        final int kc = e.getKeyCode();
        final int sc = scancode(kc);
        // Inject any key we have an SDL scancode for (not a gamepad). Unmapped keys (volume/back/etc.) fall
        // through to the system. We intentionally do NOT gate on KEYBOARD_TYPE/SOURCE_KEYBOARD — combo
        // keyboard+touchpad devices were being mis-detected and dropped.
        if (sc == 0) return false;

        final boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // Text char: only Shift/Alt affect the produced character (matches Zomdroid; avoids Ctrl+key → control char).
        final int meta = e.getMetaState() & (KeyEvent.META_SHIFT_ON | KeyEvent.META_ALT_ON);
        // Auto-repeat: re-emit text for held printable keys, but do not re-send the key-down edge.
        if (down && e.getRepeatCount() > 0) {
            int rep = e.getUnicodeChar(meta);
            if (rep >= 32 && rep != 127) GameActivity.nativeText(String.valueOf((char) rep));
            return true;
        }

        try {
            GameActivity.nativeKey(sc, keysym(kc, sc, e), down ? 1 : 0);
            if (down) {
                int uni = e.getUnicodeChar(meta);
                if (uni >= 32 && uni != 127) GameActivity.nativeText(String.valueOf((char) uni));
            }
        } catch (UnsatisfiedLinkError ignored) {}
        return true;
    }

    /** SDL keysym (SDLK): ASCII for printable/control keys, else scancode | 0x40000000. */
    private static int keysym(int kc, int sc, KeyEvent e) {
        switch (kc) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return 13;
            case KeyEvent.KEYCODE_ESCAPE:    return 27;
            case KeyEvent.KEYCODE_TAB:       return 9;
            case KeyEvent.KEYCODE_DEL:       return 8;   // backspace
            case KeyEvent.KEYCODE_SPACE:     return 32;
        }
        int base = e.getUnicodeChar(0);   // unmodified base character
        if (base > 0 && base < 128) return Character.toLowerCase(base);
        return sc | 0x40000000;
    }

    /** Android keyCode -> SDL scancode (0 = unmapped). */
    private static int scancode(int kc) {
        // Letters A..Z  (Android 29..54 -> SDL 4..29)
        if (kc >= KeyEvent.KEYCODE_A && kc <= KeyEvent.KEYCODE_Z) return 4 + (kc - KeyEvent.KEYCODE_A);
        // Top-row digits 1..9,0  (Android 8..16 -> SDL 30..38, 0 -> 39)
        if (kc >= KeyEvent.KEYCODE_1 && kc <= KeyEvent.KEYCODE_9) return 30 + (kc - KeyEvent.KEYCODE_1);
        if (kc == KeyEvent.KEYCODE_0) return 39;
        // Function keys F1..F12  (Android 131..142 -> SDL 58..69)
        if (kc >= KeyEvent.KEYCODE_F1 && kc <= KeyEvent.KEYCODE_F12) return 58 + (kc - KeyEvent.KEYCODE_F1);
        switch (kc) {
            case KeyEvent.KEYCODE_ENTER:          return 40;
            case KeyEvent.KEYCODE_NUMPAD_ENTER:   return 88;
            case KeyEvent.KEYCODE_ESCAPE:         return 41;
            case KeyEvent.KEYCODE_DEL:            return 42;   // backspace
            case KeyEvent.KEYCODE_TAB:            return 43;
            case KeyEvent.KEYCODE_SPACE:          return 44;
            case KeyEvent.KEYCODE_MINUS:          return 45;
            case KeyEvent.KEYCODE_EQUALS:         return 46;
            case KeyEvent.KEYCODE_LEFT_BRACKET:   return 47;
            case KeyEvent.KEYCODE_RIGHT_BRACKET:  return 48;
            case KeyEvent.KEYCODE_BACKSLASH:      return 49;
            case KeyEvent.KEYCODE_SEMICOLON:      return 51;
            case KeyEvent.KEYCODE_APOSTROPHE:     return 52;
            case KeyEvent.KEYCODE_GRAVE:          return 53;
            case KeyEvent.KEYCODE_COMMA:          return 54;
            case KeyEvent.KEYCODE_PERIOD:         return 55;
            case KeyEvent.KEYCODE_SLASH:          return 56;
            case KeyEvent.KEYCODE_CAPS_LOCK:      return 57;
            // arrows
            case KeyEvent.KEYCODE_DPAD_RIGHT:     return 79;
            case KeyEvent.KEYCODE_DPAD_LEFT:      return 80;
            case KeyEvent.KEYCODE_DPAD_DOWN:      return 81;
            case KeyEvent.KEYCODE_DPAD_UP:        return 82;
            // nav cluster
            case KeyEvent.KEYCODE_INSERT:         return 73;
            case KeyEvent.KEYCODE_MOVE_HOME:      return 74;
            case KeyEvent.KEYCODE_PAGE_UP:        return 75;
            case KeyEvent.KEYCODE_FORWARD_DEL:    return 76;   // delete
            case KeyEvent.KEYCODE_MOVE_END:       return 77;
            case KeyEvent.KEYCODE_PAGE_DOWN:      return 78;
            // modifiers
            case KeyEvent.KEYCODE_CTRL_LEFT:      return 224;
            case KeyEvent.KEYCODE_SHIFT_LEFT:     return 225;
            case KeyEvent.KEYCODE_ALT_LEFT:       return 226;
            case KeyEvent.KEYCODE_CTRL_RIGHT:     return 228;
            case KeyEvent.KEYCODE_SHIFT_RIGHT:    return 229;
            case KeyEvent.KEYCODE_ALT_RIGHT:      return 230;
            default:                              return 0;
        }
    }
}
