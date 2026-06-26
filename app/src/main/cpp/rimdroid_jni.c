#include <jni.h>
#include "rimdroid.h"
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <dlfcn.h>
#include <libgen.h>
#include <unistd.h>
#include <sys/wait.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "logger.h"

extern char** environ;

#define LOG_TAG "rimdroid-jni"

// Launch the standalone box64+RimWorld binary (librimdroid_exec.so) as a FRESH
// process via fork()+execve().  execve wipes the ART heap → clean address space
// (RimWorld's fixed 0x021a9000 is free → box64 needs NO internal fork) and the
// new process gets a FRESH binder ProcessState → GPU context can be created+used
// without fork (fixes the GPU-after-fork crash).  execve inherits `environ`,
// which already has all the Os.setenv() box64/renderer vars from GameLauncher.
// The binary lives next to librimdroid.so in nativeLibraryDir (resolved via
// dladdr; requires android:extractNativeLibs="true" so it's on disk + exec'able).
JNIEXPORT jint JNICALL
Java_com_rimdroid_GameLauncher_execStandaloneGame(
        JNIEnv* env, jobject clazz,
        jstring j_game_dir, jstring j_lib_dir, jstring j_extra_arg)
{
    const char* game_dir = (*env)->GetStringUTFChars(env, j_game_dir, NULL);
    const char* lib_dir  = (*env)->GetStringUTFChars(env, j_lib_dir, NULL);
    const char* extra    = j_extra_arg ? (*env)->GetStringUTFChars(env, j_extra_arg, NULL) : NULL;

    // Resolve nativeLibraryDir from our own .so path.
    char self_copy[4096] = {0};
    Dl_info info;
    if (dladdr((void*)&Java_com_rimdroid_GameLauncher_execStandaloneGame, &info) && info.dli_fname) {
        strncpy(self_copy, info.dli_fname, sizeof(self_copy) - 1);
    }
    char* native_lib_dir = dirname(self_copy);   // mutates self_copy, returns dir
    char bin[4096];
    snprintf(bin, sizeof(bin), "%s/librimdroid_exec.so", native_lib_dir);

    LOGI("execStandaloneGame: bin=%s game=%s lib=%s extra=%s LD=%s",
         bin, game_dir, lib_dir, extra ? extra : "(none)", native_lib_dir);

    jint code = -1;
    pid_t pid = fork();
    if (pid == 0) {
        // child → execve immediately (no binder/GPU touched before exec)
        setenv("LD_LIBRARY_PATH", native_lib_dir, 1);
        char* argv[6];
        int n = 0;
        argv[n++] = bin;
        argv[n++] = (char*)game_dir;
        argv[n++] = (char*)lib_dir;
        if (extra) argv[n++] = (char*)extra;
        argv[n] = NULL;
        execve(bin, argv, environ);
        _exit(127);   // execve failed
    } else if (pid > 0) {
        int status = 0;
        waitpid(pid, &status, 0);
        if (WIFEXITED(status)) {
            code = WEXITSTATUS(status);
            LOGI("execStandaloneGame: child pid=%d exited code=%d", (int)pid, code);
        } else if (WIFSIGNALED(status)) {
            LOGI("execStandaloneGame: child pid=%d killed by signal %d", (int)pid, WTERMSIG(status));
        }
    } else {
        LOGE("execStandaloneGame: fork failed: %s", strerror(errno));
    }

    (*env)->ReleaseStringUTFChars(env, j_game_dir, game_dir);
    (*env)->ReleaseStringUTFChars(env, j_lib_dir, lib_dir);
    if (extra) (*env)->ReleaseStringUTFChars(env, j_extra_arg, extra);
    return code;
}

JNIEXPORT void JNICALL
Java_com_rimdroid_GameLauncher_startGame(
        JNIEnv* env, jobject clazz,
        jstring j_game_dir_path,
        jstring j_library_dir_path,
        jobjectArray j_args)
{
    const char* game_dir_path    = (*env)->GetStringUTFChars(env, j_game_dir_path, NULL);
    const char* library_dir_path = (*env)->GetStringUTFChars(env, j_library_dir_path, NULL);

    int argc = (*env)->GetArrayLength(env, j_args);
    char** argv = NULL;
    if (argc > 0) {
        argv = malloc(argc * sizeof(char*));
        for (int i = 0; i < argc; i++) {
            jstring arg_string = (*env)->GetObjectArrayElement(env, j_args, i);
            const char* arg = (*env)->GetStringUTFChars(env, arg_string, NULL);
            argv[i] = strdup(arg);
            (*env)->ReleaseStringUTFChars(env, arg_string, arg);
        }
    }

    rimdroid_start_game(game_dir_path, library_dir_path, argc, (const char**)argv);

    (*env)->ReleaseStringUTFChars(env, j_game_dir_path, game_dir_path);
    (*env)->ReleaseStringUTFChars(env, j_library_dir_path, library_dir_path);

    if (argv) {
        for (int i = 0; i < argc; i++) free(argv[i]);
        free(argv);
    }
}

JNIEXPORT jint JNICALL
Java_com_rimdroid_GameLauncher_initRimDroidWindow(JNIEnv* env, jobject clazz) {
    return rimdroid_init();
}

JNIEXPORT void JNICALL
Java_com_rimdroid_GameLauncher_destroyRimDroidWindow(JNIEnv* env, jobject clazz) {
    rimdroid_deinit();
}

JNIEXPORT jint JNICALL
Java_com_rimdroid_GameLauncher_setSurface(
        JNIEnv* env, jobject clazz,
        jobject surface, jint width, jint height)
{
    ANativeWindow* wnd = ANativeWindow_fromSurface(env, surface);
    rimdroid_surface_init(wnd, width, height);
    return 1;
}

JNIEXPORT void JNICALL
Java_com_rimdroid_GameLauncher_destroySurface(JNIEnv* env, jobject clazz) {
    rimdroid_surface_deinit();
}

JNIEXPORT jint JNICALL
Java_com_rimdroid_GameLauncher_nativeOsmesaSmokeTest(JNIEnv* env, jclass clazz, jstring jpath) {
    const char* path = jpath ? (*env)->GetStringUTFChars(env, jpath, NULL) : NULL;
    int rc = rimdroid_osmesa_smoketest(path);
    if (path) (*env)->ReleaseStringUTFChars(env, jpath, path);
    return rc;
}

// --- Phase A input injection (touch → SDL mouse); rd_input_* declared in rimdroid.h ---
// action: 0 = move, 1 = down, 2 = up. x,y are game-window pixels (already scaled).
JNIEXPORT void JNICALL
Java_com_rimdroid_GameActivity_nativeTouch(JNIEnv* env, jclass clazz,
                                           jint action, jint x, jint y) {
    if (action == 1) {            // DOWN: move there, then press left button
        rd_input_mouse_motion(x, y);
        rd_input_mouse_button(1 /*SDL_BUTTON_LEFT*/, 1, x, y);
    } else if (action == 2) {     // UP: release left button
        rd_input_mouse_button(1, 0, x, y);
    } else {                      // MOVE
        rd_input_mouse_motion(x, y);
    }
}

// Generic button (1=L,2=M,3=R), down=1/0, at (x,y). Moves cursor there first.
JNIEXPORT void JNICALL
Java_com_rimdroid_GameActivity_nativeButton(JNIEnv* env, jclass clazz,
                                            jint button, jint down, jint x, jint y) {
    rd_input_mouse_motion(x, y);
    rd_input_mouse_button(button, down, x, y);
}

// Mouse wheel (zoom). Positions the cursor at (x,y) first so RimWorld zooms there.
JNIEXPORT void JNICALL
Java_com_rimdroid_GameActivity_nativeScroll(JNIEnv* env, jclass clazz,
                                            jint x, jint y, jint dy) {
    rd_input_mouse_motion(x, y);
    rd_input_mouse_scroll(dy);
}

// Keyboard key (SDL scancode + keycode), down=1/0. For camera pan (WASD) etc.
JNIEXPORT void JNICALL
Java_com_rimdroid_GameActivity_nativeKey(JNIEnv* env, jclass clazz,
                                         jint scancode, jint keycode, jint down) {
    rd_input_key(scancode, keycode, down);
}

// Text input (typing) — UTF-8 string from the Android IME keyboard.
JNIEXPORT void JNICALL
Java_com_rimdroid_GameActivity_nativeText(JNIEnv* env, jclass clazz, jstring jtext) {
    if (!jtext) return;
    const char* s = (*env)->GetStringUTFChars(env, jtext, NULL);
    if (s) { rd_input_text(s); (*env)->ReleaseStringUTFChars(env, jtext, s); }
}

// FPS overlay: total presented frames so far. The Java overlay reads this once a
// second and shows the delta = true presented FPS.
JNIEXPORT jlong JNICALL
Java_com_rimdroid_GameActivity_nativeGetFrameCount(JNIEnv* env, jclass clazz) {
    return (jlong)g_rimdroid_frame_count;
}
