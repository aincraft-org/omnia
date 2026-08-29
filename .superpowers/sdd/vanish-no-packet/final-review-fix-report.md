# Final whole-branch review fix report

## Changed files

- `vanish-common/src/main/java/io/github/aincraft/vanish/common/VersionedState.java`
  - Added the explicit `markSnapshotNeeded()` transition used when a Paper event gap is queued.
- `vanish-paper/src/main/java/io/github/aincraft/vanish/paper/VanishManager.java`
  - Added readiness reporting based on `VersionedState.ready()` and `needsSnapshot()`.
  - Added the package-level gap transition used by the Redis reconciler.
- `vanish-paper/src/main/java/io/github/aincraft/vanish/paper/RedisPaperService.java`
  - `hasValidState()` now fails closed for a known `VersionedState` gap or queued delta.
  - Queued gaps mark the manager state stale; reconciliation and cached-state access cannot use stale state.
  - Snapshot responses are applied even when unsolicited, allowing the coordinator startup announcement to repair an initial stale GET.
- `vanish-paper/src/test/java/io/github/aincraft/vanish/paper/PlayerListenerTest.java`
  - Added a pre-login/join regression covering a queued gap and recovery by a sufficient snapshot.
- `vanish-paper/src/test/java/io/github/aincraft/vanish/paper/RedisPaperServiceTest.java`
  - Added coverage for an unsolicited startup snapshot response and retained the existing gap ordering coverage.
- `vanish-velocity/src/main/java/io/github/aincraft/vanish/velocity/RedisVelocityService.java`
  - Startup now completes durable-key SET plus versioned snapshot-response announcement before starting the subscriber.
  - Reconnect rewrites and announces after repairing queued publications.
  - Failed mutation publications retain committed snapshot/delta authority in an ordered repair queue; retrying an idempotent request repairs the publication before acknowledging success.
  - Added an explicit subscription-close seam and production Jedis subscription ownership. Active `JedisPubSub` is unsubscribed and its connection closed before pool/executor shutdown.
- `vanish-velocity/src/test/java/io/github/aincraft/vanish/velocity/RedisVelocityServiceTest.java`
  - Added startup rewrite/subscriber ordering, publication failure/recovery, and subscription-close lifecycle tests; updated response-count assertions for the startup announcement.

## Finding fixes

1. **Paper pending version gaps:** A non-contiguous delta now marks `VersionedState` as needing a snapshot and remains queued. Paper readiness, reconciliation fallback, pre-login, join visibility, and the public cached snapshot path all require a ready state with no queued gap. A sufficient snapshot clears the marker and drains contiguous queued deltas.
2. **Velocity startup stale-key race:** The durable snapshot rewrite is serialized ahead of starting the request subscriber. The rewrite also publishes a versioned `SnapshotResponse` after the SET; Paper applies unsolicited coordinator startup responses, so a backend that already completed an older GET converges without requiring a later mutation event. Reconnect uses the same repair-and-announce ordering.
3. **Failed Redis mutation publication:** File persistence and in-memory authority remain committed. The failed snapshot/delta publication is retained for ordered repair. A reconnect or later request repairs the queued snapshot/delta before success can be acknowledged; an idempotent request can therefore repair a missing publication and receive an accepted ack only after repair and ack publication succeed.
4. **Active subscription lifecycle:** The production Jedis client retains active connection and `JedisPubSub` references under a lock. Service close calls `closeSubscription()` before client pool close and subscriber executor shutdown; the seam test asserts `close-subscription` precedes `close`.

## Verification

### Focused affected tests

Command:

```text
./gradlew :vanish-paper:test --tests io.github.aincraft.vanish.paper.PlayerListenerTest :vanish-paper:test --tests io.github.aincraft.vanish.paper.RedisPaperServiceTest :vanish-velocity:test --tests io.github.aincraft.vanish.velocity.RedisVelocityServiceTest
```

Exact result:

```text
BUILD SUCCESSFUL in 1s
14 actionable tasks: 2 executed, 12 up-to-date
```

### Full quality gate

Command:

```text
./gradlew clean check
```

Exact result:

```text
BUILD SUCCESSFUL in 10s
43 actionable tasks: 39 executed, 4 up-to-date
```

The command emitted existing Checkstyle warnings in unrelated pre-existing Velocity files (`VanishVelocityPlugin`, `VanishStateStore`, `VelocityConfig`, `VanishTabMasker`, `VanishServersCommand`, and `ServerConnectionGuard`) and the pre-existing `RedisDownRetainsCachedMaskingAndRejectsNewChanges` test-name warning. It also emitted expected Paper API removal warnings in the existing/new listener tests. No changed implementation file produced a PMD violation; the changed code passed Spotless/PMD/SpotBugs tasks.

### Shadow jars

Command:

```text
./gradlew :vanish-paper:shadowJar :vanish-velocity:shadowJar
```

Exact result:

```text
BUILD SUCCESSFUL in 1s
12 actionable tasks: 2 executed, 10 up-to-date
```

## Self-review

- No direct packet injection, NMS, or ProtocolLib was added.
- Paper visibility mutation remains on the Paper main-thread handoff path.
- `see-uuids: false`, tab masking, disconnect cleanup, and public Velocity APIs were not changed.
- Authority state remains fail-closed on malformed/disabled state files and failed publication does not roll back committed durable authority.
- Repair replay is idempotent for Paper's versioned delta application; startup/reconnect snapshot announcements provide full-state convergence after a stale GET or process restart.
- Subscription close handles unsubscribe and connection-close failures independently enough to attempt both resources, while service shutdown still proceeds.
- Runtime POC/smoke evidence remains honestly BLOCKED by the existing user-owned listeners/harness lobby-port limitation; no live PASS claim was added and FR-008 was not implemented.

## Concerns

- The full gate remains green but reports the pre-existing Checkstyle warnings listed above.
- The required live POC/smoke scenarios remain BLOCKED by the existing user-owned listeners/harness limitation; this change does not claim runtime validation.
