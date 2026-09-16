using System.Collections.Immutable;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Text;

if (args.Length is < 1 or > 2)
{
    Console.Error.WriteLine("usage: IcallAudit MANAGED_DIR [OUTPUT_TSV]");
    return 2;
}

string managedDir = Path.GetFullPath(args[0]);
string? outputPath = args.Length == 2 ? Path.GetFullPath(args[1]) : null;
if (!Directory.Exists(managedDir))
{
    Console.Error.WriteLine($"managed directory not found: {managedDir}");
    return 3;
}

string[] assemblyPaths = Directory.EnumerateFiles(managedDir, "*.dll").Order().ToArray();
Dictionary<string, string> enumAbi = BuildEnumAbiCatalog(assemblyPaths);
var rows = new List<Row>();
foreach (string assemblyPath in assemblyPaths)
{
    using var stream = File.OpenRead(assemblyPath);
    using var pe = new PEReader(stream);
    if (!pe.HasMetadata)
        continue;

    MetadataReader reader = pe.GetMetadataReader();
    var provider = new TypeNameProvider();
    foreach (MethodDefinitionHandle handle in reader.MethodDefinitions)
    {
        MethodDefinition method = reader.GetMethodDefinition(handle);
        if ((method.ImplAttributes & MethodImplAttributes.InternalCall) == 0)
            continue;

        MethodSignature<string> signature = method.DecodeSignature(provider, null);
        string typeName = MetadataNames.GetTypeName(reader, method.GetDeclaringType());
        string methodName = reader.GetString(method.Name);
        var managedParameters = signature.ParameterTypes.ToList();
        var abiParameters = new List<string>();
        if (signature.Header.IsInstance)
            abiParameters.Add("p");
        abiParameters.AddRange(managedParameters.Select(type => ToAbiCode(type, enumAbi)));

        string registrationName = $"{typeName}::{methodName}";
        rows.Add(new Row(
            Path.GetFileName(assemblyPath),
            registrationName,
            signature.Header.IsInstance,
            signature.ReturnType,
            string.Join(",", managedParameters),
            $"{ToAbiCode(signature.ReturnType, enumAbi)}F{string.Concat(abiParameters)}"));
    }
}

IEnumerable<string> lines = new[]
{
    "assembly\tname\tinstance\treturn\tparameters\tabi"
}.Concat(rows.Select(row => string.Join('\t',
    Escape(row.Assembly), Escape(row.Name), row.Instance ? "1" : "0",
    Escape(row.ReturnType), Escape(row.Parameters), Escape(row.Abi))));

if (outputPath is null)
{
    foreach (string line in lines)
        Console.WriteLine(line);
}
else
{
    Directory.CreateDirectory(Path.GetDirectoryName(outputPath)!);
    File.WriteAllLines(outputPath, lines, new UTF8Encoding(false));
}

int unknown = rows.Count(row => row.Abi.Contains('?'));
int duplicateNames = rows.GroupBy(row => row.Name).Count(group => group.Count() > 1);
Console.Error.WriteLine(
    $"ICALL_AUDIT methods={rows.Count} abi_shapes={rows.Select(row => row.Abi).Distinct().Count()} " +
    $"unknown={unknown} overloaded_names={duplicateNames}");
return unknown == 0 ? 0 : 10;

static string Escape(string value) => value.Replace('\t', ' ').Replace('\r', ' ').Replace('\n', ' ');

static Dictionary<string, string> BuildEnumAbiCatalog(IEnumerable<string> assemblyPaths)
{
    var result = new Dictionary<string, string>(StringComparer.Ordinal);
    foreach (string assemblyPath in assemblyPaths)
    {
        using var stream = File.OpenRead(assemblyPath);
        using var pe = new PEReader(stream);
        if (!pe.HasMetadata)
            continue;

        MetadataReader reader = pe.GetMetadataReader();
        var provider = new TypeNameProvider();
        foreach (TypeDefinitionHandle handle in reader.TypeDefinitions)
        {
            TypeDefinition type = reader.GetTypeDefinition(handle);
            if (MetadataNames.GetEntityTypeName(reader, type.BaseType) != "System.Enum")
                continue;

            foreach (FieldDefinitionHandle fieldHandle in type.GetFields())
            {
                FieldDefinition field = reader.GetFieldDefinition(fieldHandle);
                if ((field.Attributes & FieldAttributes.Static) != 0)
                    continue;

                string underlying = field.DecodeSignature(provider, null);
                string code = ToAbiCode(underlying, result);
                if (!code.StartsWith('?'))
                    result[MetadataNames.GetTypeName(reader, handle)] = code;
                break;
            }
        }
    }
    return result;
}

static string ToAbiCode(string type, IReadOnlyDictionary<string, string> enumAbi)
{
    string bare = type.TrimEnd('&');
    if (type.EndsWith('&') || bare.EndsWith('*') || bare.EndsWith(']') || bare.StartsWith("class "))
        return "p";

    if (bare.StartsWith("valuetype ") && TryResolveEnumAbi(bare[10..], enumAbi, out string enumCode))
        return enumCode;

    return bare switch
    {
        "void" => "v",
        "bool" or "char" or "int8" or "uint8" or "int16" or "uint16" or "int32" => "i",
        "uint32" => "u",
        "int64" => "I",
        "uint64" => "U",
        "native int" or "native uint" or "string" or "object" or "typedref" => "p",
        "float32" => "f",
        "float64" => "d",
        _ when bare.StartsWith("!") => "p",
        _ when bare.StartsWith("valuetype ") => $"?{bare[10..]}",
        _ => $"?{bare}"
    };
}

static bool TryResolveEnumAbi(string typeName, IReadOnlyDictionary<string, string> enumAbi, out string code)
{
    string definitionName = typeName.Split('<', 2)[0];
    if (enumAbi.TryGetValue(definitionName, out code!))
        return true;

    string suffix = $".{definitionName}";
    string nestedSuffix = $"/{definitionName}";
    KeyValuePair<string, string>[] matches = enumAbi
        .Where(pair => pair.Key.EndsWith(suffix, StringComparison.Ordinal) ||
                       pair.Key.EndsWith(nestedSuffix, StringComparison.Ordinal))
        .Take(2)
        .ToArray();
    if (matches.Length == 1)
    {
        code = matches[0].Value;
        return true;
    }

    code = string.Empty;
    return false;
}

internal sealed record Row(
    string Assembly,
    string Name,
    bool Instance,
    string ReturnType,
    string Parameters,
    string Abi);

internal static class MetadataNames
{
    public static string GetTypeName(MetadataReader reader, TypeDefinitionHandle handle)
    {
        TypeDefinition type = reader.GetTypeDefinition(handle);
        string name = reader.GetString(type.Name);
        TypeDefinitionHandle parent = type.GetDeclaringType();
        if (!parent.IsNil)
            return $"{GetTypeName(reader, parent)}/{name}";

        string ns = reader.GetString(type.Namespace);
        return string.IsNullOrEmpty(ns) ? name : $"{ns}.{name}";
    }

    public static string? GetEntityTypeName(MetadataReader reader, EntityHandle handle)
    {
        if (handle.IsNil)
            return null;

        return handle.Kind switch
        {
            HandleKind.TypeDefinition => GetTypeName(reader, (TypeDefinitionHandle)handle),
            HandleKind.TypeReference => GetTypeReferenceName(reader, (TypeReferenceHandle)handle),
            _ => null
        };
    }

    public static string GetTypeReferenceName(MetadataReader reader, TypeReferenceHandle handle)
    {
        TypeReference type = reader.GetTypeReference(handle);
        string ns = reader.GetString(type.Namespace);
        string name = reader.GetString(type.Name);
        return string.IsNullOrEmpty(ns) ? name : $"{ns}.{name}";
    }
}

internal sealed class TypeNameProvider : ISignatureTypeProvider<string, object?>
{
    public string GetArrayType(string elementType, ArrayShape shape) => $"{elementType}[{new string(',', shape.Rank - 1)}]";
    public string GetByReferenceType(string elementType) => $"{elementType}&";
    public string GetFunctionPointerType(MethodSignature<string> signature) => "fnptr";
    public string GetGenericInstantiation(string genericType, ImmutableArray<string> typeArguments) =>
        $"{genericType}<{string.Join(',', typeArguments)}>";
    public string GetGenericMethodParameter(object? genericContext, int index) => $"!!{index}";
    public string GetGenericTypeParameter(object? genericContext, int index) => $"!{index}";
    public string GetModifiedType(string modifierType, string unmodifiedType, bool isRequired) => unmodifiedType;
    public string GetPinnedType(string elementType) => elementType;
    public string GetPointerType(string elementType) => $"{elementType}*";
    public string GetPrimitiveType(PrimitiveTypeCode typeCode) => typeCode switch
    {
        PrimitiveTypeCode.Void => "void",
        PrimitiveTypeCode.Boolean => "bool",
        PrimitiveTypeCode.Char => "char",
        PrimitiveTypeCode.SByte => "int8",
        PrimitiveTypeCode.Byte => "uint8",
        PrimitiveTypeCode.Int16 => "int16",
        PrimitiveTypeCode.UInt16 => "uint16",
        PrimitiveTypeCode.Int32 => "int32",
        PrimitiveTypeCode.UInt32 => "uint32",
        PrimitiveTypeCode.Int64 => "int64",
        PrimitiveTypeCode.UInt64 => "uint64",
        PrimitiveTypeCode.Single => "float32",
        PrimitiveTypeCode.Double => "float64",
        PrimitiveTypeCode.String => "string",
        PrimitiveTypeCode.IntPtr => "native int",
        PrimitiveTypeCode.UIntPtr => "native uint",
        PrimitiveTypeCode.Object => "object",
        PrimitiveTypeCode.TypedReference => "typedref",
        _ => typeCode.ToString()
    };
    public string GetSZArrayType(string elementType) => $"{elementType}[]";
    public string GetTypeFromDefinition(MetadataReader reader, TypeDefinitionHandle handle, byte rawTypeKind) =>
        Prefix(rawTypeKind, MetadataNames.GetTypeName(reader, handle));
    public string GetTypeFromReference(MetadataReader reader, TypeReferenceHandle handle, byte rawTypeKind)
        => Prefix(rawTypeKind, MetadataNames.GetTypeReferenceName(reader, handle));
    public string GetTypeFromSpecification(
        MetadataReader reader, object? genericContext, TypeSpecificationHandle handle, byte rawTypeKind) =>
        reader.GetTypeSpecification(handle).DecodeSignature(this, genericContext);

    private static string Prefix(byte rawTypeKind, string name) => rawTypeKind == 0x11 ? $"valuetype {name}" : $"class {name}";
}
