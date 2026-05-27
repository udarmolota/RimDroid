#ifndef RIMDROID_GLOBALS_H
#define RIMDROID_GLOBALS_H

#include <android/native_window.h>
#include <stdatomic.h>
#include <pthread.h>
#include <stdbool.h>

typedef enum {
    RD_GL4ES,
    RD_ZINK_ZFA,
    RD_ZINK_OSMESA
} RimDroidRenderer;

extern RimDroidRenderer g_rimdroid_renderer;
extern const char*      g_rimdroid_vulkan_driver_name;

typedef struct {
    ANativeWindow* native_window;
    int width;
    int height;
    bool is_dirty;
    bool is_used;
    pthread_mutex_t mutex;
    pthread_cond_t ready_for_destroy_cond;
} RimDroidSurface;

extern RimDroidSurface g_rimdroid_surface;

// Namespace used to load x86_64 libs via linkernsbypass
struct android_namespace_t;
extern struct android_namespace_t* rimdroid_ns;

#endif // RIMDROID_GLOBALS_H
