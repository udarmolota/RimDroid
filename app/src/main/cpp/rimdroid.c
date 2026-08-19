#include <dlfcn.h>
#include <android/dlext.h>
#include <android/native_window.h>
#include <android/hardware_buffer.h>
#include <media/NdkImageReader.h>
#include <EGL/egl.h>
#include <stdbool.h>
#include <stdint.h>
#include <pthread.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <wait.h>
#include <errno.h>
#include <malloc.h>
#include <stdlib.h>
#include <signal.h>
#include <ucontext.h>
#include <sys/sysinfo.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <asm-generic/fcntl.h>
#include <bits/stdatomic.h>
#include <stdio.h>
#include <sys/syscall.h>

#include "rimdroid_globals.h"
#include "rimdroid.h"
#include "android_linker_ns.h"
#include "logger.h"

#define LOG_TAG "rimdroid-main"

// ---- Globals ----------------------------------------------------------------

struct android_namespace_t* rimdroid_ns;
RimDroidRenderer g_rimdroid_renderer;
const char*      g_rimdroid_vulkan_driver_name;

// EGL state for GL4ES — initialized in child process before launch_rimworld_elf().
// wrappedsdl2.c intercepts SDL_GL_CreateContext/SwapWindow and uses these.
// Declared as void* to avoid EGL type pollution in wrappedsdl2.c (EGL* are all void*).
void* g_egl_display = NULL;   // EGLDisplay
void* g_egl_surface = NULL;   // EGLSurface
void* g_egl_context = NULL;   // EGLContext (the PRIMARY; workers share with it — see eglt_create_shared)
static void* g_egl_config = NULL;   // EGLConfig the surface/contexts were created with

// ZFA (Zink-for-Android) state for the ZINK_ZFA renderer.  ZFA presents a real
// desktop OpenGL CORE profile (Mesa Zink over Vulkan/Turnip), which is what
// Unity's Linux player requires (GL4ES cannot — it only fakes a GLES-backed
// profile).  Created in the PARENT before fork() (Vulkan/binder init is not
// fork-safe), then the child rebinds via zfaMakeCurrent().  Read by
// wrappedsdl2.c's my2_SDL_GL_* intercepts as weak externs.
void* g_zfa_handle  = NULL;   // libzfa.so dlopen handle (for GL proc resolution)
void* g_zfa_context = NULL;   // zfaCreateContext() handle

typedef void* (*PFN_zfaCreateContext)(int depth, int stencil, int compat, int major, int minor);
typedef int   (*PFN_zfaMakeCurrent)(void* ctx, ANativeWindow* win, int w, int h);
typedef void  (*PFN_zfaFlushFront)(void);
typedef void  (*PFN_zfaDestroyContext)(void* ctx);
typedef int   (*PFN_zfaReleaseCurrent)(void);   // added to libzfa (Plan A): st_api_make_current(NULL,NULL,NULL)
static PFN_zfaCreateContext  p_zfaCreateContext  = NULL;
static PFN_zfaMakeCurrent    p_zfaMakeCurrent    = NULL;
static PFN_zfaFlushFront     p_zfaFlushFront     = NULL;
static PFN_zfaDestroyContext p_zfaDestroyContext = NULL;
static PFN_zfaReleaseCurrent p_zfaReleaseCurrent = NULL;

RimDroidSurface g_rimdroid_surface = {
    .mutex = PTHREAD_MUTEX_INITIALIZER,
    .ready_for_destroy_cond = PTHREAD_COND_INITIALIZER
};

// RimWorld 1.6 (X11/Vulkan path): box64's wrappedvulkan weak-externs this to turn a guest
// vkCreateXlibSurfaceKHR/vkCreateXcbSurfaceKHR into a real Android WSI surface on OUR window
// (Turnip has no X11 WSI). See memory rimworld_16_port.
__attribute__((visibility("default")))
void* rimdroid_get_native_window(void) {
    return (void*)g_rimdroid_surface.native_window;
}

// Log file — extern in logger.h, used by the LOGI/LOGW/LOGE macros
FILE* g_rimdroid_log_file = NULL;

static char g_log_file_path[1024] = {0};

// ---- GL4ES EGL initialisation -----------------------------------------------
// Sets up an EGL window surface + GLES context backed by nativeWindow.
// Called in the child process BEFORE launch_rimworld_elf() so that when Unity's
// SDL2 calls SDL_GL_CreateContext(), our wrappedsdl2.c override can return the
// pre-created EGLContext instead of going through SDL's dummy-driver GL path
// (which always fails with "no supported OpenGL core profile").

// GL4ES (libgl4es.so) in this build does NOT manage EGL itself.  It must be
// told how to resolve the underlying GLES driver entry points via its exported
// set_getprocaddress().  Without it, GL4ES's internal GLES dispatch stays NULL
// and the first real GL call dereferences it (SIGSEGV @0x10 inside libgl4es).
static void* g_libglesv2 = NULL;
static void* rimdroid_gles_resolver(const char* name) {
    void* p = (void*)eglGetProcAddress(name);
    if (!p) {
        // eglGetProcAddress on Android may not return core GLES entry points;
        // fall back to dlsym on the GLES2/3 driver.
        if (!g_libglesv2)
            g_libglesv2 = dlopen("libGLESv2.so", RTLD_LAZY | RTLD_GLOBAL);
        if (g_libglesv2)
            p = dlsym(g_libglesv2, name);
    }
    return p;
}

// ---- ZFA (Zink) initialisation ----------------------------------------------
// Loads libzfa.so and creates a real desktop GL CORE-profile context (Mesa Zink
// over the custom Turnip Vulkan driver injected by load_linker_hook()).  Called
// in the PARENT before fork().  rimdroid_zfa_make_current()/swap() are exported
// (default visibility) so wrappedsdl2.c can rebind/present from the emulated
// SDL_GL_* intercepts (resolved there as weak externs).

int rimdroid_zfa_make_current(void) {
    if (!p_zfaMakeCurrent || !g_zfa_context) return 0;
    ANativeWindow* w = g_rimdroid_surface.native_window;
    // RIMDROID: render at the size the game thinks the screen is (dummy SDL =
    // 1024x768), NOT the physical surface (2340x1080).  This makes our GL surface
    // / FBO 0 match Unity's resolution belief, removing the FBO-vs-window size
    // mismatch suspected of triggering the fullscreen GfxDevice teardown loop.
    // ANativeWindow_setBuffersGeometry resizes the producer buffers; SurfaceFlinger
    // then scales them to fill the physical SurfaceView (2340x1080).
    // Render at the NATIVE surface size, taken CONSTANTLY from the surface dims
    // (captured once in surfaceChanged) — NOT per-call ANativeWindow_getWidth,
    // which changes when Unity resizes mid-run and made the frame blink then
    // collapse into a corner. Constant size + forced buffer geometry = stable.
    // Mirror Zomdroid's ZFA path: do NOT call ANativeWindow_setBuffersGeometry here
    // (the Java holder.setFixedSize establishes the buffer). Just make current at the
    // surface size, then force the buffer transform to IDENTITY (below).
    int rw = g_rimdroid_surface.width  > 0 ? g_rimdroid_surface.width  : 2340;
    int rh = g_rimdroid_surface.height > 0 ? g_rimdroid_surface.height : 1080;
    int ww = w ? rw : 1;
    int hh = w ? rh : 1;
    if (!p_zfaMakeCurrent(g_zfa_context, w, ww, hh)) {
        LOGE("ZFA: zfaMakeCurrent failed (%dx%d)", ww, hh);
        return 0;
    }
    // Force the device-orientation transform to IDENTITY. With the landscape lock,
    // this cancels the system's portrait pre-rotation → the frame stays HORIZONTAL
    // (this is the pair that produced yesterday's good horizontal screen). API 26+,
    // resolved via dlsym.
    if (w) {
        static int (*fn_set_transform)(ANativeWindow*, int32_t) = NULL;
        static int checked = 0;
        if (!checked) {
            checked = 1;
            void* h = dlopen("libandroid.so", RTLD_LAZY);
            if (h) fn_set_transform = (int (*)(ANativeWindow*, int32_t))
                dlsym(h, "ANativeWindow_setBuffersTransform");
        }
        if (fn_set_transform) fn_set_transform(w, 0 /* IDENTITY */);
    }
    LOGI("ZFA make_current native %dx%d (landscape, identity transform)", ww, hh);
    return 1;
}

void rimdroid_zfa_swap(void) {
    if (p_zfaFlushFront) p_zfaFlushFront();
}

// ---- FPS overlay ------------------------------------------------------------
// Count actually-presented frames. box64's my2_SDL_GL_SwapWindow calls
// rimdroid_frame_tick() (weak extern) exactly once per present, for EVERY
// renderer (ZFA/Zink, softpipe/OSMesa, egl). The Java FPS overlay polls
// nativeGetFrameCount() once a second and shows the delta = true presented FPS
// (independent of the game's own frame estimate; needs no mod). The game runs
// in-process (Unity relocatable, no fork), so this global is readable from JNI.
volatile uint64_t g_rimdroid_frame_count = 0;

// ---- Frame-rate cap (present pacing) ----------------------------------------
// RimWorld is CPU-bound under emulation, so rendering as fast as possible burns
// CPU the simulation needs and generates heat → thermal throttling → the FPS
// swings players see. Capping the present rate here (the one spot hit exactly
// once per frame, for every renderer) evens out frame delivery AND frees CPU for
// the game's tick loop → steadier, and often higher, TPS. 0 = uncapped.
volatile uint64_t g_rimdroid_frame_min_ns = 0;   // min nanoseconds between presents
static uint64_t g_rd_last_present_ns = 0;

static uint64_t rd_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

void rimdroid_frame_tick(void) {
    g_rimdroid_frame_count++;
    uint64_t cap = g_rimdroid_frame_min_ns;
    if (!cap) { g_rd_last_present_ns = 0; return; }   // uncapped: forget the clock
    uint64_t now = rd_now_ns();
    if (g_rd_last_present_ns) {
        uint64_t elapsed = now - g_rd_last_present_ns;
        if (elapsed < cap) {
            uint64_t s = cap - elapsed;
            struct timespec req = { (time_t)(s / 1000000000ull), (long)(s % 1000000000ull) };
            nanosleep(&req, NULL);
            now = rd_now_ns();
        }
    }
    g_rd_last_present_ns = now;
}

// ---- OSMesa (softpipe) software-renderer SMOKE TEST -------------------------
// Milestone 1 of the CPU/software renderer: prove libOSMesa.so (built with
// softpipe) renders natively on the device AND that we can blit its CPU buffer to
// the ANativeWindow — BEFORE wiring the box64 SDL interception. Draws a dark-blue
// background with a centered green rectangle (scissor clears, no shaders needed)
// and presents it to the current surface. Returns 0 on success.
//
// OSMesa / GL tokens are hard-coded so we don't pull in GL/osmesa headers.
#define RD_OSMESA_RGBA          0x1908
#define RD_OSMESA_Y_UP          0x11
#define RD_GL_UNSIGNED_BYTE     0x1401
#define RD_GL_COLOR_BUFFER_BIT  0x4000
#define RD_GL_SCISSOR_TEST      0x0C11
#define RD_GL_RENDERER          0x1F01
#define RD_GL_VERSION           0x1F02

typedef void* (*PFN_OSMesaCreateContext)(unsigned int format, void* sharelist);
typedef int   (*PFN_OSMesaMakeCurrent)(void* ctx, void* buffer, unsigned int type, int width, int height);
typedef void  (*PFN_OSMesaDestroyContext)(void* ctx);
typedef void  (*PFN_OSMesaPixelStore)(int pname, int value);

// Attribute-list context creation for a CORE profile (Unity rejects the legacy
// compatibility-profile context that plain OSMesaCreateContext yields). Tokens
// from Mesa's osmesa.h — VERIFY against the built osmesa.h if context creation
// fails (values have been stable across Mesa releases, but confirm on mismatch).
typedef void* (*PFN_OSMesaCreateContextAttribs)(const int* attribList, void* sharelist);
#define RD_OSMESA_FORMAT                 0x22
#define RD_OSMESA_DEPTH_BITS             0x30
#define RD_OSMESA_STENCIL_BITS           0x31
#define RD_OSMESA_PROFILE                0x33
#define RD_OSMESA_CORE_PROFILE           0x34
#define RD_OSMESA_CONTEXT_MAJOR_VERSION  0x36
#define RD_OSMESA_CONTEXT_MINOR_VERSION  0x37

int rimdroid_osmesa_smoketest(const char* osmesa_lib_path) {
    if (!osmesa_lib_path || !osmesa_lib_path[0]) { LOGE("OSMesa smoke: no lib path"); return -1; }

    ANativeWindow* win = g_rimdroid_surface.native_window;
    int w = g_rimdroid_surface.width;
    int h = g_rimdroid_surface.height;
    if (!win || w <= 0 || h <= 0) {
        LOGE("OSMesa smoke: no surface (win=%p %dx%d)", (void*)win, w, h);
        return -1;
    }

    // libOSMesa.so depends on platform libs (libcutils, liblog, libnativewindow, …) that the
    // app's default (restricted) linker namespace can't resolve. Load it through a SHARED
    // linkernsbypass namespace — the same mechanism libzfa uses — whose search path includes
    // /system/lib64. Reuse the game's rimdroid_ns if a launch set it up; otherwise (this smoke
    // test runs WITHOUT launching the game) build a minimal SHARED namespace rooted at the deps
    // dir + /system/lib64.
    struct android_namespace_t* ns = rimdroid_ns;
    if (!ns) {
        // Cached so repeated surfaceChanged calls don't recreate the namespace.
        static struct android_namespace_t* s_osmesa_ns = NULL;
        if (!s_osmesa_ns) {
            if (!linkernsbypass_load_status()) {
                LOGE("OSMesa smoke: linkernsbypass not loaded"); return -1;
            }
            char dir[1024];
            strncpy(dir, osmesa_lib_path, sizeof(dir) - 1);
            dir[sizeof(dir) - 1] = '\0';
            char* slash = strrchr(dir, '/');
            if (slash) *slash = '\0';
            char paths[1200];
            snprintf(paths, sizeof(paths), "%s:/system/lib64", dir);
            s_osmesa_ns = android_create_namespace("rimdroid-osmesa-ns", paths, paths,
                                                   ANDROID_NAMESPACE_TYPE_SHARED, NULL, NULL);
        }
        ns = s_osmesa_ns;
        if (!ns) { LOGE("OSMesa smoke: android_create_namespace failed"); return -1; }
    }
    void* lib = linkernsbypass_namespace_dlopen("libOSMesa.so", RTLD_NOW | RTLD_LOCAL, ns);
    if (!lib) { LOGE("OSMesa smoke: namespace dlopen('libOSMesa.so') failed: %s", dlerror()); return -1; }

    PFN_OSMesaCreateContext  fCreate  = (PFN_OSMesaCreateContext)  dlsym(lib, "OSMesaCreateContext");
    PFN_OSMesaMakeCurrent    fCurrent = (PFN_OSMesaMakeCurrent)    dlsym(lib, "OSMesaMakeCurrent");
    PFN_OSMesaDestroyContext fDestroy = (PFN_OSMesaDestroyContext) dlsym(lib, "OSMesaDestroyContext");
    PFN_OSMesaPixelStore     fPixel   = (PFN_OSMesaPixelStore)     dlsym(lib, "OSMesaPixelStore");
    if (!fCreate || !fCurrent) {
        LOGE("OSMesa smoke: missing OSMesa API (create=%p current=%p)", (void*)fCreate, (void*)fCurrent);
        dlclose(lib); return -1;
    }
    // GL entry points are exported by libOSMesa itself.
    void (*p_glViewport)(int,int,int,int)           = (void(*)(int,int,int,int))           dlsym(lib, "glViewport");
    void (*p_glClearColor)(float,float,float,float) = (void(*)(float,float,float,float))   dlsym(lib, "glClearColor");
    void (*p_glClear)(unsigned int)                 = (void(*)(unsigned int))              dlsym(lib, "glClear");
    void (*p_glEnable)(unsigned int)                = (void(*)(unsigned int))              dlsym(lib, "glEnable");
    void (*p_glDisable)(unsigned int)               = (void(*)(unsigned int))              dlsym(lib, "glDisable");
    void (*p_glScissor)(int,int,int,int)            = (void(*)(int,int,int,int))           dlsym(lib, "glScissor");
    void (*p_glFinish)(void)                        = (void(*)(void))                      dlsym(lib, "glFinish");
    const unsigned char* (*p_glGetString)(unsigned int) =
        (const unsigned char*(*)(unsigned int)) dlsym(lib, "glGetString");
    if (!p_glViewport || !p_glClearColor || !p_glClear || !p_glFinish) {
        LOGE("OSMesa smoke: missing GL entry points in libOSMesa"); dlclose(lib); return -1;
    }

    int rc = -1;
    void* buf = malloc((size_t)w * (size_t)h * 4);
    void* ctx = NULL;
    if (!buf) { LOGE("OSMesa smoke: OOM (%dx%d)", w, h); dlclose(lib); return -1; }

    ctx = fCreate(RD_OSMESA_RGBA, NULL);
    if (!ctx) { LOGE("OSMesa smoke: OSMesaCreateContext failed"); free(buf); dlclose(lib); return -1; }
    if (!fCurrent(ctx, buf, RD_GL_UNSIGNED_BYTE, w, h)) {
        LOGE("OSMesa smoke: OSMesaMakeCurrent failed");
        if (fDestroy) fDestroy(ctx);
        free(buf); dlclose(lib); return -1;
    }
    if (fPixel) fPixel(RD_OSMESA_Y_UP, 0);   // top-down rows → match ANativeWindow origin

    const unsigned char* ren = p_glGetString ? p_glGetString(RD_GL_RENDERER) : NULL;
    const unsigned char* ver = p_glGetString ? p_glGetString(RD_GL_VERSION)  : NULL;
    LOGI("OSMesa smoke: context ok %dx%d GL_RENDERER='%s' GL_VERSION='%s'",
         w, h, ren ? (const char*)ren : "(null)", ver ? (const char*)ver : "(null)");

    // Render (scissor clears — no shaders/VBOs): blue background + green centre rect.
    p_glViewport(0, 0, w, h);
    p_glClearColor(0.05f, 0.07f, 0.15f, 1.0f);
    p_glClear(RD_GL_COLOR_BUFFER_BIT);
    if (p_glEnable && p_glScissor && p_glDisable) {
        p_glEnable(RD_GL_SCISSOR_TEST);
        p_glScissor(w / 4, h / 4, w / 2, h / 2);
        p_glClearColor(0.10f, 0.80f, 0.20f, 1.0f);
        p_glClear(RD_GL_COLOR_BUFFER_BIT);
        p_glDisable(RD_GL_SCISSOR_TEST);
    }
    p_glFinish();

    // Present the CPU buffer to the surface.
    ANativeWindow_acquire(win);
    if (ANativeWindow_setBuffersGeometry(win, w, h, WINDOW_FORMAT_RGBA_8888) == 0) {
        ANativeWindow_Buffer wb;
        if (ANativeWindow_lock(win, &wb, NULL) == 0) {
            const unsigned char* src = (const unsigned char*)buf;
            unsigned char* dst = (unsigned char*)wb.bits;
            int cw = wb.width  < w ? wb.width  : w;
            int ch = wb.height < h ? wb.height : h;
            for (int y = 0; y < ch; y++) {
                memcpy(dst + (size_t)y * (size_t)wb.stride * 4,
                       src + (size_t)y * (size_t)w * 4,
                       (size_t)cw * 4);
            }
            ANativeWindow_unlockAndPost(win);
            rc = 0;
            LOGI("OSMesa smoke: PRESENTED %dx%d (win %dx%d stride %d) — softpipe works!",
                 w, h, wb.width, wb.height, wb.stride);
        } else {
            LOGE("OSMesa smoke: ANativeWindow_lock failed");
        }
    } else {
        LOGE("OSMesa smoke: setBuffersGeometry failed");
    }
    ANativeWindow_release(win);

    fCurrent(ctx, NULL, RD_GL_UNSIGNED_BYTE, 0, 0);   // unbind (best effort)
    if (fDestroy) fDestroy(ctx);
    free(buf);
    dlclose(lib);
    return rc;
}

// ---- OSMesa (softpipe) PERSISTENT software-renderer path --------------------
// Milestone 2: wire softpipe into the actual game. OSMesa is an OFFSCREEN GL
// context (no winsys), so unlike ZFA/Zink (which auto-presents via its kopper
// Vulkan swapchain) NOTHING presents our frame automatically — box64's
// my2_SDL_GL_SwapWindow must call rimdroid_osmesa_swap() to blit our CPU buffer
// to the ANativeWindow. These globals/functions mirror the ZFA ones: box64
// (wrappedsdl2.c) weak-references g_osmesa_context (non-NULL ⇒ softpipe active),
// g_osmesa_handle (GL proc source), rimdroid_osmesa_make_current and
// rimdroid_osmesa_swap. Default visibility (librimdroid is built without
// -fvisibility=hidden) so librimdroidlinker.so resolves them at link time.
void* g_osmesa_handle  = NULL;   // libOSMesa.so dlopen handle (box64 resolves GL procs from it)
void* g_osmesa_context = NULL;   // OSMesaCreateContext* — non-NULL selects the softpipe path
static void* g_osmesa_buffer = NULL;        // persistent RGBA8888 CPU render target
static int   g_osmesa_w = 0, g_osmesa_h = 0; // current buffer dimensions
static PFN_OSMesaMakeCurrent g_osmesa_make_current_fn = NULL;
static PFN_OSMesaPixelStore  g_osmesa_pixel_store_fn  = NULL;
static void (*g_osmesa_glFinish_fn)(void)             = NULL;
static PFN_OSMesaCreateContextAttribs g_osmesa_create_attribs_fn = NULL; // for extra shared contexts

// Load libOSMesa.so (via rimdroid_ns so libcutils/liblog resolve) and create a
// CORE 3.3 softpipe context. Called once at launch for RD_SOFTPIPE. Returns 0 ok.
int rimdroid_init_osmesa(void) {
    if (g_osmesa_context) return 0;  // already initialised
    if (!rimdroid_ns) { LOGE("OSMesa init: rimdroid_ns not ready"); return -1; }
    // RTLD_LOCAL: box64 resolves GL procs by dlsym(g_osmesa_handle, name), so the
    // softpipe GL symbols need NOT be in the global scope (keeps them from
    // colliding with the system GLES). The smoke test proved this works.
    void* lib = linkernsbypass_namespace_dlopen("libOSMesa.so", RTLD_NOW | RTLD_LOCAL, rimdroid_ns);
    if (!lib) { LOGE("OSMesa init: dlopen('libOSMesa.so') failed: %s", dlerror()); return -1; }

    PFN_OSMesaCreateContextAttribs fAttribs =
        (PFN_OSMesaCreateContextAttribs) dlsym(lib, "OSMesaCreateContextAttribs");
    PFN_OSMesaCreateContext fCreate =
        (PFN_OSMesaCreateContext) dlsym(lib, "OSMesaCreateContext");
    g_osmesa_make_current_fn = (PFN_OSMesaMakeCurrent) dlsym(lib, "OSMesaMakeCurrent");
    g_osmesa_pixel_store_fn  = (PFN_OSMesaPixelStore)  dlsym(lib, "OSMesaPixelStore");
    g_osmesa_glFinish_fn     = (void(*)(void))         dlsym(lib, "glFinish");
    g_osmesa_create_attribs_fn = fAttribs;   // kept so we can make extra SHARED contexts later
    if (!g_osmesa_make_current_fn || (!fAttribs && !fCreate)) {
        LOGE("OSMesa init: missing API (attribs=%p create=%p makecur=%p)",
             (void*)fAttribs, (void*)fCreate, (void*)g_osmesa_make_current_fn);
        dlclose(lib); return -1;
    }

    // Prefer a CORE 3.3 context (Unity's renderer detection rejects the legacy
    // compatibility profile). Fall back to plain RGBA create if attribs missing.
    void* ctx = NULL;
    if (fAttribs) {
        const int attribs[] = {
            RD_OSMESA_FORMAT,                RD_OSMESA_RGBA,
            RD_OSMESA_DEPTH_BITS,            24,
            RD_OSMESA_STENCIL_BITS,          8,
            RD_OSMESA_PROFILE,               RD_OSMESA_CORE_PROFILE,
            RD_OSMESA_CONTEXT_MAJOR_VERSION, 3,
            RD_OSMESA_CONTEXT_MINOR_VERSION, 3,
            0
        };
        ctx = fAttribs(attribs, NULL);
        if (!ctx) LOGW("OSMesa init: CORE 3.3 attribs context failed — trying legacy create");
    }
    if (!ctx && fCreate) ctx = fCreate(RD_OSMESA_RGBA, NULL);
    if (!ctx) { LOGE("OSMesa init: context creation failed"); dlclose(lib); return -1; }

    g_osmesa_handle  = lib;
    g_osmesa_context = ctx;   // publish LAST: box64 keys the softpipe path on this
    LOGI("OSMesa init: softpipe context ready (ctx=%p handle=%p)", ctx, lib);
    return 0;
}

// Bind the OSMesa context to the CALLING thread and (re)size the CPU buffer to the
// current surface. Called by box64 on SDL_GL_CreateContext / MakeCurrent. The
// buffer is sized to g_rimdroid_surface (= the game's drawable, since PrefsXml
// pins screenWidth/Height = surface dims for softpipe → glViewport matches);
// SurfaceFlinger upscales the posted buffer to the physical display. Returns 1 ok.
// Create an ADDITIONAL OSMesa context that SHARES resources (textures, buffers, …) with the
// primary one. Unity's GLCore path creates a 2nd "shared" GL context; with a single OSMesa
// context their separate GL states collapsed into one → the menu rendered into nothing (black).
// Each Unity context now gets its own OSMesa context (separate state) but shared resources, all
// drawing into the one window CPU buffer. Returns the new context, or the primary as a fallback.
void* rimdroid_osmesa_create_shared(void) {
    if (!g_osmesa_create_attribs_fn || !g_osmesa_context) return g_osmesa_context;
    const int attribs[] = {
        RD_OSMESA_FORMAT,                RD_OSMESA_RGBA,
        RD_OSMESA_DEPTH_BITS,            24,
        RD_OSMESA_STENCIL_BITS,          8,
        RD_OSMESA_PROFILE,               RD_OSMESA_CORE_PROFILE,
        RD_OSMESA_CONTEXT_MAJOR_VERSION, 3,
        RD_OSMESA_CONTEXT_MINOR_VERSION, 3,
        0
    };
    void* ctx = g_osmesa_create_attribs_fn(attribs, g_osmesa_context);  // sharelist = primary
    LOGI("OSMesa: created shared context %p (sharing with %p)", ctx, g_osmesa_context);
    return ctx ? ctx : g_osmesa_context;
}

// Bind a SPECIFIC OSMesa context to the (shared) window CPU buffer, (re)sizing it to the surface.
int rimdroid_osmesa_make_current_ctx(void* ctx) {
    if (!ctx || !g_osmesa_make_current_fn) return 0;
    int w = g_rimdroid_surface.width;
    int h = g_rimdroid_surface.height;
    if (w <= 0 || h <= 0) { LOGE("OSMesa make_current: bad surface %dx%d", w, h); return 0; }
    if (!g_osmesa_buffer || w != g_osmesa_w || h != g_osmesa_h) {
        void* nb = realloc(g_osmesa_buffer, (size_t)w * (size_t)h * 4);
        if (!nb) { LOGE("OSMesa make_current: OOM %dx%d", w, h); return 0; }
        g_osmesa_buffer = nb; g_osmesa_w = w; g_osmesa_h = h;
    }
    if (!g_osmesa_make_current_fn(ctx, g_osmesa_buffer, RD_GL_UNSIGNED_BYTE, w, h)) {
        LOGE("OSMesa make_current: OSMesaMakeCurrent failed (%dx%d)", w, h);
        return 0;
    }
    // Top-down rows so buffer origin matches ANativeWindow's top-left.
    if (g_osmesa_pixel_store_fn) g_osmesa_pixel_store_fn(RD_OSMESA_Y_UP, 0);
    return 1;
}

// Back-compat wrapper: bind the PRIMARY context.
int rimdroid_osmesa_make_current(void) {
    return rimdroid_osmesa_make_current_ctx(g_osmesa_context);
}

// Present: finish CPU rendering, then blit the OSMesa buffer to the surface.
// This IS the only present path for softpipe (OSMesa has no winsys). Mirrors the
// proven smoke-test blit. Called by box64 on SDL_GL_SwapWindow.
void rimdroid_osmesa_swap(void) {
    if (!g_osmesa_context || !g_osmesa_buffer) return;
    if (g_osmesa_glFinish_fn) g_osmesa_glFinish_fn();
    // DIAGNOSTIC (throttled): is Unity's image actually IN our buffer? Count non-zero RGB pixels.
    // all-zero ⇒ Unity never draws to the default framebuffer (OSMesa/softpipe FBO issue);
    // non-zero ⇒ content is present and the blit/surface path is what's dropping it.
    {
        static unsigned long s_swapn = 0;
        if ((s_swapn++ % 120UL) == 0UL && g_osmesa_w > 0 && g_osmesa_h > 0) {
            const unsigned int* p = (const unsigned int*)g_osmesa_buffer;
            size_t n = (size_t)g_osmesa_w * (size_t)g_osmesa_h, nz = 0;
            unsigned int sample = 0;
            for (size_t i = 0; i < n; i++) {
                unsigned int v = p[i] & 0x00FFFFFFu;
                if (v) { nz++; if (!sample) sample = p[i]; }
            }
            // Also ask GL what Unity left bound at swap: which framebuffer + what viewport. FBO != 0
            // ⇒ Unity rendered into its own FBO and never presented to the default (our buffer);
            // viewport != buffer size ⇒ content rendered off-buffer (resolution mismatch).
            static void (*p_glGetIntegerv)(unsigned int, int*) = NULL;
            static int s_resolved = 0;
            if (!s_resolved) { s_resolved = 1; if (g_osmesa_handle) p_glGetIntegerv =
                (void(*)(unsigned int,int*)) dlsym(g_osmesa_handle, "glGetIntegerv"); }
            int fbo = -1, vp[4] = {-1,-1,-1,-1};
            if (p_glGetIntegerv) {
                p_glGetIntegerv(0x8CA6 /*GL_DRAW_FRAMEBUFFER_BINDING*/, &fbo);
                p_glGetIntegerv(0x0BA2 /*GL_VIEWPORT*/, vp);
            }
            LOGI("OSMesa buffer diag: %zu/%zu non-zero px, sample=0x%08x buf=%dx%d | draw_fbo=%d viewport=[%d,%d,%d,%d]",
                 nz, n, sample, g_osmesa_w, g_osmesa_h, fbo, vp[0], vp[1], vp[2], vp[3]);
        }
    }
    ANativeWindow* win = g_rimdroid_surface.native_window;
    int w = g_osmesa_w, h = g_osmesa_h;
    if (!win || w <= 0 || h <= 0) return;
    ANativeWindow_acquire(win);
    if (ANativeWindow_setBuffersGeometry(win, w, h, WINDOW_FORMAT_RGBA_8888) == 0) {
        ANativeWindow_Buffer wb;
        if (ANativeWindow_lock(win, &wb, NULL) == 0) {
            const unsigned char* src = (const unsigned char*)g_osmesa_buffer;
            unsigned char* dst = (unsigned char*)wb.bits;
            int cw = wb.width  < w ? wb.width  : w;
            int ch = wb.height < h ? wb.height : h;
            // Copy each row AND force alpha=0xFF: Unity leaves a non-opaque alpha in
            // the default framebuffer, and SurfaceFlinger blends the SurfaceView by
            // that per-pixel alpha over black → the whole frame looks dim/washed out.
            // Force opaque (RGBA byte order → A is the high byte of the LE u32, so OR
            // 0xFF000000) so the composited image shows at full brightness. One pass.
            for (int y = 0; y < ch; y++) {
                unsigned int* drow = (unsigned int*)(dst + (size_t)y * (size_t)wb.stride * 4);
                const unsigned int* srow = (const unsigned int*)(src + (size_t)y * (size_t)w * 4);
                for (int x = 0; x < cw; x++) drow[x] = srow[x] | 0xFF000000u;
            }
            ANativeWindow_unlockAndPost(win);
        }
    }
    ANativeWindow_release(win);
}

// Release the ZFA/Zink GL context from the CALLING thread (Plan A: serialize the
// context handoff between Unity's main + render-worker threads).  Returns 1 on
// success, 0 if libzfa doesn't export zfaReleaseCurrent yet (then it's a no-op and
// behaviour is unchanged).  Called from wrappedsdl2.c on SDL_GL_MakeCurrent(NULL).
int rimdroid_zfa_release_current(void) {
    if (p_zfaReleaseCurrent) return p_zfaReleaseCurrent();
    return 0;
}

// ---- EGL routed through the GL translator (MobileGlues context tracking, 2026-08-13) ---------
// MobileGlues keeps its per-context state -- texture-unit bindings, buffer/framebuffer shadows,
// FSR1 -- only for contexts IT created. A handle it never saw falls into one process-wide
// fallback record shared by every untracked context; its own gl/texture.cpp says so: "Two
// untracked contexts on two threads therefore have one set of shadow values between them".
// Ours were created straight against the system EGL, so in threaded rendering two contexts shared
// one binding shadow: MG then skips a glBindTexture the other thread still needs and the upload
// lands on the wrong texture -- the red patches, worst in the mip levels written after the first
// bind (clean when zoomed in, red when zoomed out). Calling MG's own egl* entry points instead
// gives every context its own record; they are thin pass-throughs to the same system EGL plus the
// bookkeeping. Present goes through MG too: presentSurface is where its FSR1 upscaler lives, so
// the config knob we write is inert while we swap directly. RIMDROID_GLT_EGLTRACK=1 to enable.
static struct {
    EGLContext (*create)(EGLDisplay, EGLConfig, EGLContext, const EGLint*);
    EGLBoolean (*make_current)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
    EGLBoolean (*destroy)(EGLDisplay, EGLContext);
    EGLBoolean (*swap)(EGLDisplay, EGLSurface);
} g_glt_egl;

static void rimdroid_glt_egl_route(void* h) {
    const char* e = getenv("RIMDROID_GLT_EGLTRACK");
    if (!h || !e || e[0] != '1') return;
    g_glt_egl.create       = (EGLContext(*)(EGLDisplay, EGLConfig, EGLContext, const EGLint*))dlsym(h, "eglCreateContext");
    g_glt_egl.make_current = (EGLBoolean(*)(EGLDisplay, EGLSurface, EGLSurface, EGLContext))dlsym(h, "eglMakeCurrent");
    g_glt_egl.destroy      = (EGLBoolean(*)(EGLDisplay, EGLContext))dlsym(h, "eglDestroyContext");
    g_glt_egl.swap         = (EGLBoolean(*)(EGLDisplay, EGLSurface))dlsym(h, "eglSwapBuffers");
    LOGI("GLT: EGL routed through translator (EGLTRACK=1): create=%p make_current=%p destroy=%p swap=%p",
         (void*)g_glt_egl.create, (void*)g_glt_egl.make_current, (void*)g_glt_egl.destroy, (void*)g_glt_egl.swap);
}

static EGLContext rd_eglCreateContext(EGLDisplay d, EGLConfig c, EGLContext share, const EGLint* attr) {
    return g_glt_egl.create ? g_glt_egl.create(d, c, share, attr) : eglCreateContext(d, c, share, attr);
}
static EGLBoolean rd_eglMakeCurrent(EGLDisplay d, EGLSurface draw, EGLSurface read, EGLContext c) {
    return g_glt_egl.make_current ? g_glt_egl.make_current(d, draw, read, c) : eglMakeCurrent(d, draw, read, c);
}
static EGLBoolean rd_eglDestroyContext(EGLDisplay d, EGLContext c) {
    return g_glt_egl.destroy ? g_glt_egl.destroy(d, c) : eglDestroyContext(d, c);
}
static EGLBoolean rd_eglSwapBuffers(EGLDisplay d, EGLSurface s) {
    return g_glt_egl.swap ? g_glt_egl.swap(d, s) : eglSwapBuffers(d, s);
}

// ---- GLX -> EGL-translator bridge helpers (RimWorld 1.6 on MobileGlues/NG) --------
// wrappedlibgl.c weak-imports these for its rd_bridge_* dispatch: when the renderer is
// a GL->GLES translator (g_egl_context set, no ZFA), Unity's glX calls land on the one
// EGL context rimdroid_init_gl4es_egl created. Same thread-affinity rules as any EGL
// context: bind/release happen on the CALLING thread (single-threaded render enforced
// by getArgs while the translator is active).
int rimdroid_eglt_make_current(void) {
    if (!g_egl_display || !g_egl_surface || !g_egl_context) return 0;
    if (!rd_eglMakeCurrent(g_egl_display, g_egl_surface, g_egl_surface, g_egl_context)) {
        LOGE("EGLT: eglMakeCurrent failed: 0x%x", eglGetError());
        return 0;
    }
    return 1;
}
int rimdroid_eglt_release_current(void) {
    if (!g_egl_display) return 0;
    return rd_eglMakeCurrent(g_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) ? 1 : 0;
}
void rimdroid_eglt_swap(void) {
    if (g_egl_display && g_egl_surface) rd_eglSwapBuffers(g_egl_display, g_egl_surface);
}

// ---- Multi-context factory (bridge Level 3, 2026-08-13) --------------------------------------
// The one-real-context-under-N-names aliasing is what corrupts textures in threaded mode (proven:
// zero upload collisions with TLS scratch, red patches persist). These give wrappedlibgl.c REAL
// EGL contexts, one per Unity logical GLX context: objects (textures/buffers/programs) are shared
// via the EGL share group with the primary, per-context STATE is kept by the driver itself, and
// only the presenting context ever holds the window surface — workers bind surfaceless (with a
// 1x1 pbuffer fallback for drivers without EGL_KHR_surfaceless_context).
#define RD_EGLT_MAX_CTX 8
static struct { void* ctx; void* pbuf; } g_eglt_ctxs[RD_EGLT_MAX_CTX];

void* rimdroid_eglt_create_shared(void) {
    if (!g_egl_display || !g_egl_config || !g_egl_context) return NULL;
    const EGLint ctx3[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    void* ctx = rd_eglCreateContext(g_egl_display, g_egl_config, g_egl_context, ctx3);
    if (ctx == EGL_NO_CONTEXT) {
        LOGE("EGLT: shared context creation failed: 0x%x", eglGetError());
        return NULL;
    }
    for (int i = 0; i < RD_EGLT_MAX_CTX; i++)
        if (!g_eglt_ctxs[i].ctx) { g_eglt_ctxs[i].ctx = ctx; break; }
    LOGI("EGLT: created shared context %p (share group of %p)", ctx, g_egl_context);
    return ctx;
}

// Bind ctx on the CALLING thread. with_window=1 binds the real window surface (presenter only);
// otherwise surfaceless, falling back to a per-context 1x1 pbuffer.
int rimdroid_eglt_make_current_on(void* ctx, int with_window) {
    if (!g_egl_display || !ctx) return 0;
    if (with_window)
        return rd_eglMakeCurrent(g_egl_display, g_egl_surface, g_egl_surface, ctx) ? 1 : 0;
    if (rd_eglMakeCurrent(g_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) return 1;
    // No surfaceless support: use (and lazily create) this context's own pbuffer.
    void** pb = NULL;
    for (int i = 0; i < RD_EGLT_MAX_CTX; i++)
        if (g_eglt_ctxs[i].ctx == ctx) { pb = &g_eglt_ctxs[i].pbuf; break; }
    if (pb && !*pb) {
        const EGLint at[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        *pb = eglCreatePbufferSurface(g_egl_display, g_egl_config, at);
        LOGI("EGLT: surfaceless unsupported — pbuffer %p for ctx %p", pb ? *pb : NULL, ctx);
    }
    if (pb && *pb && rd_eglMakeCurrent(g_egl_display, *pb, *pb, ctx)) return 1;
    LOGE("EGLT: make-current(ctx=%p, window=%d) failed: 0x%x", ctx, with_window, eglGetError());
    return 0;
}

void rimdroid_eglt_destroy_ctx(void* ctx) {
    if (!g_egl_display || !ctx || ctx == g_egl_context) return;   // never the primary
    for (int i = 0; i < RD_EGLT_MAX_CTX; i++)
        if (g_eglt_ctxs[i].ctx == ctx) {
            if (g_eglt_ctxs[i].pbuf) { eglDestroySurface(g_egl_display, g_eglt_ctxs[i].pbuf); g_eglt_ctxs[i].pbuf = NULL; }
            g_eglt_ctxs[i].ctx = NULL;
            break;
        }
    rd_eglDestroyContext(g_egl_display, ctx);
    LOGI("EGLT: destroyed shared context %p", ctx);
}

// ===================== RimDroid injected input (Phase A) =====================
// Lock-protected ring of pre-built x86_64 SDL_Event records. The Android touch
// handler (JNI, UI thread) pushes events here; box64's my2_SDL_PollEvent drains
// them via the weak rd_input_poll() below (box64 is a separate .so, so the ring
// lives here where JNI can reach it directly). SDL_Event is 56 bytes; we fill
// only the mouse fields.
#define RD_IN_QCAP 256
#define RD_SDL_EVENT_SZ 56
static unsigned char rd_in_q[RD_IN_QCAP][RD_SDL_EVENT_SZ];
static int rd_in_head = 0, rd_in_tail = 0;
static pthread_mutex_t rd_in_mx = PTHREAD_MUTEX_INITIALIZER;
static unsigned int rd_mouse_btnmask = 0;
static int rd_mouse_x = 0, rd_mouse_y = 0;   // current injected cursor (for SDL_GetMouseState)

#define RD_PUT32(buf,off,val) do { unsigned int _v=(unsigned int)(val); \
    (buf)[(off)+0]=_v&0xff; (buf)[(off)+1]=(_v>>8)&0xff; \
    (buf)[(off)+2]=(_v>>16)&0xff; (buf)[(off)+3]=(_v>>24)&0xff; } while(0)

#include <time.h>
static unsigned int rd_now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned int)((unsigned long long)ts.tv_sec * 1000ULL + ts.tv_nsec / 1000000ULL);
}
static void rd_in_push(const unsigned char* ev) {
    pthread_mutex_lock(&rd_in_mx);
    int n = (rd_in_head + 1) % RD_IN_QCAP;
    if (n != rd_in_tail) {
        memcpy(rd_in_q[rd_in_head], ev, RD_SDL_EVENT_SZ);
        RD_PUT32(rd_in_q[rd_in_head], 4, rd_now_ms());  // real SDL timestamp → fixes false double-clicks
        rd_in_head = n;
    }
    pthread_mutex_unlock(&rd_in_mx);
}

void rd_input_mouse_motion(int x, int y) {
    rd_mouse_x = x; rd_mouse_y = y;
    unsigned char e[RD_SDL_EVENT_SZ]; memset(e, 0, sizeof(e));
    RD_PUT32(e, 0,  0x400);              // SDL_MOUSEMOTION
    RD_PUT32(e, 8,  1);                  // windowID
    RD_PUT32(e, 16, rd_mouse_btnmask);   // button state mask
    RD_PUT32(e, 20, x);
    RD_PUT32(e, 24, y);
    rd_in_push(e);
}

void rd_input_mouse_button(int button, int down, int x, int y) {
    rd_mouse_x = x; rd_mouse_y = y;
    if (button >= 1) { if (down) rd_mouse_btnmask |= (1u << (button-1)); else rd_mouse_btnmask &= ~(1u << (button-1)); }
    unsigned char e[RD_SDL_EVENT_SZ]; memset(e, 0, sizeof(e));
    RD_PUT32(e, 0, down ? 0x401 : 0x402); // SDL_MOUSEBUTTONDOWN / UP
    RD_PUT32(e, 8, 1);                    // windowID
    e[16] = (unsigned char)button;        // 1=L,2=M,3=R
    e[17] = down ? 1 : 0;                  // SDL_PRESSED=1
    e[18] = 1;                            // clicks
    RD_PUT32(e, 20, x);
    RD_PUT32(e, 24, y);
    rd_in_push(e);
    LOGI("inject MOUSEBUTTON btn=%d down=%d @%d,%d", button, down, x, y);
}

// SDL_MOUSEWHEEL (0x403): y = +up / -down (RimWorld zoom). Set cursor first so the
// game zooms toward that point.
void rd_input_mouse_scroll(int dy) {
    unsigned char e[RD_SDL_EVENT_SZ]; memset(e, 0, sizeof(e));
    RD_PUT32(e, 0,  0x403);   // SDL_MOUSEWHEEL
    RD_PUT32(e, 8,  1);       // windowID
    RD_PUT32(e, 16, 0);       // x (horizontal)
    RD_PUT32(e, 20, dy);      // y (vertical: +up/-down)
    RD_PUT32(e, 24, 0);       // direction = SDL_MOUSEWHEEL_NORMAL
    rd_in_push(e);
    LOGI("inject MOUSEWHEEL dy=%d", dy);
}

// SDL_KEYDOWN(0x300)/KEYUP(0x301). Unity maps keysym.scancode (SDL_Scancode) to
// its KeyCode, so the scancode is what matters (e.g. W=26,A=4,S=22,D=7, arrows
// 79-82, Space=44, Esc=41, Return=40, digits 1-0 = 30-39).
void rd_input_key(int scancode, int keycode, int down) {
    unsigned char e[RD_SDL_EVENT_SZ]; memset(e, 0, sizeof(e));
    RD_PUT32(e, 0, down ? 0x300 : 0x301);  // SDL_KEYDOWN / SDL_KEYUP
    RD_PUT32(e, 8, 1);            // windowID
    e[12] = down ? 1 : 0;         // state (SDL_PRESSED=1)
    e[13] = 0;                    // repeat
    RD_PUT32(e, 16, scancode);    // keysym.scancode
    RD_PUT32(e, 20, keycode);     // keysym.sym
    // keysym.mod @24 = 0
    rd_in_push(e);
    LOGI("inject KEY sc=%d kc=%d down=%d", scancode, keycode, down);
}

// SDL_TEXTINPUT(0x303): UTF-8 text the game reads for typing (names, search).
void rd_input_text(const char* utf8) {
    unsigned char e[RD_SDL_EVENT_SZ]; memset(e, 0, sizeof(e));
    RD_PUT32(e, 0, 0x303);        // SDL_TEXTINPUT
    RD_PUT32(e, 8, 1);            // windowID
    int i = 0;                    // text[32] at offset 12
    if (utf8) { while (utf8[i] && i < 31) { e[12 + i] = (unsigned char)utf8[i]; i++; } }
    e[12 + i] = 0;
    rd_in_push(e);
    LOGI("inject TEXT '%s'", utf8 ? utf8 : "");
}

// Called (weakly) by box64's my2_SDL_PollEvent. Fills out (>=56 bytes) and
// returns 1 if an event was dequeued, else 0.
int rd_input_poll(unsigned char* out) {
    int got = 0;
    pthread_mutex_lock(&rd_in_mx);
    if (rd_in_tail != rd_in_head) {
        memcpy(out, rd_in_q[rd_in_tail], RD_SDL_EVENT_SZ);
        rd_in_tail = (rd_in_tail + 1) % RD_IN_QCAP;
        got = 1;
    }
    pthread_mutex_unlock(&rd_in_mx);
    return got;
}

// Called (weakly) by box64's my2_SDL_GetMouseState. Fills the current injected
// cursor position and returns the SDL button bitmask, so RimWorld's position/state
// polling (selection drag, right-click target) matches our virtual cursor.
unsigned int rd_input_get_mouse(int* x, int* y) {
    if (x) *x = rd_mouse_x;
    if (y) *y = rd_mouse_y;
    return rd_mouse_btnmask;
}
// ============================================================================

// ============================================================================
// ZFA create-context hang watchdog — Adreno 640 / Android 11 diagnosis.
//
// Two field devices (Galaxy S10 / SD855 and Poco X3 Pro / SD860 — both Adreno
// 640, both Android 11, system Vulkan driver built 03/2021) hang FOREVER inside
// zfaCreateContext: the last log line is "calling zfaCreateContext...", no
// crash, and the main thread keeps pumping input. Newer Turnip builds crash
// outright on their old KGSL interface, so the system driver is their only
// door — and it hangs. A healthy create returns in well under a second, so
// 15 s without a return is definitely the hang, not a slow device.
//
// When it fires, the watchdog signals the stuck thread; the handler grabs
// PC/LR from the ucontext and walks the frame-pointer chain (arm64 keeps frame
// records), then the watchdog logs "libzfa.so+0x..." lines we symbolize
// offline against the unstripped CI libzfa — the same technique that pinned
// the a610 flush_resource crash. Two samples 3 s apart distinguish a live spin
// (PCs move) from a blocked futex/ioctl (PCs identical). Diagnostic only: on a
// healthy device the watchdog thread exits quietly once create returns.

#define RD_WD_MAX_FRAMES 32
static struct {
    volatile int done;                     // set once zfaCreateContext returns
    pid_t        tid;                      // thread executing zfaCreateContext
    volatile int dump_ready;               // handler finished writing a sample
    int          nframes;
    uintptr_t    frames[RD_WD_MAX_FRAMES];
} rd_zfa_wd;

// Signal handler on the STUCK thread. Async-signal-safe: only reads the
// ucontext and walks readable stack memory into a static buffer; all logging
// and dladdr happen later on the watchdog thread.
static void rd_zfa_wd_handler(int sig, siginfo_t* si, void* uctx_v) {
    (void)sig; (void)si;
    int n = 0;
#if defined(__aarch64__)
    ucontext_t* uc = (ucontext_t*)uctx_v;
    uintptr_t pc = uc->uc_mcontext.pc;
    uintptr_t lr = uc->uc_mcontext.regs[30];
    uintptr_t fp = uc->uc_mcontext.regs[29];
    uintptr_t sp = uc->uc_mcontext.sp;
    rd_zfa_wd.frames[n++] = pc;
    if (lr) rd_zfa_wd.frames[n++] = lr;
    // Frame-record walk: [fp] = next fp, [fp+8] = return address. Bounds-check
    // fp against the live thread stack so a garbage frame can't fault us.
    while (n < RD_WD_MAX_FRAMES) {
        if (fp == 0 || (fp & 0xf) || fp < sp || fp - sp > (8u << 20)) break;
        uintptr_t next_fp = ((uintptr_t*)fp)[0];
        uintptr_t ret     = ((uintptr_t*)fp)[1];
        if (ret < 4096) break;
        rd_zfa_wd.frames[n++] = ret;
        if (next_fp <= fp) break;               // must grow toward stack base
        fp = next_fp;
    }
#else
    (void)uctx_v;
#endif
    rd_zfa_wd.nframes = n;
    rd_zfa_wd.dump_ready = 1;
}

// Read the kernel run-state of a thread ('R' running/spinning, 'S' sleeping on
// a futex/ioctl, 'D' uninterruptible IO) from /proc/self/task/<tid>/stat.
static char rd_zfa_wd_thread_state(pid_t tid) {
    char path[64], buf[256];
    snprintf(path, sizeof(path), "/proc/self/task/%d/stat", tid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return '?';
    ssize_t r = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (r <= 0) return '?';
    buf[r] = 0;
    char* p = strrchr(buf, ')');                // comm may contain spaces
    return (p && p[1] == ' ' && p[2]) ? p[2] : '?';
}

static void rd_zfa_wd_log_sample(int sample) {
    LOGE("ZFA WATCHDOG: sample %d — thread %d state '%c', %d frames:",
         sample, rd_zfa_wd.tid, rd_zfa_wd_thread_state(rd_zfa_wd.tid), rd_zfa_wd.nframes);
    for (int i = 0; i < rd_zfa_wd.nframes; i++) {
        uintptr_t a = rd_zfa_wd.frames[i];
        Dl_info info;
        if (dladdr((void*)a, &info) && info.dli_fname) {
            const char* base = strrchr(info.dli_fname, '/');
            base = base ? base + 1 : info.dli_fname;
            if (info.dli_sname)
                LOGE("ZFA WATCHDOG:   #%02d %s+0x%lx (%s+0x%lx)", i, base,
                     (unsigned long)(a - (uintptr_t)info.dli_fbase),
                     info.dli_sname, (unsigned long)(a - (uintptr_t)info.dli_saddr));
            else
                LOGE("ZFA WATCHDOG:   #%02d %s+0x%lx", i, base,
                     (unsigned long)(a - (uintptr_t)info.dli_fbase));
        } else {
            LOGE("ZFA WATCHDOG:   #%02d 0x%lx (unmapped?)", i, (unsigned long)a);
        }
    }
}

static void* rd_zfa_watchdog_main(void* arg) {
    (void)arg;
    // Sample schedule (ms since arming). 5 s, not 15: a healthy create finishes in well under a
    // second (the create duration is now logged on every launch to keep that claim honest), and
    // the first field run taught us testers give a black screen ~6 s before touching something —
    // the 15 s dump never happened. Sample 3 at 30 s tells a hang that sits in ONE kernel call
    // from one that keeps crawling through init (PCs move between samples => crawling).
    static const int fire_ms[3] = { 5000, 8000, 30000 };
    struct sigaction sa = {0}, old;
    int installed = 0;
    const int sig = SIGRTMIN + 7;               // clear of bionic-reserved RT signals
    int elapsed = 0;
    for (int sample = 1; sample <= 3; sample++) {
        while (elapsed < fire_ms[sample - 1]) {
            if (rd_zfa_wd.done) goto out;       // healthy path: exit silently
            usleep(100 * 1000);
            elapsed += 100;
        }
        if (sample == 1) {
            LOGE("ZFA WATCHDOG: zfaCreateContext stuck for 5 s — dumping thread %d", rd_zfa_wd.tid);
            // Install the handler only now, on the hang path, so a healthy launch never
            // carries it (and a later fork()ed child can't inherit it either).
            sa.sa_sigaction = rd_zfa_wd_handler;
            sa.sa_flags = SA_SIGINFO;
            sigemptyset(&sa.sa_mask);
            if (sigaction(sig, &sa, &old) != 0) {
                LOGE("ZFA WATCHDOG: sigaction failed: %s", strerror(errno));
                return NULL;
            }
            installed = 1;
        }
        rd_zfa_wd.dump_ready = 0;
        rd_zfa_wd.nframes = 0;
        if (syscall(SYS_tgkill, getpid(), rd_zfa_wd.tid, sig) != 0) {
            LOGE("ZFA WATCHDOG: tgkill failed: %s", strerror(errno));
            break;
        }
        for (int w = 0; w < 2000 && !rd_zfa_wd.dump_ready; w += 10) usleep(10 * 1000);
        if (!rd_zfa_wd.dump_ready) {
            // Signal never delivered — the thread is blocked in the kernel with
            // the signal queued (uninterruptible ioctl). That is itself the answer.
            LOGE("ZFA WATCHDOG: sample %d (@%ds) — no handler response in 2 s, thread state '%c'"
                 " (blocked in kernel, signal undelivered)",
                 sample, fire_ms[sample - 1] / 1000, rd_zfa_wd_thread_state(rd_zfa_wd.tid));
        } else {
            rd_zfa_wd_log_sample(sample);
        }
    }
    LOGE("ZFA WATCHDOG: dump complete — leaving the process alive for log export");
out:
    if (installed) sigaction(sig, &old, NULL);
    return NULL;
}

static int rimdroid_init_zfa(ANativeWindow* nativeWindow) {
    (void)nativeWindow;  // window is bound later via zfaMakeCurrent()
    // Load libzfa.so into rimdroid_ns (NOT the default namespace): Zink must find
    // the Vulkan loader/driver, which load_linker_hook() put into rimdroid_ns.
    // A plain dlopen() in the app's default namespace cannot link them together.
    g_zfa_handle = linkernsbypass_namespace_dlopen("libzfa.so", RTLD_GLOBAL, rimdroid_ns);
    if (!g_zfa_handle) {
        LOGE("ZFA: namespace dlopen('libzfa.so') failed: %s", dlerror());
        return -1;
    }
    p_zfaCreateContext  = (PFN_zfaCreateContext) dlsym(g_zfa_handle, "zfaCreateContext");
    p_zfaMakeCurrent    = (PFN_zfaMakeCurrent)   dlsym(g_zfa_handle, "zfaMakeCurrent");
    p_zfaFlushFront     = (PFN_zfaFlushFront)    dlsym(g_zfa_handle, "zfaFlushFront");
    p_zfaDestroyContext = (PFN_zfaDestroyContext)dlsym(g_zfa_handle, "zfaDestroyContext");
    p_zfaReleaseCurrent = (PFN_zfaReleaseCurrent)dlsym(g_zfa_handle, "zfaReleaseCurrent");  // NULL until libzfa rebuilt
    if (!p_zfaCreateContext || !p_zfaMakeCurrent || !p_zfaFlushFront) {
        LOGE("ZFA: missing entry points (create=%p makecur=%p flush=%p)",
             (void*)p_zfaCreateContext, (void*)p_zfaMakeCurrent, (void*)p_zfaFlushFront);
        return -1;
    }
    // depth=24, stencil=8, compat=0 (CORE profile), GL 4.3 (matches
    // MESA_GL_VERSION_OVERRIDE; satisfies Unity's "OpenGL core 3.2+" check).
    //
    // Arm the hang watchdog (see rd_zfa_watchdog_main above) and turn on Mesa's
    // own init logging JUST for the create: setenv before, unsetenv right after,
    // so the later fork()ed game child never inherits MESA_DEBUG spam. Mesa's
    // stderr is already piped into rimdroid.log, so anything it says before the
    // hang lands next to the watchdog dump.
    rd_zfa_wd.done = 0;
    rd_zfa_wd.tid  = gettid();
    setenv("MESA_DEBUG", "1", 0);
    pthread_t wd_thread;
    if (pthread_create(&wd_thread, NULL, rd_zfa_watchdog_main, NULL) == 0)
        pthread_detach(wd_thread);
    // The armed line + the duration below double as the build fingerprint in field logs (version
    // strings don't change between test builds; we've been burned identifying APKs before).
    LOGI("ZFA: hang watchdog armed (samples at 5/8/30 s)");
    struct timespec rd_t0, rd_t1;
    clock_gettime(CLOCK_MONOTONIC, &rd_t0);
    LOGI("ZFA: calling zfaCreateContext(24,8,0,4,3)...");
    g_zfa_context = p_zfaCreateContext(24, 8, 0, 4, 3);
    rd_zfa_wd.done = 1;
    unsetenv("MESA_DEBUG");
    clock_gettime(CLOCK_MONOTONIC, &rd_t1);
    // First real create-duration telemetry across the device park: how close does a healthy
    // create get to the 5 s watchdog threshold? (S25/Adreno 830 ballpark: tens of ms.)
    LOGI("ZFA: zfaCreateContext returned %p in %lld ms", g_zfa_context,
         (long long)((rd_t1.tv_sec - rd_t0.tv_sec) * 1000 + (rd_t1.tv_nsec - rd_t0.tv_nsec) / 1000000));
    if (!g_zfa_context) {
        LOGE("ZFA: zfaCreateContext failed");
        return -1;
    }
    LOGI("ZFA: calling initial zfaMakeCurrent...");
    if (!rimdroid_zfa_make_current()) {
        LOGE("ZFA: initial make-current failed");
        return -1;
    }
    LOGI("ZFA: initial make-current OK");
    LOGI("ZFA: context %p ready (Zink GL 4.3 core, handle=%p)", g_zfa_context, g_zfa_handle);

    // --- RIMDROID DIAG: probe ZFA default framebuffer (FBO 0) ---
    // GL spec: when a context is first made current, GL_VIEWPORT = (0,0,win_w,win_h).
    // If viewport[2..3] == 0 the default framebuffer (window backbuffer / Vulkan
    // swapchain) has NO size -> Unity can't render to FBO 0 -> GfxDevice teardown loop.
    {
        void (*p_glGetIntegerv)(unsigned int, int*) =
            (void (*)(unsigned int, int*))dlsym(g_zfa_handle, "glGetIntegerv");
        unsigned int (*p_glCheckFramebufferStatus)(unsigned int) =
            (unsigned int (*)(unsigned int))dlsym(g_zfa_handle, "glCheckFramebufferStatus");
        const unsigned char* (*p_glGetString)(unsigned int) =
            (const unsigned char* (*)(unsigned int))dlsym(g_zfa_handle, "glGetString");
        unsigned int (*p_glGetError)(void) =
            (unsigned int (*)(void))dlsym(g_zfa_handle, "glGetError");
        if (p_glGetIntegerv) {
            int vp[4] = {-1,-1,-1,-1};
            p_glGetIntegerv(0x0BA2, vp);             // GL_VIEWPORT
            int fbb = -1;
            p_glGetIntegerv(0x8CA6, &fbb);           // GL_FRAMEBUFFER_BINDING (expect 0)
            unsigned int st = p_glCheckFramebufferStatus ? p_glCheckFramebufferStatus(0x8D40) : 0; // GL_FRAMEBUFFER
            const char* ver = p_glGetString ? (const char*)p_glGetString(0x1F02) : NULL; // GL_VERSION
            const char* ren = p_glGetString ? (const char*)p_glGetString(0x1F01) : NULL; // GL_RENDERER
            unsigned int err = p_glGetError ? p_glGetError() : 0xDEAD;
            LOGI("RIMDROID FBO0-DIAG: viewport=[%d,%d,%d,%d] fb_binding=%d checkstatus=0x%x(complete=0x8CD5) GL_VERSION='%s' GL_RENDERER='%s' glerr=0x%x",
                 vp[0], vp[1], vp[2], vp[3], fbb, st,
                 ver ? ver : "(null)", ren ? ren : "(null)", err);
        } else {
            LOGI("RIMDROID FBO0-DIAG: glGetIntegerv not resolvable from libzfa");
        }
    }
    return 0;
}

static int rimdroid_init_gl4es_egl(ANativeWindow* nativeWindow) {
    // Experimental GL-translator harness (2026-08-09): BOX64_LIBGL may point at NG-GL4ES
    // ("Krypton", reports GL 4.50) or MobileGlues instead of classic gl4es — the smoke test for
    // the broken-Vulkan device class. Pre-load it with RTLD_GLOBAL because (a) NG's GetProcAddress
    // table is commented out upstream and its lookup is a bare dlsym(RTLD_DEFAULT), which only
    // finds the lib's own symbols if they are globally visible; (b) NG boots in a Zomboid-specific
    // SIMPLE shader-conversion mode that no Unity GLSL survives — the exported
    // updateSimpleShaderConvState(0) switches it to the glslang/SPIR-V path (full audit: memory
    // ng_gl4es_audit). Classic libgl4es just gets one extra harmless dlopen + a log line.
    {
        const char* libgl = getenv("BOX64_LIBGL");
        if (libgl && libgl[0]) {
            // Preload known translator DT_NEEDED deps from the SAME dir by absolute path first:
            // the deps dir is not on the default namespace search path, so NEEDED-by-soname would
            // fail; a lib already loaded under that soname satisfies NEEDED for both our dlopen
            // and box64's own one. NG-GL4ES needs libspirv-cross-c-shared.so (glslang out path).
            {
                const char* slash = strrchr(libgl, '/');
                if (slash) {
                    char dep[512];
                    int dirlen = (int)(slash - libgl);
                    snprintf(dep, sizeof(dep), "%.*s/libspirv-cross-c-shared.so", dirlen, libgl);
                    void* dh = dlopen(dep, RTLD_NOW | RTLD_GLOBAL);
                    if (dh) LOGI("GLT: preloaded dep %s", dep);
                }
            }
            void* h = dlopen(libgl, RTLD_NOW | RTLD_GLOBAL);
            LOGI("GLT: dlopen('%s') -> %p%s%s", libgl, h,
                 h ? "" : " FAILED: ", h ? "" : dlerror());
            if (h) {
                void (*fSimple)(int) = (void(*)(int))dlsym(h, "updateSimpleShaderConvState");
                if (fSimple) {
                    fSimple(0);
                    LOGI("GLT: updateSimpleShaderConvState(0) — NG simple shader path OFF, glslang path ON");
                } else {
                    LOGI("GLT: no updateSimpleShaderConvState export (classic gl4es / MobileGlues / older NG)");
                }
                // Fingerprint the exports the smoke cares about: what Unity's GL bootstrap will find.
                LOGI("GLT: exports: glGetString=%p glXGetProcAddress=%p glGenQueries=%p glTexStorage2D=%p glGenVertexArrays=%p glMapBufferRange=%p",
                     dlsym(h, "glGetString"), dlsym(h, "glXGetProcAddress"),
                     dlsym(h, "glGenQueries"), dlsym(h, "glTexStorage2D"),
                     dlsym(h, "glGenVertexArrays"), dlsym(h, "glMapBufferRange"));
                // Before any context exists: everything below must go through these (see the
                // EGLTRACK note above) or MG never records our contexts.
                rimdroid_glt_egl_route(h);
            }
        }
    }
    g_egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_egl_display == EGL_NO_DISPLAY) {
        LOGE("EGL: eglGetDisplay failed");
        return -1;
    }

    EGLint major = 0, minor = 0;
    if (!eglInitialize(g_egl_display, &major, &minor)) {
        LOGE("EGL: eglInitialize failed: 0x%x", eglGetError());
        return -1;
    }
    LOGI("EGL: version %d.%d", major, minor);

    // Choose a config that supports GLES3 window rendering with depth+stencil.
    const EGLint attribs[] = {
        EGL_RED_SIZE,           8,
        EGL_GREEN_SIZE,         8,
        EGL_BLUE_SIZE,          8,
        EGL_ALPHA_SIZE,         8,
        EGL_DEPTH_SIZE,         24,
        EGL_STENCIL_SIZE,       8,
        EGL_RENDERABLE_TYPE,    EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE,       EGL_WINDOW_BIT,
        EGL_NONE
    };
    EGLConfig config = NULL;
    EGLint numConfigs = 0;
    if (!eglChooseConfig(g_egl_display, attribs, &config, 1, &numConfigs) || numConfigs == 0) {
        // Fallback: GLES2
        LOGW("EGL: GLES3 config not found, falling back to GLES2");
        const EGLint attribs2[] = {
            EGL_RED_SIZE,           8,
            EGL_GREEN_SIZE,         8,
            EGL_BLUE_SIZE,          8,
            EGL_ALPHA_SIZE,         8,
            EGL_DEPTH_SIZE,         24,
            EGL_STENCIL_SIZE,       8,
            EGL_RENDERABLE_TYPE,    EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE,       EGL_WINDOW_BIT,
            EGL_NONE
        };
        if (!eglChooseConfig(g_egl_display, attribs2, &config, 1, &numConfigs) || numConfigs == 0) {
            LOGE("EGL: eglChooseConfig failed: 0x%x", eglGetError());
            return -1;
        }
    }

    // Match the ANativeWindow pixel format to the EGL config.
    EGLint format = 0;
    eglGetConfigAttrib(g_egl_display, config, EGL_NATIVE_VISUAL_ID, &format);
    ANativeWindow_setBuffersGeometry(nativeWindow, 0, 0, format);

    g_egl_surface = eglCreateWindowSurface(g_egl_display, config, nativeWindow, NULL);
    if (g_egl_surface == EGL_NO_SURFACE) {
        LOGE("EGL: eglCreateWindowSurface failed: 0x%x", eglGetError());
        return -1;
    }

    // Try GLES3 context first, fallback to GLES2.
    const EGLint ctx3[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    g_egl_context = rd_eglCreateContext(g_egl_display, config, EGL_NO_CONTEXT, ctx3);
    if (g_egl_context == EGL_NO_CONTEXT) {
        LOGW("EGL: GLES3 context failed (0x%x), trying GLES2", eglGetError());
        const EGLint ctx2[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
        g_egl_context = rd_eglCreateContext(g_egl_display, config, EGL_NO_CONTEXT, ctx2);
    }
    if (g_egl_context == EGL_NO_CONTEXT) {
        LOGE("EGL: eglCreateContext failed: 0x%x", eglGetError());
        return -1;
    }
    g_egl_config = config;   // kept for the multi-context bridge (worker contexts share this config)

    // Make current on this thread so GL4ES can query capabilities immediately.
    if (!rd_eglMakeCurrent(g_egl_display, g_egl_surface, g_egl_surface, g_egl_context)) {
        LOGE("EGL: eglMakeCurrent failed: 0x%x", eglGetError());
        return -1;
    }
    // RIMDROID_GLT_NOVSYNC=1: present without waiting for the display tick. Diagnostic first
    // (the menu FPS ceiling on MobileGlues read exactly 120 = the S25's refresh rate, masking
    // the path's true throughput vs zink's unthrottled 500-600), possibly a small in-game win
    // (frames that just miss a tick don't stall). Off by default: uncapped = heat/battery.
    {
        const char* nv = getenv("RIMDROID_GLT_NOVSYNC");
        if (nv && nv[0] == '1') {
            eglSwapInterval(g_egl_display, 0);
            LOGI("EGL: swap interval 0 (RIMDROID_GLT_NOVSYNC=1) — present does not wait for vsync");
        }
    }
    LOGI("EGL: context %p surface %p display %p — GL4ES ready",
         g_egl_context, g_egl_surface, g_egl_display);

    // Hand GL4ES a resolver for the real GLES driver functions.  BOX64_LIBGL is
    // the absolute path to libgl4es.so (set by GameLauncher).  This global is
    // inherited by the forked child, so calling it here in the parent is enough.
    {
        const char* gl4es_path = getenv("BOX64_LIBGL");
        if (gl4es_path && *gl4es_path) {
            void* h = dlopen(gl4es_path, RTLD_LAZY | RTLD_GLOBAL);
            if (h) {
                void (*set_gpa)(void* (*)(const char*)) =
                    (void (*)(void* (*)(const char*)))dlsym(h, "set_getprocaddress");
                if (set_gpa) {
                    set_gpa(rimdroid_gles_resolver);
                    LOGI("GL4ES: set_getprocaddress installed");
                } else {
                    // Not an error: set_getprocaddress is a classic-GL4ES-only entry point. MobileGlues
                    // and NG-GL4ES resolve the GLES driver themselves, so its absence is expected there.
                    LOGI("GLT: no set_getprocaddress export in %s (expected for MobileGlues / NG-GL4ES)",
                         gl4es_path);
                }
            } else {
                LOGE("GL4ES: dlopen(%s) failed: %s", gl4es_path, dlerror());
            }
        } else {
            LOGE("GL4ES: BOX64_LIBGL not set — cannot install GLES resolver");
        }
    }
    return 0;
}

// ---- Memory / stdio monitor -------------------------------------------------

static long get_mem_available_mb() {
    FILE* f = fopen("/proc/meminfo", "r");
    if (!f) return -1;
    char line[256];
    long memAvailableKb = -1;
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "MemAvailable: %ld kB", &memAvailableKb) == 1) break;
    }
    fclose(f);
    return (memAvailableKb > 0) ? (memAvailableKb / 1024) : -1;
}

__attribute__((noreturn))
static void monitor_stdio_and_memory() {
    int pipefd[2];
    char buffer[8192];

    if (pipe(pipefd) == -1) { LOGE("Failed to create stdio pipe"); abort(); }

    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    dup2(pipefd[1], STDOUT_FILENO);
    dup2(pipefd[1], STDERR_FILENO);
    close(pipefd[1]);
    fcntl(pipefd[0], F_SETFL, O_NONBLOCK);

    time_t last_mem_check = 0;
    time_t last_mem_log   = 0;

    while (1) {
        ssize_t n = read(pipefd[0], buffer, sizeof(buffer) - 1);
        if (n > 0) {
            buffer[n] = '\0';
            char* saveptr;
            char* line = strtok_r(buffer, "\n", &saveptr);
            while (line) {
                // Write to logcat and to the file via LOGI
                LOGI("%s", line);
                line = strtok_r(NULL, "\n", &saveptr);
            }
        }

        time_t now = time(NULL);
        if ((now - last_mem_check >= 1) && (now - last_mem_log >= 30)) {
            last_mem_check = now;
            long free_mb = get_mem_available_mb();
            if (free_mb != -1 && free_mb < 300) {
                last_mem_log = now;
                LOGW("Low memory: only %ld MB available", free_mb);
            }
        }
        usleep(10000);
    }
}

// ---- Exit / abort handlers --------------------------------------------------

static void rimdroid_atexit_handler(void) {
    // Called on any exit() from any code in the process (native or emulated).
    // This gives us a native-side confirmation that exit() was the cause of death
    // (as opposed to a signal, which would not call atexit handlers).
    LOGE("=== atexit handler fired: process is exiting via exit() ===");
}

static void handle_abort(int sig) {
    LOGE("SIGABRT received");
    signal(SIGABRT, SIG_DFL);
    raise(SIGABRT);
}

// ---- Fatal signal handler (SIGILL, SIGBUS) -----------------------------------
// These are left at SIG_DFL by the reset loop, which causes silent termination.
// This handler logs the crash info to logcat and lets the OS write a tombstone.

static void handle_fatal_signal(int sig, siginfo_t* info, void* ctx) {
    void* pc = NULL;
    if (ctx) {
        ucontext_t* uc = (ucontext_t*)ctx;
        pc = (void*)uc->uc_mcontext.pc;   // arm64 program counter
    }
    // NOTE: do NOT call dladdr() here — it takes the linker lock, which the
    // crashing Vulkan loader (mid dlopen/dlsym) may already hold → deadlock/hang.
    // Instead dump /proc/self/maps via raw async-signal-safe syscalls so the PC
    // can be resolved offline.  STDERR is dup2'd to rimdroid_game.log.
    LOGE("Fatal signal %d (%s) addr=%p pc=%p tid=%d",
         sig, strsignal(sig), info ? info->si_addr : NULL, pc, (int)gettid());
    {
        const char hdr[] = "=== FATAL /proc/self/maps dump ===\n";
        write(STDERR_FILENO, hdr, sizeof(hdr) - 1);
        int mf = open("/proc/self/maps", O_RDONLY);
        if (mf >= 0) {
            char buf[4096];
            ssize_t n;
            while ((n = read(mf, buf, sizeof(buf))) > 0) {
                ssize_t off = 0;
                while (off < n) {
                    ssize_t w = write(STDERR_FILENO, buf + off, (size_t)(n - off));
                    if (w <= 0) break;
                    off += w;
                }
            }
            close(mf);
        }
        const char ftr[] = "=== end maps dump ===\n";
        write(STDERR_FILENO, ftr, sizeof(ftr) - 1);
    }
    _exit(139);
}

// ---- Namespace init ---------------------------------------------------------

static int init_rimdroid_namespace(const char* ld_library_path) {
    if (!linkernsbypass_load_status()) {
        LOGE("linkernsbypass is not loaded");
        return -1;
    }

    // SHARED (not SHARED_ISOLATED): an isolated namespace restricts loads to
    // permitted_paths and cannot reach /apex/.../bionic/libdl_android.so, which
    // /system/lib64/libvulkan.so needs — breaking the ZINK_ZFA Vulkan loader.
    // SHARED inherits the parent's accessibility (incl. apex bionic), matching
    // the proven zomdroid setup, and still works for GL4ES.
    rimdroid_ns = android_create_namespace(
        "rimdroid-ns",
        ld_library_path,
        ld_library_path,
        ANDROID_NAMESPACE_TYPE_SHARED,
        NULL,
        NULL
    );

    if (!rimdroid_ns) {
        LOGE("android_create_namespace failed");
        return -1;
    }
    return 0;
}

// ---- Linker hook ------------------------------------------------------------

static int load_linker_hook() {
    void* rimdroid_linker = linkernsbypass_namespace_dlopen(
        "librimdroidlinker.so", RTLD_LOCAL, rimdroid_ns);

    if (!rimdroid_linker) {
        LOGE("Failed to load librimdroidlinker.so: %s", dlerror());
        return -1;
    }

    void (*rimdroid_linker_set_proc_addrs)(void*, void*, void*) =
        dlsym(rimdroid_linker, "rimdroid_linker_set_proc_addrs");
    int (*rimdroid_linker_init)() =
        dlsym(rimdroid_linker, "rimdroid_linker_init");
    void (*rimdroid_linker_set_vulkan_loader_handle)(void*) =
        dlsym(rimdroid_linker, "rimdroid_linker_set_vulkan_loader_handle");
    void (*rimdroid_linker_set_vulkan_driver_handle)(void*) =
        dlsym(rimdroid_linker, "rimdroid_linker_set_vulkan_driver_handle");

    if (!rimdroid_linker_init || !rimdroid_linker_set_proc_addrs ||
        !rimdroid_linker_set_vulkan_loader_handle || !rimdroid_linker_set_vulkan_driver_handle) {
        LOGE("Failed to locate symbols in librimdroidlinker.so");
        return -1;
    }

    void* libdl = dlopen("libdl.so", RTLD_LAZY);
    void* _loader_dlopen_fn             = dlsym(libdl, "__loader_dlopen");
    void* _loader_dlsym_fn              = dlsym(libdl, "__loader_dlsym");
    void* _loader_android_dlopen_ext_fn = dlsym(libdl, "__loader_android_dlopen_ext");

    if (!_loader_dlopen_fn || !_loader_dlsym_fn || !_loader_android_dlopen_ext_fn) {
        LOGE("Failed to locate loader symbols in libdl.so");
        return -1;
    }

    rimdroid_linker_set_proc_addrs(
        _loader_dlopen_fn, _loader_dlsym_fn, _loader_android_dlopen_ext_fn);

    if (rimdroid_linker_init() != 0) {
        LOGE("rimdroid_linker_init() failed");
        return -1;
    }

    if (g_rimdroid_vulkan_driver_name != NULL) {
        void* vulkan_loader = linkernsbypass_namespace_dlopen_unique(
            "/system/lib64/libvulkan.so", NULL, RTLD_GLOBAL, rimdroid_ns);
        if (!vulkan_loader) {
            LOGE("Failed to load libvulkan.so: %s", dlerror());
            return -1;
        }
        rimdroid_linker_set_vulkan_loader_handle(vulkan_loader);

        void* vulkan_driver = linkernsbypass_namespace_dlopen(
            g_rimdroid_vulkan_driver_name, RTLD_LOCAL, rimdroid_ns);
        if (!vulkan_driver) {
            LOGE("Failed to load vulkan driver %s: %s", g_rimdroid_vulkan_driver_name, dlerror());
            return -1;
        }
        rimdroid_linker_set_vulkan_driver_handle(vulkan_driver);
    }

    return 0;
}

// ---- ELF launch via box64 ---------------------------------------------------

static void launch_rimworld_elf(const char* game_dir_path, int argc, const char** argv) {
    void* linker = dlopen("librimdroidlinker.so", RTLD_NOLOAD);
    if (!linker) {
        LOGE("librimdroidlinker.so not loaded when trying to run ELF");
        return;
    }

    int (*run_elf_file)(const char*, int, const char**) =
        dlsym(linker, "rimdroid_run_elf");

    if (!run_elf_file) {
        LOGE("rimdroid_run_elf symbol not found");
        return;
    }

    char binary_path[1024];
    // RIMDROID_EXEC override (X11 bring-up, see memory rimworld_16_port): run an
    // arbitrary guest ELF (e.g. xdpyinfo) instead of the game, WITHOUT the Unity
    // -screen-* args (foreign tools reject unknown options).
    const char* rd_exec = getenv("RIMDROID_EXEC");
    if (rd_exec && rd_exec[0]) {
        snprintf(binary_path, sizeof(binary_path), "%s", rd_exec);
        const char** exec_argv = malloc((argc + 1) * sizeof(char*));
        exec_argv[0] = binary_path;
        for (int i = 0; i < argc; i++) exec_argv[i + 1] = argv[i];
        LOGI("Executing (RIMDROID_EXEC override): %s", binary_path);
        run_elf_file(binary_path, argc + 1, exec_argv);
        free(exec_argv);
        return;
    }
    snprintf(binary_path, sizeof(binary_path), "%s/RimWorldLinux", game_dir_path);

    // RIMDROID: windowed at the NATIVE surface size, so Unity's window == our GL
    // surface == the ANativeWindow buffer == the physical surface (no scale, no
    // tiny-corner). Built at runtime from the real surface dimensions.
    static char rd_w_str[16], rd_h_str[16];
    int nw = g_rimdroid_surface.width  > 0 ? g_rimdroid_surface.width  : 2340;
    int nh = g_rimdroid_surface.height > 0 ? g_rimdroid_surface.height : 1080;
    snprintf(rd_w_str, sizeof(rd_w_str), "%d", nw);
    snprintf(rd_h_str, sizeof(rd_h_str), "%d", nh);
    const char* extra_argv[] = {
        "-screen-fullscreen", "0",
        "-screen-width",  rd_w_str,
        "-screen-height", rd_h_str,
        // RimDroid 1.6/X11: Unity 2022's Vulkan backend was creating our surface and then never
        // touching it (no caps/formats/swapchain, offscreen rendering leaking ~1-2GB GPU/min).
        // This UnityPlayer.so flag (string present in the binary) forces the onscreen swapchain.
        "-force-vulkan-onscreen-swapchain",
        // Borderless window: with Prefs pinned to fullscreen=False this fully avoids SDL's
        // WM-less legacy-fullscreen unmap/reparent dance (present-gate root cause suspect).
        "-popupwindow",
    };
    const int extra_n = (int)(sizeof(extra_argv) / sizeof(extra_argv[0]));

    const char** full_argv = malloc((argc + extra_n + 1) * sizeof(char*));
    full_argv[0] = binary_path;
    for (int i = 0; i < argc; i++) full_argv[i + 1] = argv[i];
    for (int i = 0; i < extra_n; i++) full_argv[argc + 1 + i] = extra_argv[i];

    LOGI("Executing: %s (+ -screen-fullscreen 0 -screen-width 2340 -screen-height 1080)", binary_path);
    run_elf_file(binary_path, argc + extra_n + 1, full_argv);
    free(full_argv);
}

// ---- Public API -------------------------------------------------------------

void rimdroid_start_game(const char* game_dir_path,
                         const char* library_dir_path,
                         int argc,
                         const char** argv) {

    signal(SIGABRT, handle_abort);
    atexit(rimdroid_atexit_handler);

    // Open the log file FIRST — before anything else
    snprintf(g_log_file_path, sizeof(g_log_file_path), "%s/rimdroid.log", game_dir_path);
    g_rimdroid_log_file = fopen(g_log_file_path, "w");
    if (g_rimdroid_log_file) {
        setvbuf(g_rimdroid_log_file, NULL, _IOLBF, 0);
        fprintf(g_rimdroid_log_file, "=== RimDroid log started ===\n");
        // Self-describing header: the launcher settings this run was started with (renderer,
        // Vulkan driver, debug, render scale, box64 knobs). Composed in Java (GameLauncher), passed
        // verbatim via RIMDROID_LAUNCH_CONFIG, so a pasted rimdroid.log says how it was launched.
        const char* launch_cfg = getenv("RIMDROID_LAUNCH_CONFIG");
        if (launch_cfg && launch_cfg[0]) {
            fprintf(g_rimdroid_log_file, "%s\n", launch_cfg);
        }
        fflush(g_rimdroid_log_file);
    }

    // Now all LOGI/LOGE write to both logcat and the file
    LOGI("rimdroid_start_game: game=%s libs=%s", game_dir_path, library_dir_path);

    const char* ld_path = getenv("BOX64_LD_LIBRARY_PATH");
    LOGI("BOX64_LD_LIBRARY_PATH from env: %s", ld_path ? ld_path : "NOT SET");

    // Start stdout/stderr → logcat + file bridge
    pthread_t logging_thread;
    if (pthread_create(&logging_thread, NULL,
                       (void *(*)(void *))&monitor_stdio_and_memory, NULL) == 0) {
        pthread_detach(logging_thread);
    } else {
        LOGW("Failed to create stdio logging thread");
    }

    if (init_rimdroid_namespace(library_dir_path) != 0) {
        LOGE("Failed to initialize rimdroid namespace");
        return;
    }

    if (load_linker_hook() != 0) {
        LOGE("Failed to load linker hook");
        return;
    }

    if (chdir(game_dir_path) != 0) {
        LOGE("chdir(%s) failed: %s", game_dir_path, strerror(errno));
        return;
    }

    struct sigaction sa = { 0 };
    for (int sig = SIGHUP; sig < NSIG; sig++) {
        if (sig == SIGSEGV)      sa.sa_handler = SIG_IGN;
        else if (sig == SIGABRT) continue;
        else                     sa.sa_handler = SIG_DFL;
        sigaction(sig, &sa, NULL);
    }

    // Override SIGILL and SIGBUS so they log the faulting address before dying.
    // (box64 may later override SIGBUS for its own dynarec; that's fine.)
    struct sigaction sa_fatal;
    memset(&sa_fatal, 0, sizeof(sa_fatal));
    sa_fatal.sa_sigaction = handle_fatal_signal;
    sa_fatal.sa_flags = SA_SIGINFO;
    sigaction(SIGILL, &sa_fatal, NULL);
    sigaction(SIGBUS, &sa_fatal, NULL);

    LOGI("Starting RimWorldLinux via box64...");

    // For GL4ES: wait for ANativeWindow, then initialise EGL in the PARENT
    // before fork().  EGL on Android uses libbinder IPC to talk to SurfaceFlinger;
    // libbinder explicitly refuses to operate after fork() with:
    //   "libbinder ProcessState can not be used after fork"
    // so eglGetDisplay() in the child instantly fails.  We must initialise EGL
    // in the parent and rebind via eglMakeCurrent() in the child.
    // GL4ES and ZINK_ZFA both need GPU/window init done in the PARENT (Vulkan and
    // EGL both use libbinder IPC, which refuses to operate after fork()).  The
    // child only rebinds the context to its thread.
    const char* direct_vulkan_env = getenv("RIMDROID_DIRECT_VULKAN");
    const bool direct_vulkan = direct_vulkan_env && direct_vulkan_env[0] &&
                               strcmp(direct_vulkan_env, "0") != 0;
    if (!direct_vulkan &&
        (g_rimdroid_renderer == RD_GL4ES || g_rimdroid_renderer == RD_ZINK_ZFA)) {
        const char* rname = (g_rimdroid_renderer == RD_GL4ES) ? "GL4ES" : "ZFA";
        LOGI("%s: waiting for native_window (up to 5 s)...", rname);
        for (int i = 0; i < 500 && !g_rimdroid_surface.native_window; i++) {
            struct timespec ts = {0, 10 * 1000 * 1000}; // 10 ms
            nanosleep(&ts, NULL);
        }
        if (g_rimdroid_surface.native_window) {
            // NOTE (2026-05-29): both GL4ES (EGL) and ZFA (Zink/Turnip Vulkan WSI)
            // need binder to create the GPU context, and binder refuses to operate
            // after fork — so the context MUST be created here in the parent.
            // Creating it in the forked child fails with "libbinder ProcessState
            // can not be used after fork" (verified).  But using the parent's
            // context in the child is GPU-fork-unsafe → crashes on the first real
            // texture upload.  This catch-22 is the current hard blocker; the real
            // fix is a non-forked process (exec'd standalone box64) or a fork-safe
            // software renderer (llvmpipe).  See memory/renderer_and_sigaction.md.
            LOGI("%s: native_window ready: %p — initialising in parent",
                 rname, g_rimdroid_surface.native_window);
            int rc = (g_rimdroid_renderer == RD_GL4ES)
                ? rimdroid_init_gl4es_egl(g_rimdroid_surface.native_window)
                : rimdroid_init_zfa(g_rimdroid_surface.native_window);
            if (rc != 0) {
                LOGE("%s: init failed in parent — GL will likely crash", rname);
            }
        } else {
            LOGE("%s: timed out waiting for native_window — GL will fail", rname);
        }
    } else if (direct_vulkan) {
        LOGI("DIRECT_VULKAN: skipping legacy GL/ZFA context init; Unity owns ANativeWindow WSI");
    }

    // RD_SOFTPIPE: CPU software renderer (OSMesa + softpipe). No GPU/Vulkan/EGL →
    // no binder, so the fork caveats above do not apply; but we still need the
    // surface ready before creating the context (the buffer is sized from it).
    // The game is relocatable → runs in-process (below), so the context created
    // here is valid for the whole run.
    if (g_rimdroid_renderer == RD_SOFTPIPE) {
        LOGI("SOFTPIPE: waiting for native_window (up to 5 s)...");
        for (int i = 0; i < 500 && !g_rimdroid_surface.native_window; i++) {
            struct timespec ts = {0, 10 * 1000 * 1000}; // 10 ms
            nanosleep(&ts, NULL);
        }
        if (g_rimdroid_surface.native_window) {
            LOGI("SOFTPIPE: native_window ready: %p — initialising OSMesa softpipe",
                 g_rimdroid_surface.native_window);
            if (rimdroid_init_osmesa() != 0)
                LOGE("SOFTPIPE: OSMesa init failed — GL will fall through to dummy SDL");
        } else {
            LOGE("SOFTPIPE: timed out waiting for native_window — GL will fail");
        }
    }

    // --- RELOCATABLE GAME → run IN-PROCESS, NO FORK ---------------------------
    // RimWorld 1.5+ (Unity 2022) ships the engine as UnityPlayer.so (a DYN shared
    // library loaded at a flexible address) + a tiny launcher whose segments sit
    // below the ART heap — so NOTHING collides with the dalvik heap.  That means
    // box64 can run it right here in the app process with NO fork and NO munmap.
    // The whole reason for the fork (freeing 0x021a9000) does not apply, and
    // running in-process keeps the Android graphics framework + the real Surface
    // available → the GPU context (created above in this same never-forked
    // process) is valid → no GPU-after-fork crash.
    // (Monolithic non-PIE builds like RimWorld 1.2 have data@0x021a9000 inside
    // the heap and still need the fork path below.)
    {
        char up[1200];
        snprintf(up, sizeof(up), "%s/UnityPlayer.so", game_dir_path);
        if (access(up, F_OK) == 0) {
            LOGI("Relocatable game (UnityPlayer.so present) — running IN-PROCESS, NO fork");
            // SDL GL remap: REQUIRED for 1.5 too.  Disassembly of UnityPlayer.so's
            // jump table shows RimWorld 1.5's SDL uses the SAME GL slot order as
            // 1.2 (GetProcAddress=510, CreateContext=515, MakeCurrent=516,
            // SwapWindow=521, DeleteContext=522) — which DIFFERS from box64's
            // newer SDL (514/515/520/521/527/528).  Without the remap, Unity's
            // SDL_GL_CreateContext (game idx 515) lands on box64's GetProcAddress
            // bridge → no ZFA context → "Unable to find a supported OpenGL core
            // profile".  The remap's built-in 1.2 indices are correct here, so
            // enable it (it's on by default; set explicitly for clarity).
            setenv("RIMDROID_SDL_REMAP", "1", 1);
            LOGI("RIMDROID_SDL_REMAP=1: SDL GL remap ENABLED (1.5 uses 1.2 GL slot order)");
            // Boehm GC (libmonobdwgc) tuning: RimWorld's content load allocates
            // heavily and, with a small heap, triggers hundreds of stop-the-world
            // GCs — each one is a signal round-trip to every thread, brutally slow
            // under box64.  A large initial heap drastically cuts GC frequency →
            // much faster load.  GC_FREE_SPACE_DIVISOR low = collect less often.
            // overwrite=0: these are DEFAULTS only — a per-instance "Extra env vars"
            // entry (e.g. a smaller GC_INITIAL_HEAP_SIZE + higher GC_FREE_SPACE_DIVISOR
            // on a low-RAM device to cut peak memory / avoid OOM) takes precedence.
            //
            // Scale the initial heap to the device's physical RAM instead of a fixed 1 GiB.
            // A big head start cuts GC frequency (each stop-the-world collection is a brutally
            // slow signal round-trip under box64), but a flat 1 GiB is too much up-front on
            // 4-6 GB phones: it both contributes to OOM (caravan-trade crash on a 6 GB device) and,
            // in the IN-PROCESS path on a 39-bit-VA device, adds to the address-space crunch (ART
            // boot image + scudo reservations already crowd the low VA; a 1 GiB contiguous mmap makes
            // it worse). Use ~1/12 of RAM, clamped to [384 MiB, 1 GiB]: flagships keep the full 1 GiB,
            // low-RAM devices get ~512 MiB (6 GB) / ~384 MiB (4 GB). Env field still overrides.
            {
                long phys_pages = sysconf(_SC_PHYS_PAGES);
                long page_size  = sysconf(_SC_PAGE_SIZE);
                unsigned long long total_ram =
                    (phys_pages > 0 && page_size > 0)
                        ? (unsigned long long)phys_pages * (unsigned long long)page_size : 0ULL;
                const unsigned long long HEAP_MIN = 384ULL * 1024 * 1024;   // 384 MiB floor
                const unsigned long long HEAP_MAX = 1024ULL * 1024 * 1024;  // 1 GiB cap (prior default)
                unsigned long long heap = (total_ram > 0) ? (total_ram / 12ULL) : HEAP_MAX;
                if (heap < HEAP_MIN) heap = HEAP_MIN;
                if (heap > HEAP_MAX) heap = HEAP_MAX;
                char heap_buf[32];
                snprintf(heap_buf, sizeof(heap_buf), "%llu", heap);
                setenv("GC_INITIAL_HEAP_SIZE", heap_buf, 0);   // RAM-scaled
                setenv("GC_FREE_SPACE_DIVISOR", "1", 0);       // collect rarely
                LOGI("Boehm GC: device RAM=%lluMiB -> initial heap %lluMiB, fsd=1 (overridable via env)",
                     total_ram / (1024ULL * 1024), heap / (1024ULL * 1024));
            }
            launch_rimworld_elf(game_dir_path, argc, argv);
            LOGI("In-process launch returned");
            LOGI("rimdroid_start_game: done (in-process)");
            return;
        }
        LOGI("No UnityPlayer.so — monolithic build, using fork path");
    }

    // Fork a child process so it gets a clean view of the address space.
    // Problem: JVM dalvik-main space occupies 0x02000000–0x12000000, which
    // collides with RimWorldLinux's data segment at 0x021a9000.  Box64 cannot
    // mmap the ELF at its requested address, falls back to a random address,
    // and the dynarec generates incorrect ARM64 jumps → SIGSEGV at 0x19f21d0.
    // Solution: fork(), then munmap() the JVM heap in the child so box64 can
    // claim 0x021a9000.  The child never calls Java/JNI, so the fork is safe.
    pid_t child_pid = fork();
    if (child_pid == 0) {
        // ---- Child process ----

        // Free the JVM heap region that conflicts with the ELF data segment.
        // This does NOT affect the parent; the parent's heap is untouched.
        if (munmap((void*)0x02000000, 0x10000000) != 0) {
            // Not fatal — log and continue; box64 may still find free space.
            LOGW("Child: munmap(0x02000000, 0x10000000) failed: %s", strerror(errno));
        } else {
            LOGI("Child: freed dalvik-main space (0x02000000–0x12000000)");
        }

        // Reopen log to a separate file so child and parent don't interleave.
        if (g_rimdroid_log_file) { fclose(g_rimdroid_log_file); g_rimdroid_log_file = NULL; }
        snprintf(g_log_file_path, sizeof(g_log_file_path),
                 "%s/rimdroid_game.log", game_dir_path);
        g_rimdroid_log_file = fopen(g_log_file_path, "w");
        if (g_rimdroid_log_file) {
            setvbuf(g_rimdroid_log_file, NULL, _IOLBF, 0);
            fprintf(g_rimdroid_log_file, "=== RimDroid game log (child pid=%d) ===\n",
                    (int)getpid());
            // Redirect stdout/stderr to the game log file so box64 printf output
            // goes there instead of the parent's pipe (which nobody reads in child).
            int log_fd = fileno(g_rimdroid_log_file);
            dup2(log_fd, STDOUT_FILENO);
            dup2(log_fd, STDERR_FILENO);
            // box64 logs via printf_log → ftrace, which defaults to the stdout/stderr
            // FILE*.  When those point at a regular file they become FULLY buffered,
            // so on a hang/crash the last ~4 KB never reach disk and the log appears
            // to stop mid-init.  Force unbuffered so the true last line is always
            // flushed — essential for diagnosing where the child hangs.
            setvbuf(stdout, NULL, _IONBF, 0);
            setvbuf(stderr, NULL, _IONBF, 0);
        }

        // Reset all signal handlers to SIG_DFL so Unity/Mono can find "available" signals.
        // After fork(), child inherits whatever handlers were installed (JVM ART, our
        // own handle_abort/handle_fatal_signal, etc.).  Unity's Mono runtime scans
        // sigaction() on every signal looking for sa_handler == SIG_DFL to reserve one
        // for its GC stop-the-world mechanism.  If none is SIG_DFL it prints:
        //   "Could not find an available signal"
        // and calls abort().  Resetting here (child only) gives box64 and Unity a clean
        // signal table; box64 will install its own handlers as it needs them.
        {
            struct sigaction sa_dfl;
            memset(&sa_dfl, 0, sizeof(sa_dfl));
            sa_dfl.sa_handler = SIG_DFL;
            sigemptyset(&sa_dfl.sa_mask);
            for (int sig = 1; sig < NSIG; sig++) {
                if (sig == SIGKILL || sig == SIGSTOP) continue;
                sigaction(sig, &sa_dfl, NULL);
            }
            LOGI("Child: all signal handlers reset to SIG_DFL");
        }

        // GL4ES: EGL was initialised in parent before fork().  In the child we
        // just rebind the inherited context to this thread via eglMakeCurrent().
        // Fresh EGL init in the child is impossible: libbinder refuses to
        // operate after fork ("ProcessState can not be used after fork").
        if (g_rimdroid_renderer == RD_GL4ES) {
            if (g_egl_display && g_egl_surface && g_egl_context) {
                // Routed like every other bind: the translator's context records were built in
                // the parent and copied by fork(), but nothing is CURRENT in the child until its
                // own make-current runs -- go around it and the child renders on the shared
                // fallback record, which is the whole bug this routing exists to fix.
                if (!rd_eglMakeCurrent(g_egl_display, g_egl_surface,
                                       g_egl_surface, g_egl_context)) {
                    LOGE("Child: eglMakeCurrent failed: 0x%x — GL may crash",
                         eglGetError());
                } else {
                    LOGI("Child: EGL context rebound to child thread — GL4ES ready");
                }

                // DIAGNOSTIC: does GL actually work in the forked child?  If the
                // GPU driver's per-process (binder) state didn't survive fork(),
                // glGetString() returns NULL/garbage — which is exactly what would
                // feed NULL names into Unity's GL loader.  This one test settles
                // whether the blocker is the fork+EGL problem.
                {
                    const char* gl4es_path = getenv("BOX64_LIBGL");
                    void* h = gl4es_path ? dlopen(gl4es_path, RTLD_LAZY | RTLD_GLOBAL) : NULL;
                    const unsigned char* (*p_glGetString)(unsigned int) =
                        h ? (const unsigned char*(*)(unsigned int))dlsym(h, "glGetString") : NULL;
                    unsigned int (*p_glGetError)(void) =
                        h ? (unsigned int(*)(void))dlsym(h, "glGetError") : NULL;
                    if (p_glGetString) {
                        const unsigned char* ver = p_glGetString(0x1F02); // GL_VERSION
                        const unsigned char* ren = p_glGetString(0x1F01); // GL_RENDERER
                        const unsigned char* ven = p_glGetString(0x1F00); // GL_VENDOR
                        LOGI("Child GL test: VERSION='%s' RENDERER='%s' VENDOR='%s' glErr=0x%x",
                             ver ? (const char*)ver : "(null)",
                             ren ? (const char*)ren : "(null)",
                             ven ? (const char*)ven : "(null)",
                             p_glGetError ? p_glGetError() : 0xDEAD);
                    } else {
                        LOGE("Child GL test: glGetString not resolvable (h=%p)", h);
                    }
                }
            } else {
                LOGE("Child: EGL not initialised in parent (display=%p surface=%p ctx=%p)",
                     g_egl_display, g_egl_surface, g_egl_context);
            }
        }

        // ZINK_ZFA: ZFA context created in parent; rebind to this child thread.
        // (If the Vulkan swapchain doesn't survive fork, zfaMakeCurrent fails here
        // — that tells us whether ZFA needs a different fork strategy.)
        if (g_rimdroid_renderer == RD_ZINK_ZFA) {
            // ZFA context was created in the parent (binder needs the parent).
            // Rebind it to this child thread.  NOTE: this is GPU-fork-unsafe and
            // crashes on the first real texture upload — see the catch-22 note at
            // the parent-side init above.  Creating the context in the child
            // instead fails on "libbinder ProcessState can not be used after fork".
            if (g_zfa_context) {
                if (rimdroid_zfa_make_current()) {
                    LOGI("Child: ZFA context rebound to child thread — Zink ready");
                } else {
                    LOGE("Child: ZFA rebind failed — GL may crash");
                }
            } else {
                LOGE("Child: ZFA not initialised in parent");
            }
        }

        launch_rimworld_elf(game_dir_path, argc, argv);
        LOGI("Child: launch_rimworld_elf returned");
        _exit(0);

    } else if (child_pid > 0) {
        // ---- Parent process ----
        LOGI("Game launched in child process (pid=%d), waiting...", (int)child_pid);
        int status = 0;
        waitpid(child_pid, &status, 0);
        if (WIFEXITED(status)) {
            LOGI("Game process (pid=%d) exited normally, code=%d",
                 (int)child_pid, WEXITSTATUS(status));
        } else if (WIFSIGNALED(status)) {
            LOGI("Game process (pid=%d) killed by signal %d",
                 (int)child_pid, WTERMSIG(status));
        } else {
            LOGI("Game process (pid=%d) ended, status=0x%x", (int)child_pid, status);
        }

    } else {
        // fork() failed — fall back to running in-process (old behavior).
        LOGE("fork() failed: %s — running box64 in-process (JVM conflict likely)",
             strerror(errno));
        launch_rimworld_elf(game_dir_path, argc, argv);
    }

    LOGI("rimdroid_start_game: done");
}

// ---------------------------------------------------------------------------
// rimdroid_run_standalone — NO-FORK entry for the standalone exec'd binary.
//
// Architecture (see memory/renderer_and_sigaction.md): RimWorld is a monolithic
// non-PIE x86_64 EXEC pinned at 0x021a9000, which collides with the Android app
// process's ART heap (0x02000000-0x12000000).  The current JNI path forks a
// child + munmaps the heap to free that address — but the fork breaks the GPU
// driver / binder state, so GPU rendering crashes on the first texture upload.
//
// This entry is meant to run in a FRESH exec'd process (launched via exec from
// the Java launcher), which has a CLEAN address space (no ART heap) and a FRESH
// binder ProcessState.  So box64 can load RimWorldLinux at 0x021a9000 with NO
// fork, and the GPU context (added later) is created+used in this one
// never-forked process → fork-safe.  Milestone 1: validate that box64 runs
// RimWorld here and reaches renderer detection without fork.  Surface/renderer
// (headless AHardwareBuffer render + present to the app process) come next.
//
// argv here are EXTRA args passed to RimWorldLinux (e.g. -force-gfx-direct).
__attribute__((visibility("default"), used))
int rimdroid_run_standalone(const char* game_dir_path,
                            const char* library_dir_path,
                            int argc,
                            const char** argv) {
    signal(SIGABRT, handle_abort);
    atexit(rimdroid_atexit_handler);

    snprintf(g_log_file_path, sizeof(g_log_file_path), "%s/rimdroid_game.log", game_dir_path);
    g_rimdroid_log_file = fopen(g_log_file_path, "w");
    if (g_rimdroid_log_file) {
        setvbuf(g_rimdroid_log_file, NULL, _IONBF, 0);
        fprintf(g_rimdroid_log_file, "=== RimDroid STANDALONE (pid=%d, no-fork) ===\n", (int)getpid());
        // Redirect stdout/stderr → log file so box64 printf output is captured
        // (unbuffered so the true last line survives a crash).
        int log_fd = fileno(g_rimdroid_log_file);
        dup2(log_fd, STDOUT_FILENO);
        dup2(log_fd, STDERR_FILENO);
        setvbuf(stdout, NULL, _IONBF, 0);
        setvbuf(stderr, NULL, _IONBF, 0);
    }
    LOGI("rimdroid_run_standalone: game=%s libs=%s (pid=%d, NO fork)",
         game_dir_path, library_dir_path, (int)getpid());

    pthread_t logging_thread;
    if (pthread_create(&logging_thread, NULL,
                       (void *(*)(void *))&monitor_stdio_and_memory, NULL) == 0) {
        pthread_detach(logging_thread);
    }

    // A bare exec'd process (unlike a normal Android app process) has NO binder
    // thread pool — the framework starts one at app startup.  GPU init
    // (Vulkan/Turnip via gralloc/SurfaceFlinger) makes synchronous binder calls
    // that need a thread reading the binder driver to receive replies/callbacks;
    // without the pool those calls HANG (confirmed: zfaCreateContext froze).
    // ABinderProcess_* exist in the runtime libbinder_ndk.so but the NDK ships no
    // import stub for them, so resolve them via dlsym.
    {
        void* binder_ndk = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_GLOBAL);
        if (binder_ndk) {
            bool (*set_max)(uint32_t) =
                (bool(*)(uint32_t))dlsym(binder_ndk, "ABinderProcess_setThreadPoolMaxThreadCount");
            void (*start_pool)(void) =
                (void(*)(void))dlsym(binder_ndk, "ABinderProcess_startThreadPool");
            if (set_max) set_max(4);
            if (start_pool) { start_pool(); LOGI("standalone: binder thread pool started"); }
            else LOGE("standalone: ABinderProcess_startThreadPool not found in libbinder_ndk.so");
        } else {
            LOGE("standalone: dlopen libbinder_ndk.so failed: %s", dlerror());
        }
    }

    // Reads RIMDROID_RENDERER + RIMDROID_VULKAN_DRIVER_NAME into globals (the JNI
    // path does this via initRimDroidWindow before startGame; the exec'd process
    // must do it itself, and BEFORE load_linker_hook which uses the driver name).
    rimdroid_init();

    if (init_rimdroid_namespace(library_dir_path) != 0) {
        LOGE("standalone: namespace init failed");
        return 1;
    }
    if (load_linker_hook() != 0) {
        LOGE("standalone: linker hook failed");
        return 1;
    }
    if (chdir(game_dir_path) != 0) {
        LOGE("standalone: chdir(%s) failed: %s", game_dir_path, strerror(errno));
        return 1;
    }

    struct sigaction sa = { 0 };
    for (int sig = SIGHUP; sig < NSIG; sig++) {
        if (sig == SIGABRT) continue;
        sa.sa_handler = SIG_DFL;
        sigaction(sig, &sa, NULL);
    }
    // During our native renderer init, route SIGSEGV/SIGILL/SIGBUS to the logging
    // fatal handler so a crash prints addr/RIP instead of (with SIG_IGN) looping
    // forever and appearing to "hang".  SIGSEGV is switched to SIG_IGN only later,
    // right before launching box64 (Mono/box64 use SIGSEGV legitimately).
    struct sigaction sa_fatal;
    memset(&sa_fatal, 0, sizeof(sa_fatal));
    sa_fatal.sa_sigaction = handle_fatal_signal;
    sa_fatal.sa_flags = SA_SIGINFO;
    sigaction(SIGILL, &sa_fatal, NULL);
    sigaction(SIGBUS, &sa_fatal, NULL);
    sigaction(SIGSEGV, &sa_fatal, NULL);

    // Milestone 1c — give the renderer a real render target WITHOUT the Activity's
    // Surface: AImageReader provides an ANativeWindow (a BufferQueue producer) that
    // libzfa/Zink can render to (windowed path, which it supports).  The exec'd
    // process has a FRESH binder ProcessState (no fork), so context creation should
    // succeed (no "ProcessState can not be used after fork"), and since there is NO
    // fork the GPU context is created+used in this one process → texture upload
    // should no longer crash.  (Milestone 2 will pull frames from the ImageReader
    // as AHardwareBuffers and present them on the app's SurfaceView.)
    if (g_rimdroid_renderer == RD_GL4ES || g_rimdroid_renderer == RD_ZINK_ZFA ||
        g_rimdroid_renderer == RD_ZINK_OSMESA) {
        const int rw = 2340, rh = 1080;   // native (standalone/AImageReader path, currently unused)
        AImageReader* reader = NULL;
        uint64_t usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                         AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        media_status_t st = AImageReader_newWithUsage(
            rw, rh, AIMAGE_FORMAT_RGBA_8888, usage, 4, &reader);
        if (st != AMEDIA_OK || !reader) {
            LOGE("standalone: AImageReader_newWithUsage failed: %d", (int)st);
        } else {
            ANativeWindow* win = NULL;
            if (AImageReader_getWindow(reader, &win) == AMEDIA_OK && win) {
                LOGI("standalone: AImageReader window=%p (%dx%d) — creating renderer context in-process (no fork)",
                     (void*)win, rw, rh);
                rimdroid_surface_init(win, rw, rh);
                int rc = (g_rimdroid_renderer == RD_GL4ES)
                    ? rimdroid_init_gl4es_egl(win)
                    : rimdroid_init_zfa(win);
                if (rc != 0)
                    LOGE("standalone: renderer init FAILED (rc=%d)", rc);
                else
                    LOGI("standalone: renderer context CREATED in-process (no fork) — GPU ready");
            } else {
                LOGE("standalone: AImageReader_getWindow failed");
            }
        }
    }

    // Renderer init done — restore SIGSEGV=SIG_IGN for box64/Mono (they use
    // SIGSEGV legitimately during emulation; box64 installs its own handler too).
    {
        struct sigaction sa_ign = { 0 };
        sa_ign.sa_handler = SIG_IGN;
        sigaction(SIGSEGV, &sa_ign, NULL);
    }

    LOGI("standalone: launching RimWorld in-process (no fork)...");
    launch_rimworld_elf(game_dir_path, argc, argv);
    LOGI("standalone: launch_rimworld_elf returned");
    return 0;
}

int rimdroid_init() {
    const char* renderer_name = getenv("RIMDROID_RENDERER");

    if (renderer_name == NULL || strcmp(renderer_name, "GL4ES") == 0) {
        g_rimdroid_renderer = RD_GL4ES;
    } else if (strcmp(renderer_name, "ZINK_ZFA") == 0) {
        g_rimdroid_renderer = RD_ZINK_ZFA;
    } else if (strcmp(renderer_name, "ZINK_OSMESA") == 0) {
        g_rimdroid_renderer = RD_ZINK_OSMESA;
    } else if (strcmp(renderer_name, "SOFTPIPE") == 0) {
        g_rimdroid_renderer = RD_SOFTPIPE;
    } else {
        LOGE("Unrecognized renderer: %s", renderer_name);
        g_rimdroid_renderer = RD_GL4ES;
    }

    // Empty / unset => use the phone's SYSTEM Vulkan driver (no bundled Turnip ICD):
    // load_linker_hook() then skips the custom-driver injection and the loader resolves
    // the device's default ICD. A non-empty value names a bundled driver to inject.
    {
        const char* vk = getenv("RIMDROID_VULKAN_DRIVER_NAME");
        g_rimdroid_vulkan_driver_name = (vk && vk[0]) ? vk : NULL;
    }
    LOGI("rimdroid_init: renderer=%s vulkan_driver=%s", renderer_name ? renderer_name : "GL4ES",
         g_rimdroid_vulkan_driver_name ? g_rimdroid_vulkan_driver_name : "(system)");
    return 0;
}

void rimdroid_deinit() {
    rimdroid_surface_deinit();
}

void rimdroid_surface_init(ANativeWindow* wnd, int width, int height) {
    pthread_mutex_lock(&g_rimdroid_surface.mutex);
    // Release the previously acquired window before replacing it — repeated surfaceChanged
    // leaked one ANativeWindow reference per call (kept the old BufferQueue pinned).
    if (g_rimdroid_surface.native_window && g_rimdroid_surface.native_window != wnd)
        ANativeWindow_release(g_rimdroid_surface.native_window);
    g_rimdroid_surface.native_window = wnd;
    g_rimdroid_surface.width  = width;
    g_rimdroid_surface.height = height;
    g_rimdroid_surface.is_dirty = true;
    pthread_mutex_unlock(&g_rimdroid_surface.mutex);
    LOGI("Surface init: %dx%d", width, height);
}

void rimdroid_surface_deinit() {
    pthread_mutex_lock(&g_rimdroid_surface.mutex);
    if (g_rimdroid_surface.is_used) {
        pthread_cond_wait(&g_rimdroid_surface.ready_for_destroy_cond,
                          &g_rimdroid_surface.mutex);
    }
    if (g_rimdroid_surface.native_window) {
        ANativeWindow_release(g_rimdroid_surface.native_window);
        g_rimdroid_surface.native_window = NULL;
    }
    pthread_mutex_unlock(&g_rimdroid_surface.mutex);
}
