# RimDroid — Tester Registry & Test/Fix Ledger

Living log of who is testing, on what device, what their problem is, and **every test/fix we have
given them and its result**. Purpose: never burn a tester attempt on a test we already ran or that
can't answer the question. Update this the moment we hand a tester a test or get a result back.

> Full device-compatibility matrix lives in memory `device_compat.md`; deep-bug analysis in
> `save_bug_investigation.md` / `known_bugs.md`. This file is the **operational** view (per-tester
> attempts), not the engineering write-up.

Status legend: 🔴 blocked · 🟡 testing in progress · 🟢 playable · ⚪ idle / waiting on tester

---

## ✅✅ CURRENT STATUS 2026-06-24 — LAUNCH solved on ALL devices; 3 open bugs (priority order)
The game now **launches on every tester device**, including weak-Vulkan-1.1 Mali (G57) and **Helio G99**
(fixB5 false-MAPERR + compat mode + per-GPU driver picker + fresh Turnip v26.2). The per-tester "🔴 black
screen / won't launch" rows below are HISTORY — launch is no longer the blocker. **Open bugs, fix in order:**
1. **MODS crash the game's LOADING** on previously-problematic devices (725, Mali-G57). Root: box64 miscompiles
   .NET self-replicating `Parallel` tasks in RimWorld's parallel def-load (`ShortHashGiver.GiveAllShortHashes`)
   → `NullReferenceException` in the Task worker → "Caught exception while loading play data … Resetting mods
   config" → def DB half-built → broken. **Workaround to test:** Extra env `BOX64_MAXCPU=1` (needs the box64
   `my_sched_getaffinity` build so Mono's ProcessorCount actually drops to 1 → `Parallel.ForEach` runs serial
   → buggy path skipped → mods load). Confirmed signature in `rimdroid_logs_20260624_114052` (Mali-G57+Harmony).
2. **SAVES** corruption (pawns saved/loaded empty — IMT mis-dispatch `Pawn.ExposeData`→`AnythingToStrip`).
   Workaround = RimDroidSaveFix Harmony mod.
3. **Vulkan-1.1 render ARTIFACTS** varying load-to-load on old-Vulkan-1.1 GPUs (Mali-G57, Helio G99) — cosmetic,
   not a crash/black. Lowest priority.

---

## Reusable test recipes

Applied via **Settings → Extra env vars** (space-separated `KEY=VALUE`; applied last, override defaults).
No rebuild needed for any of these.

| Recipe | Env | What it tells us |
|---|---|---|
| **Dispatch log** | `RIMDROID_DISPATCH_LOG=1` | On a JIT'd null-deref (the save NRE), dumps `rip / fault / rax / r10 / rdi`. `r10` = IMT method-cookie → distinguishes r10-clobber (dispatch bug) from reading empty data. **Must run during the failing action (SAVE for the writer bug), not its consequence (load).** |
| **Writer watch** | `RIMDROID_WATCH_ADDR=<addr>` | Logs live accesses to a watched slot + names the writer RIP. For pinning the pawn-save writer. |
| **Bad-jump dump** | *(always-on in the diagnostic build)* | On a control-transfer to a bogus address (instruction-fetch fault, no block at target — the 725 `0x4034…` crash), prints `[RD-BADJUMP]` with `old_ip` (source block), full GPRs, and stack@rsp. Names the miscompiled branch. Trigger is so specific it only fires on this crash class → no env var (added 2026-06-18, box64 signals.c). Debug APK: `RD/rimdroid-0.1.7-debug-badjump.apk`. |
| Box64 conservative codegen | `BOX64_DYNAREC_CALLRET=0` / `BOX64_DYNAREC_SAFEFLAGS=2` / `BOX64_DYNAREC_X87DOUBLE=1 BOX64_DYNAREC_FASTROUND=0` / `BOX64_DYNAREC_BIGBLOCK=0` | Make dynarec more conservative. Tunes around *some* miscompiles; does **not** touch the deep "wrong-value" bug (confirmed on 725). |
| Targeted interpret | `BOX64_DYNAREC_EXCLUDE=<lib.so>` | Run only one library interpreted (correct but slower), rest stays dynarec'd. Best "playable-now" shot for a crash localized to one lib (e.g. `libmonobdwgc-2.0.so`). |
| Full interpreter | `BOX64_DYNAREC=0` | 100% correct, far too slow for a Unity game (≈unplayable). Last-resort correctness oracle only. |

---

## Active testers

### T1 — ClockSignificant7495 — Mali (disappeared colonists) ⚪
- **Device:** **Poco X7 Pro 12/512** (Dimensity 8400-Ultra, Mali-G720); instance `RimWorld_1.5`, driver = System (phone).
- **Problem:** Colonists vanish from saves. Root: **pawn save corruption** — `Pawn.ExposeData` NREs at
  **write** time, the scribe swallows it, the pawn is written as an empty `<li />` tag. Bug is **phasic**
  (good→bad phase, reboot resets). This is the WRITER.
- **Fixes shipped to try:** box64 hotpage hardening — `N_HOTPAGE 128`, `MARK 256`, NEVERCLEAN-on-evict
  (in a build; never cleanly confirmed).

| Date | Test given | Result |
|---|---|---|
| 2026-06-18 | `RIMDROID_DISPATCH_LOG=1` + **load** a bad save | Confirmed save already corrupt (`<li />` empty pawns). Read-side `r10` **valid** → load NRE is just "reading empty data", not a dispatch clobber. **Did not target the writer — wasted attempt** (load is the consequence, not the cause). |

- **Next test (targets the writer):** `RIMDROID_DISPATCH_LOG=1`, debug on → load a save that **still has
  colonists** (or new game) → play a minute → **SAVE** (repeat a few times per session) → send logs. If
  `RD-DISP` fires during the **save**, we finally see `r10`/`rdi` of the writer NRE.

### T2 — Henrique Pires Lima — Adreno 725 (#2, from YouTube) 🔴
- **Device:** Adreno 725, Turnip `ad07XX_regular`, ~11 GB RAM; instance `msf:1000556869`.
- **Problem:** Loads to the **main menu** (renders fine), then **SIGSEGV in Mono GC**
  (`libmonobdwgc-2.0.so`), control jumps to `0x4034000000000000` (= IEEE-754 double `20.0` used as a
  code pointer). = the deep box64 **"wrong-value" miscompile** (same family as saves/FPS). Crash frame
  sits on a GC `lock cmpxchg`. Identical across all flags → it's codegen, not a tunable.

| Date | Test given | Result |
|---|---|---|
| 2026-06-17 | env flag #1 `BOX64_DYNAREC_CALLRET=0` | Just flashes; one try → endless loading screen. Same crash. |
| 2026-06-17 | env flag #2 `BOX64_DYNAREC_X87DOUBLE=1 BOX64_DYNAREC_FASTROUND=0` | Flashes. Same crash. |
| 2026-06-17 | env flag #3 `BOX64_DYNAREC_SAFEFLAGS=2` | Flashes. Same crash. |
| 2026-06-17 | env flag #4 `BOX64_DYNAREC_BIGBLOCK=0 BOX64_DYNAREC_CALLRET=0 BOX64_DYNAREC_SAFEFLAGS=2` | Cropped image every time, then same `0x4034…` crash in Mono GC. |
| 2026-06-18 | `BOX64_DYNAREC_EXCLUDE=libmonobdwgc-2.0.so` | "Doesn't work either. Just flashes the menu and goes dark." (no log yet). |
| 2026-06-18 | debug-badjump APK v1 (0.1.8) | "Cropped image, crash back to instances." Same `0x4034…` Mono-GC crash, but **`[RD-BADJUMP]` did NOT fire** — my gate (`si_addr==x64pc && !db`) was wrong: the bad jump faults INSIDE a dynablock (db!=NULL) and emu->ip wasn't synced to the target. FIXED → fire on any **non-canonical** fault addr regardless of db. New build: `RD/rimdroid-0.1.8-debug-badjump2.apk`. |

- **He is already on `-O0`.** The one `-O1` GitHub-Actions build was NEVER distributed (replaced by `-O0`
  before any tester got it), so his `0.1.7 (17)` = `-O0`. ⇒ **`-O0` does NOT fix this crash** (unlike the
  S25U GetTypes NRE, which `-O0` did fix). His GC crash is a *deeper* miscompile that survives `-O0`, and
  env flags don't touch it. Note: `versionCode 17` covers both `-O1` and `-O0` builds — the version string
  alone can't tell them apart; only distribution history (above) settles it.
- **Always-asked red herring:** `cannot dlopen(libudev.so.0)` — **benign**, every device prints it
  (Android has no libudev; box64 falls back). NOT the cause; working devices (S25U) show it too.
- **CONCLUSION: genuine deep box64 wrong-value bug; -O0 + all env flags exhausted.** Real fix = box64
  dynarec dive. Crash = control transfer to `0x4034000000000000` (double `20.0`) in Mono GC.
- **NEXT — diagnostic debug build sent (2026-06-18):** `RD/rimdroid-0.1.7-debug-badjump.apk`, RD-BADJUMP
  **always-on** (no env var to forget). Debug signature ≠ release → tester uninstalls + re-downloads game
  (~200MB, accepted). One crash run → send logs → `[RD-BADJUMP]` names `old_ip` (source block) + the
  register holding `20.0`. Then disassemble that block in `libmonobdwgc-2.0.so` (or JIT region) → find
  the miscompiled instruction.

| 2026-06-19 | badjump build | "Goes black forever, still trying." Log: SAME GC crash but target is now a
  **canonical** JIT addr `0x321c8a60` (not the double), `libmonobdwgc+54071` → **`[RD-BADJUMP]` missed it**
  (my non-canonical filter). FIXED → also fire on instruction-fetch faults (`pc == si_addr`), catches any
  bad jump (code change in signals.c, not yet built). |

- **The deep GC bug is GPU-INDEPENDENT:** the SAME crash (jump to `~0x321c87xx`, `libmonobdwgc+54071`) also
  hits T4's Mali-G57 (`0x321c8740`). Both devices land in the same `~0x321c87xx` box64 JIT/bridge region →
  strong lead. It's box64/Mono, not the GPU. Also reproduced locally as the save bug ([[save_bug_investigation]]).

### T4 — "Вася Пупкин" — TECNO POVA 4 Pro (Mali-G57 MC2) 🔴
- **Device:** TECNO POVA 4 Pro, **Mali-G57 MC2** (Helio G99-class), System/stock Vulkan driver. App 0.1.7.
- **Problem:** **same deep Mono-GC crash as T2** — `libmonobdwgc+54071 → ??? [0x321c8740]` (canonical JIT
  addr). GL DID init (Zink GL4.3 over Mali Vulkan 1.1), so it's not a render failure — the GC crash is the
  blocker. (Secondary: Mali-G57 lacks some Zink base features — logicOp / fillModeNonSolid /
  shaderClipDistance — would matter for rendering IF we got past the crash; no Turnip for Mali.)
- **Same root as T2** → one box64 dynarec fix covers 725 + Mali + saves + FPS. Give him the broadened
  RD-BADJUMP build once built.

| Date | Test given | Result |
|---|---|---|
| 2026-06-20 | `badjump5` (stderr dual-write), debug ON | **`[RD-BADJUMP]` STILL not captured (3rd miss) — ROOT CAUSE FOUND.** Crashes present: `0x4034000000000000` (×2, libmono+14df92) + `0x321c8820` (libmono+54071, inside box64's own JIT region). box64 delivers these via `native_br`→**`EmitSignal()`** (`dynarec_native_functions.c:245/249`), which bypasses BOTH the native signal handler (signals.c) AND the DynaRun `!block` loop (dynarec.c) → that's why all 3 hook placements missed. **Correct hook = `native_br`/`native_gpf`** (R_RIP=bad target + old_ip=source both live there). Decision: **pause crash-capture, pivot to the local deterministic Y700 save repro** (same bug, native handler actually fires there). |
| 2026-06-19 | `rimdroid-0.1.8-debug-badjump4.apk` (dynarec-level hook), 5 runs | **Same `0x4034000000000000` crash confirmed** (Player-prev.log: `libmonobdwgc+d7c7c→14ce37→14df92→??? [0x4034…]`). Audio worked (AAudio, 0 underrun, reached menu). **`[RD-BADJUMP]` NOT captured** — not a hook failure: it prints via `printf_log`→`rimdroid.log`, which is **NOT rotated**, so his later (hang) run overwrote the crash run's box64 log. Mono's trace survived only because Unity rotates `Player.log`→`Player-prev.log`. **FIX needed:** dual-write `[RD-BADJUMP]` to `stderr` (+`fflush`) so it lands in `Player.log` (rotated) → survives a relaunch. Then rebuild = badjump5. |

### T3 — "MediaTek Tianji 8400" — Dimensity 8400, 12GB RAM 🟡
- **Device:** MediaTek Dimensity 8400 (mid-high), 12 GB RAM. Confirmed: game runs + colonists present
  (hasn't tried saves yet — the phasic pawn-save bug may bite him later, watch for it).
- **Problem 1 — CJK text missing:** in-game Chinese blank, **Russian fine**. CONFIRMED via 2026-06-18 log
  (game in Chinese): no FreeType errors, only Android system fonts mapped (Roboto/vivoSans/Droid — those
  are the launcher/ART side, not the emulated game); NO CJK font in the game's env. RimWorld's bundled GUI
  font has Latin+Cyrillic but not CJK; for CJK Unity falls back to OS fonts. **UnityPlayer.so scans
  `/usr/share/fonts` + `HOME`/`XDG_DATA` dirs** (`GetOSInstalledFontNames`, ext ttf/ttc/otf/dfont) for
  fallback fonts — empty in our emulated Linux → blank CJK. **FIX (launcher-level, no box64): drop a CJK
  font (Noto Sans CJK / Source Han) into a dir Unity scans — `/usr/share/fonts` is unusable (Android root
  RO), so use `$HOME/.fonts` or `$XDG_DATA_HOME/fonts` (writable, set the env in GameLauncher).** Confirm
  the exact dir on the tester. Fixes CJK for all (zh/ja/ko). Not tester-blocked to implement.
- **Problem 2 — low FPS (<20):** general performance limit. box64 currently built `-O0` (correctness over
  speed) + single-thread render (`-force-gfx-direct`) + emulation overhead. Real lift = the deep box64
  fix (the same `-O1` miscompile we're chasing via T2's RD-BADJUMP) → then `-O1` + FPS for everyone.
- **"Tree flicker":** RimWorld trees sway (normal); whether his "flicker" is that or a render artifact is
  unclear — need a short video.

---

### T5 — OnePlus 13 — Snapdragon 8 Elite / Adreno 830 🟢
- **Device:** OnePlus 13, Snapdragon 8 Elite (Adreno 830), 24 GB RAM. App **0.1.7** (pre-new-audio).
- **Result (2026-06-19):** **playable out of the box.** Launches in ~1.5 min with **all DLC**,
  30-60 FPS, "± playable". No sound (expected — 0.1.7 predates the on-device sound pack; offer 0.1.8).
- **Significance:** high-end Adreno = clean run. Confirms the deep Mono-GC "wrong-value" miscompile
  (T2/T4) is **NOT universal** — it bites specific devices/phases, not flagship Adreno. Good baseline.

### T6 — Moto Edge 50 — Snapdragon 7 Gen 1 / Adreno 644 🔴
- **Device:** Moto Edge 50, **Snapdragon 7 Gen 1** (Adreno 644), 12/512. App **0.1.7**, debug OFF,
  driver = Turnip v25 (**correct** for a644 per [GpuInfo](app/src/main/java/com/rimdroid/GpuInfo.java) —
  a644≥630 needs modern Turnip; legacy ad06XX hangs Zink on it).
- **CORRECTED 2026-06-20: PLAYABLE with the System driver — NOT blocked.** Earlier "stuck/crash" verdict
  was from his **Turnip v25** run (rimdroid_logs_20260619_090401: Zink GL4.3 over v25, 2× SwapWindow, then
  the `0x4034…` Mono-GC crash). On **System (phone Vulkan) driver the game LOADS and runs** — he only finds
  it "uncomfortable" (FPS/smoothness), and is asking for a faster driver. So v25 triggered the deep crash for
  him; System avoids it. (Consistent with the deep bug being timing/per-boot-sensitive — the driver shifts
  init timing, so it fires on v25 but not System. Bug is still CPU-class, not GPU.)
- **Driver advice given:** try Turnip variants for more FPS than System, in order: "Turnip Adreno 7xx
  (anti-flicker)" (ad07XX) → "Turnip Adreno 830/840" (the _840 build, not v25). If a Turnip loads → use it
  (faster than System); if all crash → keep System (playable, just slower). Do NOT give legacy "Turnip
  Adreno 6xx" (hangs Zink on a644 per [GpuInfo](app/src/main/java/com/rimdroid/GpuInfo.java)).
- Unknown if he hits the save-corruption bug over longer play — watch for it.

### T7 — Realme P4x 6/128 — Mali (budget) 🟢
- **Device:** Realme P4x, 6 GB RAM / 128 GB, Mali GPU (budget/entry tier).
- **Status: playable** — game runs, **saves verified OK** (no colonist loss). NOT hit by the deep `0x4034`
  bug despite being budget-tier → confirms that bug is **phasic / microarchitecture-dependent, not a hard
  "weak CPU always crashes" law.**
- **His actual issues (separate, not the deep bug):**
  1. **Crash on caravan formation — suspected OOM.** Only 6 GB RAM; caravan forming is one of RimWorld's
     heaviest ops. Resource limit, not the miscompile. Levers: Boehm GC heap sizing (we set it from device
     RAM; `overridable via env`), overall RAM pressure.
  2. **Low FPS** — general budget-SoC perf ceiling (emulation + forced `-O0`). The deep-bug fix → `-O1`
     would help here.

### T8 — voider6969 — Mali-G615 MC2 (MediaTek) ⚪
- **Device:** Mali-G615 MC2 (ARM proprietary) — MediaTek, ~Dimensity 7200/7050 class. Exact phone model
  NOT in the log (our launch header logs GPU, not `ro.product.model`). Found via email.
- **Log:** `RimDroid log 1.zip` — **app version 0.1.6 (OLD)**, renderer ZINK_ZFA, driver = System (phone),
  render scale 72%, box64 STRONGMEM=4 SAFEFLAGS=1. Predates fixB1 + recent work → low value for current state.
- **Notable:** Zink warns **"Mali-G615 MC2 doesn't support base Zink requirements → incorrect rendering"**
  + "Unable to initialize any audio device (even FMOD nosound)". → Falls in the **Mali-present/Zink** bucket
  (same class as T4 Пупкин G57 black screen), NOT the deep `0x4034` CPU bug.
- **TODO:** ask him for the phone model + a fresh log on current build; consider giving fixB1.

### T2 UPDATE 2026-06-21 — Enrico's Adreno 725 PLAYS on fixB5 (reclass fired) — fixB5 CONFIRMED for the 725
Log rimdroid_logs_20260621_145409: **`false-MAPERR fix ACTIVE` reclass=1** (fixB5's fix fired on the 725),
42193 SwapWindow frames + TWO scene transitions (menu→in-game) → he **loaded into a game and created a
colony.** So fixB5 unblocks the 725 class (the original target). CAVEAT: intermittent (~1 in 10 launches gets
past "initializing") + slow + needs his custom box64 env + device tweaks (Tecno VRAM-off + GameTurbo). The
intermittency = the deep PHASIC bug (separate from fixB5's false-MAPERR fix). His EFFECTIVE box64 env:
`BOX64_DYNAREC_WEAKBARRIER=2` (default 1) + `BOX64_DYNAREC_X87DOUBLE=1` (`BOX64_DYNAREC_STRONG_MEM=1` is a
TYPO — real var is STRONGMEM, ignored, so STRONGMEM stayed 4). → CLUE: the deep bug is sensitive to dynarec
memory-model/FP codegen knobs and is random-per-launch → looks like a MEMORY-ORDERING RACE in the generated
code (timing/layout dependent = the phasic/1-in-10 behavior). Tweaks shift timing & mitigate but don't fix.
IDEA: expose box64 tuning (WEAKBARRIER/X87DOUBLE) as advanced per-instance settings for power users like him.

### T4 UPDATE 2026-06-21 — Пупкин (Mali-G57) on fixB5: crash FIXED, but Mali BLACK SCREEN remains (front A)
Log rimdroid_logs_20260622_003436 (System driver): `false-MAPERR fix ACTIVE` reclass=1 → fixB5 fired,
RD-SEGV=0 (no crash), reached menu (RimWorld 1.5.4104, Completed reload), 746 frames rendering — but "didn't
work" = the **Mali-G57 Zink→present BLACK SCREEN** (the separate render issue, NOT the load crash). So fixB5
DID fix his crash (reaches menu now), but the Mali black-screen (front A) blocks actual play. Пупкин is in the
"Mali present/black" bucket, not the crash bucket. NOTE discrepancy: Pova 7 (Helio G100, also MediaTek) PLAYS
while Mali-G57 blacks — understand the GPU/driver difference. Front A (Mali present/black) is still unaddressed.

### T9 — Tecno Pova 7 4G (Helio G100, MediaTek/Mali) 🟢 — PLAYS on fixB5+dlauth
- Had Steam DOWNLOAD problems → given the new build with the **download auth fix + fixB5**
  (`rimdroid-0.1.8-debug-fixB5+dlauth.apk`). Result: **download worked AND he PLAYS the game.** First
  Mali/MediaTek device confirmed playable on clean fixB5.
- Caveat: no old-version baseline, so can't fully attribute playability to fixB5 (might have played anyway).
  TODO: get his LOG → check `false-MAPERR fix ACTIVE` (reclass): if it fired → fixB5 actively fixed his
  load crash; if not → he lacked the bug, fixB5 dormant (still proves fixB5 is harmless on Mali).

### T10 — Jhosept Jaramillo Diaz — Infinix Note 50s 5G (Mali-G615 MC2, MediaTek) 🟢 — PLAYS for HOURS
System driver, **Vulkan 1.3** → Zink works → **plays for hours** (91172 frames in a long-session log;
rimdroid_logs_20260621_132827). Confirms: Mali-G615 (Vulkan 1.3) is in the GREEN zone (vs Mali-G57 Vulkan 1.1
= black). Two complaints: (1) **FPS drops a lot over a long session** even on low settings/small tiles —
= RimWorld colony growth + he was on the -O0 DEBUG build → give him the **-O1 FPS release**
(rimdroid-0.1.8-FPS-release.apk); (2) **game closes after ~2h** (rimdroid_logs_20260621_103956:
SIGSEGV→destroyed-mutex→SIGABRT; abort-state dump hides the fault site — long-session deep-bug or memory,
separate target). Was wrongly told "not supported" — actually fine. App version 0.1.8.

### T11 — Xiaomi Pad 7 Pro (Snapdragon 8s Gen 3 / Adreno 735) 🟢 — "all great"
Reported everything works great. High-end Adreno → consistent with the "Adreno flagships play cleanly"
pattern. CAVEAT (per the don't-be-rosy lesson): verbal report, no log — unknown which build + how long he
played (whether the deep phasic bug shows in a long session). Counts as a positive green Adreno signal; get a
log + play-duration to confirm depth if possible.

## Resolved / reference (see device_compat.md for the full matrix)
- Mali-G720 black textures — FIXED (MESA_EXTENSION_OVERRIDE s3tc/rgtc/bptc).
- F5 / Adreno-725 **black screen** (distinct from T2's GC crash) — root fix MAPERR→ACCERR in signals.c.
- S25U / Adreno-840 release-APK startup crash — FIXED (box64 Android build forced to `-O0`).
- Realme P4x (Mali) — works.
