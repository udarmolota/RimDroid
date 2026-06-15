package com.rimdroid.input;

/**
 * A single bindable action that a control element can inject into the game via
 * RimDroid's SDL event injection (see GameActivity.native*). This is the RimDroid
 * equivalent of Zomdroid's GLFWBinding, but targets our SDL layer and is MNK-only
 * (mouse + keyboard); there is no gamepad/joystick injection.
 *
 * Each entry carries everything the InputControlsView needs to inject it:
 *   - MOUSE  : code = SDL button number (1=left, 2=middle, 3=right), injected at the cursor.
 *   - SCROLL : code = wheel delta (+1 = zoom in, -1 = zoom out), injected at the cursor on press.
 *   - KEY    : code = SDL scancode, keycode = SDL keysym; text != null for printable keys
 *              (injected as SDL_TEXTINPUT so RimWorld text fields receive the character).
 */
public enum Binding {
    NONE(Kind.NONE, 0, 0, null, "None"),

    // --- Special UI actions (handled by InputControlsView itself, NOT injected into the game) ---
    // Toggle the visibility of all on-screen controls (the bound button stays visible so you can
    // bring them back). Bind it to the default "V" button.
    TOGGLE_CONTROLS(Kind.SPECIAL, 0, 0, null, "Hide/show controls"),

    // --- Mouse buttons (injected at the on-screen cursor) ---
    MOUSE_LEFT(Kind.MOUSE, 1, 0, null, "Left click"),
    MOUSE_MIDDLE(Kind.MOUSE, 2, 0, null, "Middle click"),
    MOUSE_RIGHT(Kind.MOUSE, 3, 0, null, "Right click"),

    // --- Mouse wheel ---
    SCROLL_UP(Kind.SCROLL, 1, 0, null, "Zoom in"),
    SCROLL_DOWN(Kind.SCROLL, -1, 0, null, "Zoom out"),

    // --- Arrow keys (RimWorld camera) ---
    KEY_UP(Kind.KEY, 82, 0x40000052, null, "Arrow Up"),
    KEY_DOWN(Kind.KEY, 81, 0x40000051, null, "Arrow Down"),
    KEY_LEFT(Kind.KEY, 80, 0x40000050, null, "Arrow Left"),
    KEY_RIGHT(Kind.KEY, 79, 0x4000004F, null, "Arrow Right"),

    // --- Common control keys ---
    KEY_SPACE(Kind.KEY, 44, 32, " ", "Space"),
    KEY_ESCAPE(Kind.KEY, 41, 27, null, "Escape"),
    KEY_ENTER(Kind.KEY, 40, 13, null, "Enter"),
    KEY_TAB(Kind.KEY, 43, 9, null, "Tab"),
    KEY_BACKSPACE(Kind.KEY, 42, 8, null, "Backspace"),
    KEY_DELETE(Kind.KEY, 76, 127, null, "Delete"),

    // --- Function keys (scancode SDL_SCANCODE_F1..F12 = 58..69; keycode = scancode | 0x40000000) ---
    KEY_F1(Kind.KEY, 58, 0x4000003A, null, "F1"),
    KEY_F2(Kind.KEY, 59, 0x4000003B, null, "F2"),
    KEY_F3(Kind.KEY, 60, 0x4000003C, null, "F3"),
    KEY_F4(Kind.KEY, 61, 0x4000003D, null, "F4"),
    KEY_F5(Kind.KEY, 62, 0x4000003E, null, "F5"),
    KEY_F6(Kind.KEY, 63, 0x4000003F, null, "F6"),
    KEY_F7(Kind.KEY, 64, 0x40000040, null, "F7"),
    KEY_F8(Kind.KEY, 65, 0x40000041, null, "F8"),
    KEY_F9(Kind.KEY, 66, 0x40000042, null, "F9"),
    KEY_F10(Kind.KEY, 67, 0x40000043, null, "F10"),
    KEY_F11(Kind.KEY, 68, 0x40000044, null, "F11"),
    KEY_F12(Kind.KEY, 69, 0x40000045, null, "F12"),

    // --- Letters (scancode SDL_SCANCODE_A..Z = 4..29; keycode = lowercase ASCII) ---
    KEY_A(Kind.KEY, 4, 97, "a", "A"),
    KEY_B(Kind.KEY, 5, 98, "b", "B"),
    KEY_C(Kind.KEY, 6, 99, "c", "C"),
    KEY_D(Kind.KEY, 7, 100, "d", "D"),
    KEY_E(Kind.KEY, 8, 101, "e", "E"),
    KEY_F(Kind.KEY, 9, 102, "f", "F"),
    KEY_G(Kind.KEY, 10, 103, "g", "G"),
    KEY_H(Kind.KEY, 11, 104, "h", "H"),
    KEY_I(Kind.KEY, 12, 105, "i", "I"),
    KEY_J(Kind.KEY, 13, 106, "j", "J"),
    KEY_K(Kind.KEY, 14, 107, "k", "K"),
    KEY_L(Kind.KEY, 15, 108, "l", "L"),
    KEY_M(Kind.KEY, 16, 109, "m", "M"),
    KEY_N(Kind.KEY, 17, 110, "n", "N"),
    KEY_O(Kind.KEY, 18, 111, "o", "O"),
    KEY_P(Kind.KEY, 19, 112, "p", "P"),
    KEY_Q(Kind.KEY, 20, 113, "q", "Q"),
    KEY_R(Kind.KEY, 21, 114, "r", "R"),
    KEY_S(Kind.KEY, 22, 115, "s", "S"),
    KEY_T(Kind.KEY, 23, 116, "t", "T"),
    KEY_U(Kind.KEY, 24, 117, "u", "U"),
    KEY_V(Kind.KEY, 25, 118, "v", "V"),
    KEY_W(Kind.KEY, 26, 119, "w", "W"),
    KEY_X(Kind.KEY, 27, 120, "x", "X"),
    KEY_Y(Kind.KEY, 28, 121, "y", "Y"),
    KEY_Z(Kind.KEY, 29, 122, "z", "Z"),

    // --- Digits (scancode SDL_SCANCODE_1..9 = 30..38, 0 = 39; keycode = ASCII) ---
    KEY_1(Kind.KEY, 30, 49, "1", "1"),
    KEY_2(Kind.KEY, 31, 50, "2", "2"),
    KEY_3(Kind.KEY, 32, 51, "3", "3"),
    KEY_4(Kind.KEY, 33, 52, "4", "4"),
    KEY_5(Kind.KEY, 34, 53, "5", "5"),
    KEY_6(Kind.KEY, 35, 54, "6", "6"),
    KEY_7(Kind.KEY, 36, 55, "7", "7"),
    KEY_8(Kind.KEY, 37, 56, "8", "8"),
    KEY_9(Kind.KEY, 38, 57, "9", "9"),
    KEY_0(Kind.KEY, 39, 48, "0", "0");

    public enum Kind { NONE, MOUSE, SCROLL, KEY, SPECIAL }

    public final Kind kind;
    public final int code;      // MOUSE: button#, SCROLL: dy, KEY: scancode
    public final int keycode;   // KEY: SDL keysym
    public final String text;   // KEY: printable char for SDL_TEXTINPUT, else null
    public final String label;  // human-readable, shown in the editor

    Binding(Kind kind, int code, int keycode, String text, String label) {
        this.kind = kind;
        this.code = code;
        this.keycode = keycode;
        this.text = text;
        this.label = label;
    }

    @Override public String toString() { return label; }

    public static Binding fromName(String name, Binding fallback) {
        if (name == null) return fallback;
        try { return Binding.valueOf(name); } catch (Exception e) { return fallback; }
    }
}
