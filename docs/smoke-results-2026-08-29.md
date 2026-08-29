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
/tmp/vanish-nopacket-task8/proxy/plugins/vanish-velocity-2026.08.29-SNAPSHOT.jar
```

SHA-256:

```text
vanish-paper-2026.08.29-SNAPSHOT.jar    6d8682820def04c9f339bea5a5b5ee06233b549a4f9c66a868daac5c2cd226e9
vanish-velocity-2026.08.29-SNAPSHOT.jar 6df2e35b40de8c8dae830ca392f7d4c758cfaf65c5261b10199ee70c6cb4e31b
```

## Gradle wiring

The standalone project was wired only to the existing composite:

- `settings.gradle.kts` includes `/home/jlo/dev/omnia/.agents/skills/development-network/network`.
- `vanish-paper/build.gradle.kts` applies `io.github.development-network`.
- `vanish-velocity/build.gradle.kts` applies `io.github.development-network`.

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
