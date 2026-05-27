#include <string.h>
#include <stdio.h>
#include <dlfcn.h>
#include <android/dlext.h>
#include <malloc.h>
#include <unistd.h>
#include "logger.h"
#include "emulation.h"
#include "rimdroid_globals.h"
#include "liblinkernsbypass/android_linker_ns.h"

#define LOG_TAG "rimdroid-linker"

static void* (*loader_dlopen)(const char* filename, int flags, const void* caller);
static void* (*loader_dlsym)(void* handle, const char* symbol, const void* caller);
static void* (*loader_android_dlopen_ext)(const char* filename,
                                          int flag,
                                          const android_dlextinfo* extinfo,
                                          const void* caller_addr);
static void* vulkan_driver_handle;
static void* vulkan_loader_handle;

__attribute__((visibility("default"), used))
void rimdroid_linker_set_vulkan_driver_handle(void* handle) {
    vulkan_driver_handle = handle;
}

__attribute__((visibility("default"), used))
void rimdroid_linker_set_vulkan_loader_handle(void* handle) {
    vulkan_loader_handle = handle;
}

__attribute__((visibility("default"), used))
void rimdroid_linker_set_proc_addrs(void* _loader_dlopen_fn, void* _loader_dlsym_fn,
                                    void* _loader_android_dlopen_ext_fn) {
    loader_dlopen              = _loader_dlopen_fn;
    loader_dlsym               = _loader_dlsym_fn;
    loader_android_dlopen_ext  = _loader_android_dlopen_ext_fn;
}

__attribute__((visibility("default"), used))
int rimdroid_linker_init() {
    if (rimdroid_emulation_init() != 0) {
        LOGE("Failed to initialize emulation");
        return -1;
    }
    return 0;
}

// ---- dlopen/dlsym/android_dlopen_ext overrides ------------------------------
// These intercept native (arm64) linker calls so we can redirect Vulkan driver
// loads and, in the future, route x86_64 libraries through box64.

__attribute__((visibility("default"), used))
void* dlopen(const char* filename, int flags) {
    if (filename == NULL)
        return loader_dlopen(NULL, flags, __builtin_return_address(0));

    if (strcmp(filename, "libvulkan.so") == 0 && vulkan_loader_handle)
        return vulkan_loader_handle;

    return loader_dlopen(filename, flags, __builtin_return_address(0));
}

__attribute__((visibility("default"), used))
void* dlsym(void* handle, const char* sym_name) {
    return loader_dlsym(handle, sym_name, __builtin_return_address(0));
}

__attribute__((visibility("default"), used))
void* android_dlopen_ext(const char* filename, int flags, const android_dlextinfo* extinfo) {
    if (filename && strstr(filename, "vulkan.") && vulkan_driver_handle)
        return vulkan_driver_handle;

    return loader_android_dlopen_ext(filename, flags, extinfo, &android_dlopen_ext);
}

__attribute__((visibility("default"), used))
void* android_load_sphal_library(const char* filename, int flags) {
    if (filename && strstr(filename, "vulkan.") && vulkan_driver_handle)
        return vulkan_driver_handle;

    char* ns_names[] = {"sphal", "vendor", "default"};
    struct android_namespace_t* sphal_ns = NULL;
    for (int i = 0; i < (int)(sizeof(ns_names) / sizeof(char*)); i++) {
        sphal_ns = android_get_exported_namespace(ns_names[i]);
        if (sphal_ns) break;
    }
    android_dlextinfo info;
    info.flags             = ANDROID_DLEXT_USE_NAMESPACE;
    info.library_namespace = sphal_ns;
    return android_dlopen_ext(filename, flags, &info);
}
