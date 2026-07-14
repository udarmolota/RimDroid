# Brief v16 — box64 deep bug: a stack-local pointer reloads as 0 right before a virtual/interface call (deterministic capture)

## One-line
On arm64, box64 (x86_64→arm64 dynarec, `./box64` fork, dynarec pinned **-O0**) running RimWorld 1.6's
Mono/Unity-2022 JIT code deterministically produces a **NULL where a valid object pointer should be**:
a stack local reloaded into RAX comes back 0, and the *next* instructions are a virtual/interface call
(`mov rax,[rax]; call [rax+0xc0]`). We captured the exact faulting instruction, the preceding load, all
guest GPRs, and the stack top. We want help pinpointing the miscompiled dynarec sequence.

## Exact capture (from our in-box64 SIGSEGV logger, RimWorld 1.6, Adreno 830 / SD 8 Elite, arm64)
```
pre@RIP-32  0x322d93b3:  .. 48 8b 45 a0   mov rax,[rbp-0x60]    ; reload a stack local into RAX
                             48 8b f8       mov rdi,rax
fault@RIP   0x322d93d3:  83 38 00          cmp dword [rax],0     ; <-- SIGSEGV, si_addr=0x0, RAX=0
                             90               nop
                             e8 46 d1 df ff   call <rel32>
            (further:        48 8b f8         mov rdi,rax
                             48 8b 00         mov rax,[rax]
                             ff 90 c0 00 00 00 call [rax+0xc0])   ; virtual/interface dispatch
GPR at fault: RAX=0 RCX=0 RDX=4 RBX=6e96b035b0 RSI=0 RDI=0 R8=0 R9=8d R10=0
              R11=3f1123fb3d R12=6e96b16a80 R13=6e96b22db0 R14=6e96b22db0 R15=6e96afa100
stack@RSP  0x6e55b2de60:  6e96b01e40  6e96b01e40  6e96b01e40  6e55b2e020
si_addr=0x0  TRAPNO=14  prot=0  mmapped=0   (genuinely-unmapped NULL deref, i.e. a real null pointer)
```
- The `cmp dword [rax],0` is Mono's **implicit null-check** (touch the object; if null → SIGSEGV → Mono's
  handler throws NullReferenceException). So a *legitimate* null would be handled. But here the object is
  NOT legitimately null (see below), so Mono's handler + backtrace runs and the process dies (the same
  guest RIP re-faults dozens of times; Mono stack shows mono_breakpoint_clean_code →
  mono_unity_backtrace_from_context → mono_assertion_message).
- **RAX was just loaded from `[rbp-0x60]`** (a stack local) and is 0.
- **A whole fan of registers is 0**: RAX, RCX, RSI, RDI, R8, R10. RDX=4. The pointer-valued regs that
  survive are RBX/R11/R12/R13/R14/R15 (all plausible heap/JIT addresses).
- The `this`/arg pointer is intact on the stack: `[RSP]=[RSP+8]=[RSP+0x10]=0x6e96b01e40` (repeated), so the
  object existed and was passed correctly — only the reload of it into RAX yields 0.

## Why we think this is a box64 miscompile, not a real managed null
1. **RimWorld 1.5 runs fine under the same box64** (saves, gameplay) — Mono's implicit-null-check /
   NRE-via-SIGSEGV path works, so this is not a generic signal-handling bug.
2. **The object is present** (repeated on the stack top) yet its reload into RAX is 0.
3. **A fan of registers is simultaneously 0** — smells like a spill/reload or block-boundary
   register-state loss rather than one genuinely-null field.
4. It reproduces **deterministically** in one configuration (instant crash on the first frames) and is
   **phasic** in others — matching our long-standing "deep wrong-value" family (which also causes IMT
   interface-dispatch mis-resolution in saves, fixed only by an app-side IL workaround).
5. The faulting method's tail is a **virtual/interface call** (`mov rax,[rax]; call [rax+0xc0]`), i.e. the
   same vtable/IMT dispatch area implicated in the save-corruption bug.

## Background: the "deep bug" family (all likely one root)
- **Saves**: Mono IMT conflict-thunk for `IExposable::ExposeData` mis-dispatches to the wrong method
  (`AnythingToStrip`) → corrupt saves. Workaround shipped = install-time IL rewrite of the 4 `callvirt`
  callsites to a direct concrete call (bypasses the IMT). The concrete method's JIT'd code is correct;
  only the interface dispatch is wrong.
- **Loading / this brief**: stack-local object pointer reloads as 0 before a virtual/interface call.
- Both center on the **vtable/IMT dispatch + register/stack state around it**.
- We pin dynarec to **-O0** because -O1 makes it worse (a separate known regression). `STRONGMEM=4`
  (strictest) is required for correctness. `WEAKBARRIER`/`X87DOUBLE` tuning shifts the phase odds. All of
  this points to a **memory-ordering / register-liveness / spill-reload** sensitivity.

## Questions for review
1. Given `mov rax,[rbp-0x60]` → RAX=0 while the source object is demonstrably live: what in a **-O0
   arm64 dynarec** would make an rbp-relative reload return 0? Candidates we're weighing:
   - a **spill/fill** bug: the value was spilled to `[rbp-0x60]` in an earlier block and the store was
     dropped/mis-addressed, so the fill reads stale/zero;
   - a **guest-RBP vs native-frame** mismatch (box64 computing `[rbp-0x60]` against the wrong base after a
     block transition / after the signal frame);
   - **register liveness lost at a block boundary** (the fan-of-zeros suggests several guest regs were
     flushed and not restored — e.g. a mis-synced `emu->regs` write-back on a branch or call);
   - an **FP↔GPR aliasing** issue (an XMM/x87 value clobbering the ARM reg backing a guest GPR).
2. Is the fan of zero registers (RAX/RCX/RSI/RDI/R8/R10 all 0) a known signature of a specific box64
   codegen path (e.g. a helper call that doesn't preserve caller-saved guest regs, or a `getGPRToSpill`
   set)? Which x86 caller-saved set does that match?
3. This is right before a **virtual/interface dispatch**. Does box64 have special handling around
   indirect `call [reg+disp]` / IMT thunks (SMC on JIT-patched thunks, block linking) that could corrupt
   the object register reloaded just before it?
4. Best **instrumentation** to convict the exact instruction, given: RIMDROID_* env is **stripped** by
   box64 from the guest environ (use file triggers / hardcoded addresses); hot per-instruction tracing
   **hangs** the load; invalidating a live dynablock to retrofit a trace **crashes**; the only proven-safe
   live hook is the **SMC-write fault**. We can already dump GPRs + code bytes + stack at the fatal fault.
   Next idea: at the faulting guest RIP, one-shot-log the STORE to `[rbp-0x60]` earlier in the same block
   (watch that stack address). Or a minimal standalone x86_64 ELF reproducing `store local; …; reload
   local; cmp [reg]` across a block boundary + an indirect call. Which is most likely to isolate it?
5. Known upstream box64 (ptitSeb) issues about: rbp-relative spill/reload correctness at -O0, register
   state loss across block boundaries or helper calls, or vtable/interface-call miscompiles under Mono?

## Assets
- box64 fully ours (`github.com/udarmolota/rimdroid-box64`, root `./box64`); can add instrumentation and
  rebuild fast. SIGSEGV logger already dumps guest GPRs + 16 bytes @RIP + 32 bytes pre-RIP + stack top.
- Deterministic repro on a Snapdragon 8 Elite / Adreno 830 device (drive via adb; one manual unlock).
- Prior modelling in our notes: the IMT save bug is fully characterised (thunk writes wrong impl); this
  brief is the *loading-time* face with a clean register/stack capture.


---

## CORRECTION (post-Codex, full disasm) — RAX=0 is the return of a VIRTUAL CALL, not the reload
Sequential capstone disasm from the boundary `48 8b 45 a0`:
```
mov  rax,[rbp-0x60]     ; reload local
cmp  [rax],0            ; null-check #1 -- DID NOT FAULT: the reloaded local was a VALID pointer
call 0x322dd700
mov  rax,[rax]          ; rax = object's MonoVTable ptr
call [rax+0xc0]         ; VIRTUAL/INTERFACE dispatch
mov  rdi,rax
cmp  [rax],0            ; <== FAULT: RAX = the RETURN of call [rax+0xc0] = 0
```
So the earlier "reload zeroed / register-state loss" framing is WITHDRAWN. The null is the RETURN VALUE
of the vtable dispatch `call [rax+0xc0]`. The fan of zero regs is caller-saved-after-call. Revised
question: does box64 mis-dispatch `call [rax+0xc0]` (wrong vtable slot -> wrong method -> null return,
same family as the IMT save bug), or did the correct virtual method legitimately return null? Best next
instrumentation: one-shot trace at the call site — log RAX (vtable) + the resolved target [RAX+0xc0] +
whether execution branches to the correct MonoMethod.
