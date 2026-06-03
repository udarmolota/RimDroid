package com.rimdroid;

import android.app.Activity;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.util.Log;

public class GameActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "RimDroid/GameActivity";

    private SurfaceView surfaceView;

    // Native input injection (rimdroid_jni.c → box64). action 0=move,1=Ldown,2=Lup.
    public static native void nativeTouch(int action, int x, int y);
    public static native void nativeButton(int button, int down, int x, int y); // 1=L,2=M,3=R
    public static native void nativeScroll(int x, int y, int dy);
    public static native void nativeKey(int scancode, int keycode, int down);
    public static native void nativeText(String text);

    private float renderScale = 0.72f;
    // Auto-pin RimWorld's Prefs.xml to fullscreen at our render resolution.
    // DISABLED for now: forcing fullscreen at surface*scale raised the internal
    // resolution on weak GPUs (e.g. 1024x768 → ~1953x878 on Mali) and broke
    // boot-without-Debug — the heavier load flips a fragile box64 startup timing
    // into a hang. Keep the code; re-enable once it's made safe for weak devices.
    private static final boolean PIN_GAME_PREFS = false;
    private android.view.ScaleGestureDetector scaleDetector;
    private boolean scaling = false;
    private com.rimdroid.input.InputControlsView controls;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private float tapDownX, tapDownY; private long tapDownT; private boolean tapMoved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LauncherPreferences lp = LauncherPreferences.getSingleton();
        if (lp != null) {
            // Effective render scale = stored value raised to the per-device floor, so
            // RimWorld's UI never drops below 1280x720 even on high-res panels. Use the
            // window bounds via max/min so it's correct regardless of current rotation.
            android.graphics.Rect b = getWindowManager().getCurrentWindowMetrics().getBounds();
            int sLong  = Math.max(b.width(), b.height());
            int sShort = Math.min(b.width(), b.height());
            renderScale = lp.getEffectiveRenderScale(sLong, sShort);
        }

        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // True immersive fullscreen: hide BOTH status and navigation bars. The old
        // FLAG_FULLSCREEN only hid the status bar, so on devices with a 3-button
        // navigation bar (e.g. tablets) it stayed visible and ate the bottom of the game.
        getWindow().setDecorFitsSystemWindows(false);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);

        scaleDetector = new android.view.ScaleGestureDetector(this, new ScaleListener());
        controls = new com.rimdroid.input.InputControlsView(this, renderScale);

        FrameLayout root = new FrameLayout(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(controls, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemBars();   // after setContentView — the decor view / insets controller now exist
    }

    private void hideSystemBars() {
        android.view.WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(android.view.WindowInsets.Type.systemBars());
            // Keep them hidden; a swipe shows them transiently, then they auto-hide again.
            c.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();   // re-hide after dialogs / focus regain
    }

    // Pinch-zoom: only non-stick touches reach here (overlay returns false for them).
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (scaleDetector != null) scaleDetector.onTouchEvent(e);
        if (e.getPointerCount() >= 2 || scaling) return true;   // pinch-zoom
        float d = getResources().getDisplayMetrics().density;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tapDownX = e.getX(); tapDownY = e.getY(); tapDownT = System.currentTimeMillis(); tapMoved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - tapDownX) + Math.abs(e.getY() - tapDownY) > 14 * d) tapMoved = true;
                return true;
            case MotionEvent.ACTION_UP:
                if (!tapMoved && !scaling && System.currentTimeMillis() - tapDownT < 250) {
                    final int gx = (int) (e.getX() * renderScale), gy = (int) (e.getY() * renderScale);
                    try {
                        nativeButton(1, 1, gx, gy);   // direct tap = left click at finger
                        ui.postDelayed(() -> { try { nativeButton(1, 0, gx, gy); } catch (UnsatisfiedLinkError ig) {} }, 50);
                    } catch (UnsatisfiedLinkError ig) {}
                }
                return true;
        }
        return true;
    }

    private class ScaleListener extends android.view.ScaleGestureDetector.SimpleOnScaleGestureListener {
        private float accum = 0f;
        @Override public boolean onScaleBegin(android.view.ScaleGestureDetector dt) { scaling = true; accum = 0f; return true; }
        @Override public boolean onScale(android.view.ScaleGestureDetector dt) {
            accum += dt.getScaleFactor() - 1f;
            int fx = (int) (dt.getFocusX() * renderScale), fy = (int) (dt.getFocusY() * renderScale);
            while (accum >  0.15f) { accum -= 0.15f; safeScroll(fx, fy, +1); }
            while (accum < -0.15f) { accum += 0.15f; safeScroll(fx, fy, -1); }
            return true;
        }
        @Override public void onScaleEnd(android.view.ScaleGestureDetector dt) { scaling = false; }
    }
    private void safeScroll(int x, int y, int dy) { try { nativeScroll(x, y, dy); } catch (UnsatisfiedLinkError ignored) {} }

    @Override
    protected void onPause() {
        super.onPause();
        if (controls != null) controls.resetAll();   // release any held buttons/keys
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        GameLauncher.destroyRimDroidWindow();
    }

    // === SurfaceHolder.Callback ==================================================
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        int bw = Math.max(1, Math.round(surfaceView.getWidth()  * renderScale));
        int bh = Math.max(1, Math.round(surfaceView.getHeight() * renderScale));
        Log.i(TAG, "surfaceCreated: scale=" + renderScale + " buffer=" + bw + "x" + bh);
        holder.setFixedSize(bw, bh);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height);
        GameLauncher.setSurface(holder.getSurface(), width, height);
        // Pin RimWorld's Prefs.xml to fullscreen at EXACTLY the buffer resolution we
        // pass the game here (= surface * render scale). RimWorld re-applies its saved
        // Prefs resolution shortly after launch, overriding -screen-width; if that saved
        // value is the Unity default 1024x768 (4:3) the game renders stretched into our
        // surface. Writing the matching size + fullscreen=True before RimWorld reads
        // Prefs (during its init, seconds later) keeps the render 1:1 with correct aspect.
        //noinspection ConstantValue
        if (PIN_GAME_PREFS) pinGamePrefs(width, height);
    }

    /** Force the selected instance's Config/Prefs.xml to fullscreen at the render
     *  resolution we use, so RimWorld doesn't override us with its 4:3 default. */
    private void pinGamePrefs(int width, int height) {
        try {
            LauncherPreferences lp = LauncherPreferences.getSingleton();
            if (lp == null) return;
            String name = lp.getLastInstanceName();
            if (name == null || name.isEmpty()) return;
            com.rimdroid.game.GameInstanceManager mgr =
                    com.rimdroid.game.GameInstanceManager.requireSingleton();
            mgr.reload();
            com.rimdroid.game.GameInstance gi = mgr.getByName(name);
            if (gi == null) return;
            PrefsXml.forceFullscreen(new java.io.File(gi.getUserDataDir(), "Config"), width, height);
        } catch (Throwable t) {
            Log.w(TAG, "pinGamePrefs failed: " + t.getMessage());
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        GameLauncher.destroySurface();
    }
}
