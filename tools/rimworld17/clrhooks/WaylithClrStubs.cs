// Stand-ins for the field injection API of the game's modified runtime ("WaylithClr"). Since 1.7.4995
// the game touches these types on every start, and stock .NET has none of them, so it died in
// PrepatcherCompat.Install before anything loaded. Injection needs that runtime's own native code, so
// here it reports itself as unsupported, a case the game handles: requested fields are logged as
// disabled and their accessors throw. tools/rimworld17/build_runtime_facade.sh forwards these names
// from System.Runtime, where the game looks for them.
//
// 1.7.5005 (2026-11-11) moved method body rewriting onto a transformer API: the game derives its
// Prepatcher, field injection and profiler stages from MethodBodyTransformer and reads bodies through
// MethodBodyView. Without these types the profiler's static constructor failed at every start. The
// transformers can be built and registered, but stock .NET never calls them; MethodBodyUpdater
// still reports itself as unsupported.
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

    [Flags]
    public enum MethodBodyFlags { None = 0 }

    public readonly struct MethodBodyClause
    {
        public ExceptionHandlingClauseOptions Flags { get; }
        public int TryOffset { get; }
        public int TryLength { get; }
        public int HandlerOffset { get; }
        public int HandlerLength { get; }
        public int ClassTokenOrFilterOffset { get; }
    }

    public ref struct MethodBodyView
    {
        public ReadOnlySpan<byte> IL { get { return default; } }
        public ReadOnlySpan<byte> LocalSignature { get { return default; } }
        public int LocalSignatureToken { get { return 0; } }
        public int MaxStackSize { get { return 0; } }
        public bool InitLocals { get { return false; } }
        public int ClauseCount { get { return 0; } }
        public MethodBodyFlags Flags { get { return MethodBodyFlags.None; } }
    }

    public sealed class MethodBodyBuilder
    {
        internal MethodBodyBuilder() { }
        public void Write(ReadOnlySpan<byte> il) { }
        public void AddOffsetMapEntries(ReadOnlySpan<ILOffsetMapEntry> entries) { }
    }

    public readonly struct MethodBodyTransformFailure
    {
        public RuntimeMethodHandle Method { get; }
        public Exception Exception { get; }
    }

    /// <summary>The game's stages override the protected members; nothing ever calls them here.</summary>
    public abstract class MethodBodyTransformer
    {
        static readonly MethodBodyTransformFailure[] NoFailures = new MethodBodyTransformFailure[0];

        protected MethodBodyTransformer(int priority) { Priority = priority; }

        public int Priority { get; }

        public static event Action<Module, ReadOnlyMemory<int>, ReadOnlyMemory<int>> DefaultBodiesChanged { add { } remove { } }
        public static event Action<Exception> UnobservedException { add { } remove { } }
        public static void ProcessMetadataUpdates() { }

        public void Register() { }
        public void Unregister() { }
        public MethodBodyTransformFailure[] Rebuild(ReadOnlySpan<RuntimeMethodHandle> methods) { return NoFailures; }

        protected virtual bool StaysAttached { get { return false; } }
        protected virtual bool CanTransformConcurrently { get { return false; } }
        protected virtual bool CanTransform(RuntimeMethodHandle method) { return false; }
        protected virtual void Prepare(RuntimeMethodHandle method, ref MethodBodyView body, ReadOnlySpan<MethodBodyClause> clauses) { }
        protected virtual bool TryTransform(RuntimeMethodHandle method, ref MethodBodyView body,
            ReadOnlySpan<MethodBodyClause> clauses, MethodBodyBuilder builder) { return false; }
        protected virtual void OnTransformFailed(RuntimeMethodHandle method, Exception exception) { }
        protected virtual void OnParticipationChanged(ReadOnlySpan<RuntimeMethodHandle> added, ReadOnlySpan<RuntimeMethodHandle> removed) { }
        protected virtual void OnDefaultBodiesChanged(ReadOnlySpan<RuntimeMethodHandle> methods) { }
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

        // 1.7.5005
        public static int GetTokenForSignature(Module module, ReadOnlySpan<byte> signature) { throw Unsupported(); }
        public static RuntimeMethodHandle GetDefinitionHandle(MethodBase method) { return method.MethodHandle; }
        public static MethodBase GetMethodFromHandle(RuntimeMethodHandle handle) { return MethodBase.GetMethodFromHandle(handle); }
        public static RuntimeMethodHandle ResolveMethodDef(Module module, int token) { return module.ResolveMethod(token).MethodHandle; }
        public static object ResolveToken(MethodBase scope, int token) { throw Unsupported(); }
        public static object ResolveToken(RuntimeMethodHandle scope, int token) { throw Unsupported(); }

        public static bool TryGetDefaultMethodBody(MethodBase method, Span<MethodBodyClause> clauses, ref MethodBodyView body)
        {
            return false;
        }
    }
}
