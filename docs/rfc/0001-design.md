# RFC 0001: cache-eviction-lab design

- **Status:** Accepted (M1 implemented)
- **Author:** EquinoxWN
- **Created:** 2026

## Problem

Every cache has to decide what to throw away when it is full, and the hit rate it keeps depends on
that choice far more than on most tuning. Teams usually pick LRU because it is the default, or read
a benchmark run on synthetic Zipf data, which says little about their own traffic: real request
streams mix one-time keys, scans, bursts and slowly shifting popularity. The question "which policy
keeps my hit rate up, and at what cache size does the difference matter?" can only be answered by
replaying real requests through each policy under identical conditions.

This project builds that comparison: one `Cache` interface, several eviction policies behind it
(FIFO, LRU, LFU, ARC and W-TinyLFU in M1), a harness that streams public production traces through
all of them at several cache sizes, and tests that prove each policy is correct before any hit rate
is compared.

**Users:** backend engineers sizing or choosing a cache (Memcached, Redis, an in-process cache such
as Caffeine) who want evidence from real traffic rather than a default; people preparing for
"design a cache" discussions; contributors to cache libraries who need a small, auditable harness.

## Critical-to-quality measures (Define)

| CTQ | Measure | Target (M1) |
|---|---|---|
| Policy correctness | answers and resident keys that differ from the policy's brute-force model on seeded random get/put/evict sequences | 0 differences over 600,000 operations per policy, capacities 1 to 150 |
| Cache contract | `size() > capacity()` after any operation; a key put and not evicted that is missing | 0 |
| Physical bound | trace and size cells where a bounded policy hits more often than a cache that never evicts | 0 (checked on every replay, exit 3 otherwise) |
| Reproducibility | cells of a re-run that differ from the committed hit-rate table | 0 (CI replays one trace on every push) |
| Data integrity | traces used whose size or SHA-256 differs from the pin; malformed lines dropped silently | 0; every skipped line counted and printed |
| Coverage | traces x sizes x policies reported from runs on the author's machine | 5 traces x 5 sizes x 5 policies = 125 cells |
| Resources | heap needed to replay a 1,000,000-request trace through 25 caches | at most 1 GB; the trace is streamed, never loaded |
| Waste | downloaded traces left on disk after `make bench` | none (deleted; pins and commands kept) |
| Build quality | compiler warnings, format violations, known-vulnerable dependencies | 0 |

## Goals

- **One `Cache` interface** (`get`, `put`, `evict`, plus `contains`, `size`, `capacity`) that every
  policy implements, so the harness never changes when a policy is added.
- **Policies, M1:** FIFO, LRU, LFU (O(1) frequency buckets), ARC (Megiddo and Modha 2003) and
  W-TinyLFU (Einziger, Friedman and Manes 2017) using the Count-Min sketch from
  probabilistic-sketches as its admission filter.
- **Correctness first:** each policy is checked against a brute-force list-based model on random
  operation sequences, against the shared contract, and on hand-checked sequences that show its
  defining behaviour.
- **Traces:** public production traces with a clear licence, pinned by URL, size and SHA-256,
  streamed from disk, at cache sizes chosen as a percentage of each trace's distinct keys.
- **Report:** hit rate per trace, size and policy, next to the hit rate of a cache that never
  evicts (the ceiling), as a printed matrix and a committed tab-separated table.
- Later: byte hit rate and throughput, S3-FIFO (M2); a scan-heavy trace and hit-rate curves with a
  written guide (M3).

## Non-goals

- A production cache library: no concurrency, no expiry, no memory accounting per entry. The
  policies are single-threaded models built to be measured.
- Byte-sized capacity in M1: capacity counts entries; value sizes are parsed and validated but
  only used from M2 (byte hit rate).
- TTL expiry: the Twitter traces carry TTLs, but M1 ignores them (all policies equally).
- Running as a hosted service.

## Proposed design

![architecture](../architecture.png)

### Cache interface and policies

`Cache` (package `portfolio.cacheevictionlab.policy`) holds 64-bit keys. `get` is a lookup that
updates the policy's state only on a hit; `put` admits a key or refreshes a resident one and evicts
(or, for an admission policy, rejects the newcomer) when full; `evict` removes a key on request,
which the replay uses for `delete`. `contains` never changes state, so the harness can ask without
disturbing the policy. `Policy` maps report names to constructors.

| Policy | Structure | Notes |
|---|---|---|
| FIFO | hash map + intrusive doubly linked list | hits change nothing |
| LRU | hash map + list in order of use | every hit or refresh moves the key to the tail |
| LFU | hash map + linked list of frequency buckets, each a list | O(1) per operation; ties broken by least recent use; counts forgotten on eviction |
| ARC | one hash map, four lists T1, T2, B1, B2, real-valued target `p` | the paper's REQUEST split into `get` (Case I) and `put` (Cases II to IV); deletes drop a key without making it a ghost and REPLACE runs only when full, which changes nothing when there are no deletes |
| W-TinyLFU | 1% LRU window, segmented LRU main cache (80% protected), Count-Min sketch (depth 4, width 4 x capacity, conservative update), halving every 10 x capacity counts | admission: the window's victim enters only if its estimated frequency is above the main cache's victim |

The Count-Min sketch and its MurmurHash3 helper are copied from probabilistic-sketches (same
author, not published to a Maven repository) into `portfolio.cacheevictionlab.sketch`, unchanged
except the package line, with the source file named in a header comment and the source's tests
kept (ADR 0003).

### Traces and pins

- **Source:** Twitter's Twemcache/Pelikan production traces (Yang, Yue and Vinayak, OSDI 2020),
  CC BY 4.0. M1 uses the 1,000,000-request samples of five clusters from the repository's
  `samples/2020Mar` folder (34.6 to 56.9 MB each, 217 MB in total): cluster017, 024, 044, 045 and 052.
  All five are on the trace authors' recommended list for miss-ratio (admission and eviction)
  studies. cluster045 has 838,523 distinct keys in 1,000,000 requests, so its ceiling is low.
- **Pins:** `traces/pins.tsv` holds name, URL (pinned to commit `1c50367`), exact size, SHA-256 and
  licence. `lab fetch` downloads over HTTPS without redirects to `data/<name>.part`, aborts past
  the pinned size, and renames to `data/<name>.csv` only when size and hash match. `lab bench`
  re-hashes every file before replaying it.
- **Parsing:** `TwitterTraceReader` streams 64 KiB chunks, accepts lines up to 4,096 bytes, takes the
  key as everything between the first comma and the fifth comma from the end, checks every numeric
  field and the command, and counts skipped lines. Keys become the first 64 bits of MurmurHash3
  x64 128 (collision chance about `n^2 / 2^65`, below `10^-7` for the 838,523 keys of the largest
  trace).

### Replay model

Look-aside, as Twemcache is used (ADR 0002): `get`/`gets` are reads, count towards the hit rate and
fill the cache on a miss; `set` stores; `add` stores only an absent key; `replace`, `cas`,
`append`, `prepend`, `incr` and `decr` refresh only a cached key; `delete` evicts. Writes are not
counted as hits or misses. `--mode every-request` treats every request as a read instead, the
shortcut most simulators take, so the two can be compared.

### Harness

`Replayer` makes two streaming passes per trace. The first counts requests by kind and distinct
keys and replays an `UnboundedCache` (never evicts), whose hit rate is the ceiling for every policy
since a bounded cache always holds a subset of its keys. Cache sizes are percentages of the distinct
keys (default 0.1, 0.5, 1, 5 and 10%, rounded half up, at least one entry). The second pass feeds
every request to all policy x size caches side by side (at most 200). Memory grows with distinct
keys and total capacity only.

### Command-line tool

`lab fetch`, `lab replay --trace FILE` and `lab bench` (all pinned traces). `--out` writes the table,
`--expect` compares with a saved one; the run exits with 3 if any cell beats the ceiling or differs
from the expected table, and prints a `RESULT` line that CI turns into an annotation.

## Alternatives considered

| Option | Why not (yet) |
|---|---|
| Use an existing simulator (Caffeine's simulator, libCacheSim) | Both are mature and are the reference to compare against later (Caffeine is the upstream this repo will contribute benchmarks to). The goal here is a small harness whose every policy is checked against a brute-force model; libCacheSim is C, and Caffeine's simulator brings a large configuration surface. |
| The classic ARC and LIRS traces (P1-P14, OLTP, DS1, glimpse, multi) | They are the traces the ARC and LIRS papers used, but they are redistributed through third-party mirrors without a stated licence. The Twitter traces have a clear CC BY 4.0 licence and an official, commit-pinnable source. |
| Every request as a read (the common simulator shortcut) | Simple, but on cluster045 every `add` would count as a miss and a fill, which is not how a look-aside cache sees writes; ADR 0002 measures the difference. Kept as `--mode every-request`. |
| Map keys to dense integer ids instead of hashing | Exact, but needs a map from every key string to its id (memory grows with the key bytes, not 8 bytes per key) and two different code paths for tests and traces. A 64-bit hash has a collision chance below `10^-7` here. |
| Depend on probabilistic-sketches as a Maven artifact | It is not published to any Maven repository; JitPack or GitHub Packages would add a build-time network dependency or a token. Vendoring one class with a header and its tests is reproducible offline (ADR 0003). |
| Capacity in bytes from the start | Closer to Memcached, but mixes two questions (which keys to keep, and how object size changes that). M1 answers the first with entry counts; byte hit rate is M2. |
| Fixed window W-TinyLFU vs Caffeine's adaptive (hill-climbing) window | Adaptive sizing is what Caffeine ships, but it adds a tuning loop that would hide whether the admission filter itself helps. M1 measures the fixed 1% window from the paper; the results show where that loses to ARC. |

## Measurement plan (Measure, Analyze, Control)

- **Correctness before hit rates:** `ReferenceModelTest` (each policy vs its brute-force model, 25
  sequences x 3,000 operations at 8 capacities, plus 40,000-request read-and-fill runs),
  `CacheContractTest`, `PolicyBehaviourTest` (Belady's anomaly for FIFO, LRU's stack property,
  ARC's list invariants and ghost adaptation, W-TinyLFU's scan resistance), and a mutation check
  that applies deliberate bugs and records which tests catch them.
- **Hit rates:** `lab bench` on the five pinned traces at 0.1, 0.5, 1, 5 and 10% of distinct keys,
  look-aside mode, all five policies; every cell next to the never-evicting ceiling. Results are
  deterministic (no randomness, no timing), so a re-run must reproduce them exactly.
- **Control:** `make e2e` in CI fetches cluster045, replays it and requires the committed cells
  (`--expect docs/results/m1-hit-rates.tsv`); the ceiling check runs on every replay; the unit
  tests hold the correctness CTQs; Spotless, `-Xlint:all -Werror` and the OSV audit fail the build.
- **Not measured in M1:** throughput and byte hit rate (M2), a scan-heavy trace and curves (M3).

## Milestones

- **M1:** RFC; one Cache interface with FIFO, LRU, LFU, ARC and W-TinyLFU; trace pins, streaming
  replay at several sizes, hit-rate table and CI reproduction gate. Done. W-TinyLFU, listed under
  M2 in the backlog, was built in M1 because it is the reason the Count-Min sketch is reused.
- **M2:** Byte hit rate and throughput per policy per size; S3-FIFO (small, main and ghost queues).
- **M3:** A scan-heavy trace showing LRU collapsing while scan-resistant policies hold; hit-rate
  curves and a short guide to which policy suits which workload.

## Risks and open questions

- **Samples, not full traces:** each sample is the first 1,000,000 requests of a week-long trace.
  Early requests include more cold misses, so absolute hit rates are lower than in production; the
  ceiling column shows how much of the gap is compulsory.
- **Entry-count capacity** ignores object sizes; a policy that keeps many small objects can look
  better by count than by bytes. M2 adds byte hit rate.
- **TTLs are ignored**, which slightly favours every policy equally on clusters with short TTLs.
- **W-TinyLFU parameters are fixed** (1% window, 80% protected, width 4 x capacity, sample 10 x
  capacity). Other settings, or Caffeine's adaptive window, may change its ranking against ARC.
- **Hash collisions** between distinct keys are possible in principle (below `10^-7` per trace here).
