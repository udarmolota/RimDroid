# Brief — deterministic IMT-dispatch crash (surgery bill), box64 deep-bug family

## One-line
We finally have a **deterministic, save-carried reproducer** of the box64 "deep wrong-value" bug:
loading a RimWorld 1.5 save that contains a pending surgery bill crashes ~3-4 s after load, every
run, on a Lenovo Y700 Gen3 tablet (Snapdragon 8+ Gen1, LSE atomics). The fatal fault is a Mono
**IMT interface dispatch** whose receiver register holds a **MonoVTable/MonoClass pointer instead of
the object** (or NULL in the non-fatal face). We captured the exact call site, registers, and the
memory around the dispatch table across 5 runs. Two candidate fixes already tested and REJECTED by
the reproducer. We want help picking the mechanism and the next instrumentation.

## Environment
- box64 fork (github.com/udarmolota/rimdroid-box64), dynarec pinned **-O0** (separate -O1 regression),
  `BOX64_DYNAREC_STRONGMEM=4, BIGBLOCK=0, SAFEFLAGS=1, WEAKBARRIER=1, FASTNAN/FASTROUND=0,
  ALIGNED_ATOMICS=1 (debug builds)`.
- Game: RimWorld 1.5 (Unity 2019, Mono), JIT region 0x32xxxxxx, libmonobdwgc-2.0.so at 0x3f04000000.
- Device: Adreno 730 / SD 8+ Gen1, arm64, cpuext.atomics=1 (CASAL path live where used).
- Crash reproduced identically on app 0.1.9 (release) and 0.2.1 (debug, instrumented box64).

## Crash anatomy (stable across 5 instrumented runs)
Two faults per crash, always in this order:

**Fault A (non-fatal, repeats across sessions — 21 entries in our fault log):**
`cmp dword [rax],0` with RAX=0 at a JIT RIP (per-run ~0x3229xxxx; RIP stable within a run).
Mono's implicit null-check → SIGSEGV → Mono handler → managed NullReferenceException; the game's
UE catcher logs it and play continues. Receiver was NULL.

**Fault B (fatal): execution jumps to RIP=0x58** (si_addr=0x58, ERR=0x14 instruction fetch).
Guest-stack return-address scan names the caller; the call-site bytes (dumped from guest memory at
fault time) are, in every run:
```
48 8b 00                        mov  rax, [rax]
49 ba <imm64>                   movabs r10, <MonoMethod* — matches R10 at fault>
ff 50 d8                        call [rax - 0x28]        ; IMT dispatch (table below vtable)
```
Caller addresses across runs: 0x32b666e4, 0x32b67344, 0x32b67ca4, 0x32b6c1a4, 0x32b757b4 — a tight
~64KB cluster (JIT arena layout is de-facto deterministic for this save).

**Memory at fault (imtdump, `[rax-0x40 .. rax+0x18]`):**
- `[rax-0x28]` (the called slot) durably holds **0x58**; `[rax-0x08]` holds **0x60**;
  the other IMT-range slots hold valid-looking pointers.
- `[rax+0]` and `[rax+8]` both equal **rax itself** (self-pointers).
- rdichain: `rdi` (managed `this`) = P, `[P] = rax`, `[rax] = rax`.

## Interpretation (our current best reading)
`[X+0]=[X+8]=X` self-pointers are the signature of a **MonoClass** (`element_class` and `cast_class`
of a plain class point to the class itself). So the structures in memory are likely all HEALTHY:
- X = MonoClass; P (= rdi = receiver) = **MonoVTable** (whose +0 field is the klass = X ✓);
- the "0x58 / 0x60 IMT slots" are then just MonoClass fields at negative offsets — not a corrupted
  IMT table at all;
- ⇒ **no memory corruption; the receiver VALUE is wrong**: a MonoVTable* was passed/loaded where the
  object* belongs (fault B), or NULL (fault A). "One deref off" in both directions.

The vtable lives in scudo (host native heap; box64 maps guest malloc onto it) — consistent with a
Mono-allocated MonoVTable, not a stale/freed block.

## What we already tested ON THE REPRODUCER (both rejected)
1. **NODYNAREC window over the June save-bug writer**: our earlier hunt named the writer of the IMT
   save corruption as the Mono call-site backpatcher at libmono+0x14df92 (`lock cmpxchgl` patching a
   call rel32; 60/60 captures). Running 0x3f0414d000-0x3f0414f000 through the interpreter
   (BOX64_NODYNAREC, block splitting verified in dynarec_native_pass) → **crash unchanged** ⇒ that
   codegen is exonerated for THIS crash.
2. **Upstream 38d64baba cherry (skip=1→3)**: resume via DYNAREC instead of the INTERPRETER after a
   guest signal handler returns. Attractive because fault A (handled NRE) always precedes fault B,
   and post-signal interpreter stretches were a suspect → **crash unchanged**.

Also relevant: full-program BOX64_DYNAREC_TEST is unusable (interpreter-speed, hours; device
overheated). A NARROW window (0x32b60000-0x32b80000, covers all 5 observed call sites) is armed as
the next run. Concern: DYNAREC_TEST only catches the divergence if the wrong value is PRODUCED in
the window; if it's produced elsewhere and only consumed here, the window stays silent.

## Family context (same device class, likely same root)
- **Save corruption (shipped workaround)**: IMT conflict-thunk dispatches IExposable::ExposeData to a
  wrong sibling method. Same IMT machinery as fault B.
- **Adreno 725 loading crash**: native stack `libmono+d7c7c → +14ce37 → +14df92 → [0x4034000000000000]`
  — a jump through a garbage code pointer that equals IEEE-754 double 20.0. Same "jump to non-code
  garbage" shape as our 0x58.
- 1.5 plays fine otherwise; NRE handling per se works (fault A is survivable, thousands of NREs fine
  elsewhere).

## Questions
1. **Mechanism**: in box64's arm64 dynarec at -O0, what would produce a receiver that is exactly
   "one deref off" (object→vtable, or →NULL)? Candidates we see: (a) a guest register not written
   back / stale across a dynablock boundary right after `mov rax,[rax]`-style chains; (b) callee-vs-
   caller confusion around an IMT/conflict trampoline (R10/R11 handling); (c) signal interruption
   mid-block re-executing `mov rax,[rax]` twice (we partially tested via skip=3 — rejected); (d) a
   linked/patched CALL landing in the middle of the intended target so the prologue's receiver
   setup is skipped. Which is most consistent with a DURABLE, per-save-deterministic crash?
2. Is **narrow DYNAREC_TEST** worth one run given the produce-vs-consume caveat, or is a targeted
   dynarec instrumentation strictly better: e.g. instrument the emitted code for `call [reg+disp8<0]`
   (IMT pattern) to check "is the loaded target < 0x10000?" and dump the block + register history on
   hit — a zero-false-positive tripwire at native speed?
3. The call sites cluster in ~64KB and the save is deterministic. Is dumping the **dynablock arm64
   code** for those blocks (BOX64_DYNAREC_DUMP range) and eyeballing the receiver dataflow (x86 vs
   emitted arm64) a realistic path, or too noisy at -O0?
4. Fault A (NULL receiver) and fault B (vtable-as-receiver) at nearby-but-different JIT sites: one
   producer with two consumers, or two independent bugs? Any box64-known issue where a **Mono IMT
   conflict thunk** (built at runtime, patched while other threads dispatch) yields transiently-wrong
   values under the dynarec despite STRONGMEM=4?
5. Any known upstream fixes beyond our fork base (b946deaee, 2026-06) worth cherry-picking for this
   class? We reviewed: 8a3ecb8cc (exec-mem tracking), 38a4a2c75 (hotpage before unprotect),
   3bf34a77c (NEVERCLEAN invalidations), 70dc4e489 (no siglongjmp resume from dynablock), 86a850cfc
   (callret=2 default). The first three are code-page/SMC-oriented — our fault B consumes DATA-heap
   values, so we deprioritized them; 70dc4e489 is untested (95-line signals.c change, conflicts with
   our instrumentation).

## Assets / repro logistics
- The save + instance live on the tester tablet (Wi-Fi adb); one crash cycle ≈ 2-3 minutes.
- box64 fork fully ours; instrumented signals.c already dumps: guest GPRs, code@RIP, retscan
  (return-address scan with symbolization), callsite bytes, imtdump (slots around RAX), rdichain.
- File-based knobs (no rebuilds): `rd_nodynarec` (BOX64_NODYNAREC range), `rd_box64_env`
  (arbitrary BOX64_* KEY=VALUE lines).
- libmonobdwgc-2.0.so (1.5) pulled locally; offsets symbolized against its dynsym.
