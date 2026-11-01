// Copies a framework facade, adding type forwarders to another assembly: the game looks for its
// runtime's own types (WaylithClr.*) in System.Runtime, and only a facade of that exact identity can
// answer. Usage: AddForwards stock.dll out.dll TargetAssembly Namespace.Type...
using System;
using System.IO;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;

internal static class AddForwards
{
    const TypeAttributes Forwarder = (TypeAttributes)0x00200000;

    static int Main(string[] args)
    {
        using (PEReader pe = new PEReader(File.OpenRead(args[0])))
        {
            MetadataReader md = pe.GetMetadataReader();
            if (md.TypeDefinitions.Count != 1) throw new InvalidDataException("not a plain facade");
            MetadataBuilder b = new MetadataBuilder();
            ModuleDefinition module = md.GetModuleDefinition();
            b.AddModule(0, b.GetOrAddString(md.GetString(module.Name)), b.GetOrAddGuid(md.GetGuid(module.Mvid)),
                default(GuidHandle), default(GuidHandle));
            AssemblyDefinition def = md.GetAssemblyDefinition();
            b.AddAssembly(b.GetOrAddString(md.GetString(def.Name)), def.Version, default(StringHandle),
                b.GetOrAddBlob(md.GetBlobBytes(def.PublicKey)), def.Flags, def.HashAlgorithm);
            foreach (AssemblyReferenceHandle h in md.AssemblyReferences)
            {
                AssemblyReference r = md.GetAssemblyReference(h);
                b.AddAssemblyReference(b.GetOrAddString(md.GetString(r.Name)), r.Version, default(StringHandle),
                    b.GetOrAddBlob(md.GetBlobBytes(r.PublicKeyOrToken)), r.Flags, default(BlobHandle));
            }
            AssemblyReferenceHandle target = b.AddAssemblyReference(b.GetOrAddString(args[2]), new Version(0, 0, 0, 0),
                default(StringHandle), default(BlobHandle), 0, default(BlobHandle));
            b.AddTypeDefinition(0, default(StringHandle), b.GetOrAddString("<Module>"), default(EntityHandle),
                MetadataTokens.FieldDefinitionHandle(1), MetadataTokens.MethodDefinitionHandle(1));
            // Rows keep their order, so nested forwarders still point at their parents.
            foreach (ExportedTypeHandle h in md.ExportedTypes)
            {
                ExportedType t = md.GetExportedType(h);
                b.AddExportedType(t.Attributes, b.GetOrAddString(md.GetString(t.Namespace)),
                    b.GetOrAddString(md.GetString(t.Name)), t.Implementation, 0);
            }
            for (int i = 3; i < args.Length; i++)
            {
                int dot = args[i].LastIndexOf('.');
                b.AddExportedType(Forwarder, b.GetOrAddString(args[i].Substring(0, dot)),
                    b.GetOrAddString(args[i].Substring(dot + 1)), target, 0);
            }
            ManagedPEBuilder image = new ManagedPEBuilder(PEHeaderBuilder.CreateLibraryHeader(),
                new MetadataRootBuilder(b), new BlobBuilder());
            BlobBuilder blob = new BlobBuilder();
            image.Serialize(blob);
            File.WriteAllBytes(args[1], blob.ToArray());
        }
        return 0;
    }
}
