---
name: performance-optimization
description: Use when diagnosing server lag or tick loss, profiling a Paper server or plugin, optimizing main-thread or async chunk-loading work, fixing listener leaks, tuning Paper configuration, or when a claimed performance optimization needs A/B proof with before-and-after graphs.
---

# Performance Optimization (Paper Server)

Diagnose and fix Paper server lag with profiling, main-thread discipline, safe async patterns, and A/B proof.

Core principle: **keep the old code path, build the new one, run real load through both, and only call it an optimization when A/B graphs prove it. The main thread is the bottleneck — never make it wait.**

## Hot-path code-shape review

Do not depend on the compiler or JIT to discover the optimal code shape. It may perform these transformations after warmup, but it must preserve semantics and therefore conservatively refuses them when it cannot prove stability, aliasing, freshness, exception timing, or side-effect ordering. Write the optimized shape explicitly; compiler optimization is a bonus, not a dependency.

Use this router whenever a profiled hot path repeats work:

- **Invariant work**: inspect every value loaded, calculated, allocated, or looked up inside the repeated context. If it is provably stable across the entire context, compute it once outside. This applies to loops, per-tick iteration, per-entity processing, and repeatedly invoked event handlers.
- **Stable conditions**: if a condition cannot change across the repeated context, resolve it once and dispatch to a specialized handler or function. Do not evaluate the same feature flag, mode, capability, or configuration branch for every item.
- **Semantic safety**: before moving work, prove the transformation preserves freshness, aliasing behavior, `volatile` visibility, exception timing, side-effect order, and required thread confinement. “Not assigned in this loop” is not sufficient proof.
- **Measurement**: these are mandatory review checks, not evidence of a speedup. Keep the A/B control/candidate process below for every claimed performance improvement.

### Reference: hoist only proven invariants

Move a load or calculation outside a repeated context only when all of these hold:

1. No code in the context, called methods, other threads, callbacks, or aliases can mutate the source.
2. The value does not require a fresh read on each repetition (`volatile`, live server state, or an explicitly changing API).
3. Moving it does not change whether, when, or how often an exception, allocation, I/O operation, or other side effect occurs.
4. The value remains valid for the whole context and is used under the same thread-confinement rules.

```java
// Avoid repeated stable lookup in a hot path.
double radius = config.combatRadius();
for (Player player : players) {
    applyRadiusCheck(player, radius);
}
```

If any proof obligation fails, leave the operation in place or redesign the ownership/snapshot boundary first. Do not trade freshness or visibility for a benchmark result.

### Reference: specialize stable conditions once

When a condition is stable across repeated work, select the strategy once at configuration or relevant state change, then dispatch to a shared or specialized handler:

```java
PlayerProcessor processor = particlesEnabled
        ? this::processPlayersWithParticles
        : this::processPlayersWithoutParticles;

// Reuse processor for each tick or batch; do not re-check the flag per player.
processor.process(players);
```

Prefer separate handlers when they keep each path clear. Do not duplicate large loops merely to remove a branch; duplication can introduce behavioral drift and maintenance cost. If the condition can change during processing, retain the check at the narrowest correct boundary.

## Optimization category router

Use this router after profiling identifies a repeated cost. These are review prompts, not unconditional rules. For each candidate, record the hot path, expected invariant, semantic safety proof, primary metric, and guardrails before changing code. Reject folklore changes that have no measured workload or plausible cost model.

### Allocation and GC

- Inspect allocation rate, young-generation collections, promotion, and pause time before changing allocation patterns.
- Remove measured temporary collections, boxing, repeated formatting, or per-item object creation only when the replacement preserves ownership, mutability, and lifetime semantics.
- Do not add object pools by default; pooling can retain objects longer, increase memory pressure, and add synchronization.
- Verify with allocation profiles and GC telemetry under the same seeded peak load; guard against retained memory and longer pauses.

### Collections, boxing, and strings

- Choose `List`, `Set`, `Map`, queues, or primitive-specialized structures from the access pattern and measured complexity, not preference.
- Inspect boxing and repeated string parsing/formatting in a measured hot path; do not mandate streams, `StringBuilder`, or manual loops universally.
- Preserve ordering, equality, null handling, iteration behavior, and API contracts when changing a collection or representation.
- Verify lookup latency, allocation rate, CPU, and memory under realistic player/entity cardinality.

### I/O and serialization

- Find N+1 queries, repeated disk writes, redundant serialization, and request-per-item network calls before optimizing.
- Batch work or debounce writes only when durability, ordering, retry, and failure semantics remain correct. Keep database connections held only for the database operation.
- Reuse clients and pools within their intended ownership; do not create unbounded threads, connections, or requests.
- Verify I/O count, queue depth, connection wait time, latency distributions, error rate, and shutdown/disable draining.

### Synchronization and contention

- Profile lock contention, blocked time, executor saturation, and queueing before replacing locks with atomics or weakening synchronization.
- Reduce shared mutable state or narrow critical sections only when visibility, ordering, cancellation, and failure semantics remain explicit.
- Never use asynchronous execution as a substitute for ownership; a callback still runs on the completing thread unless deliberately rescheduled.
- Verify contention, throughput, tail latency, race behavior, and clean plugin/world/player teardown.

### Algorithmic complexity and indexing

- Replace a repeated `O(n)` scan with an index, map, set, spatial bound, batch, or deduplication only when the scan is on a measured hot path.
- Define index ownership, update events, stale-entry handling, memory bounds, and rebuild behavior before adding one.
- Preserve ordering and completeness; an index that silently misses entities or stale records is a correctness bug.
- Verify scan count, lookup latency, update cost, memory, and behavior across join/quit/world-load/unload transitions.

### Cache locality and data layout

- Consider data layout or locality only for measured CPU-bound processing over large data sets; Paper API calls, scheduling, and I/O usually dominate first.
- Prefer simpler representations that reduce copying and allocation without exposing mutable state or breaking thread ownership.
- Do not trade readable domain objects for packed data or manual pooling without a profile showing the target cost.
- Verify CPU samples, cache-sensitive throughput where available, allocation, memory footprint, and semantic parity.

### Async/main-thread ownership

- Classify each operation by its actual ownership: Bukkit/Paper APIs may require a specific server, region, entity, or main-thread context; pure computation and external I/O may run asynchronously only when their inputs are safe snapshots.
- On Folia or regionized servers, use the scheduler matching the accessed resource and its ownership (`EntityScheduler` for an entity, `RegionScheduler` for region-owned work, `GlobalRegionScheduler` for global-region work, and `AsyncScheduler` for work that is genuinely asynchronous). Do not select a scheduler by habit.
- Completion stages run on the completing executor; `thenAcceptAsync` does not make Bukkit access safe unless its executor has the required ownership.
- Never block a server or region thread on `get()`, `join()`, database I/O, or another scheduler.
- Verify queue depth, executor saturation, handoff latency, cancellation, stale-result rejection, and disable/unload draining.

### Bukkit/Paper API call frequency

- Profile repeated API calls before caching or batching them. Identify calls that scan entities, load chunks, allocate wrappers, perform conversions, or cross ownership boundaries.
- Cache only immutable or explicitly snapshotted data with a documented freshness and invalidation boundary; do not cache live `Player`, `Entity`, `Chunk`, or world state indefinitely.
- Batch or move work only when the API contract permits it and the required thread/region ownership is preserved.
- Verify API call count, main/region-thread time, freshness, memory retention, and behavior after player/world/plugin lifecycle changes.

### Scheduler budgets and backpressure

- Bound recurring work, queue drains, retries, async completions, and per-tick scans with a budget appropriate to the owning thread or region.
- Add backpressure, coalescing, or rejection when producers can outrun consumers; do not hide an unbounded queue behind an async executor.
- Preserve fairness, ordering, deadlines, and retry semantics when spreading work across ticks.
- Verify queue depth, oldest-item age, work-per-tick, MSPT/region time, dropped/coalesced work, and recovery after bursts.

### Entity, spatial, and event queries

- Avoid repeated full-world scans and broad `getNearbyEntities` queries on measured hot paths; constrain by world, chunk, bounding box, type, and state before expensive checks.
- Prefer event-maintained indexes only when update coverage includes spawn, death, transfer, unload, quit, and plugin disable paths.
- Filter high-frequency events at the cheapest safe boundary and avoid expensive work for irrelevant causes, materials, worlds, or entity types.
- Verify query count, candidate count, main/region-thread time, index completeness, and movement-heavy behavior.

### Server simulation and configuration

- Profile the relevant bottleneck before changing view distance, simulation distance, entity limits, hopper/redstone behavior, chunk generation, saves, or other Paper settings.
- Separate configuration effects from plugin-code effects and test the same world, player count, and workload.
- Preserve gameplay, persistence, redstone, and plugin compatibility semantics; lower load is not automatically an acceptable result.
- Verify MSPT/TPS, region or chunk time, entity counts, save latency, memory, and player-visible behavior.

Pins verified 2026-08-21 against PaperMC docs and the Spark project.

## A/B test before you ship

A change is not a performance optimization until an A/B test with graphs either affirms or disproves it.

1. Keep the old path as the control.
2. Build the candidate alongside it, behind a toggle or config flag.
3. Pick one primary metric and guardrails. Primary metric: MSPT for tick lag, TPS when tick rate is the target metric, latency if the change targets a request path. Guardrails: same-metric p95, flame graph, CPU, and memory must not regress.
4. Use a repeatable harness: same world, player count, tick, and seeded load script. Let both paths warm up, then measure. A fleet of autonomous bots running one seeded script is a repeatable load harness (see the `autonomous-testing` skill).
5. Run identical peak load through both for multiple trials. Empty servers and micro-benchmarks do not count.
6. Capture distributions, not single numbers: primary metric, guardrails, and hot-path samples. Render comparison graphs on shared axes.
7. Apply the pass/disprove rule: the primary metric must improve and all guardrails must hold. Otherwise reject it and keep the old path.

### A/B report checklist

Record this for every test:

- Workload / seed: exact script, player count, world, and seed.
- Warmup: duration discarded before measuring.
- Trials: count and order (e.g., interleaved old/new, randomized).
- Primary metric: MSPT for tick lag, TPS when tick rate is the target metric, latency if the change targets a request path.
- Distributions: old vs new median / p95 / p5 for the primary metric and guardrails.
- Graphs: shared-axis comparison plots.
- Verdict: `PASS`, `NO IMPROVEMENT`, or `REGRESSION`.

### Pass / disprove rule

- `PASS`: the primary metric improves and all guardrails hold.
- `NO IMPROVEMENT`: the primary metric is within noise or the gain is not clear. Reject and keep the old path.
- `REGRESSION`: the primary metric is worse or any guardrail fails. Reject immediately and keep the old path.

Guardrails:

- Same-metric p95 does not regress.
- No new hot path in the flame graph.
- CPU and memory do not increase meaningfully.

A result that is not a clear `PASS` is a rejection. The test disproves the claim as often as it affirms it.

A code trace, a smaller flame-graph bar, or "it looks faster" is not proof. Data flowing through both paths is.

## Profiling with Spark

Spark is the standard profiler and is bundled with modern Paper. Profile during peak load, not on an empty server, for 60–180 seconds:

```text
/spark health          # quick TPS, MSPT, memory snapshot
/spark profiler start  # begin sampling
/spark profiler stop   # end sampling, prints a shareable URL
```

Read the flame graph: the widest bars at the top of the call stack are the methods consuming the most CPU. If the main-thread stack is dominated by scheduler execution, batch the work; if it is chunk generation/loading, reduce request rate and avoid urgent loads; if it is event dispatch or listener collections, inspect listener registration and unregistering.

A Spark trace or flame graph of the new code alone is insufficient. It is one side of the A/B graph and cannot prove an optimization.

## Main-thread discipline

Time-consuming work — database queries, file I/O, complex calculations — must run off the main thread. Use the Paper async scheduler:

```java
Bukkit.getAsyncScheduler().runNow(plugin, task -> {
    // heavy work
});
```

Bring results back to the main thread only when a Bukkit API call requires it, and never call Bukkit APIs from an async task:

```java
Bukkit.getAsyncScheduler().runNow(plugin, task -> {
    String result = expensiveWork();
    Bukkit.getScheduler().runTask(plugin, () -> {
        // main-thread work with result
    });
});
```

## Async chunk loading

Load chunks asynchronously and never retain live `Chunk` objects. Deduplicate in-flight requests so the same chunk is not loaded repeatedly:

```java
private final ConcurrentHashMap<ChunkKey, CompletableFuture<Chunk>> inFlight =
        new ConcurrentHashMap<>();

private record ChunkKey(UUID worldId, int x, int z) {}

public CompletableFuture<Chunk> loadChunk(World world, int x, int z) {
    ChunkKey key = new ChunkKey(world.getUID(), x, z);
    return inFlight.computeIfAbsent(key, ignored -> {
        CompletableFuture<Chunk> future = world.getChunkAtAsync(x, z, true, false);
        future.whenComplete((chunk, error) -> inFlight.remove(key, future));
        return future;
    });
}
```

Process only a bounded number of completed chunks per tick to avoid a completion burst turning into one massive tick:

```java
private final Queue<Chunk> completedChunks = new ConcurrentLinkedQueue<>();

public void startProcessor() {
    Bukkit.getScheduler().runTaskTimer(plugin, task -> {
        int budget = 2; // tune experimentally
        while (budget-- > 0) {
            Chunk chunk = completedChunks.poll();
            if (chunk == null) break;
            processChunk(chunk);
        }
    }, 1L, 1L);
}
```

Do not use per-request `ChunkLoadEvent` listeners — they leak unless unregistered on every path. Prefer the returned future, or one global listener routing through a map. Add timeouts and clear all maps/queues on plugin disable and world unload.

## Listener and cache hygiene

- Unregister listeners on plugin disable; never register a listener per request.
- Do not store live `Chunk` or `Player` objects in long-lived caches; store immutable summaries instead.
- If you must keep a chunk loaded, use a deliberate plugin chunk ticket and remove it later.
- Clear caches, in-flight maps, and queues on disable and world unload.

## Paper configuration

- Leave `async-chunks` at `-1` (auto-detect) unless there is a specific reason to change it.
- Use `alternate-current` for redstone and adjust entity/mob spawn limits only when entity-related lag is confirmed.
- Run the recommended Java version for the server (e.g. 25 for modern versions) to benefit from GC and performance improvements.

## Verify

```bash
./gradlew build spotlessCheck
./gradlew runServer
```

Load the server with players or a load generator and run the A/B test: keep the old path as the control, enable the new path as the candidate, pick a primary metric and guardrails, run identical seeded peak load through both for multiple trials, and capture TPS, MSPT, CPU, memory, and hot-path graphs. Fill out the A/B report checklist and apply the pass/disprove rule. Only switch the default if the verdict is `PASS`. Confirm `inFlight` and `completedChunks` counts stay bounded under sustained load.

## Common mistakes

| Wrong | Right | Why |
|---|---|---|
| Optimizing without profiling | Spark profile first | Guessing wastes effort and misses the real bottleneck |
| Blocking the main thread | Async scheduler + sync hop | Main-thread blocking drops TPS |
| Calling Bukkit APIs from async tasks | Hop to main thread first | Unsafe cross-thread access corrupts world state |
| One chunk load per request, no dedup | Deduplicating in-flight map | Duplicate loads and callback bursts spike the tick |
| Per-request `ChunkLoadEvent` listener | Future-based load or one global listener | Listeners leak unless unregistered on every path |
| Storing live `Chunk`/`Player` in caches | Store immutable summaries | Retains chunks/entities and leaks memory |
| Processing all completed chunks in one tick | Bounded per-tick budget | A completion burst becomes one massive tick |
| Ignoring chunk tickets | Remove tickets deliberately | Accidental tickets keep chunks loaded forever |
| Profiling an empty server | Profile during peak load | Empty-server data misses real bottlenecks |
| Claiming an optimization without A/B data | Keep old path, build new path, test, graph | Code traces and cleaner logic do not prove speedup |
| Replacing the old path before measuring | Keep old path until data says ship | Without a fallback, a bad change becomes the only option |
| A Spark trace or flame graph of only the new code | Profile both under identical load | One-sided measurements miss regressions |
| Re-reading a stable value in a hot repeated context | Hoist it only after proving stability and freshness | The compiler may not prove aliasing or side-effect safety, but an unsafe manual hoist changes semantics |
| Assuming “not assigned in the loop” proves a value is invariant | Check aliases, callbacks, other threads, `volatile`, live server state, exception timing, and thread confinement | Mutation can occur outside the loop body |
| Checking a stable mode or feature flag for every item | Select the strategy at configuration/state change and dispatch to a shared or specialized handler | Repeated branches add work; duplicated loops can instead drift |
| Assuming the compiler or JIT will perform the optimization | Write the safe optimized shape explicitly, then treat compiler optimization as a bonus | Compilation level and JIT warmup are not correctness or performance dependencies |
| Adding object pools without allocation evidence | Profile allocation and GC first; prefer ordinary ownership | Pools can increase retention, memory pressure, and synchronization |
| Replacing code with streams or `StringBuilder` by rule | Measure the actual allocation and CPU cost | No form is universally faster, and semantics may change |
| Replacing locks, caches, or scans without measurements | Profile contention, invalidation, and lookup cost first | Unmeasured changes can regress correctness, memory, or tail latency |
| Letting async queues grow without bounds | Add budgets, backpressure, coalescing, or rejection | Producers can overwhelm consumers and create burst latency |
| Tuning Paper settings without a matching profile | Measure the affected simulation, entity, chunk, or save workload | Lower load can change gameplay and hide the real bottleneck |
