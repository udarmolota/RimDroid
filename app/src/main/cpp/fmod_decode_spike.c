// fmod_decode_spike.c — SPIKE: on-device offline FSB5-Vorbis -> PCM WAV via native FMOD.
//
// Proves we can decode the user's OWN embedded RimWorld AudioClips (FSB5-Vorbis) to clean
// PCM ON THE DEVICE using the bundled native ARM64 libfmod.so — bypassing box64's broken
// Vorbis decode. This is the de-risk for an on-device "generate sound pack from your own
// game files" feature (no redistribution: the user's files, decoded locally).
//
// Throwaway spike: librimdroid exports one JNI method; Java passes the FSB blob path, the
// libfmod.so path, an output WAV path, the clip's sample rate, and the FMOD version of that
// lib. We dlopen/dlsym FMOD, create a NOSOUND system, CreateSound(OPENMEMORY|CREATESAMPLE),
// lock the decoded PCM, write a WAV. Returns a human-readable status string.

#include <jni.h>
#include <dlfcn.h>
#include <android/dlext.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "fmod_min.h"

#define TAG "RimDroid/FmodSpike"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Little-endian WAV writer. audioFormat: 1=PCM int, 3=IEEE float.
static int write_wav(const char *path, const void *pcm1, unsigned int len1,
                     const void *pcm2, unsigned int len2,
                     int channels, int sampleRate, int bits, int audioFormat) {
    FILE *f = fopen(path, "wb");
    if (!f) return -1;
    unsigned int dataLen = len1 + len2;
    unsigned int byteRate = (unsigned int)sampleRate * channels * (bits / 8);
    unsigned short blockAlign = (unsigned short)(channels * (bits / 8));
    unsigned int chunkSize = 36 + dataLen;
    unsigned int fmtSize = 16;
    unsigned short af = (unsigned short)audioFormat;
    unsigned short ch = (unsigned short)channels;
    unsigned short bps = (unsigned short)bits;
    unsigned int sr = (unsigned int)sampleRate;

    fwrite("RIFF", 1, 4, f); fwrite(&chunkSize, 4, 1, f); fwrite("WAVE", 1, 4, f);
    fwrite("fmt ", 1, 4, f); fwrite(&fmtSize, 4, 1, f);
    fwrite(&af, 2, 1, f); fwrite(&ch, 2, 1, f); fwrite(&sr, 4, 1, f);
    fwrite(&byteRate, 4, 1, f); fwrite(&blockAlign, 2, 1, f); fwrite(&bps, 2, 1, f);
    fwrite("data", 1, 4, f); fwrite(&dataLen, 4, 1, f);
    if (pcm1 && len1) fwrite(pcm1, 1, len1, f);
    if (pcm2 && len2) fwrite(pcm2, 1, len2, f);
    fclose(f);
    return 0;
}


// ---------------------------------------------------------------------------
// Real-feature decode path: decode ONE clip from a slice of resources.resource,
// downmix to mono, resample to targetRate, write a 16-bit mono WAV. The FMOD system
// is created once and reused across the (thousands of) clips. Returns 0 on success.
// ---------------------------------------------------------------------------
// Read [offset, offset+size) of a file into a malloc'd buffer with 64 zero pad bytes (caller frees).
// The pad guards against FMOD's FSB reader over-reading a few bytes past the logical end (alignment),
// which crashed when the buffer ended exactly on a page boundary.
#define SLICE_PAD 64
static char *read_slice(const char *path, long long offset, long long size) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    if (fseeko(f, (off_t)offset, SEEK_SET) != 0) { fclose(f); return NULL; }
    char *buf = (char *)malloc((size_t)size + SLICE_PAD);
    if (!buf) { fclose(f); return NULL; }
    size_t rd = fread(buf, 1, (size_t)size, f);
    fclose(f);
    if ((long long)rd != size) { free(buf); return NULL; }
    memset(buf + size, 0, SLICE_PAD);
    return buf;
}

JNIEXPORT jint JNICALL
Java_com_rimdroid_audio_FmodDecodeSpike_nativeDecodeClip(
        JNIEnv *env, jclass clazz,
        jstring jFmodLib, jint fmodVersion, jstring jResource,
        jlong offset, jlong size, jstring jOutWav, jint srcRate, jint targetRate, jint outChannels) {

    const char *fmodLib = (*env)->GetStringUTFChars(env, jFmodLib, NULL);
    const char *resource = (*env)->GetStringUTFChars(env, jResource, NULL);
    const char *outWav  = (*env)->GetStringUTFChars(env, jOutWav, NULL);
    int rc = -100;
    void *h = NULL; char *blob = NULL; FMOD_SYSTEM *sys = NULL; FMOD_SOUND *snd = NULL; short *out16 = NULL;

    // Full FMOD lifecycle PER CALL (reusing one system across thousands of createSound/release crashed
    // libfmod). Symbols resolved each call (cheap, RTLD_NOLOAD). Mirrors the proven spike lifecycle.
    h = dlopen(fmodLib, RTLD_NOW | RTLD_NOLOAD);
    if (!h) { rc = -1; goto done; }
    pfn_System_Create    f_create    = (pfn_System_Create)    dlsym(h, "FMOD_System_Create");
    pfn_System_SetOutput f_setoutput = (pfn_System_SetOutput) dlsym(h, "FMOD_System_SetOutput");
    pfn_System_Init      f_init      = (pfn_System_Init)      dlsym(h, "FMOD_System_Init");
    pfn_System_Close     f_close     = (pfn_System_Close)     dlsym(h, "FMOD_System_Close");
    pfn_System_Release   f_release   = (pfn_System_Release)   dlsym(h, "FMOD_System_Release");
    pfn_System_CreateSound f_cs      = (pfn_System_CreateSound)    dlsym(h, "FMOD_System_CreateSound");
    pfn_Sound_GetNumSubSounds f_nsub = (pfn_Sound_GetNumSubSounds) dlsym(h, "FMOD_Sound_GetNumSubSounds");
    pfn_Sound_GetSubSound f_sub      = (pfn_Sound_GetSubSound)     dlsym(h, "FMOD_Sound_GetSubSound");
    pfn_Sound_GetLength  f_len       = (pfn_Sound_GetLength)  dlsym(h, "FMOD_Sound_GetLength");
    pfn_Sound_GetFormat  f_fmt       = (pfn_Sound_GetFormat)  dlsym(h, "FMOD_Sound_GetFormat");
    pfn_Sound_Lock       f_lock      = (pfn_Sound_Lock)       dlsym(h, "FMOD_Sound_Lock");
    pfn_Sound_Unlock     f_unlock    = (pfn_Sound_Unlock)     dlsym(h, "FMOD_Sound_Unlock");
    pfn_Sound_Release    f_srel      = (pfn_Sound_Release)    dlsym(h, "FMOD_Sound_Release");
    if (!f_create || !f_init || !f_cs || !f_len || !f_lock) { rc = -2; goto done; }

    blob = read_slice(resource, offset, size);
    if (!blob) { rc = -3; goto done; }
    if (f_create(&sys, (unsigned int)fmodVersion) != FMOD_OK) { rc = -4; goto done; }
    if (f_setoutput) f_setoutput(sys, FMOD_OUTPUTTYPE_NOSOUND);
    if (f_init(sys, 8, FMOD_INIT_NORMAL, NULL) != FMOD_OK) { rc = -5; goto done; }

    FMOD_CREATESOUNDEXINFO ex; memset(&ex, 0, sizeof(ex));
    ex.cbsize = (int)sizeof(ex); ex.length = (unsigned int)size;
    if (f_cs(sys, blob, FMOD_OPENMEMORY|FMOD_CREATESAMPLE|FMOD_LOOP_OFF|FMOD_ACCURATETIME, &ex, &snd) != FMOD_OK) { rc = -6; goto done; }

    FMOD_SOUND *t = snd; int numsub = 0;
    if (f_nsub) f_nsub(snd, &numsub);
    if (numsub > 0 && f_sub) { FMOD_SOUND *s = NULL; if (f_sub(snd, 0, &s) == FMOD_OK && s) t = s; }

    int ch = 0, bits = 0; FMOD_SOUND_FORMAT fmt = 0; FMOD_SOUND_TYPE typ = 0;
    if (f_fmt) f_fmt(t, &typ, &fmt, &ch, &bits);
    if (ch <= 0) ch = 1;
    unsigned int pcmBytes = 0;
    if (f_len(t, &pcmBytes, FMOD_TIMEUNIT_PCMBYTES) != FMOD_OK || pcmBytes == 0) { rc = -7; goto done; }

    void *p1 = NULL, *p2 = NULL; unsigned int l1 = 0, l2 = 0;
    if (f_lock(t, 0, pcmBytes, &p1, &p2, &l1, &l2) != FMOD_OK) { rc = -8; goto done; }

    // Source = FMOD decoded PCM: float32 (fmt 5) or int16 (fmt 2). Use the CONTIGUOUS locked region l1
    // only (CREATESAMPLE returns the whole buffer in p1; never over-read past l1). Downmix to mono,
    // resample to targetRate, float->int16.
    int bps = (fmt == FMOD_SOUND_FORMAT_PCMFLOAT) ? 4 : 2;
    long srcFrames = (long)l1 / (bps * ch);
    if (srcRate <= 0) srcRate = 44100;
    if (targetRate <= 0) targetRate = srcRate;
    long outFrames = (long)((double)srcFrames * targetRate / srcRate);
    if (outFrames < 1) outFrames = (srcFrames > 0) ? 1 : 0;
    int outCh = (outChannels == 2) ? 2 : 1;     // 1=mono downmix (SFX), 2=stereo (music)
    if (outFrames > 0) {
        out16 = (short *)malloc((size_t)outFrames * 2 * outCh);
        const float *pf = (const float *)p1;
        const short *ps = (const short *)p1;
        double ratio = (double)srcRate / targetRate;   // source frames per output frame
        // Box-filter decimation: average ALL source frames falling in each output frame's window.
        // This band-limits before downsampling — point-sampling (1-2 src frames) aliased high
        // frequencies back as harsh "scraping" noise, worst on short UI/transient SFX.
        for (long i = 0; i < outFrames; i++) {
            long a0 = (long)(i * ratio);
            long a1 = (long)((i + 1) * ratio);
            if (a1 <= a0) a1 = a0 + 1;          // always span >= 1 frame (covers upsampling too)
            if (a0 >= srcFrames) a0 = srcFrames - 1;
            if (a1 > srcFrames) a1 = srcFrames;
            for (int oc = 0; oc < outCh; oc++) {
                double acc = 0.0; long cnt = 0;
                for (long fr = a0; fr < a1; fr++) {
                    if (outCh == 1) {           // downmix all source channels to mono
                        double m = 0.0;
                        for (int c = 0; c < ch; c++)
                            m += (bps == 4) ? (double)pf[fr*ch + c] : (double)ps[fr*ch + c] / 32768.0;
                        acc += m / ch;
                    } else {                    // stereo out: map L/R (mono source -> duplicate)
                        int sc = (ch >= 2) ? oc : 0;
                        acc += (bps == 4) ? (double)pf[fr*ch + sc] : (double)ps[fr*ch + sc] / 32768.0;
                    }
                    cnt++;
                }
                double v = (cnt > 0) ? acc / cnt : 0.0;
                int s = (int)(v * 32767.0);
                if (s > 32767) s = 32767; else if (s < -32768) s = -32768;
                out16[i*outCh + oc] = (short)s;
            }
        }
    }
    f_unlock(t, p1, p2, l1, l2);
    if (write_wav(outWav, out16, (unsigned int)(outFrames * 2 * outCh), NULL, 0, outCh, targetRate, 16, 1) != 0) { rc = -9; goto done; }
    rc = 0;

done:
    if (snd && f_srel) f_srel(snd);
    if (sys && f_close)   f_close(sys);
    if (sys && f_release) f_release(sys);
    if (out16) free(out16);
    if (blob) free(blob);
    if (fmodLib) (*env)->ReleaseStringUTFChars(env, jFmodLib, fmodLib);
    if (resource) (*env)->ReleaseStringUTFChars(env, jResource, resource);
    if (outWav) (*env)->ReleaseStringUTFChars(env, jOutWav, outWav);
    return rc;
}
