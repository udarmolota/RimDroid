package com.rimdroid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/**
 * Full-screen transparent input overlay (Zomdroid-style), adapted to RimDroid's
 * SDL injection (GameActivity.native*). Two joysticks:
 *   - mouse-stick (right): relative cursor + tap = left-click at cursor;
 *   - wasd-stick  (left):  tilt → arrow keys (RimWorld camera).
 * Draws a visible cursor arrow. Touches NOT on a stick are NOT consumed (return
 * false) so they reach the Activity (pinch-zoom). RMB is driven from the Activity
 * via cursorButton(3, ...).
 */
public class InputOverlayView extends View {

    private final float renderScale;
    private final float density;
    private static final float SENS = 2.0f;
    private static final float DEAD = 0.30f;

    // cursor (screen px); injected as cur*renderScale
    private float curX = -1, curY = -1;

    private float stickR, knobR;
    // Mouse-stick uses a bigger inner knob so the touch target nearly fills the
    // outer ring (easier to grab); the wasd-stick keeps the smaller knobR.
    private float msKnobR;

    // mouse stick (right)
    private float msCx, msCy, msKnobX, msKnobY;
    private int msPointer = -1;
    private float msLastX, msLastY, msDownX, msDownY;
    private long msDownT;
    private boolean msMoved;

    // wasd stick (left) → arrow keys
    private float wsCx, wsCy, wsKnobX, wsKnobY;
    private int wsPointer = -1;
    private boolean kUp, kDown, kLeft, kRight;
    private static final int SC_UP = 82, SC_DOWN = 81, SC_LEFT = 80, SC_RIGHT = 79;
    private static final int KC_UP = 0x40000052, KC_DOWN = 0x40000051, KC_LEFT = 0x40000050, KC_RIGHT = 0x4000004F;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public InputOverlayView(Context c, float renderScale) {
        super(c);
        this.renderScale = renderScale;
        this.density = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(2 * density); stroke.setColor(0x4AFFFFFF);
        fill.setStyle(Paint.Style.FILL); fill.setColor(0x0EFFFFFF);
        curFill.setStyle(Paint.Style.FILL); curFill.setColor(0xFFFFFFFF); curFill.setAlpha(150);
        curStroke.setStyle(Paint.Style.STROKE); curStroke.setStrokeWidth(1.5f * density); curStroke.setColor(0xC0000000);
        setFocusable(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        stickR = 47 * density;
        knobR  = 23 * density;
        msKnobR = 40 * density;   // mouse-stick inner circle (fills most of the outer ring)
        wsCx = 0.16f * w; wsCy = 0.74f * h; wsKnobX = wsCx; wsKnobY = wsCy;
        msCx = 0.84f * w; msCy = 0.74f * h; msKnobX = msCx; msKnobY = msCy;
        if (curX < 0) { curX = w / 2f; curY = h / 2f; }
    }

    private boolean inCircle(float x, float y, float cx, float cy) {
        float dx = x - cx, dy = y - cy; return dx * dx + dy * dy <= stickR * stickR;
    }

    private float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private void moveCursor(float dx, float dy) {
        curX = clamp(curX + dx, 0, getWidth() - 1);
        curY = clamp(curY + dy, 0, getHeight() - 1);
        try { GameActivity.nativeTouch(0, (int) (curX * renderScale), (int) (curY * renderScale)); } catch (UnsatisfiedLinkError ignored) {}
    }

    private void clickCursor(int button) {
        final int gx = (int) (curX * renderScale), gy = (int) (curY * renderScale);
        try {
            GameActivity.nativeButton(button, 1, gx, gy);
            postDelayed(() -> { try { GameActivity.nativeButton(button, 0, gx, gy); } catch (UnsatisfiedLinkError ignored) {} }, 50);
        } catch (UnsatisfiedLinkError ignored) {}
    }

    /** RMB (and others) from the Activity button: hold down/up at the cursor. */
    public void cursorButton(int button, boolean down) {
        final int gx = (int) (curX * renderScale), gy = (int) (curY * renderScale);
        try { GameActivity.nativeButton(button, down ? 1 : 0, gx, gy); } catch (UnsatisfiedLinkError ignored) {}
    }

    private void clampKnob(float x, float y, boolean mouse) {
        float cx = mouse ? msCx : wsCx, cy = mouse ? msCy : wsCy;
        float dx = x - cx, dy = y - cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float max = stickR - (mouse ? msKnobR : knobR);
        if (len > max && len > 0.001f) { float k = max / len; dx *= k; dy *= k; }
        if (mouse) { msKnobX = cx + dx; msKnobY = cy + dy; }
        else       { wsKnobX = cx + dx; wsKnobY = cy + dy; }
    }

    private void dispatchWasd() {
        float max = stickR - knobR;
        float nx = (wsKnobX - wsCx) / max, ny = (wsKnobY - wsCy) / max;
        setKey(nx < -DEAD, 'L'); setKey(nx > DEAD, 'R');
        setKey(ny < -DEAD, 'U'); setKey(ny > DEAD, 'D');
    }
    private void setKey(boolean want, char dir) {
        boolean cur; int sc, kc;
        switch (dir) {
            case 'U': cur = kUp;    sc = SC_UP;    kc = KC_UP;    break;
            case 'D': cur = kDown;  sc = SC_DOWN;  kc = KC_DOWN;  break;
            case 'L': cur = kLeft;  sc = SC_LEFT;  kc = KC_LEFT;  break;
            default:  cur = kRight; sc = SC_RIGHT; kc = KC_RIGHT; break;
        }
        if (want == cur) return;
        try { GameActivity.nativeKey(sc, kc, want ? 1 : 0); } catch (UnsatisfiedLinkError ignored) {}
        switch (dir) { case 'U': kUp = want; break; case 'D': kDown = want; break; case 'L': kLeft = want; break; default: kRight = want; }
    }
    private void releaseWasd() { setKey(false, 'U'); setKey(false, 'D'); setKey(false, 'L'); setKey(false, 'R'); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int pid = e.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(idx), y = e.getY(idx);
                if (msPointer < 0 && inCircle(x, y, msCx, msCy)) {
                    msPointer = pid; msLastX = x; msLastY = y; msDownX = x; msDownY = y;
                    msDownT = System.currentTimeMillis(); msMoved = false; clampKnob(x, y, true); invalidate(); return true;
                }
                if (wsPointer < 0 && inCircle(x, y, wsCx, wsCy)) {
                    wsPointer = pid; clampKnob(x, y, false); dispatchWasd(); invalidate(); return true;
                }
                return (msPointer >= 0 || wsPointer >= 0); // consume only if a stick is active; else let game/pinch have it
            }
            case MotionEvent.ACTION_MOVE: {
                boolean handled = false;
                if (msPointer >= 0) {
                    int i = e.findPointerIndex(msPointer);
                    if (i >= 0) {
                        float x = e.getX(i), y = e.getY(i);
                        float dx = (x - msLastX) * SENS, dy = (y - msLastY) * SENS;
                        msLastX = x; msLastY = y;
                        if (Math.abs(x - msDownX) + Math.abs(y - msDownY) > 12 * density) msMoved = true;
                        moveCursor(dx, dy); clampKnob(x, y, true); handled = true;
                    }
                }
                if (wsPointer >= 0) {
                    int i = e.findPointerIndex(wsPointer);
                    if (i >= 0) { clampKnob(e.getX(i), e.getY(i), false); dispatchWasd(); handled = true; }
                }
                if (handled) invalidate();
                return handled || msPointer >= 0 || wsPointer >= 0;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = (action == MotionEvent.ACTION_CANCEL);
                if (cancel || pid == msPointer) {
                    if (msPointer >= 0 && !msMoved && System.currentTimeMillis() - msDownT < 250) clickCursor(1);
                    msPointer = -1; msKnobX = msCx; msKnobY = msCy; invalidate();
                }
                if (cancel || pid == wsPointer) {
                    wsPointer = -1; wsKnobX = wsCx; wsKnobY = wsCy; releaseWasd(); invalidate();
                }
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas c) {
        // sticks
        drawStick(c, wsCx, wsCy, wsKnobX, wsKnobY, knobR);
        drawStick(c, msCx, msCy, msKnobX, msKnobY, msKnobR);
        // cursor arrow
        if (curX >= 0) {
            float s = density, w = 17 * s, h = 25 * s;
            Path p = new Path();
            p.moveTo(curX, curY);
            p.lineTo(curX + w, curY + h * 0.85f);
            p.lineTo(curX + w * 0.55f, curY + h - w * 0.28f);
            p.lineTo(curX + w * 0.15f, curY + h);
            p.close();
            c.drawPath(p, curStroke);
            c.drawPath(p, curFill);
        }
    }
    private void drawStick(Canvas c, float cx, float cy, float kx, float ky, float kr) {
        c.drawCircle(cx, cy, stickR, fill);
        c.drawCircle(cx, cy, stickR, stroke);
        c.drawCircle(kx, ky, kr, fill);
        c.drawCircle(kx, ky, kr, stroke);
    }
}
