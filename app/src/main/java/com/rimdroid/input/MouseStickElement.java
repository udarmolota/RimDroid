package com.rimdroid.input;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Relative cursor pad: dragging moves the shared cursor (drawn by the view); a quick
 * tap on the pad = left-click at the cursor. Sensitivity scales finger delta -> cursor
 * delta. Ported from the original InputOverlayView mouse-stick.
 */
public class MouseStickElement extends ControlElement {

    private static final float OUTER_DP = 47f;   // outer ring radius
    private static final float KNOB_DP  = 40f;   // big inner knob (fills most of the ring)
    private static final float MIN_SENS = 0.25f, MAX_SENS = 8.0f;
    private static final float TAP_MOVE_DP = 12f;
    private static final long  TAP_MS = 250;

    private float sensitivity;

    private int pointerId = -1;
    private float lastX, lastY, downX, downY;
    private long downT;
    private boolean moved;
    private float knobX, knobY; // visual thumb, relative offset applied in draw

    private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MouseStickElement(InputControlsView view, ControlElementDescription d) {
        super(view, d);
        this.sensitivity = clamp(d.sensitivity, MIN_SENS, MAX_SENS);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
    }

    private float outerR() { return dp(OUTER_DP) * scale; }
    private float knobR()  { return dp(KNOB_DP) * scale; }

    @Override public boolean isPointOver(float x, float y) {
        float dx = x - centerX(), dy = y - centerY(); float r = outerR();
        return dx * dx + dy * dy <= r * r;
    }

    @Override public boolean handleTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int pid = e.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                // Claim ANY down landing on the stick — even if we still "own" a stale pointerId whose
                // UP never arrived (an in-game menu can swallow it). Re-claim only when the owned pointer
                // is a GHOST (not present in this event), so we never steal a genuinely active finger.
                // Without this the stick stays "transparent" after a menu and the touch falls through to
                // the map-pan gesture (the "camera drifts when I press the stick" bug). Covers POINTER_DOWN
                // too (a second finger while another is held), which the DOWN-only clearStalePointer missed.
                if (isPointOver(e.getX(idx), e.getY(idx))
                        && (pointerId < 0 || e.findPointerIndex(pointerId) < 0)) {
                    pointerId = pid;
                    lastX = downX = e.getX(idx); lastY = downY = e.getY(idx);
                    downT = e.getEventTime(); moved = false;
                    clampKnob(lastX, lastY);
                    view.invalidate();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (pointerId < 0) return false;
                int i = e.findPointerIndex(pointerId);
                if (i < 0) return true;
                float x = e.getX(i), y = e.getY(i);
                view.moveCursorBy((x - lastX) * sensitivity, (y - lastY) * sensitivity);
                lastX = x; lastY = y;
                if (Math.abs(x - downX) + Math.abs(y - downY) > dp(TAP_MOVE_DP)) moved = true;
                clampKnob(x, y);
                view.invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = (action == MotionEvent.ACTION_CANCEL);
                if (cancel || pid == pointerId) {
                    if (!cancel && !moved && e.getEventTime() - downT < TAP_MS) {
                        view.tapCursor(Binding.MOUSE_LEFT);
                    }
                    pointerId = -1; knobX = knobY = 0; view.invalidate();
                    return !cancel;
                }
                return false;
            }
        }
        return false;
    }

    private void clampKnob(float x, float y) {
        float dx = x - centerX(), dy = y - centerY();
        float max = outerR() - knobR();
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > max && len > 0.001f) { float k = max / len; dx *= k; dy *= k; }
        knobX = dx; knobY = dy;
    }

    @Override public void reset() { pointerId = -1; knobX = knobY = 0; }

    @Override public void draw(Canvas c) {
        float cx = centerX(), cy = centerY();
        fill.setColor(0x00FFFFFF | ((int)(alpha * 0.30f) << 24));
        stroke.setColor(0x00FFFFFF | (Math.min(255, alpha + 30) << 24));
        stroke.setStrokeWidth(dp(2));
        if (highlighted) { stroke.setColor(0xFF33C0FF); stroke.setStrokeWidth(dp(3)); }
        c.drawCircle(cx, cy, outerR(), fill);
        c.drawCircle(cx, cy, outerR(), stroke);
        c.drawCircle(cx + knobX, cy + knobY, knobR(), fill);
        c.drawCircle(cx + knobX, cy + knobY, knobR(), stroke);
    }

    @Override public ControlElementDescription describe() {
        ControlElementDescription d = new ControlElementDescription();
        d.type = "MOUSE_STICK";
        d.sensitivity = sensitivity;
        d.bindings = new String[0];
        return baseDescribe(d);
    }

    @Override public String editorLabel() { return "Mouse-stick (cursor)"; }

    public float getSensitivity() { return sensitivity; }
    public void setSensitivity(float s) { sensitivity = clamp(s, MIN_SENS, MAX_SENS); }
}
