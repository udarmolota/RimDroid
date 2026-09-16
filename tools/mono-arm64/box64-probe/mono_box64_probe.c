#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

typedef struct _MonoAssembly MonoAssembly;
typedef struct _MonoClass MonoClass;
typedef struct _MonoDomain MonoDomain;
typedef struct _MonoImage MonoImage;
typedef struct _MonoMethod MonoMethod;
typedef struct _MonoObject MonoObject;

typedef void (*mono_set_dirs_fn)(const char *, const char *);
typedef void (*mono_set_assemblies_path_fn)(const char *);
typedef void (*mono_config_parse_fn)(const char *);
typedef MonoDomain *(*mono_jit_init_version_fn)(const char *, const char *);
typedef void (*mono_jit_cleanup_fn)(MonoDomain *);
typedef MonoAssembly *(*mono_domain_assembly_open_fn)(MonoDomain *, const char *);
typedef MonoImage *(*mono_assembly_get_image_fn)(MonoAssembly *);
typedef MonoClass *(*mono_class_from_name_fn)(MonoImage *, const char *, const char *);
typedef MonoMethod *(*mono_class_get_method_from_name_fn)(MonoClass *, const char *, int);
typedef MonoObject *(*mono_runtime_invoke_fn)(MonoMethod *, void *, void **, MonoObject **);
typedef void *(*mono_object_unbox_fn)(MonoObject *);
typedef MonoClass *(*mono_object_get_class_fn)(MonoObject *);
typedef const char *(*mono_class_get_name_fn)(MonoClass *);
typedef const char *(*mono_class_get_namespace_fn)(MonoClass *);
typedef const char *(*mono_get_runtime_build_info_fn)(void);
typedef void (*mono_add_internal_call_fn)(const char *, const void *);

static int native_add(int left, int right)
{
    fprintf(stderr, "BOX64_MONO_PROBE phase=reverse_icall left=0x%x right=0x%x\n", left, right);
    return left + right;
}

static int64_t native_add_long(int64_t left, int64_t right)
{
    return left + right;
}

static void *native_pointer_identity(void *value)
{
    return value;
}

static double native_add_double(double left, double right)
{
    return left + right;
}

static double native_add_float_args(float left, float right)
{
    return (double)(left + right);
}

static int64_t native_sum_nine(
    int64_t a, int64_t b, int64_t c, int64_t d, int64_t e,
    int64_t f, int64_t g, int64_t h, int64_t i)
{
    return a + b + c + d + e + f + g + h + i;
}

static int8_t native_sbyte_identity(int8_t value) { return value; }
static uint8_t native_byte_identity(uint8_t value) { return value; }
static int16_t native_int16_identity(int16_t value) { return value; }
static uint16_t native_uint16_identity(uint16_t value) { return value; }

static void *require_symbol(void *library, const char *name)
{
    dlerror();
    void *symbol = dlsym(library, name);
    const char *error = dlerror();
    if (error || !symbol) {
        fprintf(stderr, "BOX64_MONO_PROBE phase=dlsym result=FAIL symbol=%s error=%s\n",
                name, error ? error : "not found");
        exit(20);
    }
    return symbol;
}

int main(int argc, char **argv)
{
    if (argc != 5) {
        fprintf(stderr, "usage: %s MANAGED_DIR CONFIG_DIR PROBE_DLL METHOD\n", argv[0]);
        return 2;
    }

    fprintf(stderr, "BOX64_MONO_PROBE phase=dlopen library=librdmonoprobe.so\n");
    void *library = dlopen("librdmonoprobe.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        fprintf(stderr, "BOX64_MONO_PROBE phase=dlopen result=FAIL error=%s\n", dlerror());
        return 10;
    }

#define LOAD(name) name##_fn name = (name##_fn)require_symbol(library, #name)
    LOAD(mono_set_dirs);
    LOAD(mono_set_assemblies_path);
    LOAD(mono_config_parse);
    LOAD(mono_jit_init_version);
    LOAD(mono_jit_cleanup);
    LOAD(mono_domain_assembly_open);
    LOAD(mono_assembly_get_image);
    LOAD(mono_class_from_name);
    LOAD(mono_class_get_method_from_name);
    LOAD(mono_runtime_invoke);
    LOAD(mono_object_unbox);
    LOAD(mono_object_get_class);
    LOAD(mono_class_get_name);
    LOAD(mono_class_get_namespace);
    LOAD(mono_get_runtime_build_info);
    LOAD(mono_add_internal_call);
#undef LOAD

    fprintf(stderr, "BOX64_MONO_PROBE phase=runtime version=%s\n", mono_get_runtime_build_info());
    mono_set_dirs(argv[1], argv[2]);
    mono_set_assemblies_path(argv[1]);
    mono_config_parse(NULL);

    fprintf(stderr, "BOX64_MONO_PROBE phase=jit_init\n");
    MonoDomain *domain = mono_jit_init_version("RimDroidBox64MonoProbe", "v4.0.30319");
    if (!domain) return 30;

    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeAdd",
        (const void *)native_add);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeAddLong",
        (const void *)native_add_long);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativePointerIdentity",
        (const void *)native_pointer_identity);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeAddDouble",
        (const void *)native_add_double);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeAddFloatArgs",
        (const void *)native_add_float_args);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeSumNine",
        (const void *)native_sum_nine);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeSByteIdentity",
        (const void *)native_sbyte_identity);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeByteIdentity",
        (const void *)native_byte_identity);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeInt16Identity",
        (const void *)native_int16_identity);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeUInt16Identity",
        (const void *)native_uint16_identity);

    MonoAssembly *assembly = mono_domain_assembly_open(domain, argv[3]);
    MonoImage *image = assembly ? mono_assembly_get_image(assembly) : NULL;
    MonoClass *klass = image ? mono_class_from_name(image, "RimDroid.MonoArm64Probe", "EntryPoint") : NULL;
    MonoMethod *method = klass ? mono_class_get_method_from_name(klass, argv[4], 0) : NULL;
    if (!method) {
        fprintf(stderr, "BOX64_MONO_PROBE phase=method_lookup result=FAIL\n");
        mono_jit_cleanup(domain);
        return 31;
    }

    MonoObject *exception = NULL;
    MonoObject *boxed_result = mono_runtime_invoke(method, NULL, NULL, &exception);
    if (exception || !boxed_result) {
        if (exception) {
            MonoClass *exception_class = mono_object_get_class(exception);
            fprintf(stderr, "BOX64_MONO_PROBE exception=%s.%s\n",
                    exception_class ? mono_class_get_namespace(exception_class) : "?",
                    exception_class ? mono_class_get_name(exception_class) : "?");
        }
        fprintf(stderr, "BOX64_MONO_PROBE phase=invoke result=%s\n", exception ? "MANAGED_EXCEPTION" : "NULL");
        mono_jit_cleanup(domain);
        return 32;
    }

    int32_t value = *(int32_t *)mono_object_unbox(boxed_result);
    mono_jit_cleanup(domain);
    dlclose(library);
    fprintf(stderr, "BOX64_MONO_PROBE verdict=%s value=0x%04x\n",
            value == 0x5244 ? "PASS" : "FAIL", (unsigned int)value);
    return value == 0x5244 ? 0 : 40;
}
