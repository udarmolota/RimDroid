package com.rimdroid.input;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rimdroid.GameActivity;
import com.rimdroid.LauncherPreferences;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Container overlay holding modular {@link ControlElement}s. In PLAY mode it routes
 * multi-touch to the elements and injects SDL events at the shared cursor; in EDIT mode
 * (used by ControlsEditorActivity) it lets you tap-select and drag elements. The layout
 * is JSON-serialized (Gson) to prefs, with a bundled default in assets.
 */
public class InputControlsView extends View {

    private static final String TAG = "RimDroid/Controls";
    public static final String DEFAULT_ASSET = "default_controls.json";

    private final List<ControlElement> elements = new ArrayList<>();
    private final float density;
    private float renderScale = 0.72f;

    // shared cursor (screen px); injected as cur * renderScale
    private float curX = -1, curY = -1;

    // edit mode
    private boolean editMode = false;
    private ControlElement selected;
    private ControlElement dragging;
    private float lastTouchX, lastTouchY;
    private float editDownX, editDownY;
    private long editDownTime;
    private boolean editMoved;
    // Hold an element this long (without dragging) to open its settings panel. A light tap
    // just selects/moves it — so quick drags don't pop the panel.
    private static final long EDIT_PANEL_TAP_MS = 350;
    private EditorListener editorListener;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Paint curFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Gson GSON = new Gson();

    public interface EditorListener {
        /** el == null means selection cleared (tapped empty space). */
        void onElementSelected(ControlElement el);
    }

    public InputControlsView(Context c, float renderScale) {
        super(c);
        this.renderScale = renderScale;
        this.density = getResources().getDisplayMetrics().density;
        curFill.setStyle(Paint.Style.FILL); curFill.setColor(0xFFFFFFFF); curFill.setAlpha(150);
        curStroke.setStyle(Paint.Style.STROKE); curStroke.setStrokeWidth(1.5f * density); curStroke.setColor(0xC0000000);
        setFocusable(false);
        loadFromPrefsOrDefault();
    }

    public float density() { return density; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (curX < 0) { curX = w / 2f; curY = h / 2f; }
    }

    // ====================== injection (called by elements) =====================

    private int gx() { return (int) (curX * renderScale); }
    private int gy() { return (int) (curY * renderScale); }

    public void moveCursorBy(float dx, float dy) {
        curX = Math.max(0, Math.min(getWidth()  - 1, curX + dx));
        curY = Math.max(0, Math.min(getHeight() - 1, curY + dy));
        try { GameActivity.nativeTouch(0, gx(), gy()); } catch (UnsatisfiedLinkError ignored) {}
    }

    /** Inject a binding press/release. Mouse buttons act at the cursor. */
    public void inject(Binding b, boolean pressed) {
        if (b == null) return;
        try {
            switch (b.kind) {
                case MOUSE:  GameActivity.nativeButton(b.code, pressed ? 1 : 0, gx(), gy()); break;
                case SCROLL: if (pressed) GameActivity.nativeScroll(gx(), gy(), b.code); break;
                case KEY:
                    GameActivity.nativeKey(b.code, b.keycode, pressed ? 1 : 0);
                    if (pressed && b.text != null) GameActivity.nativeText(b.text);
                    break;
                case NONE: default: break;
            }
        } catch (UnsatisfiedLinkError ignored) {}
    }

    /** Quick press+release of a binding at the cursor (used by mouse-stick tap). */
    public void tapCursor(Binding b) {
        inject(b, true);
        ui.postDelayed(() -> inject(b, false), 50);
    }

    // ============================ touch routing ===============================

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (editMode) { handleEditTouch(e); return true; }

        int a = e.getActionMasked();
        boolean down = (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN);
        boolean consumed = false;
        // Broadcast; on DOWN the first element to claim the pointer wins.
        for (ControlElement el : elements) {
            if (el.handleTouch(e)) { consumed = true; if (down) break; }
        }
        return consumed; // false -> Activity gets it (pinch-zoom / direct tap)
    }

    /** Release any held inputs (call when leaving the game / pausing). */
    public void resetAll() { for (ControlElement el : elements) el.reset(); }

    // ============================== drawing ===================================

    @Override protected void onDraw(Canvas c) {
        for (ControlElement el : elements) el.draw(c);
        if (!editMode && curX >= 0) drawCursor(c);
    }

    private void drawCursor(Canvas c) {
        float s = density, w = 17 * s / 1.5f, h = 25 * s / 1.5f;   // 1.5x smaller cursor
        Path p = new Path();
        p.moveTo(curX, curY);
        p.lineTo(curX + w, curY + h * 0.85f);
        p.lineTo(curX + w * 0.55f, curY + h - w * 0.28f);
        p.lineTo(curX + w * 0.15f, curY + h);
        p.close();
        c.drawPath(p, curStroke);
        c.drawPath(p, curFill);
    }

    // ============================== edit mode =================================

    /** Editor: set every element's opacity to the given alpha (0-255), then redraw.
     *  Used by the "Opacity → all" button so the whole layout can match one element. */
    public void applyAlphaToAll(int alpha) {
        for (ControlElement el : elements) el.setAlpha(alpha);
        invalidate();
    }

    public void setEditMode(boolean edit, EditorListener listener) {
        this.editMode = edit;
        this.editorListener = listener;
        if (edit) resetAll();
        else { selected = null; dragging = null; }
        invalidate();
    }

    public boolean isEditMode() { return editMode; }
    public ControlElement getSelected() { return selected; }
    public List<ControlElement> getElements() { return elements; }

    private void handleEditTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = e.getX(), y = e.getY();
                lastTouchX = x; lastTouchY = y;
                editDownX = x; editDownY = y;
                editDownTime = System.currentTimeMillis();
                editMoved = false;
                ControlElement hit = pickAt(x, y);
                if (hit != null) {
                    selectQuiet(hit);   // highlight for dragging — do NOT open the panel yet
                    dragging = hit;
                } else {
                    select(null);       // tap on empty space clears selection + closes the panel
                    dragging = null;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging != null) {
                    if (Math.abs(e.getX() - editDownX) + Math.abs(e.getY() - editDownY) > 10 * density)
                        editMoved = true;
                    dragging.moveCenterPx(e.getX() - lastTouchX, e.getY() - lastTouchY);
                    lastTouchX = e.getX(); lastTouchY = e.getY();
                    invalidate();
                }
                break;
            }
            case MotionEvent.ACTION_UP:
                // Open the settings panel only on a deliberate, slightly longer tap that didn't
                // drag — so a light touch just moves the element instead of popping the panel.
                if (dragging != null && !editMoved && selected != null
                        && System.currentTimeMillis() - editDownTime >= EDIT_PANEL_TAP_MS
                        && editorListener != null) {
                    editorListener.onElementSelected(selected);
                }
                dragging = null;
                break;
            case MotionEvent.ACTION_CANCEL:
                dragging = null;
                break;
        }
    }

    /** Select + highlight an element WITHOUT opening the editor panel (used on touch-down so
     *  dragging doesn't pop the panel; the panel opens on a deliberate hold, see handleEditTouch). */
    private void selectQuiet(ControlElement el) {
        if (selected != null) selected.setHighlighted(false);
        selected = el;
        if (selected != null) selected.setHighlighted(true);
        invalidate();
    }

    private ControlElement pickAt(float x, float y) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            if (elements.get(i).isPointOver(x, y)) return elements.get(i);
        }
        return null;
    }

    private void select(ControlElement el) {
        if (selected != null) selected.setHighlighted(false);
        selected = el;
        if (selected != null) selected.setHighlighted(true);
        if (editorListener != null) editorListener.onElementSelected(el);
        invalidate();
    }

    public void addElement(ControlElementDescription d) {
        ControlElement el = create(d);
        if (el == null) return;
        elements.add(el);
        select(el);
        invalidate();
    }

    public void removeSelected() {
        if (selected == null) return;
        selected.reset();
        elements.remove(selected);
        select(null);
        invalidate();
    }

    // ============================ persistence =================================

    private ControlElement create(ControlElementDescription d) {
        if (d == null || d.type == null) return null;
        switch (d.type) {
            case "BUTTON":      return new ButtonElement(this, d);
            case "MOUSE_STICK": return new MouseStickElement(this, d);
            case "WASD_STICK":  return new WasdStickElement(this, d);
            default: Log.w(TAG, "unknown element type: " + d.type); return null;
        }
    }

    private void applyDescriptions(List<ControlElementDescription> list) {
        for (ControlElement el : elements) el.reset();
        elements.clear();
        if (list != null) for (ControlElementDescription d : list) {
            ControlElement el = create(d);
            if (el != null) elements.add(el);
        }
        select(null);
        invalidate();
    }

    private static List<ControlElementDescription> parse(String json) {
        try {
            return GSON.fromJson(json, new TypeToken<ArrayList<ControlElementDescription>>(){}.getType());
        } catch (Exception ex) { Log.w(TAG, "parse layout failed: " + ex.getMessage()); return null; }
    }

    public void loadFromPrefsOrDefault() {
        String json = null;
        LauncherPreferences lp = LauncherPreferences.getSingleton();
        if (lp != null) json = lp.getControlsJson();
        List<ControlElementDescription> list = (json != null) ? parse(json) : null;
        if (list == null || list.isEmpty()) list = readDefaultAsset();
        applyDescriptions(list);
    }

    public void loadDefault() {
        applyDescriptions(readDefaultAsset());
    }

    private List<ControlElementDescription> readDefaultAsset() {
        try (InputStream is = getContext().getAssets().open(DEFAULT_ASSET)) {
            byte[] buf = new byte[is.available()];
            int n = is.read(buf);
            String json = new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);
            List<ControlElementDescription> list = parse(json);
            if (list != null) return list;
        } catch (Exception ex) { Log.e(TAG, "default asset read failed: " + ex.getMessage()); }
        return new ArrayList<>();
    }

    public String toJson() {
        List<ControlElementDescription> list = new ArrayList<>();
        for (ControlElement el : elements) list.add(el.describe());
        return GSON.toJson(list);
    }

    public void saveToPrefs() {
        LauncherPreferences lp = LauncherPreferences.getSingleton();
        if (lp != null) lp.setControlsJson(toJson());
    }
}
