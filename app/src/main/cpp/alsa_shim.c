/*
 * SPDX-License-Identifier: MIT
 *
 * libasound (ALSA) shim → AAudio — by udarmolota for RimDroid.
 * Copyright (c) 2026 udarmolota
 *
 * This single file is MIT-licensed (NOT the GPL-3.0 of the rest of RimDroid).
 *
 * WHY: RimWorld's FMOD probes PulseAudio first, then ALSA. With no pulse libs present, FMOD falls back
 * to its ALSA output. This is a minimal native ARM64 libasound.so.2 implementing the synchronous
 * snd_pcm_* subset FMOD's ALSA output uses, backed by AAudio. No async callbacks / no mainloop (unlike
 * the pulse async API). snd_pcm_writei maps 1:1 to a blocking AAudio write.
 *
 * Built with soname "libasound.so.2" and preloaded by name (RimDroidApplication) so box64's
 * wrappedlibasound dlopen("libasound.so.2") resolves to it. Heavily logged for bring-up.
 *
 * hw_params/sw_params are treated as OPAQUE blobs — we never parse them; the setters that matter store
 * the desired rate/format/channels straight into our snd_pcm_t, and snd_pcm_hw_params() opens the AAudio
 * stream from those. Only PLAYBACK, interleaved RW, S16/FLOAT32/S32.
 */
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <math.h>
#include <android/log.h>
#include <aaudio/AAudio.h>

#define RD_TWO_PI 6.28318530717958647692

#define TAG "RimDroid/alsa"
/* Log to BOTH logcat AND stderr — box64's stderr folds into Unity's Player.log, so these lines show up
 * in "Export logs (ZIP)" too (no separate logcat capture needed). All call sites pass a literal format. */
#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__); \
                       fprintf(stderr, "[RD-alsa] " __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__); \
                       fprintf(stderr, "[RD-alsa] " __VA_ARGS__); fprintf(stderr, "\n"); } while (0)

#define EXPORT __attribute__((visibility("default")))

typedef unsigned long snd_pcm_uframes_t;
typedef long          snd_pcm_sframes_t;

/* ALSA format codes (snd_pcm_format_t) */
enum { SND_PCM_FORMAT_S16_LE = 2, SND_PCM_FORMAT_S32_LE = 10, SND_PCM_FORMAT_FLOAT_LE = 14 };
/* snd_pcm_state_t */
enum { SND_PCM_STATE_OPEN=0, SND_PCM_STATE_SETUP, SND_PCM_STATE_PREPARED, SND_PCM_STATE_RUNNING,
       SND_PCM_STATE_XRUN, SND_PCM_STATE_DRAINING, SND_PCM_STATE_PAUSED, SND_PCM_STATE_SUSPENDED };

typedef struct snd_pcm {
    AAudioStream *stream;
    aaudio_format_t fmt;          /* format FMOD writes (mapped from the ALSA format it set) */
    aaudio_format_t actualFmt;    /* format AAudio actually gave us (may differ → convert in writei) */
    int32_t  frameBytes;
    int32_t  bits;                /* bits per sample (16 / 32) */
    int32_t  alsaFormat;          /* SND_PCM_FORMAT_* code FMOD requested */
    uint32_t rate;
    int32_t  channels;
    snd_pcm_uframes_t period;     /* frames */
    snd_pcm_uframes_t buffer;     /* frames */
    int state;
    int capture;                  /* 1 = capture/record stream (no AAudio; inert) */
} snd_pcm_t;

/* REAL alsa-lib / kernel-UAPI snd_pcm_hw_params_t layout. FMOD's snd_pcm_hw_params_get_* are INLINE
 * accessors compiled into the game that read these fields DIRECTLY (which is why our exported getters
 * never fire) — so snd_pcm_hw_params() MUST materialize the negotiated config here, and sizeof() MUST be
 * the real size, or FMOD reads zeros → FMOD_ERR_INTERNAL. Field order/array sizes mirror
 * uapi/sound/asound.h exactly so offsets match what FMOD's inlined accessors expect. */
struct snd_mask { uint32_t bits[8]; };                 /* SNDRV_MASK_MAX=256 → 8 u32 */
struct snd_interval {
    unsigned int min, max;
    unsigned int flags;          /* openmin:1, openmax:1, integer:1, empty:1 (bit2 = integer) */
};
typedef struct snd_pcm_hw_params {
    unsigned int flags;
    struct snd_mask masks[3];        /* FIRST_MASK(ACCESS=0)..LAST_MASK(SUBFORMAT=2) */
    struct snd_mask mres[5];         /* reserved masks */
    struct snd_interval intervals[13]; /* FIRST_INTERVAL(SAMPLE_BITS=8)..LAST_INTERVAL(TICK_TIME=20) */
    struct snd_interval ires[9];     /* reserved intervals */
    unsigned int rmask, cmask, info, msbits, rate_num, rate_den;
    snd_pcm_uframes_t fifo_size;
    unsigned char reserved[64];
} snd_pcm_hw_params_t;
typedef struct { char _[8]; } snd_pcm_sw_params_t;

/* hw_param indices (kernel UAPI). MASK params index masks[p]; INTERVAL params index intervals[p-8]. */
enum { HWP_ACCESS=0, HWP_FORMAT=1,
       HWP_SAMPLE_BITS=8, HWP_FRAME_BITS=9, HWP_CHANNELS=10, HWP_RATE=11, HWP_PERIOD_TIME=12,
       HWP_PERIOD_SIZE=13, HWP_PERIOD_BYTES=14, HWP_PERIODS=15, HWP_BUFFER_TIME=16,
       HWP_BUFFER_SIZE=17, HWP_BUFFER_BYTES=18 };

static void hwp_set_mask(snd_pcm_hw_params_t *p, int maskParam, int bit) {
    if (!p) return;
    memset(&p->masks[maskParam], 0, sizeof(p->masks[maskParam]));
    p->masks[maskParam].bits[bit >> 5] |= (1u << (bit & 31));
}
static void hwp_set_interval(snd_pcm_hw_params_t *p, int intervalParam, unsigned int val) {
    if (!p) return;
    struct snd_interval *iv = &p->intervals[intervalParam - HWP_SAMPLE_BITS];
    iv->min = iv->max = val;
    iv->flags = 0x4;   /* integer=1, openmin/openmax/empty = 0 */
}

static aaudio_format_t map_format(int alsa, int32_t *bps) {
    switch (alsa) {
        case SND_PCM_FORMAT_S16_LE:   *bps = 2; return AAUDIO_FORMAT_PCM_I16;
        case SND_PCM_FORMAT_FLOAT_LE: *bps = 4; return AAUDIO_FORMAT_PCM_FLOAT;
#if defined(AAUDIO_FORMAT_PCM_I32)
        case SND_PCM_FORMAT_S32_LE:   *bps = 4; return AAUDIO_FORMAT_PCM_I32;
#endif
        default:                      *bps = 2; return AAUDIO_FORMAT_PCM_I16;
    }
}

static int open_aaudio(snd_pcm_t *p) {
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK || !b) return -1;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(b, (int32_t) p->rate);
    AAudioStreamBuilder_setChannelCount(b, p->channels);
    AAudioStreamBuilder_setFormat(b, p->fmt);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
    /* Generous buffer capacity: under box64 emulation FMOD feeds PCM in jittery bursts, so a small buffer
     * underruns → crackle/dropouts. Ask for a large capacity (~16k frames ≈ 340ms @ 48k); higher latency
     * is fine for a game, and it absorbs the emulation jitter. */
    AAudioStreamBuilder_setBufferCapacityInFrames(b, 16384);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &p->stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK || !p->stream) { LOGE("openStream: %s", AAudio_convertResultToText(r)); p->stream=NULL; return -1; }
    /* Maximize the running buffer size within the granted capacity to tolerate underruns. */
    int32_t cap = AAudioStream_getBufferCapacityInFrames(p->stream);
    if (cap > 0) AAudioStream_setBufferSizeInFrames(p->stream, cap);
    int32_t bufsz = AAudioStream_getBufferSizeInFrames(p->stream);
    LOGI("AAudio buffer: requested cap=16384, GRANTED capacity=%d, bufferSize set to=%d", cap, bufsz);
    AAudioStream_requestStart(p->stream);
    /* AAudio may hand back a stream whose ACTUAL format/rate/channels differ from what we requested
     * (e.g. FLOAT on flagship devices even when we asked for I16). Capture them; writei converts/handles. */
    p->actualFmt = AAudioStream_getFormat(p->stream);
    int aRate = AAudioStream_getSampleRate(p->stream);
    int aCh   = AAudioStream_getChannelCount(p->stream);
    int aBurst= AAudioStream_getFramesPerBurst(p->stream);
    LOGI("AAudio open: requested fmt=%d %uHz %dch | ACTUAL fmt=%d %dHz %dch burst=%d",
         (int)p->fmt, p->rate, p->channels, (int)p->actualFmt, aRate, aCh, aBurst);
    if ((aaudio_format_t)p->actualFmt != p->fmt)
        LOGE("FORMAT MISMATCH: FMOD writes fmt=%d but AAudio stream is fmt=%d → converting in writei",
             (int)p->fmt, (int)p->actualFmt);
    return 0;
}

/* ============================ open / close ============================ */
EXPORT int snd_pcm_open(snd_pcm_t **pcmp, const char *name, int stream, int mode) {
    LOGI("snd_pcm_open(name=%s, stream=%d, mode=%d)", name ? name : "(null)", stream, mode);
    snd_pcm_t *p = (snd_pcm_t *) calloc(1, sizeof(*p));
    if (!p) return -12 /*-ENOMEM*/;
    p->fmt = AAUDIO_FORMAT_PCM_I16; p->frameBytes = 4; p->bits = 16; p->alsaFormat = SND_PCM_FORMAT_S16_LE;
    p->rate = 48000; p->channels = 2; p->period = 1024; p->buffer = 4096; p->state = SND_PCM_STATE_OPEN;
    p->capture = (stream != 0);   /* CAPTURE: accept it (inert) so FMOD's input enumeration succeeds */
    *pcmp = p;
    return 0;
}
EXPORT int snd_pcm_close(snd_pcm_t *p) {
    LOGI("snd_pcm_close");
    if (p) { if (p->stream) { AAudioStream_requestStop(p->stream); AAudioStream_close(p->stream); } free(p); }
    return 0;
}
EXPORT int snd_pcm_nonblock(snd_pcm_t *p, int n) { (void)p; LOGI("snd_pcm_nonblock %d", n); return 0; }
EXPORT const char *snd_pcm_name(snd_pcm_t *p) { (void)p; return "default"; }
EXPORT int snd_pcm_state(snd_pcm_t *p) { int s = p ? p->state : SND_PCM_STATE_OPEN; LOGI("snd_pcm_state →%d", s); return s; }

/* ============================ hw params ============================ */
EXPORT size_t snd_pcm_hw_params_sizeof(void) { return sizeof(snd_pcm_hw_params_t); }   /* REAL size now */
EXPORT int  snd_pcm_hw_params_malloc(snd_pcm_hw_params_t **p) { *p = (snd_pcm_hw_params_t*)calloc(1,sizeof(**p)); return *p?0:-12; }
EXPORT void snd_pcm_hw_params_free(snd_pcm_hw_params_t *p) { LOGI("hw_params_free"); free(p); }
EXPORT int  snd_pcm_hw_params_any(snd_pcm_t *pcm, snd_pcm_hw_params_t *p) { (void)pcm; (void)p; LOGI("hw_params_any"); return 0; }
EXPORT int  snd_pcm_hw_params_set_access(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, int access) {
    (void)pcm; (void)p;
    /* 3 = RW_INTERLEAVED (we support, → snd_pcm_writei). REJECT mmap (0/1) and non-interleaved (2) so
     * FMOD falls back to the RW/writei path — we have no snd_pcm_mmap_begin/commit. */
    LOGI("set_access %d %s", access, access == 3 ? "(RW_INTERLEAVED ok)" : "(REJECTED → force RW)");
    return (access == 3) ? 0 : -22 /*-EINVAL*/;
}
EXPORT int  snd_pcm_hw_params_set_format(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, int fmt) {
    (void)p;
    /* FIX/TEST (env RIMDROID_AUDIO_FORCE_FLOAT=1): reject integer formats so FMOD outputs FLOAT32 instead.
     * box64 miscompiles FMOD's emulated float→int16 SSE conversion (sine test proved our path is clean, so
     * the garbage is FMOD's int16 data). If FMOD hands us float, that broken conversion is skipped and we
     * feed float straight to AAudio (native-float on this device → no conversion at all). */
    if (getenv("RIMDROID_AUDIO_FORCE_FLOAT") && fmt != SND_PCM_FORMAT_FLOAT_LE) {
        LOGI("set_format alsa=%d REJECTED (forcing FLOAT32)", fmt);
        return -22 /*-EINVAL → FMOD tries the next format (FLOAT)*/;
    }
    if (pcm) { int bps=0; pcm->fmt = map_format(fmt, &bps); pcm->bits = bps*8; pcm->alsaFormat = fmt;
        pcm->frameBytes = bps * pcm->channels; LOGI("set_format alsa=%d", fmt);} return 0; }
EXPORT int  snd_pcm_hw_params_set_channels(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, unsigned int ch) {
    (void)p; if (pcm && ch) { pcm->channels = (int)ch; pcm->frameBytes = (pcm->bits?pcm->bits/8:2)*ch; LOGI("set_channels %u", ch);} return 0; }
EXPORT int  snd_pcm_hw_params_set_rate(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, unsigned int rate, int dir) {
    (void)p; (void)dir; if (pcm && rate) pcm->rate = rate; LOGI("set_rate %u", rate); return 0; }
EXPORT int  snd_pcm_hw_params_set_rate_near(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, unsigned int *rate, int *dir) {
    (void)p; if (pcm && rate && *rate) pcm->rate = *rate; if (dir) *dir = 0; LOGI("set_rate_near %u", rate?*rate:0); return 0; }
EXPORT int  snd_pcm_hw_params_set_rate_resample(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, unsigned int v) { (void)pcm;(void)p; LOGI("set_rate_resample %u", v); return 0; }
EXPORT int  snd_pcm_hw_params_set_period_size_near(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, snd_pcm_uframes_t *val, int *dir) {
    (void)p; if (pcm && val && *val) pcm->period = *val; if (dir) *dir = 0;
    LOGI("set_period_size_near %lu", val ? (unsigned long)*val : 0); return 0; }
EXPORT int  snd_pcm_hw_params_set_periods_near(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, unsigned int *val, int *dir) {
    (void)pcm; (void)p; (void)val; if (dir) *dir = 0; return 0; }
EXPORT int  snd_pcm_hw_params_set_buffer_size_near(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, snd_pcm_uframes_t *val) {
    (void)p; if (pcm && val && *val) pcm->buffer = *val;
    LOGI("set_buffer_size_near %lu", val ? (unsigned long)*val : 0); return 0; }
EXPORT int  snd_pcm_hw_params_set_buffer_time_near(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, unsigned int *val, int *dir) {
    (void)pcm; (void)p; (void)val; if (dir) *dir = 0; return 0; }
EXPORT int  snd_pcm_hw_params_set_period_time_near(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, unsigned int *val, int *dir) {
    (void)pcm; (void)p; (void)val; if (dir) *dir = 0; return 0; }
EXPORT int  snd_pcm_hw_params_get_period_size(const snd_pcm_hw_params_t *p, snd_pcm_uframes_t *val, int *dir) {
    (void)p; if (val) *val = 1024; if (dir) *dir = 0; LOGI("get_period_size →1024"); return 0; }
EXPORT int  snd_pcm_hw_params_get_buffer_size(const snd_pcm_hw_params_t *p, snd_pcm_uframes_t *val) {
    (void)p; if (val) *val = 4096; LOGI("get_buffer_size →4096"); return 0; }
EXPORT int  snd_pcm_hw_params_get_periods(const snd_pcm_hw_params_t *p, unsigned int *val, int *dir) {
    (void)p; if (val) *val = 4; if (dir) *dir = 0; LOGI("get_periods →4"); return 0; }
EXPORT unsigned int snd_pcm_hw_params_get_channels(const snd_pcm_hw_params_t *p) { (void)p; LOGI("get_channels →2"); return 2; }
EXPORT int  snd_pcm_hw_params_get_channels_n(const snd_pcm_hw_params_t *p, unsigned int *val) { (void)p; if (val) *val = 2; LOGI("get_channels(ptr) →2"); return 0; }
EXPORT int  snd_pcm_hw_params_get_rate(const snd_pcm_hw_params_t *p, unsigned int *val, int *dir) {
    (void)p; if (val) *val = 48000; if (dir) *dir = 0; LOGI("get_rate →48000"); return 0; }
/* capability/range getters FMOD may query after hw_params (all permissive) */
EXPORT int  snd_pcm_hw_params_get_format(const snd_pcm_hw_params_t *p, int *fmt) { (void)p; if (fmt) *fmt = SND_PCM_FORMAT_S16_LE; LOGI("get_format →S16"); return 0; }
EXPORT int  snd_pcm_hw_params_get_access(const snd_pcm_hw_params_t *p, int *acc) { (void)p; if (acc) *acc = 3; LOGI("get_access →RW"); return 0; }
EXPORT int  snd_pcm_hw_params_get_rate_min(const snd_pcm_hw_params_t *p, unsigned int *v, int *d) { (void)p; if (v) *v = 8000; if (d) *d = 0; return 0; }
EXPORT int  snd_pcm_hw_params_get_rate_max(const snd_pcm_hw_params_t *p, unsigned int *v, int *d) { (void)p; if (v) *v = 192000; if (d) *d = 0; return 0; }
EXPORT int  snd_pcm_hw_params_get_channels_min(const snd_pcm_hw_params_t *p, unsigned int *v) { (void)p; if (v) *v = 1; return 0; }
EXPORT int  snd_pcm_hw_params_get_channels_max(const snd_pcm_hw_params_t *p, unsigned int *v) { (void)p; if (v) *v = 2; return 0; }
EXPORT int  snd_pcm_hw_params_get_period_size_min(const snd_pcm_hw_params_t *p, snd_pcm_uframes_t *v, int *d) { (void)p; if (v) *v = 64; if (d) *d = 0; return 0; }
EXPORT int  snd_pcm_hw_params_get_period_size_max(const snd_pcm_hw_params_t *p, snd_pcm_uframes_t *v, int *d) { (void)p; if (v) *v = 8192; if (d) *d = 0; return 0; }
EXPORT int  snd_pcm_hw_params_get_buffer_size_min(const snd_pcm_hw_params_t *p, snd_pcm_uframes_t *v) { (void)p; if (v) *v = 128; return 0; }
EXPORT int  snd_pcm_hw_params_get_buffer_size_max(const snd_pcm_hw_params_t *p, snd_pcm_uframes_t *v) { (void)p; if (v) *v = 65536; return 0; }
EXPORT int  snd_pcm_hw_params_get_buffer_time_max(const snd_pcm_hw_params_t *p, unsigned int *v, int *d) { (void)p; if (v) *v = 1000000; if (d) *d = 0; return 0; }
EXPORT int  snd_pcm_hw_params_can_pause(const snd_pcm_hw_params_t *p) { (void)p; return 0; }
EXPORT int  snd_pcm_hw_params_can_resume(const snd_pcm_hw_params_t *p) { (void)p; return 0; }
EXPORT int  snd_pcm_hw_params_is_batch(const snd_pcm_hw_params_t *p) { (void)p; return 0; }
EXPORT int  snd_pcm_hw_params_current(snd_pcm_t *pcm, snd_pcm_hw_params_t *p) { (void)pcm; (void)p; LOGI("hw_params_current"); return 0; }
EXPORT int  snd_pcm_hw_params_test_format(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, int fmt) {
    (void)pcm; (void)p;
    if (getenv("RIMDROID_AUDIO_FORCE_FLOAT")) return (fmt==SND_PCM_FORMAT_FLOAT_LE)?0:-22;  /* advertise FLOAT only */
    return (fmt==SND_PCM_FORMAT_S16_LE||fmt==SND_PCM_FORMAT_FLOAT_LE||fmt==SND_PCM_FORMAT_S32_LE)?0:-22; }
EXPORT int  snd_pcm_hw_params_test_rate(snd_pcm_t *pcm, snd_pcm_hw_params_t *p, unsigned int r, int dir) {
    (void)pcm;(void)p;(void)r;(void)dir; return 0; }
EXPORT int  snd_pcm_hw_params(snd_pcm_t *pcm, snd_pcm_hw_params_t *p) {
    if (!pcm) return -22;
    /* Materialize the negotiated config INTO the params struct so FMOD's inline accessors read real
     * values (period/buffer/rate/channels/format). Without this they read zeros → FMOD_ERR_INTERNAL. */
    if (p) {
        unsigned int periods = pcm->period ? (unsigned int)(pcm->buffer / pcm->period) : 1;
        hwp_set_mask(p, HWP_ACCESS, 3 /*SND_PCM_ACCESS_RW_INTERLEAVED*/);
        hwp_set_mask(p, HWP_FORMAT, pcm->alsaFormat);
        hwp_set_interval(p, HWP_CHANNELS,    (unsigned)pcm->channels);
        hwp_set_interval(p, HWP_RATE,        pcm->rate);
        hwp_set_interval(p, HWP_SAMPLE_BITS, (unsigned)pcm->bits);
        hwp_set_interval(p, HWP_FRAME_BITS,  (unsigned)(pcm->bits * pcm->channels));
        hwp_set_interval(p, HWP_PERIOD_SIZE, (unsigned)pcm->period);
        hwp_set_interval(p, HWP_BUFFER_SIZE, (unsigned)pcm->buffer);
        hwp_set_interval(p, HWP_PERIODS,     periods);
        hwp_set_interval(p, HWP_PERIOD_BYTES,(unsigned)(pcm->period * pcm->frameBytes));
        hwp_set_interval(p, HWP_BUFFER_BYTES,(unsigned)(pcm->buffer * pcm->frameBytes));
        hwp_set_interval(p, HWP_PERIOD_TIME, pcm->rate ? (unsigned)((uint64_t)pcm->period * 1000000ULL / pcm->rate) : 0);
        hwp_set_interval(p, HWP_BUFFER_TIME, pcm->rate ? (unsigned)((uint64_t)pcm->buffer * 1000000ULL / pcm->rate) : 0);
        p->rate_num = pcm->rate; p->rate_den = 1; p->msbits = pcm->bits;
    }
    /* hw_params only CONFIGURES (→ SETUP); AAudio is opened lazily in snd_pcm_prepare. */
    pcm->state = SND_PCM_STATE_SETUP;
    LOGI("snd_pcm_hw_params → SETUP (rate=%u ch=%d bits=%d period=%lu buffer=%lu) filled params, ret 0",
         pcm->rate, pcm->channels, pcm->bits, (unsigned long)pcm->period, (unsigned long)pcm->buffer);
    return 0;
}

/* High-level helper FMOD often calls right after hw_params to grab buffer+period in one shot. */
EXPORT int snd_pcm_get_params(snd_pcm_t *pcm, snd_pcm_uframes_t *buffer_size, snd_pcm_uframes_t *period_size) {
    if (buffer_size) *buffer_size = pcm ? pcm->buffer : 4096;
    if (period_size) *period_size = pcm ? pcm->period : 1024;
    LOGI("snd_pcm_get_params → buffer=%lu period=%lu",
         (unsigned long)(pcm?pcm->buffer:4096), (unsigned long)(pcm?pcm->period:1024));
    return 0;
}

/* ============================ sw params ============================ */
EXPORT size_t snd_pcm_sw_params_sizeof(void) { return sizeof(snd_pcm_sw_params_t); }
EXPORT int  snd_pcm_sw_params_malloc(snd_pcm_sw_params_t **p) { LOGI("sw_params_malloc"); *p=(snd_pcm_sw_params_t*)calloc(1,sizeof(**p)); return *p?0:-12; }
EXPORT void snd_pcm_sw_params_free(snd_pcm_sw_params_t *p) { free(p); }
EXPORT int  snd_pcm_sw_params_current(snd_pcm_t *pcm, snd_pcm_sw_params_t *p) { (void)pcm;(void)p; LOGI("sw_params_current"); return 0; }
EXPORT int  snd_pcm_sw_params_set_start_threshold(snd_pcm_t *pcm, snd_pcm_sw_params_t *p, snd_pcm_uframes_t v) { (void)pcm;(void)p;(void)v; return 0; }
EXPORT int  snd_pcm_sw_params_set_stop_threshold(snd_pcm_t *pcm, snd_pcm_sw_params_t *p, snd_pcm_uframes_t v) { (void)pcm;(void)p;(void)v; return 0; }
EXPORT int  snd_pcm_sw_params_set_avail_min(snd_pcm_t *pcm, snd_pcm_sw_params_t *p, snd_pcm_uframes_t v) { (void)pcm;(void)p;(void)v; return 0; }
EXPORT int  snd_pcm_sw_params(snd_pcm_t *pcm, snd_pcm_sw_params_t *p) { (void)pcm;(void)p; LOGI("sw_params"); return 0; }

/* ============================ transfer ============================ */
EXPORT int snd_pcm_prepare(snd_pcm_t *pcm) {
    LOGI("snd_pcm_prepare%s", (pcm && pcm->capture) ? " (capture)" : "");
    if (pcm) { if (!pcm->capture && !pcm->stream) open_aaudio(pcm); pcm->state = SND_PCM_STATE_PREPARED; }
    return 0;
}
EXPORT int snd_pcm_start(snd_pcm_t *pcm) { LOGI("snd_pcm_start"); if (pcm) pcm->state = SND_PCM_STATE_RUNNING; return 0; }
EXPORT int snd_pcm_drop(snd_pcm_t *pcm) {
    LOGI("snd_pcm_drop");
    if (pcm && pcm->stream) { AAudioStream_requestStop(pcm->stream); pcm->state = SND_PCM_STATE_SETUP; } return 0;
}
EXPORT int snd_pcm_drain(snd_pcm_t *pcm) {
    if (pcm && pcm->stream) {
        int64_t w = AAudioStream_getFramesWritten(pcm->stream);
        for (int i=0;i<200;i++){ if (AAudioStream_getFramesRead(pcm->stream)>=w) break; struct timespec t={0,10000000}; nanosleep(&t,NULL);} }
    return 0;
}
EXPORT int snd_pcm_pause(snd_pcm_t *pcm, int enable) {
    if (pcm && pcm->stream) { if (enable) AAudioStream_requestPause(pcm->stream); else AAudioStream_requestStart(pcm->stream); }
    return 0;
}
EXPORT int snd_pcm_reset(snd_pcm_t *pcm) { (void)pcm; return 0; }

EXPORT snd_pcm_sframes_t snd_pcm_writei(snd_pcm_t *pcm, const void *buf, snd_pcm_uframes_t size) {
    static int firstWrite = 1;
    static long wcount = 0;
    if (firstWrite) { LOGI("snd_pcm_writei FIRST call: %lu frames", (unsigned long)size); firstWrite = 0; }
    if (!pcm || !buf) return -5 /*-EIO*/;
    /* Periodic diagnostic: AAudio underrun count. If it climbs steadily → starvation (need more buffer /
     * thread priority / callback path). If it stays ~0 but audio still clicks → sample corruption, not
     * underrun. */
    if (pcm->stream && (++wcount % 200) == 0) {
        LOGI("writei #%ld: AAudio XRun(underrun)=%d bufSize=%d framesWritten=%lld framesRead=%lld",
             wcount, AAudioStream_getXRunCount(pcm->stream),
             AAudioStream_getBufferSizeInFrames(pcm->stream),
             (long long)AAudioStream_getFramesWritten(pcm->stream),
             (long long)AAudioStream_getFramesRead(pcm->stream));
    }
    /* Lazy-open the AAudio stream on first write: FMOD goes hw_params → writei DIRECTLY (no snd_pcm_prepare
     * in its path), so the stream wouldn't otherwise be open. Open it here if needed. */
    if (!pcm->stream && !pcm->capture && open_aaudio(pcm) != 0) return -5 /*-EIO*/;
    if (!pcm->stream) return -5 /*-EIO*/;
    pcm->state = SND_PCM_STATE_RUNNING;

    /* ===== DIAGNOSTIC 1: dump the RAW PCM FMOD gives us, to <HOME>/fmod_dump.raw (env RIMDROID_AUDIO_DUMP=1).
     * Pull it and import in Audacity as S16_LE / 48000 / stereo. If the file is ALSO noise, the corruption is
     * upstream (box64-emulated FMOD), not our AAudio path. Capped at ~20s so the file stays small. */
    static FILE *dumpf = NULL; static int dumpDone = 0; static long dumpFrames = 0;
    if (getenv("RIMDROID_AUDIO_DUMP") && !dumpDone) {
        /* Skip leading SILENCE: the first writei calls happen during the (long, under box64) load before
         * the menu music starts, so FMOD feeds zeros. Begin the dump only once a buffer actually has sound,
         * otherwise we capture 20s of silence and miss the real (noisy) music. */
        int hasSound = 0;
        {
            const int16_t *q = (const int16_t *) buf;
            size_t ns = (size_t) size * (size_t) pcm->channels;
            for (size_t i = 0; i < ns; i += 8) { if (q[i]) { hasSound = 1; break; } }
        }
        if (!dumpf && hasSound) {
            /* Write to the PUBLIC Downloads dir so the user can pull it with any file manager
             * (HOME is app-private /data/data and not user-accessible without root). */
            char path[512];
            snprintf(path, sizeof(path), "/sdcard/Download/fmod_dump.raw");
            dumpf = fopen(path, "wb");
            if (!dumpf) {  /* fallback to HOME if Downloads is not writable */
                const char *home = getenv("HOME");
                snprintf(path, sizeof(path), "%s/fmod_dump.raw", home ? home : "/data/local/tmp");
                dumpf = fopen(path, "wb");
            }
            LOGI("AUDIO DUMP %s → %s (frameBytes=%d ch=%d alsaFmt=%d) [started at first non-silent buffer]",
                 dumpf ? "open" : "FAILED", path, (int)pcm->frameBytes, (int)pcm->channels, (int)pcm->fmt);
        }
        if (dumpf) {
            fwrite(buf, (size_t) pcm->frameBytes, (size_t) size, dumpf);
            dumpFrames += (long) size;
            if (dumpFrames >= 48000L * 20) { fflush(dumpf); fclose(dumpf); dumpf = NULL; dumpDone = 1; LOGI("AUDIO DUMP done (~20s)"); }
        }
    }
    /* ===== DIAGNOSTIC 1b: log PCM STATISTICS (no Audacity needed). Reveals the corruption character:
     * flipRate≈0.5 + high RMS = white noise; low flipRate w/ recognizable peaks = tonal/music; high clip%
     * = saturation/wrap bug; high zero% chunks = dropouts/race. Treats data as S16 (FMOD outputs S16). */
    if (getenv("RIMDROID_AUDIO_DUMP") && pcm->fmt == AAUDIO_FORMAT_PCM_I16 && (wcount % 200) == 0) {
        const int16_t *s = (const int16_t *) buf;
        size_t n = (size_t) size * (size_t) pcm->channels;
        long long sum = 0, sumsq = 0; int mn = 32767, mx = -32768;
        long clip = 0, zero = 0, flips = 0; int prevSign = 0;
        for (size_t i = 0; i < n; i++) {
            int v = s[i];
            sum += v; sumsq += (long long) v * v;
            if (v < mn) mn = v; if (v > mx) mx = v;
            if (v > 32000 || v < -32000) clip++;
            if (v == 0) zero++;
            int sign = (v > 0) ? 1 : (v < 0 ? -1 : 0);
            if (sign && prevSign && sign != prevSign) flips++;
            if (sign) prevSign = sign;
        }
        double rms = n ? sqrt((double) sumsq / (double) n) : 0;
        LOGI("PCM stats #%ld: absMax=%d rms=%.0f clip%%=%.1f zero%%=%.1f flipRate=%.3f DC=%.0f (n=%zu)",
             wcount, (mx > -mn ? mx : -mn), rms,
             100.0 * clip / (double) n, 100.0 * zero / (double) n, (double) flips / (double) n,
             (double) sum / (double) n, n);
    }

    /* ===== DIAGNOSTIC 2: replace FMOD's audio with a clean 440Hz sine (env RIMDROID_AUDIO_SINE=1) to test our
     * AAudio output path in isolation. Clean tone = AAudio/shim path is correct → the garbage is FMOD's data. */
    void *sinebuf = NULL;
    if (getenv("RIMDROID_AUDIO_SINE")) {
        static double phase = 0.0;
        double inc = RD_TWO_PI * 440.0 / (double) (pcm->rate ? pcm->rate : 48000);
        if (pcm->fmt == AAUDIO_FORMAT_PCM_FLOAT) {
            float *sb = (float *) malloc((size_t) size * pcm->channels * sizeof(float));
            if (sb) { for (size_t i = 0; i < size; i++) { float v = (float) (sin(phase) * 0.25); phase += inc; if (phase > RD_TWO_PI) phase -= RD_TWO_PI; for (int c = 0; c < pcm->channels; c++) sb[i*pcm->channels+c] = v; } sinebuf = sb; buf = sb; }
        } else {
            int16_t *sb = (int16_t *) malloc((size_t) size * pcm->channels * sizeof(int16_t));
            if (sb) { for (size_t i = 0; i < size; i++) { int16_t v = (int16_t) (sin(phase) * 8000.0); phase += inc; if (phase > RD_TWO_PI) phase -= RD_TWO_PI; for (int c = 0; c < pcm->channels; c++) sb[i*pcm->channels+c] = v; } sinebuf = sb; buf = sb; }
        }
    }

    /* AAudio's actual stream format may differ from what FMOD writes (e.g. flagship devices give FLOAT
     * even when we ask for I16). Feeding raw I16 bytes to a FLOAT stream = ear-splitting noise. Convert. */
    const void *data = buf;
    void *conv = NULL;
    int outFrameBytes = pcm->frameBytes;
    if (pcm->actualFmt != pcm->fmt) {
        size_t nsamp = (size_t) size * (size_t) pcm->channels;
        if (pcm->fmt == AAUDIO_FORMAT_PCM_I16 && pcm->actualFmt == AAUDIO_FORMAT_PCM_FLOAT) {
            conv = malloc(nsamp * sizeof(float));
            if (!conv) return -5;
            const int16_t *si = (const int16_t *) buf;
            float *fo = (float *) conv;
            for (size_t i = 0; i < nsamp; i++) fo[i] = (float) si[i] * (1.0f / 32768.0f);
            data = conv; outFrameBytes = (int) (sizeof(float) * pcm->channels);
        } else if (pcm->fmt == AAUDIO_FORMAT_PCM_FLOAT && pcm->actualFmt == AAUDIO_FORMAT_PCM_I16) {
            conv = malloc(nsamp * sizeof(int16_t));
            if (!conv) return -5;
            const float *fi = (const float *) buf;
            int16_t *so = (int16_t *) conv;
            for (size_t i = 0; i < nsamp; i++) {
                float v = fi[i]; if (v > 1.0f) v = 1.0f; else if (v < -1.0f) v = -1.0f;
                so[i] = (int16_t) (v * 32767.0f);
            }
            data = conv; outFrameBytes = (int) (sizeof(int16_t) * pcm->channels);
        }
        /* any other mismatch: fall through and write as-is (best effort) */
    }

    const uint8_t *p = (const uint8_t *) data;
    snd_pcm_uframes_t left = size;
    int reopened = 0;
    while (left > 0) {
        aaudio_result_t r = AAudioStream_write(pcm->stream, p, (int32_t)left, 1000000000L);
        if (r < 0) {
            if (r == AAUDIO_ERROR_DISCONNECTED && !reopened) {
                AAudioStream_close(pcm->stream); pcm->stream=NULL;
                if (open_aaudio(pcm)!=0) { free(conv); return -32 /*-EPIPE*/; }
                reopened = 1; continue;
            }
            LOGE("write: %s", AAudio_convertResultToText(r));
            free(conv);
            return -32 /*-EPIPE → FMOD will recover/prepare*/;
        }
        left -= r;
        p += (size_t) r * (size_t) outFrameBytes;
    }
    free(conv);
    free(sinebuf);
    return (snd_pcm_sframes_t) size;
}

EXPORT snd_pcm_sframes_t snd_pcm_readi(snd_pcm_t *pcm, void *buf, snd_pcm_uframes_t size) {
    /* No real capture — return the requested frames as silence so FMOD input doesn't error. */
    if (pcm && buf && size) memset(buf, 0, (size_t) size * (size_t) pcm->frameBytes);
    return (snd_pcm_sframes_t) size;
}

EXPORT snd_pcm_sframes_t snd_pcm_avail_update(snd_pcm_t *pcm) {
    /* report a period's worth as writable so FMOD feeds us; AAudio blocking-write does real flow control */
    return pcm ? (snd_pcm_sframes_t) pcm->period : 1024;
}
EXPORT snd_pcm_sframes_t snd_pcm_avail(snd_pcm_t *pcm) { return snd_pcm_avail_update(pcm); }
EXPORT int snd_pcm_wait(snd_pcm_t *pcm, int timeout) { (void)pcm; (void)timeout; return 1; }
EXPORT int snd_pcm_delay(snd_pcm_t *pcm, snd_pcm_sframes_t *delayp) {
    if (delayp) {
        if (pcm && pcm->stream) {
            int64_t d = AAudioStream_getFramesWritten(pcm->stream) - AAudioStream_getFramesRead(pcm->stream);
            *delayp = d > 0 ? (snd_pcm_sframes_t)d : 0;
        } else *delayp = 0;
    }
    return 0;
}
EXPORT int snd_pcm_recover(snd_pcm_t *pcm, int err, int silent) {
    (void)err; (void)silent;
    if (pcm) { if (!pcm->stream) open_aaudio(pcm); pcm->state = SND_PCM_STATE_PREPARED; }
    return 0;
}

/* ============================ enumeration / misc ============================ */
EXPORT int snd_pcm_hw_free(snd_pcm_t *pcm) { (void)pcm; LOGI("snd_pcm_hw_free"); return 0; }
EXPORT const char *snd_strerror(int e) { (void)e; return "rimdroid-alsa"; }
EXPORT int snd_card_next(int *card) { if (card) *card = -1; return 0; }   /* no hw cards → FMOD uses "default" */
EXPORT int snd_lib_error_set_handler(void *h) { (void)h; return 0; }
EXPORT int snd_config_update_free_global(void) { return 0; }

/* device name hints: advertise exactly one PCM, "default" */
EXPORT int snd_device_name_hint(int card, const char *iface, void ***hints) {
    (void)card; LOGI("snd_device_name_hint(%s)", iface ? iface : "(null)");
    if (!hints) return -22;
    void **arr = (void **) calloc(2, sizeof(void *));
    if (!arr) return -12;
    arr[0] = (void *) "default";   /* sentinel; get_hint below ignores the value and returns fixed strings */
    arr[1] = NULL;
    *hints = arr;
    return 0;
}
EXPORT char *snd_device_name_get_hint(const void *hint, const char *id) {
    (void)hint;
    if (id && strcmp(id, "NAME") == 0) return strdup("default");
    if (id && strcmp(id, "DESC") == 0) return strdup("Default Output (AAudio)");
    if (id && strcmp(id, "IOID") == 0) return strdup("Output");
    return NULL;
}
EXPORT int snd_device_name_free_hint(void **hints) { free(hints); return 0; }
