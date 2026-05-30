// Standalone entry point for the exec'd box64+RimWorld process.
//
// Built as librimdroid_exec.so (the "lib*.so" name is the Android packaging
// trick that gets a real ELF EXECUTABLE extracted into the app's
// nativeLibraryDir with exec permission).  The Java launcher exec()s this
// instead of doing the in-process fork — a fresh process gives box64 a clean
// address space (RimWorld's fixed 0x021a9000 is free, no ART heap → NO fork)
// AND a fresh binder ProcessState, so the GPU context can be created+used in
// one never-forked process (fixes the GPU-after-fork crash).
//
// Usage:  librimdroid_exec.so <game_dir> <lib_dir> [extra RimWorld args...]
// Env (set by the launcher, inherited): BOX64_LD_LIBRARY_PATH, RIMDROID_RENDERER,
//   GALLIUM_DRIVER, MESA_*, SDL_DYNAMIC_API, BOX64_LIBGL, etc.

#include <stdio.h>
#include <dlfcn.h>
#include "rimdroid.h"

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <game_dir> <lib_dir> [args...]\n",
                argc > 0 ? argv[0] : "rimdroid_exec");
        return 2;
    }
    const char* game_dir = argv[1];
    const char* lib_dir  = argv[2];
    int          gargc   = argc - 3;
    const char** gargv   = (const char**)(argv + 3);

    // In the JNI app, RimDroidApplication calls System.loadLibrary("rimdroidlinker"),
    // which puts librimdroidlinker.so in the DEFAULT linker namespace so that
    // launch_rimworld_elf()'s dlopen(RTLD_NOLOAD) can find it (load_linker_hook
    // separately loads it into the isolated rimdroid_ns).  This exec'd process has
    // no Application, so do the default-namespace load here ourselves.
    void* linker = dlopen("librimdroidlinker.so", RTLD_NOW | RTLD_GLOBAL);
    if (!linker) {
        fprintf(stderr, "rimdroid_exec: failed to load librimdroidlinker.so: %s\n", dlerror());
        return 3;
    }

    return rimdroid_run_standalone(game_dir, lib_dir, gargc, gargv);
}
