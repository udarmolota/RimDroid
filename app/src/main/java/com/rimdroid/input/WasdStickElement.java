package com.rimdroid.input;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Directional stick that emits up to four key bindings (default arrow keys = RimWorld
 * camera). Tilt past a deadzone presses the matching direction(s). Ported from the
 * original InputOverlayView wasd-stick; directions are now rebindable.
 *
 * bindings index order: [0]=up, [1]=right, [2]=down, [3]=left.
 */
public class WasdStickElement extends ControlElement {

    private static final float OUTER_DP = 47f;
    private static final float KNOB_DP  = 23f;
    private static final float DEAD = 0.30f;

    private final Binding[] dirs = new Binding[4]; // up,right,down,left
    private final boolean[] pressed = new boolean[4];

    private int pointerId = -1;
    private float knobX, knobY;

    private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WasdStickElement(InputControlsView view, ControlElementDescription d) {
        super(view, d);
        Binding[] def = { Binding.KEY_UP, Binding.KEY_RIGHT, Binding.KEY_DOWN, Binding.KEY_LEFT };
        for (int i = 0; i < 4; i++) {
            dirs[i] = (d.bindings != null && d.bindings.length > i)
                    ? Binding.fromName(d.bindings[i], def[i]) : def[i];
        }
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
                if (pointerId < 0 && isPointOver(e.getX(idx), e.getY(idx))) {
                    pointerId = pid;
                    update(e.getX(idx), e.getY(idx));
                    view.invalidate();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (pointerId < 0) return false;
                int i = e.findPointerIndex(pointerId);
                if (i >= 0) { update(e.getX(i), e.getY(i)); view.invalidate(); }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = (action == MotionEvent.ACTION_CANCEL);
                if (cancel || pid == pointerId) {
                    releaseAll(); pointerId = -1; knobX = knobY = 0; view.invalidate();
                    return !cancel;
                }
                return false;
            }
        }
        return false;
    }

    private void update(float x, float y) {
        float dx = x - centerX(), dy = y - centerY();
        float max = outerR() - knobR();
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > max && len > 0.001f) { float k = max / len; dx *= k; dy *= k; }
        knobX = dx; knobY = dy;
        float nx = max > 0 ? dx / max : 0, ny = max > 0 ? dy / max : 0;
        setDir(0, ny < -DEAD);  // up
        setDir(1, nx >  DEAD);  // right
        setDir(2, ny >  DEAD);  // down
        setDir(3, nx < -DEAD);  // left
    }

    private void setDir(int i, boolean want) {
        if (want == pressed[i]) return;
        pressed[i] = want;
        view.inject(dirs[i], want);
    }

    private void releaseAll() { for (int i = 0; i < 4; i++) setDir(i, false); }

    @Override public void reset() { releaseAll(); pointerId = -1; knobX = knobY = 0; }

    @Override public void draw(Canvas c) {
        float cx = centerX(), cy = centerY();
        fill.setColor(0x00FFFFFF | ((int)(alpha * 0.20f) << 24));
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
        d.type = "WASD_STICK";
        d.bindings = new String[]{ dirs[0].name(), dirs[1].name(), dirs[2].name(), dirs[3].name() };
        return baseDescribe(d);
    }

    @Override public String editorLabel() { return "Camera-stick (keys)"; }

    public Binding getDir(int i) { return dirs[i]; }
    public void setDir(int i, Binding b) { if (i >= 0 && i < 4) { setDir(i, false); dirs[i] = b; } }
}
