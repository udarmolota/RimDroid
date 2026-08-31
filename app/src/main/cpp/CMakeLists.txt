cmake_minimum_required(VERSION 3.4.1)
project(rimdroid)
message(STATUS "CMake configuring at: ${CMAKE_CURRENT_SOURCE_DIR}")
string(TIMESTAMP BUILD_TIME "%Y-%m-%d %H:%M:%S")
message(STATUS "Build time: ${BUILD_TIME}")
cmake_policy(SET CMP0079 NEW)
set(CMAKE_C_STANDARD 11)

# ---------------------------------------------------------------------------
# liblinkernsbypass — same as zomdroid, unchanged
# ---------------------------------------------------------------------------
add_subdirectory(liblinkernsbypass)

# ---------------------------------------------------------------------------
# librimdroid — surface management, renderer init, JNI bridge
# Equivalent to libzomdroid in Zomdroid (without GLFW / JVM parts)
# ---------------------------------------------------------------------------
add_library(rimdroid SHARED
    rimdroid.c
    rimdroid_jni.c
)
target_link_libraries(rimdroid
    log
    android
    EGL
    mediandk
    linkernsbypass
)

# ---------------------------------------------------------------------------
# librimdroidxserver.so — native side of the ported Winlator X server (LGPL-2.1,
# from brunodev85/winlator-app): unix-socket epoll transport (xconnector), 2D
# drawable primitives, SysV-SHM emulation. JNI symbols renamed com_winlator ->
# com_rimdroid. Used by the RimWorld 1.6 X11/Vulkan path (memory rimworld_16_port).
# ---------------------------------------------------------------------------
add_library(rimdroidxserver SHARED
    xserver/jni_onload.c
    xserver/xconnector_epoll.c
    xserver/xinput_stream.c
    xserver/xoutput_stream.c
    xserver/drawable.c
    xserver/sysvshared_memory.c
    xserver/arrays.c
)
target_include_directories(rimdroidxserver PRIVATE xserver)
target_link_libraries(rimdroidxserver
    log
    android
    jnigraphics
)

# ---------------------------------------------------------------------------
# libfmoddecode.so — STANDALONE offline FSB5-Vorbis -> PCM WAV decoder (native FMOD).
# Deliberately does NOT link linkernsbypass/box64: it is loaded ONLY in the separate
# ":fmoddec" process (where librimdroidlinker is NOT loaded), so its dlopen of the
# bundled libfmod.so is the real bionic dlopen in the normal app namespace — avoiding
# the in-process crash (box64's interposed dlopen loads libfmod into a namespace that
# lacks libc++_shared/libaaudio -> libfmod init SIGSEGVs). See fmod_decode_spike.c.
# ---------------------------------------------------------------------------
add_library(fmoddecode SHARED fmod_decode_spike.c)
target_link_libraries(fmoddecode
    log
    android
)

# ---------------------------------------------------------------------------
# libpulsesimpleshim.so — tiny PulseAudio "simple" API shim backed by AAudio.
# RimWorld's FMOD probes libpulse-simple.so.0 for output; we provide the 7 funcs
# box64's wrappedpulsesimple bridges. Built with DT_SONAME "libpulse-simple.so.0"
# so that, once preloaded by name (RimDroidApplication), box64's wrapper
# dlopen("libpulse-simple.so.0") resolves to this library. See pulse_simple_shim.c.
# ---------------------------------------------------------------------------
add_library(pulsesimpleshim SHARED pulse_simple_shim.c)
set_target_properties(pulsesimpleshim PROPERTIES OUTPUT_NAME "pulsesimpleshim")
# Force DT_SONAME = libpulse-simple.so.0 so that, once this file (libpulsesimpleshim.so) is preloaded,
# the linker registers it under that soname and box64's dlopen("libpulse-simple.so.0") resolves to it.
# The flag MUST come AFTER CMake's auto "-soname,libpulsesimpleshim.so" (last one wins); CMake emits its
# auto soname after <LINK_FLAGS> but before <LINK_LIBRARIES>, so we pass ours as a link-library item
# (the very end of the link line) rather than via target_link_options (which lands in <LINK_FLAGS>).
target_link_libraries(pulsesimpleshim
    log
    aaudio
    "-Wl,-soname,libpulse-simple.so.0"
)

# ---------------------------------------------------------------------------
# libpulseshim.so — fake async PulseAudio client (libpulse.so.0). FMOD's pulse output enumerates
# devices via this (getNumDrivers); reports one "default" sink so FMOD proceeds to play via pa_simple.
# Same soname-override trick as the pulse-simple shim.
# ---------------------------------------------------------------------------
add_library(pulseshim SHARED pulse_shim.c)
set_target_properties(pulseshim PROPERTIES OUTPUT_NAME "pulseshim")
target_link_libraries(pulseshim
    log
    "-Wl,-soname,libpulse.so.0"
)

# ---------------------------------------------------------------------------
# libasoundshim.so — minimal ALSA (libasound.so.2) → AAudio. With the pulse shims NOT preloaded,
# FMOD falls back to its ALSA output and uses this. snd_pcm_writei → blocking AAudio write.
# ---------------------------------------------------------------------------
add_library(asoundshim SHARED alsa_shim.c)
set_target_properties(asoundshim PROPERTIES OUTPUT_NAME "asoundshim")
target_link_libraries(asoundshim
    log
    aaudio
    "-Wl,-soname,libasound.so.2"
)

# ---------------------------------------------------------------------------
# box64 — root-level submodule (path: <project_root>/box64)
# CMAKE_CURRENT_SOURCE_DIR is app/src/main/cpp, so go up 4 levels.
#
# ⚠️  THIS root-level <project_root>/box64 IS THE box64 THAT GETS COMPILED INTO THE APK.
#     .gitmodules also registers a SECOND checkout at app/src/main/cpp/box64 — that one is an
#     UNUSED DUPLICATE (same fork, never built). Make ALL box64 source edits in <project_root>/box64.
#     Editing app/src/main/cpp/box64 has NO effect on the build.
# ---------------------------------------------------------------------------
set(BOX64_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../box64)
set(NOGIT ON)
set(ARM_DYNAREC ON)
add_subdirectory(${BOX64_ROOT} box64_build)
target_compile_definitions(box64 PRIVATE BUILD_DYNAMIC)

# box64/CMakeLists.txt:377 applies -fvisibility=hidden to all its sources.
# This makes my_* wrapper functions invisible to dlsym(box64lib, "my_xxx"),
# causing all wrapped libc/pthread symbols to be "not found" at runtime.
#
# Fix: apply -fvisibility=default surgically to ONLY the wrapper source files.
# We cannot touch all of mainobj because arm64_lock.S accesses cpuext from
# core.c via non-GOT PC-relative addressing (R_AARCH64_ADR_PREL_PG_HI21),
# which requires cpuext to keep hidden visibility in a shared library.
#
# set_source_files_properties overrides the per-target compile options at the
# translation-unit level — the last flag wins, so this beats the hidden default.
file(GLOB_RECURSE _box64_wrapper_srcs
    "${BOX64_ROOT}/src/wrapped/*.c"
    "${BOX64_ROOT}/src/wrapped32/*.c"
)
if(_box64_wrapper_srcs)
    set_source_files_properties(${_box64_wrapper_srcs}
        PROPERTIES COMPILE_OPTIONS "-fvisibility=default")
endif()

# ---------------------------------------------------------------------------
# librimdroidlinker — box64 + linker hook + emulation
# Equivalent to libzomdroidlinker in Zomdroid
# Link order: c before box64 to prevent mmap override
# ---------------------------------------------------------------------------
add_library(rimdroidlinker SHARED
    linker.c
    emulation.c
)
target_include_directories(rimdroidlinker PRIVATE
    ${BOX64_ROOT}/src/include
    ${BOX64_ROOT}/src
)
# Must match the DYNAREC/ARM64 flags used when building box64 so box64context_t layout is identical
target_compile_definitions(rimdroidlinker PRIVATE DYNAREC ARM64)
target_link_libraries(rimdroidlinker PRIVATE
    c
    log
    $<TARGET_OBJECTS:mainobj>
    $<TARGET_OBJECTS:interpreter>
    dynarec
    rimdroid
)
target_link_options(rimdroidlinker PRIVATE "-Wl,-z,global")

# ---------------------------------------------------------------------------
# librimdroid_exec.so — STANDALONE EXECUTABLE (not a library).
# The "lib*.so" name is the Android packaging trick: the APK build extracts it
# into the app's nativeLibraryDir with exec permission, so the Java launcher can
# exec() it as a fresh process.  A fresh process gives box64 a clean address
# space (RimWorld's fixed 0x021a9000 free, no ART heap → NO fork needed) and a
# fresh binder ProcessState → GPU context can be created+used without fork.
# It links librimdroid.so (which has rimdroid_run_standalone + the launch logic;
# box64 itself is dlopen'd at runtime via librimdroidlinker.so, same as the JNI
# path).  Android NDK builds executables as PIE by default.
# ---------------------------------------------------------------------------
add_executable(rimdroid_exec rimdroid_main.c)
set_target_properties(rimdroid_exec PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
    OUTPUT_NAME "rimdroid_exec")
target_link_libraries(rimdroid_exec
    rimdroid
    log
    android)
