// DynamicMethod attributes as Mono took them, for mods on stock .NET (2026-11-07).
//
// Mods written for RimWorld 1.6 (Mono) build helper methods with
// new DynamicMethod(name, MethodAttributes.Private | HideBySig | Static, ...). Mono ignored the
// attributes; stock .NET accepts only Public | Static and throws NotSupportedException ("Wrong
// MethodAttributes or CallingConventions for DynamicMethod"). Vanilla Factions Expanded - Deserters
// failed in a static constructor that way (ImperialForcesSizePatches), so the whole mod did not load.
//
// A dynamic method is always static and its access attributes mean nothing at run time, so when
// 0Harmony loads, DynamicMethod.Init (where every constructor ends up and the check is made) gets a
// prefix: a static method asked for with other access or flags becomes Public | Static. Anything
// else (an instance method, odd calling conventions) is left to fail as before.
using System;
using System.Reflection;
using System.Reflection.Emit;

public static class RimDroidLaxDynamicMethods
{
    static bool patched, logged;
    static Action<int, string> log;

    internal static void Install(Assembly harmony, Action<int, string> logger)
    {
        if (patched) return;
        log = logger;
        try
        {
            Type harmonyType = harmony.GetType("HarmonyLib.Harmony");
            Type harmonyMethod = harmony.GetType("HarmonyLib.HarmonyMethod");
            if (harmonyType == null || harmonyMethod == null) { log(5, "lax dynamic methods: Harmony types not found"); return; }
            // Init(string name, MethodAttributes attributes, CallingConventions callingConvention, ...)
            MethodInfo init = null;
            foreach (MethodInfo m in typeof(DynamicMethod).GetMethods(BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.DeclaredOnly))
            {
                ParameterInfo[] p = m.GetParameters();
                if (m.Name == "Init" && p.Length > 2 && p[1].ParameterType == typeof(MethodAttributes)) { init = m; break; }
            }
            if (init == null) { log(5, "lax dynamic methods: DynamicMethod.Init not found"); return; }
            MethodInfo patch = null;
            foreach (MethodInfo m in harmonyType.GetMethods(BindingFlags.Public | BindingFlags.Instance))
            {
                ParameterInfo[] p = m.GetParameters();
                if (m.Name == "Patch" && p.Length >= 3 && p[0].ParameterType == typeof(MethodBase)) { patch = m; break; }
            }
            if (patch == null) { log(5, "lax dynamic methods: Harmony.Patch not found"); return; }
            object instance = Activator.CreateInstance(harmonyType, "rimdroid.laxdynamicmethods");
            object prefix = Activator.CreateInstance(harmonyMethod, typeof(RimDroidLaxDynamicMethods).GetMethod(nameof(Prefix)));
            object[] args = new object[patch.GetParameters().Length];
            args[0] = init;
            args[1] = prefix;   // (original, prefix, postfix, ...)
            patch.Invoke(instance, args);
            patched = true;
            log(4, "lax dynamic methods: DynamicMethod.Init patched (Harmony " + harmony.GetName().Version + ")");
        }
        catch (Exception e)
        {
            log(5, "lax dynamic methods: cannot patch: " + (e.InnerException ?? e).Message);
        }
    }

    // Prefix on DynamicMethod.Init: __1 is "attributes", by position (the name is not part of the API).
    public static void Prefix(string __0, ref MethodAttributes __1)
    {
        const MethodAttributes Wanted = MethodAttributes.Public | MethodAttributes.Static;
        if (__1 == Wanted || (__1 & MethodAttributes.Static) == 0) return;
        if (!logged)
        {
            logged = true;
            log?.Invoke(4, "lax dynamic methods: " + __0 + " asked for " + __1 + ", made Public, Static");
        }
        __1 = Wanted;
    }
}
