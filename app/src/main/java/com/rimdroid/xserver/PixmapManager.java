package com.rimdroid.xserver;

import android.graphics.Bitmap;
import android.util.SparseArray;

public class PixmapManager extends XResourceManager {
    public final Visual visual;
    public final Visual[] supportedVisuals;
    public final PixmapFormat[] supportedPixmapFormats;
    private final SparseArray<Pixmap> pixmaps = new SparseArray<>();

    public PixmapManager() {
        // RimDroid: DEFAULT visual is depth-24 TrueColor (bits_per_rgb=8), like every real X server.
        // Winlator defaulted to a depth-32 default visual, which SDL2's x11 driver rejects
        // (get_visualinfo → XMatchVisualInfo(DefaultDepth, TrueColor) → SDL gets 0 displays →
        // RimWorld 1.6 crashes writing into the empty display list). Depth-32 (ARGB) stays available
        // as a secondary visual. See memory rimworld_16_port.
        visual = new Visual(IDGenerator.generate(), true, 24, 8, 0xff0000, 0x00ff00, 0x0000ff);
        Visual visual32 = new Visual(IDGenerator.generate(), true, 32, 8, 0xff0000, 0x00ff00, 0x0000ff);
        supportedVisuals = new Visual[]{visual, visual32, new Visual(IDGenerator.generate(), false, 1, 1, 0, 0, 0)};

        supportedPixmapFormats = new PixmapFormat[] {
            new PixmapFormat(1, 1, 32),
            new PixmapFormat(24, 32, 32),
            new PixmapFormat(32, 32, 32)
        };
    }

    public Pixmap getPixmap(int id) {
        return pixmaps.get(id);
    }

    public Pixmap createPixmap(Drawable drawable) {
        if (pixmaps.indexOfKey(drawable.id) >= 0) return null;
        Pixmap pixmap = new Pixmap(drawable);
        pixmaps.put(drawable.id, pixmap);
        triggerOnCreateResourceListener(pixmap);
        return pixmap;
    }

    public void freePixmap(int id) {
        triggerOnFreeResourceListener(pixmaps.get(id));
        pixmaps.remove(id);
    }

    public Visual getVisualForDepth(byte depth) {
        if (depth == visual.depth) return visual;
        for (Visual visual : supportedVisuals) {
            if (depth == visual.depth) return visual;
        }
        return null;
    }

    public Visual getVisual(int id) {
        if (id == visual.id) return visual;
        for (Visual visual : supportedVisuals) {
            if (id == visual.id && visual.displayable) return visual;
        }
        return null;
    }

    public Bitmap getWindowIcon(Window window) {
        int colorPixmapId = window.getWMHintsValue(Window.WMHints.ICON_PIXMAP);
        int maskPixmapId = window.getWMHintsValue(Window.WMHints.ICON_MASK);
        Pixmap colorPixmap = colorPixmapId != 0 ? getPixmap(colorPixmapId) : null;
        Pixmap maskPixmap = maskPixmapId != 0 ? getPixmap(maskPixmapId) : null;
        return colorPixmap != null ? colorPixmap.toBitmap(maskPixmap) : null;
    }
}
