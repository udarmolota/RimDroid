// Harmony's add-on for Ludeon's own .NET, kept out quietly (2026-11-19).
//
// The Harmony mod for RimWorld 1.7 (3.0.4) ships HarmonyMod.RuntimeIL, which patches through the
// method-body API of Ludeon's modified .NET (WaylithClr.MethodBodyTransformer and friends inside
// System.Private.CoreLib). On stock .NET those types are not in CoreLib: our stand-ins are forwarded
// from System.Runtime, which this assembly does not reference, so it cannot load. RimWorld then logs a
// red ReflectionTypeLoadException at every start and opens the log window, though nothing is lost:
// stock .NET could not run that path anyway, and Harmony patches the usual way without it.
//
// When 0Harmony loads, ModAssemblyHandler.AssemblyIsUsable gets a prefix that answers "not usable"
// for that one assembly without looking at its types, so it is skipped silently. Every other
// assembly goes through the game's own check.
using System;
using System.Reflection;

public static class RimDroidSkipRuntimeIL
{
    const string Skipped = "HarmonyMod.RuntimeIL";
    static bool patched, logged;
    static Action<int, string> log;

    internal static void Install(Assembly harmony, Action<int, string> logger)
    {
        if (patched) return;
        log = logger;
        try
        {
            Type handler = null;
            foreach (Assembly a in AppDomain.CurrentDomain.GetAssemblies())
            {
                string n = a.GetName().Name;
                if (n != "Assembly-CSharp" && n != "RimWorld") continue;
                handler = a.GetType("Verse.ModAssemblyHandler", false);
                if (handler != null) break;
            }
            MethodInfo usable = handler?.GetMethod("AssemblyIsUsable",
                BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static | BindingFlags.Instance);
            if (usable == null || usable.ReturnType != typeof(bool)) { log(5, "skip RuntimeIL: AssemblyIsUsable not found"); return; }
            Type harmonyType = harmony.GetType("HarmonyLib.Harmony");
            Type harmonyMethod = harmony.GetType("HarmonyLib.HarmonyMethod");
            MethodInfo patch = null;
            foreach (MethodInfo m in harmonyType.GetMethods(BindingFlags.Public | BindingFlags.Instance))
            {
                ParameterInfo[] p = m.GetParameters();
                if (m.Name == "Patch" && p.Length >= 3 && p[0].ParameterType == typeof(MethodBase)) { patch = m; break; }
            }
            if (patch == null) { log(5, "skip RuntimeIL: Harmony.Patch not found"); return; }
            object instance = Activator.CreateInstance(harmonyType, "rimdroid.skipruntimeil");
            object prefix = Activator.CreateInstance(harmonyMethod, typeof(RimDroidSkipRuntimeIL).GetMethod(nameof(Prefix)));
            object[] args = new object[patch.GetParameters().Length];
            args[0] = usable;
            args[1] = prefix;   // (original, prefix, postfix, ...)
            patch.Invoke(instance, args);
            patched = true;
            log(4, "skip RuntimeIL: ModAssemblyHandler.AssemblyIsUsable patched");
        }
        catch (Exception e)
        {
            log(5, "skip RuntimeIL: cannot patch: " + (e.InnerException ?? e).Message);
        }
    }

    // __0 is the assembly being checked.
    public static bool Prefix(Assembly __0, ref bool __result)
    {
        if (__0 == null || __0.GetName().Name != Skipped) return true;
        if (!logged)
        {
            logged = true;
            log(4, "skip RuntimeIL: " + Skipped + " left out (it needs Ludeon's own .NET; Harmony works without it)");
        }
        __result = false;
        return false;
    }
}
