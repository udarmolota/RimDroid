package com.rimdroid.input;

/**
 * Serializable snapshot of one on-screen control element (Gson <-> JSON). A saved
 * layout is just a JSON array of these. Mirrors Zomdroid's ControlElementDescription,
 * trimmed to RimDroid's needs (MNK only, three element types).
 *
 * Coordinates are RELATIVE to the overlay (0..1) so a layout survives resolution /
 * orientation changes. Sizes are a multiplier (scale) on each element's base size.
 */
public class ControlElementDescription {

    public String  type;            // "BUTTON" | "MOUSE_STICK" | "WASD_STICK"
    public String  shape;           // BUTTON only: "RECT" | "CIRCLE"
    public float   centerXRelative; // 0..1
    public float   centerYRelative; // 0..1
    public float   scale;           // 0.5..2.0
    public int     alpha;           // 0..255
    public String  text;            // BUTTON label (may be null)

    /**
     * Binding names (see {@link Binding}). Semantics per type:
     *   BUTTON      -> bindings[0] is the action.
     *   WASD_STICK  -> bindings = [up, right, down, left].
     *   MOUSE_STICK -> empty (fixed: relative cursor + tap = left click).
     */
    public String[] bindings;

    public boolean isToggle;        // BUTTON: hold (false) vs toggle (true)
    public float   sensitivity;     // MOUSE_STICK cursor speed (0.25..8.0)

    public ControlElementDescription() {
        // defaults for Gson / fields missing in older JSON
        this.shape = "CIRCLE";   // circular buttons are more compact (save screen space)
        this.scale = 1f;
        this.alpha = 180;
        this.bindings = new String[0];
        this.isToggle = false;
        this.sensitivity = 2.0f;
    }

    public static ControlElementDescription button(String text, Binding binding, String shape,
                                                   float xRel, float yRel) {
        ControlElementDescription d = new ControlElementDescription();
        d.type = "BUTTON";
        d.shape = shape;
        d.text = text;
        d.bindings = new String[]{ binding.name() };
        d.centerXRelative = xRel;
        d.centerYRelative = yRel;
        return d;
    }

    public static ControlElementDescription mouseStick(float xRel, float yRel) {
        ControlElementDescription d = new ControlElementDescription();
        d.type = "MOUSE_STICK";
        d.centerXRelative = xRel;
        d.centerYRelative = yRel;
        return d;
    }

    public static ControlElementDescription wasdStick(Binding up, Binding right,
                                                      Binding down, Binding left,
                                                      float xRel, float yRel) {
        ControlElementDescription d = new ControlElementDescription();
        d.type = "WASD_STICK";
        d.bindings = new String[]{ up.name(), right.name(), down.name(), left.name() };
        d.centerXRelative = xRel;
        d.centerYRelative = yRel;
        return d;
    }
}
