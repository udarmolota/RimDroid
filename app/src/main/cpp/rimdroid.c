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
void* g_egl_context = NULL;   // EGLContext

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
static PFN_zfaCreateContext  p_zfaCreateContext  = NULL;
static PFN_zfaMakeCurrent    p_zfaMakeCurrent    = NULL;
static PFN_zfaFlushFront     p_zfaFlushFront     = NULL;
static PFN_zfaDestroyContext p_zfaDestroyContext = NULL;

RimDroidSurface g_rimdroid_surface = {
    .mutex = PTHREAD_MUTEX_INITIALIZER,
    .ready_for_destroy_cond = PTHREAD_COND_INITIALIZER
};

// Лог-файл — extern в logger.h, используется макросами LOGI/LOGW/LOGE
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
    const int RD_RENDER_W = 1024, RD_RENDER_H = 768;
    int ww = w ? RD_RENDER_W : 1;
    int hh = w ? RD_RENDER_H : 1;
    if (w) ANativeWindow_setBuffersGeometry(w, RD_RENDER_W, RD_RENDER_H, 0 /* keep format */);
    if (!p_zfaMakeCurrent(g_zfa_context, w, ww, hh)) {
        LOGE("ZFA: zfaMakeCurrent failed");
        return 0;
    }
    // Vulkan applies the device-orientation transform by default; force identity.
    // Resolved via dlsym (ANativeWindow_setBuffersTransform is API 26+, not in the
    // link-time stub at this project's minSdk).
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
    return 1;
}

void rimdroid_zfa_swap(void) {
    if (p_zfaFlushFront) p_zfaFlushFront();
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
    if (!p_zfaCreateContext || !p_zfaMakeCurrent || !p_zfaFlushFront) {
        LOGE("ZFA: missing entry points (create=%p makecur=%p flush=%p)",
             (void*)p_zfaCreateContext, (void*)p_zfaMakeCurrent, (void*)p_zfaFlushFront);
        return -1;
    }
    // depth=24, stencil=8, compat=0 (CORE profile), GL 4.3 (matches
    // MESA_GL_VERSION_OVERRIDE; satisfies Unity's "OpenGL core 3.2+" check).
    LOGI("ZFA: calling zfaCreateContext(24,8,0,4,3)...");
    g_zfa_context = p_zfaCreateContext(24, 8, 0, 4, 3);
    LOGI("ZFA: zfaCreateContext returned %p", g_zfa_context);
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
    g_egl_context = eglCreateContext(g_egl_display, config, EGL_NO_CONTEXT, ctx3);
    if (g_egl_context == EGL_NO_CONTEXT) {
        LOGW("EGL: GLES3 context failed (0x%x), trying GLES2", eglGetError());
        const EGLint ctx2[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
        g_egl_context = eglCreateContext(g_egl_display, config, EGL_NO_CONTEXT, ctx2);
    }
    if (g_egl_context == EGL_NO_CONTEXT) {
        LOGE("EGL: eglCreateContext failed: 0x%x", eglGetError());
        return -1;
    }

    // Make current on this thread so GL4ES can query capabilities immediately.
    if (!eglMakeCurrent(g_egl_display, g_egl_surface, g_egl_surface, g_egl_context)) {
        LOGE("EGL: eglMakeCurrent failed: 0x%x", eglGetError());
        return -1;
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
                    LOGE("GL4ES: set_getprocaddress symbol not found in %s", gl4es_path);
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
                // Пишем в logcat и в файл через LOGI
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
            LOGE("Failed to load libvulkan.so");
            return -1;
        }
        rimdroid_linker_set_vulkan_loader_handle(vulkan_loader);

        void* vulkan_driver = linkernsbypass_namespace_dlopen(
            g_rimdroid_vulkan_driver_name, RTLD_LOCAL, rimdroid_ns);
        if (!vulkan_driver) {
            LOGE("Failed to load vulkan driver: %s", g_rimdroid_vulkan_driver_name);
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
    snprintf(binary_path, sizeof(binary_path), "%s/RimWorldLinux", game_dir_path);

    // RIMDROID: force WINDOWED at native size. The infinite SDL_GL_DeleteContext
    // teardown loop is triggered by Unity switching to fullscreen on the bogus
    // dummy-SDL mode "1024x768 @ 0 Hz". Starting windowed should avoid that mode-set.
    static const char* extra_argv[] = {
        "-screen-fullscreen", "0",
        "-screen-width",  "1024",   // match dummy-SDL display (1024x768) so the
        "-screen-height", "768",    // whole pipeline is one consistent size
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

    // Открываем лог-файл ПЕРВЫМ ДЕЛОМ — до всего остального
    snprintf(g_log_file_path, sizeof(g_log_file_path), "%s/rimdroid.log", game_dir_path);
    g_rimdroid_log_file = fopen(g_log_file_path, "w");
    if (g_rimdroid_log_file) {
        setvbuf(g_rimdroid_log_file, NULL, _IOLBF, 0);
        fprintf(g_rimdroid_log_file, "=== RimDroid log started ===\n");
        fflush(g_rimdroid_log_file);
    }

    // Теперь все LOGI/LOGE пишут и в logcat и в файл
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
    if (g_rimdroid_renderer == RD_GL4ES || g_rimdroid_renderer == RD_ZINK_ZFA) {
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
            setenv("GC_INITIAL_HEAP_SIZE", "1073741824", 1);   // 1 GiB
            setenv("GC_FREE_SPACE_DIVISOR", "1", 1);
            LOGI("Boehm GC: initial heap 1GiB, free_space_divisor=1 (fewer STW GCs)");
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
                if (!eglMakeCurrent(g_egl_display, g_egl_surface,
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
    FILE* f = fopen("/data/data/com.rimdroid/files/init_called.txt", "w");
    if (f) { fprintf(f, "rimdroid_init called\n"); fclose(f); }
    const char* renderer_name = getenv("RIMDROID_RENDERER");

    if (renderer_name == NULL || strcmp(renderer_name, "GL4ES") == 0) {
        g_rimdroid_renderer = RD_GL4ES;
    } else if (strcmp(renderer_name, "ZINK_ZFA") == 0) {
        g_rimdroid_renderer = RD_ZINK_ZFA;
    } else if (strcmp(renderer_name, "ZINK_OSMESA") == 0) {
        g_rimdroid_renderer = RD_ZINK_OSMESA;
    } else {
        LOGE("Unrecognized renderer: %s", renderer_name);
        g_rimdroid_renderer = RD_GL4ES;
    }

    g_rimdroid_vulkan_driver_name = getenv("RIMDROID_VULKAN_DRIVER_NAME");
    LOGI("rimdroid_init: renderer=%s", renderer_name ? renderer_name : "GL4ES");
    return 0;
}

void rimdroid_deinit() {
    rimdroid_surface_deinit();
}

void rimdroid_surface_init(ANativeWindow* wnd, int width, int height) {
    pthread_mutex_lock(&g_rimdroid_surface.mutex);
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
