package com.rimdroid.audio;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-device reader for RimWorld's Unity audio assets — the Java port of the PC-validated recipe
 * (matches UnityPy 1:1 for RimWorld 1.5.4104 = Unity 2019.4.30f1, SerializedFile v21, little-endian,
 * no type tree). Produces the work-list for the on-device sound-pack generator:
 *   {clipPath (original case), AudioClip resource offset/size + channels/freq, target "rd/..." path}.
 *
 * Pipeline (see FmodDecodeService / [[audio_fmod_plan]]):
 *   resources.assets      -> {pathID -> AudioClip{name, resOffset, resSize, channels, freq}}  (class 83)
 *   globalgamemanagers    -> {"sounds/<path>" -> pathID}  (ResourceManager class 147 m_Container)
 *   Data/.../SoundDefs/*.xml -> clipFolderPath + clipPath (original case)
 *   correlate             -> clipPath = clipFolderPath + "/" + AudioClip.m_Name (or the def clipPath)
 *
 * NOTE: parses only the RimWorld 1.5 layout (SerializedFile v21, type-tree stripped). If a future
 * RimWorld ships a different Unity version, this needs revisiting (logs and returns empty).
 */
public final class UnityAudioAssets {
    private static final String TAG = "RimDroid/SoundPack";

    public static final class Clip {
        public long pathId;
        public String name;
        public long resOffset, resSize;  // into resources.resource
        public int channels, freq, compressionFormat;
    }

    public static final class WorkItem {
        public String clipPath;   // original-case clipPath that RimWorld defs use (xpath text)
        public String rdPath;     // "rd/<lowercased>" — target clipPath in our mod (no extension)
        public Clip clip;
    }

    /**
     * The full plan: every clip to decode (WAVs), plus the MINIMAL patch set. We patch whole folders
     * (clipFolderPath, ~360) instead of every clip (clipPath, ~2203) so RimWorld's startup patch phase
     * stays cheap — 2203 per-clip XPath replaces hung the load. Single-clip defs still patch per clip.
     */
    public static final class Plan {
        public final List<WorkItem> decode = new ArrayList<>();        // all clips -> WAV
        public final java.util.LinkedHashMap<String, String> folderPatches = new java.util.LinkedHashMap<>(); // clipFolderPath(orig) -> rd/<lower>
        public final java.util.LinkedHashMap<String, String> singlePatches = new java.util.LinkedHashMap<>(); // clipPath(orig)       -> rd/<lower>
        public WorkItem menuMusic;  // EntrySong clip to decode in stereo (null if not found); other music is silenced
    }

    // ---- tiny cursor over a byte[] ----
    private static final class Cur {
        final byte[] d; int p;
        Cur(byte[] d, int p) { this.d = d; this.p = p; }
        int u8() { return d[p++] & 0xff; }
        int i16le() { int v = (d[p] & 0xff) | ((d[p+1] & 0xff) << 8); p += 2; return (short) v; }
        int i32le() { int v = (d[p]&0xff) | ((d[p+1]&0xff)<<8) | ((d[p+2]&0xff)<<16) | ((d[p+3]&0xff)<<24); p += 4; return v; }
        long u32le() { return i32le() & 0xffffffffL; }
        long i64le() { long v = 0; for (int i = 0; i < 8; i++) v |= (long)(d[p+i] & 0xff) << (8*i); p += 8; return v; }
        void align4() { p = (p + 3) & ~3; }
        String alignedString() {
            int n = i32le();
            String s = new String(d, p, n, StandardCharsets.UTF_8);
            p += n; align4();
            return s;
        }
        String cstring() {
            int s = p; while (d[p] != 0) p++;
            String r = new String(d, s, p - s, StandardCharsets.UTF_8); p++;
            return r;
        }
    }

    // SerializedFile header + object table. Returns object records; sets out[0]=dataOffset.
    private static final class Obj { long pathId; long absStart; long size; int classId; }

    private static List<Obj> parseObjects(byte[] d) {
        // header is big-endian
        long metaSize = beU32(d, 0), fileSize = beU32(d, 4), version = beU32(d, 8), dataOffset = beU32(d, 12);
        int endian = d[16] & 0xff;
        if ((version != 21 && version != 22) || endian != 0) {
            Log.w(TAG, "Unexpected SerializedFile version=" + version + " endian=" + endian + " (need v21/v22 LE)");
            return null;
        }
        int metaStart = 20;
        if (version == 22) {
            // v22 = "LargeFilesSupport" (Unity 2020.1+ / RimWorld 1.6's 2022.3): the classic 32-bit
            // header fields are superseded by an extension right after the endian byte+padding —
            // u32 metadataSize, i64 fileSize, i64 dataOffset, i64 reserved — still big-endian
            // (the endian byte only governs the metadata that follows). Object byteStart also
            // widens to i64 (see below). Everything else matches v21.
            metaSize = beU32(d, 20);
            fileSize = beI64(d, 24);
            dataOffset = beI64(d, 32);
            metaStart = 48;
        }
        final boolean v22 = (version == 22);
        Cur c = new Cur(d, metaStart);
        c.cstring();              // unity_version
        c.i32le();               // target_platform
        int enableTypeTree = c.u8();
        if (enableTypeTree != 0) { Log.w(TAG, "type tree present — unsupported"); return null; }
        int typeCount = c.i32le();
        int[] classIds = new int[typeCount];
        for (int i = 0; i < typeCount; i++) {
            int classId = c.i32le();
            c.u8();              // is_stripped
            c.i16le();           // script_type_index
            if (classId == 114) c.p += 16;  // script id hash
            c.p += 16;           // old type hash
            classIds[i] = classId;
        }
        int objCount = c.i32le();
        List<Obj> objs = new ArrayList<>(objCount);
        for (int i = 0; i < objCount; i++) {
            c.align4();
            Obj o = new Obj();
            o.pathId = c.i64le();
            o.absStart = dataOffset + (v22 ? c.i64le() : c.u32le());
            o.size = c.u32le();
            int typeId = c.i32le();
            o.classId = (typeId >= 0 && typeId < typeCount) ? classIds[typeId] : -1;
            objs.add(o);
        }
        return objs;
    }

    private static long beU32(byte[] d, int o) {
        return ((long)(d[o]&0xff)<<24) | ((d[o+1]&0xff)<<16) | ((d[o+2]&0xff)<<8) | (d[o+3]&0xff);
    }

    private static long beI64(byte[] d, int o) {
        return (beU32(d, o) << 32) | beU32(d, o + 4);
    }

    /** resources.assets -> {pathID -> Clip}. */
    public static Map<Long, Clip> parseAudioClips(File resourcesAssets) {
        byte[] d = readAll(resourcesAssets);
        if (d == null) return null;
        List<Obj> objs = parseObjects(d);
        if (objs == null) return null;
        Map<Long, Clip> map = new HashMap<>();
        for (Obj o : objs) {
            if (o.classId != 83) continue;  // AudioClip
            Cur c = new Cur(d, (int) o.absStart);
            Clip clip = new Clip();
            clip.pathId = o.pathId;
            clip.name = c.alignedString();      // m_Name
            c.i32le();                          // m_LoadType
            clip.channels = c.i32le();          // m_Channels
            clip.freq = c.i32le();              // m_Frequency
            c.i32le();                          // m_BitsPerSample
            c.i32le();                          // m_Length (float bits, skip)
            c.u8(); c.align4();                 // m_IsTrackerFormat + align
            c.i32le();                          // m_SubsoundIndex
            c.u8(); c.u8(); c.u8(); c.align4(); // preload/loadInBg/legacy3D + align
            c.alignedString();                  // m_Resource.m_Source
            clip.resOffset = c.i64le();         // m_Resource.m_Offset
            clip.resSize = c.i64le();           // m_Resource.m_Size
            clip.compressionFormat = c.i32le(); // m_CompressionFormat (1 = Vorbis)
            map.put(o.pathId, clip);
        }
        Log.i(TAG, "parsed AudioClips: " + map.size());
        return map;
    }

    /** globalgamemanagers ResourceManager m_Container -> {"sounds/..." -> pathID}. */
    public static Map<String, Long> parseSoundContainer(File globalgamemanagers) {
        byte[] d = readAll(globalgamemanagers);
        if (d == null) return null;
        List<Obj> objs = parseObjects(d);
        if (objs == null) return null;
        Obj rm = null;
        for (Obj o : objs) if (o.classId == 147) { rm = o; break; }  // ResourceManager
        if (rm == null) { Log.w(TAG, "ResourceManager not found"); return null; }
        Cur c = new Cur(d, (int) rm.absStart);
        int count = c.i32le();
        Map<String, Long> map = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String key = c.alignedString();
            c.i32le();              // PPtr.m_FileID
            long pathId = c.i64le();// PPtr.m_PathID
            if (key.startsWith("sounds/")) map.put(key, pathId);  // covers SFX + music (sounds/songs/...)
        }
        Log.i(TAG, "parsed sound container entries: " + map.size());
        return map;
    }

    private static final Pattern P_FOLDER = Pattern.compile("<clipFolderPath>([^<]+)</clipFolderPath>");
    private static final Pattern P_CLIP   = Pattern.compile("<clipPath>([^<]+)</clipPath>");

    /** Scan <instance>/Data/<*>/Defs/SoundDefs/**.xml for clipFolderPath + clipPath (original case). */
    public static void parseSoundDefs(File instanceDir, Set<String> folders, Set<String> singles) {
        File data = new File(instanceDir, "Data");
        File[] mods = data.listFiles();
        if (mods == null) return;
        for (File mod : mods) {
            File sd = new File(mod, "Defs/SoundDefs");
            scanXml(sd, folders, singles);
        }
    }

    private static void scanXml(File dir, Set<String> folders, Set<String> singles) {
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) { scanXml(k, folders, singles); continue; }
            if (!k.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) continue;
            byte[] b = readAll(k);
            if (b == null) continue;
            String t = new String(b, StandardCharsets.UTF_8);
            Matcher m = P_FOLDER.matcher(t);
            while (m.find()) folders.add(m.group(1).trim());
            m = P_CLIP.matcher(t);
            while (m.find()) singles.add(m.group(1).trim());
        }
    }

    /** All clips to decode (back-compat helper for the debug summary). */
    public static List<WorkItem> buildWorkList(File instanceDir) {
        Plan p = buildPlan(instanceDir);
        return p == null ? null : p.decode;
    }

    /** Correlate all three sources into the decode list + the MINIMAL (folder-level) patch set. */
    public static Plan buildPlan(File instanceDir) {
        File dataRoot = new File(instanceDir, "RimWorldLinux_Data");
        Map<Long, Clip> clips = parseAudioClips(new File(dataRoot, "resources.assets"));
        Map<String, Long> container = parseSoundContainer(new File(dataRoot, "globalgamemanagers"));
        if (clips == null || container == null) return null;
        Set<String> folders = new HashSet<>(), singles = new HashSet<>();
        parseSoundDefs(instanceDir, folders, singles);
        Log.i(TAG, "def clipFolderPaths=" + folders.size() + " clipPaths=" + singles.size());

        Plan plan = new Plan();
        Map<String, WorkItem> decodeMap = new HashMap<>();  // dedupe clips by clipPath

        // folder defs: decode every clip in the folder, but emit ONE clipFolderPath patch for the folder.
        for (String f : folders) {
            String prefix = "sounds/" + f.toLowerCase(Locale.ROOT) + "/";
            boolean matched = false;
            for (Map.Entry<String, Long> e : container.entrySet()) {
                String key = e.getKey();
                if (!key.startsWith(prefix)) continue;
                if (key.indexOf('/', prefix.length()) >= 0) continue;  // direct child only
                Clip clip = clips.get(e.getValue());
                if (clip == null) continue;
                addItem(decodeMap, f + "/" + clip.name, clip);
                matched = true;
            }
            if (matched) plan.folderPatches.put(f, "rd/" + f.toLowerCase(Locale.ROOT));
        }
        // single clipPath defs: decode + per-clip patch.
        for (String s : singles) {
            Long pid = container.get("sounds/" + s.toLowerCase(Locale.ROOT));
            if (pid == null) continue;
            Clip clip = clips.get(pid);
            if (clip == null) continue;
            addItem(decodeMap, s, clip);
            plan.singlePatches.put(s, "rd/" + s.toLowerCase(Locale.ROOT));
        }
        plan.decode.addAll(decodeMap.values());

        // Menu music: the game's EntrySong (clipPath "Songs/Entry/Entry_Ambience_Full") lives in the
        // container under "sounds/songs/entry/entry_ambience_full". Decode it in stereo so the menu has
        // clean PCM; all OTHER music is silenced (Vorbis is garbled under box64) — see generatePack.
        Long entryPid = container.get("sounds/songs/entry/entry_ambience_full");
        if (entryPid != null) {
            Clip ec = clips.get(entryPid);
            if (ec != null) {
                WorkItem m = new WorkItem();
                m.clipPath = "Songs/Entry/Entry_Ambience_Full";
                m.rdPath   = "rd/songs/entry/entry_ambience_full";
                m.clip     = ec;
                plan.menuMusic = m;
            }
        }

        Log.i(TAG, "plan: decode=" + plan.decode.size() + " folderPatches=" + plan.folderPatches.size()
                + " singlePatches=" + plan.singlePatches.size() + " menuMusic=" + (plan.menuMusic != null)
                + " (was " + decodeMap.size() + " per-clip patches)");
        return plan;
    }

    private static void addItem(Map<String, WorkItem> out, String clipPath, Clip clip) {
        if (out.containsKey(clipPath)) return;
        WorkItem w = new WorkItem();
        w.clipPath = clipPath;
        // Sanitize OUR output filename: some vanilla clip names contain spaces (e.g.
        // "ThroneSpeech_male/Speech_royal_ 8") and RimWorld 1.6 loads mod audio via curl
        // file:// URLs that reject unescaped spaces. We control both the .wav name and the
        // patched clipPath value, so replace URL-hostile chars consistently here.
        w.rdPath = ("rd/" + clipPath.toLowerCase(Locale.ROOT)).replaceAll("[^a-z0-9/._-]+", "_");
        w.clip = clip;
        out.put(clipPath, w);
    }

    private static byte[] readAll(File f) {
        if (f == null || !f.isFile()) { Log.w(TAG, "missing: " + f); return null; }
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[1 << 16]; int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) { Log.e(TAG, "read failed: " + f, t); return null; }
    }

    private UnityAudioAssets() {}
}
