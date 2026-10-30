package com.rimdroid.game;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * WaylithClr.SkipAccessChecks for mods on RimWorld 1.7. Mods built against "publicized" game
 * assemblies reach private fields and methods of the game directly; Mono never checked, the game's
 * own runtime has that switch, stock .NET throws FieldAccessException / MethodAccessException (VEF's
 * Mod Settings Framework reading ModContentPack.patches failed the whole load, 2026-10-14). Stock
 * .NET does honour {@code [assembly: IgnoresAccessChecksTo("Name")]} on the assembly that reaches in,
 * matched by the attribute's name, so each mod assembly gets one for Assembly-CSharp and for every
 * other non-framework assembly it references. The attribute type itself lives in our startup hook
 * (RimDroidClrHooks): the access check needs only the name, but code that reads an assembly's
 * attributes resolves the type.
 *
 * Changing visibility in the game instead was tried and broke the many mods that look members up by
 * reflection with BindingFlags.NonPublic.
 *
 * The attribute needs new metadata rows (an AssemblyRef to the hooks, a TypeRef, a MemberRef for its
 * constructor, a CustomAttribute row per name) and heap entries, so the metadata is rebuilt with them and placed at
 * the end of the file's last section; the CLI header points to the new copy and nothing else moves
 * (IL, resources and the old metadata stay where they were, tokens keep their numbers). Anything
 * unusual (uncompressed tables, an index that would have to grow, data after the last section) leaves
 * the file as it is.
 */
final class AccessChecks {
    static final String ATTRIBUTE = "IgnoresAccessChecksToAttribute";
    static final String NAMESPACE = "System.Runtime.CompilerServices";
    static final String GAME = "Assembly-CSharp";
    /**
     * The framework assemblies whose private members mods reach (List<T>._items, XmlElement's fields...):
     * the game's runtime skips access checks for the framework too (Adaptive Storage's Fishery reading
     * List<T> internals failed, 2026-10-25). The first one also marks a grant that already has them.
     */
    static final String[] FRAMEWORK = { "System.Private.CoreLib", "System.Private.Xml", "System.Private.Xml.Linq",
            "System.Collections", "System.Linq", "System.Linq.Expressions" };
    /** Defines the attribute (tools/rimworld17/clrhooks/IgnoresAccessChecksTo.cs); loaded at startup. */
    static final String HOOKS = "RimDroidClrHooks";

    private static final int[] TYPE_DEF_OR_REF = { 0x02, 0x01, 0x1b };
    private static final int[] HAS_CONSTANT = { 0x04, 0x08, 0x17 };
    private static final int[] HAS_CUSTOM_ATTRIBUTE = { 0x06, 0x04, 0x01, 0x02, 0x08, 0x09, 0x0a, 0x00, 0x0e,
            0x17, 0x14, 0x11, 0x1a, 0x1b, 0x20, 0x23, 0x26, 0x27, 0x28, 0x2a, 0x2c, 0x2b };
    private static final int[] HAS_FIELD_MARSHAL = { 0x04, 0x08 };
    private static final int[] HAS_DECL_SECURITY = { 0x02, 0x06, 0x20 };
    private static final int[] MEMBER_REF_PARENT = { 0x02, 0x01, 0x1a, 0x06, 0x1b };
    private static final int[] HAS_SEMANTICS = { 0x14, 0x17 };
    private static final int[] METHOD_DEF_OR_REF = { 0x06, 0x0a };
    private static final int[] MEMBER_FORWARDED = { 0x04, 0x06 };
    private static final int[] IMPLEMENTATION = { 0x26, 0x23, 0x27 };
    private static final int[] CUSTOM_ATTRIBUTE_TYPE = { 0x06, 0x0a };   // tags 2 and 3 of 3 bits
    private static final int[] RESOLUTION_SCOPE = { 0x00, 0x1a, 0x23, 0x01 };
    private static final int[] TYPE_OR_METHOD_DEF = { 0x02, 0x06 };
    private static final int ASSEMBLY_PARENT = (1 << 5) | 14;            // HasCustomAttribute: Assembly row 1

    private final byte[] b;
    private int opt, sectionTable, sections, cli, md;
    private final List<String> streamNames = new ArrayList<>();
    private final List<int[]> streams = new ArrayList<>();               // {file offset, size}
    private int tables, strings, stringsSize, blob, blobSize, heapSizes, tableData, si, bi;
    private final int[] rows = new int[64];
    private int[] size;
    private final int[] start = new int[64];

    private AccessChecks(byte[] b) { this.b = b; }

    /** The assembly with the attributes added, or null if it has them already or cannot take them. */
    static byte[] grant(byte[] image) {
        try {
            return new AccessChecks(image).run();
        } catch (RuntimeException e) {
            return null;   // not an assembly, or not one laid out the usual way: left alone
        }
    }

    private byte[] run() {
        parse();
        if (rows[0x20] != 1 || rows[0x23] == 0 || rows[0x01] == 0 || rows[0x0a] == 0 || rows[0x0c] == 0)
            return null;

        // Done already: the attribute's TypeRef, pointing to the hooks assembly that defines it. A first
        // build of this patcher pointed it to the framework, where no such type exists: whatever read
        // the assembly's attributes failed (Harmony's mod could not tell its version, 2026-10-14). Such
        // a file still holds its original metadata, untouched, ahead of ours: the grant is redone on it.
        int scopeCol = width(RESOLUTION_SCOPE, 2, rows);
        for (int r = 0; r < rows[0x01]; r++) {
            int o = start[0x01] + r * size[0x01];
            if (!ATTRIBUTE.equals(string(idx(o + scopeCol, si))) || !NAMESPACE.equals(string(idx(o + scopeCol + si, si))))
                continue;
            int scope = idx(o, scopeCol);
            if ((scope & 3) == 2 && HOOKS.equals(assemblyRefName(scope >> 2)) && hasBlobString(FRAMEWORK[0])) return null;
            return regrant();   // made before the framework names were granted: redone on the original
        }

        // The names to grant, and the hooks assembly's AssemblyRef (added if missing).
        Set<String> targets = new LinkedHashSet<>();
        targets.add(GAME);
        java.util.Collections.addAll(targets, FRAMEWORK);
        boolean unity = false;
        int hooks = 0;
        for (int r = 1; r <= rows[0x23]; r++) {
            String name = assemblyRefName(r);
            String low = name.toLowerCase(Locale.ROOT);
            if (name.equals(HOOKS)) { hooks = r; continue; }
            if (low.equals("mscorlib") || low.equals("netstandard") || low.startsWith("system")
                    || low.startsWith("microsoft.")) continue;
            if (low.startsWith("unityengine") || low.startsWith("unity.")) { unity = true; continue; }
            targets.add(name);
        }
        if (unity) targets.add("UnityStubs");
        boolean newHooksRef = hooks == 0;
        int scope = newHooksRef ? rows[0x23] + 1 : hooks;

        // Heaps: the strings and blobs the new rows point to, after what is there.
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(b, strings, stringsSize);
        int nameOff = append(s, ATTRIBUTE), nsOff = append(s, NAMESPACE);
        int hooksOff = newHooksRef ? append(s, HOOKS) : 0;
        int ctorOff = findString(".ctor");
        if (ctorOff < 0) ctorOff = append(s, ".ctor");
        pad(s);
        ByteArrayOutputStream bl = new ByteArrayOutputStream();
        bl.write(b, blob, blobSize);
        int sigOff = appendBlob(bl, new byte[] { 0x20, 0x01, 0x01, 0x0e });   // instance void (string)
        List<Integer> valueOffs = new ArrayList<>();
        for (String target : targets) {
            byte[] utf8 = target.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream v = new ByteArrayOutputStream();
            v.write(1); v.write(0);                                        // prolog
            compressed(v, utf8.length); v.write(utf8, 0, utf8.length);     // the name, a SerString
            v.write(0); v.write(0);                                        // no named arguments
            valueOffs.add(appendBlob(bl, v.toByteArray()));
        }
        pad(bl);
        if (si == 2 && s.size() > 0xffff || bi == 2 && bl.size() > 0xffff) return null;   // an index would grow

        int[] newRows = rows.clone();
        if (newHooksRef) newRows[0x23]++;
        newRows[0x01]++;
        newRows[0x0a]++;
        newRows[0x0c] += targets.size();
        if (!Arrays.equals(size, rowSizes(newRows, si, (heapSizes & 2) != 0 ? 4 : 2, bi))) return null;
        int typeRef = rows[0x01] + 1, memberRef = rows[0x0a] + 1;

        // The tables: AssemblyRef, TypeRef and MemberRef rows at their tables' ends, so every token
        // keeps its number; CustomAttribute rows where the order by parent puts them.
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write(b, tables, 24);
        for (int i = 0; i < 64; i++) if (rows[i] > 0) le(t, newRows[i], 4);
        for (int i = 0; i < 64; i++) {
            if (rows[i] == 0) continue;
            int from = start[i], len = rows[i] * size[i];
            if (i == 0x0c) {
                int hasCa = width(HAS_CUSTOM_ATTRIBUTE, 5, rows), at = rows[i];
                for (int r = 0; r < rows[i]; r++)
                    if (idx(from + r * size[i], hasCa) > ASSEMBLY_PARENT) { at = r; break; }
                t.write(b, from, at * size[i]);
                for (int value : valueOffs) {
                    le(t, ASSEMBLY_PARENT, hasCa);
                    le(t, memberRef << 3 | 3, width(CUSTOM_ATTRIBUTE_TYPE, 3, rows));
                    le(t, value, bi);
                }
                t.write(b, from + at * size[i], len - at * size[i]);
                continue;
            }
            t.write(b, from, len);
            if (i == 0x01) {
                le(t, scope << 2 | 2, width(RESOLUTION_SCOPE, 2, rows));      // AssemblyRef
                le(t, nameOff, si);
                le(t, nsOff, si);
            } else if (i == 0x0a) {
                le(t, typeRef << 3 | 1, width(MEMBER_REF_PARENT, 3, rows));  // TypeRef
                le(t, ctorOff, si);
                le(t, sigOff, bi);
            } else if (i == 0x23 && newHooksRef) {
                for (int k = 0; k < 12; k++) t.write(0);                    // version 0.0.0.0, flags
                le(t, 0, bi);                                                // no public key
                le(t, hooksOff, si);
                le(t, 0, si);                                                // neutral culture
                le(t, 0, bi);                                                // no hash
            }
        }
        pad(t);

        // The metadata root with the three new streams in place of the old ones.
        int vlen = u32(md + 12);
        int prefix = 16 + vlen + 2;
        int headers = prefix + 2;
        for (String name : streamNames) headers += 8 + ((name.length() + 4) & ~3);
        ByteArrayOutputStream root = new ByteArrayOutputStream();
        root.write(b, md, prefix);
        le(root, streamNames.size(), 2);
        byte[][] data = new byte[streamNames.size()][];
        for (int i = 0; i < data.length; i++) {
            String name = streamNames.get(i);
            data[i] = name.equals("#~") ? t.toByteArray() : name.equals("#Strings") ? s.toByteArray()
                    : name.equals("#Blob") ? bl.toByteArray()
                    : Arrays.copyOfRange(b, streams.get(i)[0], streams.get(i)[0] + streams.get(i)[1]);
        }
        int off = (headers + 3) & ~3;
        for (int i = 0; i < data.length; i++) {
            le(root, off, 4);
            le(root, data[i].length, 4);
            byte[] name = streamNames.get(i).getBytes(StandardCharsets.US_ASCII);
            root.write(name, 0, name.length);
            for (int k = name.length; k < ((name.length + 4) & ~3); k++) root.write(0);
            off += (data[i].length + 3) & ~3;
        }
        while (root.size() < ((headers + 3) & ~3)) root.write(0);
        for (byte[] d : data) { root.write(d, 0, d.length); while ((root.size() & 3) != 0) root.write(0); }
        byte[] newMd = root.toByteArray();

        int[] where = new int[1];
        byte[] out = appendToLastSection(newMd, where);
        if (out == null) return null;
        put32(out, cli + 8, where[0]);
        put32(out, cli + 12, newMd.length);
        return out;
    }

    /** {@code data} at the end of the last section (16-aligned); its RVA in where[0]. Null if the
     *  file is not laid out for it (the last section not last in memory, or data after it). */
    private byte[] appendToLastSection(byte[] data, int[] where) {
        int last = -1;
        for (int i = 0; i < sections; i++) {
            int sh = sectionTable + i * 40;
            if (last < 0 || u32(sh + 20) > u32(last + 20)) last = sh;
        }
        for (int i = 0; i < sections; i++)
            if (u32(sectionTable + i * 40 + 12) > u32(last + 12)) return null;   // not last in memory too
        int va = u32(last + 12), vsize = u32(last + 8), raw = u32(last + 20), rawSize = u32(last + 16);
        if (raw + rawSize != b.length) return null;                             // data after it (a signature)
        int fileAlign = u32(opt + 36), sectAlign = u32(opt + 32);
        int at = (Math.max(vsize, 0) + 15) & ~15;
        int newVsize = at + data.length;
        int newRawSize = (newVsize + fileAlign - 1) / fileAlign * fileAlign;
        byte[] out = Arrays.copyOf(b, raw + newRawSize);
        System.arraycopy(data, 0, out, raw + at, data.length);
        put32(out, last + 8, newVsize);
        put32(out, last + 16, newRawSize);
        int ch = u32(last + 36);
        put32(out, last + 36, (ch | 0x40000040) & ~0x02000000);                 // read, initialized data, kept
        int image = (va + newVsize + sectAlign - 1) / sectAlign * sectAlign;
        if (image > u32(opt + 56)) put32(out, opt + 56, image);
        where[0] = va + at;
        return out;
    }

    /**
     * {@link #grant} for assemblies embedded as resources, as Brrainz's CrossPromotion keeps its real
     * code (Brrainz.CrossPromotionSteam[Deck].dll) and loads it with Assembly.Load(bytes): the outer DLL
     * had the grant, the inner one never did, and its prefix on the game's mod list failed on every
     * frame (FieldAccessException on WorkshopItemHook.steamAuthor; Camera+, Achtung and the other
     * Brrainz mods lost the right-hand panel of the in-game mod list, 2026-10-18). The resources are
     * written anew, the PE ones granted, at the end of the last section; the CLI header and the
     * ManifestResource offsets point there. Null when nothing changes.
     */
    static byte[] grantEmbedded(byte[] image) {
        try {
            return new AccessChecks(image).embedded();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private byte[] embedded() {
        parse();
        int count = rows[0x28];
        int resRva = u32(cli + 24);
        if (count == 0 || resRva == 0) return null;
        int res = offset(resRva);
        int implCol = 8 + si, implWidth = width(IMPLEMENTATION, 2, rows);
        java.util.List<int[]> list = new java.util.ArrayList<>();   // {row offset in file, data offset}
        for (int r = 0; r < count; r++) {
            int o = start[0x28] + r * size[0x28];
            if (idx(o + implCol, implWidth) != 0) continue;            // in another file
            list.add(new int[] { o, u32(o) });
        }
        list.sort((x, y) -> Integer.compare(x[1], y[1]));
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        int[] newOff = new int[list.size()];
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            int at = res + list.get(i)[1], len = u32(at);
            byte[] data = Arrays.copyOfRange(b, at + 4, at + 4 + len);
            if (len > 2 && data[0] == 'M' && data[1] == 'Z') {
                byte[] g = grant(data);
                if (g != null) { data = g; changed = true; }
            }
            newOff[i] = blob.size();
            le(blob, data.length, 4);
            blob.write(data, 0, data.length);
            while ((blob.size() & 7) != 0) blob.write(0);
        }
        if (!changed) return null;
        byte[] bytes = blob.toByteArray();
        int[] where = new int[1];
        byte[] out = appendToLastSection(bytes, where);
        if (out == null) return null;
        put32(out, cli + 24, where[0]);
        put32(out, cli + 28, bytes.length);
        for (int i = 0; i < list.size(); i++) put32(out, list.get(i)[0], newOff[i]);
        return out;
    }

    private void parse() {
        if (u16(0) != 0x5a4d) throw new IllegalStateException("not a PE file");
        int pe = u32(0x3c);
        if (u32(pe) != 0x4550) throw new IllegalStateException("not a PE file");
        sections = u16(pe + 6);
        opt = pe + 24;
        int magic = u16(opt);
        int dirs = opt + (magic == 0x20b ? 112 : magic == 0x10b ? 96 : -1);
        if (dirs < opt) throw new IllegalStateException("optional header");
        sectionTable = opt + u16(pe + 20);
        int cliRva = u32(dirs + 14 * 8);
        if (cliRva == 0) throw new IllegalStateException("not .NET");
        cli = offset(cliRva);
        md = offset(u32(cli + 8));
        if (u32(md) != 0x424a5342) throw new IllegalStateException("no metadata");
        int vlen = u32(md + 12);
        int p = md + 16 + vlen + 2;
        int n = u16(p); p += 2;
        for (int i = 0; i < n; i++) {
            int off = u32(p), size = u32(p + 4), q = p + 8, e = q;
            while (b[e] != 0) e++;
            String name = new String(b, q, e - q, StandardCharsets.US_ASCII);
            p = q + ((e - q + 4) & ~3);
            streamNames.add(name);
            streams.add(new int[] { md + off, size });
            if (name.equals("#~")) tables = md + off;
            else if (name.equals("#Strings")) { strings = md + off; stringsSize = size; }
            else if (name.equals("#Blob")) { blob = md + off; blobSize = size; }
            else if (name.equals("#-")) throw new IllegalStateException("uncompressed tables");
        }
        if (tables == 0 || strings == 0 || blob == 0) throw new IllegalStateException("streams");
        heapSizes = b[tables + 6] & 0xff;
        if ((heapSizes & 0x40) != 0) throw new IllegalStateException("extra data");
        long valid = u32(tables + 8) & 0xffffffffL | (long) u32(tables + 12) << 32;
        p = tables + 24;
        for (int i = 0; i < 64; i++) if ((valid >> i & 1) != 0) { rows[i] = u32(p); p += 4; }
        tableData = p;
        si = (heapSizes & 1) != 0 ? 4 : 2;
        bi = (heapSizes & 4) != 0 ? 4 : 2;
        size = rowSizes(rows, si, (heapSizes & 2) != 0 ? 4 : 2, bi);
        for (int t = 0, q = tableData; t < 64; t++) { start[t] = q; if (rows[t] > 0) q += rows[t] * size[t]; }
    }

    private String assemblyRefName(int row) {
        return string(idx(start[0x23] + (row - 1) * size[0x23] + 12 + bi, si));
    }

    /**
     * A file granted by the first build (TypeRef to the framework): the CLI header back on the
     * original metadata, which that build left in place ahead of its copy (the first "BSJB" in the
     * file that has no such TypeRef), then the grant again.
     */
    /** The #Blob heap holds this attribute argument (a SerString: length byte, UTF-8). */
    private boolean hasBlobString(String text) {
        byte[] t = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = blob; i + 1 + t.length <= blob + blobSize; i++) {
            if ((b[i] & 0xff) != t.length) continue;
            for (int j = 0; j < t.length; j++) if (b[i + 1 + j] != t[j]) continue outer;
            return true;
        }
        return false;
    }

    private byte[] regrant() {
        for (int at = 0; at + 4 <= b.length; at++) {
            if (at == md || b[at] != 'B' || b[at + 1] != 'S' || b[at + 2] != 'J' || b[at + 3] != 'B') continue;
            int rva = rvaOf(at);
            if (rva < 0) continue;
            byte[] c = b.clone();
            byte[] granted;
            try {
                put32(c, cli + 8, rva);
                put32(c, cli + 12, metadataLength(at));
                granted = new AccessChecks(c).run();
            } catch (RuntimeException e) {
                continue;   // not metadata after all
            }
            return granted != null ? granted : c;
        }
        return null;
    }

    private int metadataLength(int root) {
        int vlen = u32(root + 12), p = root + 16 + vlen + 2, n = u16(p), end = 0;
        p += 2;
        for (int i = 0; i < n; i++) {
            end = Math.max(end, u32(p) + u32(p + 4));
            int e = p + 8;
            while (b[e] != 0) e++;
            p = p + 8 + ((e - (p + 8) + 4) & ~3);
        }
        return end;
    }

    private int rvaOf(int fileOffset) {
        for (int i = 0; i < sections; i++) {
            int sh = sectionTable + i * 40;
            int raw = u32(sh + 20), rs = u32(sh + 16);
            if (fileOffset >= raw && fileOffset < raw + rs) return fileOffset - raw + u32(sh + 12);
        }
        return -1;
    }

    /** Row sizes of every table for these row counts and heap index widths (ECMA-335 II.22). */
    private static int[] rowSizes(int[] r, int si, int gi, int bi) {
        int tdor = width(TYPE_DEF_OR_REF, 2, r), hc = width(HAS_CONSTANT, 2, r);
        int hca = width(HAS_CUSTOM_ATTRIBUTE, 5, r), hfm = width(HAS_FIELD_MARSHAL, 1, r);
        int hds = width(HAS_DECL_SECURITY, 2, r), mrp = width(MEMBER_REF_PARENT, 3, r);
        int hs = width(HAS_SEMANTICS, 1, r), mdor = width(METHOD_DEF_OR_REF, 1, r);
        int mf = width(MEMBER_FORWARDED, 1, r), impl = width(IMPLEMENTATION, 2, r);
        int cat = width(CUSTOM_ATTRIBUTE_TYPE, 3, r), rs = width(RESOLUTION_SCOPE, 2, r);
        int tomd = width(TYPE_OR_METHOD_DEF, 1, r);
        int[] s = new int[64];
        s[0x00] = 2 + si + gi * 3;
        s[0x01] = rs + si * 2;
        s[0x02] = 4 + si * 2 + tdor + simple(0x04, r) + simple(0x06, r);
        s[0x03] = simple(0x04, r);
        s[0x04] = 2 + si + bi;
        s[0x05] = simple(0x06, r);
        s[0x06] = 4 + 2 + 2 + si + bi + simple(0x08, r);
        s[0x07] = simple(0x08, r);
        s[0x08] = 2 + 2 + si;
        s[0x09] = simple(0x02, r) + tdor;
        s[0x0a] = mrp + si + bi;
        s[0x0b] = 2 + hc + bi;
        s[0x0c] = hca + cat + bi;
        s[0x0d] = hfm + bi;
        s[0x0e] = 2 + hds + bi;
        s[0x0f] = 2 + 4 + simple(0x02, r);
        s[0x10] = 4 + simple(0x04, r);
        s[0x11] = bi;
        s[0x12] = simple(0x02, r) + simple(0x14, r);
        s[0x13] = simple(0x14, r);
        s[0x14] = 2 + si + tdor;
        s[0x15] = simple(0x02, r) + simple(0x17, r);
        s[0x16] = simple(0x17, r);
        s[0x17] = 2 + si + bi;
        s[0x18] = 2 + simple(0x06, r) + hs;
        s[0x19] = simple(0x02, r) + mdor * 2;
        s[0x1a] = si;
        s[0x1b] = bi;
        s[0x1c] = 2 + mf + si + simple(0x1a, r);
        s[0x1d] = 4 + simple(0x04, r);
        s[0x1e] = 8;
        s[0x1f] = 4;
        s[0x20] = 4 + 8 + 4 + bi + si * 2;
        s[0x21] = 4;
        s[0x22] = 12;
        s[0x23] = 8 + 4 + bi + si * 2 + bi;
        s[0x24] = 4 + simple(0x23, r);
        s[0x25] = 12 + simple(0x23, r);
        s[0x26] = 4 + si + bi;
        s[0x27] = 8 + si * 2 + impl;
        s[0x28] = 8 + si + impl;
        s[0x29] = simple(0x02, r) * 2;
        s[0x2a] = 2 + 2 + tomd + si;
        s[0x2b] = mdor + bi;
        s[0x2c] = simple(0x2a, r) + tdor;
        for (int i = 0x2d; i < 64; i++) if (r[i] > 0) throw new IllegalStateException("table " + i);
        return s;
    }

    private static int width(int[] tables, int bits, int[] r) {
        int max = 0;
        for (int t : tables) max = Math.max(max, r[t]);
        return max >= (1 << (16 - bits)) ? 4 : 2;
    }

    private static int simple(int t, int[] r) { return r[t] >= 65536 ? 4 : 2; }

    private int findString(String text) {
        byte[] want = (text + "\0").getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 1; i + want.length <= stringsSize; i++) {
            if (b[strings + i - 1] != 0) continue;   // a whole entry, not the tail of another
            for (int j = 0; j < want.length; j++) if (b[strings + i + j] != want[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static int append(ByteArrayOutputStream s, String text) {
        int at = s.size();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        s.write(bytes, 0, bytes.length);
        s.write(0);
        return at;
    }

    private static int appendBlob(ByteArrayOutputStream s, byte[] data) {
        int at = s.size();
        compressed(s, data.length);
        s.write(data, 0, data.length);
        return at;
    }

    private static void compressed(ByteArrayOutputStream s, int n) {
        if (n < 0x80) s.write(n);
        else if (n < 0x4000) { s.write(0x80 | n >> 8); s.write(n & 0xff); }
        else throw new IllegalStateException("blob too long");
    }

    private static void pad(ByteArrayOutputStream s) { while ((s.size() & 3) != 0) s.write(0); }

    private static void le(ByteArrayOutputStream s, int v, int width) {
        s.write(v); s.write(v >> 8);
        if (width == 4) { s.write(v >> 16); s.write(v >> 24); }
    }

    private int offset(int rva) {
        for (int i = 0; i < sections; i++) {
            int sh = sectionTable + i * 40;
            int vs = u32(sh + 8), va = u32(sh + 12), rs = u32(sh + 16), raw = u32(sh + 20);
            if (rva >= va && rva < va + Math.max(vs, rs)) return rva - va + raw;
        }
        throw new IllegalStateException("rva " + rva);
    }

    private String string(int off) {
        int s = strings + off, e = s;
        while (b[e] != 0) e++;
        return new String(b, s, e - s, StandardCharsets.UTF_8);
    }

    private int idx(int o, int width) { return width == 4 ? u32(o) : u16(o); }
    private int u16(int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    private int u32(int o) { return u16(o) | u16(o + 2) << 16; }
    private static void put32(byte[] a, int o, int v) {
        a[o] = (byte) v; a[o + 1] = (byte) (v >> 8); a[o + 2] = (byte) (v >> 16); a[o + 3] = (byte) (v >> 24);
    }
}
