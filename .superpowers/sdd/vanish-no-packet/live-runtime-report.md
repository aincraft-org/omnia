# Isolated live runtime report — 2026-08-29

## Result

The isolated alternate-port network started and accepted two local offline protocol clients. Paper alpha/beta and Velocity loaded the current shadow jars, the proxy routed `/server alpha` and `/server beta`, and the client observed the vanish acknowledgement plus tab-list packets for hide/unhide transitions. This is stronger than the earlier required-port blocked record, but it is **not a full PASS for the smoke matrix**:

- The local probe observed tab-list packets, not a separately attributable entity-spawn/entity-destroy proof.
- The destination transition emitted a `Target` player-info add immediately followed by a player-remove at the same client timestamp. I do not claim a no-flicker or one-Paper-tick guarantee from this probe.
- The initial Redis restart attempt (before the reconnect-repair fix) restored subscribers but not the ephemeral snapshot key; the fixed-artifact follow-up below passes the retry/repair check.
- Proxy restart/config-reload, permission exemption/revocation, `/vservers`, and the full negative/online-backend matrix were not exercised.

The previous required-port `BLOCKED` record in `docs/smoke-results-2026-08-29.md` was preserved.

## Ownership and isolation

Only `/tmp/vanish-nopacket-live-20260829` and this isolated worktree were used for task-owned state. The shared harness under `/home/jlo/dev/omnia/.agents/skills/development-network` was read-only; it was not edited, and its shared runtime was not used. Existing listeners on 25565/30066/30067/30068 and other user-owned services were not stopped, changed, or attached to.

The owned process/container names were:

- `vanish-live-redis`: `docker run --rm --name vanish-live-redis-20260829 -p 127.0.0.1:16379:6379 redis:7.4-alpine`
- `vanish-live-lobby`: `boot-lobby.sh` with `SERVER_PORT=38166`
- `vanish-live-alpha`: `boot-backend.sh alpha` with `SERVER_PORT=38167` and `SERVER_DIR=.../runtime/auto/alpha`
- `vanish-live-beta`: `boot-backend.sh beta` with `SERVER_PORT=38168` and `SERVER_DIR=.../runtime/auto/beta`
- `vanish-live-proxy`: direct Java launch of the downloaded Velocity jar using the local `/tmp` config
- `vanish-live-client`: temporary Node offline protocol probe; it exited normally

The alternate port map was proxy `28175`, lobby `38166`, alpha `38167`, beta `38168`, and Redis `16379`.

## Preflight and artifact evidence

Preflight completed before starting the owned components. The local Velocity config was:

```text
config-version = "2.8"
bind = "0.0.0.0:28175"
online-mode = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "/tmp/vanish-nopacket-live-20260829/runtime/forwarding.secret"
[servers]
lobby = "localhost:38166"
alpha = "localhost:38167"
beta = "localhost:38168"
try = ["lobby", "alpha", "beta"]
```

Each Paper server was separately materialized with `online-mode=false`, `proxies.velocity.enabled=true`, `proxies.velocity.online-mode=false`, the shared development secret, and `spigot.yml settings.bungeecord: false`. Alpha and beta had distinct plugin configuration values `backend-id: alpha` and `backend-id: beta`; both pointed at Redis `localhost:16379`.

Version evidence:

```text
java -version: OpenJDK 25.0.2 (Red Hat)
Velocity log: Velocity 4.1.1
Paper logs: Paper 26.2-119-main@bb09b43 / API 26.2.build.119
Redis: redis_version 7.4.11; redis-cli ping -> PONG
status protocol: 776
```

Pinned server artifact hashes:

```text
a8c9140c3075bd7c04973e9cdc491b21bfe6bad472b674ef932a4ae0fec19629  paper-26.2-119.jar
846411d2d0560fed0f23496ffb89681be528d2c0650ecdcf21724d2d7bd9c1ee  velocity-4.1.1-24.jar
```

Current product shadow hashes deployed to the owned runtime:

```text
b8160d4bd19785741b9736f82b0addada1ef4a9b507796a5f3ba47a4ce5dbe43  vanish-paper-2026.08.29-SNAPSHOT.jar (alpha and beta)
1d1b56c4d9ea95137ac2d20e99e77444979006d079e681abbbe451d15f432c94  vanish-velocity-2026.08.29-SNAPSHOT.jar
```

Startup evidence from the owned logs:

```text
Velocity: Loaded plugin vanish-nopacket 2026.08.29-SNAPSHOT; Loaded 2 plugins; Listening on ...:28175
alpha: Bukkit plugins (1): VanishNoPacket (2026.08.29-SNAPSHOT); Starting Minecraft server on *:38167; Enabling VanishNoPacket
beta: Bukkit plugins (1): VanishNoPacket (2026.08.29-SNAPSHOT); Starting Minecraft server on *:38168; Enabling VanishNoPacket
lobby: Starting Minecraft server on *:38166; Done
```

The plugins emitted Jedis RESP3 auto-negotiation warnings. Initial Paper snapshot reconciliation also timed out in the startup logs before the later client run; this is recorded rather than presented as clean initialization. Once all components were running, the owned Redis reported:

```text
PUBSUB NUMSUB vanish:state:requests vanish:state:events vanish:state:responses
vanish:state:requests 1
vanish:state:events 2
vanish:state:responses 2
```

This proves one Velocity request subscriber and two Paper event/response subscribers at that observation point.

## Reachability and local client observation

A temporary Python status probe at `/tmp/vanish-nopacket-live-20260829/status_probe.py` performed Minecraft status handshakes against all four owned ports:

```text
proxy: reachable motd='dev-network' version=Velocity 1.7.2-26.2 protocol=776 players=0
lobby: reachable motd='dev-network lobby' version=Paper 26.2 protocol=776 players=0
alpha: reachable motd='dev-network alpha' version=Paper 26.2 protocol=776 players=0
beta: reachable motd='dev-network beta' version=Paper 26.2 protocol=776 players=0
```

A temporary local Node probe used `minecraft-protocol@1.68.0` installed under `/tmp` only. Its package had 26.1 schemas, so it used the 26.1 serializer/parser while overriding only the outgoing handshake protocol value to 776. It connected two local offline names (`Target`, `Observer`) through the owned proxy; it did not use a remote account, authentication bypass, packet injection, NMS, ProtocolLib, or a permanent repository dependency. The parser logged known 26.1/26.2 schema-size warnings, so only the explicitly observed selected packets below are relied upon.

Selected evidence from `/tmp/vanish-nopacket-live-20260829/node-client/live_vanish_probe.log`:

1. `Target` received login success, joined lobby, sent `/server alpha`, and received a new play login at alpha. `Observer` did the same after the proxy's 3-second login rate limit, then joined alpha.
2. At `10.755s`, Observer received `player_info action=255 players=[Target:listed=1]`, proving the target was present in the observer's alpha tab view before vanish.
3. Observer then sent `/server beta` and received beta's play login at `14.649s`, while Target remained on alpha.
4. Target sent `/vanish` at `19.148s`; at `19.188s` Target received the server acknowledgement `Target is now vanished.`
5. Target sent `/server beta` at `24.149s`. At `24.259s`, Observer received a `player_info action=255` add for Target followed immediately by `player_remove uuids=["db958d5e-bde2-36ef-8ccd-8577d5387953"]`. This is an actual observer-side protocol observation of the vanished target being removed after the cross-backend join. Because the add and remove were both observed, this report does not claim zero transient exposure or a one-tick guarantee.
6. Target sent `/vanish` again at `32.148s`. At `32.156s`, both Target and Observer received `player_info action=29 players=[Target:listed=1]`; at `32.194s`, Target received `Target is now visible.` This is packet-level evidence of unvanish restoration to the observer.
7. Proxy logs recorded `Target -> alpha`, `Observer -> alpha`, `Observer -> beta`, and `Target -> beta` server connections, confirming that the commands exercised real Velocity routing rather than direct backend-only sessions.

No entity-spawn/entity-destroy packet was isolated and correlated to the vanish transition in this run. Therefore entity hiding remains **not proven** by live client evidence.

## Redis restart attempt

The owned Redis container was stopped and restarted while the Paper/Velocity processes remained task-owned. Before the stop, the normal state key was readable after the client run:

```text
GET vanish:state:snapshot
{"schema":1,"type":"vanish_state","version":2,"vanished":[]}
```

During the outage, Velocity logged `Vanish Redis disconnected` and Paper logged `Redis subscriber disconnected; retrying` with `JedisConnectionException`; after the restart, `redis-cli ping` returned `PONG` and `PUBSUB NUMSUB` returned the same `1/2/2` subscriber counts. Because this first run used an intentionally ephemeral Redis container and the pre-fix artifact, the durable key remained absent. The reconnect repair attempt logged `Unable to repair the Redis vanish snapshot` after an end-of-stream race, and `GET vanish:state:snapshot` remained nil. A subsequent local Target client attempt received:

```text
Vanish authority request failed: Redis snapshot key is missing
```

This initial pre-fix run observed Redis TCP/subscriber reconnect, but durable snapshot repair and post-restart mutation were **not a PASS**. The fixed-artifact retry result is recorded in `## Fixed-artifact follow-up`.

## Smoke assessment

| Observation | Current isolated result |
|---|---|
| Java/Paper/Velocity/Redis versions and offline/modern-forwarding preflight | PASS |
| Owned proxy/lobby/alpha/beta startup and plugin loading | PASS, with startup reconciliation warnings recorded above |
| Direct status reachability on all alternate ports | PASS |
| Real local offline login through Velocity | PASS |
| `/server alpha` and `/server beta` routing | PASS for the exercised Target/Observer transitions |
| Vanish acknowledgement | PASS for Target's observed server chat acknowledgement |
| Observer's pre-vanish target tab entry | PASS; `player_info` observed |
| Observer-side hide after Target moves alpha -> beta | PARTIAL; `player_info` add then `player_remove` observed at the same client timestamp |
| Separate entity hiding proof | NOT PROVEN; no correlated entity packet was captured |
| Unvanish restoration | PASS at tab-packet level; `player_info action=29` observed by both clients |
| Redis restart and subscriber resync | PASS in fixed follow-up; initial pre-fix run was not a pass |
| Proxy restart and JSON reload | BLOCKED; not exercised as a client scenario |
| Permission see exemption/revocation | BLOCKED; not exercised |
| `/vservers`/`/vanishservers` filtering | PASS in fixed follow-up; `/vservers` omitted the beta server containing only vanished Target |
| Empty backend reconciliation and built-in `/server` guard | PASS in fixed follow-up; Observer entered empty beta while Target was vanished elsewhere, then `/vservers` omitted beta and `/server beta` returned `That server is unavailable.` |

No FR-008 implementation or direct packet/NMS/ProtocolLib path was opened.

## Cleanup evidence

Only task-owned names were stopped: `vanish-live-proxy`, `vanish-live-alpha`, `vanish-live-beta`, `vanish-live-lobby`, and `vanish-live-redis`. The temporary client had already exited normally. After cleanup:

```text
ss -ltn filtered for 28175,38166,38167,38168,16379: no output
container filter vanish-live-redis-20260829: no output
```

No user-owned listener or service was stopped or modified.

## Fixed-artifact follow-up

The first live run exposed a real reconnect defect: if the first Velocity durable-snapshot write after Redis reconnect failed, `onRedisConnected()` logged the failure and never retried. A regression test now covers one transient write failure:

```text
./gradlew :vanish-velocity:test --tests io.github.aincraft.vanish.velocity.RedisVelocityServiceTest.transientReconnectFailureRetriesSnapshotRepair
BUILD SUCCESSFUL
```

The fix retries snapshot repair with bounded backoff and stops scheduling after shutdown. The rebuilt Velocity shadow jar used for the follow-up was:

```text
536614f9527aa57b75d06d685f99611998c15165889056eb2e18b4b360c1a05b
```

A second task-owned runtime used proxy `28176`, lobby `38171`, alpha `38169`, beta `38170`, and Redis `16380`; all shared harness and user-owned endpoints remained untouched. It loaded the current Paper shadow jar and the fixed Velocity shadow jar. The temporary Node probe at `/tmp/vanish-nopacket-live-20260829/node-client/live_vanish_rerun.js` used `minecraft-protocol@1.68.0` under `/tmp` only and overrode the handshake protocol to `776` for the local offline test clients.

Selected follow-up observations from `/tmp/vanish-nopacket-live-20260829/node-client/live_vanish_rerun.log`:

1. `Target` and `Observer` logged in through Velocity, reached alpha through `/server alpha`, and Observer received Target's `player_info action=255` entry at `11.242s` before the transition.
2. Target sent `/vanish` at `18.154s`; Observer received `player_remove` for Target at `18.167s`, and Target received `Target is now vanished.` at `18.176s`.
3. Target sent `/server beta` at `24.154s` and received beta's play login at `24.694s`. The observer-side alpha session removed Target during the cross-backend departure.
4. Target sent `/vanish` on beta at `32.154s`; both clients received `player_info action=29` for Target at `32.162s`, and Target received `Target is now visible.` at `32.175s`. This confirms beta had the authoritative vanished state before the command toggled it visible.
5. Observer then sent `/server beta` at `36.154s`, received a beta play login at `36.239s`, and received Target in the beta tab list at `36.240s`. Target vanished again at `44.175s`; both clients received `player_remove` for Target at `44.190s`/`44.191s` and Target received the vanished acknowledgement at `44.235s`.

The probe emitted known 26.1/26.2 schema-size warnings, so the report relies only on the explicitly decoded login, server-chat, player-info, and player-remove packets. It proves real two-client Velocity routing and Paper-managed tab masking/restoration; it does not prove a separately attributable entity-spawn/entity-destroy transition, no-flicker timing, or a one-Paper-tick guarantee.

The fixed Redis reconnect check started the proxy with the prior durable state at version `3`, stopped the task-owned ephemeral Redis container, started a fresh container, and observed the fixed proxy's retry path restore the key. `GET vanish:state:snapshot` before and after repair returned:

```text
{"schema":1,"type":"vanish_state","version":3,"vanished":["db958d5e-bde2-36ef-8ccd-8577d5387953"]}
```

Velocity logs recorded one failed repair attempt followed by the delayed retry. This proves reconnect repair for the current artifact; it does not claim Redis can retain a key after its own storage is destroyed.

The original required-port run and the first ephemeral Redis restart failure remain preserved as historical evidence. Backend-offline transitions, permission see exemption/revocation, proxy JSON reload, and the full no-leak timing matrix remain unexercised. The fixed follow-up now covers empty-backend reconciliation, `/vservers` filtering, and the built-in `/server` guard. No FR-008, NMS, ProtocolLib, direct packet injection, or permanent client dependency was added.

## Fixed-artifact empty-backend and server-filter follow-up

A third task-owned run used the same fixed artifacts and ports as the reconnect follow-up: proxy `28176`, lobby `38171`, alpha `38169`, beta `38170`, and Redis `16380`. The temporary client probe was `/tmp/vanish-nopacket-live-20260829/node-client/empty_backend_probe.js`; its output is `/tmp/vanish-nopacket-live-20260829/node-client/empty_backend_probe.log`.

Selected observations:

1. Target vanished on alpha at `18.151s`. Observer then requested `/server beta` at `23.152s` while beta was empty and Target remained on alpha; Observer reached beta's play login at `23.744s`. This proves a backend can reconcile the already-vanished state when the first non-vanished viewer arrives.
2. Target requested `/server beta` at `28.151s`. Observer received the target tab add at `28.225s` followed by `player_remove` at `28.658s`, matching the target's vanished state after the cross-backend arrival.
3. Observer moved to empty lobby at `34.152s` and requested `/vservers` at `38.151s`; the proxy returned `Servers: alpha, lobby` at `38.155s`, omitting beta because it contained only the vanished Target.
4. Observer requested `/server beta` at `42.151s`; Velocity returned `That server is unavailable.` at `42.152s`. After Target unvanished on beta at `46.151s`, both clients received `player_info action=29` at `46.160s`/`46.161s`; Observer's `/server beta` at `48.151s` then reached beta login at `48.221s` and received Target's tab entry at `48.222s`.

The probe completed with `clients=2 firstLogins=2 events=70`. This adds direct evidence for empty-backend reconciliation, `/vservers` filtering, and the built-in server guard. Backend-offline transitions, permission exemption/revocation, proxy JSON reload, and no-leak timing remain unexercised. No FR-008, NMS, ProtocolLib, direct packet injection, or permanent client dependency was added.
