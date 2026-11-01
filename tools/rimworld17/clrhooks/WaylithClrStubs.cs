// Stand-ins for the field injection API of the game's modified runtime ("WaylithClr"). Since 1.7.4995
// the game touches these types on every start, and stock .NET has none of them, so it died in
// PrepatcherCompat.Install before anything loaded. Injection needs that runtime's own native code, so
// here it reports itself as unsupported, a case the game handles: requested fields are logged as
// disabled and their accessors throw. tools/rimworld17/build_runtime_facade.sh forwards these names
// from System.Runtime, where the game looks for them.
using System;
using System.Collections.Generic;
using System.Reflection;

namespace WaylithClr
{
    public enum FieldInjectionBatchStatus { Building, Queued, Applied, Failed }

    public enum FieldInjectionRefusal
    {
        None, CompileGateBusy, DependentFrames, PinnedInstance, NoGCRegions, GCNotLatched, GCBusy
    }

    public sealed class InjectedField
    {
        internal InjectedField(Type declaringType, string name, Type fieldType)
        {
            DeclaringType = declaringType;
            Name = name;
            FieldType = fieldType;
        }

        public Type DeclaringType { get; }
        public string Name { get; }
        public Type FieldType { get; }
        public FieldInfo Field { get { return null; } }
    }

    public sealed class FieldInjectionApplyResult
    {
        static readonly MethodBase[] NoMethods = new MethodBase[0];
        static readonly FieldInjectionBatch[] NoBatches = new FieldInjectionBatch[0];

        public IReadOnlyList<FieldInjectionBatch> Applied { get { return NoBatches; } }
        public IReadOnlyList<FieldInjectionBatch> Pending { get { return NoBatches; } }
        public IReadOnlyList<MethodBase> RejittedMethods { get { return NoMethods; } }
        public IReadOnlyList<MethodBase> StaleDynamicMethods { get { return NoMethods; } }
        public long MigratedInstances { get { return 0; } }
        public bool AllApplied { get { return true; } }
    }

    public sealed class FieldInjectionBatch
    {
        readonly List<InjectedField> fields = new List<InjectedField>();

        public IReadOnlyList<InjectedField> Fields { get { return fields; } }
        public FieldInjectionBatchStatus Status { get { return FieldInjectionBatchStatus.Failed; } }
        public FieldInjectionRefusal LastRefusal { get { return FieldInjectionRefusal.None; } }
        public string PendingReason { get { return null; } }
        public Exception Error { get { return new NotSupportedException("field injection is not supported"); } }

        // The game's patch runner gathers its fields before it asks whether injection is supported.
        public InjectedField AddField(Type declaringType, string name, Type fieldType, FieldAttributes attributes)
        {
            InjectedField field = new InjectedField(declaringType, name, fieldType);
            fields.Add(field);
            return field;
        }

        public void SetDefault(InjectedField field, object value) { }
        public void OnMigrated(Type type, Action<object> initializer) { }
        public void Commit() { throw new NotSupportedException("field injection is not supported"); }
    }

    public static class FieldInjection
    {
        public static bool IsSupported { get { return false; } }
        public static int PendingCount { get { return 0; } }
        public static FieldInjectionApplyResult ApplyPending() { return new FieldInjectionApplyResult(); }
    }

    public readonly struct ILOffsetMapEntry
    {
        public ILOffsetMapEntry(int originalOffset, int newOffset)
        {
            OriginalOffset = originalOffset;
            NewOffset = newOffset;
        }

        public int OriginalOffset { get; }
        public int NewOffset { get; }
    }

    public static class MethodBodyUpdater
    {
        static Exception Unsupported() { return new NotSupportedException("method body updates are not supported"); }

        public static bool IsSupported { get { return false; } }
        public static int MetadataUpdateVersion { get { return 0; } }
        public static int[] GetUpdatedMethodTokens(Module module) { return new int[0]; }

        public static int GetTokenFor(Module module, string value) { throw Unsupported(); }
        public static int GetTokenFor(Module module, Type type) { throw Unsupported(); }
        public static int GetTokenFor(Module module, MethodBase method) { throw Unsupported(); }
        public static int GetTokenFor(Module module, FieldInfo field) { throw Unsupported(); }
        public static int GetTokenFor(MethodBase scope, Type type) { throw Unsupported(); }
        public static int GetTokenFor(MethodBase scope, MethodBase method) { throw Unsupported(); }
        public static int GetTokenForLocals(Module module, byte[] signature) { throw Unsupported(); }
        public static int GetTokenForLocals(MethodBase scope, byte[] signature) { throw Unsupported(); }
        public static int GetTokenForInstantiation(Module module, Type type) { throw Unsupported(); }
        public static int GetTokenForVarargCall(Module module, MethodBase method, Type[] varargs) { throw Unsupported(); }
        public static int GetTokenForDefinition(Module module, MethodBase method) { throw Unsupported(); }
        public static int GetTokenForDefinition(Module module, FieldInfo field) { throw Unsupported(); }

        public static void ReplaceMethodBody(MethodBase method, ReadOnlySpan<byte> il, ReadOnlySpan<ILOffsetMapEntry> map)
        {
            throw Unsupported();
        }

        public static void ReplaceMethodBodies(ReadOnlySpan<MethodBase> methods, ReadOnlySpan<ReadOnlyMemory<byte>> il,
            ReadOnlySpan<ReadOnlyMemory<ILOffsetMapEntry>> maps)
        {
            throw Unsupported();
        }

        public static void ReplaceMethodBodies(ReadOnlySpan<MethodBase> methods, ReadOnlySpan<byte> il,
            ReadOnlySpan<int> ilLengths, ReadOnlySpan<ILOffsetMapEntry> maps, ReadOnlySpan<int> mapLengths)
        {
            throw Unsupported();
        }

        public static void Revert(ReadOnlySpan<MethodBase> methods) { throw Unsupported(); }
    }
}
