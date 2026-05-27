#include <string.h>
#include <malloc.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <unistd.h>

extern char **environ;

#include "logger.h"
#include "emulation.h"

// box64 headers — available via target_include_directories in CMakeLists.txt
#include "box64context.h"
#include "debug.h"
#include "env.h"

#define LOG_TAG "rimdroid-emu"

// box64 core entry points (defined in box64/src/core.c)
typedef struct elfheader_s elfheader_t;
int initialize(int argc, const char** argv, char** env,
               x64emu_t** emulator, elfheader_t** elfheader, int exec);
int emulate(x64emu_t* emu, elfheader_t* elf_header);

static void* get_self_handle() {
    Dl_info info;
    if (!dladdr((void*)get_self_handle, &info)) return NULL;
    return dlopen(info.dli_fname, RTLD_NOW | RTLD_NOLOAD);
}

static const char* get_self_path() {
    Dl_info info;
    if (!dladdr((void*)rimdroid_run_elf, &info) || !info.dli_fname)
        return "/system/bin/sh";   // fallback: a file that always exists
    const char* fname = info.dli_fname;
    // On Android with extractNativeLibs=false, .so files are mapped directly
    // from the APK, so dladdr returns a ZIP-entry path like:
    //   /data/app/~~.../base.apk!/lib/arm64-v8a/librimdroidlinker.so
    // realpath() cannot resolve this; strip everything from '!' onward and
    // return just the APK path, which IS a real file realpath() can handle.
    const char* bang = strchr(fname, '!');
    if (bang) {
        static char apk_path[4096];
        size_t len = (size_t)(bang - fname);
        if (len < sizeof(apk_path)) {
            memcpy(apk_path, fname, len);
            apk_path[len] = '\0';
            return apk_path;
        }
    }
    return fname;
}

int rimdroid_emulation_init() {
    box64_pagesize = sysconf(_SC_PAGESIZE);
    if (!box64_pagesize) box64_pagesize = 4096;

    LoadEnvVariables();

    // Passing LD_LIBRARY_PATH to box64 after LoadEnvVariables
    const char* ld_path = getenv("BOX64_LD_LIBRARY_PATH");
    if (ld_path) {
        LOGI("[emulation] BOX64_LD_LIBRARY_PATH = %s", ld_path);
    } else {
        LOGE("[emulation] BOX64_LD_LIBRARY_PATH is NOT SET!");
    }

    LOGI("[emulation] pre-init done, pagesize=%lu", (unsigned long)box64_pagesize);
    return 0;
}

__attribute__((visibility("default"), used))
int rimdroid_run_elf(const char* path, int argc, const char** argv) {
    // box64 initialize() expects: argv[0]="box64", argv[1]=ELF_path, argv[2..]=ELF_args
    // Our argv already has path at [0]; prepend a fake "box64" slot (= our own path).
    //
    // CRITICAL: box64's core.c argv-compaction code (lines 1424-1435) assumes
    // all argv[] strings are stored CONTIGUOUSLY in memory (as they are in a
    // real process's argv). If argv[0] and argv[1] live in separate allocations
    // (BSS vs. stack), the computed 'diff' is huge and memset() zeroes megabytes
    // of memory → silent SIGSEGV caught by box64's own signal handler.
    //
    // Fix: allocate ALL argv strings in a single contiguous buffer.
    const char* self_path = get_self_path();
    int init_argc = argc + 1;

    // Calculate total bytes needed for the string data
    size_t total_str_len = strlen(self_path) + 1;
    for (int i = 0; i < argc; i++) total_str_len += strlen(argv[i]) + 1;

    char* str_buf = malloc(total_str_len);
    const char** init_argv = malloc((size_t)init_argc * sizeof(char*));
    if (!str_buf || !init_argv) {
        free(str_buf);
        free(init_argv);
        return -1;
    }

    // Fill the contiguous string buffer and set pointers into it
    char* p = str_buf;
    init_argv[0] = p;
    size_t n = strlen(self_path) + 1;
    memcpy(p, self_path, n); p += n;
    for (int i = 0; i < argc; i++) {
        init_argv[i + 1] = p;
        n = strlen(argv[i]) + 1;
        memcpy(p, argv[i], n); p += n;
    }

    x64emu_t*    emu        = NULL;
    elfheader_t* elf_header = NULL;

    LOGI("[emulation] calling box64 initialize() for %s (argv[0]=%s)", path, init_argv[0]);
    int init_ret = initialize(init_argc, init_argv, environ, &emu, &elf_header, 1);
    LOGI("[emulation] initialize() returned %d (emu=%p elf=%p ctx=%p)",
         init_ret, (void*)emu, (void*)elf_header, (void*)my_context);

    // Do NOT free str_buf or init_argv here!
    // box64 stores my_context->orig_argv = init_argv, and orig_argv[0] = str_buf,
    // which wrappedlibc uses as a writable buffer for prctl(PR_SET_NAME, ...).
    // Both must outlive the game session.

    if (init_ret != 0) {
        LOGE("[emulation] box64 initialize() failed for %s", path);
        free(init_argv);
        free(str_buf);
        return -1;
    }

    // box64lib must point to ourselves so box64 can find wrapped symbols
    LOGI("[emulation] box64lib before dlclose: %p", my_context ? my_context->box64lib : NULL);
    if (my_context) {
        dlclose(my_context->box64lib);
        LOGI("[emulation] dlclose OK, calling get_self_handle");
        my_context->box64lib = get_self_handle();
        LOGI("[emulation] get_self_handle returned %p", my_context->box64lib);
    }

    LOGI("[emulation] entering emulate() for %s", path);
    return emulate(emu, elf_header);
}
