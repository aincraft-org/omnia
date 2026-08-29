---
name: pebblehost-deploy
description: Use when deploying a built Minecraft/Paper plugin or mod jar to PebbleHost servers, wiring the pebblehost-deploy Gradle plugin into a build, installing or logging in the pb CLI, configuring canary/flat rollout, restarting remote servers after a jar upload, or troubleshooting PebbleHost upload/verify failures. Triggers include "deploy to PebbleHost", pb login/install, deployPebbleHost, pebblehost block, canary rollout, PEBBLEHOST_API_TOKEN, and pebblehost-cli.
---

# PebbleHost Deployment

Deployment is **jar upload, not remote install**: build the jar from this GitHub repository (locally or in GitHub Actions), push it into the server's `plugins/` (or `mods/`) directory, and restart the server. `pb` has no `link`, `install`, or repository command that clones GitHub source onto a PebbleHost server; installing `pb` from a GitHub release is only CLI installation.

Use one of these paths, but only after the selected `pb` binary passes the push-capability check below:

- **`deployPebbleHost` Gradle task** (recommended) — via the `dev.mintychochip.pebblehost.deploy` plugin; handles `pb` resolution, rollout planning, restart, verification, rollback. Set `pbBinary` explicitly when the plugin's default CLI does not pass the check.
- **`pb` CLI directly** — `pb file push` for manual uploads, using a released CLI that exposes that subcommand or a locally built push-capable binary.

Pins and behaviors verified 2026-08-28 against `pebblehost-cli` (master, tags, `feat/file-push-restored` branch) and `pebblehost-deploy` sources (`PebbleHostPlugin.java`, `DeployPebbleHostTask.java`, `PebbleHostClient.java`).

## GitHub source vs server upload

GitHub is the source and CI surface; PebbleHost receives the built artifact. Push the repository to GitHub, build the exact jar, then pass that jar to `deployPebbleHost` or `pb file push`. Do not describe this as linking a repository or installing source remotely. The deploy plugin itself is installed from GitHub Packages through the `pluginManagement` block below.

## Prerequisites

- `pb` binary — install with the one-line installer:

  ```bash
  curl -sSL https://raw.githubusercontent.com/mintychochip/pebblehost-cli/master/scripts/install.sh | sh
  # pinned release:
  curl -sSL https://raw.githubusercontent.com/mintychochip/pebblehost-cli/master/scripts/install.sh | sh -s -- --tag v2026.8.15.3
  ```

  Installs to `~/.local/bin` (or `/usr/local/bin`), on `PATH`. Update with `pb update`.

  Before any upload, bind the check to the exact binary the deployment will invoke:

  ```bash
  PB_BIN="${PB_BIN:-$(command -v pb)}"
  test -n "$PB_BIN" || {
      echo "No pb binary found; deployment is unavailable." >&2
      exit 1
  }
  "$PB_BIN" --version
  "$PB_BIN" file --help | grep -Fq 'push' || {
      echo "$PB_BIN does not expose 'file push'; deployment is unavailable." >&2
      exit 1
  }
  ```

  For Gradle, set `PB_BIN` to the same path configured in `pebblehost.pbBinary`, run this check, then run `./gradlew deployPebbleHost`. If Gradle is intentionally using its `pb`-on-`PATH` default, leave `PB_BIN` unset. Do not validate one binary and deploy with another.

  Do not claim deployment succeeded unless this check passes and the server log confirms the plugin loaded.

- **`file push` caveat — released CLI lacks it.** The latest release tag `v2026.8.27.23` does not contain `file push`; that subcommand exists only on the unmerged `feat/file-push-restored` branch (verified 2026-08-28). The deploy plugin calls `pb file push`, so a stock install fails with `error: unrecognized subcommand 'file push'`. Until a release includes it, build from source:

  ```bash
  git clone https://github.com/mintychochip/pebblehost-cli.git
  cd pebblehost-cli
  git checkout origin/feat/file-push-restored
  cargo build --release
  ```

  Point the Gradle plugin at the resulting binary:

  ```kotlin
  pebblehost {
      pbBinary = "/path/to/pebblehost-cli/target/release/pb"
  }
  ```

  Re-check tags before assuming this is fixed; record the verified date when it changes.

- **API token compatibility** — `pb login` stores a key for the released CLI, whose current master reads `PEBBLEHOST_API_KEY`. The push-capable `feat/file-push-restored` branch reads `PEBBLEHOST_API_TOKEN`, matching the deploy plugin's environment handoff. The plugin resolves its token from the extension `token`, then `PEBBLEHOST_API_TOKEN`, then Gradle project property `pebblehostToken`; use the push-capable binary until a release provides `file push`.

## Wiring the Gradle plugin

Published to GitHub Packages on every `v*` tag. In `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/mintychochip/pebblehost-deploy")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: settings.extra["gpr.user"]?.toString()
                password = System.getenv("GITHUB_TOKEN") ?: settings.extra["gpr.key"]?.toString()
            }
        }
        gradlePluginPortal()
    }
}
```

Apply in `build.gradle.kts`:

```kotlin
plugins {
    id("dev.mintychochip.pebblehost.deploy") version "2026.08.21"
}
```

Configure:

```kotlin
pebblehost {
    jar = file("build/libs/myplugin.jar")
    targetDir = "plugins"                // or "mods"
    strategy = "flat"                    // "flat" | "groups"
    canaryGate = true
    continueAfterCanary = false
    restart = true
    verifyState = "running"
    verifyTimeoutMs = 180_000
    rollback = "abort"                   // "abort" | "restore"
    pbBinary = "pb"                      // optional path to a pb with file push
    target("abc123")                     // server UUID; default group
}
```

Resolve the target's server UUID with `pb servers --json` (server `uuid` field).

## Deploy

Manual:

```bash
./gradlew deployPebbleHost
./gradlew deployPebbleHost --deploy-restart=false   # upload only
./gradlew deployPebbleHost --continue-after-canary  # proceed past canary group
```

The task is `@UntrackedTask` — it always runs, never up-to-date. Exit non-zero with per-server status on failure.

`pb` resolution order: explicit `pbBinary` (must exist) → `pb` on `PATH` → auto-download of the release pinned by `cliVersion` (`latest` default; one GitHub API call, so set `GITHUB_TOKEN` to avoid rate limits), cached under `$GRADLE_USER_HOME/caches/pebblehost-deploy/pb/<version>/`. Because released CLIs lack `file push`, the auto-download path fails until a release ships it — prefer an explicit `pbBinary`.

### Rollout

- `flat`: all targets deploy in parallel.
- `groups`: targets with `group` deploy in parallel within a group; groups run in order. With `canaryGate=true`, only the first group deploys until re-run with `--continue-after-canary`.

### Restart and verification

Uploading a jar does not hot-reload: the new code runs only after a restart. `restart=true` restarts and waits for `verifyState` (default `running`). Mechanical verification (server reachable/running) does **not** prove the plugin loaded — confirm via server console/log after deploy. `rollback=restore` restores the versioned backup, restarts, and re-verifies on failure.

### CI

The reusable `deploy.yml` workflow is the GitHub remote flow: it checks out the repository, builds the jar, installs the latest `pb` release, verifies `pb file push`, and runs the same task with `PEBBLEHOST_API_TOKEN` from secrets. As of the 2026-08-28 verification, the latest CLI release `v2026.8.27.23` lacks `file push`, so this workflow stops at its verification step until a push-capable CLI release is published or the workflow's installer is changed; do not claim CI deployment succeeded from the current workflow.

## Manual CLI upload (`pb file push`)

Requires a pb built with `file push` (see prerequisite caveat):

```bash
pb file push ./build/libs/myplugin.jar --server <SERVER_ID> --directory plugins
pb power <SERVER_ID> --action restart
```

`pb file <SERVER_ID> <path>` reads a file; `pb files <SERVER_ID>` lists. The CLI normalizes JSON:API envelopes with `--json`/`--verbose`.

## Common mistakes

| Wrong | Right | Why |
|---|---|---|
| Assuming `pb` from the released installer can deploy | Build pb from `feat/file-push-restored` until a release includes `file push` | Released CLI exits `unrecognized subcommand 'file push'` (verified 2026-08-28) |
| Treating "deploy" as installing the repo on GitHub | Build the jar, push it, restart | The CLI has no repository-link/remote-install command; upload is the only deploy primitive |
| `cliVersion = "latest"` with no `pbBinary` | Set `pbBinary` to a push-capable binary | Auto-download fetches the released CLI, which lacks `file push` |
| Restart without verification | `restart=true` + verify `running` + console log check | Reachable ≠ plugin loaded |
| `pb servers` output read raw | Use `pb servers --json` for the `uuid` field | JSON:API envelopes are normalized only under `--json` |
| Deploying every CI run without a gate | `workflow_dispatch` + canary group + `--continue-after-canary` | Rollout should be human-gated, not automatic on push |
| `retry`-less upload to a stopped server | Ensure server is running (or use the plugin's verify) | Upload to a stopped node can fail silently |

## Verify

```bash
pb --version                                  # CLI installed
pb servers --json                             # auth works; prints target server uuids
./gradlew deployPebbleHost                    # deploy + restart + verify
# then confirm plugin load in the server console/log
```