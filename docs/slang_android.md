# Slang for Android (libslang.so)

RimWorld 1.7 (Waylith) compiles shaders at runtime with [Slang](https://github.com/shader-slang/slang).
The game's own shaders come prebuilt in its Assets.zip, but mods that ship GLSL sources (IsoRim,
MineSight, ...) need the compiler on the device. `app/src/main/jniLibs/arm64-v8a/libslang.so` is the
official Slang source of the **same version as the game's** (`spGetBuildTagString()` = the tag the
game was built with; the prebuilt shader pack is keyed by it), cross-compiled with the Android NDK.

Built 2026-10-21 from tag `v2026.19` on Windows:

1. Host generators (any host C++ compiler; Visual Studio without a Windows SDK cannot, so Zig was
   used: `pip install --user cmake ninja ziglang`, wrappers `zig cc|c++ -target x86_64-windows-gnu`,
   `zig ar|ranlib|rc`, NDK `llvm-objcopy`/`llvm-strip`; `slangc` and `slang-bootstrap` use `wmain`
   and need `-municode`):
   ```
   cmake -S . -B build-gen -G Ninja -DCMAKE_BUILD_TYPE=Release -DSLANG_SLANG_LLVM_FLAVOR=DISABLE \
     -DSLANG_ENABLE_SLANG_RHI=OFF -DSLANG_ENABLE_TESTS=OFF -DSLANG_ENABLE_GFX=OFF -DSLANG_ENABLE_DXIL=OFF ...
   cmake --build build-gen --target all-generators
   cmake --install build-gen --prefix generators --component generators
   ```
2. Android arm64 (official preset):
   ```
   set ANDROID_NDK_HOME=<NDK 27>
   cmake --preset android-arm64 --fresh -DSLANG_GENERATORS_PATH=<abs>/generators/bin \
     -DANDROID_PLATFORM=android-30 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DSLANG_ENABLE_DXIL=OFF -DSLANG_ENABLE_PCH=OFF
   cmake --build --preset android-arm64-release --target slang
   ```
   The build ends with a failed symlink libslang.so -> libslang-compiler.so on Windows (needs
   privileges); the library itself is built. Strip it with NDK `llvm-strip` and copy it as
   `libslang.so` (about 30 MB; libc++ is static, it needs only libc, libm, libdl).

3. `libslang-glslang-2026.19.so` (glslang + SPIRV-Tools; Slang loads it by that name for
   `spirv-opt` and GLSL work; without it some mod shaders fail with "failed to load downstream
   compiler 'spirv-opt'"). Added 2026-11-14 from the same tree:
   ```
   cmake --build --preset android-arm64-release --target slang-glslang
   ```
   Strip with `llvm-strip --strip-unneeded` (about 7 MB; needs only libc, libm, libdl) and copy it
   under the same name next to `libslang.so`.

When Ludeon moves the game to a newer Slang (check the Linux libslang.so's build tag in the game
files), rebuild from that tag.
