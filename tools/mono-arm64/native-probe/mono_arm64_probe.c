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
typedef const char *(*mono_get_runtime_build_info_fn)(void);

static void *require_symbol(void *library, const char *name)
{
    dlerror();
    void *symbol = dlsym(library, name);
    const char *error = dlerror();
    if (error || !symbol) {
        fprintf(stderr, "PROBE phase=dlsym result=FAIL symbol=%s error=%s\n",
                name, error ? error : "not found");
        exit(20);
    }
    return symbol;
}

int main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    if (argc != 6) {
        fprintf(stderr,
                "usage: %s LIBMONO MANAGED_DIR CONFIG_DIR PROBE_DLL METHOD\n"
                "METHOD is RunBasic or RunStress\n",
                argv[0]);
        return 2;
    }

    const char *library_path = argv[1];
    const char *managed_dir = argv[2];
    const char *config_dir = argv[3];
    const char *assembly_path = argv[4];
    const char *method_name = argv[5];

    fprintf(stderr, "PROBE phase=dlopen library=%s\n", library_path);
    void *library = dlopen(library_path, RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        fprintf(stderr, "PROBE phase=dlopen result=FAIL error=%s\n", dlerror());
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
#undef LOAD

    mono_get_runtime_build_info_fn mono_get_runtime_build_info =
        (mono_get_runtime_build_info_fn)dlsym(library, "mono_get_runtime_build_info");
    fprintf(stderr, "PROBE phase=runtime version=%s\n",
            mono_get_runtime_build_info ? mono_get_runtime_build_info() : "unknown");

    mono_set_dirs(managed_dir, config_dir);
    mono_set_assemblies_path(managed_dir);
    mono_config_parse(NULL);

    fprintf(stderr, "PROBE phase=jit_init\n");
    MonoDomain *domain = mono_jit_init_version("RimDroidMonoArm64Probe", "v4.0.30319");
    if (!domain) {
        fprintf(stderr, "PROBE phase=jit_init result=FAIL\n");
        return 30;
    }

    fprintf(stderr, "PROBE phase=assembly_open path=%s\n", assembly_path);
    MonoAssembly *assembly = mono_domain_assembly_open(domain, assembly_path);
    if (!assembly) {
        fprintf(stderr, "PROBE phase=assembly_open result=FAIL\n");
        mono_jit_cleanup(domain);
        return 31;
    }

    MonoImage *image = mono_assembly_get_image(assembly);
    MonoClass *klass = image
        ? mono_class_from_name(image, "RimDroid.MonoArm64Probe", "EntryPoint")
        : NULL;
    MonoMethod *method = klass
        ? mono_class_get_method_from_name(klass, method_name, 0)
        : NULL;
    if (!method) {
        fprintf(stderr, "PROBE phase=method_lookup result=FAIL method=%s\n", method_name);
        mono_jit_cleanup(domain);
        return 32;
    }

    fprintf(stderr, "PROBE phase=invoke method=%s\n", method_name);
    MonoObject *exception = NULL;
    MonoObject *boxed_result = mono_runtime_invoke(method, NULL, NULL, &exception);
    if (exception) {
        fprintf(stderr, "PROBE phase=invoke result=MANAGED_EXCEPTION\n");
        mono_jit_cleanup(domain);
        return 33;
    }
    if (!boxed_result) {
        fprintf(stderr, "PROBE phase=invoke result=NULL\n");
        mono_jit_cleanup(domain);
        return 34;
    }

    int32_t value = *(int32_t *)mono_object_unbox(boxed_result);
    fprintf(stderr, "PROBE phase=invoke result=0x%04x\n", (unsigned int)value);
    mono_jit_cleanup(domain);
    dlclose(library);

    if (value != 0x5244) {
        fprintf(stderr, "PROBE verdict=FAIL expected=0x5244\n");
        return 40;
    }
    fprintf(stderr, "PROBE verdict=PASS\n");
    return 0;
}
