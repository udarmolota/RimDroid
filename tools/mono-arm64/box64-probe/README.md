# Box64 -> ARM64 Mono P1 probe

This is a deliberately narrow ABI gate.  An x86_64 executable asks Box64 to
load the synthetic `librdmonoprobe.so`; its wrapper opens the real ARM64 Unity
Mono runtime specified by `RIMDROID_NATIVE_MONO_PATH`.

It then exercises the minimal forward path:

`dlopen -> dlsym -> mono_jit_init_version -> managed method -> result`

The guest RimWorld `libmonobdwgc-2.0.so` is never intercepted by this stage.
Do not use this result as evidence that Unity, icalls, GC, exceptions, signals,
Burst, or Steam P/Invokes work.

Build the guest x86_64 executable on Linux:

```sh
./build-x86.sh
```

On an ARM64 device, place the executable, `RimDroid.MonoArm64Probe.dll`, its
Mono managed/config directories, and the native ARM64 Mono runtime.  Then run
it through the Box64 binary that includes `wrappedrdmonoprobe.c`:

```sh
RIMDROID_NATIVE_MONO_PATH=/absolute/path/libmonobdwgc-2.0.so \
  box64 ./mono_box64_probe MANAGED_DIR CONFIG_DIR RimDroid.MonoArm64Probe.dll RunBasic
```

`BOX64_MONO_PROBE verdict=PASS value=0x5244` is the only P1 success criterion.

## P2-mini reverse icall gate

Run the same executable with `RunReverseIcall`.  ARM64 Mono registers and calls
the managed `InternalCall`, the Box64 wrapper converts its x86_64 function
pointer to an ARM64 callback, and the x86 guest adds `0x5200 + 0x44`.

```sh
RIMDROID_NATIVE_MONO_PATH=/absolute/path/libmonobdwgc-2.0.so \
  box64 ./mono_box64_probe MANAGED_DIR CONFIG_DIR RimDroid.MonoArm64Probe.dll RunReverseIcall
```

P2-mini passes only when both lines are present:

```text
BOX64_MONO_PROBE phase=reverse_icall left=0x5200 right=0x44
BOX64_MONO_PROBE verdict=PASS value=0x5244
```

This proves one integer callback shape.  It does not yet implement Unity's full
icall signature set, floating-point callbacks, exceptions, or foreign GC roots.

`RunAbiMatrix` extends the gate to signed 64-bit values, pointers, double and
float argument registers, and nine 64-bit arguments so both ABIs must use their
stack calling convention. A `PASS` result means these representative shapes
survived; it still does not replace the generated full Unity icall bridge.
