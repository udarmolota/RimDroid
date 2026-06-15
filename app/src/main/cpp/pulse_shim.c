/*
 * SPDX-License-Identifier: MIT
 *
 * libpulse shim (async client API, enumeration-only) — by udarmolota for RimDroid.
 * Copyright (c) 2026 udarmolota
 *
 * This single file is MIT-licensed (NOT the GPL-3.0 of the rest of RimDroid).
 *
 * WHY: RimWorld's FMOD PulseAudio output needs TWO libs — libpulse-simple.so.0 (pa_simple, the actual
 * PLAYBACK path, handled by pulse_simple_shim.c → AAudio) AND libpulse.so.0 (the async pa_context /
 * mainloop API used by FMOD's getNumDrivers / device ENUMERATION). Android has neither. Without
 * enumeration FMOD bails ("failed to get number of drivers", err 19) before it ever plays.
 *
 * This is a FAKE async pulse client that reports exactly ONE output sink ("default"). The trick that
 * avoids real threading/deadlock: every async op completes SYNCHRONOUSLY — pa_context_connect sets the
 * state to READY and fires the state callback inline; pa_context_get_sink_info_list invokes the info
 * callback once (the sink) then once with eol=1 inline; operations report DONE immediately. FMOD's
 * "while (state != READY) pa_threaded_mainloop_wait()" loops therefore never wait. box64's wrappedpulse
 * wrapper marshals the x86_64↔native calls and wraps FMOD's emulated callbacks into native-callable
 * thunks, so here we just call the callback pointers normally.
 *
 * Built with soname "libpulse.so.0" and preloaded by name (RimDroidApplication) so box64's
 * dlopen("libpulse.so.0") resolves to it. Heavily logged for the first bring-up iterations.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "RimDroid/pa-ctx"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* ---- PulseAudio enums (stable ABI values) ---- */
enum { PA_CONTEXT_UNCONNECTED=0, PA_CONTEXT_CONNECTING, PA_CONTEXT_AUTHORIZING,
       PA_CONTEXT_SETTING_NAME, PA_CONTEXT_READY, PA_CONTEXT_FAILED, PA_CONTEXT_TERMINATED };
enum { PA_OPERATION_RUNNING=0, PA_OPERATION_DONE, PA_OPERATION_CANCELLED };
enum { PA_SAMPLE_S16LE = 3 };

/* ---- ABI-identical structs (natural layout matches PulseAudio on LP64) ---- */
typedef struct { int format; uint32_t rate; uint8_t channels; } pa_sample_spec;
typedef struct { uint8_t channels; int map[32]; } pa_channel_map;
typedef struct { uint8_t channels; uint32_t values[32]; } pa_cvolume;

typedef struct pa_sink_info {
    const char *name;
    uint32_t index;
    const char *description;
    pa_sample_spec sample_spec;
    pa_channel_map channel_map;
    uint32_t owner_module;
    pa_cvolume volume;
    int mute;
    uint32_t monitor_source;
    const char *monitor_source_name;
    uint64_t latency;
    const char *driver;
    int flags;
    void *proplist;
    uint64_t configured_latency;
    uint32_t base_volume;
    int state;
    uint32_t n_volume_steps;
    uint32_t card;
    uint32_t n_ports;
    void *ports;
    void *active_port;
    uint8_t n_formats;
    void *formats;
} pa_sink_info;

typedef struct pa_server_info {
    const char *user_name;
    const char *host_name;
    const char *server_version;
    const char *server_name;
    pa_sample_spec sample_spec;
    const char *default_sink_name;
    const char *default_source_name;
    uint32_t cookie;
    pa_channel_map channel_map;
} pa_server_info;

/* ---- callback typedefs (opaque context as void*) ---- */
typedef void (*pa_context_notify_cb_t)(void *c, void *userdata);
typedef void (*pa_sink_info_cb_t)(void *c, const pa_sink_info *i, int eol, void *userdata);
typedef void (*pa_server_info_cb_t)(void *c, const pa_server_info *i, void *userdata);
typedef void (*pa_context_success_cb_t)(void *c, int success, void *userdata);

/* ---- our fake objects ---- */
typedef struct {
    int state;
    pa_context_notify_cb_t state_cb;
    void *state_ud;
} fake_ctx;

static int g_dummy_api;     /* pa_mainloop_api* — opaque, never dereferenced by us */
static int g_dummy_ml;      /* (threaded) mainloop handle */
static int g_done_op;       /* pa_operation* — always reports DONE */
static int g_proplist;      /* pa_proplist* */

static pa_sink_info   g_sink;
static pa_server_info g_server;

static void fill_default_devices(void) {
    memset(&g_sink, 0, sizeof(g_sink));
    g_sink.name = "default";
    g_sink.index = 0;
    g_sink.description = "Default Output (AAudio)";
    g_sink.sample_spec.format = PA_SAMPLE_S16LE;
    g_sink.sample_spec.rate = 48000;
    g_sink.sample_spec.channels = 2;
    g_sink.channel_map.channels = 2;
    g_sink.channel_map.map[0] = 1;   /* PA_CHANNEL_POSITION_FRONT_LEFT */
    g_sink.channel_map.map[1] = 2;   /* PA_CHANNEL_POSITION_FRONT_RIGHT */
    g_sink.volume.channels = 2;
    g_sink.volume.values[0] = 0x10000;  /* PA_VOLUME_NORM */
    g_sink.volume.values[1] = 0x10000;
    g_sink.base_volume = 0x10000;
    g_sink.n_volume_steps = 65537;
    g_sink.driver = "aaudio";
    g_sink.monitor_source_name = "default.monitor";

    memset(&g_server, 0, sizeof(g_server));
    g_server.user_name = "android";
    g_server.host_name = "android";
    g_server.server_version = "15.0.0";
    g_server.server_name = "rimdroid-aaudio";
    g_server.sample_spec.format = PA_SAMPLE_S16LE;
    g_server.sample_spec.rate = 48000;
    g_server.sample_spec.channels = 2;
    g_server.default_sink_name = "default";
    g_server.default_source_name = "default";
    g_server.channel_map.channels = 2;
    g_server.channel_map.map[0] = 1;
    g_server.channel_map.map[1] = 2;
}

#define EXPORT __attribute__((visibility("default")))

/* ============================ threaded mainloop ============================ */
EXPORT void *pa_threaded_mainloop_new(void) { LOGI("threaded_mainloop_new"); return &g_dummy_ml; }
EXPORT int   pa_threaded_mainloop_start(void *m) { (void)m; LOGI("threaded_mainloop_start"); return 0; }
EXPORT void  pa_threaded_mainloop_stop(void *m) { (void)m; LOGI("threaded_mainloop_stop"); }
EXPORT void  pa_threaded_mainloop_free(void *m) { (void)m; LOGI("threaded_mainloop_free"); }
EXPORT void  pa_threaded_mainloop_lock(void *m) { (void)m; }
EXPORT void  pa_threaded_mainloop_unlock(void *m) { (void)m; }
EXPORT void  pa_threaded_mainloop_wait(void *m) { (void)m; /* sync completion → must never block */ }
EXPORT void  pa_threaded_mainloop_signal(void *m, int wait) { (void)m; (void)wait; }
EXPORT void  pa_threaded_mainloop_accept(void *m) { (void)m; }
EXPORT void *pa_threaded_mainloop_get_api(void *m) { (void)m; return &g_dummy_api; }
EXPORT int   pa_threaded_mainloop_in_thread(void *m) { (void)m; return 0; }
EXPORT void  pa_threaded_mainloop_set_name(void *m, const char *n) { (void)m; (void)n; }
EXPORT int   pa_threaded_mainloop_get_retval(void *m) { (void)m; return 0; }

/* ============================ plain mainloop (fallback) ============================ */
EXPORT void *pa_mainloop_new(void) { LOGI("mainloop_new"); return &g_dummy_ml; }
EXPORT void  pa_mainloop_free(void *m) { (void)m; }
EXPORT void *pa_mainloop_get_api(void *m) { (void)m; return &g_dummy_api; }
EXPORT int   pa_mainloop_iterate(void *m, int block, int *ret) { (void)m; (void)block; if (ret) *ret = 0; return 0; }
EXPORT int   pa_mainloop_run(void *m, int *ret) { (void)m; if (ret) *ret = 0; return 0; }
EXPORT void  pa_mainloop_quit(void *m, int r) { (void)m; (void)r; }

/* ============================ context ============================ */
EXPORT void *pa_context_new(void *api, const char *name) {
    (void)api; LOGI("context_new(%s)", name ? name : "(null)");
    fill_default_devices();
    fake_ctx *c = (fake_ctx *) calloc(1, sizeof(*c));
    if (c) c->state = PA_CONTEXT_UNCONNECTED;
    return c;
}
EXPORT void *pa_context_new_with_proplist(void *api, const char *name, void *p) {
    (void)p; return pa_context_new(api, name);
}
EXPORT void pa_context_set_state_callback(void *c, pa_context_notify_cb_t cb, void *ud) {
    LOGI("context_set_state_callback");
    fake_ctx *fc = (fake_ctx *) c;
    if (fc) { fc->state_cb = cb; fc->state_ud = ud; }
}
EXPORT int pa_context_connect(void *c, const char *server, int flags, const void *api) {
    (void)server; (void)flags; (void)api;
    LOGI("context_connect → READY (sync)");
    fake_ctx *fc = (fake_ctx *) c;
    if (fc) {
        fc->state = PA_CONTEXT_READY;
        if (fc->state_cb) fc->state_cb(c, fc->state_ud);   /* fire inline; FMOD then sees READY */
    }
    return 0;
}
EXPORT int pa_context_get_state(void *c) {
    fake_ctx *fc = (fake_ctx *) c;
    return fc ? fc->state : PA_CONTEXT_READY;
}
EXPORT void pa_context_disconnect(void *c) { (void)c; LOGI("context_disconnect"); }
EXPORT void pa_context_unref(void *c) { LOGI("context_unref"); free(c); }
EXPORT void *pa_context_ref(void *c) { return c; }
EXPORT int pa_context_errno(void *c) { (void)c; return 0; }
EXPORT uint32_t pa_context_get_index(void *c) { (void)c; return 0; }

EXPORT void *pa_context_get_sink_info_list(void *c, pa_sink_info_cb_t cb, void *ud) {
    LOGI("get_sink_info_list → 1 sink + eol (sync)");
    if (cb) { cb(c, &g_sink, 0, ud); cb(c, NULL, 1, ud); }
    return &g_done_op;
}
EXPORT void *pa_context_get_sink_info_by_name(void *c, const char *name, pa_sink_info_cb_t cb, void *ud) {
    (void)name; LOGI("get_sink_info_by_name(%s)", name ? name : "(null)");
    if (cb) { cb(c, &g_sink, 0, ud); cb(c, NULL, 1, ud); }
    return &g_done_op;
}
EXPORT void *pa_context_get_sink_info_by_index(void *c, uint32_t idx, pa_sink_info_cb_t cb, void *ud) {
    (void)idx; LOGI("get_sink_info_by_index(%u)", idx);
    if (cb) { cb(c, &g_sink, 0, ud); cb(c, NULL, 1, ud); }
    return &g_done_op;
}
EXPORT void *pa_context_get_server_info(void *c, pa_server_info_cb_t cb, void *ud) {
    LOGI("get_server_info (sync)");
    if (cb) cb(c, &g_server, ud);
    return &g_done_op;
}

/* ============================ operation ============================ */
EXPORT int  pa_operation_get_state(void *o) { (void)o; return PA_OPERATION_DONE; }
EXPORT void pa_operation_unref(void *o) { (void)o; }
EXPORT void *pa_operation_ref(void *o) { return o; }
EXPORT void pa_operation_cancel(void *o) { (void)o; }
EXPORT void pa_operation_set_state_callback(void *o, void *cb, void *ud) { (void)o; (void)cb; (void)ud; }

/* ============================ misc ============================ */
EXPORT void *pa_proplist_new(void) { return &g_proplist; }
EXPORT void  pa_proplist_free(void *p) { (void)p; }
EXPORT int   pa_proplist_sets(void *p, const char *k, const char *v) { (void)p; (void)k; (void)v; return 0; }
EXPORT const char *pa_strerror(int e) { (void)e; return "OK"; }
EXPORT const char *pa_get_library_version(void) { return "15.0.0"; }
