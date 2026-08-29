# Isolated live runtime report — 2026-08-29

## Result

The isolated alternate-port network started and accepted two local offline protocol clients. Paper alpha/beta and Velocity loaded the current shadow jars, the proxy routed `/server alpha` and `/server beta`, and the client observed the vanish acknowledgement plus tab-list packets for hide/unhide transitions. This is stronger than the earlier required-port blocked record, but it is **not a full PASS for the smoke matrix**:

- The local probe observed tab-list packets, not a separately attributable entity-spawn/entity-destroy proof.
- The destination transition emitted a `Target` player-info add immediately followed by a player-remove at the same client timestamp. I do not claim a no-flicker or one-Paper-tick guarantee from this probe.
- Redis transport subscribers reconnected after an owned Redis restart, but the ephemeral durable snapshot key was not restored. The subsequent `/vanish` request was rejected with `Redis snapshot key is missing`.
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

During the outage, Velocity logged `Vanish Redis disconnected` and Paper logged `Redis subscriber disconnected; retrying` with `JedisConnectionException`; after the restart, `redis-cli ping` again returned `PONG` and `PUBSUB NUMSUB` returned the same `1/2/2` subscriber counts. However, because the Redis container was intentionally ephemeral, the durable key was initially absent. The reconnect repair attempt logged `Unable to repair the Redis vanish snapshot` after an end-of-stream race, and `GET vanish:state:snapshot` remained nil. A subsequent local Target client attempt received:

```text
Vanish authority request failed: Redis snapshot key is missing
```

Thus Redis TCP/subscriber reconnect was observed, but durable snapshot repair and post-restart mutation were **not a PASS**.

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
| Redis restart and subscriber resync | BLOCKED/NOT PASS; subscribers returned but snapshot key repair failed |
| Proxy restart and JSON reload | BLOCKED; not exercised as a client scenario |
| Permission see exemption/revocation | BLOCKED; not exercised |
| `/vservers`/`/vanishservers` filtering | BLOCKED; not exercised |
| Full matrix including backend offline/empty and no-leak timing | BLOCKED; not proven by this run |

No FR-008 implementation or direct packet/NMS/ProtocolLib path was opened.

## Cleanup evidence

Only task-owned names were stopped: `vanish-live-proxy`, `vanish-live-alpha`, `vanish-live-beta`, `vanish-live-lobby`, and `vanish-live-redis`. The temporary client had already exited normally. After cleanup:

```text
ss -ltn filtered for 28175,38166,38167,38168,16379: no output
container filter vanish-live-redis-20260829: no output
```

No user-owned listener or service was stopped or modified.
