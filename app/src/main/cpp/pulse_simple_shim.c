/*
 * SPDX-License-Identifier: MIT
 *
 * libpulse-simple shim → AAudio  (original mechanism by udarmolota for RimDroid).
 * Copyright (c) 2026 udarmolota
 *
 * This single file is MIT-licensed (NOT the GPL-3.0 of the rest of RimDroid), so it can be reused
 * across the author's projects (e.g. Zomdroid) and by others, provided this notice is preserved.
 *
 * WHAT THIS IS
 * RimWorld is a Unity game; its audio (FMOD, embedded in UnityPlayer.so) on Linux probes
 * libpulse-simple.so.0 first, then libasound.so.2 — neither exists on Android, so the game is silent.
 * This is a TINY native ARM64 implementation of PulseAudio's synchronous "simple" API (only the 7
 * functions box64's wrappedpulsesimple bridges), backed by AAudio. box64 wraps the emulated x86_64
 * pa_simple_* calls and forwards them here; we push the PCM straight to AAudio → Android audio HAL.
 *
 * No PulseAudio server, no daemon. The library is built with soname "libpulse-simple.so.0" and
 * preloaded by name at app start so box64's wrapper dlopen("libpulse-simple.so.0") resolves to it.
 *
 * Only PLAYBACK is implemented (FMOD output). pa_simple_read is a stub. Formats: S16LE and FLOAT32LE
 * (what FMOD's Linux output actually uses); S32LE best-effort.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <android/log.h>
#include <aaudio/AAudio.h>

#define TAG "RimDroid/pa-shim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ---- minimal PulseAudio simple-API types (ABI-identical on x86_64 / arm64) ---- */
typedef enum {
    PA_STREAM_NODIRECTION, PA_STREAM_PLAYBACK, PA_STREAM_RECORD, PA_STREAM_UPLOAD
} pa_stream_direction_t;

typedef enum {
    PA_SAMPLE_U8 = 0, PA_SAMPLE_ALAW, PA_SAMPLE_ULAW,
    PA_SAMPLE_S16LE, PA_SAMPLE_S16BE,
    PA_SAMPLE_FLOAT32LE, PA_SAMPLE_FLOAT32BE,
    PA_SAMPLE_S32LE, PA_SAMPLE_S32BE,
    PA_SAMPLE_S24LE, PA_SAMPLE_S24BE,
    PA_SAMPLE_S24_32LE, PA_SAMPLE_S24_32BE
} pa_sample_format_t;

typedef struct { pa_sample_format_t format; uint32_t rate; uint8_t channels; } pa_sample_spec;
typedef uint64_t pa_usec_t;

/* PulseAudio error codes we may report (negative of the AAudio result is meaningless to FMOD, so we
 * use PA_ERR_IO for any failure — FMOD only checks for non-zero). */
#define PA_ERR_IO 5

struct pa_simple {
    AAudioStream *stream;
    int32_t       frameBytes;   /* channels * bytesPerSample */
    uint32_t      rate;
    int32_t       channels;
    aaudio_format_t fmt;
};

/* ---- helpers ---- */
static aaudio_format_t map_format(pa_sample_format_t f, int32_t *bytesPerSample) {
    switch (f) {
        case PA_SAMPLE_S16LE:    *bytesPerSample = 2; return AAUDIO_FORMAT_PCM_I16;
        case PA_SAMPLE_FLOAT32LE:*bytesPerSample = 4; return AAUDIO_FORMAT_PCM_FLOAT;
#if defined(AAUDIO_FORMAT_PCM_I32)
        case PA_SAMPLE_S32LE:    *bytesPerSample = 4; return AAUDIO_FORMAT_PCM_I32;
#endif
        default:                 *bytesPerSample = 0; return AAUDIO_FORMAT_INVALID;
    }
}

static aaudio_result_t open_stream(struct pa_simple *s) {
    AAudioStreamBuilder *b = NULL;
    aaudio_result_t r = AAudio_createStreamBuilder(&b);
    if (r != AAUDIO_OK || !b) return r != AAUDIO_OK ? r : AAUDIO_ERROR_NO_MEMORY;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(b, (int32_t) s->rate);
    AAudioStreamBuilder_setChannelCount(b, s->channels);
    AAudioStreamBuilder_setFormat(b, s->fmt);
    /* NONE (larger buffer) is more underrun-tolerant under box64 jank than LOW_LATENCY. */
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
    /* No data callback → blocking-write mode (AAudioStream_write). */
    r = AAudioStreamBuilder_openStream(b, &s->stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK || !s->stream) {
        LOGE("openStream failed: %s", AAudio_convertResultToText(r));
        s->stream = NULL;
        return r;
    }
    r = AAudioStream_requestStart(s->stream);
    if (r != AAUDIO_OK) {
        LOGE("requestStart failed: %s", AAudio_convertResultToText(r));
        AAudioStream_close(s->stream);
        s->stream = NULL;
        return r;
    }
    LOGI("AAudio stream open: %u Hz, %d ch, fmt=%d, frameBytes=%d",
         s->rate, s->channels, (int) s->fmt, s->frameBytes);
    return AAUDIO_OK;
}

/* ============================ exported pa_simple API ============================ */

__attribute__((visibility("default")))
struct pa_simple *pa_simple_new(const char *server, const char *name,
                                pa_stream_direction_t dir, const char *dev,
                                const char *stream_name, const pa_sample_spec *ss,
                                const void *map, const void *attr, int *error) {
    (void) server; (void) name; (void) dev; (void) stream_name; (void) map; (void) attr;
    LOGI("pa_simple_new(dir=%d, ss={fmt=%d, rate=%u, ch=%d})", dir,
         ss ? (int) ss->format : -1, ss ? ss->rate : 0, ss ? ss->channels : 0);
    if (!ss) { if (error) *error = PA_ERR_IO; return NULL; }
    if (dir != PA_STREAM_PLAYBACK) {
        LOGW("pa_simple_new dir=%d (only PLAYBACK supported) — opening anyway as playback", dir);
    }
    struct pa_simple *s = (struct pa_simple *) calloc(1, sizeof(*s));
    if (!s) { if (error) *error = PA_ERR_IO; return NULL; }

    int32_t bps = 0;
    s->fmt = map_format(ss->format, &bps);
    if (s->fmt == AAUDIO_FORMAT_INVALID || bps == 0) {
        LOGE("unsupported pa_sample_format %d", ss->format);
        free(s);
        if (error) *error = PA_ERR_IO;
        return NULL;
    }
    s->rate       = ss->rate ? ss->rate : 48000;
    s->channels   = ss->channels ? ss->channels : 2;
    s->frameBytes = bps * s->channels;

    if (open_stream(s) != AAUDIO_OK) {
        free(s);
        if (error) *error = PA_ERR_IO;
        return NULL;
    }
    if (error) *error = 0;
    return s;
}

__attribute__((visibility("default")))
int pa_simple_write(struct pa_simple *s, const void *data, size_t bytes, int *error) {
    static int firstWrite = 1;
    if (firstWrite) { LOGI("pa_simple_write FIRST call: %zu bytes", bytes); firstWrite = 0; }
    if (!s || !s->stream || !data) { if (error) *error = PA_ERR_IO; return -1; }
    int32_t frames = (int32_t) (bytes / (size_t) s->frameBytes);
    const uint8_t *p = (const uint8_t *) data;
    int reopened = 0;
    while (frames > 0) {
        aaudio_result_t r = AAudioStream_write(s->stream, p, frames, 1000000000L /* 1s */);
        if (r < 0) {
            if (r == AAUDIO_ERROR_DISCONNECTED && !reopened) {
                /* Output route changed (e.g. headphones plugged) — reopen once and retry. */
                LOGW("stream disconnected; reopening");
                AAudioStream_close(s->stream);
                s->stream = NULL;
                if (open_stream(s) != AAUDIO_OK) { if (error) *error = PA_ERR_IO; return -1; }
                reopened = 1;
                continue;
            }
            LOGE("AAudioStream_write: %s", AAudio_convertResultToText(r));
            if (error) *error = PA_ERR_IO;
            return -1;
        }
        frames -= r;
        p += (size_t) r * (size_t) s->frameBytes;
    }
    if (error) *error = 0;
    return 0;
}

__attribute__((visibility("default")))
int pa_simple_drain(struct pa_simple *s, int *error) {
    /* Block until the buffered audio has played out, so tail samples aren't cut on shutdown. */
    if (s && s->stream) {
        int64_t written = AAudioStream_getFramesWritten(s->stream);
        for (int i = 0; i < 200; i++) {   /* cap ~2s */
            int64_t read = AAudioStream_getFramesRead(s->stream);
            if (read >= written) break;
            struct timespec ts = { 0, 10 * 1000 * 1000 };  /* 10 ms */
            nanosleep(&ts, NULL);
        }
    }
    if (error) *error = 0;
    return 0;
}

__attribute__((visibility("default")))
int pa_simple_flush(struct pa_simple *s, int *error) {
    /* Discard buffered audio: stop → start clears AAudio's internal buffer. */
    if (s && s->stream) {
        AAudioStream_requestStop(s->stream);
        AAudioStream_requestStart(s->stream);
    }
    if (error) *error = 0;
    return 0;
}

__attribute__((visibility("default")))
pa_usec_t pa_simple_get_latency(struct pa_simple *s, int *error) {
    static int firstLat = 1;
    if (firstLat) { LOGI("pa_simple_get_latency FIRST call"); firstLat = 0; }
    if (error) *error = 0;
    if (!s || !s->stream || s->rate == 0) return 0;
    int64_t written = AAudioStream_getFramesWritten(s->stream);
    int64_t read    = AAudioStream_getFramesRead(s->stream);
    int64_t buffered = written - read;
    if (buffered < 0) buffered = 0;
    return (pa_usec_t) (buffered * 1000000LL / (int64_t) s->rate);
}

__attribute__((visibility("default")))
int pa_simple_read(struct pa_simple *s, void *data, size_t bytes, int *error) {
    /* Record is not implemented (FMOD output only). Report success with silence. */
    (void) s;
    if (data && bytes) memset(data, 0, bytes);
    if (error) *error = 0;
    return 0;
}

__attribute__((visibility("default")))
void pa_simple_free(struct pa_simple *s) {
    LOGI("pa_simple_free(%p)", (void *) s);
    if (!s) return;
    if (s->stream) {
        AAudioStream_requestStop(s->stream);
        AAudioStream_close(s->stream);
    }
    free(s);
}
