// libglfw.so for Android — RimWorld 1.7 (Waylith on .NET) asks GLFW 3.4 for its window, input and
// Vulkan surface. This is that API over ClrActivity's SurfaceView: one window = the Android surface,
// one monitor = the screen, Vulkan surfaces through VK_KHR_android_surface, and touch/keys queued from
// Java and handed out in glfwPollEvents on the game's thread. Every one of the 149 functions the
// engine imports exists, so nothing fails with "entry point not found"; what a phone has no use for
// (gamma, joystick, other platforms' native handles, OpenGL contexts) answers with an empty value.
// The engine is Vulkan-only, so no GL/EGL.
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define TAG "RimDroid/glfw"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define API __attribute__((visibility("default")))

// ---- GLFW constants used here (glfw3.h 3.4)
#define GLFW_TRUE 1
#define GLFW_FALSE 0
#define GLFW_RELEASE 0
#define GLFW_PRESS 1
#define GLFW_PLATFORM_NULL 0x00060005
#define GLFW_FOCUSED 0x00020001
#define GLFW_VISIBLE 0x00020004
#define GLFW_HOVERED 0x0002000B
#define GLFW_CLIENT_API 0x00022001
#define GLFW_NO_API 0
#define GLFW_CURSOR 0x00033001
#define GLFW_CURSOR_NORMAL 0x00034001
#define GLFW_KEY_LAST 348
#define GLFW_MOUSE_BUTTON_LAST 7

typedef struct { int width, height, redBits, greenBits, blueBits, refreshRate; } GLFWvidmode;
typedef void (*GLFWerrorfun)(int, const char*);
typedef void (*GLFWwindowsizefun)(void*, int, int);
typedef void (*GLFWcursorposfun)(void*, double, double);
typedef void (*GLFWmousebuttonfun)(void*, int, int, int);
typedef void (*GLFWscrollfun)(void*, double, double);
typedef void (*GLFWkeyfun)(void*, int, int, int, int);
typedef void (*GLFWcharfun)(void*, unsigned int);
typedef void (*GLFWfocusfun)(void*, int);
typedef void (*GLFWclosefun)(void*);
typedef void* (*PFN_getproc)(void* instance, const char* name);

typedef struct {
    int width, height;
    int shouldClose;
    void* user;
    char title[256];
    int inputModes[8];
    unsigned char keys[GLFW_KEY_LAST + 1];
    unsigned char buttons[GLFW_MOUSE_BUTTON_LAST + 1];
    double cursorX, cursorY;
    GLFWwindowsizefun sizeCb, fbSizeCb;
    GLFWcursorposfun cursorCb;
    GLFWmousebuttonfun buttonCb;
    GLFWscrollfun scrollCb;
    GLFWkeyfun keyCb;
    GLFWcharfun charCb;
    GLFWfocusfun focusCb;
    GLFWclosefun closeCb;
    void* otherCb[12];
} Window;

typedef struct { int dummy; void* user; } Monitor;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
// glfwSetCursorPos -> Java (see there). Its own lock, so a call from inside an input callback can't
// meet the event queue's.
static pthread_mutex_t g_warp_lock = PTHREAD_MUTEX_INITIALIZER;
static double g_warpX, g_warpY;
static int g_warpPending;
static pthread_cond_t g_surface_cond = PTHREAD_COND_INITIALIZER;
static ANativeWindow* g_native;          // the SurfaceView's surface, set from Java
// The Android surface goes away in the background and the engine's VkSurfaceKHR dies with it. The
// engine recovers by itself: on VK_ERROR_SURFACE_LOST it asks for a new surface once the window has a
// size again. libvulkan.so tells it so through these two (vulkan_wrapper.c).
static int g_surfaceGen;                 // counts lost surfaces
static int g_vkSurfaceGen;               // g_surfaceGen when the engine's VkSurfaceKHR was made
static Window* g_window;                 // the one window
static Monitor g_monitor;
static Monitor* g_monitors[1] = { &g_monitor };
// The display as the game sees it: set from Java (glfwDisplayMode) before the game starts. RimWorld
// caps its frame rate at this refresh rate (Root.CheckGlobalInit: targetFrameRate =
// Screen.currentResolution.refreshRate), so it must be the real one, not a guess.
static GLFWvidmode g_mode = { 1920, 1080, 8, 8, 8, 60 };
static GLFWerrorfun g_errorCb;
static PFN_getproc g_vkGetInstanceProcAddr;
static double g_timeBase;
static char g_clipboard[4096];

// ---- event queue (Java → game thread)
enum { EV_TOUCH = 1, EV_KEY = 2, EV_CHAR = 3, EV_SCROLL = 4, EV_RESIZE = 5, EV_CLOSE = 6, EV_FOCUS = 7, EV_BUTTON = 8 };
typedef struct { int type, a, b, c; double x, y; } Event;
#define QSIZE 1024
static Event g_queue[QSIZE];
static int g_qhead, g_qtail;

static void push(Event e) {
    pthread_mutex_lock(&g_lock);
    int next = (g_qtail + 1) % QSIZE;
    if (next != g_qhead) { g_queue[g_qtail] = e; g_qtail = next; }
    pthread_mutex_unlock(&g_lock);
}

static double now_seconds(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + ts.tv_nsec / 1e9;
}

// ===================================================================== JNI (ClrActivity)

// The last glfwSetCursorPos since the previous call, in the game's pixels, into out[0..1]; false if none.
JNIEXPORT jboolean JNICALL Java_com_rimdroid_ClrActivity_glfwTakeCursorWarp(JNIEnv* env, jclass c, jfloatArray out) {
    (void)c;
    float xy[2];
    int got = 0;
    pthread_mutex_lock(&g_warp_lock);
    if (g_warpPending) { xy[0] = (float)g_warpX; xy[1] = (float)g_warpY; g_warpPending = 0; got = 1; }
    pthread_mutex_unlock(&g_warp_lock);
    if (got) (*env)->SetFloatArrayRegion(env, out, 0, 2, xy);
    return got ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_rimdroid_ClrActivity_glfwSurface(JNIEnv* env, jclass c, jobject surface, jint w, jint h) {
    (void)c;
    pthread_mutex_lock(&g_lock);
    int had = g_native != NULL;
    if (g_native) { ANativeWindow_release(g_native); g_native = NULL; }
    if (surface) g_native = ANativeWindow_fromSurface(env, surface);
    if (had && !g_native) g_surfaceGen++;
    if (w > 0 && h > 0) { g_mode.width = w; g_mode.height = h; }
    pthread_cond_broadcast(&g_surface_cond);
    pthread_mutex_unlock(&g_lock);
    LOGI("surface %s %dx%d", surface ? "set" : "cleared", w, h);
    if (surface && g_window) push((Event){ .type = EV_RESIZE, .a = w, .b = h });
    // Out of focus the engine idles and pauses its audio; at 0x0 it stops rendering (see below).
    if (g_window && had != (surface != NULL)) push((Event){ .type = EV_FOCUS, .a = surface != NULL });
}

JNIEXPORT void JNICALL Java_com_rimdroid_ClrActivity_glfwTouch(JNIEnv* env, jclass c, jint action, jfloat x, jfloat y) {
    (void)env; (void)c;
    push((Event){ .type = EV_TOUCH, .a = action, .x = x, .y = y });
}

JNIEXPORT void JNICALL Java_com_rimdroid_ClrActivity_glfwKey(JNIEnv* env, jclass c, jint key, jint pressed, jint codepoint) {
    (void)env; (void)c;
    push((Event){ .type = EV_KEY, .a = key, .b = pressed });
    if (pressed && codepoint > 0) push((Event){ .type = EV_CHAR, .a = codepoint });
}

JNIEXPORT void JNICALL Java_com_rimdroid_ClrActivity_glfwDisplayMode(JNIEnv* env, jclass c, jint w, jint h, jint refresh) {
    (void)env; (void)c;
    if (w > 0 && h > 0) { g_mode.width = w; g_mode.height = h; }
    if (refresh > 0) g_mode.refreshRate = refresh;
    LOGI("display mode %dx%d @ %d Hz", g_mode.width, g_mode.height, g_mode.refreshRate);
}

// Presented frames, for the FPS overlay: counted by the libvulkan.so wrapper at each vkQueuePresentKHR.
static _Atomic unsigned long long g_frames;
API void rd_frame_tick(void) { g_frames++; }

// 1 and the window's size while there is an Android surface.
API int rd_surface_size(int* w, int* h) {
    pthread_mutex_lock(&g_lock);
    int have = g_native != NULL;
    if (have) { *w = ANativeWindow_getWidth(g_native); *h = ANativeWindow_getHeight(g_native); }
    pthread_mutex_unlock(&g_lock);
    return have;
}

API int rd_surface_stale(void) {
    pthread_mutex_lock(&g_lock);
    int stale = g_vkSurfaceGen != g_surfaceGen;
    pthread_mutex_unlock(&g_lock);
    return stale;
}
JNIEXPORT jlong JNICALL Java_com_rimdroid_ClrActivity_frameCount(JNIEnv* env, jclass c) {
    (void)env; (void)c;
    return (jlong)g_frames;
}

// A mouse button (0 left, 1 right, 2 middle) at a position: the on-screen controls and the touches.
JNIEXPORT void JNICALL Java_com_rimdroid_ClrActivity_glfwMouse(JNIEnv* env, jclass c, jint button, jint down, jfloat x, jfloat y) {
    (void)env; (void)c;
    push((Event){ .type = EV_BUTTON, .a = button, .b = down, .x = x, .y = y });
}

// Typed text, one Unicode code point (any script: the game's ImGui takes UTF-32).
JNIEXPORT void JNICALL Java_com_rimdroid_ClrActivity_glfwChar(JNIEnv* env, jclass c, jint codepoint) {
    (void)env; (void)c;
    if (codepoint > 0) push((Event){ .type = EV_CHAR, .a = codepoint });
}

JNIEXPORT void JNICALL Java_com_rimdroid_ClrActivity_glfwScroll(JNIEnv* env, jclass c, jfloat dx, jfloat dy) {
    (void)env; (void)c;
    push((Event){ .type = EV_SCROLL, .x = dx, .y = dy });
}

JNIEXPORT void JNICALL Java_com_rimdroid_ClrActivity_glfwClose(JNIEnv* env, jclass c) {
    (void)env; (void)c;
    push((Event){ .type = EV_CLOSE });
}

// ===================================================================== init, time, errors

API int glfwInit(void) { g_timeBase = now_seconds(); LOGI("glfwInit"); return GLFW_TRUE; }
API void glfwTerminate(void) {}
API void glfwInitHint(int hint, int value) { (void)hint; (void)value; }
API void glfwInitAllocator(const void* a) { (void)a; }
API void glfwInitVulkanLoader(void* loader) { g_vkGetInstanceProcAddr = (PFN_getproc)loader; }
API void glfwGetVersion(int* maj, int* min, int* rev) { if (maj) *maj = 3; if (min) *min = 4; if (rev) *rev = 0; }
API const char* glfwGetVersionString(void) { return "3.4.0 RimDroid Android"; }
API int glfwGetError(const char** desc) { if (desc) *desc = NULL; return 0; }
API void* glfwSetErrorCallback(void* cb) { void* old = (void*)g_errorCb; g_errorCb = (GLFWerrorfun)cb; return old; }
API int glfwGetPlatform(void) { return GLFW_PLATFORM_NULL; }
API int glfwPlatformSupported(int p) { return p == GLFW_PLATFORM_NULL; }
API double glfwGetTime(void) { return now_seconds() - g_timeBase; }
API void glfwSetTime(double t) { g_timeBase = now_seconds() - t; }
API uint64_t glfwGetTimerValue(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}
API uint64_t glfwGetTimerFrequency(void) { return 1000000000ull; }

// ===================================================================== monitors

API void** glfwGetMonitors(int* count) { if (count) *count = 1; return (void**)g_monitors; }
API void* glfwGetPrimaryMonitor(void) { return &g_monitor; }
API void glfwGetMonitorPos(void* m, int* x, int* y) { (void)m; if (x) *x = 0; if (y) *y = 0; }
API void glfwGetMonitorWorkarea(void* m, int* x, int* y, int* w, int* h) {
    (void)m; if (x) *x = 0; if (y) *y = 0; if (w) *w = g_mode.width; if (h) *h = g_mode.height;
}
API void glfwGetMonitorPhysicalSize(void* m, int* w, int* h) { (void)m; if (w) *w = 150; if (h) *h = 70; }
API void glfwGetMonitorContentScale(void* m, float* x, float* y) { (void)m; if (x) *x = 1; if (y) *y = 1; }
API const char* glfwGetMonitorName(void* m) { (void)m; return "Phone screen"; }
API void glfwSetMonitorUserPointer(void* m, void* p) { ((Monitor*)m)->user = p; }
API void* glfwGetMonitorUserPointer(void* m) { return ((Monitor*)m)->user; }
API void* glfwSetMonitorCallback(void* cb) { (void)cb; return NULL; }
API const GLFWvidmode* glfwGetVideoModes(void* m, int* count) { (void)m; if (count) *count = 1; return &g_mode; }
API const GLFWvidmode* glfwGetVideoMode(void* m) { (void)m; return &g_mode; }
API void glfwSetGamma(void* m, float g) { (void)m; (void)g; }
API const void* glfwGetGammaRamp(void* m) { (void)m; return NULL; }
API void glfwSetGammaRamp(void* m, const void* r) { (void)m; (void)r; }

// ===================================================================== windows

API void glfwDefaultWindowHints(void) {}
API void glfwWindowHint(int hint, int value) { (void)hint; (void)value; }
API void glfwWindowHintString(int hint, const char* value) { (void)hint; (void)value; }

API void* glfwCreateWindow(int width, int height, const char* title, void* monitor, void* share) {
    (void)monitor; (void)share;
    pthread_mutex_lock(&g_lock);
    // The surface arrives from the UI thread; wait for it (the activity creates it at start).
    struct timespec until;
    clock_gettime(CLOCK_REALTIME, &until);
    until.tv_sec += 10;
    while (!g_native && pthread_cond_timedwait(&g_surface_cond, &g_lock, &until) == 0) {}
    int haveSurface = g_native != NULL;
    pthread_mutex_unlock(&g_lock);
    if (!haveSurface) LOGW("glfwCreateWindow: no surface after 10 s");
    Window* w = calloc(1, sizeof(Window));
    w->width = haveSurface ? ANativeWindow_getWidth(g_native) : width;
    w->height = haveSurface ? ANativeWindow_getHeight(g_native) : height;
    if (title) strncpy(w->title, title, sizeof(w->title) - 1);
    w->inputModes[0] = GLFW_CURSOR_NORMAL;
    g_window = w;
    LOGI("glfwCreateWindow %dx%d requested, %dx%d surface, \"%s\"", width, height, w->width, w->height, w->title);
    return w;
}

API void glfwDestroyWindow(void* w) { if (w == g_window) g_window = NULL; free(w); }
API int glfwWindowShouldClose(void* w) { return w ? ((Window*)w)->shouldClose : 1; }
API void glfwSetWindowShouldClose(void* w, int v) { if (w) ((Window*)w)->shouldClose = v; }
API const char* glfwGetWindowTitle(void* w) { return w ? ((Window*)w)->title : ""; }
API void glfwSetWindowTitle(void* w, const char* t) { if (w && t) strncpy(((Window*)w)->title, t, 255); }
API void glfwSetWindowIcon(void* w, int n, const void* img) { (void)w; (void)n; (void)img; }
API void glfwGetWindowPos(void* w, int* x, int* y) { (void)w; if (x) *x = 0; if (y) *y = 0; }
API void glfwSetWindowPos(void* w, int x, int y) { (void)w; (void)x; (void)y; }
API void glfwGetWindowSize(void* w, int* x, int* y) { Window* win = w; if (x) *x = win ? win->width : 0; if (y) *y = win ? win->height : 0; }
API void glfwGetFramebufferSize(void* w, int* x, int* y) {
    int sw, sh;
    if (rd_surface_size(&sw, &sh)) glfwGetWindowSize(w, x, y);   // 0x0 = "minimized" to the engine
    else { if (x) *x = 0; if (y) *y = 0; }
}
API void glfwSetWindowSize(void* w, int x, int y) { (void)w; (void)x; (void)y; }
API void glfwSetWindowSizeLimits(void* w, int a, int b, int c, int d) { (void)w; (void)a; (void)b; (void)c; (void)d; }
API void glfwSetWindowAspectRatio(void* w, int n, int d) { (void)w; (void)n; (void)d; }
API void glfwGetWindowFrameSize(void* w, int* l, int* t, int* r, int* b) { (void)w; if (l) *l = 0; if (t) *t = 0; if (r) *r = 0; if (b) *b = 0; }
API void glfwGetWindowContentScale(void* w, float* x, float* y) { (void)w; if (x) *x = 1; if (y) *y = 1; }
API float glfwGetWindowOpacity(void* w) { (void)w; return 1; }
API void glfwSetWindowOpacity(void* w, float o) { (void)w; (void)o; }
API void glfwIconifyWindow(void* w) { (void)w; }
API void glfwRestoreWindow(void* w) { (void)w; }
API void glfwMaximizeWindow(void* w) { (void)w; }
API void glfwShowWindow(void* w) { (void)w; }
API void glfwHideWindow(void* w) { (void)w; }
API void glfwFocusWindow(void* w) { (void)w; }
API void glfwRequestWindowAttention(void* w) { (void)w; }
API void* glfwGetWindowMonitor(void* w) { (void)w; return &g_monitor; }
API void glfwSetWindowMonitor(void* w, void* m, int x, int y, int ww, int hh, int r) { (void)w; (void)m; (void)x; (void)y; (void)ww; (void)hh; (void)r; }
API int glfwGetWindowAttrib(void* w, int attrib) {
    (void)w;
    switch (attrib) {
        case GLFW_FOCUSED: case GLFW_VISIBLE: case GLFW_HOVERED: return GLFW_TRUE;
        case GLFW_CLIENT_API: return GLFW_NO_API;
        default: return 0;
    }
}
API void glfwSetWindowAttrib(void* w, int a, int v) { (void)w; (void)a; (void)v; }
API void glfwSetWindowUserPointer(void* w, void* p) { if (w) ((Window*)w)->user = p; }
API void* glfwGetWindowUserPointer(void* w) { return w ? ((Window*)w)->user : NULL; }

#define SETCB(name, field, type) \
    API void* name(void* w, void* cb) { Window* win = w; if (!win) return NULL; void* old = (void*)win->field; win->field = (type)cb; return old; }
SETCB(glfwSetWindowSizeCallback, sizeCb, GLFWwindowsizefun)
SETCB(glfwSetFramebufferSizeCallback, fbSizeCb, GLFWwindowsizefun)
SETCB(glfwSetCursorPosCallback, cursorCb, GLFWcursorposfun)
SETCB(glfwSetMouseButtonCallback, buttonCb, GLFWmousebuttonfun)
SETCB(glfwSetScrollCallback, scrollCb, GLFWscrollfun)
SETCB(glfwSetKeyCallback, keyCb, GLFWkeyfun)
SETCB(glfwSetCharCallback, charCb, GLFWcharfun)
SETCB(glfwSetWindowFocusCallback, focusCb, GLFWfocusfun)
SETCB(glfwSetWindowCloseCallback, closeCb, GLFWclosefun)
#define SETCB_UNUSED(name, slot) \
    API void* name(void* w, void* cb) { Window* win = w; if (!win) return NULL; void* old = win->otherCb[slot]; win->otherCb[slot] = cb; return old; }
SETCB_UNUSED(glfwSetWindowPosCallback, 0)
SETCB_UNUSED(glfwSetWindowRefreshCallback, 1)
SETCB_UNUSED(glfwSetWindowIconifyCallback, 2)
SETCB_UNUSED(glfwSetWindowMaximizeCallback, 3)
SETCB_UNUSED(glfwSetWindowContentScaleCallback, 4)
SETCB_UNUSED(glfwSetCharModsCallback, 5)
SETCB_UNUSED(glfwSetCursorEnterCallback, 6)
SETCB_UNUSED(glfwSetDropCallback, 7)
API void* glfwSetJoystickCallback(void* cb) { (void)cb; return NULL; }

// ===================================================================== events and input

// GLFW_MOD_* from the modifier keys held (Shift-click, Ctrl-click in the game).
static int mods(const Window* w) {
    int m = 0;
    if (w->keys[340] || w->keys[344]) m |= 0x0001;   // shift
    if (w->keys[341] || w->keys[345]) m |= 0x0002;   // control
    if (w->keys[342] || w->keys[346]) m |= 0x0004;   // alt
    if (w->keys[343] || w->keys[347]) m |= 0x0008;   // super
    return m;
}

static void dispatch(Event* e) {
    Window* w = g_window;
    if (!w) return;
    switch (e->type) {
        case EV_TOUCH:   // 0 down, 1 up, 2 move (left button); 3 down, 4 up (right button)
            w->cursorX = e->x; w->cursorY = e->y;
            if (w->cursorCb) w->cursorCb(w, e->x, e->y);
            if (e->a == 0 || e->a == 1 || e->a == 3 || e->a == 4) {
                int button = e->a >= 3 ? 1 : 0;   // GLFW_MOUSE_BUTTON_LEFT / _RIGHT
                w->buttons[button] = (e->a == 0 || e->a == 3) ? GLFW_PRESS : GLFW_RELEASE;
                if (w->buttonCb) w->buttonCb(w, button, w->buttons[button], mods(w));
            }
            break;
        case EV_BUTTON:
            w->cursorX = e->x; w->cursorY = e->y;
            if (w->cursorCb) w->cursorCb(w, e->x, e->y);
            if (e->a >= 0 && e->a <= GLFW_MOUSE_BUTTON_LAST) {
                w->buttons[e->a] = e->b ? GLFW_PRESS : GLFW_RELEASE;
                if (w->buttonCb) w->buttonCb(w, e->a, w->buttons[e->a], mods(w));
            }
            break;
        case EV_KEY:
            if (e->a >= 0 && e->a <= GLFW_KEY_LAST) w->keys[e->a] = e->b ? GLFW_PRESS : GLFW_RELEASE;
            if (w->keyCb) w->keyCb(w, e->a, e->a, e->b ? GLFW_PRESS : GLFW_RELEASE, mods(w));
            break;
        case EV_CHAR:
            if (w->charCb) w->charCb(w, (unsigned int)e->a);
            break;
        case EV_SCROLL:
            if (w->scrollCb) w->scrollCb(w, e->x, e->y);
            break;
        case EV_FOCUS:
            if (w->focusCb) w->focusCb(w, e->a);
            break;
        case EV_RESIZE:
            w->width = e->a; w->height = e->b;
            if (w->sizeCb) w->sizeCb(w, e->a, e->b);
            if (w->fbSizeCb) w->fbSizeCb(w, e->a, e->b);
            break;
        case EV_CLOSE:
            w->shouldClose = 1;
            if (w->closeCb) w->closeCb(w);
            break;
    }
}

API void glfwPollEvents(void) {
    for (;;) {
        Event e;
        pthread_mutex_lock(&g_lock);
        if (g_qhead == g_qtail) { pthread_mutex_unlock(&g_lock); return; }
        e = g_queue[g_qhead];
        g_qhead = (g_qhead + 1) % QSIZE;
        pthread_mutex_unlock(&g_lock);
        dispatch(&e);
    }
}
API void glfwWaitEvents(void) { glfwPollEvents(); }
API void glfwWaitEventsTimeout(double t) { (void)t; glfwPollEvents(); }
API void glfwPostEmptyEvent(void) {}

API int glfwGetInputMode(void* w, int mode) { Window* win = w; int i = mode - GLFW_CURSOR; return win && i >= 0 && i < 8 ? win->inputModes[i] : 0; }
API void glfwSetInputMode(void* w, int mode, int value) { Window* win = w; int i = mode - GLFW_CURSOR; if (win && i >= 0 && i < 8) win->inputModes[i] = value; }
API int glfwRawMouseMotionSupported(void) { return GLFW_FALSE; }
API const char* glfwGetKeyName(int key, int scancode) { (void)key; (void)scancode; return NULL; }
API int glfwGetKeyScancode(int key) { return key; }
API int glfwGetKey(void* w, int key) { Window* win = w; return win && key >= 0 && key <= GLFW_KEY_LAST ? win->keys[key] : 0; }
API int glfwGetMouseButton(void* w, int b) { Window* win = w; return win && b >= 0 && b <= GLFW_MOUSE_BUTTON_LAST ? win->buttons[b] : 0; }
API void glfwGetCursorPos(void* w, double* x, double* y) { Window* win = w; if (x) *x = win ? win->cursorX : 0; if (y) *y = win ? win->cursorY : 0; }
// A game or mod moving the pointer, as desktop GLFW does: kept until Java takes it on its next frame
// (ClrActivity.glfwTakeCursorWarp) and moves the drawn cursor there.
API void glfwSetCursorPos(void* w, double x, double y) {
    Window* win = w;
    if (!win) return;
    win->cursorX = x; win->cursorY = y;
    pthread_mutex_lock(&g_warp_lock);
    g_warpX = x; g_warpY = y; g_warpPending = 1;
    pthread_mutex_unlock(&g_warp_lock);
}

static int g_cursor_dummy;
API void* glfwCreateCursor(const void* img, int x, int y) { (void)img; (void)x; (void)y; return &g_cursor_dummy; }
API void* glfwCreateStandardCursor(int shape) { (void)shape; return &g_cursor_dummy; }
API void glfwDestroyCursor(void* c) { (void)c; }
API void glfwSetCursor(void* w, void* c) { (void)w; (void)c; }

API void glfwSetClipboardString(void* w, const char* s) { (void)w; if (s) strncpy(g_clipboard, s, sizeof(g_clipboard) - 1); }
API const char* glfwGetClipboardString(void* w) { (void)w; return g_clipboard; }

// Joysticks: none yet (the gamepad comes through Android later).
API int glfwJoystickPresent(int j) { (void)j; return GLFW_FALSE; }
API const float* glfwGetJoystickAxes(int j, int* n) { (void)j; if (n) *n = 0; return NULL; }
API const unsigned char* glfwGetJoystickButtons(int j, int* n) { (void)j; if (n) *n = 0; return NULL; }
API const unsigned char* glfwGetJoystickHats(int j, int* n) { (void)j; if (n) *n = 0; return NULL; }
API const char* glfwGetJoystickName(int j) { (void)j; return NULL; }
API const char* glfwGetJoystickGUID(int j) { (void)j; return NULL; }
API void glfwSetJoystickUserPointer(int j, void* p) { (void)j; (void)p; }
API void* glfwGetJoystickUserPointer(int j) { (void)j; return NULL; }
API int glfwJoystickIsGamepad(int j) { (void)j; return GLFW_FALSE; }
API int glfwUpdateGamepadMappings(const char* s) { (void)s; return GLFW_TRUE; }
API const char* glfwGetGamepadName(int j) { (void)j; return NULL; }
API int glfwGetGamepadState(int j, void* s) { (void)j; (void)s; return GLFW_FALSE; }

// ===================================================================== Vulkan

static PFN_getproc get_proc(void) {
    if (g_vkGetInstanceProcAddr) return g_vkGetInstanceProcAddr;
    void* vk = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);   // the APK's wrapper (vulkan_wrapper.c) when present
    if (vk) g_vkGetInstanceProcAddr = (PFN_getproc)dlsym(vk, "vkGetInstanceProcAddr");
    return g_vkGetInstanceProcAddr;
}

API int glfwVulkanSupported(void) { return get_proc() != NULL; }

static const char* g_extensions[] = { "VK_KHR_surface", "VK_KHR_android_surface" };
API const char** glfwGetRequiredInstanceExtensions(uint32_t* count) { if (count) *count = 2; return g_extensions; }

API void* glfwGetInstanceProcAddress(void* instance, const char* name) {
    PFN_getproc gp = get_proc();
    return gp ? gp(instance, name) : NULL;
}

API int glfwGetPhysicalDevicePresentationSupport(void* instance, void* device, uint32_t family) {
    (void)instance; (void)device; (void)family;
    return GLFW_TRUE;   // Android presents from any graphics queue family
}

typedef struct { int sType; const void* pNext; uint32_t flags; ANativeWindow* window; } VkAndroidSurfaceCreateInfoKHR;
typedef int (*PFN_vkCreateAndroidSurfaceKHR)(void* instance, const VkAndroidSurfaceCreateInfoKHR* info,
                                             const void* allocator, uint64_t* surface);

API int glfwCreateWindowSurface(void* instance, void* window, const void* allocator, uint64_t* surface) {
    (void)window;
    PFN_getproc gp = get_proc();
    PFN_vkCreateAndroidSurfaceKHR create = gp ? (PFN_vkCreateAndroidSurfaceKHR)gp(instance, "vkCreateAndroidSurfaceKHR") : NULL;
    struct timespec until;
    clock_gettime(CLOCK_REALTIME, &until);
    until.tv_sec += 5;
    pthread_mutex_lock(&g_lock);
    // The engine comes here when the window has a size again, and it may be gone a moment later.
    while (!g_native && pthread_cond_timedwait(&g_surface_cond, &g_lock, &until) == 0) {}
    ANativeWindow* native = g_native;
    if (native) ANativeWindow_acquire(native);
    int gen = g_surfaceGen;
    pthread_mutex_unlock(&g_lock);
    if (!create || !native) {
        LOGW("glfwCreateWindowSurface: %s", create ? "no surface" : "no vkCreateAndroidSurfaceKHR");
        if (native) ANativeWindow_release(native);
        *surface = 0;
        return -7;   // VK_ERROR_EXTENSION_NOT_PRESENT
    }
    VkAndroidSurfaceCreateInfoKHR info = { 1000008000, NULL, 0, native };   // VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR
    int r = create(instance, &info, allocator, surface);
    ANativeWindow_release(native);
    if (r == 0) {
        pthread_mutex_lock(&g_lock);
        g_vkSurfaceGen = gen;
        pthread_mutex_unlock(&g_lock);
    }
    LOGI("glfwCreateWindowSurface -> %d", r);
    return r;
}

// ===================================================================== no GL, no other platforms

API void glfwMakeContextCurrent(void* w) { (void)w; }
API void* glfwGetCurrentContext(void) { return NULL; }
API void glfwSwapBuffers(void* w) { (void)w; }
API void glfwSwapInterval(int i) { (void)i; }
API int glfwExtensionSupported(const char* e) { (void)e; return GLFW_FALSE; }
API void* glfwGetProcAddress(const char* n) { (void)n; return NULL; }
API void* glfwGetEGLDisplay(void) { return NULL; }
API void* glfwGetEGLContext(void* w) { (void)w; return NULL; }
API void* glfwGetEGLSurface(void* w) { (void)w; return NULL; }
API void* glfwGetGLXContext(void* w) { (void)w; return NULL; }
API uint64_t glfwGetGLXWindow(void* w) { (void)w; return 0; }
API void* glfwGetWGLContext(void* w) { (void)w; return NULL; }
API void* glfwGetNSGLContext(void* w) { (void)w; return NULL; }
API void* glfwGetOSMesaContext(void* w) { (void)w; return NULL; }
API int glfwGetOSMesaColorBuffer(void* w, int* a, int* b, int* c, void** d) { (void)w; (void)a; (void)b; (void)c; (void)d; return GLFW_FALSE; }
API int glfwGetOSMesaDepthBuffer(void* w, int* a, int* b, int* c, void** d) { (void)w; (void)a; (void)b; (void)c; (void)d; return GLFW_FALSE; }
API void* glfwGetX11Display(void) { return NULL; }
API uint64_t glfwGetX11Window(void* w) { (void)w; return 0; }
API uint64_t glfwGetX11Adapter(void* m) { (void)m; return 0; }
API uint64_t glfwGetX11Monitor(void* m) { (void)m; return 0; }
API void glfwSetX11SelectionString(const char* s) { (void)s; }
API const char* glfwGetX11SelectionString(void) { return NULL; }
API void* glfwGetWaylandDisplay(void) { return NULL; }
API void* glfwGetWaylandWindow(void* w) { (void)w; return NULL; }
API void* glfwGetWaylandMonitor(void* m) { (void)m; return NULL; }
API const char* glfwGetWin32Adapter(void* m) { (void)m; return NULL; }
API const char* glfwGetWin32Monitor(void* m) { (void)m; return NULL; }
API void* glfwGetWin32Window(void* w) { (void)w; return NULL; }
API uint32_t glfwGetCocoaMonitor(void* m) { (void)m; return 0; }
API void* glfwGetCocoaWindow(void* w) { (void)w; return NULL; }
API void* glfwGetCocoaView(void* w) { (void)w; return NULL; }
