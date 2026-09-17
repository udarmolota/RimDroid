# Box64 -> ARM64 Mono bridge probe (P1-P5)

An x86_64 executable asks Box64 to load the synthetic `librdmonoprobe.so`; its
wrapper opens the real ARM64 Unity Mono runtime specified by
`RIMDROID_NATIVE_MONO_PATH`. Each managed method of `RimDroid.MonoArm64Probe.dll`
is one gate of the bridge between the emulated x86 side and native ARM64 Mono.

**Status: every gate below passes on a Samsung S25**, and every fix is proven A/B
with an environment switch on the same build. Results, commits and analysis are in
[`docs/MONO_ARM64_SPIKE.md`](../../../docs/MONO_ARM64_SPIKE.md).

This is still a synthetic harness. The guest RimWorld `libmonobdwgc-2.0.so` is not
intercepted, and it does not show that Unity itself boots: the 289-function Mono
wrapper, the generated internal call thunks, multi-thread GC registration, Burst
and Steam P/Invokes belong to the integration phase.

## Build and run

Build the guest x86_64 executable on Linux (CI does this in the `box64-mono-p1`
artifact):

```sh
./build-x86.sh
```

On the device, place the executable, `RimDroid.MonoArm64Probe.dll`, its Mono
managed/config directories, the native ARM64 Mono runtime and the Box64 binary that
includes `wrappedrdmonoprobe.c`, then run one method:

```sh
RIMDROID_NATIVE_MONO_PATH=/absolute/path/libmonobdwgc-2.0.so BOX64_LOG=1 \
  box64 ./mono_box64_probe MANAGED_DIR CONFIG_DIR RimDroid.MonoArm64Probe.dll METHOD
```

A method passes only when both lines are present:

```text
BOX64_MONO_PROBE guest_state=INTACT mask=0x0
BOX64_MONO_PROBE verdict=PASS value=0x5244
```

The host calls `mono_runtime_invoke` through an assembly guard that loads canaries
into RBX, RBP and R12-R15 and checks them and the stack pointer afterwards.
`guest_state=CORRUPT` with a nonzero mask fails the run even if the value is right.

| Method | Gate | Result on S25 | Negative control |
|---|---|---|---|
| `RunBasic` | P1 forward ABI | PASS | - |
| `RunReverseIcall` | P2-mini, one integer internal call | PASS | - |
| `RunAbiMatrix` | P2 scalar ABI | PASS | - |
| `RunGcRootProbe` | P3 GC roots in guest state | PASS 3/3 | `RIMDROID_P3_NO_GUEST_ROOTS=1` -> `-201` |
| `RunExceptionProbe` | P4 exceptions from x86 | PASS + INTACT 3/3 | `RIMDROID_P4_NO_EXCEPTION_BRIDGE=1` -> process dies, exit 255 |
| `RunIcallBenchmark` | transition cost | 46-76 ns reverse, 15-18 ns forward | - |
| `RunSignalProbe` | P5 signal ownership | PASS + INTACT 3/3 | `RIMDROID_P5_NO_SIGNAL_CHAINING=1` -> Mono native crash |

## P1: forward path

`RunBasic`: `dlopen -> dlsym -> mono_jit_init_version -> managed method -> result`.
The method returns `0x5244`.

## P2: reverse internal calls

`RunReverseIcall`: ARM64 Mono calls a managed `InternalCall`, the wrapper converts its
x86_64 function pointer to an ARM64 callback, and the x86 guest adds `0x5200 + 0x44`.
It also prints:

```text
BOX64_MONO_PROBE phase=reverse_icall left=0x5200 right=0x44
```

`RunAbiMatrix` extends this to signed 64-bit values, pointers, `double` and `float`
argument registers, nine 64-bit arguments (stack on both ABIs), and signed/unsigned
8- and 16-bit values with the top bit set. Failures return `-101`..`-109`, one per
shape.

## P3: GC roots held by the guest

`RunGcRootProbe`: a worker thread allocates a 128 KB array, the x86 side stores its
address only in a slot on the main thread's guest stack, and the worker exits. The
main thread runs full collections and checks a `WeakReference`. A second round stores
the address XOR-masked and must be collected.

- `-201`: the guest-held object was collected (GC does not see guest state).
- `-202`: the masked control survived (the test cannot discriminate).

The wrapper chains a `GC_push_other_roots` hook after JIT init and pushes the emulated
registers, XMM registers and the live guest stack. The log line
`RIMDROID P3 guest GC roots pushed N time(s)` shows how many times it ran. Scope: the thread that initialized Mono only.

## P4: exceptions raised by x86 code

`RunExceptionProbe`: 1000 times, an x86 internal call loads garbage into every
callee-saved register and raises `ArgumentNullException` with `mono_raise_exception`;
managed code catches it. Then a normal reverse call must still work.

- `-301`: the call returned instead of throwing.
- `-302`: the reverse call after the exceptions failed.
- `-303`: not every exception was caught.

Inside a reverse internal call the wrapper turns the raise into
`mono_runtime_set_pending_exception`, stops the emulator (`emu->quit`) and restores
R12-R15, so the x86 frames return normally and Mono throws in managed code. Without the
bridge the managed side catches all 1000 exceptions and the process then dies on the
return into the corrupted x86 host.

## Transition cost

`RunIcallBenchmark` times one million calls of each kind: an internal call against a
plain managed call of the same shape, and `mono_class_get_name` from x86 against a plain
x86 call. It prints (per-call nanoseconds):

```text
BOX64_MONO_PROBE bench forward_api_ns=... x86_call_ns=... forward_overhead_ns=... calls=1000000
BOX64_MONO_PROBE bench reverse_icall_ns=... managed_call_ns=... reverse_overhead_ns=... calls=1000000
```

The reverse path goes through the generic `RunFunctionFmt` slot and is not optimized.
The numbers did not change with the game's Box64 environment.

## P5: signal ownership

`RunSignalProbe`: 200 rounds on one thread interleave an x86 function generated into an
executable mapping and rewritten every round (Box64 must catch the write to translated
code) with a managed field read through `null` (Mono must raise
`NullReferenceException`).

- `-501`: the rewritten function returned a stale value.
- `-502`: the null read did not throw.
- `-503`: not every null read was caught.

Mono replaces Box64's SIGSEGV handler during JIT init. The wrapper calls
`mono_set_signal_chaining(1)` and `mono_set_crash_chaining(0)` before init, so Mono
keeps Box64's handler and hands it every fault outside JIT code.
