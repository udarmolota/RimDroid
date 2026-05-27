#include <dlfcn.h>
#include <android/dlext.h>
#include <pthread.h>
#include <string.h>
#include <unistd.h>
#include <wait.h>
#include <errno.h>
#include <malloc.h>
#include <stdlib.h>
#include <signal.h>
#include <sys/sysinfo.h>
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

RimDroidSurface g_rimdroid_surface = {
    .mutex = PTHREAD_MUTEX_INITIALIZER,
    .ready_for_destroy_cond = PTHREAD_COND_INITIALIZER
};

// Лог-файл — extern в logger.h, используется макросами LOGI/LOGW/LOGE
FILE* g_rimdroid_log_file = NULL;

static char g_log_file_path[1024] = {0};

// Force rebuild marker
static const char build_marker[] = "RIMDROID_BUILD_2026_05_27_FORCED_REBUILD_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";

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

// ---- Abort handler ----------------------------------------------------------

static void handle_abort(int sig) {
    LOGE("SIGABRT received");
    signal(SIGABRT, SIG_DFL);
    raise(SIGABRT);
}

// ---- Namespace init ---------------------------------------------------------

static int init_rimdroid_namespace(const char* ld_library_path) {
    if (!linkernsbypass_load_status()) {
        LOGE("linkernsbypass is not loaded");
        return -1;
    }

    rimdroid_ns = android_create_namespace(
        "rimdroid-ns",
        ld_library_path,
        ld_library_path,
        ANDROID_NAMESPACE_TYPE_SHARED_ISOLATED,
        "/system/lib64:/system/lib",
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

    const char** full_argv = malloc((argc + 1) * sizeof(char*));
    full_argv[0] = binary_path;
    for (int i = 0; i < argc; i++) full_argv[i + 1] = argv[i];

    LOGI("Executing: %s", binary_path);
    run_elf_file(binary_path, argc + 1, full_argv);
    free(full_argv);
}

// ---- Public API -------------------------------------------------------------

void rimdroid_start_game(const char* game_dir_path,
                         const char* library_dir_path,
                         int argc,
                         const char** argv) {

    signal(SIGABRT, handle_abort);

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

    // Явно устанавливаем BOX64_LD_LIBRARY_PATH ДО инициализации box64
    setenv("BOX64_LD_LIBRARY_PATH", library_dir_path, 1);
    LOGI("BOX64_LD_LIBRARY_PATH forced to: %s", library_dir_path);

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

    LOGI("Starting RimWorldLinux via box64...");
    launch_rimworld_elf(game_dir_path, argc, argv);
    LOGI("rimdroid_start_game: returned from launch_rimworld_elf");
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
