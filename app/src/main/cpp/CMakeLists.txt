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
# box64 — root-level submodule (path: <project_root>/box64)
# CMAKE_CURRENT_SOURCE_DIR is app/src/main/cpp, so go up 4 levels
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
