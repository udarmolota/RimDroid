package com.rimdroid.mods;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies the downloaded mods of one instance into another's Mods/ (as in Zomdroid, 2026-11). Only
 * mods are copied, never Core or the DLC, and a mod the target already has (the same packageId,
 * whatever its folder is called) is left alone. A mod is copied into a hidden folder first and
 * renamed when complete, so an interrupted copy leaves no half mod behind. Symbolic links are not
 * followed or copied. A mod usually carries folders for several game versions (1.5/1.6/1.7), so any
 * instance can be the target; the game itself marks a mod that does not support its version.
 *
 * Optionally the target also takes the source's load order: which mods are on and in what order,
 * limited to what the target has (a DLC the target lacks is left out).
 */
public final class ModCopier {
    private ModCopier() {}

    public static final class Result {
        public final List<String> copied = new ArrayList<>();
        public final List<String> alreadyThere = new ArrayList<>();
        public final List<String> failed = new ArrayList<>();
        /** Only when the load order was copied: false if the target's ModsConfig.xml could not be written. */
        public boolean orderWritten = true;
    }

    public interface Progress {
        void onMod(String name, int index, int total);
    }

    /** The source's downloaded mods, by name: what the copy dialog lists. */
    public static List<ModList.Mod> listCopyable(File fromInstance) {
        List<ModList.Mod> mods = new ArrayList<>();
        for (ModList.Mod m : ModList.installed(fromInstance)) if (!m.official) mods.add(m);
        return mods;
    }

    /** The packageIds an instance has: copying one of them again would change nothing. */
    public static Set<String> installedIds(File instance) {
        Set<String> ids = new HashSet<>();
        for (ModList.Mod m : ModList.installed(instance)) ids.add(m.packageId);
        return ids;
    }

    /** Copies the mods whose packageIds are in {@code only} (every mod when null). */
    public static Result copy(File fromInstance, File toInstance, java.util.Collection<String> only,
                              boolean withOrder, Progress progress) {
        Result result = new Result();
        File toMods = new File(toInstance, "Mods");
        //noinspection ResultOfMethodCallIgnored
        toMods.mkdirs();
        Set<String> have = new HashSet<>();
        for (ModList.Mod m : ModList.installed(toInstance)) have.add(m.packageId);

        List<ModList.Mod> mods = new ArrayList<>();
        for (ModList.Mod m : listCopyable(fromInstance)) if (only == null || only.contains(m.packageId)) mods.add(m);
        for (int i = 0; i < mods.size(); i++) {
            ModList.Mod mod = mods.get(i);
            if (progress != null) progress.onMod(mod.name, i + 1, mods.size());
            File dest = new File(toMods, mod.folder.getName());
            if (have.contains(mod.packageId) || dest.exists()) {
                result.alreadyThere.add(mod.name);
                continue;
            }
            File tmp = new File(toMods, "." + mod.folder.getName() + ".copying");
            try {
                deleteTree(tmp.toPath());
                copyTree(mod.folder.toPath(), tmp.toPath());
                Files.move(tmp.toPath(), dest.toPath());
                result.copied.add(mod.name);
                have.add(mod.packageId);
            } catch (IOException e) {
                result.failed.add(mod.name);
                try { deleteTree(tmp.toPath()); } catch (IOException ignored) { }
            }
        }

        if (withOrder) result.orderWritten = copyOrder(fromInstance, toInstance);
        return result;
    }

    /** The source's active mods, in order, as far as the target has them; RimDroid's own mod keeps its state. */
    private static boolean copyOrder(File fromInstance, File toInstance) {
        List<String> from = ModList.activeOrder(fromInstance);
        if (from == null || !ModList.ensureConfig(toInstance)) return false;
        List<String> before = ModList.activeOrder(toInstance);
        Map<String, ModList.Mod> target = ModList.byId(ModList.installed(toInstance));
        List<String> order = new ArrayList<>();
        for (String id : from) {
            if (ModList.BUILTIN.equals(id) || order.contains(id)) continue;
            if (target.containsKey(id) || ModList.CORE.equals(id)) order.add(id);
        }
        if (!order.contains(ModList.CORE)) order.add(0, ModList.CORE);
        if (before != null && before.contains(ModList.BUILTIN)) order.add(ModList.BUILTIN);
        return ModList.writeOrder(toInstance, order);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile())
                    Files.copy(file, to.resolve(from.relativize(file).toString()), LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
