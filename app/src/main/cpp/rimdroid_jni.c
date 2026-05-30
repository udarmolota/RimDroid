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
    FILE* f = fopen("/data/data/com.rimdroid/files/jni_called.txt", "w");
    if (f) { fprintf(f, "JNI startGame called\n"); fclose(f); }
        
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
