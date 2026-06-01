package com.rimdroid.input;

import android.graphics.Canvas;
import android.view.MotionEvent;

/**
 * Base class for an on-screen control element. Subclasses: {@link ButtonElement},
 * {@link MouseStickElement}, {@link WasdStickElement}.
 *
 * Position is stored relative (0..1) to the parent {@link InputControlsView}; size is a
 * scale multiplier on the subclass's base size. Each element handles its own touch in
 * play mode and renders itself; the editor manipulates it via the geometry/scale/alpha
 * setters and serializes it via {@link #describe()}.
 */
public abstract class ControlElement {

    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 2.0f;

    protected final InputControlsView view;
    protected float centerXRel, centerYRel;   // 0..1
    protected float scale;
    protected int alpha;                       // 0..255
    protected boolean highlighted;             // editor selection outline

    protected ControlElement(InputControlsView view, ControlElementDescription d) {
        this.view = view;
        this.centerXRel = d.centerXRelative;
        this.centerYRel = d.centerYRelative;
        this.scale = clamp(d.scale, MIN_SCALE, MAX_SCALE);
        this.alpha = clampInt(d.alpha, 0, 255);
    }

    // --- geometry (screen px) ---
    public float centerX() { return centerXRel * view.getWidth(); }
    public float centerY() { return centerYRel * view.getHeight(); }

    public void setCenterPx(float x, float y) {
        int w = Math.max(1, view.getWidth()), h = Math.max(1, view.getHeight());
        centerXRel = clamp(x / w, 0.02f, 0.98f);
        centerYRel = clamp(y / h, 0.02f, 0.98f);
    }
    public void moveCenterPx(float dx, float dy) { setCenterPx(centerX() + dx, centerY() + dy); }

    // --- size / opacity ---
    public float getScale() { return scale; }
    public void  setScale(float s) { scale = clamp(s, MIN_SCALE, MAX_SCALE); }
    public int   getAlpha() { return alpha; }
    public void  setAlpha(int a) { alpha = clampInt(a, 0, 255); }
    public void  setHighlighted(boolean h) { highlighted = h; }

    /** dp -> px helper using the parent view's density. */
    protected float dp(float v) { return v * view.density(); }

    // --- contract ---
    public abstract void draw(Canvas c);
    public abstract boolean isPointOver(float x, float y);
    /** Play-mode touch. Return true if this element consumed the event. */
    public abstract boolean handleTouch(MotionEvent e);
    /** Release any held input + reset visual state (called when leaving play mode). */
    public abstract void reset();
    public abstract ControlElementDescription describe();
    /** Short human label for the editor "selected: ..." line. */
    public abstract String editorLabel();

    protected ControlElementDescription baseDescribe(ControlElementDescription d) {
        d.centerXRelative = centerXRel;
        d.centerYRelative = centerYRel;
        d.scale = scale;
        d.alpha = alpha;
        return d;
    }

    protected static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    protected static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
