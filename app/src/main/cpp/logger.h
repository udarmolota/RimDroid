// logger.h
#pragma once
#include <android/log.h>
#include <stdio.h>

#ifndef LOG_TAG
#define LOG_TAG "rimdroid"
#endif

extern FILE* g_rimdroid_log_file;  // defined in rimdroid.c

#define _LOG_TO_FILE(fmt, ...) do { \
    if (g_rimdroid_log_file) { \
        fprintf(g_rimdroid_log_file, fmt "\n", ##__VA_ARGS__); \
        fflush(g_rimdroid_log_file); \
    } \
} while(0)

#define LOGI(...) do { \
    __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__); \
    _LOG_TO_FILE(__VA_ARGS__); \
} while(0)
#define LOGW(...) do { \
    __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__); \
    _LOG_TO_FILE(__VA_ARGS__); \
} while(0)
#define LOGE(...) do { \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); \
    _LOG_TO_FILE(__VA_ARGS__); \
} while(0)
