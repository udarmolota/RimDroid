#ifndef RIMDROID_H
#define RIMDROID_H

#include <android/native_window.h>

/**
 * Called from JNI before startGame.
 * Reads RIMDROID_RENDERER env var, initialises surface state.
 */
int rimdroid_init();

/**
 * Called from JNI on activity destroy.
 */
void rimdroid_deinit();

/**
 * Main entry point: initialise box64 namespace, load linker hook,
 * then exec RimWorldLinux ELF via box64.
 *
 * @param game_dir_path      absolute path to RimWorld instance dir (cwd)
 * @param library_dir_path   colon-separated ARM64 native lib search paths
 * @param argc               count of extra args for RimWorldLinux
 * @param argv               extra args (usually NULL)
 */
void rimdroid_start_game(const char* game_dir_path,
                         const char* library_dir_path,
                         int argc,
                         const char** argv);

void rimdroid_surface_init(ANativeWindow* wnd, int width, int height);
void rimdroid_surface_deinit();

/**
 * Software-renderer smoke test (OSMesa + softpipe). Renders a test frame into a CPU
 * buffer via libOSMesa.so and blits it to the current surface. Proves the CPU GL path
 * works before the box64 SDL interception is wired. Returns 0 on success.
 * @param osmesa_lib_path absolute path to libOSMesa.so (in the deps dir)
 */
int rimdroid_osmesa_smoketest(const char* osmesa_lib_path);

/**
 * OSMesa (softpipe) PERSISTENT software-renderer path (Milestone 2). Wired into
 * the game: box64's my2_SDL_GL_* handlers drive these when RD_SOFTPIPE is active.
 * rimdroid_init_osmesa() loads libOSMesa + creates a CORE 3.3 context (call once
 * at launch). make_current binds the context + CPU buffer to the calling thread;
 * swap blits the buffer to the surface. The g_osmesa_context / g_osmesa_handle
 * globals are weak-referenced by box64 (wrappedsdl2.c) to select the softpipe path.
 */
int  rimdroid_init_osmesa(void);
int  rimdroid_osmesa_make_current(void);
void rimdroid_osmesa_swap(void);

/* Phase A input injection (defined in rimdroid.c). The Android touch handler
 * pushes mouse events; box64's my2_SDL_PollEvent drains them via rd_input_poll. */
void rd_input_mouse_motion(int x, int y);
void rd_input_mouse_button(int button, int down, int x, int y);
void rd_input_mouse_scroll(int dy);      /* SDL mouse wheel: +up / -down (zoom) */
void rd_input_key(int scancode, int keycode, int down);  /* SDL key down/up */
void rd_input_text(const char* utf8);    /* SDL text input (typing) */
int  rd_input_poll(unsigned char* out);  /* out >= 56 bytes; returns 1 if filled */
unsigned int rd_input_get_mouse(int* x, int* y);  /* current cursor + SDL button mask */

/**
 * NO-FORK entry point for the standalone exec'd binary (librimdroid_exec.so).
 * Runs box64+RimWorld in a fresh process (clean address space, fresh binder) so
 * the GPU context can be created+used without fork.  See rimdroid.c for details.
 */
int rimdroid_run_standalone(const char* game_dir_path,
                            const char* library_dir_path,
                            int argc,
                            const char** argv);

#endif // RIMDROID_H
