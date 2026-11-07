// RimDroidClrHooks — the startup hook of RimWorld 1.7 on the stock .NET 10 runtime for Android
// (STARTUP_HOOKS, set by DotnetEngine; built by tools/rimworld17/build_clrhooks.sh).
//
// The game ships a modified runtime ("WaylithClr") whose switches in RimWorld.runtimeconfig.json keep
// mods written for the Unity/Mono era working. Stock .NET has none of them; this hook stands in for
// the ones mods meet first:
//  - WaylithClr.AssemblyNameRedirects: mods compiled against Unity reference UnityEngine.CoreModule,
//    UnityEngine.IMGUIModule and the like, whose types the game keeps in UnityStubs. A missing
//    assembly matching a rule is answered with a facade of that name forwarding every type of the
//    rule's target. The rules are read from the game's runtimeconfig.
//  - WaylithClr.IsolatedLoadsResolveByName / LaxAssemblyBinding: a mod assembly loaded from bytes
//    sits in a load context of its own and does not see other mods' assemblies; a reference is
//    answered with an already loaded assembly of the same simple name, whatever its version.
//  - WaylithClr.LaxPrivateFieldNames: see LaxFieldNames.cs (Harmony's field lookups).
//  - DynamicMethods made with Mono-era attributes (Private | Static): see LaxDynamicMethods.cs.
//  - WaylithClr.FieldInjection: see WaylithClrStubs.cs (reported as unsupported).
//  - MonoMod inside Harmony P/Invokes glibc's "libc" (and "libdl.so.2"); Android's libc is bionic.
//    Those go to librdglibc.so, which translates the few calls whose ABI differs.
using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Runtime.InteropServices;
using System.Runtime.Loader;
using System.Text.Json;

internal static class StartupHook
{
    const string Tag = "RimDroid/clrhooks";
    // RimWorld 1.7.4977's rules, used when the runtimeconfig cannot be read.
    const string DefaultRedirects =
        "UnityEngine*=UnityStubs;Unity.Newtonsoft.Json=;Unity.*=UnityStubs;Assembly-CSharp-firstpass=UnityStubs";

    static readonly object gate = new object();
    static readonly Dictionary<string, Assembly> facades = new Dictionary<string, Assembly>(StringComparer.OrdinalIgnoreCase);
    static List<KeyValuePair<string, string>> redirects;
    static IntPtr glibc;

    public static void Initialize()
    {
        redirects = ParseRedirects(ReadRedirects());
        AppDomain.CurrentDomain.AssemblyLoad += OnAssemblyLoad;
        AppDomain.CurrentDomain.AssemblyResolve += OnAssemblyResolve;
        Log(4, "ready: " + redirects.Count + " assembly redirects");
    }

    // ---- assemblies -----------------------------------------------------------------------------

    static Assembly OnAssemblyResolve(object sender, ResolveEventArgs args)
    {
        AssemblyName want;
        try { want = new AssemblyName(args.Name); }
        catch { return null; }
        string name = want.Name;
        if (string.IsNullOrEmpty(name) || name.EndsWith(".resources", StringComparison.OrdinalIgnoreCase)) return null;

        // Another mod's assembly (or a facade made earlier), whatever version was asked for.
        foreach (Assembly loaded in AppDomain.CurrentDomain.GetAssemblies())
        {
            if (!loaded.IsDynamic && string.Equals(loaded.GetName().Name, name, StringComparison.OrdinalIgnoreCase))
                return loaded;
        }

        string target = RedirectTarget(name);
        if (string.IsNullOrEmpty(target)) return null;
        lock (gate)
        {
            Assembly facade;
            if (facades.TryGetValue(name, out facade)) return facade;
            try
            {
                int count;
                byte[] image = BuildFacade(name, want.Version, target, out count);
                facade = AssemblyLoadContext.Default.LoadFromStream(new MemoryStream(image));
                facades[name] = facade;
                Log(4, "assembly " + name + " -> " + target + " (" + count + " types)");
                return facade;
            }
            catch (Exception e)
            {
                Log(6, "assembly " + name + " -> " + target + " failed: " + e);
                return null;
            }
        }
    }

    // First matching rule wins; "Name*" matches by prefix; an empty target means "no redirect".
    static string RedirectTarget(string name)
    {
        foreach (KeyValuePair<string, string> rule in redirects)
        {
            string pattern = rule.Key;
            bool match = pattern.EndsWith("*", StringComparison.Ordinal)
                ? name.StartsWith(pattern.Substring(0, pattern.Length - 1), StringComparison.OrdinalIgnoreCase)
                : string.Equals(name, pattern, StringComparison.OrdinalIgnoreCase);
            if (match) return string.Equals(name, rule.Value, StringComparison.OrdinalIgnoreCase) ? null : rule.Value;
        }
        return null;
    }

    // An assembly called `name` holding nothing but type forwarders to every top-level type of
    // `targetName` (nested types travel with their parent). Loaded under the requested name, it
    // passes the runtime's name check that a plain redirect to UnityStubs would fail.
    static byte[] BuildFacade(string name, Version version, string targetName, out int count)
    {
        Assembly target = Assembly.Load(new AssemblyName(targetName));
        using (PEReader pe = new PEReader(File.OpenRead(target.Location)))
        {
            MetadataReader md = pe.GetMetadataReader();
            MetadataBuilder b = new MetadataBuilder();
            b.AddModule(0, b.GetOrAddString(name + ".dll"), b.GetOrAddGuid(Guid.NewGuid()), default(GuidHandle), default(GuidHandle));
            b.AddAssembly(b.GetOrAddString(name), version ?? new Version(0, 0, 0, 0), default(StringHandle),
                default(BlobHandle), 0, AssemblyHashAlgorithm.None);
            AssemblyDefinition def = md.GetAssemblyDefinition();
            AssemblyReferenceHandle targetRef = b.AddAssemblyReference(b.GetOrAddString(md.GetString(def.Name)),
                def.Version, default(StringHandle), default(BlobHandle), 0, default(BlobHandle));
            b.AddTypeDefinition(0, default(StringHandle), b.GetOrAddString("<Module>"), default(EntityHandle),
                MetadataTokens.FieldDefinitionHandle(1), MetadataTokens.MethodDefinitionHandle(1));
            const TypeAttributes Forwarder = (TypeAttributes)0x00200000;
            HashSet<string> seen = new HashSet<string>(StringComparer.Ordinal);
            count = 0;
            foreach (TypeDefinitionHandle h in md.TypeDefinitions)
            {
                TypeDefinition t = md.GetTypeDefinition(h);
                if (!t.GetDeclaringType().IsNil) continue;
                string ns = md.GetString(t.Namespace), n = md.GetString(t.Name);
                if (n == "<Module>" || !seen.Add(ns + "." + n)) continue;
                b.AddExportedType(Forwarder, b.GetOrAddString(ns), b.GetOrAddString(n), targetRef, 0);
                count++;
            }
            foreach (ExportedTypeHandle h in md.ExportedTypes)
            {
                ExportedType t = md.GetExportedType(h);
                if (t.Implementation.Kind == HandleKind.ExportedType) continue;
                string ns = md.GetString(t.Namespace), n = md.GetString(t.Name);
                if (!seen.Add(ns + "." + n)) continue;
                b.AddExportedType(Forwarder, b.GetOrAddString(ns), b.GetOrAddString(n), targetRef, 0);
                count++;
            }
            ManagedPEBuilder image = new ManagedPEBuilder(PEHeaderBuilder.CreateLibraryHeader(),
                new MetadataRootBuilder(b), new BlobBuilder());
            BlobBuilder blob = new BlobBuilder();
            image.Serialize(blob);
            return blob.ToArray();
        }
    }

    static string ReadRedirects()
    {
        try
        {
            string dir = AppContext.BaseDirectory;
            string config = Path.Combine(dir, "RimWorld.runtimeconfig.json");
            using (JsonDocument doc = JsonDocument.Parse(File.ReadAllText(config)))
            {
                JsonElement props;
                JsonElement value;
                if (doc.RootElement.GetProperty("runtimeOptions").TryGetProperty("configProperties", out props)
                    && props.TryGetProperty("WaylithClr.AssemblyNameRedirects", out value)
                    && value.ValueKind == JsonValueKind.String)
                    return value.GetString();
            }
        }
        catch (Exception e)
        {
            Log(5, "runtimeconfig not read, using the built-in redirects: " + e.Message);
        }
        return DefaultRedirects;
    }

    static List<KeyValuePair<string, string>> ParseRedirects(string text)
    {
        List<KeyValuePair<string, string>> rules = new List<KeyValuePair<string, string>>();
        foreach (string part in text.Split(';'))
        {
            int eq = part.IndexOf('=');
            if (eq <= 0) continue;
            rules.Add(new KeyValuePair<string, string>(part.Substring(0, eq).Trim(), part.Substring(eq + 1).Trim()));
        }
        return rules;
    }

    // ---- native libraries -----------------------------------------------------------------------

    static void OnAssemblyLoad(object sender, AssemblyLoadEventArgs args)
    {
        Assembly a = args.LoadedAssembly;
        if (a.IsDynamic) return;
        string name = a.GetName().Name;
        if (name == "CameraPlus") { SkipCameraPlusRendering(a); return; }
        if (name != "0Harmony" && !name.StartsWith("MonoMod", StringComparison.Ordinal)) return;
        try
        {
            NativeLibrary.SetDllImportResolver(a, ResolveNative);
            Log(4, name + " " + a.GetName().Version + ": libc -> librdglibc.so");
        }
        catch (Exception e)
        {
            Log(5, name + ": cannot route libc: " + e.Message);
        }
        // WaylithClr.LaxPrivateFieldNames: Harmony finds framework fields by their Mono names too.
        if (name == "0Harmony")
        {
            RimDroidLaxFieldNames.Install(a, Log);
            RimDroidLaxDynamicMethods.Install(a, Log);
        }
    }

    // Camera+ draws its colonist markers with shaders from its own Unity asset bundle, generated on the
    // GPU on the first frame of a loaded game; on this engine that frame loses the Vulkan device
    // (Adreno, 2026-10-14: 25 shader pipelines the game never uses otherwise, then VK_ERROR_DEVICE_LOST).
    // Camera+ offers other mods a flag to skip its drawing (markers, label and floating-text filters);
    // its zoom range and camera handling stay. Set once the assembly is in.
    static void SkipCameraPlusRendering(Assembly a)
    {
        try
        {
            Type main = a.GetType("CameraPlus.CameraPlusMain", false);
            FieldInfo flag = main == null ? null : main.GetField("skipCustomRendering", BindingFlags.Public | BindingFlags.Static);
            if (flag == null || flag.FieldType != typeof(bool)) { Log(5, "CameraPlus: no skipCustomRendering flag in this version"); return; }
            flag.SetValue(null, true);
            Log(4, "CameraPlus " + a.GetName().Version + ": custom rendering (markers) skipped");
        }
        catch (Exception e)
        {
            Log(5, "CameraPlus: cannot skip its rendering: " + e.Message);
        }
    }

    static IntPtr ResolveNative(string library, Assembly assembly, DllImportSearchPath? searchPath)
    {
        if (library != "libc" && library != "libc.so.6" && library != "libdl.so.2") return IntPtr.Zero;
        lock (gate)
        {
            if (glibc != IntPtr.Zero) return glibc;
            string dirs = AppContext.GetData("NATIVE_DLL_SEARCH_DIRECTORIES") as string ?? "";
            foreach (string dir in dirs.Split(Path.PathSeparator))
            {
                string path = Path.Combine(dir, "librdglibc.so");
                if (dir.Length > 0 && File.Exists(path) && NativeLibrary.TryLoad(path, out glibc)) return glibc;
            }
            Log(6, "librdglibc.so not found in " + dirs);
            return IntPtr.Zero;
        }
    }

    // ---- log ------------------------------------------------------------------------------------

    [DllImport("log", EntryPoint = "__android_log_write")]
    static extern int AndroidLog(int priority, string tag, string text);

    // 4 info, 5 warning, 6 error (android/log.h)
    static void Log(int priority, string text)
    {
        try { AndroidLog(priority, Tag, text); }
        catch { }
    }
}
