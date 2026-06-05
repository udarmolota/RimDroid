DO NOT EDIT the box64 submodule in THIS directory (app/src/main/cpp/box64).

It is an UNUSED DUPLICATE checkout. The APK compiles the ROOT-LEVEL box64 submodule instead:

    <project_root>/box64        <-- EDIT box64 SOURCE HERE

See app/src/main/cpp/CMakeLists.txt:
    set(BOX64_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../box64)   # = <project_root>/box64
    add_subdirectory(${BOX64_ROOT} box64_build)

Both paths are the same fork (github.com/udarmolota/rimdroid-box64), registered twice in
.gitmodules, but only <project_root>/box64 is built. Editing app/src/main/cpp/box64 has NO
effect on the APK.

(Native sources that ARE built from this directory: rimdroid.c, rimdroid_jni.c, rimdroid.h,
liblinkernsbypass — those are fine to edit here. Only the box64/ subtree is the duplicate.)
