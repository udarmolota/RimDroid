#include "mg_caps.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#ifndef RD_MG_CAPS_TEST
#define LOG_TAG "rimdroid-mg-caps"
#include "logger.h"
#else
#include <stdio.h>
#define LOGI(...) ((void)0)
#define LOGW(...) ((void)0)
#endif

#define RD_GL_EXTENSIONS 0x1F03u
#define RD_GL_NUM_EXTENSIONS 0x821Du
#define RD_GL_TEXTURE_2D 0x0DE1u
#define RD_GL_TEXTURE_BINDING_2D 0x8069u
#define RD_GL_TEXTURE_IMMUTABLE_FORMAT 0x912Fu
#define RD_GL_TEXTURE_IMMUTABLE_LEVELS 0x82DFu

static const unsigned char* (*mg_get_string)(unsigned int);
static const unsigned char* (*mg_get_string_i)(unsigned int, unsigned int);
static void (*mg_get_integer)(unsigned int, int*);
static void* (*mg_get_proc)(const char*);
static void* (*mg_get_proc_arb)(const char*);
static char* extensions;
static char* extension_tokens;
static const char** extension_items;
static int extension_count;
static atomic_bool ready;
static atomic_bool query_logged;

static void log_query(void) {
    if (!atomic_exchange_explicit(&query_logged, 1, memory_order_relaxed))
        LOGI("MG CAPS: guest queried patched extensions (storage=1, BPTC=0, count=%d)",
             extension_count);
}

static const unsigned char* caps_get_string(unsigned int name) {
    if (name != RD_GL_EXTENSIONS) return mg_get_string(name);
    log_query();
    return (const unsigned char*)extensions;
}

static const unsigned char* caps_get_string_i(unsigned int name, unsigned int index) {
    if (name != RD_GL_EXTENSIONS) return mg_get_string_i(name, index);
    log_query();
    return index < (unsigned int)extension_count
        ? (const unsigned char*)extension_items[index] : NULL;
}

static void caps_get_integer(unsigned int name, int* value) {
    if (name != RD_GL_NUM_EXTENSIONS) { mg_get_integer(name, value); return; }
    log_query();
    if (value) *value = extension_count;
}

static void* caps_get_proc(const char* name) {
    return rimdroid_mg_caps_override(name, mg_get_proc(name));
}

static void* caps_get_proc_arb(const char* name) {
    return rimdroid_mg_caps_override(name, mg_get_proc_arb(name));
}

void* rimdroid_mg_caps_override(const char* name, void* address) {
    if (!atomic_load_explicit(&ready, memory_order_acquire) || !address || !name)
        return address;
    if (!strcmp(name, "glGetString") && address == (void*)mg_get_string)
        return (void*)caps_get_string;
    if (!strcmp(name, "glGetStringi") && address == (void*)mg_get_string_i)
        return (void*)caps_get_string_i;
    if (!strcmp(name, "glGetIntegerv") && address == (void*)mg_get_integer)
        return (void*)caps_get_integer;
    if (!strcmp(name, "glXGetProcAddress") && address == (void*)mg_get_proc)
        return (void*)caps_get_proc;
    if (!strcmp(name, "glXGetProcAddressARB") && address == (void*)mg_get_proc_arb)
        return (void*)caps_get_proc_arb;
    return address;
}

static int probe_storage(void* handle, rd_mg_resolver resolve) {
    void (*gen)(int, unsigned int*) = resolve(handle, "glGenTextures");
    void (*bind)(unsigned int, unsigned int) = resolve(handle, "glBindTexture");
    void (*remove)(int, const unsigned int*) = resolve(handle, "glDeleteTextures");
    void (*storage)(unsigned int, int, unsigned int, int, int) = resolve(handle, "glTexStorage2D");
    void (*get_param)(unsigned int, unsigned int, int*) = resolve(handle, "glGetTexParameteriv");
    if (!gen || !bind || !remove || !storage || !get_param
            || !resolve(handle, "glTexStorage1D") || !resolve(handle, "glTexStorage3D"))
        return 0;

    int old_binding = 0, immutable = 0, levels = 0;
    unsigned int texture = 0;
    mg_get_integer(RD_GL_TEXTURE_BINDING_2D, &old_binding);
    gen(1, &texture);
    if (!texture) return 0;
    bind(RD_GL_TEXTURE_2D, texture);
    storage(RD_GL_TEXTURE_2D, 3, 0x8058u /* RGBA8 */, 4, 4);
    get_param(RD_GL_TEXTURE_2D, RD_GL_TEXTURE_IMMUTABLE_FORMAT, &immutable);
    get_param(RD_GL_TEXTURE_2D, RD_GL_TEXTURE_IMMUTABLE_LEVELS, &levels);
    bind(RD_GL_TEXTURE_2D, (unsigned int)old_binding);
    remove(1, &texture);
    LOGI("MG CAPS: TexStorage2D probe immutable=%d levels=%d (expected 1/3)", immutable, levels);
    return immutable == 1 && levels == 3;
}

int rimdroid_mg_caps_init(void* handle, rd_mg_resolver resolve) {
    if (atomic_load_explicit(&ready, memory_order_acquire)) return 1;
    if (!handle || !resolve) return 0;
    mg_get_string = resolve(handle, "glGetString");
    mg_get_string_i = resolve(handle, "glGetStringi");
    mg_get_integer = resolve(handle, "glGetIntegerv");
    mg_get_proc = resolve(handle, "glXGetProcAddress");
    mg_get_proc_arb = resolve(handle, "glXGetProcAddressARB");
    if (!mg_get_string || !mg_get_string_i || !mg_get_integer) return 0;

    int major = 0, minor = 0;
    mg_get_integer(0x821Bu /* GL_MAJOR_VERSION */, &major);
    mg_get_integer(0x821Cu /* GL_MINOR_VERSION */, &minor);
    if (major != 4 || minor != 0) {
        LOGW("MG CAPS: skipped for GL %d.%d (test requires 4.0)", major, minor);
        return 0;
    }
    if (!probe_storage(handle, resolve)) {
        LOGW("MG CAPS: storage probe failed; keeping unmodified GL 4.0 capabilities");
        return 0;
    }

    const char* original = (const char*)mg_get_string(RD_GL_EXTENSIONS);
    if (!original) return 0;
    const char* storage_ext = "GL_ARB_texture_storage";
    size_t length = strlen(original);
    size_t capacity = length + strlen(storage_ext) + 2;
    char* tokens = malloc(capacity);
    char* joined = malloc(capacity);
    const char** items = malloc((length / 2 + 2) * sizeof(*items));
    if (!tokens || !joined || !items) {
        free(tokens); free(joined); free(items);
        return 0;
    }
    memcpy(tokens, original, length + 1);
    char* extra = tokens + length + 1;
    strcpy(extra, storage_ext);

    int count = 0, has_storage = 0;
    size_t used = 0;
    char* cursor = tokens;
    /* Publish one immutable snapshot for both string and indexed GL enumeration. */
    while (*cursor) {
        while (*cursor == ' ') ++cursor;
        if (!*cursor) break;
        char* token = cursor;
        while (*cursor && *cursor != ' ') ++cursor;
        if (*cursor) *cursor++ = '\0';
        if (!strcmp(token, "GL_ARB_texture_compression_bptc")
                || !strcmp(token, "GL_EXT_texture_compression_bptc")) continue;
        if (!strcmp(token, storage_ext)) {
            if (has_storage) continue;
            has_storage = 1;
        }
        items[count++] = token;
    }
    if (!has_storage) items[count++] = extra;
    for (int i = 0; i < count; ++i) {
        if (i) joined[used++] = ' ';
        size_t n = strlen(items[i]);
        memcpy(joined + used, items[i], n);
        used += n;
    }
    joined[used] = '\0';
    extensions = joined;
    extension_tokens = tokens;
    extension_items = items;
    extension_count = count;
    atomic_store_explicit(&ready, 1, memory_order_release);
    LOGI("MG CAPS: profile=4.0+ARB_texture_storage, BPTC hidden; shrink still needs Unity TexStorage uploads");
    return 1;
}
