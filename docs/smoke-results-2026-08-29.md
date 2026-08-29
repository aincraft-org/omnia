# Task 8 smoke results — 2026-08-29

## Result

**BLOCKED**. The required shared-network endpoints are occupied by pre-existing, user-owned Java processes. The development-network controller has no lobby-port override: even with an isolated `BASE`, it hard-codes lobby port `30066` and exits before starting any process. No client was connected, and no blocked scenario is reported as PASS.

## Scope and ownership

- Project: `vanish-nopacket` worktree.
- Harness: `/home/jlo/dev/omnia/.agents/skills/development-network` (read-only; not modified).
- Isolated attempted runtime: `/tmp/vanish-nopacket-task8` (generated runtime/world files intentionally kept outside the repository).
- Required endpoint: Velocity `localhost:25565`; lobby `30066`; backends `alpha`/`beta`.
- Alternate preflight endpoint used only to test the harness gate: proxy `25575`, lobby `30166`, `alpha` `30167`, `beta` `30168`.
- Existing occupied listeners were not stopped or altered.

## Versions and artifacts

Exact environment evidence:

```text
OpenJDK 25.0.2 (Red Hat)
Gradle 9.7.1
Kotlin 2.4.0
```

Build command:

```text
./gradlew :vanish-paper:shadowJar :vanish-velocity:shadowJar
BUILD SUCCESSFUL in 5s
12 actionable tasks: 4 executed, 8 up-to-date
```

Product jars deployed to the isolated runtime (one Paper jar per backend and one Velocity jar):

```text
/tmp/vanish-nopacket-task8/runtime/auto/alpha/plugins/vanish-paper-2026.08.29-SNAPSHOT.jar
/tmp/vanish-nopacket-task8/runtime/auto/beta/plugins/vanish-paper-2026.08.29-SNAPSHOT.jar
/tmp/vanish-nopacket-task8/runtime/plugins/vanish-velocity-2026.08.29-SNAPSHOT.jar
```


SHA-256:

```text
vanish-paper-2026.08.29-SNAPSHOT.jar    6d8682820def04c9f339bea5a5b5ee06233b549a4f9c66a868daac5c2cd226e9
vanish-velocity-2026.08.29-SNAPSHOT.jar 6df2e35b40de8c8dae830ca392f7d4c758cfaf65c5261b10199ee70c6cb4e31b
```
## Gradle wiring

The standalone project was wired only to the existing composite:

- `settings.gradle.kts` includes `/home/jlo/dev/omnia/.agents/skills/development-network/network`.
- `vanish-paper/build.gradle.kts` applies `io.github.development-network`, sets `extra["devNetworkBin"]` to the shared harness `bin` directory, sets `extra["networkJarTask"] = "shadowJar"`, and makes `runBackend`/`runNetwork` depend on `shadowJar`. This overrides the harness's thin-`jar` default for clean managed-backend runs.
- `vanish-velocity/build.gradle.kts` applies `io.github.development-network`, sets `extra["devNetworkBin"]` to the shared harness `bin` directory, and adds `deployVelocityProxyPlugin`, which depends on `shadowJar` and copies the deployable artifact to `-PnetworkBase=<dir>/runtime/plugins`; `runProxy` depends on this task.

Exact task-graph/deployment checks:

```text
./gradlew :vanish-paper:runBackend --dry-run :vanish-velocity:runProxy --dry-run -PnetworkBase=/tmp/vanish-nopacket-task8
:vanish-paper:jar SKIPPED
:vanish-paper:shadowJar SKIPPED
:vanish-paper:runBackend SKIPPED
:vanish-velocity:shadowJar SKIPPED
:vanish-velocity:deployVelocityProxyPlugin SKIPPED
:vanish-velocity:runProxy SKIPPED
BUILD SUCCESSFUL

./gradlew :vanish-velocity:deployVelocityProxyPlugin -PnetworkBase=/tmp/vanish-nopacket-task8
BUILD SUCCESSFUL
deployed=vanish-velocity-2026.08.29-SNAPSHOT.jar

./gradlew :vanish-paper:shadowJar
BUILD SUCCESSFUL
alpha=vanish-paper-2026.08.29-SNAPSHOT.jar
beta=vanish-paper-2026.08.29-SNAPSHOT.jar
```

Post-wiring Paper managed-task deployment gate (the network was intentionally not running):

```text
rm -f /tmp/vanish-nopacket-task8/runtime/auto/alpha/plugins/*.jar
./gradlew :vanish-paper:runBackend -PnetworkBase=/tmp/vanish-nopacket-task8 -PnetworkBackend=alpha
== runBackend: backend 'alpha' -> /tmp/vanish-nopacket-task8/runtime/auto/alpha (owner gradle-:vanish-paper:alpha-754-16390311531923)
!! runBackend: cleanup exited with code 1

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':vanish-paper:runBackend' (registered by plugin 'io.github.development-network').
> managed backend registration exited with code 1

BUILD FAILED in 1s
11 actionable tasks: 3 executed, 8 up-to-date
```

The expected registration failure occurred because no proxy controller was running; before that failure, `runBackend` deployed the configured shadow artifact:

```text
alpha=vanish-paper-2026.08.29-SNAPSHOT.jar
6d8682820def04c9f339bea5a5b5ee06233b549a4f9c66a868daac5c2cd226e9  /tmp/vanish-nopacket-task8/runtime/auto/alpha/plugins/vanish-paper-2026.08.29-SNAPSHOT.jar
```

The absolute composite path is intentional for this workstation because the shared harness is outside the standalone project; the plugin also supports `DEV_NETWORK_BIN`/`DEV_NETWORK_DIR` for a portable checkout.

## Mandatory preflight (completed before any network/client start)

The isolated proxy configuration was generated with the harness generator, `PROXY_ONLINE_MODE=false`, `BACKENDS='alpha beta'`, `PROXY_PORT=25575`, and `PORT_ALPHA=30167 PORT_BETA=30168`. Paper `server.properties`, `paper-global.yml`, `spigot.yml`, and `eula.txt` were materialized in the isolated runtime from the harness's boot configuration shape. The preflight command checked every proxy/backend setting before any component start:

```text
proxy online-mode = false
proxy player-info-forwarding-mode = "modern"
lobby server.properties online-mode=false
alpha server.properties online-mode=false
beta server.properties online-mode=false
lobby/alpha/beta paper-global.yml proxies.velocity.online-mode=false
lobby/alpha/beta paper-global.yml shared secret="dev-local-forwarding-secret-change-me"
--- preflight checks ---
PASS
```

Exact generator and verification commands (run in this order; no server process was started by either command):

```bash
set -euo pipefail
BASE=/tmp/vanish-nopacket-task8 PROXY_PORT=25575 BACKENDS='alpha beta' \
  PORT_ALPHA=30167 PORT_BETA=30168 PROXY_ONLINE_MODE=false \
  bash -c '. /home/jlo/dev/omnia/.agents/skills/development-network/bin/velocity-toml.sh && write_velocity_toml'

BASE=/tmp/vanish-nopacket-task8
test "$(sed -n 's/^online-mode = //p' "$BASE/runtime/velocity.toml")" = false
test "$(sed -n 's/^player-info-forwarding-mode = //p' "$BASE/runtime/velocity.toml")" = '"modern"'
test "$(cat "$BASE/runtime/forwarding.secret")" = 'dev-local-forwarding-secret-change-me'
for f in "$BASE/runtime/lobby/server.properties" \
         "$BASE/runtime/auto/alpha/server.properties" \
         "$BASE/runtime/auto/beta/server.properties"; do
  test "$(sed -n 's/^online-mode=//p' "$f")" = false
done
for f in "$BASE/runtime/lobby/config/paper-global.yml" \
         "$BASE/runtime/auto/alpha/config/paper-global.yml" \
         "$BASE/runtime/auto/beta/config/paper-global.yml"; do
  test "$(sed -n 's/^    online-mode: //p' "$f")" = false
  test "$(sed -n 's/^    secret: //p' "$f")" = '"dev-local-forwarding-secret-change-me"'
done
printf 'preflight_result=PASS\n'
```

Ordered timestamp evidence from the final pre-start gate:

```text
preflight_started_utc=2026-08-29T13:11:43Z
velocity_config=2026-08-29 06:11:43.621165959 -0700 /tmp/vanish-nopacket-task8/runtime/velocity.toml
forwarding_secret=2026-08-29 06:11:43.618901000 -0700 /tmp/vanish-nopacket-task8/runtime/forwarding.secret
2026-08-29 05:53:25.556744140 -0700 /tmp/vanish-nopacket-task8/runtime/lobby/server.properties
2026-08-29 05:53:25.557744150 -0700 /tmp/vanish-nopacket-task8/runtime/auto/alpha/server.properties
2026-08-29 05:53:25.558806594 -0700 /tmp/vanish-nopacket-task8/runtime/auto/beta/server.properties
2026-08-29 05:53:25.558806594 -0700 /tmp/vanish-nopacket-task8/runtime/lobby/config/paper-global.yml
2026-08-29 05:53:25.557744150 -0700 /tmp/vanish-nopacket-task8/runtime/auto/alpha/config/paper-global.yml
2026-08-29 05:53:25.558806594 -0700 /tmp/vanish-nopacket-task8/runtime/auto/beta/config/paper-global.yml
preflight_result=PASS
network_attempt_started_utc=2026-08-29T13:11:43Z
network_attempt_exit=1
network_attempt_finished_utc=2026-08-29T13:11:43Z
```

Required-port evidence, collected before the attempt:

```text
*:25565  java pid=3983747  cwd=/home/jlo/dev/terra/run/network-plugin-multiplexer-final25/runtime
*:30066  java pid=1122800  cwd=/home/jlo/dev/buildtools/development-network/runtime/runtime/lobby
*:30067  java pid=1122870  cwd=/home/jlo/dev/buildtools/development-network/runtime/runtime/auto/masonry
*:30068  java pid=3275230  cwd=/home/jlo/dev/kitsune/run
```

The active existing proxy configuration was inspected and is external to this run; it contains `online-mode = false` and `player-info-forwarding-mode = "modern"`, but it was not used or changed.

## Redis attempt

After the mandatory preflight, an owned Redis container was started:

```text
docker run -d --rm --name vanish-task8-redis -p 6379:6379 redis:7.4-alpine
container: c0b4d486d286f31b3cb5920fc3f67f6276bb3b8332f87b16c92387b76c5337e5
docker exec vanish-task8-redis redis-cli ping
PONG
docker exec vanish-task8-redis redis-cli INFO server
redis_version=7.4.11
```

Redis was stopped with the owned-container command `docker rm -f vanish-task8-redis` before report completion. **Redis readiness: PASS.** It does not constitute cross-backend smoke evidence because the network gate prevented Paper/Velocity startup.

## Network start attempt

The documented isolated invocation was attempted after preflight:

```text
BASE=/tmp/vanish-nopacket-task8 BACKENDS='alpha beta' PROXY_PORT=25575 \
  PORT_ALPHA=30167 PORT_BETA=30168 PROXY_ONLINE_MODE=false \
  /home/jlo/dev/omnia/.agents/skills/development-network/bin/dev-network.sh
```

Exact result:

```text
== auto-discovered backends:  alpha beta
== network role: full
!! dev-network: lobby port 30066 already in use
```

Exit code was `1`. No isolated proxy/lobby/backend process was started; ports `25575`, `30166`, `30167`, and `30168` had no listeners after the attempt. The alternate backend/proxy values cannot overcome the harness's hard-coded lobby check/configuration, so this is a harness-supported limitation rather than a permission to modify the shared harness.

## Smoke matrix

Because the network start gate failed before any Paper/Velocity process or real client connection, each runtime scenario is **BLOCKED** (not PASS):

| Scenario | Status | Evidence / blocker |
|---|---|---|
| `/vanish` acknowledgment and local entity hiding | BLOCKED | No Paper backend or client started; required lobby `30066` occupied. |
| Observer joins after target is vanished | BLOCKED | No real clients could connect through the required proxy. |
| Vanished target `alpha -> beta`, hidden within one Paper tick | BLOCKED | `alpha`/`beta` never started; no destination join timing available. |
| `beta` empty while state changes | BLOCKED | Two-backend network never reached ready state. |
| `beta` offline while state changes; reconcile before join | BLOCKED | No managed backend lifecycle available after controller exit. |
| Redis restart and subscriber resync | BLOCKED | Redis itself passed readiness, but no plugin subscribers started. |
| Proxy restart and JSON reload | BLOCKED | Required proxy `25565` is user-owned; isolated proxy was rejected by lobby gate before startup. |
| Unvanish restoration | BLOCKED | No real viewer/target session. |
| See permission exemption and permission revocation | BLOCKED | No real clients or live Velocity permission surface. |
| Proxy tab masking | BLOCKED | No Velocity login/tab list. |
| `/vservers` / `/vanishservers` filtering | BLOCKED | No Velocity proxy/plugin process. |
| Built-in `/server beta` denial for non-see and allow for see | BLOCKED | No proxy login or command execution. |

No flicker/leak timing can be recorded. No FR-008 packet/NMS/ProtocolLib path was opened or authorized.

## Acceptance assessment

- Live convergence within two seconds: **BLOCKED**, no live state transition was possible.
- Destination visibility within one Paper tick: **BLOCKED**, no destination join was possible.
- Default raw packet/NMS path: no new raw packet/NMS/ProtocolLib implementation was introduced by Task 8 wiring.
- Full cross-backend smoke matrix: **BLOCKED** by the pre-existing required-port ownership and harness lobby-port limitation.

## Current isolated alternate-port evidence

The prior required-port `BLOCKED` result above is preserved. A separate task-owned runtime was then exercised under `/tmp/vanish-nopacket-live-20260829` without modifying or stopping the shared harness or user-owned services:

- Proxy `28175`, lobby `38166`, alpha `38167`, beta `38168`, and Redis `16379`.
- Java 25.0.2, Paper `26.2-119`, Velocity `4.1.1`, and Redis `7.4.11`.
- Velocity was verified `online-mode=false`, `player-info-forwarding-mode="modern"`, and configured for the shared development secret. Each Paper server was separately verified `online-mode=false`, modern forwarding enabled with the same secret, BungeeCord forwarding disabled, and distinct `backend-id` values (`alpha`/`beta`).
- The current `vanish-paper` shadow jar loaded on both Paper backends and the current `vanish-velocity` shadow jar loaded on Velocity. Alternate-port status handshakes reached all four Minecraft endpoints with protocol `776`.
- A temporary local offline protocol client (Node `minecraft-protocol@1.68.0`, installed only under `/tmp`; no repository dependency) connected `Target` and `Observer` through Velocity. It routed Target and Observer to alpha, then Observer to beta, and later routed vanished Target to beta. This is real login/routing evidence, not a direct-backend-only check.
- Observer received Target's `player_info` tab entry before vanish. After Target's `/vanish` acknowledgement and `alpha -> beta` move, Observer received Target's `player_info` add immediately followed by `player_remove`; unvanish later produced `player_info action=29` for Target on both clients. This proves observed tab-list masking/restoration packets, but does not prove an entity packet transition or a no-flicker/one-Paper-tick guarantee.
- Redis showed one request subscriber and two event/response subscribers while the network was live. An owned Redis stop/restart produced subscriber reconnect logs and restored `PONG`/subscriber counts, but the ephemeral Redis snapshot key was not restored after an end-of-stream repair race; a subsequent `/vanish` request was rejected with `Redis snapshot key is missing`. Redis restart/resync is therefore **BLOCKED/NOT PASS**, not silently upgraded.

Current isolated assessment:

| Scenario | Current result |
|---|---|
| Alternate-port component startup, versions, forwarding preflight, and status reachability | PASS |
| Local offline login through Velocity and `/server alpha`/`/server beta` routing | PASS for exercised transitions |
| `/vanish` acknowledgement and observer tab packet masking/restoration | PASS at observed packet level |
| Entity hiding, no-flicker timing, and one-Paper-tick destination guarantee | NOT PROVEN; the probe observed a Target tab add followed immediately by removal |
| Redis restart and subscriber resync | PASS in fixed follow-up; the initial run's ephemeral-key repair failure is retained in the detailed report |
| Empty backend reconciliation, `/vservers` filtering, and built-in `/server` guard | PASS in fixed follow-up; empty beta admitted Observer, `/vservers` returned `Servers: alpha, lobby`, and `/server beta` was unavailable while beta contained only vanished Target |
| Backend-offline state change and restart reconciliation | PASS in fixed follow-up; beta was offline during Target's vanish, restarted before Target's destination arrival, and applied the hidden state |
| Proxy JSON reload and post-restart destination guard | PASS in fixed follow-up; persisted `vanish-state.json` was reloaded and `/server beta` remained unavailable to Observer |
| Permission see exemption/revocation | PASS in fixed follow-up; configured Observer could see/enter hidden beta, then config removal plus proxy restart restored masking and denial |

Detailed commands, logs, packet observations, client limitations, and cleanup proof are in `.superpowers/sdd/vanish-no-packet/live-runtime-report.md`. No FR-008, NMS, ProtocolLib, direct packet injection, permanent client dependency, or user-owned process change was introduced.

## Fixed-artifact reconnect follow-up

After the first live run, a regression test reproduced a transient Redis snapshot-write failure during the Velocity reconnect callback. The fix retries the durable snapshot repair with bounded backoff and stops scheduling after shutdown:

```text
./gradlew :vanish-velocity:test --tests io.github.aincraft.vanish.velocity.RedisVelocityServiceTest.transientReconnectFailureRetriesSnapshotRepair
BUILD SUCCESSFUL
```

The fixed Velocity shadow jar used for the follow-up was SHA-256 `536614f9527aa57b75d06d685f99611998c15165889056eb2e18b4b360c1a05b`. A task-owned rerun used proxy `28176`, lobby `38171`, alpha `38169`, beta `38170`, and Redis `16380`. The current jar was loaded by Velocity; Redis was stopped and started as a fresh ephemeral container. Before the restart, Redis returned:

```text
{"schema":1,"type":"vanish_state","version":3,"vanished":["db958d5e-bde2-36ef-8ccd-8577d5387953"]}
```

After the restart and delayed repair retry, the same `GET vanish:state:snapshot` value returned. Velocity logs recorded one failed repair attempt followed by the retry path; the key was present after the retry. This updates the reconnect result for the fixed artifact only; it does not claim Redis persistence across loss of the Redis process's own storage.

The fixed-artifact follow-up also ran `/tmp/vanish-nopacket-live-20260829/node-client/empty_backend_probe.js`. Target vanished on alpha, Observer entered empty beta, Target later moved to beta, and Observer moved to lobby. `/vservers` returned `Servers: alpha, lobby` while beta contained only vanished Target; Observer's `/server beta` returned `That server is unavailable.` until Target unvanished, after which Observer entered beta and received Target's tab entry. The probe completed with `clients=2 firstLogins=2 events=70`. This proves empty-backend reconciliation, server-list filtering, and the built-in destination guard for the fixed artifact.

The fixed-artifact follow-up also ran `/tmp/vanish-nopacket-live-20260829/node-client/offline_backend_probe.js`. Beta was stopped while Target and Observer were on alpha, Target vanished while beta was offline, beta restarted, and Target later reached beta at `45.699s`. The target received `player_remove` at `46.632s`; Observer's `/server beta` returned `That server is unavailable.` at `52.154s` until Target unvanished, after which Observer reached beta at `60.262s` and received Target's tab entry. The probe completed with `clients=2 firstLogins=2 events=62`. This proves backend-offline state-change and startup reconciliation for the fixed artifact.
The fixed-artifact follow-up also exercised proxy restart/state reload. Target vanished at `18.152s`; after the proxy stopped, `vanish-state.json` contained version `1` with Target's UUID. Velocity restarted, Target reconnected and received `player_remove` for its own UUID at `0.931s` into the delayed post-restart probe, then reached beta at `4.282s`. Observer reconnected at `10.256s`, and `/server beta` returned `That server is unavailable.` at `20.154s`. The successful delayed probe completed with `clients=2 firstLogins=2 events=29`; an earlier immediate second reconnect was rate-limited and is not used as passing evidence.
The fixed-artifact follow-up also exercised the configured see exemption and restart-based revocation. With Observer's UUID in `see-uuids`, Target vanished and moved to beta; Observer entered beta at `32.234s`, received Target's tab entry at `32.235s`, and `/vservers` returned `Servers: alpha, beta, lobby` at `39.154s`. After the proxy restarted with `see-uuids: false`, Observer's `/server beta` returned `That server is unavailable.` at `20.158s`. The see probe completed with `clients=2 firstLogins=2 events=49`; the revoked probe completed with `clients=2 firstLogins=2 events=27`. This covers configured Velocity see exemption and restart-based revocation; it does not claim a hot-reload API.


The earlier limitations remain: the live probes did not isolate entity spawn/destroy packets and do not establish a no-flicker or one-Paper-tick guarantee. No FR-008 packet/NMS/ProtocolLib implementation was added.

