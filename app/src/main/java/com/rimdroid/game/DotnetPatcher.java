package com.rimdroid.game;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes RimWorld 1.7's engine take its Linux paths on Android. The stock .NET for Android answers
 * {@code OperatingSystem.IsLinux()} and {@code RuntimeInformation.IsOSPlatform(OSPlatform.Linux)}
 * with false (Android is its own platform), and Waylith/Brutal know only Linux, Windows and macOS:
 * {@code Brutal.Platform}'s static constructor throws "Unsupported platform".
 *
 * In the engine's assemblies, each {@code call OperatingSystem::IsLinux} (5 bytes) becomes
 * {@code ldc.i4.1} + 4 {@code nop}, and each {@code call OSPlatform::get_Linux; call
 * RuntimeInformation::IsOSPlatform} pair (10 bytes) becomes {@code ldc.i4.1} + 9 {@code nop}: same
 * length, same stack effect, nothing else moves. The tokens are read from the assembly's own metadata
 * (MemberRef rows by name and parent type), so only those calls match. Originals are kept as .rdorig;
 * a patched file has no such calls left, so running it again changes nothing.
 *
 * Same trick for calls that simply throw PlatformNotSupportedException on Android:
 * {@code PosixSignalRegistration.Create(signal, handler)} (Waylith hooks SIGTERM/SIGINT to save on
 * exit; Android ends apps its own way) becomes {@code pop; pop; ldnull} — no registration;
 * {@code Console.add_CancelKeyPress(handler)} (Ctrl+C, the same signals underneath) becomes {@code pop}.
 *
 * And {@code Environment.ProcessPath}: on Android it is /system/bin/app_process64, so the engine put
 * the game folder at /system/bin and its Data at /system/bin/Data (read-only). Where the assembly
 * also references {@code AppContext.BaseDirectory} (the game folder, set by the host), each call to the
 * one becomes a call to the other — the callers only take Path.GetDirectoryName of it.
 *
 * And Waylith's {@code ResourceFiles.ResolveCaseInsensitive(path, isFile)}, which finds a folder by
 * walking from "/" down and looking each name up in its parent's listing (to forgive a wrong letter
 * case, as Windows would). An Android app may not list "/", /data or /data/data, so the walk failed at
 * the first step and every folder came back missing: textures loaded one by one worked, but every
 * folder of textures (plants, items, fire), every sound folder and the name lists did not. Two
 * 2-byte changes make the "does it exist on disk" test it already does for "/" run for every path,
 * and fall through to the walk only when the path is not on disk (the files inside Assets.zip):
 * {@code brfalse.s} after the second IsNullOrEmpty becomes {@code pop; nop}, and the {@code ldnull;
 * ret} after the existence test becomes {@code br.s} to the walk.
 */
final class DotnetPatcher {
    private static final String TAG = "RimDroid/dotnet";
    /** The engine assemblies that ask which OS they run on (Assembly-CSharp does not). */
    static final String[] ASSEMBLIES = { "Brutal.Core.Common.dll", "Brutal.Vulkan.dll", "Brutal.Glfw.dll",
            "Waylith.dll", "Waylith.AssetBake.dll", "RimWorld.dll", "UnityStubs.dll" };

    private DotnetPatcher() {}

    static void patchPlatformChecks(File gameDir) {
        for (String name : ASSEMBLIES) {
            File f = new File(gameDir, name);
            if (!f.isFile()) continue;
            try {
                byte[] b = Files.readAllBytes(f.toPath());
                int patched = patch(b);
                if (patched == 0) continue;
                File orig = new File(f.getPath() + ".rdorig");
                if (!orig.exists()) Files.copy(f.toPath(), orig.toPath());
                Files.write(f.toPath(), b);
                Log.i(TAG, name + ": " + patched + " call site(s) patched for Android");
            } catch (Exception e) {
                Log.w(TAG, "cannot patch platform checks in " + name + ": " + e);
            }
        }
    }

    /** Patches {@code b} in place; returns how many call sites changed. */
    static int patch(byte[] b) {
        Meta m = new Meta(b);
        List<Integer> isLinux = new ArrayList<>(), getLinux = new ArrayList<>(), isOsPlatform = new ArrayList<>(),
                posixSignal = new ArrayList<>(), cancelKey = new ArrayList<>(),
                processPath = new ArrayList<>(), baseDir = new ArrayList<>(),
                dirName = new ArrayList<>(), fileName = new ArrayList<>(), nullOrEmpty = new ArrayList<>(),
                dirExists = new ArrayList<>(), fileExists = new ArrayList<>();
        for (int row = 1; row <= m.memberRefs; row++) {
            String type = m.memberRefParentTypeName(row), name = m.memberRefName(row);
            if (type == null) continue;
            int token = 0x0A000000 | row;
            if (type.equals("OperatingSystem") && name.equals("IsLinux")) isLinux.add(token);
            else if (type.equals("OSPlatform") && name.equals("get_Linux")) getLinux.add(token);
            else if (type.equals("RuntimeInformation") && name.equals("IsOSPlatform")) isOsPlatform.add(token);
            else if (type.equals("PosixSignalRegistration") && name.equals("Create")) posixSignal.add(token);
            else if (type.equals("Console") && name.equals("add_CancelKeyPress")) cancelKey.add(token);
            else if (type.equals("Environment") && name.equals("get_ProcessPath")) processPath.add(token);
            else if (type.equals("AppContext") && name.equals("get_BaseDirectory")) baseDir.add(token);
            else if (type.equals("Path") && name.equals("GetDirectoryName")) dirName.add(token);
            else if (type.equals("Path") && name.equals("GetFileName")) fileName.add(token);
            else if (type.equals("String") && name.equals("IsNullOrEmpty")) nullOrEmpty.add(token);
            else if (type.equals("Directory") && name.equals("Exists")) dirExists.add(token);
            else if (type.equals("File") && name.equals("Exists")) fileExists.add(token);
        }
        int count = 0;
        for (int t : posixSignal)
            count += replace(b, call(t), new byte[] { 0x26, 0x26, 0x14, 0x00, 0x00 });   // pop; pop; ldnull
        for (int t : cancelKey)
            count += replace(b, call(t), new byte[] { 0x26, 0x00, 0x00, 0x00, 0x00 });   // pop
        if (!baseDir.isEmpty())
            for (int t : processPath) count += replace(b, call(t), call(baseDir.get(0)));
        for (int t : isLinux) count += replace(b, call(t), trueOf(5));
        for (int g : getLinux)
            for (int p : isOsPlatform) {
                byte[] pair = new byte[10];
                System.arraycopy(call(g), 0, pair, 0, 5);
                System.arraycopy(call(p), 0, pair, 5, 5);
                count += replace(b, pair, trueOf(10));
            }
        for (int dn : dirName) for (int fn : fileName) for (int ne : nullOrEmpty)
            for (int de : dirExists) for (int fe : fileExists)
                count += replace(b, resolveCaseInsensitiveHead(dn, fn, ne, de, fe, false),
                        resolveCaseInsensitiveHead(dn, fn, ne, de, fe, true));
        return count;
    }

    /**
     * The start of ResourceFiles.ResolveCaseInsensitive, as compiled (53 bytes):
     * <pre>
     * parent = Path.GetDirectoryName(path); name = Path.GetFileName(path);
     * if (string.IsNullOrEmpty(parent) || string.IsNullOrEmpty(name))
     *     return (isFile ? File.Exists(path) : Directory.Exists(path)) ? path : null;
     * </pre>
     * Patched, the existence test runs for every path, and a miss goes on to the listing walk.
     */
    private static byte[] resolveCaseInsensitiveHead(int dirName, int fileName, int nullOrEmpty,
                                                     int dirExists, int fileExists, boolean patched) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x02); o.write(call(dirName), 0, 5); o.write(0x0a);              // ldarg.0; call; stloc.0
        o.write(0x02); o.write(call(fileName), 0, 5); o.write(0x0b);             // ldarg.0; call; stloc.1
        o.write(0x06); o.write(call(nullOrEmpty), 0, 5); o.write(0x2d); o.write(0x08);   // ldloc.0; call; brtrue.s
        o.write(0x07); o.write(call(nullOrEmpty), 0, 5);                          // ldloc.1; call
        if (patched) { o.write(0x26); o.write(0x00); }                           // pop; nop
        else { o.write(0x2c); o.write(0x17); }                                   // brfalse.s -> the walk
        o.write(0x03); o.write(0x2d); o.write(0x08);                             // ldarg.1; brtrue.s
        o.write(0x02); o.write(call(dirExists), 0, 5); o.write(0x2b); o.write(0x06);     // ldarg.0; call; br.s
        o.write(0x02); o.write(call(fileExists), 0, 5);                           // ldarg.0; call
        o.write(0x2d); o.write(0x02);                                            // brtrue.s -> return path
        if (patched) { o.write(0x2b); o.write(0x02); }                           // br.s -> the walk
        else { o.write(0x14); o.write(0x2a); }                                   // ldnull; ret
        o.write(0x02); o.write(0x2a);                                            // ldarg.0; ret
        return o.toByteArray();
    }

    /**
     * The engine's GPU texture encoder on or off. It writes BC blocks with compute shaders, which on a
     * GPU without BC (Mali, PowerVR) libvulkan.so cannot re-encode: the CPU never sees them (see
     * vulkan_wrapper.c, "BC on GPUs without it"). Off, TextureBlockEncoder.IsConfigured and
     * CanEncodeBc7Fast answer false ({@code ldc.i4.0; ret} at the start of each getter), and every
     * texture is encoded on the CPU, the way the engine already goes when a GPU batch fails. Its
     * EncodeLocked, the GPU encode behind Texture2D.CompressInPlace and the fast BC7 path, does not ask
     * those (13 textures still came from the GPU on a test, 2026-10-15): it answers null
     * ({@code ldnull; ret}), which its callers take as "not encoded" (the fast BC7 path then encodes on
     * the CPU, an in-place compression leaves the texture uncompressed). On again, the first bytes come
     * back from Waylith.dll.rdorig (the platform patches leave these methods alone, so the bytes there
     * are the originals).
     */
    static void gpuTextureEncoder(File gameDir, boolean on) {
        File f = new File(gameDir, "Waylith.dll"), orig = new File(gameDir, "Waylith.dll.rdorig");
        if (!f.isFile()) return;
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            byte[] o = orig.isFile() ? Files.readAllBytes(orig.toPath()) : null;
            Meta m = new Meta(b);
            int changed = 0;
            String[] methods = { "get_IsConfigured", "get_CanEncodeBc7Fast", "EncodeLocked" };
            byte[][] off = { { 0x16, 0x2a }, { 0x16, 0x2a }, { 0x14, 0x2a } };   // ldc.i4.0 / ldnull; ret
            for (int i = 0; i < methods.length; i++) {
                int code = m.methodCode("Waylith.Rendering", "TextureBlockEncoder", methods[i]);
                if (code < 0) { Log.w(TAG, "Waylith.dll: TextureBlockEncoder." + methods[i] + " not found"); continue; }
                byte[] want = on ? (o != null && o.length == b.length ? new byte[] { o[code], o[code + 1] } : null) : off[i];
                if (want == null || (b[code] == want[0] && b[code + 1] == want[1])) continue;
                b[code] = want[0];
                b[code + 1] = want[1];
                changed++;
            }
            if (changed == 0) return;
            if (!orig.exists()) Files.copy(f.toPath(), orig.toPath());
            Files.write(f.toPath(), b);
            Log.i(TAG, "Waylith.dll: GPU texture encoder " + (on ? "back on" : "off (textures encoded on the CPU)"));
        } catch (Exception e) {
            Log.w(TAG, "cannot switch the GPU texture encoder in Waylith.dll: " + e);
        }
    }

    /** The game's own logic; mods reach into it (see {@link #laxGameAssembly}). */
    static final String GAME_ASSEMBLY = "Assembly-CSharp.dll";

    /**
     * {@link #laxMetadata} and {@link #skipFailedModAssemblies} on the game's assembly, built from the
     * original (.rdorig) every time, so what the file holds is exactly what this build of the patcher
     * makes of it: a test build of 2026-10-14 also made the game's members public, which broke mods
     * that look them up as non-public (VEF's Mod Settings Framework: GetNestedType(NonPublic) found
     * nothing), and a rebuild drops it.
     */
    static void laxGameAssembly(File gameDir) {
        File f = new File(gameDir, GAME_ASSEMBLY);
        if (!f.isFile()) return;
        try {
            File orig = new File(f.getPath() + ".rdorig");
            byte[] current = Files.readAllBytes(f.toPath());
            byte[] b = orig.isFile() ? Files.readAllBytes(orig.toPath()) : current.clone();
            DotnetEngine.retargetX64(b);   // as retargetX64Assemblies left the file, if it had to
            int flags = laxMetadata(b), loops = skipFailedModAssemblies(b);
            if (java.util.Arrays.equals(b, current)) return;
            if (!orig.exists()) Files.copy(f.toPath(), orig.toPath());
            Files.write(f.toPath(), b);
            Log.i(TAG, GAME_ASSEMBLY + " rebuilt from the original: " + flags + " static readonly fields made writable, "
                    + loops + " mod assembly loop(s) made to go past a failed file");
        } catch (Exception e) {
            Log.w(TAG, "cannot relax " + GAME_ASSEMBLY + ": " + e);
        }
    }

    /** {@link #laxMetadata} and {@link AccessChecks#grant} on a mod's assembly; false if it is not one. */
    static boolean laxModAssembly(File f) {
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            int changed = laxMetadata(b);
            byte[] granted = AccessChecks.grant(b);
            byte[] inner = AccessChecks.grantEmbedded(granted != null ? granted : b);   // assemblies inside it
            if (inner != null) granted = inner;
            if (changed > 0 || granted != null) {
                Files.write(f.toPath(), granted != null ? granted : b);
                Log.i(TAG, f.getPath() + ": " + changed + " static readonly fields made writable"
                        + (granted != null ? "; may reach the game's private members" : "")
                        + (inner != null ? " (embedded assemblies too)" : ""));
            }
            return true;
        } catch (Exception e) {
            return false;   // a native DLL, or not an assembly at all
        }
    }

    /**
     * WaylithClr.AllowInitOnlyStaticWrites, a switch of the game's own runtime that stock .NET lacks,
     * done in an assembly's metadata instead (flag bits only, nothing moves): a static readonly field
     * may be set by reflection after its class is initialized, as Mono allowed. RimWorld fills [DefOf]
     * classes that way, and one a mod had declared readonly (RimHUD) failed the whole load, so the game
     * turned every mod off ("recovered from errors", 2026-10-14). Static fields lose InitOnly (not
     * constants or data-backed ones). Visibility stays as it is: making members public for mods built
     * against publicized assemblies (WaylithClr.SkipAccessChecks) broke the many mods that find
     * members by reflection with BindingFlags.NonPublic. Returns how many fields changed; a second run
     * changes none.
     */
    static int laxMetadata(byte[] b) {
        Meta m = new Meta(b);
        int n = 0;
        for (int r = 0; r < m.rows[0x04]; r++) {
            int o = m.fieldStart + r * m.fieldSize;
            int f = m.u16(o);
            if ((f & 0x0010) == 0 || (f & 0x0140) != 0 || (f & 0x0020) == 0) continue;   // static InitOnly, not Literal/HasFieldRVA
            m.put16(o, f & ~0x0020);
            n++;
        }
        return n;
    }

    /**
     * RimWorld's ModAssemblyHandler.ReloadAll gives up on the rest of a mod's assemblies when one of
     * them fails to load (a {@code break} in its catch). Under Mono a second copy of a library another
     * mod had already loaded (an old 0Harmony.dll bundled with a mod, 0MultiplayerAPI.dll) loaded side
     * by side; .NET refuses it ("Assembly with same name is already loaded"; the game's own runtime has
     * WaylithClr.LaxAssemblyBinding for it), so whatever came after it in that mod was never loaded:
     * Processor Framework's own assembly, then Medieval Overhaul which needs it failed the whole load
     * and the game turned every mod off (2026-10-14). The catch's {@code leave.s} to the end of the
     * loop becomes a {@code leave.s} to the loop's next step: the failed file is logged and skipped,
     * the rest of the mod loads. Found by shape, not by tokens of this build:
     * {@code call Assembly::LoadFrom; stloc; leave.s AFTER}, the catch ending in {@code leave.s} right
     * before AFTER, and {@code ldloc; callvirt IEnumerator::MoveNext} after it. A second run changes
     * nothing.
     */
    static int skipFailedModAssemblies(byte[] b) {
        Meta m = new Meta(b);
        List<Integer> loadFrom = new ArrayList<>(), moveNext = new ArrayList<>();
        for (int row = 1; row <= m.memberRefs; row++) {
            String type = m.memberRefParentTypeName(row), name = m.memberRefName(row);
            if (type == null) continue;
            if (type.equals("Assembly") && name.equals("LoadFrom")) loadFrom.add(0x0A000000 | row);
            else if (type.equals("IEnumerator") && name.equals("MoveNext")) moveNext.add(0x0A000000 | row);
        }
        int n = 0;
        for (int token : loadFrom) {
            byte[] call = call(token);
            for (int i = indexOf(b, call, 0); i >= 0; i = indexOf(b, call, i + 1)) {
                int j = i + 5, st = b[j] & 0xff;
                if (st >= 0x0a && st <= 0x0d) j += 1;                      // stloc.0-3
                else if (st == 0x13) j += 2;                                // stloc.s
                else continue;
                if ((b[j] & 0xff) != 0xde) continue;                        // leave.s past the catch
                int after = j + 2 + b[j + 1];
                int end = after - 2;                                         // the catch's own leave.s
                if (end <= j || (b[end] & 0xff) != 0xde) continue;
                int next = -1;
                for (int k = after; k < after + 64 && k + 5 <= b.length; k++) {
                    if ((b[k] & 0xff) != 0x6f || !moveNext.contains(m.u32(k + 1))) continue;
                    int ld = b[k - 1] & 0xff;
                    if (ld >= 0x06 && ld <= 0x09) next = k - 1;             // ldloc.0-3 of the enumerator
                    break;
                }
                if (next < 0 || next - after > 127 || after + b[end + 1] == next) continue;
                b[end + 1] = (byte) (next - after);
                n++;
            }
        }
        return n;
    }

    private static int indexOf(byte[] b, byte[] pattern, int from) {
        outer:
        for (int i = from; i + pattern.length <= b.length; i++) {
            for (int j = 0; j < pattern.length; j++) if (b[i + j] != pattern[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] call(int token) {
        return new byte[] { 0x28, (byte) token, (byte) (token >> 8), (byte) (token >> 16), (byte) (token >> 24) };
    }

    /** ldc.i4.1 (true) followed by nops, {@code len} bytes. */
    private static byte[] trueOf(int len) {
        byte[] r = new byte[len];
        r[0] = 0x17;
        return r;
    }

    /** Every occurrence of {@code pattern} becomes {@code with} (same length). */
    private static int replace(byte[] b, byte[] pattern, byte[] with) {
        int n = 0, len = pattern.length;
        outer:
        for (int i = 0; i + len <= b.length; i++) {
            for (int j = 0; j < len; j++) if (b[i + j] != pattern[j]) continue outer;
            System.arraycopy(with, 0, b, i, len);
            n++;
            i += len - 1;
        }
        return n;
    }

    /**
     * Just enough of ECMA-335 metadata to name MemberRef rows and their parent TypeRefs, and to reach
     * the flags of Field rows.
     */
    private static final class Meta {
        final byte[] b;
        int strings, tablesRow0;
        int si, gi, bi;
        final int[] rows = new int[64];
        int memberRefs, memberRefStart, typeRefStart, typeRefSize, memberRefSize, memberRefParent, resolutionScope;
        int fieldStart, fieldSize;
        int typeDefStart, typeDefSize, methodStart, methodSize, typeDefOrRefSize;
        int pe, sections, optSize;

        Meta(byte[] b) {
            this.b = b;
            pe = u32(0x3c);
            sections = u16(pe + 6);
            optSize = u16(pe + 20);
            int opt = pe + 24;
            boolean pe32plus = u16(opt) == 0x20b;
            int cliRva = u32(opt + (pe32plus ? 112 : 96) + 14 * 8);
            int cli = rva(cliRva, pe, sections, optSize);
            int md = rva(u32(cli + 8), pe, sections, optSize);
            int vlen = u32(md + 12);
            int p = md + 16 + vlen + 2;
            int streams = u16(p); p += 2;
            int tables = -1;
            for (int i = 0; i < streams; i++) {
                int off = u32(p), q = p + 8;
                int e = q; while (b[e] != 0) e++;
                String name = new String(b, q, e - q, StandardCharsets.US_ASCII);
                p = q + ((e - q + 4) & ~3);
                if (name.equals("#Strings")) strings = md + off;
                else if (name.equals("#~") || name.equals("#-")) tables = md + off;
            }
            int heap = b[tables + 6] & 0xff;
            si = (heap & 1) != 0 ? 4 : 2; gi = (heap & 2) != 0 ? 4 : 2; bi = (heap & 4) != 0 ? 4 : 2;
            long valid = u64(tables + 8);
            p = tables + 24;
            for (int i = 0; i < 64; i++) if ((valid >> i & 1) != 0) { rows[i] = u32(p); p += 4; }
            if ((heap & 0x40) != 0) p += 4;   // "extra data" after the row counts (some obfuscators)
            tablesRow0 = p;
            resolutionScope = coded(new int[] { 0x00, 0x1a, 0x23, 0x01 }, 2);
            int typeDefOrRef = coded(new int[] { 0x02, 0x01, 0x1b }, 2);
            typeDefOrRefSize = typeDefOrRef;
            memberRefParent = coded(new int[] { 0x02, 0x01, 0x1a, 0x06, 0x1b }, 3);
            int[] size = new int[0x0b];
            size[0x00] = 2 + si + gi * 3;
            size[0x01] = resolutionScope + si * 2;
            size[0x02] = 4 + si * 2 + typeDefOrRef + simple(0x04) + simple(0x06);
            size[0x03] = simple(0x04);
            size[0x04] = 2 + si + bi;
            size[0x05] = simple(0x06);
            size[0x06] = 4 + 2 + 2 + si + bi + simple(0x08);
            size[0x07] = simple(0x08);
            size[0x08] = 2 + 2 + si;
            size[0x09] = simple(0x02) + typeDefOrRef;
            size[0x0a] = memberRefParent + si + bi;
            int q = tablesRow0;
            for (int t = 0; t < 0x0a; t++) {
                if (t == 0x01) typeRefStart = q;
                else if (t == 0x02) typeDefStart = q;
                else if (t == 0x04) fieldStart = q;
                else if (t == 0x06) methodStart = q;
                q += rows[t] * size[t];
            }
            memberRefStart = q;
            fieldSize = size[0x04];
            typeDefSize = size[0x02];
            methodSize = size[0x06];
            typeRefSize = size[0x01];
            memberRefSize = size[0x0a];
            memberRefs = rows[0x0a];
        }

        /** File offset of the IL code of {@code ns.type::name} (the first such method), or -1. */
        int methodCode(String ns, String type, String name) {
            int typeDefs = rows[0x02], methods = rows[0x06];
            for (int t = 1; t <= typeDefs; t++) {
                int o = typeDefStart + (t - 1) * typeDefSize;
                if (!string(idx(o + 4, si)).equals(type) || !string(idx(o + 4 + si, si)).equals(ns)) continue;
                int listAt = 4 + si * 2 + typeDefOrRefSize + simple(0x04);
                int first = idx(o + listAt, simple(0x06));
                int last = t < typeDefs ? idx(o + typeDefSize + listAt, simple(0x06)) : methods + 1;
                for (int mr = first; mr < last && mr <= methods; mr++) {
                    int mo = methodStart + (mr - 1) * methodSize;
                    if (!string(idx(mo + 8, si)).equals(name)) continue;
                    int body = rva(u32(mo), pe, sections, optSize);
                    int h = b[body] & 0xff;
                    return (h & 3) == 2 ? body + 1 : body + ((b[body + 1] & 0xff) >> 4) * 4;   // tiny or fat header
                }
            }
            return -1;
        }

        String memberRefName(int row) {
            int o = memberRefStart + (row - 1) * memberRefSize;
            return string(idx(o + memberRefParent, si));
        }

        /** Name of the TypeRef that owns the MemberRef, or null for other parents. */
        String memberRefParentTypeName(int row) {
            int o = memberRefStart + (row - 1) * memberRefSize;
            int coded = idx(o, memberRefParent);
            if ((coded & 7) != 1) return null;
            int t = coded >> 3;
            if (t < 1 || t > rows[0x01]) return null;
            return string(idx(typeRefStart + (t - 1) * typeRefSize + resolutionScope, si));
        }

        private int coded(int[] tables, int bits) {
            int max = 0;
            for (int t : tables) max = Math.max(max, rows[t]);
            return max >= (1 << (16 - bits)) ? 4 : 2;
        }

        private int simple(int t) { return rows[t] >= 65536 ? 4 : 2; }
        private int idx(int o, int size) { return size == 4 ? u32(o) : u16(o); }

        private String string(int off) {
            int s = strings + off, e = s;
            while (b[e] != 0) e++;
            return new String(b, s, e - s, StandardCharsets.UTF_8);
        }

        private int rva(int rva, int pe, int sections, int optSize) {
            int so = pe + 24 + optSize;
            for (int i = 0; i < sections; i++) {
                int s = so + i * 40;
                int vs = u32(s + 8), va = u32(s + 12), rs = u32(s + 16), raw = u32(s + 20);
                if (rva >= va && rva < va + Math.max(vs, rs)) return rva - va + raw;
            }
            throw new IllegalStateException("rva " + rva);
        }

        private int u16(int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
        private void put16(int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
        private int u32(int o) { return u16(o) | u16(o + 2) << 16; }
        private long u64(int o) { return (u32(o) & 0xffffffffL) | ((long) u32(o + 4)) << 32; }
    }
}
