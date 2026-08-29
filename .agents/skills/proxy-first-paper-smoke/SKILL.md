---
name: proxy-first-paper-smoke
description: Use when launching or smoke-testing a Paper/Minecraft plugin where players should connect through Velocity, including ambiguous “run Paper” requests, runServer/runNetwork/runProxy/registerBackend tasks, proxy ports, backend registration, or player-facing verification.
---

# Proxy-First Paper Smoke

## Overview

Direct Paper readiness proves backend startup, not player behavior. Unless “isolated backend” is explicit, use the development-network proxy.

**Reference:** Read `development-network` for current commands and ownership; use the current sibling copy.

## Choose the workflow

| Request | Workflow | Success address |
|---|---|---|
| Compile or run unit tests | No server | N/A |
| Explicit isolated backend diagnosis | `runServer` only; state that it bypasses Velocity | Backend port only |
| Normal local runtime or player test | `runProxy` + backend + `registerBackend` | `localhost:25565` |
| One-project managed network | `runNetwork` only after verifying its deployed jar | `localhost:25565` |

Shared ownership: `runProxy` owns proxy/lobby; `runServer` owns Paper; `registerBackend` only attaches/reloads. Direct `runServer` is not proxy evidence.

## Preflight

- Read `development-network` and the build file.
- Identify the product artifact; a test jar is not the product.
- Inspect owners, stale registry/pids, listeners, and ports; preserve worlds/maps.
- Verify offline mode, modern forwarding, and matching secret; never silently rewrite external servers.
- Confirm the existing DB; create no users/databases. Reserve proxy `25565`, lobby `30066`, and backend `30067+` (or explicit `30070`).

## Concrete shared-network pattern

```bash
# Terminal A: keep infrastructure running; wait for proxy/lobby readiness.
./gradlew :<network-project>:runProxy \
  -PnetworkBase=run/network -PnetworkProxyPort=25565 \
  -PnetworkOnlineMode=false

# Terminal B: start after A; this backend port is internal.
./gradlew :<plugin-project>:runServer

# Terminal C: attach only; record the owner for unregistering.
./gradlew :<plugin-project>:registerBackend \
  -PnetworkBase=run/network -PnetworkBackend=<backend-name> \
  -PnetworkBackendPort=30070 \
  -PnetworkRegistrationOwner=<smoke-owner>
```

Use actual owners and `-PdevNetworkBin` when needed. Configure a project-specific `runServer` backend port using that task’s documented property. For `runBackend`/`runNetwork`, verify the owning jar task and product artifact; never force `-PnetworkJarTask=shadowJar` unless it exists there. Guilds uses `guilds-paper:shadowJar`; `guilds-test.jar` is only TestPlugin.

Map choice is separate: Rust-backed squaremap uses `squaremap-server`; verify the artifact.

## Verification and cleanup

Verify ready logs/listeners, registration, and reload. Status is reachability only. Prove the player path with login to `localhost:25565`, lobby arrival, `/server <backend>`, and the requested feature; report observations. Test HTTP separately.

Unregister with the recorded owner:
`./gradlew :<plugin-project>:unregisterBackend -PnetworkBase=run/network -PnetworkBackend=<backend-name> -PnetworkRegistrationOwner=<smoke-owner>`.
Stop only run-owned processes and database containers; never broad-`pkill`, delete worlds, or remove shared state.

## Common mistakes

| Mistake or rationalization | Reality |
|---|---|
| “The direct server is ready; that is enough.” | Direct readiness bypasses Velocity; use the proxy unless isolation was requested. |
| “Players can connect to backend port 30070.” | `30070` is internal; players use `25565`. |
| “`runNetwork` must deploy Guilds.” | It may deploy a test jar; verify the task and production artifact. |
| “The proxy config will configure Paper.” | Each server needs its own offline/forwarding configuration. |
| “Existing runtime state is harmless.” | Inspect stale ownership and ports before reuse. |
