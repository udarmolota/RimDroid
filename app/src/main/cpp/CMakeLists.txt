cmake_minimum_required(VERSION 3.4.1)
project(rimdroid)
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
