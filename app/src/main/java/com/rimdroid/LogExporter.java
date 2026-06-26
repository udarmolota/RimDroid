package com.rimdroid;

import com.rimdroid.game.GameInstance;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Collects an instance's diagnostic logs into a single zip for sharing/support.
 *
 * <p>In our in-process setup box64 has no separate file — its output folds into Unity's
 * Player.log — so the most useful artifacts are Player.log + Player-prev.log (the
 * previous run, which often holds the crash). We also include box64.log / rimdroid.log
 * if present and Config/Prefs.xml (handy for resolution / settings issues). Missing
 * files are skipped silently.
 */
public final class LogExporter {

    private LogExporter() {}

    public static final class Result {
        public final List<String> items = new ArrayList<>();
        public long bytes;
        public String error;
        public boolean ok() { return error == null && !items.isEmpty(); }
    }

    public static Result export(GameInstance gi, OutputStream rawOut) {
        Result r = new Result();
        if (gi == null) { r.error = "No game instance selected."; return r; }

        File gamePath = new File(gi.getGamePath());
        File userDir  = gi.getUserDataDir();
        File configDir = new File(userDir, "Config");

        // Global (not instance-scoped) uncaught-crash log — e.g. an in-app Steam download that
        // hard-crashed the app. Lives in the app's private files dir.
        File crashLog = new File(AppStorage.requireSingleton().getHomePath(),
                RimDroidApplication.CRASH_LOG);

        File[] candidates = {
                new File(userDir, "Player.log"),
                new File(userDir, "Player-prev.log"),
                new File(gamePath, "box64.log"),
                new File(gamePath, "rimdroid.log"),
                new File(configDir, "Prefs.xml"),
                new File(configDir, "ModsConfig.xml"),   // active mods + load order — vital for mod/Harmony issues
                crashLog,
        };

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(rawOut))) {
            byte[] buf = new byte[65536];
            for (File f : candidates) {
                if (f == null || !f.isFile()) continue;
                zos.putNextEntry(new ZipEntry(f.getName()));
                try (FileInputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                        r.bytes += n;
                    }
                }
                zos.closeEntry();
                r.items.add(f.getName());
            }
        } catch (Exception e) {
            r.error = e.getMessage();
            return r;
        }
        if (r.items.isEmpty()) r.error = "No logs found yet (run the game first).";
        return r;
    }
}
