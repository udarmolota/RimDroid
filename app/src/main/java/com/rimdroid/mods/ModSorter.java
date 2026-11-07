package com.rimdroid.mods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Load-order sort from the rules mod authors declare in About.xml — the same rules RimWorld's own
 * auto-sort and RimSort start from: modDependencies (the dependency loads first), loadBefore /
 * loadAfter and their force- and ByVersion variants. On top of those come the rules players
 * collected for mods that do not declare them ({@link CommunityRules}, RimSort's database): more
 * loadBefore / loadAfter / incompatibleWith, and "load as early / as late as possible" (performance
 * mods such as RocketMan go last). Core and the DLC come right after anything that asks to load
 * before them (Harmony does), in their release order, ahead of every other mod.
 *
 * The sort is stable: among mods free to go next it always takes the one that stood earliest, so a
 * mod moves only when a rule makes it, and a list that is already right comes back unchanged.
 * "Early" and "late" mods are only preferred among the ready ones, so they never contradict a rule.
 * The database contradicts itself in places (XML Extensions, HugsLib and Vanilla Expanded Framework
 * form a loop), so a collected rule is skipped when it would contradict the rules already taken —
 * the authors' own rules always win and only their contradictions are reported.
 * Contradicting rules (A before B, B before A) cannot all hold: they are reported, and the mod that
 * stood earliest goes first. Rules naming mods that are not active are ignored for the order.
 */
public final class ModSorter {
    static final String[] OFFICIAL = { ModList.CORE, "ludeon.rimworld.royalty", "ludeon.rimworld.ideology",
            "ludeon.rimworld.biotech", "ludeon.rimworld.anomaly", "ludeon.rimworld.odyssey" };

    public static final class Result {
        public final List<String> order = new ArrayList<>();
        public int moved;
        /** Required but not installed: "Name (Workshop id)". */
        public final List<ModList.Dependency> missing = new ArrayList<>();
        /** Required, installed, but switched off: their mods. */
        public final List<ModList.Mod> inactive = new ArrayList<>();
        /** Mods whose rules contradict each other. */
        public final List<String> conflicts = new ArrayList<>();
        /** "A ↔ B": both on, one says it is incompatible with the other. */
        public final List<String> incompatible = new ArrayList<>();
    }

    public static Result sort(List<String> current, Map<String, ModList.Mod> installed,
                              Map<String, CommunityRules.Rule> community) {
        Result r = new Result();
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(current));
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) pos.put(ids.get(i), i);

        Map<String, Set<String>> after = new HashMap<>();   // id -> ids that must come after it
        Map<String, Integer> incoming = new HashMap<>();
        for (String id : ids) { after.put(id, new LinkedHashSet<>()); incoming.put(id, 0); }

        Set<String> beforeOfficial = new HashSet<>();   // mods that ask to load before Core or a DLC
        for (String id : ids) {
            ModList.Mod m = installed.get(id);
            if (m == null) continue;
            for (String b : m.loadBefore) {
                edge(id, b, after, incoming);
                for (String o : OFFICIAL) if (o.equals(b)) beforeOfficial.add(id);
            }
            for (String a : m.loadAfter) edge(a, id, after, incoming);
            for (ModList.Dependency d : m.dependencies) edge(d.packageId, id, after, incoming);
        }
        Map<String, Integer> tier = new HashMap<>();   // 0 as early as possible, 1 normal, 2 as late as possible
        for (String id : ids) {
            CommunityRules.Rule c = community.get(id);
            tier.put(id, c == null ? 1 : c.loadBottom ? 2 : c.loadTop ? 0 : 1);
            if (c == null || installed.get(id) != null && installed.get(id).official) continue;
            for (String b : c.loadBefore) for (String o : OFFICIAL) if (o.equals(b)) beforeOfficial.add(id);
        }
        // Core, then the DLC in release order, ahead of every other mod not asking otherwise.
        String prev = null;
        for (String o : OFFICIAL) {
            if (!pos.containsKey(o)) continue;
            if (prev != null) edge(prev, o, after, incoming);
            prev = o;
        }
        if (prev != null) {
            for (String id : ids) {
                ModList.Mod m = installed.get(id);
                boolean official = m != null && m.official;
                if (!official && !beforeOfficial.contains(id) && !isOfficialId(id)) edge(prev, id, after, incoming);
            }
        }

        // The collected rules last, each only if it keeps the order possible.
        for (String id : ids) {
            CommunityRules.Rule c = community.get(id);
            if (c == null) continue;
            for (String b : c.loadBefore) softEdge(id, b, after, incoming);
            for (String a : c.loadAfter) softEdge(a, id, after, incoming);
        }

        // Kahn's algorithm, always taking the earliest-standing ready mod of the earliest tier.
        PriorityQueue<String> ready = new PriorityQueue<>((a, b) -> {
            int t = tier.get(a) - tier.get(b);
            return t != 0 ? t : pos.get(a) - pos.get(b);
        });
        for (String id : ids) if (incoming.get(id) == 0) ready.add(id);
        Set<String> placed = new HashSet<>();
        while (placed.size() < ids.size()) {
            if (ready.isEmpty()) {
                // A cycle: report what is left, place the earliest of it to break it.
                String first = null;
                for (String id : ids) if (!placed.contains(id) && (first == null || pos.get(id) < pos.get(first))) first = id;
                if (r.conflicts.isEmpty()) for (String id : ids) if (!placed.contains(id) && incoming.get(id) > 0) r.conflicts.add(id);
                incoming.put(first, 0);
                ready.add(first);
            }
            String id = ready.poll();
            if (placed.contains(id)) continue;
            placed.add(id);
            r.order.add(id);
            for (String next : after.get(id)) {
                if (placed.contains(next)) continue;
                int n = incoming.get(next) - 1;
                incoming.put(next, n);
                if (n == 0) ready.add(next);
            }
        }
        for (int i = 0; i < r.order.size(); i++) if (!r.order.get(i).equals(ids.get(i))) r.moved++;

        // Warnings: what the active mods need, and incompatible pairs.
        Set<String> active = new HashSet<>(ids), seenMissing = new HashSet<>(), seenInactive = new HashSet<>();
        Set<String> seenPairs = new HashSet<>();
        for (String id : ids) {
            ModList.Mod m = installed.get(id);
            if (m == null) continue;
            for (ModList.Dependency d : m.dependencies) {
                if (active.contains(d.packageId)) continue;
                ModList.Mod dep = installed.get(d.packageId);
                if (dep == null) { if (seenMissing.add(d.packageId)) r.missing.add(d); }
                else if (seenInactive.add(d.packageId)) r.inactive.add(dep);
            }
            for (String other : m.incompatibleWith) incompatible(m, other, installed, active, seenPairs, r);
        }
        for (String id : ids) {
            ModList.Mod m = installed.get(id);
            CommunityRules.Rule c = community.get(id);
            if (m == null || c == null) continue;
            for (String other : c.incompatibleWith) incompatible(m, other, installed, active, seenPairs, r);
        }
        return r;
    }

    /** Reports "m \u2194 other" once per pair, when both are on. */
    private static void incompatible(ModList.Mod m, String other, Map<String, ModList.Mod> installed,
                                     Set<String> active, Set<String> seenPairs, Result r) {
        if (!active.contains(other)) return;
        String a = m.packageId, b = other;
        if (!seenPairs.add(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a)) return;
        ModList.Mod o = installed.get(other);
        r.incompatible.add(m.name + " \u2194 " + (o != null ? o.name : other));
    }

    private static boolean isOfficialId(String id) {
        for (String o : OFFICIAL) if (o.equals(id)) return true;
        return false;
    }

    /** {@link #edge}, unless second already has to load before first. */
    private static void softEdge(String first, String second, Map<String, Set<String>> after, Map<String, Integer> incoming) {
        if (first.equals(second) || !after.containsKey(first) || !after.containsKey(second)) return;
        if (!reaches(second, first, after)) edge(first, second, after, incoming);
    }

    /** True when "from" must load before "to" through the rules so far. */
    private static boolean reaches(String from, String to, Map<String, Set<String>> after) {
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        stack.push(from);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (id.equals(to)) return true;
            if (seen.add(id)) for (String next : after.get(id)) if (!seen.contains(next)) stack.push(next);
        }
        return false;
    }

    /** "first loads before second", when both are in the list and the edge is new. */
    private static void edge(String first, String second, Map<String, Set<String>> after, Map<String, Integer> incoming) {
        if (first.equals(second) || !after.containsKey(first) || !after.containsKey(second)) return;
        if (after.get(first).add(second)) incoming.put(second, incoming.get(second) + 1);
    }

    private ModSorter() {}
}
