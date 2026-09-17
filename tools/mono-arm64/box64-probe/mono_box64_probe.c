#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

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
typedef MonoObject *(*mono_get_exception_argument_null_fn)(const char *);
typedef void (*mono_raise_exception_fn)(MonoObject *);

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

static volatile uintptr_t encoded_managed_object;
static volatile uintptr_t *guest_root_slot;
static const uintptr_t managed_object_mask = UINT64_C(0xa5a55a5af00dc33c);

__attribute__((noinline)) static void native_publish_object(void *value)
{
    encoded_managed_object = (uintptr_t)value ^ managed_object_mask;
}

__attribute__((noinline)) static void native_install_guest_root(void)
{
    uintptr_t value = encoded_managed_object ^ managed_object_mask;
    *guest_root_slot = value;
    encoded_managed_object = 0;
    __asm__ __volatile__("" : : "r"(value) : "memory");
}

__attribute__((noinline)) static void native_clear_guest_root(void)
{
    *guest_root_slot = 0;
    encoded_managed_object = 0;
}

/*
 * P4: managed exceptions raised by an x86 internal call.
 *
 * rd_guest_throw loads garbage into every callee-saved register and then raises through
 * mono_raise_exception, which never returns, so its epilogue never restores them. That is exactly
 * the state a Unity internal call leaves behind when it raises.
 *
 * rd_invoke_guarded runs mono_runtime_invoke with canaries in all callee-saved registers and
 * records in rd_guard_bad which of them, or the stack pointer, did not survive. A consistent guest
 * must come back with rd_guard_bad == 0 however many exceptions crossed the bridge meanwhile.
 */
static mono_get_exception_argument_null_fn p4_get_exception_argument_null;
static mono_raise_exception_fn p4_raise_exception;
uint64_t rd_guard_bad;
int rd_guest_throw(int marker);
MonoObject *rd_invoke_guarded(mono_runtime_invoke_fn fn, MonoMethod *method, MonoObject **exception);
void rd_guest_throw_raise(void);

__attribute__((noinline, used)) void rd_guest_throw_raise(void)
{
    p4_raise_exception(p4_get_exception_argument_null("rimdroid_p4"));
}

__asm__(
    ".text\n"
    ".globl rd_guest_throw\n"
    ".type rd_guest_throw, @function\n"
    "rd_guest_throw:\n"
    "    push %rbx\n"
    "    push %r12\n"
    "    push %r13\n"
    "    push %r14\n"
    "    push %r15\n"
    "    movabs $0x524400000000bad1, %rbx\n"
    "    movabs $0x524400000000bad2, %r12\n"
    "    movabs $0x524400000000bad3, %r13\n"
    "    movabs $0x524400000000bad4, %r14\n"
    "    movabs $0x524400000000bad5, %r15\n"
    "    call rd_guest_throw_raise\n"
    "    pop %r15\n"
    "    pop %r14\n"
    "    pop %r13\n"
    "    pop %r12\n"
    "    pop %rbx\n"
    "    mov $-1, %eax\n"
    "    ret\n"
    ".size rd_guest_throw, .-rd_guest_throw\n"
    ".globl rd_invoke_guarded\n"
    ".type rd_invoke_guarded, @function\n"
    "rd_invoke_guarded:\n"
    "    push %rbp\n"
    "    push %rbx\n"
    "    push %r12\n"
    "    push %r13\n"
    "    push %r14\n"
    "    push %r15\n"
    "    sub $8, %rsp\n"
    "    mov %rsp, %rbp\n"
    "    movabs $0xc0de00000000cafe, %rbx\n"
    "    movabs $0xc0de00000000c012, %r12\n"
    "    movabs $0xc0de00000000c013, %r13\n"
    "    movabs $0xc0de00000000c014, %r14\n"
    "    movabs $0xc0de00000000c015, %r15\n"
    "    mov %rdi, %rax\n"
    "    mov %rdx, %rcx\n"
    "    mov %rsi, %rdi\n"
    "    xor %esi, %esi\n"
    "    xor %edx, %edx\n"
    "    call *%rax\n"
    "    xor %r10d, %r10d\n"
    "    movabs $0xc0de00000000cafe, %r11\n"
    "    cmp %r11, %rbx\n"
    "    je 1f\n"
    "    or $1, %r10\n"
    "1:  movabs $0xc0de00000000c012, %r11\n"
    "    cmp %r11, %r12\n"
    "    je 2f\n"
    "    or $2, %r10\n"
    "2:  movabs $0xc0de00000000c013, %r11\n"
    "    cmp %r11, %r13\n"
    "    je 3f\n"
    "    or $4, %r10\n"
    "3:  movabs $0xc0de00000000c014, %r11\n"
    "    cmp %r11, %r14\n"
    "    je 4f\n"
    "    or $8, %r10\n"
    "4:  movabs $0xc0de00000000c015, %r11\n"
    "    cmp %r11, %r15\n"
    "    je 5f\n"
    "    or $16, %r10\n"
    "5:  cmp %rsp, %rbp\n"
    "    je 6f\n"
    "    or $32, %r10\n"
    "6:  mov %r10, rd_guard_bad(%rip)\n"
    "    add $8, %rsp\n"
    "    pop %r15\n"
    "    pop %r14\n"
    "    pop %r13\n"
    "    pop %r12\n"
    "    pop %rbx\n"
    "    pop %rbp\n"
    "    ret\n"
    ".size rd_invoke_guarded, .-rd_invoke_guarded\n"
);

/*
 * Transition cost benchmark. RunIcallBenchmark times one million ARM64 Mono -> x86 internal calls
 * against the same number of plain managed calls; the host times one million x86 -> ARM64 Mono
 * API calls against plain x86 calls. The difference is what the bridge adds per call.
 */
static int64_t bench_now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000 + ts.tv_nsec;
}

static int native_bench_identity(int value)
{
    return value;
}

static int64_t native_now_ns(void)
{
    return bench_now_ns();
}

static void native_report_bench(int64_t reverse_icall_ns, int64_t managed_call_ns, int calls)
{
    double icall = (double)reverse_icall_ns / calls;
    double managed = (double)managed_call_ns / calls;
    fprintf(stderr, "BOX64_MONO_PROBE bench reverse_icall_ns=%.1f managed_call_ns=%.1f "
            "reverse_overhead_ns=%.1f calls=%d\n", icall, managed, icall - managed, calls);
}

__attribute__((noinline)) static const char *bench_x86_identity(const char *value)
{
    return value;
}

static void run_forward_bench(mono_class_get_name_fn class_get_name, MonoClass *klass)
{
    const int calls = 1000000;
    const char *volatile sink = NULL;
    for (int i = 0; i < 20000; i++) {
        sink = class_get_name(klass);
        sink = bench_x86_identity(sink);
    }
    int64_t t0 = bench_now_ns();
    for (int i = 0; i < calls; i++)
        sink = class_get_name(klass);
    int64_t t1 = bench_now_ns();
    for (int i = 0; i < calls; i++)
        sink = bench_x86_identity(sink);
    int64_t t2 = bench_now_ns();
    double api = (double)(t1 - t0) / calls;
    double plain = (double)(t2 - t1) / calls;
    fprintf(stderr, "BOX64_MONO_PROBE bench forward_api_ns=%.1f x86_call_ns=%.1f "
            "forward_overhead_ns=%.1f calls=%d\n", api, plain, api - plain, calls);
}

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
    LOAD(mono_get_exception_argument_null);
    LOAD(mono_raise_exception);
#undef LOAD
    p4_get_exception_argument_null = mono_get_exception_argument_null;
    p4_raise_exception = mono_raise_exception;

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
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativePublishObject",
        (const void *)native_publish_object);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeInstallGuestRoot",
        (const void *)native_install_guest_root);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeClearGuestRoot",
        (const void *)native_clear_guest_root);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeThrowFromGuest",
        (const void *)rd_guest_throw);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeBenchIdentity",
        (const void *)native_bench_identity);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeNowNs",
        (const void *)native_now_ns);
    mono_add_internal_call(
        "RimDroid.MonoArm64Probe.EntryPoint::NativeReportBench",
        (const void *)native_report_bench);

    volatile uintptr_t guest_root = 0;
    guest_root_slot = &guest_root;

    MonoAssembly *assembly = mono_domain_assembly_open(domain, argv[3]);
    MonoImage *image = assembly ? mono_assembly_get_image(assembly) : NULL;
    MonoClass *klass = image ? mono_class_from_name(image, "RimDroid.MonoArm64Probe", "EntryPoint") : NULL;
    MonoMethod *method = klass ? mono_class_get_method_from_name(klass, argv[4], 0) : NULL;
    if (!method) {
        fprintf(stderr, "BOX64_MONO_PROBE phase=method_lookup result=FAIL\n");
        mono_jit_cleanup(domain);
        return 31;
    }

    if (strcmp(argv[4], "RunIcallBenchmark") == 0)
        run_forward_bench(mono_class_get_name, klass);

    MonoObject *exception = NULL;
    MonoObject *boxed_result = rd_invoke_guarded(mono_runtime_invoke, method, &exception);
    fprintf(stderr, "BOX64_MONO_PROBE guest_state=%s mask=0x%llx\n",
            rd_guard_bad ? "CORRUPT" : "INTACT", (unsigned long long)rd_guard_bad);
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
    guest_root = 0;
    guest_root_slot = NULL;
    mono_jit_cleanup(domain);
    dlclose(library);
    int passed = value == 0x5244 && rd_guard_bad == 0;
    fprintf(stderr, "BOX64_MONO_PROBE verdict=%s value=0x%04x\n",
            passed ? "PASS" : "FAIL", (unsigned int)value);
    return passed ? 0 : 40;
}
