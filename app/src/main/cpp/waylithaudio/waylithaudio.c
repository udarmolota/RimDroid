// libwaylithaudio.so for RimWorld 1.7 on Android: the game's audio API (wa_*, P/Invoke from
// Waylith.Audio.MiniAudio.WaylithAudioNative), rebuilt on miniaudio 0.11.22 (public domain / MIT-0,
// miniaudio/) with stb_vorbis for Ogg. Output goes through AAudio or OpenSL ES, whichever miniaudio
// finds.
//
// How the game uses it (read from Waylith.dll / UnityStubs.dll):
//  - wa_init(48000, 2, 0) once; 0 = success.
//  - Sounds come from Assets.zip: whole clips are decoded here into 16-bit PCM
//    (wa_sound_load_memory), music is streamed from a copy of the file (wa_sound_load_memory_stream),
//    PCM can also be handed over directly (wa_sound_load_pcm, copy flag = 1).
//  - A sound plays through a voice: AudioSource.Play calls wa_voice_play(sound, group, 1, 1) — the
//    voice starts paused — then sets volume, pitch, loop, 3D, start time, and unpauses it.
//  - There is no "release voice": the game stops voices or forgets finished one-shots, so finished
//    voices are freed in wa_update and their handles just stop answering.
//  - Filters and effects (low/high pass, echo, reverb) answer -3, which the game accepts as "fine".
// Unity's coordinates are left-handed and miniaudio's right-handed: z is negated for the listener
// and voice positions so left and right stay where they belong.
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define STB_VORBIS_HEADER_ONLY
#include "miniaudio/stb_vorbis.c"
#define MINIAUDIO_IMPLEMENTATION
#define MA_NO_ENCODING
#define MA_NO_GENERATION
#include "miniaudio/miniaudio.h"
#undef STB_VORBIS_HEADER_ONLY
#include "miniaudio/stb_vorbis.c"

#define API __attribute__((visibility("default")))
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RimDroid/audio", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "RimDroid/audio", __VA_ARGS__)

#define WA_UNSUPPORTED (-3)   // Voice.Ok() takes 0 and -3 as success

// ---- handles: (generation << 32) | (slot + 1), so a stale handle never reaches a reused slot ----

typedef struct { void** items; uint32_t* gens; uint32_t cap; } Table;

static uint64_t tbl_add(Table* t, void* p) {
    uint32_t i = 0;
    while (i < t->cap && t->items[i]) i++;
    if (i == t->cap) {
        uint32_t cap = t->cap ? t->cap * 2 : 64;
        void** items = realloc(t->items, cap * sizeof(void*));
        uint32_t* gens = realloc(t->gens, cap * sizeof(uint32_t));
        if (!items || !gens) { if (items) t->items = items; if (gens) t->gens = gens; return 0; }
        memset(items + t->cap, 0, (cap - t->cap) * sizeof(void*));
        for (uint32_t k = t->cap; k < cap; k++) gens[k] = 1;
        t->items = items; t->gens = gens; t->cap = cap;
    }
    t->items[i] = p;
    return ((uint64_t)t->gens[i] << 32) | (uint64_t)(i + 1);
}
static void* tbl_get(Table* t, uint64_t h) {
    uint32_t i = (uint32_t)h - 1, g = (uint32_t)(h >> 32);
    if (!h || i >= t->cap || t->gens[i] != g) return NULL;
    return t->items[i];
}
static void tbl_remove(Table* t, uint64_t h) {
    uint32_t i = (uint32_t)h - 1;
    if (!h || i >= t->cap || t->gens[i] != (uint32_t)(h >> 32)) return;
    t->items[i] = NULL;
    t->gens[i]++;
}

// ---- state ----

enum { KIND_PCM = 0, KIND_STREAM_MEMORY = 1, KIND_STREAM_FILE = 2, KIND_USER = 3 };

typedef int (*wa_read_fn)(void* user, float* buffer, uint32_t frames);
typedef int (*wa_seek_fn)(void* user, uint64_t frame);

typedef struct Sound {
    int kind;
    uint32_t channels, rate;
    uint64_t frames;
    int16_t* pcm; int ownsPcm;            // KIND_PCM
    void* mem; size_t memSize;            // KIND_STREAM_MEMORY (own copy)
    char* path;                           // KIND_STREAM_FILE
    wa_read_fn read; wa_seek_fn seek; void* user;   // KIND_USER
    int refs, released;
} Sound;

typedef struct {
    ma_data_source_base base;
    Sound* sound;
    uint64_t cursor;
} UserSource;

typedef struct Group Group;
struct Group {
    ma_sound_group group;
    float volume;
    int muted;
};

typedef struct Voice {
    ma_sound snd;
    Sound* sound;
    struct Group* group;                  // where its output goes (NULL: straight to the output)
    ma_audio_buffer_ref buffer;
    ma_decoder decoder; int hasDecoder;
    UserSource user;
    uint64_t handle;
    float volume;
    int muted, looping;
} Voice;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static ma_engine g_engine;
static int g_ready, g_error;
static Table g_sounds, g_voices, g_groups;

static void lock(void) { pthread_mutex_lock(&g_lock); }
static void unlock(void) { pthread_mutex_unlock(&g_lock); }

static void sound_free(Sound* s) {
    if (s->ownsPcm) free(s->pcm);
    free(s->mem);
    free(s->path);
    free(s);
}

static void voice_free(Voice* v) {
    ma_sound_uninit(&v->snd);
    if (v->hasDecoder) ma_decoder_uninit(&v->decoder);
    if (v->sound->kind == KIND_USER) ma_data_source_uninit(&v->user.base);
    if (v->sound->kind == KIND_PCM) ma_audio_buffer_ref_uninit(&v->buffer);
    Sound* s = v->sound;
    if (--s->refs == 0 && s->released) sound_free(s);
    tbl_remove(&g_voices, v->handle);
    free(v);
}

static int voice_finished(Voice* v) {
    return !v->looping && ma_sound_at_end(&v->snd);
}

// ---- the user-callback data source (CreateStreamSound: video audio) ----

static ma_result us_read(ma_data_source* ds, void* out, ma_uint64 frameCount, ma_uint64* framesRead) {
    UserSource* u = (UserSource*)ds;
    Sound* s = u->sound;
    float* o = (float*)out;
    ma_uint64 total = 0;
    while (total < frameCount) {
        uint32_t want = (uint32_t)(frameCount - total > 4096 ? 4096 : frameCount - total);
        int got = s->read(s->user, o + total * s->channels, want);
        if (got <= 0) break;
        uint64_t frames = (uint64_t)got / s->channels;   // the managed Read answers samples
        if (frames == 0) break;
        total += frames;
        if (frames < want) break;
    }
    u->cursor += total;
    if (framesRead) *framesRead = total;
    return total == 0 && frameCount > 0 ? MA_AT_END : MA_SUCCESS;
}
static ma_result us_seek(ma_data_source* ds, ma_uint64 frame) {
    UserSource* u = (UserSource*)ds;
    if (!u->sound->seek || u->sound->seek(u->sound->user, frame) != 0) return MA_ERROR;
    u->cursor = frame;
    return MA_SUCCESS;
}
static ma_result us_format(ma_data_source* ds, ma_format* f, ma_uint32* ch, ma_uint32* rate, ma_channel* map, size_t cap) {
    UserSource* u = (UserSource*)ds;
    *f = ma_format_f32; *ch = u->sound->channels; *rate = u->sound->rate;
    ma_channel_map_init_standard(ma_standard_channel_map_default, map, cap, u->sound->channels);
    return MA_SUCCESS;
}
static ma_result us_cursor(ma_data_source* ds, ma_uint64* c) { *c = ((UserSource*)ds)->cursor; return MA_SUCCESS; }
static ma_result us_length(ma_data_source* ds, ma_uint64* l) {
    *l = ((UserSource*)ds)->sound->frames;
    return *l ? MA_SUCCESS : MA_NOT_IMPLEMENTED;
}
static const ma_data_source_vtable g_userVtable = { us_read, us_seek, us_format, us_cursor, us_length, NULL, 0 };

// ---- engine ----

API void wa_set_allocator(void* alloc, void* realloc_, void* free_) { (void)alloc; (void)realloc_; (void)free_; }

API int wa_init(uint32_t sampleRate, uint32_t channels, int periodFrames) {
    lock();
    if (g_ready) { unlock(); return 0; }
    ma_engine_config c = ma_engine_config_init();
    c.sampleRate = sampleRate;
    c.channels = channels;
    if (periodFrames > 0) c.periodSizeInFrames = (ma_uint32)periodFrames;
    ma_result r = ma_engine_init(&c, &g_engine);
    if (r != MA_SUCCESS) {
        g_error = r;
        unlock();
        LOGW("ma_engine_init failed: %d", r);
        return r;
    }
    g_ready = 1;
    ma_device* d = ma_engine_get_device(&g_engine);
    LOGI("audio up: %u Hz, %u channels, period %u frames, backend %s", ma_engine_get_sample_rate(&g_engine),
         ma_engine_get_channels(&g_engine), d ? d->playback.internalPeriodSizeInFrames : 0,
         d ? ma_get_backend_name(d->pContext->backend) : "?");
    unlock();
    return 0;
}

API void wa_shutdown(void) {
    lock();
    if (!g_ready) { unlock(); return; }
    for (uint32_t i = 0; i < g_voices.cap; i++) if (g_voices.items[i]) voice_free(g_voices.items[i]);
    for (uint32_t i = 0; i < g_groups.cap; i++) if (g_groups.items[i]) {
        Group* g = g_groups.items[i];
        ma_sound_group_uninit(&g->group);
        free(g);
        g_groups.items[i] = NULL;
    }
    ma_engine_uninit(&g_engine);
    g_ready = 0;
    unlock();
}

API int wa_last_error(void) { return g_error; }
// 1.7.4995 polls these each frame to log a lost or restarted output device; miniaudio's AAudio
// backend reroutes on its own here.
API uint32_t wa_device_recoveries(void) { return 0; }
API uint32_t wa_device_lost(void) { return 0; }
API uint32_t wa_channels(void) { return g_ready ? ma_engine_get_channels(&g_engine) : 0; }
API uint32_t wa_sample_rate(void) { return g_ready ? ma_engine_get_sample_rate(&g_engine) : 0; }
API uint32_t wa_period_frames(void) {
    ma_device* d = g_ready ? ma_engine_get_device(&g_engine) : NULL;
    return d ? d->playback.internalPeriodSizeInFrames : 0;
}
API uint64_t wa_time_frames(void) { return g_ready ? ma_engine_get_time_in_pcm_frames(&g_engine) : 0; }

API void wa_set_master_volume(float v) { if (g_ready) ma_engine_set_volume(&g_engine, v); }
API void wa_set_master_paused(int paused) {
    if (!g_ready) return;
    if (paused) ma_engine_stop(&g_engine); else ma_engine_start(&g_engine);
}

API void wa_set_listener(float px, float py, float pz, float fx, float fy, float fz, float ux, float uy, float uz) {
    if (!g_ready) return;
    ma_engine_listener_set_position(&g_engine, 0, px, py, -pz);
    ma_engine_listener_set_direction(&g_engine, 0, fx, fy, -fz);
    ma_engine_listener_set_world_up(&g_engine, 0, ux, uy, -uz);
}

API void wa_update(void) {
    lock();
    for (uint32_t i = 0; i < g_voices.cap; i++) {
        Voice* v = g_voices.items[i];
        if (v && voice_finished(v)) voice_free(v);
    }
    unlock();
}

API int wa_playing_count(void) {
    int n = 0;
    lock();
    for (uint32_t i = 0; i < g_voices.cap; i++) {
        Voice* v = g_voices.items[i];
        if (v && !voice_finished(v) && ma_sound_is_playing(&v->snd)) n++;
    }
    unlock();
    return n;
}

// ---- sounds ----

static uint64_t add_sound(Sound* s) {
    lock();
    uint64_t h = tbl_add(&g_sounds, s);
    unlock();
    if (!h) sound_free(s);
    return h;
}

static int probe_decoder(ma_decoder* d, uint64_t* frames, uint32_t* channels, uint32_t* rate) {
    ma_format f;
    ma_uint32 ch = 0, sr = 0;
    ma_uint64 len = 0;
    if (ma_decoder_get_data_format(d, &f, &ch, &sr, NULL, 0) != MA_SUCCESS) return 0;
    if (ma_decoder_get_length_in_pcm_frames(d, &len) != MA_SUCCESS) len = 0;
    if (frames) *frames = len;
    if (channels) *channels = ch;
    if (rate) *rate = sr;
    return ch > 0 && sr > 0;
}

API int wa_probe_memory(const void* data, size_t size, uint64_t* frames, uint32_t* channels, uint32_t* rate) {
    ma_decoder_config c = ma_decoder_config_init(ma_format_f32, 0, 0);
    ma_decoder d;
    if (ma_decoder_init_memory(data, size, &c, &d) != MA_SUCCESS) return 0;
    int ok = probe_decoder(&d, frames, channels, rate);
    ma_decoder_uninit(&d);
    return ok;
}

API int wa_probe_file(const char* path, uint64_t* frames, uint32_t* channels, uint32_t* rate) {
    ma_decoder_config c = ma_decoder_config_init(ma_format_f32, 0, 0);
    ma_decoder d;
    if (ma_decoder_init_file(path, &c, &d) != MA_SUCCESS) return 0;
    int ok = probe_decoder(&d, frames, channels, rate);
    ma_decoder_uninit(&d);
    return ok;
}

// A whole clip, decoded to 16-bit PCM at its own rate and channel count.
API uint64_t wa_sound_load_memory(const void* data, size_t size) {
    ma_decoder_config c = ma_decoder_config_init(ma_format_s16, 0, 0);
    ma_decoder d;
    ma_result r = ma_decoder_init_memory(data, size, &c, &d);
    if (r != MA_SUCCESS) { g_error = r; return 0; }
    uint64_t known = 0;
    uint32_t ch = 0, sr = 0;
    if (!probe_decoder(&d, &known, &ch, &sr)) { ma_decoder_uninit(&d); g_error = MA_INVALID_FILE; return 0; }
    uint64_t cap = known ? known : sr, used = 0;
    int16_t* pcm = malloc((size_t)cap * ch * sizeof(int16_t));
    while (pcm) {
        if (used == cap) {
            cap *= 2;
            int16_t* grown = realloc(pcm, (size_t)cap * ch * sizeof(int16_t));
            if (!grown) { free(pcm); pcm = NULL; break; }
            pcm = grown;
        }
        ma_uint64 got = 0;
        r = ma_decoder_read_pcm_frames(&d, pcm + used * ch, cap - used, &got);
        used += got;
        if (r != MA_SUCCESS || got == 0) break;
    }
    ma_decoder_uninit(&d);
    if (!pcm) { g_error = MA_OUT_OF_MEMORY; return 0; }
    if (used == 0) { free(pcm); g_error = MA_INVALID_FILE; return 0; }
    Sound* s = calloc(1, sizeof(Sound));
    if (!s) { free(pcm); return 0; }
    s->kind = KIND_PCM; s->channels = ch; s->rate = sr; s->frames = used; s->pcm = pcm; s->ownsPcm = 1;
    return add_sound(s);
}

// Music: decoded while it plays, each voice with its own decoder over a private copy of the file.
API uint64_t wa_sound_load_memory_stream(const void* data, size_t size, int copy) {
    (void)copy;
    uint64_t frames = 0;
    uint32_t ch = 0, sr = 0;
    if (!wa_probe_memory(data, size, &frames, &ch, &sr)) { g_error = MA_INVALID_FILE; return 0; }
    Sound* s = calloc(1, sizeof(Sound));
    void* mem = malloc(size);
    if (!s || !mem) { free(s); free(mem); return 0; }
    memcpy(mem, data, size);
    s->kind = KIND_STREAM_MEMORY; s->channels = ch; s->rate = sr; s->frames = frames; s->mem = mem; s->memSize = size;
    return add_sound(s);
}

API uint64_t wa_sound_load_file_stream(const char* path) {
    uint64_t frames = 0;
    uint32_t ch = 0, sr = 0;
    if (!path || !wa_probe_file(path, &frames, &ch, &sr)) { g_error = MA_INVALID_FILE; return 0; }
    Sound* s = calloc(1, sizeof(Sound));
    if (!s) return 0;
    s->kind = KIND_STREAM_FILE; s->channels = ch; s->rate = sr; s->frames = frames; s->path = strdup(path);
    return add_sound(s);
}

API uint64_t wa_sound_load_pcm(const void* data, uint64_t frames, uint32_t channels, uint32_t rate, int copy) {
    if (!data || !frames || !channels || !rate) return 0;
    Sound* s = calloc(1, sizeof(Sound));
    if (!s) return 0;
    s->kind = KIND_PCM; s->channels = channels; s->rate = rate; s->frames = frames;
    if (copy) {
        size_t bytes = (size_t)frames * channels * sizeof(int16_t);
        s->pcm = malloc(bytes);
        if (!s->pcm) { free(s); return 0; }
        memcpy(s->pcm, data, bytes);
        s->ownsPcm = 1;
    } else {
        s->pcm = (int16_t*)data;
    }
    return add_sound(s);
}

API uint64_t wa_sound_create_user(uint32_t channels, uint32_t rate, uint64_t frames, void* read, void* seek, void* user) {
    if (!read || !channels || !rate) return 0;
    Sound* s = calloc(1, sizeof(Sound));
    if (!s) return 0;
    s->kind = KIND_USER; s->channels = channels; s->rate = rate; s->frames = frames;
    s->read = (wa_read_fn)read; s->seek = (wa_seek_fn)seek; s->user = user;
    return add_sound(s);
}

API void wa_sound_release(uint64_t h) {
    lock();
    Sound* s = tbl_get(&g_sounds, h);
    if (s) {
        tbl_remove(&g_sounds, h);
        s->released = 1;
        if (s->refs == 0) sound_free(s);
    }
    unlock();
}

#define SOUND_FIELD(name, type, expr) \
    API type name(uint64_t h) { lock(); Sound* s = tbl_get(&g_sounds, h); type v = s ? (expr) : 0; unlock(); return v; }
SOUND_FIELD(wa_sound_channels, uint32_t, s->channels)
SOUND_FIELD(wa_sound_rate, uint32_t, s->rate)
SOUND_FIELD(wa_sound_length_frames, uint64_t, s->frames)
SOUND_FIELD(wa_sound_kind, int, s->kind == KIND_PCM ? 0 : 1)
SOUND_FIELD(wa_sound_pcm, int16_t*, s->kind == KIND_PCM ? s->pcm : NULL)
// Added in 1.7 build 4981 (EntryPointNotFoundException, and DLC sounds failed to load without it):
// the bytes a sound keeps in memory. Decoded PCM, or the copied file for an in-memory stream.
SOUND_FIELD(wa_sound_resident_bytes, uint64_t, s->kind == KIND_PCM ? s->frames * s->channels * sizeof(int16_t)
            : s->kind == KIND_STREAM_MEMORY ? (uint64_t)s->memSize : 0)

// ---- groups ----

API uint64_t wa_group_create(uint64_t parent) {
    if (!g_ready) return 0;
    Group* g = calloc(1, sizeof(Group));
    if (!g) return 0;
    lock();
    Group* p = parent ? tbl_get(&g_groups, parent) : NULL;
    if (ma_sound_group_init(&g_engine, 0, p ? &p->group : NULL, &g->group) != MA_SUCCESS) { unlock(); free(g); return 0; }
    g->volume = 1.0f;
    uint64_t h = tbl_add(&g_groups, g);
    unlock();
    return h;
}

API void wa_group_release(uint64_t h) {
    lock();
    Group* g = tbl_get(&g_groups, h);
    if (g) {
        // Its voices go straight to the output from now on.
        for (uint32_t i = 0; i < g_voices.cap; i++) {
            Voice* v = g_voices.items[i];
            if (v && v->group == g) {
                ma_node_attach_output_bus(&v->snd, 0, ma_engine_get_endpoint(&g_engine), 0);
                v->group = NULL;
            }
        }
        ma_sound_group_uninit(&g->group);
        tbl_remove(&g_groups, h);
        free(g);
    }
    unlock();
}

#define WITH_GROUP(h, body) do { lock(); Group* g = tbl_get(&g_groups, h); if (g) { body; } unlock(); } while (0)
API void wa_group_set_volume(uint64_t h, float v) { WITH_GROUP(h, g->volume = v; if (!g->muted) ma_sound_group_set_volume(&g->group, v)); }
API void wa_group_set_mute(uint64_t h, int m) { WITH_GROUP(h, g->muted = m; ma_sound_group_set_volume(&g->group, m ? 0.0f : g->volume)); }
API void wa_group_set_pitch(uint64_t h, float p) { WITH_GROUP(h, ma_sound_group_set_pitch(&g->group, p > 0.01f ? p : 0.01f)); }
API void wa_group_set_paused(uint64_t h, int p) { WITH_GROUP(h, if (p) ma_sound_group_stop(&g->group); else ma_sound_group_start(&g->group)); }

// ---- voices ----

API uint64_t wa_voice_play(uint64_t soundHandle, uint64_t groupHandle, int startPaused, int startPaused2) {
    if (!g_ready) return 0;
    lock();
    Sound* s = tbl_get(&g_sounds, soundHandle);
    Group* g = groupHandle ? tbl_get(&g_groups, groupHandle) : NULL;
    Voice* v = s ? calloc(1, sizeof(Voice)) : NULL;
    if (!v) { unlock(); return 0; }
    v->sound = s;
    ma_data_source* source = NULL;
    ma_result r = MA_ERROR;
    ma_decoder_config dc = ma_decoder_config_init(ma_format_f32, 0, 0);
    switch (s->kind) {
        case KIND_PCM:
            r = ma_audio_buffer_ref_init(ma_format_s16, s->channels, s->pcm, s->frames, &v->buffer);
            v->buffer.sampleRate = s->rate;
            source = &v->buffer;
            break;
        case KIND_STREAM_MEMORY:
            r = ma_decoder_init_memory(s->mem, s->memSize, &dc, &v->decoder);
            v->hasDecoder = r == MA_SUCCESS;
            source = &v->decoder;
            break;
        case KIND_STREAM_FILE:
            r = ma_decoder_init_file(s->path, &dc, &v->decoder);
            v->hasDecoder = r == MA_SUCCESS;
            source = &v->decoder;
            break;
        case KIND_USER: {
            ma_data_source_config c = ma_data_source_config_init();
            c.vtable = &g_userVtable;
            v->user.sound = s;
            r = ma_data_source_init(&c, &v->user.base);
            source = &v->user.base;
            break;
        }
    }
    if (r == MA_SUCCESS)
        r = ma_sound_init_from_data_source(&g_engine, source, MA_SOUND_FLAG_NO_SPATIALIZATION, g ? &g->group : NULL, &v->snd);
    if (r != MA_SUCCESS) {
        if (v->hasDecoder) ma_decoder_uninit(&v->decoder);
        g_error = r;
        unlock();
        free(v);
        return 0;
    }
    s->refs++;
    v->group = g;
    v->volume = 1.0f;
    v->handle = tbl_add(&g_voices, v);
    if (!v->handle) { voice_free(v); unlock(); return 0; }
    if (!startPaused && !startPaused2) ma_sound_start(&v->snd);
    uint64_t h = v->handle;
    unlock();
    return h;
}

#define WITH_VOICE(h, body) do { lock(); Voice* v = tbl_get(&g_voices, h); if (v) { body; } unlock(); } while (0)

API void wa_voice_stop(uint64_t h) { WITH_VOICE(h, voice_free(v)); }
API int wa_voice_is_playing(uint64_t h) { int p = 0; WITH_VOICE(h, p = !voice_finished(v)); return p; }
API void wa_voice_set_paused(uint64_t h, int p) { WITH_VOICE(h, if (p) ma_sound_stop(&v->snd); else ma_sound_start(&v->snd)); }
API void wa_voice_set_volume(uint64_t h, float vol) { WITH_VOICE(h, v->volume = vol; if (!v->muted) ma_sound_set_volume(&v->snd, vol)); }
API void wa_voice_set_mute(uint64_t h, int m) { WITH_VOICE(h, v->muted = m; ma_sound_set_volume(&v->snd, m ? 0.0f : v->volume)); }
API void wa_voice_set_pitch(uint64_t h, float p) { WITH_VOICE(h, ma_sound_set_pitch(&v->snd, p > 0.01f ? p : 0.01f)); }
API void wa_voice_set_pan(uint64_t h, float pan) { WITH_VOICE(h, ma_sound_set_pan(&v->snd, pan)); }
API void wa_voice_set_loop(uint64_t h, int loop) { WITH_VOICE(h, v->looping = loop; ma_sound_set_looping(&v->snd, loop)); }
API void wa_voice_set_priority(uint64_t h, int p) { (void)h; (void)p; }
API void wa_voice_set_start_time(uint64_t h, uint64_t frames) { WITH_VOICE(h, ma_sound_set_start_time_in_pcm_frames(&v->snd, frames)); }
API void wa_voice_set_doppler(uint64_t h, float f) { WITH_VOICE(h, ma_sound_set_doppler_factor(&v->snd, f)); }

API void wa_voice_set_group(uint64_t h, uint64_t groupHandle) {
    WITH_VOICE(h, {
        Group* g = groupHandle ? tbl_get(&g_groups, groupHandle) : NULL;
        ma_node_attach_output_bus(&v->snd, 0, g ? (ma_node*)&g->group : ma_engine_get_endpoint(&g_engine), 0);
        v->group = g;
    });
}

// enabled: 3D at all (the game passes enabled && spatial blend > 0); linear: Unity's Linear rolloff,
// otherwise Logarithmic, which is miniaudio's inverse-distance model.
API void wa_voice_set_3d(uint64_t h, int enabled, float minDistance, float maxDistance, int linear) {
    WITH_VOICE(h, {
        ma_sound_set_spatialization_enabled(&v->snd, enabled ? MA_TRUE : MA_FALSE);
        if (enabled) {
            ma_sound_set_min_distance(&v->snd, minDistance > 0.0f ? minDistance : 0.01f);
            ma_sound_set_max_distance(&v->snd, maxDistance > minDistance ? maxDistance : minDistance + 0.01f);
            ma_sound_set_attenuation_model(&v->snd, linear ? ma_attenuation_model_linear : ma_attenuation_model_inverse);
            ma_sound_set_rolloff(&v->snd, 1.0f);
        }
    });
}
API void wa_voice_set_3d_position(uint64_t h, float x, float y, float z) { WITH_VOICE(h, ma_sound_set_position(&v->snd, x, y, -z)); }

API uint64_t wa_voice_get_position_frames(uint64_t h) {
    ma_uint64 c = 0;
    WITH_VOICE(h, ma_sound_get_cursor_in_pcm_frames(&v->snd, &c));
    return c;
}
API int wa_voice_set_position_frames(uint64_t h, uint64_t frame) {
    int r = -1;
    WITH_VOICE(h, r = ma_sound_seek_to_pcm_frame(&v->snd, frame) == MA_SUCCESS ? 0 : -1);
    return r;
}

API void wa_voice_set_mix_matrix(uint64_t h, const float* m, uint32_t in, uint32_t out) { (void)h; (void)m; (void)in; (void)out; }
API int wa_voice_set_lowpass(uint64_t h, int on, float cutoff, float q) { (void)h; (void)on; (void)cutoff; (void)q; return WA_UNSUPPORTED; }
API int wa_voice_set_highpass(uint64_t h, int on, float cutoff, float q) { (void)h; (void)on; (void)cutoff; (void)q; return WA_UNSUPPORTED; }
API int wa_voice_set_echo(uint64_t h, int on, float a, float b, float c, float d) {
    (void)h; (void)on; (void)a; (void)b; (void)c; (void)d; return WA_UNSUPPORTED;
}
API int wa_voice_set_reverb(uint64_t h, int on, float a, float b, float c, float d, float e, float f) {
    (void)h; (void)on; (void)a; (void)b; (void)c; (void)d; (void)e; (void)f; return WA_UNSUPPORTED;
}
// The output tap (reading back what a voice plays) is not offered: EnableOutputTap sees a failure.
API int wa_voice_tap_enable(uint64_t h, uint32_t frames) { (void)h; (void)frames; return -1; }
API int wa_voice_tap_read(uint64_t h, float* out, uint32_t frames, uint32_t channels) { (void)h; (void)out; (void)frames; (void)channels; return 0; }
