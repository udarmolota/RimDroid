package com.rimdroid.xserver;

import com.rimdroid.xserver.util.CursorLocker;
import com.rimdroid.xserver.extensions.BigReqExtension;
import com.rimdroid.xserver.extensions.Extension;
import com.rimdroid.xserver.extensions.MITSHMExtension;
import com.rimdroid.xserver.extensions.RandRExtension;
import com.rimdroid.xserver.extensions.SyncExtension;
import com.rimdroid.xserver.extensions.XComposite;

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    private final Extension[] extensions;
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    public final CursorLocker cursorLocker;
    private SHMSegmentManager shmSegmentManager;
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private final Object inputQueueLock = new Object();
    private final ArrayDeque<PendingInput> inputQueue = new ArrayDeque<>();
    private final Thread inputThread;
    private boolean inputDispatcherRunning = true;
    private boolean relativeMouseMovement = false;

    private static final int INPUT_POINTER_MOVE = 1;
    private static final int INPUT_POINTER_DELTA = 2;
    private static final int INPUT_BUTTON_PRESS = 3;
    private static final int INPUT_BUTTON_RELEASE = 4;
    private static final int INPUT_KEY_PRESS = 5;
    private static final int INPUT_KEY_RELEASE = 6;
    private static final int INPUT_KEY_PRESS_RAW = 7;
    private static final int INPUT_KEY_RELEASE_RAW = 8;
    private static final int MAX_QUEUED_INPUTS = 256;

    private static final class PendingInput {
        final int type;
        int first;
        int second;
        final Object value;

        PendingInput(int type, int first, int second, Object value) {
            this.type = type;
            this.first = first;
            this.second = second;
            this.value = value;
        }

        boolean isMotion() {
            return type == INPUT_POINTER_MOVE || type == INPUT_POINTER_DELTA;
        }
    }

    public XServer(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
        cursorLocker = new CursorLocker(this);
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new ReentrantLock());

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);

        extensions = setupExtensions();

        inputThread = new Thread(this::runInputDispatcher, "RimDroid-XInput");
        inputThread.setDaemon(true);
        inputThread.start();
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        cursorLocker.setEnabled(!relativeMouseMovement);
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    /** All registered extensions (for ListExtensions). */
    public Extension[] getExtensions() {
        return extensions;
    }

    public Extension getExtensionByName(String name) {
        for (Extension extension : extensions) if (extension.getName().equals(name)) return extension;
        return null;
    }

    public void injectPointerMove(int x, int y) {
        enqueueInput(new PendingInput(INPUT_POINTER_MOVE, x, y, null));
    }

    private void injectPointerMoveNow(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(x, y);
        }
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        enqueueInput(new PendingInput(INPUT_POINTER_DELTA, dx, dy, null));
    }

    private void injectPointerMoveDeltaNow(int dx, int dy) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(pointer.getX() + dx, pointer.getY() + dy);
        }
    }

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        enqueueInput(new PendingInput(INPUT_BUTTON_PRESS, 0, 0, buttonCode));
    }

    private void injectPointerButtonPressNow(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);
        }
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        enqueueInput(new PendingInput(INPUT_BUTTON_RELEASE, 0, 0, buttonCode));
    }

    private void injectPointerButtonReleaseNow(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, false);
        }
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        enqueueInput(new PendingInput(INPUT_KEY_PRESS, keysym, 0, xKeycode));
    }

    private void injectKeyPressNow(XKeycode xKeycode, int keysym) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(xKeycode.id, keysym);
        }
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        enqueueInput(new PendingInput(INPUT_KEY_RELEASE, 0, 0, xKeycode));
    }

    private void injectKeyReleaseNow(XKeycode xKeycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(xKeycode.id);
        }
    }

    private final android.os.Handler injectHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private void injectKeyPressRaw(byte keycode, int keysym) {
        enqueueInput(new PendingInput(INPUT_KEY_PRESS_RAW, keycode, keysym, null));
    }

    private void injectKeyPressRawNow(byte keycode, int keysym) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(keycode, keysym);
        }
    }

    private void injectKeyReleaseRaw(byte keycode) {
        enqueueInput(new PendingInput(INPUT_KEY_RELEASE_RAW, keycode, 0, null));
    }

    private void injectKeyReleaseRawNow(byte keycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(keycode);
        }
    }

    private void enqueueInput(PendingInput input) {
        synchronized (inputQueueLock) {
            if (!inputDispatcherRunning) return;

            PendingInput last = inputQueue.peekLast();
            if (last != null && input.isMotion() && last.type == input.type) {
                if (input.type == INPUT_POINTER_DELTA) {
                    last.first += input.first;
                    last.second += input.second;
                } else {
                    last.first = input.first;
                    last.second = input.second;
                }
                return;
            }

            if (inputQueue.size() >= MAX_QUEUED_INPUTS && input.isMotion()) return;
            inputQueue.addLast(input);
            inputQueueLock.notifyAll();
        }
    }

    private void runInputDispatcher() {
        while (true) {
            PendingInput input;
            synchronized (inputQueueLock) {
                while (inputDispatcherRunning && inputQueue.isEmpty()) {
                    try {
                        inputQueueLock.wait();
                    } catch (InterruptedException ignored) {
                        // Re-check the running flag below.
                    }
                }
                if (!inputDispatcherRunning) return;
                input = inputQueue.removeFirst();
            }

            try {
                dispatchInput(input);
            } catch (RuntimeException e) {
                android.util.Log.e("RimDroid/XServer", "Failed to dispatch input", e);
            }
        }
    }

    private void dispatchInput(PendingInput input) {
        switch (input.type) {
            case INPUT_POINTER_MOVE:
                injectPointerMoveNow(input.first, input.second);
                break;
            case INPUT_POINTER_DELTA:
                injectPointerMoveDeltaNow(input.first, input.second);
                break;
            case INPUT_BUTTON_PRESS:
                injectPointerButtonPressNow((Pointer.Button)input.value);
                break;
            case INPUT_BUTTON_RELEASE:
                injectPointerButtonReleaseNow((Pointer.Button)input.value);
                break;
            case INPUT_KEY_PRESS:
                injectKeyPressNow((XKeycode)input.value, input.first);
                break;
            case INPUT_KEY_RELEASE:
                injectKeyReleaseNow((XKeycode)input.value);
                break;
            case INPUT_KEY_PRESS_RAW:
                injectKeyPressRawNow((byte)input.first, input.second);
                break;
            case INPUT_KEY_RELEASE_RAW:
                injectKeyReleaseRawNow((byte)input.first);
                break;
            default:
                throw new IllegalArgumentException("Unknown input type " + input.type);
        }
    }

    public void shutdownInputDispatcher() {
        synchronized (inputQueueLock) {
            inputDispatcherRunning = false;
            inputQueue.clear();
            inputQueueLock.notifyAll();
        }
        inputThread.interrupt();
    }

    /**
     * Type finished text into the game as real X11 key events (soft-keyboard path). ASCII characters
     * go through their REAL keycode from the keymap — with Shift for uppercase/symbols — so SDL turns
     * them into text exactly as for a hardware keyboard (borrowing a "custom" keycode collided for
     * some letters, e.g. s/f). A character not in the keymap (CJK) falls back to a custom keycode.
     * Press/release are spaced out so SDL registers each keystroke; this replaces synthesising an
     * SDL_TEXTINPUT, which RimWorld ignores.
     */
    public void injectText(String text) {
        if (text == null) return;
        int delay = 0;
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int[] res = keyboard.resolveChar(cp);
            if (res != null) {
                final byte keycode = (byte) res[0];
                final boolean shift = res[1] != 0;
                if (shift) injectHandler.postDelayed(() -> injectKeyPress(XKeycode.KEY_SHIFT_L), delay);
                injectHandler.postDelayed(() -> injectKeyPressRaw(keycode, cp), delay + (shift ? 5 : 0));
                injectHandler.postDelayed(() -> injectKeyReleaseRaw(keycode), delay + 20);
                if (shift) injectHandler.postDelayed(() -> injectKeyRelease(XKeycode.KEY_SHIFT_L), delay + 25);
            } else {
                // Non-ASCII (Cyrillic, Portuguese ã/ç, CJK…): NOT solved yet. SDL under box64 doesn't
                // pick up the runtime keymap change, so a custom-keycode press produces no text. Kept
                // as a harmless best-effort (English above types via real keycodes and works). To
                // revisit: preload full alphabets into fixed keycodes, or find why SDL ignores the
                // MappingNotify.
                final int keysym = 0x01000000 | cp;
                final XKeycode xk = keyboard.customKeycodeForKeysym(keysym);
                injectHandler.postDelayed(() -> injectKeyPress(xk, keysym), delay);
                injectHandler.postDelayed(() -> injectKeyRelease(xk), delay + 20);
            }
            delay += 45;
        }
    }

    /** Backspace from the soft keyboard (IME deleteSurroundingText). */
    public void injectBackspace() {
        injectKeyPress(XKeycode.KEY_BKSP, 0xFF08);   // XK_BackSpace
        injectHandler.postDelayed(() -> injectKeyRelease(XKeycode.KEY_BKSP), 20);
    }

    private Extension[] setupExtensions() {
        byte opcode = Extension.START_MAJOR_OPCODE;
        return new Extension[]{
            new BigReqExtension(this, opcode--),
            new MITSHMExtension(this, opcode--),
            new SyncExtension(this, opcode--),
            new XComposite(this, opcode--),
            new RandRExtension(this, opcode--)   // SDL2 x11 needs RandR≥1.3 to enumerate a display
        };
    }

    public <T extends Extension> T getExtension(byte opcode) {
        int index = Extension.START_MAJOR_OPCODE - opcode;
        return (T)extensions[index];
    }

    public void debugPrint(String line) {
        android.util.Log.d("RimDroid/XServer", line);
    }
}
