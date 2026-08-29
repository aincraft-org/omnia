# Paper 26.2 API-only visibility POC — 2026-08-29

## Scope

This temporary POC is wired into `io.github.aincraft.vanish.paper.VanishPaperPlugin` and exposes `/vanishpoc on|off <exact-player-name>`. The listener keeps only in-memory target UUIDs, hides each vanished target from online viewers without `vanish.see`, shows it to exempt viewers, and re-applies visibility on join, world change, target server-arrival join, and one scheduled tick later. Each reconciliation records the viewer UUID, target UUID, and `Player.canSee(Player)` result.

The implementation uses only Paper's public `Player.hidePlayer(Plugin, Player)`, `Player.showPlayer(Plugin, Player)`, and `Player.canSee(Player)` visibility surface. It does not add cross-backend state or an authoritative manager.

## Build evidence

```text
$ ./gradlew :vanish-paper:compileJava
BUILD SUCCESSFUL in 522ms
3 actionable tasks: 1 executed, 2 up-to-date

$ ./gradlew :vanish-paper:shadowJar
BUILD SUCCESSFUL in 776ms
5 actionable tasks: 1 executed, 4 up-to-date
```

Gradle emitted its standard deprecation notice for features incompatible with Gradle 10; no POC source deprecation warning remained. The requested public visibility calls are the only visibility mechanism in the POC.

## Runtime attempt

The requested runtime was attempted with two managed Paper 26.2 backends named `alpha` and `beta`, the harness's Velocity proxy, modern forwarding, and its shared development secret. The plugin jar was supplied to both backends.

```text
$ BASE=/tmp/vanish-poc-task3-2026-08-29 \
  BACKENDS='alpha beta' \
  PLUGIN_ALPHA=/home/jlo/dev/omnia/.worktrees/vanish-nopacket/vanish-paper/build/libs/vanish-paper-2026.08.29-SNAPSHOT.jar \
  PLUGIN_BETA=/home/jlo/dev/omnia/.worktrees/vanish-nopacket/vanish-paper/build/libs/vanish-paper-2026.08.29-SNAPSHOT.jar \
  DEV_USERS=dev \
  /home/jlo/dev/omnia/.agents/skills/development-network/bin/dev-network.sh
== auto-discovered backends:
== network role: full
!! dev-network: lobby port 30066 already in use
```

The controller exited before starting the proxy or either backend. The blocking port check was independently observed as:

```text
$ ss -ltn '( sport = :25565 or sport = :30066 or sport = :30067 or sport = :30068 )'
State  Recv-Q Send-Q Local Address:Port  Peer Address:Port
LISTEN 0      4096               *:30068            *:*
LISTEN 0      4096               *:30067            *:*
LISTEN 0      4096               *:30066            *:*
```

No client connection was attempted after this preflight failure. The available launcher command was present (`/home/jlo/.local/bin/minecraft-launcher`), but it was not used because the required proxy/lobby/backends never started and no two-client scenario could be exercised against them.

## Matrix

Because the full network failed before startup, every matrix row is **BLOCKED**, not PASS or FAIL. Entity visibility, tab visibility, and flicker/leak timing were not observable.

| Scenario | Result | Entity behavior | Tab behavior | Flicker/leak timing |
|---|---|---|---|---|
| 1. Target hides while viewer is online | BLOCKED | Not observed | Not observed | Not observed |
| 2. Target joins already hidden | BLOCKED | Not observed | Not observed | Not observed |
| 3. Viewer joins after target hides | BLOCKED | Not observed | Not observed | Not observed |
| 4. Viewer changes world | BLOCKED | Not observed | Not observed | Not observed |
| 5. Target switches `alpha` → `beta` | BLOCKED | Not observed | Not observed | Not observed |
| 6. Target unvanishes | BLOCKED | Not observed | Not observed | Not observed |
| 7. Viewer gains `vanish.see` | BLOCKED | Not observed | Not observed | Not observed |

## Gate decision

**BLOCKED — release gate not satisfied.** The Paper module compiles and packages, but the required two-client/two-backend evidence could not be collected because the available harness refused to launch while lobby port `30066` was occupied. No runtime row is claimed as passing. This is a hard stop for any claim that the API-only behavior satisfies the complete cross-backend product requirement. FR-008 remains separately approval-gated.

## Concerns

- The temporary target set is process-local by design; it is not cross-backend state and must not be treated as the later authoritative implementation.
- The occupied harness ports belong to an already-running development environment; this task did not stop or modify that environment.
- A rerun needs an available harness lobby port (or an explicitly approved isolated harness configuration) before two clients can provide entity, tab, arrival, world-change, switch, unvanish, permission, and timing evidence.
