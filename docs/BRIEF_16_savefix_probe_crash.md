# BRIEF: RimWorld 1.6 save crash = our own box64 save-fix scanner faulting (4 probe holes, all patched — verification pending)

**Date:** 2026-07-14 · **Device:** Samsung S25 (Adreno 830, Android 16) · **Game:** RimWorld 1.6.4871 (Odyssey), vanilla, no mods
**Status:** 4 fixes landed in `box64/src/dynarec/dynarec_native.c`; awaiting the next on-device save to confirm.

## Symptom

Manual save in a fresh 1.6 colony → game process dies with SIGSEGV, every time. Reproduced 5× today.
Player.log always shows (grep `RIMDROID SEGV`):

```
RIMDROID SEGV Signal 11: si_addr=<varies>, TRAPNO=14, RIP=0x32090053(box64/__tls_get_addr + 0x13), prot=0
RIMDROID SEGV regs: RDI=0x3f115aeec0 ...        <- RDI identical every crash
```

Observed si_addr across crashes: `0x7100000063`, `0x704a5bba64`, `0x7000000064`, `0x703f1999fe`, `0x40040064`.
Mono's own dump shows an EMPTY managed stacktrace (box64 can't unwind) — do not chase it.

## Key insight #1 — the guest RIP is a red herring

`RIP=box64/__tls_get_addr` does NOT mean the TLS wrapper is at fault: the guest RIP freezes at the
bridge while NATIVE box64 code runs. Symbolizing the **native** pc (`RIMDROID SEGV native pc=librimdroidlinker.so+0xNNN`)
against the UNSTRIPPED build (`app/build/intermediates/cxx/Debug/*/obj/arm64-v8a/librimdroidlinker.so`,
`llvm-nm -S` + find containing symbol) landed every crash inside **`rd_savefix_repair`** —
our OWN RimDroid save-fix (IMT ExposeData cell repair, dynarec_native.c ~line 750+), which runs from
`FillBlock64` when the corrupt-conflict-thunk detector fires during JIT block compilation.

So: **the save-bug band-aid was killing the save it exists to protect.** The detector firing also proves
the box64 IMT-thunk mis-build STILL occurs on 1.6 post-qsort-fix (RIMDROID_SAVEDIAG is NOT set on the
device, so the repair could only have been entered via the real corruption-detect path).

## Key insight #2 — the crash was always a bad-pointer READ inside the repair scan

`rd_savefix_repair` walks Mono internals lock-free (vtable candidates, class→ifaces/offsets arrays,
domain→jit_code_hash buckets). Its readability guard `rd_rd()` had FOUR successively-discovered holes,
each producing one of the observed crashes:

1. **Wildcard window**: `rd_rd` blindly accepted any pointer in `0x60_0000_0000..0x80_0000_0000`
   (fallback because getProtection_fast doesn't track Mono's heap). All the `0x70xx…` si_addrs sailed
   through it. → replaced with a real page probe.
2. **Unguarded array walks**: base pointer checked once, then `ifaces+i*8` (≤64 KB), `offs+i*2`,
   `buckets+bk*8` (≤8 MB!) walked past the end of the mapping. → every step now guarded.
3. **mincore() blesses PROT_NONE**: mincore reports "mapped" for guard pages that still fault on read
   (Mono/BDWGC uses them). → probe switched to `write(devnull_fd, addr, 1)` — the KERNEL attempts the
   read; returns EFAULT for unmapped AND unreadable alike. Positive results cached per page
   (direct-mapped 4096-entry, negatives never cached).
4. **getProtection_fast lies** (the `si_addr=0x40040064, ERR=5` crash): box64's guest-side prot table
   said READ for `klass=0x40040000` — a Mono JIT **execute-only** page (Android XOM); the `ldrh` at
   `klass+0x64` faulted with "present but read denied" ONE instruction after the guard passed
   (confirmed by disassembly: `rd_rd` cbnz → `ldrh w8,[x8]`). → `rd_rd` no longer short-circuits on
   getProtection_fast; the kernel probe is the only truth.

Same hardening applied to the sibling scanners `rd_find_code_by_name` / `rd_name_of_code`
(bucket walks, `ji+8/ji+0x10`, `m+8/m+0x18`, string reads probed to their full printed length).

## Current rd_rd (post-fix) contract

`rd_rd(p)` = kernel-verified readable for bytes p..p+7, via cached `write(/dev/null, page, 1)` probe.
No trust in box64 prot tracking, no address-range whitelists. Worst case on bad memory: the repair
logs/skips ("code not found" / reverse-find FAILED) — it can no longer crash on a read.

## What is NOT yet ruled out (if the next save still crashes)

- **The final cell WRITE** `*(uintptr_t*)implslot = code;` — only read-probed. A read-only vtable page
  would fault with the WRITE bit set (ERR=6/7 in the SEGV line). Fix would be an mprotect or a
  write-probe variant.
- **Positive-cache staleness**: a page probed readable, later mprotected unreadable (GC). Window is
  real but small; fix = drop the cache (repairs are rare, syscall-per-read is fine).
- Faults OUTSIDE rd_savefix_repair: symbolize `native pc` against the matching unstripped .so FIRST —
  offsets shift every build, never compare across builds.

## Bigger picture / follow-ups

- IMT-thunk mis-build (the deep bug) is ALIVE on 1.6 — the qsort_r fix (wrappedlibc.c) fixed sorting
  corruption (audio, surgery crash) but not this. The repair is the only 1.6 protection: the
  Assembly-CSharp bsdiff save-fix (SaveBypass) covers known 1.5 hashes only → no-op on 1.6.
- Once a save survives, check Player.log for `[RD-SAVEFIX]` lines: `cell → code` (repaired) vs
  `code not found` (corruption present, class code not JIT'd yet — the known late-JIT gap).
- Real fix target: the IMT conflict-thunk mis-build in box64's dynarec (see memory
  save_bug_investigation / deep_fix_investigation).

## Diagnostics kept in the tree (cheap, always-on)

- `RIMDROID ELFADD/ELFREMOVE` (box64context.c) — dlopen/dlclose timeline; also fixed a real (but
  unrelated to this crash) race: growing `ctx->elfs` now copy+atomic-publish+leak-old instead of
  in-place realloc under lockless readers.
- `RIMDROID TLSBAD/TLSSLOT` (wrappedldlinux.c my___tls_get_addr) — TLS descriptor/slot validation with
  guest-caller logging + scratch-slot survival; also refreshes stale per-thread TLS (tlssize/n_elfs
  behind context), not just NULL.
- `RIMDROID SEGV regs` (signals.c) — register dump (no derefs) alongside the SEGV line.
