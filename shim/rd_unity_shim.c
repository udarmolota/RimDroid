// RimDroid — a relocatable stand-in for RimWorld's Unity launcher binary.
//
// The shipped RimWorldLinux is a non-PIE (ET_EXEC) executable: it can only run from one fixed low
// address (0x200000 on RimWorld 1.6). On some phones that address is not ours to take — on a Huawei
// Kirin 8000 the Android runtime's Java heap covers 0x10000-0x32010000, i.e. the whole low address
// space, from the moment the process is created — so box64 cannot place the binary and the game never
// starts. Nothing can be done about that: a non-PIE image has its addresses baked into its code.
//
// The launcher itself, however, does almost nothing. On 1.6 it is 14 KB whose only meaningful contents
// are the strings "UnityPlayer.so" and "_Z10PlayerMainiPPc" — it loads the engine and calls
// PlayerMain(argc, argv). The engine is a normal shared library that loads at any address. So we
// replace the launcher with this PIE build of the same three lines, and the fixed address stops
// mattering.
//
// Deployed AS "RimWorldLinux" (the original kept as RimWorldLinux.rdorig) so that argv[0] and
// /proc/self/exe are unchanged — Unity derives RimWorldLinux_Data from them.
//
// 1.5 is deliberately NOT covered: there the launcher is ~2 MB with SDL2 statically linked in, and
// UnityPlayer.so resolves SDL symbols from it, so a bare stand-in would break windowing and input.

#define _GNU_SOURCE
#include <dlfcn.h>
#include <libgen.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>

typedef int (*player_main_t)(int, char **);

static void *load_engine(const char *argv0)
{
    char path[PATH_MAX];

    // Next to us, which is what the real launcher's DT_NEEDED + rpath resolve to.
    if (argv0 && *argv0) {
        char tmp[PATH_MAX];
        snprintf(tmp, sizeof(tmp), "%s", argv0);
        snprintf(path, sizeof(path), "%s/UnityPlayer.so", dirname(tmp));
        void *h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (h) return h;
        fprintf(stderr, "[rd-shim] dlopen(\"%s\") failed: %s\n", path, dlerror());
    }

    // Fall back to the loader's own search path (box64 points it at the instance dir).
    void *h = dlopen("UnityPlayer.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) fprintf(stderr, "[rd-shim] dlopen(\"UnityPlayer.so\") failed: %s\n", dlerror());
    return h;
}

int main(int argc, char **argv)
{
    void *engine = load_engine(argc > 0 ? argv[0] : NULL);
    if (!engine) return 127;

    // The Linux player exports PlayerMain(int, char**) as a C++ symbol; accept the unmangled spelling
    // too, in case a build ever ships it that way.
    player_main_t player_main = (player_main_t)dlsym(engine, "_Z10PlayerMainiPPc");
    if (!player_main) player_main = (player_main_t)dlsym(engine, "PlayerMain");
    if (!player_main) {
        fprintf(stderr, "[rd-shim] neither _Z10PlayerMainiPPc nor PlayerMain found in UnityPlayer.so\n");
        return 126;
    }

    fprintf(stderr, "[rd-shim] handing over to PlayerMain (argc=%d, argv[0]=%s)\n",
            argc, (argc > 0 && argv[0]) ? argv[0] : "(none)");
    return player_main(argc, argv);
}
