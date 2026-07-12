package com.rimdroid.xserver;

import com.rimdroid.xserver.util.Bitmask;
import com.rimdroid.xserver.render.FullscreenTransformation;
import com.rimdroid.xserver.events.ButtonPress;
import com.rimdroid.xserver.events.ButtonRelease;
import com.rimdroid.xserver.events.EnterNotify;
import com.rimdroid.xserver.events.Event;
import com.rimdroid.xserver.events.KeyPress;
import com.rimdroid.xserver.events.KeyRelease;
import com.rimdroid.xserver.events.LeaveNotify;
import com.rimdroid.xserver.events.MappingNotify;
import com.rimdroid.xserver.events.MotionNotify;
import com.rimdroid.xserver.events.PointerWindowEvent;

public class InputDeviceManager implements Pointer.OnPointerMotionListener, Keyboard.OnKeyboardListener, WindowManager.OnWindowModificationListener, XResourceManager.OnResourceLifecycleListener {
    private static final byte MOUSE_WHEEL_DELTA = 120;
    private Window pointWindow;
    private final XServer xServer;

    public InputDeviceManager(XServer xServer) {
        this.xServer = xServer;
        pointWindow = xServer.windowManager.rootWindow;
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.windowManager.addOnResourceLifecycleListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
        xServer.keyboard.addOnKeyboardListener(this);
    }

    @Override
    public void onMapWindow(Window window) {
        updatePointWindow();
    }

    @Override
    public void onUnmapWindow(Window window) {
        updatePointWindow();
    }

    @Override
    public void onChangeWindowZOrder(Window window) {
        updatePointWindow();
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        updatePointWindow();
    }

    @Override
    public void onCreateResource(XResource resource) {
        updatePointWindow();
    }

    @Override
    public void onFreeResource(XResource resource) {
        updatePointWindow();
    }

    private void updatePointWindow() {
        Window pointWindow = xServer.windowManager.findPointWindow(xServer.pointer.getClampedX(), xServer.pointer.getClampedY(), true);
        this.pointWindow = pointWindow != null ? pointWindow : xServer.windowManager.rootWindow;
    }

    public Window getPointWindow() {
        return pointWindow;
    }

    private void sendEvent(Window window, int eventId, Event event) {
        Window grabWindow = xServer.grabManager.getWindow();
        if (grabWindow != null && grabWindow.attributes.isEnabled()) {
            EventListener eventListener = xServer.grabManager.getEventListener();
            if (xServer.grabManager.isOwnerEvents() && window != null) {
                window.sendEvent(eventId, event, xServer.grabManager.getClient());
            }
            else if (eventListener.isInterestedIn(eventId)) {
                eventListener.sendEvent(event);
            }
        }
        else if (window != null && window.attributes.isEnabled()) {
            window.sendEvent(eventId, event);
        }
    }

    private void sendEvent(Window window, Bitmask eventMask, Event event) {
        Window grabWindow = xServer.grabManager.getWindow();
        if (grabWindow != null && grabWindow.attributes.isEnabled()) {
            EventListener eventListener = xServer.grabManager.getEventListener();
            if (xServer.grabManager.isOwnerEvents() && window != null) {
                window.sendEvent(eventMask, event, eventListener.client);
            }
            else if (eventListener.isInterestedIn(eventMask)) {
                eventListener.sendEvent(event);
            }
        }
        else if (window != null && window.attributes.isEnabled()) {
            window.sendEvent(eventMask, event);
        }
    }

    public void sendEnterLeaveNotify(Window windowA, Window windowB, PointerWindowEvent.Mode mode) {
        if (windowA == windowB) return;

        boolean sameScreenAndFocus = windowB.isAncestorOf(xServer.windowManager.getFocusedWindow());
        PointerWindowEvent.Detail detailA = PointerWindowEvent.Detail.NONLINEAR;
        PointerWindowEvent.Detail detailB = PointerWindowEvent.Detail.NONLINEAR;

        if (windowA.isAncestorOf(windowB)) {
            detailA = PointerWindowEvent.Detail.ANCESTOR;
            detailB = PointerWindowEvent.Detail.INFERIOR;
        }
        else if (windowB.isAncestorOf(windowA)) {
            detailB = PointerWindowEvent.Detail.ANCESTOR;
            detailA = PointerWindowEvent.Detail.INFERIOR;
        }

        Bitmask keyButMask = getKeyButMask();

        short xA = xServer.pointer.getX();
        short yA = xServer.pointer.getY();
        FullscreenTransformation fullscreenTransformationB = windowB.getFullscreenTransformation();
        if (fullscreenTransformationB != null) {
            short[] transformedPoint = fullscreenTransformationB.transformPointerCoords(xA, yA);
            xA = transformedPoint[0];
            yA = transformedPoint[1];
        }
        short[] localPointA = windowA.rootPointToLocal(xA, yA);

        short xB = xServer.pointer.getX();
        short yB = xServer.pointer.getY();
        FullscreenTransformation fullscreenTransformationA = windowA.getFullscreenTransformation();
        if (fullscreenTransformationA != null) {
            short[] transformedPoint = fullscreenTransformationA.transformPointerCoords(xB, yB);
            xB = transformedPoint[0];
            yB = transformedPoint[1];
        }
        short[] localPointB = windowB.rootPointToLocal(xB, yB);

        sendEvent(windowA, Event.LEAVE_WINDOW, new LeaveNotify(detailA, xServer.windowManager.rootWindow, windowA, null, xA, yA, localPointA[0], localPointA[1], keyButMask, mode, sameScreenAndFocus));
        sendEvent(windowB, Event.ENTER_WINDOW, new EnterNotify(detailB, xServer.windowManager.rootWindow, windowB, null, xB, yB, localPointB[0], localPointB[1], keyButMask, mode, sameScreenAndFocus));
    }

    @Override
    public void onPointerButtonPress(Pointer.Button button) {
        // RimDroid: relative-mouse mode forwarded events to Wine's WinHandler in Winlator;
        // we have no Wine side — X clients get events only through the normal X path below.
        if (!xServer.isRelativeMouseMovement()) {
            Window grabWindow = xServer.grabManager.getWindow();
            if (grabWindow == null) {
                grabWindow = pointWindow.getAncestorWithEventId(Event.BUTTON_PRESS);
                if (grabWindow != null) xServer.grabManager.activatePointerGrab(grabWindow);
            }

            if (grabWindow != null && grabWindow.attributes.isEnabled()) {
                Bitmask eventMask = createPointerEventMask();
                eventMask.unset(button.flag());

                short x = xServer.pointer.getX();
                short y = xServer.pointer.getY();

                FullscreenTransformation fullscreenTransformation = grabWindow.getFullscreenTransformation();
                if (fullscreenTransformation != null) {
                    short[] transformedPoint = fullscreenTransformation.transformPointerCoords(x, y);
                    x = transformedPoint[0];
                    y = transformedPoint[1];
                }

                short[] localPoint = grabWindow.rootPointToLocal(x, y);
                Window child = grabWindow.isAncestorOf(pointWindow) ? pointWindow : null;
                // Scroll diagnostics: zoom (buttons 4/5) reportedly does nothing in RimWorld 1.6
                // while left/right clicks work — log whether the wheel events even hit the wire.
                if (button.code() >= 4) {
                    android.util.Log.i("RimDroid/XServer",
                            "wheel ButtonPress code=" + button.code() + " @" + x + "," + y);
                }
                grabWindow.sendEvent(Event.BUTTON_PRESS, new ButtonPress(button.code(), xServer.windowManager.rootWindow, grabWindow, child, x, y, localPoint[0], localPoint[1], eventMask));
            }
        }
    }

    @Override
    public void onPointerButtonRelease(Pointer.Button button) {
        // RimDroid: no Wine WinHandler — see onPointerButtonPress.
        if (!xServer.isRelativeMouseMovement()) {
            Bitmask eventMask = createPointerEventMask();
            Window grabWindow = xServer.grabManager.getWindow();
            Window window = grabWindow == null || xServer.grabManager.isOwnerEvents() ? pointWindow.getAncestorWithEventMask(eventMask) : null;

            if (grabWindow != null || window != null) {
                Window eventWindow = window != null ? window : grabWindow;

                short x = xServer.pointer.getX();
                short y = xServer.pointer.getY();

                FullscreenTransformation fullscreenTransformation = eventWindow.getFullscreenTransformation();
                if (fullscreenTransformation != null) {
                    short[] transformedPoint = fullscreenTransformation.transformPointerCoords(x, y);
                    x = transformedPoint[0];
                    y = transformedPoint[1];
                }

                short[] localPoint = eventWindow.rootPointToLocal(x, y);
                Window child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
                ButtonRelease buttonRelease = new ButtonRelease(button.code(), xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], eventMask);
                sendEvent(window, eventMask, buttonRelease);
            }

            if (xServer.pointer.getButtonMask().isEmpty() && xServer.grabManager.isReleaseWithButtons()) {
                xServer.grabManager.deactivatePointerGrab();
            }
        }
    }

    @Override
    public void onPointerMove(short x, short y) {
        updatePointWindow();
        Bitmask eventMask = createPointerEventMask();
        Window grabWindow = xServer.grabManager.getWindow();
        Window window = grabWindow == null || xServer.grabManager.isOwnerEvents() ? pointWindow.getAncestorWithEventMask(eventMask) : null;

        if (grabWindow != null || window != null) {
            Window eventWindow = window != null ? window : grabWindow;

            FullscreenTransformation fullscreenTransformation = eventWindow.getFullscreenTransformation();
            if (fullscreenTransformation != null) {
                short[] transformedPoint = fullscreenTransformation.transformPointerCoords(x, y);
                x = transformedPoint[0];
                y = transformedPoint[1];
            }

            short[] localPoint = eventWindow.rootPointToLocal(x, y);
            Window child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
            sendEvent(window, eventMask, new MotionNotify(false, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], getKeyButMask()));
        }
    }

    @Override
    public void onKeyPress(byte keycode, int keysym) {
        Window focusedWindow = xServer.windowManager.getFocusedWindow();
        if (focusedWindow == null) return;
        updatePointWindow();

        Window eventWindow = null;
        Window child = null;
        if (focusedWindow.isAncestorOf(pointWindow)) {
            eventWindow = pointWindow.getAncestorWithEventId(Event.KEY_PRESS, focusedWindow);
            child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
        }
        if (eventWindow == null) {
            if (!focusedWindow.hasEventListenerFor(Event.KEY_PRESS)) return;
            eventWindow = focusedWindow;
        }

        if (!eventWindow.attributes.isEnabled()) return;

        Bitmask keyButMask = getKeyButMask();
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();

        FullscreenTransformation fullscreenTransformation = eventWindow.getFullscreenTransformation();
        if (fullscreenTransformation != null) {
            short[] transformedPoint = fullscreenTransformation.transformPointerCoords(x, y);
            x = transformedPoint[0];
            y = transformedPoint[1];
        }

        short[] localPoint = eventWindow.rootPointToLocal(x, y);
        if (keysym != 0 && !xServer.keyboard.hasKeysym(keycode, keysym)) {
            xServer.keyboard.setKeysyms(keycode, keysym, keysym);
            eventWindow.sendEvent(new MappingNotify(MappingNotify.Request.KEYBOARD, keycode, 1));
        }

        eventWindow.sendEvent(Event.KEY_PRESS, new KeyPress(keycode, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], keyButMask));
    }

    @Override
    public void onKeyRelease(byte keycode) {
        Window focusedWindow = xServer.windowManager.getFocusedWindow();
        if (focusedWindow == null) return;
        updatePointWindow();

        Window eventWindow = null;
        Window child = null;
        if (focusedWindow.isAncestorOf(pointWindow)) {
            eventWindow = pointWindow.getAncestorWithEventId(Event.KEY_RELEASE, focusedWindow);
            child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
        }
        if (eventWindow == null) {
            if (!focusedWindow.hasEventListenerFor(Event.KEY_RELEASE)) return;
            eventWindow = focusedWindow;
        }

        if (!eventWindow.attributes.isEnabled()) return;

        Bitmask keyButMask = getKeyButMask();
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();

        FullscreenTransformation fullscreenTransformation = eventWindow.getFullscreenTransformation();
        if (fullscreenTransformation != null) {
            short[] transformedPoint = fullscreenTransformation.transformPointerCoords(x, y);
            x = transformedPoint[0];
            y = transformedPoint[1];
        }

        short[] localPoint = eventWindow.rootPointToLocal(x, y);
        eventWindow.sendEvent(Event.KEY_RELEASE, new KeyRelease(keycode, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], keyButMask));
    }

    private Bitmask createPointerEventMask() {
        Bitmask eventMask = new Bitmask();
        eventMask.set(Event.POINTER_MOTION);

        Bitmask buttonMask = xServer.pointer.getButtonMask();
        if (!buttonMask.isEmpty()) {
            eventMask.set(Event.BUTTON_MOTION);

            if (buttonMask.isSet(Pointer.Button.BUTTON_LEFT.flag())) {
                eventMask.set(Event.BUTTON1_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_MIDDLE.flag())) {
                eventMask.set(Event.BUTTON2_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_RIGHT.flag())) {
                eventMask.set(Event.BUTTON3_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_SCROLL_UP.flag())) {
                eventMask.set(Event.BUTTON4_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_SCROLL_DOWN.flag())) {
                eventMask.set(Event.BUTTON5_MOTION);
            }
        }
        return eventMask;
    }

    public Bitmask getKeyButMask() {
        Bitmask keyButMask = new Bitmask();
        keyButMask.join(xServer.pointer.getButtonMask());
        keyButMask.join(xServer.keyboard.getModifiersMask());
        return keyButMask;
    }
}
