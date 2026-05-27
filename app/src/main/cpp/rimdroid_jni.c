#include <jni.h>
#include "rimdroid.h"
#include <stdlib.h>
#include <string.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "logger.h"

#define LOG_TAG "rimdroid-jni"

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
