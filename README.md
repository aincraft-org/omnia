# VanishNoPacket

VanishNoPacket provides authoritative, cross-backend vanish state for a Velocity proxy and its Paper backends. It uses Redis for shared state and the public Paper/Velocity APIs for visibility; it does not add a direct packet-injection path.

## Product boundary

The default implementation deliberately does **not** use hand-crafted client packet injection, ProtocolLib, NMS, or raw packet construction. Paper's public `Player.hidePlayer`/`showPlayer` and Velocity's public tab-list API still produce client-visible entity/tab updates; this is not a zero-packet implementation. A backend-only API cannot enforce a vanish state across other backends, so the proxy is the authoritative state owner and every Paper backend enforces the current state locally.

FR-008 (direct player-info packet correction) is not implemented. It is approval-gated and must not be inferred from this project.

## Deployment

The supported topology is:

1. Build one deployable Paper jar and install it on **every** backend behind the proxy.
2. Build one deployable Velocity jar and install it on the proxy.
3. Make one Redis instance reachable by Velocity and every Paper backend. Use the same Redis host, port, credentials, and database in every component.
4. Have clients connect only to Velocity. Do not expose backend addresses as client entry points.
5. Set a distinct `backend-id` in each Paper backend's `plugins/VanishNoPacket/config.yml` (`alpha`, `beta`, etc.).

The Paper plugin declares `/vanish` and the Velocity plugin registers `/vservers` with `/vanishservers` as an alias. Server names are the names registered in Velocity, not Paper backend IDs unless you deliberately use the same names.

Build artifacts:

```bash
./gradlew :vanish-paper:shadowJar :vanish-velocity:shadowJar
```

Install the resulting `vanish-paper-<version>.jar` on every backend and `vanish-velocity-<version>.jar` in Velocity's `plugins/` directory. The `shadowJar` artifacts are the deployable jars.

## Commands and permissions

### `/vanish [player]`

- `/vanish` toggles the executing player's authoritative state. It requires `vanish.use` and can only be run by a player.
- `/vanish <exact-player-name>` toggles an online player. Toggling yourself requires `vanish.use`; toggling another player requires `vanish.others`.
- The command reads the authoritative snapshot, submits a desired-state request, and reports success only after an acknowledgement. A rejected or unavailable authority is reported instead of changing local state optimistically.
- Names are exact and online. The command does not toggle offline players.

### `/vanish status`

Displays the state version and vanished UUIDs, sorted by UUID. It requires `vanish.admin`.

Paper permission nodes are declared with operator defaults:

| Node | Purpose |
| --- | --- |
| `vanish.use` | Toggle your own vanish state |
| `vanish.others` | Toggle another online player's state |
| `vanish.see` | See vanished players on Paper |
| `vanish.admin` | Use `/vanish status` |

The `vanish.see` permission is evaluated per viewer. Permission changes are reconciled on the Paper main thread every second, and visibility is restored or removed when the effective permission changes.

### `/vservers` and `/vanishservers`

With no argument, the command lists servers visible to the source. A player can connect with `/vservers <server>`. It hides a server only when all players currently connected to that server are vanished; a server with any non-vanished connected player remains listed. A player without the configured see exemption receives `That server is unavailable.` for hidden destinations. Console sources can see all destinations but cannot connect.

Velocity also masks hidden destinations from `/server` tab completion for viewers without the see exemption. The built-in `/server <name>` route is guarded as well: hidden destinations are denied unless the viewer is exempt.

## Configuration

Generated defaults are in [`vanish-paper/src/main/resources/config.yml`](vanish-paper/src/main/resources/config.yml) and [`vanish-velocity/src/main/resources/config.yml`](vanish-velocity/src/main/resources/config.yml). Restart after editing configuration.

### Paper (`plugins/VanishNoPacket/config.yml`)

```yaml
redis:
  host: localhost
  port: 6379
  username: ""
  password: ""
  database: 0
  connection-timeout-ms: 5000
  socket-timeout-ms: 5000
  blocking-socket-timeout-ms: 0
  request-timeout-ms: 5000
  retry-initial-ms: 500
  retry-max-ms: 30000
backend-id: paper-local
```

`backend-id` must be non-blank and unique per backend. The Redis timeouts and retry values are milliseconds. `blocking-socket-timeout-ms: 0` means the blocking subscriber socket has no client-side timeout.

### Velocity (`plugins/vanish-nopacket/config.yml`)

```yaml
redis:
  host: localhost
  port: 6379
  username: ""
  password: ""
  database: 0
connection-timeout-millis: 5000
socket-timeout-millis: 5000
blocking-socket-timeout-millis: 0
retry-initial-millis: 500
retry-max-millis: 30000
state-file: vanish-state.json
server-selection-masking: true
player-list-masking: true
see-uuids: false
```

Velocity reads this flat YAML by key. `state-file` is relative to Velocity's plugin data directory unless absolute. `see-uuids` is an optional list of canonical UUIDs; those viewers are exempt from proxy tab/server masking. Empty or `false` means no configured exemptions. The current implementation does not use `server-selection-masking` or `player-list-masking` as feature switches; the registered masking and routing handlers remain active. Keep them documented as compatibility settings only.

## State, Redis channels, and reconciliation

Velocity owns the durable state file and publishes the authoritative state to Redis. The fixed Redis key and channels are:

| Redis object | Value |
| --- | --- |
| Key `vanish:state:snapshot` | Latest `vanish_state` JSON snapshot |
| Channel `vanish:state:requests` | Paper change requests and snapshot requests sent to the proxy |
| Channel `vanish:state:events` | Proxy-published contiguous `state_delta` events |
| Channel `vanish:state:responses` | Change acknowledgements and snapshot responses |

Messages use schema version `1` and strict JSON envelopes. A full state is encoded as:

```json
{"schema":1,"type":"vanish_state","version":3,"vanished":["01234567-89ab-cdef-0123-456789abcdef"]}
```

Delta messages contain `schema`, `type: "state_delta"`, `version`, `playerId`, and `vanished`. Change requests contain `requestId`, `playerId`, and desired `vanished`; snapshot requests contain `requestId` and `backendId`; responses contain the request ID, backend ID, and state; acknowledgements contain request ID, `accepted`, `version`, and `error`.

On startup, Velocity loads its local state, publishes a durable snapshot, and subscribes for requests. Each Paper backend subscribes to events and responses, then reconciles its cache from the durable Redis snapshot. If a subscriber starts late, misses a delta, or detects a non-contiguous version, it requests a full snapshot through `vanish:state:requests`. Deltas are applied only when contiguous; stale deltas are ignored. Redis reconnects restart subscription/reconciliation with bounded backoff (`retry-initial-*` through `retry-max-*`). Join and pre-login paths reconcile before applying visibility; a valid cached state may be used while a fresh read is retried.

## Exact state-file schema

The Velocity `state-file` is **not** the Redis envelope. It must be a strict JSON object with exactly two fields:

```json
{
  "version": 3,
  "vanished": {
    "01234567-89ab-cdef-0123-456789abcdef": true,
    "fedcba98-7654-3210-fedc-ba9876543210": true
  }
}
```

- `version` is a non-negative integer.
- `vanished` is an object whose keys are canonical UUID strings and whose values are booleans. Only entries with `true` are included in the in-memory vanished set; the writer emits true entries.
- Unknown top-level fields, non-canonical UUIDs, non-boolean values, negative versions, trailing JSON, or malformed JSON are rejected.
- Writes use a temporary file and atomic replacement.

If the file is corrupt or unreadable, the proxy preserves it as `vanish-state.json.bak` (or a unique UUID-suffixed `.bak` if that exists), loads an empty invalid state, disables mutations and Redis publication, and does not register the active proxy handlers. This is fail-closed recovery: repair or remove the preserved backup only after investigating it, then restart the proxy. Do not hand-edit a live file while the proxy is running.

## Evidence and limitations

The [Paper API visibility POC](docs/poc-results-2026-08-29.md) and [cross-backend smoke record](docs/smoke-results-2026-08-29.md) are the complete runtime evidence for this work. Both records explicitly mark the required live matrix **BLOCKED** because user-owned listeners occupied the harness endpoints and its lobby-port gate prevented startup. Redis readiness and jar packaging passed, but no client connected and no blocked scenario is a runtime PASS. No live convergence, one-tick destination timing, tab behavior, or flicker/leak timing claim is made here.

See [`content/docs/`](content/docs/) for a guided setup and operator walkthrough.
