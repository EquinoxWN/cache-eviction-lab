# ADR 0003: W-TinyLFU on a vendored copy of the probabilistic-sketches Count-Min sketch

- **Status:** Accepted

## Context

W-TinyLFU needs a frequency estimate for every key it has seen recently, in memory that does not
grow with the number of keys: a Count-Min sketch that never under-counts and can be aged by halving.
The sibling project probabilistic-sketches (same author) has exactly that class,
`portfolio.sketches.CountMinSketch`, with `add(long)`, `estimate(long)`, `halve()` and an
explicit-dimensions constructor, documented for TinyLFU reuse and covered by 18 tests (with its
MurmurHash3 helper). That library is not published to any Maven repository. Caffeine, the reference
implementation, uses a different sketch: 4-bit counters packed 16 to a `long`, saturating at 15,
with an optional doorkeeper Bloom filter and an adaptive window.

## Decision

- **Vendor, do not depend.** `CountMinSketch.java` and `Murmur3.java` are copied into
  `portfolio.cacheevictionlab.sketch` with a header naming the source file in
  EquinoxWN/probabilistic-sketches; only the package line differs. Their test classes are copied
  the same way, so the copy keeps the guarantees it was accepted with (never under-counts, halving,
  reference hash vectors).
- **Use the sketch as it is:** depth 4, conservative update, 64-bit counters, `halve()` after
  `10 x capacity` counts. No 4-bit packing and no doorkeeper in M1.
- **Width 4 x capacity** (at least 64, capped at the sketch's 2^24-counter limit), not 1 x capacity.
- **Fixed 1% window and 80% protected segment**, as in the paper, without Caffeine's hill climbing.

## Consequences

- The build stays offline and reproducible (no JitPack, no package-registry token), at the cost of
  two copies to keep in sync. The header names the source, and the copied tests fail if the copy
  drifts in behaviour.
- **Width, measured:** with width = capacity, W-TinyLFU's hit rate was lower in every one of the 25
  trace and size cells; width 4 x capacity raised it by 0.05 to 1.22 points (mean 0.51)
  (docs/results/m1.md, Improve). Only these two widths were measured.
- **Memory:** 4 rows x 4 x capacity x 8 bytes = 128 bytes of sketch per cache entry, 8 to 16 times
  Caffeine's packed sketch (one `long` per entry, rounded up to a power of two). This changes
  footprint, not hit rate; packing to 4-bit counters is a possible M2 change, and it would also add
  saturation at 15, which changes admission decisions for very hot keys.
- **No adaptive window:** on cluster045 and cluster052 ARC beats this W-TinyLFU at every measured
  size. Caffeine sizes its window adaptively for workloads where a fixed 1% window does not fit;
  M1 reports the fixed-window result rather than tuning W-TinyLFU per trace.
- A new key whose counters all collide with frequent keys can still be over-counted and admitted;
  `PolicyBehaviourTest.wTinyLfuRejectsOneHitWondersThatWouldDisplaceFrequentKeys` allows that
  for at most 5 of 150 frequent keys (2 in its fixed sequence, at width 1x and 4x alike) and
  requires LRU to keep none.
