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
 * NO-FORK entry point for the standalone exec'd binary (librimdroid_exec.so).
 * Runs box64+RimWorld in a fresh process (clean address space, fresh binder) so
 * the GPU context can be created+used without fork.  See rimdroid.c for details.
 */
int rimdroid_run_standalone(const char* game_dir_path,
                            const char* library_dir_path,
                            int argc,
                            const char** argv);

#endif // RIMDROID_H
