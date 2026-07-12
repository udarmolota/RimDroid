# Brief v15 — SMOKING GUN: 1.45 MILLION live zink batch states (0 ever reused) — a fence/flush storm during Unity's atlas bake leaks ~12k batch states per second. Validate the diagnosis and the fake-sync fix.

## The measurement (one run, instrumented zink)
We added a per-flush log of `ctx->batch_states_count` to zink 25.2.4. During RimWorld 1.6's texture-atlas
bake (Unity 2022.3 GLCore over zink/Turnip/kgsl, Android):

```
RDZFA reap: 0 reaped, live_states=1454201, freed 0MB     ← 1.45 MILLION live batch states
(15 such lines within ONE millisecond → zink_flush is being called thousands of times per second)
```

This finally explains the entire memory wall (previous briefs v12-v14):
- RSS climbs to ~5.6GB → LMK kill; smaps showed 98% = kgsl-3d0 GPU BOs;
- kgsl histogram: the growth class is ~4-32MB BOs, 613→1105 mapped regions... plus a monotonic flood of
  Turnip-internal allocations that BYPASS zink's VkAllocateMemory accounting (zink's own bucket for
  4-32MB showed only 104 pieces / 839MB while kgsl had 1105 / 4.45GB);
- each zink batch state owns a VkCommandBuffer/pool (Turnip CS chunk BOs — internal, unaccounted),
  fences, hash tables, dynarrays. Even at a few KB-MB each × 1.45M = the missing gigabytes.
- Eager reaping of COMPLETED states frees nothing — the reuse/reap conditions
  (`fence.submitted && (last_finished || fence.completed)`, head-of-list) are NEVER true for these
  states: 0 reaped out of 1.45M.

## Who calls flush 12k times/second? — UPDATED after reading zink_flush source (25.2.4)
Verified from `zink_context.c:4047` (zink_flush) and `zink_batch.c` (get_batch_state):
- **`glFenceSync` itself does NOT retire a batch state**: with work + PIPE_FLUSH_DEFERRED, zink sets
  `deferred_fence = true` and KEEPS the current batch open (no flush_batch, no new state). With no work,
  it reuses `last_batch_state`'s fence. So fence *creation* is innocent.
- **The reuse/reap path is strictly HEAD-OF-LIST**: `get_batch_state` pops `ctx->batch_states` only if the
  HEAD is `fence.submitted && (last_finished || fence.completed)`. Therefore **a single never-submitted
  (or never-completing) state at the head permanently blocks recycling of the ENTIRE list** — which is
  exactly what we observe: 1.45M states, 0 reused. The leak mechanism is READ FROM SOURCE, not guessed.
- Remaining interpretation (90%, not yet counter-verified): the 12k/sec flush storm is **Unity POLLING
  `glClientWaitSync(timeout≈0)` in a tight loop** while uploads continue between polls — each poll on a
  pending deferred fence forces a flush of the current (small) batch → retires one more state per poll.
  Unity resolved glFenceSync/glClientWaitSync/glDeleteSync via glXGetProcAddressARB (confirmed in logs);
  call frequency not yet instrumented.
(ZINK_DEBUG=flushsync is active; our upload-pacing glFlush fires only every 192MB — neither explains
12k/sec.)

## Questions
1. Given the head-blocked-list mechanism (see above): what most plausibly parks a never-submitted /
   never-completing state at the HEAD of ctx->batch_states in 25.2.4? A deferred fence whose batch is
   flushed by a LATER path without marking the original state submitted? A tc-fence retired unsubmitted?
   And do 25.3's "check ctx batch states first when finding a usable one" / "stop trying to oom prune
   batch states" change the head-only policy (i.e. would a cherry-pick let recycling skip a poisoned
   head)? A minimal zink-side hardening we're considering: in the reap/reuse path, SKIP (not stop at)
   heads that are submitted-but-stuck, and/or force-submit a deferred head older than N flushes — sane?
2. **Proposed cheap fix (box64-side, no Mesa rebuild): FAKE the GL sync objects for the guest** —
   `glFenceSync` → return a dummy handle, `glClientWaitSync`/`glWaitSync` → ALREADY_SIGNALED no-op,
   `glDeleteSync`/`glIsSync` → no-op/true. Unity uses these fences to know when uploads complete before
   reusing/freeing CPU-side buffers. With flushsync active (synchronous submits) plus our 192MB pacing
   flushes, is the data-hazard window (Unity reusing an upload buffer the GPU hasn't consumed) actually
   closed, or do we risk texture corruption? Is there a safer middle ground (e.g. real fence but only
   every Nth, coalescing; or make glFenceSync do a real glFlush-with-submit so the batch is submitted and
   reusable)?
3. Alternative zink-side fix: on fence-create flush, if the batch is empty or the flush is deferred,
   REUSE the current batch instead of retiring it (don't spawn a state per fence). Is there precedent
   upstream (zink used to have "don't flush empty batches" logic)? What's the correct guard?
4. Sanity: does Unity GLCore actually fence per-upload (AsyncUploadManager / TextureStreaming), and can
   it be throttled app-side (QualitySettings.asyncUploadTimeSlice=1 didn't change anything; buffer is
   4MB-configured but a single 250MB host-visible persistent-mapped buffer appears on the upload thread)?

## Context/assets (short)
Android 12GB (S25U), Adreno 830. Same game loads fine via Winlator+DXVK on this device → the demand fits;
our stack's overhead is the problem — now fully named. We rebuild libzfa (Mesa 25.2.4 + our patches) in
~6 min CI; box64 is fully ours (all GL entry points shimmed — the fake-sync fix is ~20 lines there).
All previous layers fixed: kgsl-3GB cap (upload pacing), kopper NULL-dt race (guard), SUBOPTIMAL
recreation storm (disabled), pb_cache capped, eager reaper (inert but present).

## Our intended next step
Implement the fake-sync shim in box64 (with a counter of faked fences), run once: if batch_states stays
flat (~16) and RSS peak drops by gigabytes → root confirmed and probably GAME LOADS. Then decide the
proper long-term fix (zink patch vs shim) based on your answers.
