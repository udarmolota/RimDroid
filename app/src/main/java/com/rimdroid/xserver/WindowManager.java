package com.rimdroid.xserver;

import android.util.Log;
import android.util.SparseArray;

import com.rimdroid.xserver.util.Bitmask;
import com.rimdroid.xconnector.XInputStream;
import com.rimdroid.xserver.errors.BadIdChoice;
import com.rimdroid.xserver.errors.BadMatch;
import com.rimdroid.xserver.errors.BadValue;
import com.rimdroid.xserver.errors.XRequestError;
import com.rimdroid.xserver.events.ConfigureNotify;
import com.rimdroid.xserver.events.ConfigureRequest;
import com.rimdroid.xserver.events.DestroyNotify;
import com.rimdroid.xserver.events.Event;
import com.rimdroid.xserver.events.Expose;
import com.rimdroid.xserver.events.MapNotify;
import com.rimdroid.xserver.events.MapRequest;
import com.rimdroid.xserver.events.ResizeRequest;
import com.rimdroid.xserver.events.UnmapNotify;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class WindowManager extends XResourceManager {
    private static final int WM_STATE_WITHDRAWN = 0;
    private static final int WM_STATE_NORMAL = 1;
    public enum FocusRevertTo {NONE, POINTER_ROOT, PARENT}
    public final Window rootWindow;
    private final SparseArray<Window> windows = new SparseArray<>();
    public final DrawableManager drawableManager;
    private Window focusedWindow;
    private FocusRevertTo focusRevertTo = FocusRevertTo.NONE;
    private final ArrayList<OnWindowModificationListener> onWindowModificationListeners = new ArrayList<>();

    public interface OnWindowModificationListener {
        default void onMapWindow(Window window) {}

        default void onUnmapWindow(Window window) {}

        default void onChangeWindowZOrder(Window window) {}

        default void onUpdateWindowContent(Window window) {}

        default void onUpdateWindowGeometry(Window window, boolean resized) {}

        default void onUpdateWindowAttributes(Window window, Bitmask mask) {}

        default void onModifyWindowProperty(Window window, Property property) {}
    }

    public WindowManager(ScreenInfo screenInfo, DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
        int id = IDGenerator.generate();
        Drawable drawable = drawableManager.createDrawable(id, screenInfo.width, screenInfo.height, drawableManager.getVisual());
        rootWindow = new Window(id, drawable, 0, 0, screenInfo.width, screenInfo.height, null);
        rootWindow.attributes.setMapped(true);
        windows.put(id, rootWindow);
    }

    public Window getWindow(int id) {
        return windows.get(id);
    }

    public ArrayList<Window> findDialogWindows(int id) {
        ArrayList<Window> result = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) {
            Window window = windows.valueAt(i);
            if (window != null && window.getTransientFor() == id && window.isDialogBox()) result.add(window);
        }
        return result;
    }

    public Window findWindowWithProcessId(int processId) {
        for (int i = 0; i < windows.size(); i++) {
            Window window = windows.valueAt(i);
            if (window != null && window.getProcessId() == processId) return window;
        }
        return null;
    }

    public void destroyWindow(int id) {
        Window window = getWindow(id);
        if (window != null && rootWindow.id != id) {
            unmapWindow(window);
            removeAllSubwindowsAndWindow(window);
        }
    }

    private void removeAllSubwindowsAndWindow(Window window) {
        List<Window> children = new ArrayList<>(window.getChildren());
        for (Window child : children) removeAllSubwindowsAndWindow(child);

        Window parent = window.getParent();
        window.sendEvent(Event.STRUCTURE_NOTIFY, new DestroyNotify(window, window));
        parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new DestroyNotify(parent, window));
        windows.remove(window.id);
        if (window.isInputOutput()) drawableManager.removeDrawable(window.getContent().id);
        triggerOnFreeResourceListener(window);
        if (window == focusedWindow) revertFocus();
        parent.removeChild(window);
    }

    // RimWorld 1.6.4871 stall: Unity's Screen stays 0x0 through startup ("Resolution too small
    // (0x0)") and managed loading never proceeds, while the X window/ZFA drawable is a correct
    // 1685x778. SDL2 only refreshes its cached window size from ConfigureNotify, and our
    // same-geometry notify suppression (brief v12, configureWindow above) can starve it at the
    // exact moment Unity applies the prefs resolution. Kick: re-deliver the window's CURRENT
    // geometry as ConfigureNotify a few seconds after mapping — past the fragile splash frame —
    // so SDL/Unity resync. A real X server may emit such notifies at any time; this is legal.
    private final java.util.Set<Integer> configureKicked = new java.util.HashSet<>();

    private void scheduleConfigureKick(final Window window) {
        if (window == rootWindow || !window.isInputOutput()) return;
        if (window.getParent() != rootWindow) return;                 // top-level windows only
        synchronized (configureKicked) {
            if (!configureKicked.add(window.id)) return;              // once per window
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 2; i++) {
                    try { Thread.sleep(i == 0 ? 5000 : 12000); } catch (InterruptedException e) { return; }
                    Window w = getWindow(window.id);
                    if (w == null || !w.attributes.isMapped()) return;
                    android.util.Log.i("RimDroid/XServer", "ConfigureNotify KICK #" + (i + 1)
                            + " win=0x" + Integer.toHexString(w.id)
                            + " " + w.getWidth() + "x" + w.getHeight());
                    w.sendEvent(Event.STRUCTURE_NOTIFY, new ConfigureNotify(w, w, w.previousSibling(),
                            w.getX(), w.getY(), w.getWidth(), w.getHeight(),
                            w.getBorderWidth(), w.attributes.isOverrideRedirect()));
                }
            }
        }, "rd-configure-kick");
        t.setDaemon(true);
        t.start();
    }

    public void mapWindow(Window window) {
        android.util.Log.i("RimDroid/XServer", "MAPWIN win=0x" + Integer.toHexString(window.id)
                + " wasMapped=" + window.attributes.isMapped()
                + " hasStructNotify=" + window.hasEventListenerFor(Event.STRUCTURE_NOTIFY));
        if (!window.attributes.isMapped()) {
            Window parent = window.getParent();
            if (!parent.hasEventListenerFor(Event.SUBSTRUCTURE_REDIRECT) || window.attributes.isOverrideRedirect()) {
                window.attributes.setMapped(true);
                updateWmState(window, WM_STATE_NORMAL);
                window.sendEvent(Event.STRUCTURE_NOTIFY, new MapNotify(window, window));
                parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new MapNotify(parent, window));
                // RimDroid: a real X server also reports visibility after mapping — SDL2 tracks it.
                window.sendEvent(Event.VISIBILITY_CHANGE, new com.rimdroid.xserver.events.VisibilityNotify(window));
                window.sendEvent(Event.EXPOSURE, new Expose(window));
                scheduleConfigureKick(window);
                triggerOnMapWindow(window);
            }
            else parent.sendEvent(Event.SUBSTRUCTURE_REDIRECT, new MapRequest(parent, window));
        }
    }

    public void unmapWindow(Window window) {
        android.util.Log.i("RimDroid/XServer", "UNMAPWIN win=0x" + Integer.toHexString(window.id)
                + " wasMapped=" + window.attributes.isMapped() + " (caller=Unity XUnmapWindow request)");
        if (rootWindow.id != window.id && window.attributes.isMapped()) {
            window.attributes.setMapped(false);
            Window parent = window.getParent();
            // NOTE: deliberately NOT setting WithdrawnState — SDL reads WM_STATE on
            // PropertyNotify and can latch HIDDEN mid-dance (present-gate!). Keep NormalState.
            window.sendEvent(Event.STRUCTURE_NOTIFY, new UnmapNotify(window, window));
            parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new UnmapNotify(parent, window));
            if (window == focusedWindow) revertFocus();
            triggerOnUnmapWindow(window);
        }
    }

    public void mapSubWindows(Window window) {
        for (Window child : window.getChildren()) mapSubWindows(child);
        mapWindow(window);
    }

    public Window getFocusedWindow() {
        return focusedWindow;
    }

    public void revertFocus() {
        switch (focusRevertTo) {
            case NONE:
                focusedWindow = null;
                break;
            case POINTER_ROOT:
                focusedWindow = rootWindow;
                break;
            case PARENT:
                if (focusedWindow.getParent() != null) focusedWindow = focusedWindow.getParent();
                break;
        }
    }

    public void setFocus(Window focusedWindow, FocusRevertTo focusRevertTo) {
        // RimDroid: deliver focus events — SDL2 waits for FocusIn after XSetInputFocus to mark
        // its window SDL_WINDOW_INPUT_FOCUS (the Winlator port had no focus events at all).
        Window old = this.focusedWindow;
        this.focusedWindow = focusedWindow;
        this.focusRevertTo = focusRevertTo;
        if (old == focusedWindow) return;
        if (old != null && old != rootWindow)
            old.sendEvent(Event.FOCUS_CHANGE, new com.rimdroid.xserver.events.FocusOut(old));
        if (focusedWindow != null && focusedWindow != rootWindow)
            focusedWindow.sendEvent(Event.FOCUS_CHANGE, new com.rimdroid.xserver.events.FocusIn(focusedWindow));
    }

    public FocusRevertTo getFocusRevertTo() {
        return focusRevertTo;
    }

    public Window createWindow(int id, Window parent, short x, short y, short width, short height, WindowAttributes.WindowClass windowClass, Visual visual, byte depth, XClient client) throws XRequestError {
        if (windows.indexOfKey(id) >= 0) throw new BadIdChoice(id);

        boolean isInputOutput = false;
        switch (windowClass) {
            case COPY_FROM_PARENT:
                depth = (depth != 0 || !parent.isInputOutput()) ? depth : parent.getContent().visual.depth;
                isInputOutput = parent.isInputOutput();
                break;
            case INPUT_OUTPUT:
                if (parent.isInputOutput()) {
                    depth = depth == 0 ? parent.getContent().visual.depth : depth;
                    isInputOutput = true;
                } else throw new BadMatch();
                break;
            case INPUT_ONLY:
                isInputOutput = false;
                break;
        }

        if (isInputOutput) {
            visual = visual == null ? parent.getContent().visual : visual;
            if (depth != visual.depth) throw new BadMatch();
        }

        Drawable drawable = null;
        if (isInputOutput) {
            drawable = drawableManager.createDrawable(id, width, height, visual);
            if (drawable == null) throw new BadIdChoice(id);
        }

        final Window window = new Window(id, drawable, x, y, width, height, client);
        window.attributes.setWindowClass(windowClass);
        if (drawable != null) drawable.setOnDrawListener(() -> triggerOnUpdateWindowContent(window));
        windows.put(id, window);
        parent.addChild(window);
        triggerOnCreateResourceListener(window);
        return window;
    }

    private void changeWindowGeometry(Window window, short x, short y, short width, short height) {
        boolean resized = window.getWidth() != width || window.getHeight() != height;
        if (resized && window.hasEventListenerFor(Event.RESIZE_REDIRECT)) {
            window.sendEvent(Event.SUBSTRUCTURE_REDIRECT, new ResizeRequest(window, width, height));
            width = window.getWidth();
            height = window.getHeight();
            resized = false;
        }

        if (resized && window.isInputOutput()) {
            Drawable oldContent = window.getContent();
            drawableManager.removeDrawable(oldContent.id);
            Drawable newContent = drawableManager.createDrawable(oldContent.id, width, height, oldContent.visual);
            newContent.setOffscreenStorage(oldContent.isOffscreenStorage());
            newContent.setOnDrawListener(() -> triggerOnUpdateWindowContent(window));
            window.setContent(newContent);
        }

        if (resized || window.getX() != x || window.getY() != y) {
            window.setX(x);
            window.setY(y);
            window.setWidth(width);
            window.setHeight(height);
            triggerOnUpdateWindowGeometry(window, resized);
        }

        if (resized && window.isInputOutput() && window.attributes.isMapped()) {
            window.sendEvent(new Expose(window));
        }
    }

    private void changeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        Window parent = window.getParent();
        switch (stackMode) {
            case ABOVE:
                parent.moveChildAbove(window, sibling);
                break;
            case BELOW:
                parent.moveChildBelow(window, sibling);
                break;
        }
        triggerOnChangeWindowZOrder(window);
    }

    public void configureWindow(Window window, Bitmask valueMask, XInputStream inputStream) throws XRequestError {
        short x = window.getX();
        short y = window.getY();
        short width = window.getWidth();
        short height = window.getHeight();
        short borderWidth = window.getBorderWidth();
        Window sibling = null;
        Window.StackMode stackMode = null;

        for (int index : valueMask) {
            switch (index) {
                case Window.FLAG_X:
                    x = (short)inputStream.readInt();
                    break;
                case Window.FLAG_Y:
                    y = (short)inputStream.readInt();
                    break;
                case Window.FLAG_WIDTH:
                    width = (short)inputStream.readInt();
                    break;
                case Window.FLAG_HEIGHT:
                    height = (short)inputStream.readInt();
                    break;
                case Window.FLAG_BORDER_WIDTH:
                    borderWidth = (short)inputStream.readInt();
                    break;
                case Window.FLAG_SIBLING:
                    sibling = getWindow(inputStream.readInt());
                    break;
                case Window.FLAG_STACK_MODE:
                    stackMode = Window.StackMode.values()[inputStream.readInt()];
                    break;
            }
        }

        if (width <= 0) throw new BadValue(width);
        if (height <= 0) throw new BadValue(height);

        Window parent = window.getParent();
        boolean overrideRedirect = window.attributes.isOverrideRedirect();
        if (!parent.hasEventListenerFor(Event.SUBSTRUCTURE_REDIRECT) || overrideRedirect) {
            // RimDroid (RimWorld 1.6 / brief v12): suppress NO-OP ConfigureNotify. Unity's startup
            // resolution-apply sends a same-geometry ConfigureWindow; even a same-size notify can
            // push SDL/GL down a drawable-invalidation path (Zink/kopper swapchain churn is a
            // device-lost suspect at the splash-unload frame). X11 semantics allow coalescing.
            boolean unchanged = (x == window.getX() && y == window.getY()
                    && width == window.getWidth() && height == window.getHeight());
            changeWindowGeometry(window, x, y, width, height);

            window.setBorderWidth(borderWidth);
            if (stackMode != null) changeWindowZOrder(stackMode, window, sibling);

            if (unchanged) {
                android.util.Log.i("RimDroid/XServer", "ConfigureWindow no-op (same geometry) win=0x"
                        + Integer.toHexString(window.id) + " " + width + "x" + height + " — notify suppressed");
            } else {
                Window previousSibling = window.previousSibling();
                window.sendEvent(Event.STRUCTURE_NOTIFY, new ConfigureNotify(window, window, previousSibling, x, y, width, height, borderWidth, overrideRedirect));
                parent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new ConfigureNotify(parent, window, previousSibling, x, y, width, height, borderWidth, overrideRedirect));
            }
        }
        else parent.sendEvent(Event.SUBSTRUCTURE_REDIRECT, new ConfigureRequest(parent, window, window.previousSibling(), x, y, width, height, borderWidth, stackMode, valueMask));
    }

    public void reparentWindow(Window window, Window newParent) {
        // RimDroid: full XReparentWindow semantics — a MAPPED window is unmapped, reparented, then
        // re-mapped, with Unmap/Reparent/MapNotify in that order. SDL 2.0.22's legacy-fullscreen
        // path (X11_BeginWindowFullscreenLegacy) reparents its mapped main window into an
        // override-redirect fswindow and then BLOCKS in XIfEvent for the fresh MapNotify — without
        // the re-map notify the Unity 1.6 player hangs forever right here. (Credit: Codex analysis.)
        Window oldParent = window.getParent();
        boolean wasMapped = window.attributes.isMapped();
        android.util.Log.i("RimDroid/XServer", "reparent win=0x" + Integer.toHexString(window.id)
                + " -> parent=0x" + Integer.toHexString(newParent.id) + " mapped=" + wasMapped);
        if (wasMapped) {
            // NOTE: deliberately NOT setting WithdrawnState — SDL reads WM_STATE on
            // PropertyNotify and can latch HIDDEN mid-dance (present-gate!). Keep NormalState.
            window.sendEvent(Event.STRUCTURE_NOTIFY, new UnmapNotify(window, window));
            if (oldParent != null)
                oldParent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new UnmapNotify(oldParent, window));
        }
        if (oldParent != null) oldParent.removeChild(window);
        newParent.addChild(window);
        window.sendEvent(Event.STRUCTURE_NOTIFY,
                new com.rimdroid.xserver.events.ReparentNotify(window, window, newParent));
        newParent.sendEvent(Event.SUBSTRUCTURE_NOTIFY,
                new com.rimdroid.xserver.events.ReparentNotify(newParent, window, newParent));
        if (oldParent != null && oldParent != newParent)
            oldParent.sendEvent(Event.SUBSTRUCTURE_NOTIFY,
                    new com.rimdroid.xserver.events.ReparentNotify(oldParent, window, newParent));
        if (wasMapped) {
            updateWmState(window, WM_STATE_NORMAL);
            window.sendEvent(Event.STRUCTURE_NOTIFY, new MapNotify(window, window));
            newParent.sendEvent(Event.SUBSTRUCTURE_NOTIFY, new MapNotify(newParent, window));
            // Like a real server after re-map: report visibility + damage, otherwise SDL can keep
            // the window flagged hidden/occluded and Unity never starts presenting (renders the
            // whole load offscreen until Vulkan OOM). See rimworld_16_port session 5.
            window.sendEvent(Event.VISIBILITY_CHANGE, new com.rimdroid.xserver.events.VisibilityNotify(window));
            window.sendEvent(Event.EXPOSURE, new Expose(window));
        }
    }

    public Window findPointWindow(short rootX, short rootY) {
        return findPointWindow(rootWindow, rootX, rootY, false);
    }

    public Window findPointWindow(short rootX, short rootY, boolean useFullscreenTransformation) {
        return findPointWindow(rootWindow, rootX, rootY, useFullscreenTransformation);
    }

    private Window findPointWindow(Window window, short rootX, short rootY, boolean useFullscreenTransformation) {
        if (!(window.attributes.isMapped() && window.containsPoint(rootX, rootY, useFullscreenTransformation))) return null;
        Window child = window.getChildByCoords(rootX, rootY, useFullscreenTransformation);
        return child != null ? findPointWindow(child, rootX, rootY, useFullscreenTransformation) : window;
    }

    public void addOnWindowModificationListener(OnWindowModificationListener onWindowModificationListener) {
        onWindowModificationListeners.add(onWindowModificationListener);
    }

    public void removeOnWindowModificationListener(OnWindowModificationListener onWindowModificationListener) {
        onWindowModificationListeners.remove(onWindowModificationListener);
    }

    public void triggerOnMapWindow(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onMapWindow(window);
        }
    }

    public void triggerOnUnmapWindow(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUnmapWindow(window);
        }
    }

    public void triggerOnChangeWindowZOrder(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onChangeWindowZOrder(window);
        }
    }

    public void triggerOnUpdateWindowContent(Window window) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUpdateWindowContent(window);
        }
    }

    public void triggerOnUpdateWindowGeometry(Window window, boolean resized) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUpdateWindowGeometry(window, resized);
        }
    }

    public void triggerOnUpdateWindowAttributes(Window window, Bitmask mask) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onUpdateWindowAttributes(window, mask);
        }
    }

    public void triggerOnModifyWindowProperty(Window window, Property property) {
        for (int i = onWindowModificationListeners.size()-1; i >= 0; i--) {
            onWindowModificationListeners.get(i).onModifyWindowProperty(window, property);
        }
    }

    private void updateWmState(Window window, int state) {
        int wmState = Atom.internAtom("WM_STATE");
        byte[] data = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(state)
                .putInt(0)
                .array();
        Property property = window.modifyProperty(
                wmState, wmState, Property.Format.INT_ARRAY, Property.Mode.REPLACE, data);
        if (property != null) {
            triggerOnModifyWindowProperty(window, property);
            Log.i("RimDroid/XServer", "WM_STATE win=0x" + Integer.toHexString(window.id)
                    + " -> " + (state == WM_STATE_NORMAL ? "NormalState" : "WithdrawnState"));
        }
    }
}
