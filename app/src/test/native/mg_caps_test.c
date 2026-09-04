/* cc -std=c11 -Wall -Wextra -Werror mg_caps_test.c -o mg_caps_test && ./mg_caps_test */
#define RD_MG_CAPS_TEST
#include "../../main/cpp/mg_caps.c"
#include <assert.h>

static const char* mock_extensions;
static int mock_minor, mock_probe_ok, binding, deletes, storage_calls;

static const unsigned char* mock_get_string(unsigned int name) {
    return (const unsigned char*)(name == RD_GL_EXTENSIONS ? mock_extensions : "MobileGlues test");
}
static const unsigned char* mock_get_string_i(unsigned int name, unsigned int index) {
    (void)name; (void)index;
    return (const unsigned char*)"passthrough";
}
static void mock_get_integer(unsigned int name, int* value) {
    switch (name) {
        case 0x821B: *value = 4; break;
        case 0x821C: *value = mock_minor; break;
        case RD_GL_TEXTURE_BINDING_2D: *value = binding; break;
        default: *value = 123;
    }
}
static void mock_gen(int n, unsigned int* id) { assert(n == 1); *id = 77; }
static void mock_bind(unsigned int target, unsigned int id) {
    assert(target == RD_GL_TEXTURE_2D); binding = (int)id;
}
static void mock_delete(int n, const unsigned int* id) {
    assert(n == 1 && *id == 77); ++deletes;
}
static void mock_storage(unsigned int target, int levels, unsigned int format, int w, int h) {
    assert(target == RD_GL_TEXTURE_2D && levels == 3 && format == 0x8058 && w == 4 && h == 4);
    ++storage_calls;
}
static void mock_param(unsigned int target, unsigned int name, int* value) {
    assert(target == RD_GL_TEXTURE_2D && binding == 77);
    *value = mock_probe_ok ? (name == RD_GL_TEXTURE_IMMUTABLE_LEVELS ? 3 : 1) : 0;
}
static void* mock_resolve(void* handle, const char* name);
static void* mock_get_proc(const char* name) { return mock_resolve((void*)1, name); }
static void* mock_get_proc_arb(const char* name) { return mock_resolve((void*)1, name); }
static void* mock_resolve(void* handle, const char* name) {
    assert(handle == (void*)1);
    if (!strcmp(name, "glGetString")) return (void*)mock_get_string;
    if (!strcmp(name, "glGetStringi")) return (void*)mock_get_string_i;
    if (!strcmp(name, "glGetIntegerv")) return (void*)mock_get_integer;
    if (!strcmp(name, "glXGetProcAddress")) return (void*)mock_get_proc;
    if (!strcmp(name, "glXGetProcAddressARB")) return (void*)mock_get_proc_arb;
    if (!strcmp(name, "glGenTextures")) return (void*)mock_gen;
    if (!strcmp(name, "glBindTexture")) return (void*)mock_bind;
    if (!strcmp(name, "glDeleteTextures")) return (void*)mock_delete;
    if (!strcmp(name, "glTexStorage2D") || !strcmp(name, "glTexStorage1D")
            || !strcmp(name, "glTexStorage3D")) return (void*)mock_storage;
    if (!strcmp(name, "glGetTexParameteriv")) return (void*)mock_param;
    return NULL;
}

static void reset(const char* list) {
    atomic_store(&ready, 0);
    atomic_store(&query_logged, 0);
    free(extensions); extensions = NULL;
    free(extension_tokens); extension_tokens = NULL;
    free(extension_items); extension_items = NULL;
    extension_count = 0;
    mock_extensions = list;
    mock_minor = 0; mock_probe_ok = 1; binding = 9; deletes = storage_calls = 0;
}

static void check_enumeration(const char* expected, int count) {
    assert(rimdroid_mg_caps_init((void*)1, mock_resolve));
    const unsigned char* (*get)(unsigned int) = rimdroid_mg_caps_override("glGetString", (void*)mock_get_string);
    const unsigned char* (*get_i)(unsigned int, unsigned int) =
        rimdroid_mg_caps_override("glGetStringi", (void*)mock_get_string_i);
    void (*get_int)(unsigned int, int*) = rimdroid_mg_caps_override("glGetIntegerv", (void*)mock_get_integer);
    assert(!strcmp((const char*)get(RD_GL_EXTENSIONS), expected));
    int n = -1;
    get_int(RD_GL_NUM_EXTENSIONS, &n);
    assert(n == count);
    char joined[1024] = "";
    for (int i = 0; i < n; ++i) {
        if (i) strcat(joined, " ");
        strcat(joined, (const char*)get_i(RD_GL_EXTENSIONS, (unsigned int)i));
    }
    assert(!strcmp(joined, expected));
    assert(get_i(RD_GL_EXTENSIONS, (unsigned int)n) == NULL);
    assert(get_i(RD_GL_EXTENSIONS, ~0u) == NULL);
    assert(!strcmp((const char*)get(0x1F02), "MobileGlues test"));
    assert(!strcmp((const char*)get_i(0x1F02, 0), "passthrough"));
    get_int(0x0BA2, &n);
    assert(n == 123);
    assert(binding == 9 && deletes == 1 && storage_calls == 1);
    /* Repeated initialization must not allocate or change the snapshot. */
    const unsigned char* snapshot = get(RD_GL_EXTENSIONS);
    assert(rimdroid_mg_caps_init((void*)1, mock_resolve));
    assert(snapshot == get(RD_GL_EXTENSIONS) && storage_calls == 1);
    void* (*get_proc)(const char*) = rimdroid_mg_caps_override("glXGetProcAddress", (void*)mock_get_proc);
    assert(get_proc("glGetString") == (void*)get);
    assert(get_proc("glTexStorage2D") == (void*)mock_storage);
    assert(get_proc("missing") == NULL);
    get_proc = rimdroid_mg_caps_override("glXGetProcAddressARB", (void*)mock_get_proc_arb);
    assert(get_proc("glGetString") == (void*)get);
    /* An unrelated driver's address, even under the same symbol name, is untouched. */
    assert(rimdroid_mg_caps_override("glGetString", (void*)mock_get_string_i) == (void*)mock_get_string_i);
    assert(rimdroid_mg_caps_override("unknown", (void*)mock_get_string) == (void*)mock_get_string);
    assert(rimdroid_mg_caps_override("glGetString", NULL) == NULL);
}

int main(void) {
    reset("GL_ARB_alpha GL_ARB_texture_compression_bptc GL_EXT_texture_compression_bptc GL_ARB_beta ");
    check_enumeration("GL_ARB_alpha GL_ARB_beta GL_ARB_texture_storage", 3);
    reset("  GL_ARB_texture_storage GL_ARB_texture_storage  GL_EXT_foo  ");
    check_enumeration("GL_ARB_texture_storage GL_EXT_foo", 2);
    reset("");
    check_enumeration("GL_ARB_texture_storage", 1);
    reset("X Y Z");
    check_enumeration("X Y Z GL_ARB_texture_storage", 4);
    reset("GL_EXT_foo");
    mock_probe_ok = 0;
    assert(!rimdroid_mg_caps_init((void*)1, mock_resolve));
    assert(binding == 9 && deletes == 1);
    assert(rimdroid_mg_caps_override("glGetString", (void*)mock_get_string) == (void*)mock_get_string);
    reset("GL_EXT_foo");
    mock_minor = 2;
    assert(!rimdroid_mg_caps_init((void*)1, mock_resolve));
    assert(storage_calls == 0);
    reset(NULL);
    assert(!rimdroid_mg_caps_init((void*)1, mock_resolve));
    assert(!rimdroid_mg_caps_init(NULL, mock_resolve));
    assert(!rimdroid_mg_caps_init((void*)1, NULL));
    reset("");
    puts("MG caps tests passed");
    return 0;
}
