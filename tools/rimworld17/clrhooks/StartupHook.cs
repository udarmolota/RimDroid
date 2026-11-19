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
//  - Steamworks.NET's "steam_api" / "steam_api64" go to our 1.7 Steam stub (RouteSteam).
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
        foreach (Assembly a in AppDomain.CurrentDomain.GetAssemblies()) TryBindPlacing(a);
        try { rd_set_placing_query(pollPlacingRef); } catch { }
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
        if (!placingBound && (name == "RimWorld" || name == "Assembly-CSharp")) TryBindPlacing(a);
        if (name == "CameraPlus") { SkipCameraPlusRendering(a); return; }
        if (name == "com.rlabrecque.steamworks.net") { RouteSteam(a); return; }
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
            RimDroidSkipRuntimeIL.Install(a, Log);
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

    // Steamworks.NET P/Invokes "steam_api64" (Windows name, up to 1.7.4995) or "steam_api" (newer
    // builds, 2026-11-11). Our 1.7 stub is libsteam_api64.so; libsteam_api.so in the app is the 1.6
    // native engine's stub, without the newer SteamInternal_SteamAPI_Init, and the game aborted at
    // start with EntryPointNotFoundException. Both names go to the 1.7 stub.
    static IntPtr steam;

    static void RouteSteam(Assembly a)
    {
        try
        {
            NativeLibrary.SetDllImportResolver(a, ResolveSteam);
            Log(4, a.GetName().Name + ": steam_api -> libsteam_api64.so");
        }
        catch (Exception e)
        {
            Log(5, "cannot route steam_api: " + e.Message);
        }
    }

    static IntPtr ResolveSteam(string library, Assembly assembly, DllImportSearchPath? searchPath)
    {
        if (library != "steam_api" && library != "steam_api64") return IntPtr.Zero;
        lock (gate)
        {
            if (steam == IntPtr.Zero && !NativeLibrary.TryLoad("libsteam_api64.so", out steam))
                Log(6, "libsteam_api64.so not found");
            return steam;
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

    // ---- designator placement state (for ClrActivity's touch preview) ---------------------------
    // From the Wishing-Qi fork (2026-11-19), on test.
    //
    // In RimWorld, placing a single building or installing an item (Designator_Place with
    // DraggableDimensions == 0) commits immediately on MouseDown, while previewing its ghost
    // requires moving the cursor with no button pressed (hover). Line/box designators
    // (DraggableDimensions > 0: walls, conduits, floors, zones) instead use DesignationDragger
    // from MouseDown to MouseUp.
    // PollPlacing() runs on the game thread in glfwPollEvents() and returns:
    //   0 = no placement/drag designator active
    //   1 = single-tile Designator_Place (DraggableDimensions == 0): slide hovers ghost, tap places
    //   2 = draggable Designator (DraggableDimensions > 0): slide holds Left Mouse Button to drag line/box
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    delegate int PollPlacingFn();

    [DllImport("glfw", EntryPoint = "rd_set_placing_query")]
    static extern void rd_set_placing_query(PollPlacingFn cb);

    static readonly PollPlacingFn pollPlacingRef = PollPlacing;
    static bool placingBound;
    static Func<object> getCurrentMap;
    static Func<object> getDesignatorManager;
    static MethodInfo selectedDesignatorGetter;
    static MethodInfo selectedStyleGetter;
    static MethodInfo drawStyleGetter;
    static MethodInfo draggableDimsGetter;
    static FieldInfo defNameField;
    static FieldInfo stylesField;
    static FieldInfo dropdownActiveField;
    static Type designatorPlaceType;
    static Type dropdownType;
    static object cachedDm;
    static Func<object> getSelectedDesignator;
    static object cachedDes;
    static int cachedMode;
    static Func<float> getCurUICellSize;
    static FieldInfo lastUIScaleField;
    static MethodInfo mouseCellMethod;
    static FieldInfo intVecXField;
    static FieldInfo intVecZField;

    static void TryBindPlacing(Assembly a)
    {
        if (placingBound || a == null || a.IsDynamic) return;
        string name = a.GetName().Name;
        if (name != "RimWorld" && name != "Assembly-CSharp") return;
        try
        {
            Type find = a.GetType("Verse.Find", false);
            Type dm = a.GetType("Verse.DesignatorManager", false);
            Type desType = a.GetType("Verse.Designator", false);
            Type defType = a.GetType("Verse.Def", false);
            Type catType = a.GetType("Verse.DrawStyleCategoryDef", false);
            Type place = a.GetType("RimWorld.Designator_Place", false);
            Type dropdown = a.GetType("RimWorld.Designator_Dropdown", false);
            if (find == null || dm == null || desType == null || place == null) return;
            MethodInfo mapGet = find.GetProperty("CurrentMap", BindingFlags.Public | BindingFlags.Static)?.GetGetMethod();
            MethodInfo dmGet = find.GetProperty("DesignatorManager", BindingFlags.Public | BindingFlags.Static)?.GetGetMethod();
            MethodInfo selGet = dm.GetProperty("SelectedDesignator", BindingFlags.Public | BindingFlags.Instance)?.GetGetMethod();
            MethodInfo selStyleGet = dm.GetProperty("SelectedStyle", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)?.GetGetMethod(true);
            // RimWorld 1.6+ uses DrawStyleCategory ("Default" for single-cell buildings, "Walls" /
            // "Conduits" / "Floors" / "Zones" / "Orders" for draggable ones); older builds used DraggableDimensions.
            MethodInfo styleGet = desType.GetProperty("DrawStyleCategory", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)?.GetGetMethod(true);
            MethodInfo dragGet = desType.GetProperty("DraggableDimensions", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)?.GetGetMethod(true)
                ?? desType.GetMethod("DraggableDimensions", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance, null, Type.EmptyTypes, null);
            if (mapGet == null || dmGet == null || selGet == null) return;
            getCurrentMap = (Func<object>)Delegate.CreateDelegate(typeof(Func<object>), mapGet);
            getDesignatorManager = (Func<object>)Delegate.CreateDelegate(typeof(Func<object>), dmGet);
            selectedDesignatorGetter = selGet;
            selectedStyleGetter = selStyleGet;
            drawStyleGetter = styleGet;
            draggableDimsGetter = dragGet;
            defNameField = defType?.GetField("defName", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
            stylesField = catType?.GetField("styles", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
            dropdownType = dropdown;
            dropdownActiveField = dropdown?.GetField("activeDesignator", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
            designatorPlaceType = place;
            try
            {
                Type uiType = a.GetType("Verse.UI", false);
                Type iv3Type = a.GetType("Verse.IntVec3", false);
                MethodInfo cellSz = uiType?.GetMethod("CurUICellSize", BindingFlags.Public | BindingFlags.Static, null, Type.EmptyTypes, null);
                if (cellSz != null && cellSz.ReturnType == typeof(float))
                    getCurUICellSize = (Func<float>)Delegate.CreateDelegate(typeof(Func<float>), cellSz);
                lastUIScaleField = uiType?.GetField("lastUIScale", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static);
                mouseCellMethod = uiType?.GetMethod("MouseCell", BindingFlags.Public | BindingFlags.Static, null, Type.EmptyTypes, null);
                intVecXField = iv3Type?.GetField("x", BindingFlags.Public | BindingFlags.Instance);
                intVecZField = iv3Type?.GetField("z", BindingFlags.Public | BindingFlags.Instance);
            }
            catch { }
            placingBound = true;
            Log(4, "designator placement query bound (" + name + ", drawStyle=" + (styleGet != null) + ")");
        }
        catch (Exception e)
        {
            Log(5, "cannot bind designator placement query: " + e.Message);
        }
    }

    static bool IsSingleCellName(string name)
    {
        return string.IsNullOrEmpty(name)
            || string.Equals(name, "Default", StringComparison.OrdinalIgnoreCase)
            || string.Equals(name, "SingleCell", StringComparison.OrdinalIgnoreCase)
            || string.Equals(name, "Single", StringComparison.OrdinalIgnoreCase);
    }

    static string GetDefName(object def)
    {
        if (def == null) return null;
        try
        {
            if (defNameField != null)
            {
                string n = defNameField.GetValue(def) as string;
                if (n != null) return n;
            }
            return def.ToString();
        }
        catch
        {
            return null;
        }
    }

    static int ClassifyDesignator(object dm, object des)
    {
        if (des == null) return 0;
        try
        {
            if (dropdownType != null && dropdownActiveField != null && dropdownType.IsInstanceOfType(des))
            {
                object inner = dropdownActiveField.GetValue(des);
                if (inner != null) des = inner;
            }
        }
        catch { }

        string catName = null;
        try
        {
            if (drawStyleGetter != null)
            {
                object cat = drawStyleGetter.Invoke(des, null);
                if (cat != null)
                {
                    catName = GetDefName(cat);
                    if (!IsSingleCellName(catName))
                    {
                        bool onlySingle = false;
                        if (stylesField != null && stylesField.GetValue(cat) is System.Collections.IList list)
                        {
                            if (list.Count == 0) onlySingle = true;
                            else if (list.Count == 1 && IsSingleCellName(GetDefName(list[0]))) onlySingle = true;
                        }
                        if (!onlySingle)
                        {
                            if (selectedStyleGetter != null && dm != null)
                            {
                                object curStyle = selectedStyleGetter.Invoke(dm, null);
                                if (curStyle != null && IsSingleCellName(GetDefName(curStyle)))
                                    return designatorPlaceType.IsInstanceOfType(des) ? 1 : 0;
                            }
                            return 2;
                        }
                    }
                }
            }
        }
        catch { }

        try
        {
            if (draggableDimsGetter != null && Convert.ToInt32(draggableDimsGetter.Invoke(des, null)) > 0)
                return 2;
        }
        catch { }

        return designatorPlaceType.IsInstanceOfType(des) ? 1 : 0;
    }

    static int PollPlacing()
    {
        if (!placingBound) return 0;
        try
        {
            if (getCurrentMap() == null) return 0;
            object dm = getDesignatorManager();
            if (dm == null) return 0;
            if (!object.ReferenceEquals(dm, cachedDm))
            {
                cachedDm = dm;
                getSelectedDesignator = (Func<object>)Delegate.CreateDelegate(typeof(Func<object>), dm, selectedDesignatorGetter);
            }
            object des = getSelectedDesignator();
            if (des == null) { cachedDes = null; cachedMode = 0; return 0; }
            try
            {
                if (dropdownType != null && dropdownActiveField != null && dropdownType.IsInstanceOfType(des))
                {
                    object inner = dropdownActiveField.GetValue(des);
                    if (inner != null) des = inner;
                }
            }
            catch { }
            if (!object.ReferenceEquals(des, cachedDes))
            {
                cachedDes = des;
                cachedMode = ClassifyDesignator(dm, des);
            }
            if (cachedMode == 1)
            {
                int cellPx = 32, cx = 0, cz = 0;
                try
                {
                    if (getCurUICellSize != null)
                    {
                        float uiCell = getCurUICellSize();
                        float scale = lastUIScaleField != null ? Convert.ToSingle(lastUIScaleField.GetValue(null)) : 1f;
                        int px = (int)Math.Round(uiCell * (scale > 0f ? scale : 1f));
                        cellPx = px < 4 ? 4 : (px > 255 ? 255 : px);
                    }
                    if (mouseCellMethod != null && intVecXField != null && intVecZField != null)
                    {
                        object cell = mouseCellMethod.Invoke(null, null);
                        if (cell != null)
                        {
                            cx = Convert.ToInt32(intVecXField.GetValue(cell)) & 0x7FF;
                            cz = Convert.ToInt32(intVecZField.GetValue(cell)) & 0x7FF;
                        }
                    }
                }
                catch { }
                return 1 | (cellPx << 2) | (cx << 10) | (cz << 21);
            }
            return cachedMode;
        }
        catch
        {
            return 0;
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
