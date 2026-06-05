package com.rimdroid.input;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * A tappable on-screen button bound to a single {@link Binding} (mouse button, scroll,
 * or key). Hold mode (default): injects press on touch-down, release on touch-up.
 * Toggle mode: first tap latches the binding pressed, second tap releases it.
 * Mouse-button bindings act at the shared cursor (set by {@link MouseStickElement}).
 */
public class ButtonElement extends ControlElement {

    public enum Shape { RECT, CIRCLE }

    private static final float RECT_W_DP = 120f, RECT_H_DP = 66f;
    private static final float CIRCLE_D_DP = 92f;

    private Shape shape;
    private String text;
    private boolean isToggle;
    private Binding binding;

    private int pointerId = -1;
    private boolean toggledOn = false;

    private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public ButtonElement(InputControlsView view, ControlElementDescription d) {
        super(view, d);
        this.shape = "CIRCLE".equalsIgnoreCase(d.shape) ? Shape.CIRCLE : Shape.RECT;
        this.text = d.text != null ? d.text : "";
        this.isToggle = d.isToggle;
        this.binding = (d.bindings != null && d.bindings.length > 0)
                ? Binding.fromName(d.bindings[0], Binding.MOUSE_LEFT) : Binding.MOUSE_LEFT;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float halfW() { return dp((shape == Shape.RECT ? RECT_W_DP : CIRCLE_D_DP) / 2f) * scale; }
    private float halfH() { return dp((shape == Shape.RECT ? RECT_H_DP : CIRCLE_D_DP) / 2f) * scale; }

    // Touch-slop padding around the visible button: a finger landing just outside still counts as a
    // hit, so the overlay claims the touch (instead of it falling through to the map-pan gesture,
    // which made buttons feel hard to press). Affects hit-testing only, not drawing.
    private static final float TOUCH_SLOP_DP = 12f;

    @Override public boolean isPointOver(float x, float y) {
        float cx = centerX(), cy = centerY();
        float slop = dp(TOUCH_SLOP_DP);
        if (shape == Shape.CIRCLE) {
            float r = halfW() + slop; float dx = x - cx, dy = y - cy;
            return dx * dx + dy * dy <= r * r;
        }
        return Math.abs(x - cx) <= halfW() + slop && Math.abs(y - cy) <= halfH() + slop;
    }

    @Override public boolean handleTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int pid = e.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointerId < 0 && isPointOver(e.getX(idx), e.getY(idx))) {
                    pointerId = pid;
                    if (isToggle) {
                        toggledOn = !toggledOn;
                        view.inject(binding, toggledOn);
                    } else {
                        view.inject(binding, true);
                    }
                    view.invalidate();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = (action == MotionEvent.ACTION_CANCEL);
                if (cancel || pid == pointerId) {
                    if (!isToggle) view.inject(binding, false);
                    pointerId = -1;
                    view.invalidate();
                    return !cancel;
                }
                return false;
            }
        }
        return false;
    }

    @Override public void reset() {
        if (pointerId >= 0 && !isToggle) view.inject(binding, false);
        if (isToggle && toggledOn) { view.inject(binding, false); toggledOn = false; }
        pointerId = -1;
    }

    @Override public void draw(Canvas c) {
        float cx = centerX(), cy = centerY(), hw = halfW(), hh = halfH();
        boolean active = (pointerId >= 0) || (isToggle && toggledOn);
        int baseA = alpha;
        fill.setColor(0x00FFFFFF | ((active ? Math.min(255, baseA + 60) : (int)(baseA * 0.35f)) << 24));
        stroke.setColor(0x00FFFFFF | (Math.min(255, baseA + 40) << 24));
        stroke.setStrokeWidth(dp(2));
        if (highlighted) { stroke.setColor(0xFF33C0FF); stroke.setStrokeWidth(dp(3)); }

        if (shape == Shape.CIRCLE) {
            c.drawCircle(cx, cy, hw, fill);
            c.drawCircle(cx, cy, hw, stroke);
        } else {
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
            float r = dp(10) * scale;
            c.drawRoundRect(rect, r, r, fill);
            c.drawRoundRect(rect, r, r, stroke);
        }
        if (!text.isEmpty()) {
            textPaint.setColor(0x00FFFFFF | (Math.min(255, baseA + 75) << 24));
            float ts = Math.min(hh, hw) * 0.7f;
            textPaint.setTextSize(ts);
            // Shrink to fit the button width so multi-char labels (e.g. "RBC") stay inside.
            float maxW = (shape == Shape.CIRCLE ? hw * 1.4f : hw * 1.7f);
            float tw = textPaint.measureText(text);
            if (tw > maxW && tw > 0) { ts *= maxW / tw; textPaint.setTextSize(ts); }
            float ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
            c.drawText(text, cx, ty, textPaint);
        }
    }

    @Override public ControlElementDescription describe() {
        ControlElementDescription d = new ControlElementDescription();
        d.type = "BUTTON";
        d.shape = shape.name();
        d.text = text;
        d.isToggle = isToggle;
        d.bindings = new String[]{ binding.name() };
        return baseDescribe(d);
    }

    @Override public String editorLabel() { return "Button: " + (text.isEmpty() ? binding.label : text); }

    // --- editor setters ---
    public Binding getBinding() { return binding; }
    public void setBinding(Binding b) { reset(); this.binding = b; }
    public boolean isToggle() { return isToggle; }
    public void setToggle(boolean t) { reset(); this.isToggle = t; }
    public String getText() { return text; }
    public void setText(String t) { this.text = t != null ? t : ""; }
    public Shape getShape() { return shape; }
    public void setShape(Shape s) { this.shape = s; }
}
