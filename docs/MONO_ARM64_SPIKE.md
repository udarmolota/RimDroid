# Native Mono ARM64 spike

## Status (2026-09-17)

**All five fundamental bridge gates pass on a Samsung S25 (Adreno 830, Android 15).** Every gate was
proven A/B on one build: an environment switch disables the fix and the same binary reproduces the
failure. No gate is blocked; what remains is engineering towards a real Unity boot.

| Gate | Question | Result |
|---|---|---|
| P1 | x86_64 host -> Box64 wrapper -> ARM64 Mono: load, JIT init, invoke | PASS |
| P2 | ARM64 Mono JIT -> x86_64 internal call, full scalar ABI incl. 8/16-bit, float/double, stack arguments | PASS |
| P3 | Boehm GC sees object references held only in x86 guest registers/stack | PASS (fix off: object collected) |
| P4 | Managed exception raised by an x86 internal call, guest state preserved | PASS (bridge off: guest corrupted, process dies) |
| P5 | Mono and Box64 share SIGSEGV (JIT null checks vs. Box64 self-modifying-code faults) | PASS (chaining off: Mono native crash) |
| Cost | Price of one bridge transition | 46-76 ns reverse, 15-18 ns forward |

Details, commits and exact outputs are in [Feasibility results](#feasibility-results). The next phase is
described in [Next phase](#next-phase-unity-integration).

## Objective

Determine whether RimDroid can run the x86_64 Unity 2022 player under Box64
while executing RimWorld managed code in a native Android ARM64 Unity Mono.
The existing x86_64 Mono path must remain the default and fallback.

## Frozen baseline

- RimWorld `1.6.4518 rev91`
- Unity `2022.3.35f1`
- `UnityPlayer.so` SHA-256:
  `4c64f497b3f67fdb59c2e8bdc03d45a9dfbde51dd9300d288640f80c3737ad04`
- `libmonobdwgc-2.0.so` SHA-256:
  `32eb66eb4992296f44d3fed053fbfcfc87f0dd85e3eb4d91d27576048e2c7280`

RimWorld `1.6.4566 rev575` uses the same UnityPlayer and Mono binaries. Game
revisions are therefore secondary until either runtime hash changes.

The official Unity 2022.3.35f1 Android Support payload contains Mono only for
`armeabi-v7a`; it does not ship `mono/.../arm64-v8a`. This is a packaging and
support-policy limitation, not a missing ARM64 backend in the public source:

- `external/buildscripts/build.pl` in `unity-2022.3-mbe` accepts
  `--androidarch=aarch64` and selects `aarch64-linux-android`, API 21.
- The public source has an Android ARM64 target in `sdks/builds/android.mk` and
  ARM64 JIT support throughout `mono/mini`.
- The stock wrapper intentionally requests only ARMv7 and x86, both with
  `--arch32=1`.

The first reproducible source candidate is commit
`c11bc9adba5d9dcad976d64b295bca8424fa41cd` from 2024-06-25, immediately before
the 2022.3.35f1 Android payload dated 2024-06-26. This is a strong provenance
match, not proof that Unity used that exact commit.

The extracted official ARMv7 runtime is a useful platform control:

- SHA-256: `48b09c12322a5ff02366ce7c5dbccdf7eef6a171ccc71355bddf4b76c68c48c3`
- It exports all `286/286` `mono_*` entry points used by RimWorld's Linux
  `UnityPlayer.so`.
- Therefore the immediate source-build gate is realistic; it does not prove
  callback or object-layout compatibility.

## Architecture

Dropping an ARM64 library into `MonoBleedingEdge/x86_64` cannot work. The spike
requires both directions of an ABI bridge:

```text
x86_64 UnityPlayer -> Box64 wrappedlibmono -> ARM64 Unity Mono
ARM64 Mono/JIT -> generated callback thunk -> x86_64 UnityPlayer
```

## Gates

1. **Candidate provenance:** obtain ARM64 Android Mono from Unity 2022.3.35f1.
   Stock upstream Mono is not an equivalent candidate. Build the Unity fork
   with `tools/mono-arm64/build-unity-mono-arm64.sh`; the manual
   `mono-arm64-spike.yml` workflow packages the runtime and native probe only.
   **Done:** the CI build of `c11bc9adba` produces `libmonobdwgc-2.0.so` for
   `aarch64-linux-android21`.
2. **Static ABI:** compare candidate exports with the known-good runtime using
   `tools/mono-arm64/audit-mono-abi.ps1`. **Done.**
3. **Native harness:** initialize ARM64 Mono directly, load a tiny managed
   assembly, invoke a method, collect garbage, run a worker, and clean up.
   **Done.**
4. **Box64 harness:** repeat from a small x86_64 embedding executable through a
   minimal wrapped library. **Done (P1).**
5. **Reverse callbacks, GC, exceptions, signals:** prove the bridge mechanics
   before attempting Unity. **Done (P2-P5, see below).**
6. **Headless Unity:** run `-batchmode -nographics` and reach the first managed
   RimWorld method. **Next.**
7. **Game matrix:** menu, map, save/load, DLC, Harmony, then long-run testing.

## Feasibility results

All runs: Samsung S25 (`RFGYB08X13B`), the CI artifacts of the listed commits, the
`tools/mono-arm64/box64-probe` harness launched through `run-as com.rimdroid`. The
success line of every run is `BOX64_MONO_PROBE verdict=PASS value=0x5244`; since P4 the
host also prints `guest_state=INTACT mask=0x0` (see P4).

### P1 - forward ABI

The x86_64 host loads the synthetic wrapped library, which opens the ARM64 runtime,
initializes the JIT and invokes a managed method. PASS.

The 289 Mono functions UnityPlayer resolves (286 `mono_*` and 3 `unity_*`: the first headless
run found the `unity_` ones, which the name audit had filtered out) collapse to **50 distinct Box64 signatures**
with no floating-point arguments or returns, no stack-passed arguments, no structs by
value and no varargs. Only three use 8/16-bit integers
(`mono_gc_is_incremental`, `mono_error_get_error_code`, `mono_gc_set_incremental`).
About 21 need hand-written wrappers (callbacks, exceptions, init, threads); the rest
can be generated.

### P2 - reverse internal calls

ARM64 JIT code calls x86_64 functions registered with `mono_add_internal_call`.
`RunAbiMatrix` covers signed 64-bit values, pointers, `double` and `float` argument
registers, nine 64-bit arguments (stack on both ABIs), and signed/unsigned 8- and
16-bit values with the top bit set. PASS (RimDroid `958832e`).

RimWorld's managed code registers **8122 internal calls with 596 distinct native
signatures**; none passes or returns a struct by value (Unity's `_Injected` wrappers
use `ref`). 1294 have a floating-point argument or return, 150 spill arguments to the
stack on x86_64 and 59 on AArch64, 1176 return an 8/16-bit value. Small integers must
be extended explicitly at both boundaries.

### P3 - GC roots in guest state

Boehm scans native thread stacks conservatively, but the x86 guest keeps references in
Box64's emulated registers and a separately mapped guest stack.

- Test `RunGcRootProbe`: a worker thread creates a 128 KB object, the x86 side stores
  its address only in a slot on the main thread's guest stack, the worker exits, the
  main thread runs full collections and checks a `WeakReference`. A negative control
  hides the address with an XOR mask and must be collected.
- The worker thread matters: publishing on the collecting thread left the raw address
  in dead native frames of the reverse-call path, and both objects survived (`-202`),
  so the first test could not discriminate.
- Fix (Box64 `45430983d`): after `mono_jit_init_version`, chain a
  `GC_push_other_roots` hook on top of Mono's own (Mono already keeps and calls the
  previous hook) and push the emulated `regs`, `xmm` and the live guest stack
  `[RSP-128, top)`. Cleanup unregisters under `GC_call_with_alloc_lock`. The hook runs
  with the world stopped: it must not lock, allocate or print.
- Result (RimDroid `26881b0`): PASS 3/3, hook ran 22 times per run.
  `RIMDROID_P3_NO_GUEST_ROOTS=1` on the same build returns `-201` (object collected).

### P4 - exceptions from x86 internal calls

A native `mono_raise_exception` unwinds to the managed catch block through Mono's LMF
chain and drops everything in between: the reverse thunk, `RunFunctionFmt`, `DynaCall`
and dynarec frames. `DynaCall` never restores the guest state, and it never saves
R12-R15 at all.

- Test `RunExceptionProbe`: an x86 internal call loads garbage into every callee-saved
  register and raises `ArgumentNullException`; managed code catches it 1000 times and
  then makes a normal reverse call. The host calls `mono_runtime_invoke` through an
  assembly guard with canaries in RBX, RBP, R12-R15 and a stack pointer check, and
  reports `guest_state=INTACT/CORRUPT`. The guard applies to every probe method.
- Fix (Box64 `b41fca3aa`): inside a reverse internal call, the wrapper turns the raise
  into `mono_runtime_set_pending_exception` and sets `emu->quit`. The dynarec exits
  right after the bridge call (it checks `quit` after wrappers that take the emulator;
  simple wrappers do not), `DynaCall` restores RBX/RDI/RSI/RBP/RSP/RIP, and the thunk
  restores R12-R15. Mono's wrapper for foreign internal calls then throws the pending
  exception at its interruption checkpoint, inside managed code.
- Result (RimDroid `22346b1`): PASS and `INTACT` 3/3.
  `RIMDROID_P4_NO_EXCEPTION_BRIDGE=1`: all 1000 catches still "work" on the managed
  side, then the process dies silently (exit 255) returning into the corrupted x86 host.

### Transition cost

`RunIcallBenchmark` (Box64 `c8402b96e`, RimDroid `a5d1f37`), one million calls, six runs,
with default Box64 settings and with the game's settings (no difference):

| Direction | Per call | Overhead |
|---|---|---|
| ARM64 Mono -> x86 internal call | 46-76 ns | 45-74 ns over a 1.8 ns native managed call |
| x86 -> ARM64 Mono API | 15-18 ns | 8-14 ns over a plain x86 call |

Two clusters (~47 and ~73 ns) look like core placement. The reverse path measured is
the generic `RunFunctionFmt` static slot and is not optimized.

A static estimate of how often RimWorld calls into the engine (IL call graph over
`Assembly-CSharp` and `UnityEngine*.dll`, counting reachable internal calls per
invocation): `Graphics.DrawMesh` 1-2, `Matrix4x4.TRS` 1, `Text.CalcSize` 3,
`Widgets.Label` 2-14, `Widgets.ButtonText` 24-30, `Graphic.Draw` 5-13. A normal colony
is estimated at 5-15k internal calls per frame (map sections, pawns, overlays, IMGUI
running 2-3 times per frame), a heavy late game at 20-40k. That is roughly
**0.3-1 ms per frame, up to ~3 ms** at the measured price. The simulation (pathing, AI,
needs) is mostly plain C# and runs natively with no transition at all. The real rate
must be counted once the Unity bridge exists.

### P5 - signal ownership

Mono installs SIGSEGV, SIGBUS, SIGILL, SIGABRT, SIGFPE, SIGSYS and SIGQUIT handlers in
`mini_init`, i.e. during `mono_jit_init_version`, replacing the handlers Box64 installed
at startup. It keeps the previous handler only if `mono_set_signal_chaining(TRUE)` was
called before init, and it hands a fault outside JIT code to that handler only while
crash chaining is off; otherwise it reports a native crash first. ARM64 Mono uses no
alternate signal stack and relies on SIGSEGV for implicit null checks. Box64 relies on
SIGSEGV for write-protected translated code. P1-P4 never triggered a Box64 fault, which
is why the lost handler went unnoticed until P5.

- Test `RunSignalProbe`: 200 rounds on one thread interleave an x86 function generated
  into an executable mapping and rewritten every round (Box64 must see the write and
  drop the stale translation) with a managed field read through `null` (Mono must raise
  `NullReferenceException`).
- Fix (Box64 `4d3da77b3`): the wrapper calls `mono_set_signal_chaining(1)` and
  `mono_set_crash_chaining(0)` before native JIT init. Unity calls these setters itself,
  so the real wrapper must override its arguments.
- Result (RimDroid `f423ffb`): PASS and `INTACT` 3/3; P2, P3 and P4 still pass.
  `RIMDROID_P5_NO_SIGNAL_CHAINING=1`: Mono prints "Native Crash Reporting" and the
  process dies.

## Bridge audit findings

The audit of the Box64 reverse-call boundary (callback prototypes, pointer-bearing APIs,
internal call resolution) is complete. The points that shape the next phase:

- **Callbacks:** a finite set of about 12 prototypes (`dl_fallback`, find-plugin, log
  handler, `GFunc` foreach family, stack walk, liveness, profiler). Static Box64 slots
  are enough. `mono_unity_set_vprintf_func` passes a `va_list`: format natively instead
  of bridging it. `mono_unity_install_unitytls_interface` is a struct of function
  pointers and is not needed before the main menu.
- **No executable pointers flow from Mono to Unity.** None of the 289 functions returns
  JIT code or a native thunk that Unity would call.
- **Internal call signatures:** Unity registers with `mono_add_internal_call` (flag
  `FOREIGN`), and in JIT mode Mono always wraps such calls
  (`mono_marshal_get_native_wrapper` with `check_exceptions`). The exact signature is
  available from `MonoMethodSignature` when the call is resolved in `mini-runtime.c`;
  this is where a generated thunk should be bound. The generated thunks must carry the
  P4 exception bridge.
- **Forward APIs that unwind natively:** `mono_raise_exception` and, with a `NULL`
  exception slot, `mono_runtime_invoke`, `mono_runtime_invoke_array`,
  `mono_runtime_delegate_invoke`, `mono_runtime_exec_main`. The wrappers must always pass
  a real slot.
- **Hidden x86 code pointers:** RimWorld uses Burst direct calls (13 sites in
  `Assembly-CSharp`, 13 in `Unity.Collections`) that call `lib_burst_generated.so`
  through raw pointers. Run with `--burst-disable-compilation`
  (`UNITY_BURST_DISABLE_COMPILATION`) so they fall back to managed code.
- **Library selection:** Unity opens Mono by full path, and `NewLibrary` in
  `box64/src/librarian/library.c` then prefers the emulated x86 file. `libmonobdwgc`
  needs the same exception SDL2 has.
- **Stacks:** managed frames move from the 8 MB guest stack to the native stack of the
  game thread, which must be large. Threads created by Mono get an emulator lazily with a
  256 KB guest stack (`thread_get_emu`), too small for engine code.
- **GC stop-the-world:** Box64 dynarec critical sections mask all signals except the
  fault signals (`dynablock.c`), including Boehm's suspend/restart signals; remove those
  from the mask to avoid a stop-the-world stall. Guest `sigaction` for non-fault signals
  reaches the kernel and could replace Boehm's or Mono's thread signals; reserve them.
- **P/Invoke before the menu:** `libmono-native.so` (real ARM64 build) and
  `libsteam_api.so`; an ARM64 stub returning failure for the Steamworks entry points is
  enough. Mono's `etc/mono/config` maps `libc` to the build-time `@LIBC@`
  (`data/config.in`); the shipped config must name Android's `libc.so`.

## Next phase: Unity integration

Goal: RimWorld starts in batch mode and reaches its first managed method, then the menu.

1. `wrappedlibmono` for all 289 functions, generated plus hand-written wrappers, and the
   `library.c` exception so Unity gets the ARM64 runtime.
2. Generated reverse thunks for the 596 internal call signatures, bound at resolution
   time, each carrying the P4 exception bridge.
3. Per-thread GC registration of guest contexts with thread-exit handling (P3 covers one
   thread), signal chaining overrides (P5), the reserved signal set and the dynarec mask
   fix.
4. Unity callbacks, P/Invoke stubs, Burst disabled, a large game-thread stack.
5. A counter in the reverse-call dispatcher to measure the real internal call rate.

The existing x86_64 Mono path stays the default and the fallback throughout.
