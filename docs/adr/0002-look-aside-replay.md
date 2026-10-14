# ADR 0002: Replay Twitter traces as a look-aside cache, not "every request is a read"

- **Status:** Accepted

## Context

The Twitter traces record what clients sent to Twemcache: reads (`get`, `gets`), writes (`set`,
`add`, `cas`, `replace`, `append`, `prepend`, `incr`, `decr`) and `delete`. Most cache simulators
replay every request as a read that fills the cache on a miss. That is simple, but it counts a
`set` or `add` as a hit or a miss, and it ignores deletes, although the clients use Twemcache as a
look-aside cache: they read, and on a miss load from the store and write the value back. Five
traces mix these very differently: cluster045 is 22.6% `add`, cluster052 has 8,338 deletes and
25,213 updates, cluster017 and cluster024 are over 99% reads.

## Decision

The default replay mode is **look-aside** (`ReplayMode.LOOKASIDE`):

- `get`/`gets`: counted read; on a miss the key is put (the client fills the cache);
- `set`: put; `add`: put only if not cached; `replace`, `cas`, `append`, `prepend`, `incr`,
  `decr`: refresh only a cached key; `delete`: evict;
- the hit rate is hits divided by reads; writes are neither hits nor misses.

`--mode every-request` keeps the usual simulator behaviour so the two can be compared.

## Consequences

Measured on all five traces, five sizes, five policies (125 cells), `lab bench` in each mode:

- **The ceiling changes most where writes are common.** A cache that never evicts hits 20.70% of
  reads on cluster045 in look-aside mode, but 16.15% of requests in every-request mode, because each
  of the 226,141 `add` requests then counts as a miss. On cluster052 it moves from 73.38% to 74.47%.
  On the read-only traces (017, 024) it changes by 0.23 points or less.
- **Single cells move by up to about 4 points:** from -3.75 (ARC, cluster045, 10%) to +4.24 (FIFO,
  cluster052, 0.1%).
- **The winner does not change:** the best policy is the same in all 25 trace and size cells. The
  full order differs in 5 cells: LFU and FIFO swap on cluster044 at 0.1% and 0.5%, and FIFO and
  W-TinyLFU swap on cluster052 at 0.1%, 0.5% and 1%.
- Look-aside needs `contains`, which never changes policy state, so `add` and the updates can ask
  whether a key is cached without counting as a use. `ReplayModeTest` pins every rule.
- TTLs are still ignored in both modes (RFC 0001, risks).
