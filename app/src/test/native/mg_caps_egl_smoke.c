/* Android-only smoke test; argv[1] is a scratch directory containing MG and libc++_shared.so.
 * Link with mg_caps.c, -lEGL -llog -ldl. No game, APK install or window is needed. */
#include "../../main/cpp/mg_caps.h"
#include <EGL/egl.h>
#include <assert.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

FILE* g_rimdroid_log_file;

int main(int argc, char** argv) {
    assert(argc == 2);
    char path[1024];
    g_rimdroid_log_file = stdout;
    setenv("MG_DIR_PATH", argv[1], 1);
    snprintf(path, sizeof(path), "%s/config.json", argv[1]);
    FILE* config = fopen(path, "w");
    assert(config);
    fputs("{\"enableANGLE\":0,\"enableNoError\":0,\"enableExtComputeShader\":0,"
          "\"enableExtTimerQuery\":0,\"enableExtDirectStateAccess\":0,\"maxGlslCacheSize\":64,"
          "\"angleDepthClearFixMode\":0,\"customGLVersion\":40,\"hideMGEnvLevel\":0,\"fsr1Setting\":0}", config);
    fclose(config);
    snprintf(path, sizeof(path), "%s/libc++_shared.so", argv[1]);
    assert(dlopen(path, RTLD_NOW | RTLD_GLOBAL));
    snprintf(path, sizeof(path), "%s/libmobileglues.so", argv[1]);
    void* mg = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!mg) { fprintf(stderr, "%s\n", dlerror()); return 1; }
    /* Match the launcher's default: system EGL, with RIMDROID_GLT_EGLTRACK unset.
     * MG's own eglCreateContext creates an ES-client record and reports the driver's
     * GLES version; that optional route is a different test configuration. */
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    assert(eglInitialize(display, NULL, NULL));
    EGLint attrs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig egl_config;
    EGLint count = 0;
    assert(eglChooseConfig(display, attrs, &egl_config, 1, &count) && count);
    EGLint ctx_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext ctx = eglCreateContext(display, egl_config, EGL_NO_CONTEXT, ctx_attrs);
    assert(ctx != EGL_NO_CONTEXT);
    EGLint surface_attrs[] = {EGL_WIDTH, 4, EGL_HEIGHT, 4, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(display, egl_config, surface_attrs);
    assert(surface != EGL_NO_SURFACE && eglMakeCurrent(display, surface, surface, ctx));
    assert(rimdroid_mg_caps_init(mg, dlsym));
    const unsigned char* (*get_string)(unsigned int) =
        rimdroid_mg_caps_override("glGetString", dlsym(mg, "glGetString"));
    const unsigned char* (*get_string_i)(unsigned int, unsigned int) =
        rimdroid_mg_caps_override("glGetStringi", dlsym(mg, "glGetStringi"));
    void (*get_integer)(unsigned int, int*) =
        rimdroid_mg_caps_override("glGetIntegerv", dlsym(mg, "glGetIntegerv"));
    const char* ext = (const char*)get_string(0x1F03);
    assert(strstr(ext, "GL_ARB_texture_storage") && !strstr(ext, "texture_compression_bptc"));
    get_integer(0x821D, &count);
    int storage_count = 0;
    for (int i = 0; i < count; ++i) {
        const char* item = (const char*)get_string_i(0x1F03, (unsigned int)i);
        assert(item && !strchr(item, ' ') && !strstr(item, "texture_compression_bptc"));
        if (!strcmp(item, "GL_ARB_texture_storage")) ++storage_count;
    }
    assert(storage_count == 1 && get_string_i(0x1F03, (unsigned int)count) == NULL);
    printf("REAL MG SMOKE PASSED: %s; %d extensions; texture_storage=%d; BPTC=0\n",
           get_string(0x1F02), count, storage_count);
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(display, surface);
    eglDestroyContext(display, ctx);
    eglTerminate(display);
    return 0;
}
