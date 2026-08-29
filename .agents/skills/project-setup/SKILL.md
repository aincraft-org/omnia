---
name: project-setup
description: Use when creating a new Paper/Minecraft plugin project, writing or editing its Gradle build files, wrapper, plugin.yml, project README, root AGENTS.md, or GitHub issue/PR templates, wiring a plugin project into the local development-network Gradle tasks, pinning toolchain or plugin versions, configuring CI or releases, or when paper-api coordinates fail to resolve. Triggers include scaffolding a new plugin repo, setting up GitHub community files, writing agent guidance, writing the scaffold README, CalVer versioning, nightly releases, and questions about current Gradle, run-paper, google-java-format, or development-network versions.
---

# Project Setup (Paper 26.2 Plugin)

Canonical scaffold for Minecraft **26.2** plugin development. Core principle: **use the pinned versions below — never from memory.** Plugin versioning changed in the 26.x line; stale-memory coordinates do not resolve.

## Pinned versions

| Component | Version |
|---|---|
| JDK toolchain | **25** |
| Gradle wrapper | **9.7.1** |
| paper-api | `io.papermc.paper:paper-api:26.2.build.+` |
| run-paper (`xyz.jpenilla.run-paper`) | **3.1.0** |
| Spotless (`com.diffplug.spotless`) | **8.10.0** |
| google-java-format | **1.36.1** |
| Checkstyle tool (`checkstyle.toolVersion`) | **13.11.0** |
| PMD tool (`pmd.toolVersion`) | **7.26.0** |
| SpotBugs Gradle plugin (`com.github.spotbugs`) | **6.5.10** |
| SpotBugs engine (`spotbugs.toolVersion`) | **4.9.7** |

Pins verified 2026-08-21 against official sources: Paper docs (project-setup, plugin-yml), Gradle Plugin Portal metadata, services.gradle.org, Maven Central, [Gradle Checkstyle plugin](https://docs.gradle.org/current/userguide/checkstyle_plugin.html), [Gradle PMD plugin](https://docs.gradle.org/current/userguide/pmd_plugin.html), [SpotBugs Gradle Plugin Portal](https://plugins.gradle.org/plugin/com.github.spotbugs), [SpotBugs plugin README](https://github.com/spotbugs/spotbugs-gradle-plugin), [SpotBugs 4.9.7 release notes](https://github.com/spotbugs/spotbugs/releases/tag/4.9.7), [Checkstyle](https://checkstyle.org/releasenotes.html), and [PMD](https://pmd.github.io/pmd/pmd_release_notes.html). `checkstyle` and `pmd` are Gradle built-in plugin IDs, so they have no independently pinned plugin versions; pin their analyzer `toolVersion` values. The SpotBugs engine pin is selected for Java 25 bytecode compatibility; verify it again when updating the JDK.

Style: [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) is the only style guide. Enforce mechanically via Spotless and Checkstyle — never hand-format.

## Scaffold

```
gradle wrapper --gradle-version 9.7.1   # once, then commit gradlew/
```

`settings.gradle.kts`:

```kotlin
rootProject.name = "example-plugin"
```

`build.gradle.kts`:

```kotlin
plugins {
    java
    checkstyle
    pmd
    id("com.diffplug.spotless") version "8.10.0"
    id("com.github.spotbugs") version "6.5.10"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

val calverDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd"))

// CI: YYYY.MM.DD.<github_run_number>; local builds: dated -SNAPSHOT.
version = providers.gradleProperty("buildVersion")
    .orElse(
        providers.environmentVariable("GITHUB_RUN_NUMBER")
            .map { "$calverDate.$it" }
    )
    .orElse("$calverDate-SNAPSHOT")
    .get()

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

processResources {
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

checkstyle {
    toolVersion = "13.11.0"
    config = resources.text.fromUri(
        "https://raw.githubusercontent.com/checkstyle/checkstyle/checkstyle-13.11.0/src/main/resources/google_checks.xml"
    )
}

pmd {
    toolVersion = "7.26.0"
    isIgnoreFailures = false
}

spotbugs {
    toolVersion.set("4.9.7")
    ignoreFailures.set(false)
}

spotless {
    java {
        googleJavaFormat("1.36.1")
    }
}

tasks.withType<Checkstyle>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<Pmd>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports {
        create("xml") { required.set(true) }
        create("html") { required.set(true) }
    }
}

tasks.named("check") {
    dependsOn(tasks.withType<Checkstyle>())
    dependsOn(tasks.withType<Pmd>())
    dependsOn(tasks.withType<com.github.spotbugs.snom.SpotBugsTask>())
}

tasks {
    runServer {
        minecraftVersion("26.2")
    }
}
```

`src/main/resources/plugin.yml` (api-version MUST be quoted — unquoted `26.2` parses as a YAML float):

```yaml
name: ExamplePlugin
version: '${version}'
api-version: '26.2'
description: An example plugin
```


Main class — one class extends `JavaPlugin`; never name it `Main`; Google style = 2-space indent, 100-col limit, no wildcard imports:

```java
package io.github.username;

import org.bukkit.plugin.java.JavaPlugin;

public final class ExamplePlugin extends JavaPlugin {

  @Override
  public void onEnable() {
    getLogger().info("ExamplePlugin enabled");
  }
}
```

## Multi-module layout (recommended for Java)

For Java projects expected to grow beyond a single module, prefer a `[plugin]-common`, `[plugin]-api`, and `[plugin]-paper` multi-module Gradle layout. This is a recommendation, not a requirement — keep small plugins single-module until the separation provides clear value.

```text
[plugin]/
├── [plugin]-common/   # shared domain models, logic, utilities
├── [plugin]-api/      # public plugin interfaces and contracts
└── [plugin]-paper/    # Paper implementation, commands, listeners, plugin.yml
```
Dependency direction: `[plugin]-paper` → `[plugin]-api` → `[plugin]-common`. The Paper module depends only on the API boundary; platform code stays behind the API.

## README

Every scaffolded plugin repo carries a README committed with the scaffold. Like the docs site, it is a consumer surface: describe what a player or operator does, never packages, handlers, or config keys (see `docs-maintenance`). Extract every command, permission, and feature from the actual plugin source — never invent behavior the plugin does not have.

Use this template, filling each section from the scaffold and the plugin source:

```markdown
# <Plugin Name>

<one-line description — the sentence from plugin.yml's description>

## Requirements

- Paper <pinned version> (api-version from plugin.yml)

## Install

1. `./gradlew build`
2. Copy `build/libs/<plugin-name>-<version>.jar` into `plugins/`
3. Restart the server

## Commands        # from plugin.yml commands — who can run it, what it does

| Command | Who | What it does |
|---|---|---|
| `/home` | operators only | teleport to your home |

## Permissions     # from plugin.yml permissions — what each node grants

| Permission | Grants |
|---|---|
| `example.home` | access to `/home` |

## Configuration   # from config.yml defaults — describe behaviour, not keys

- <setting> — what it changes, in plain words

## Troubleshooting

- <symptom> — <cause and fix, from observed behaviour>

## License

See [LICENSE](LICENSE).
```

Notes:

- **Badges — public repos only.** Add shields.io CI/build, license, release, and platform badges per `ci-release` "README badges"; omit them entirely for private or internal repositories. Do not duplicate the badge policy here.
- **Docs link** — when the repo has a Fumadocs site (see `docs-maintenance`), the README is the front door and links to it; the docs site holds the content.

Verify the README matches `plugin.yml` before committing: name, description, version, and API version must not drift from the scaffold.

## Agent guidance file

Every scaffolded plugin repo carries an `AGENTS.md` at the repository root, written for the agent harnesses that will work in the repo. State the repo's conventions explicitly — agents start each session cold. Keep it short and factual; it is not documentation for humans.

Cover at minimum:

- **What this repo is** — one line mirroring the README description.
- **Conventions agents must follow** — the pinned Gradle/quality gates (`./gradlew clean check` must pass), the CalVer versioning rule, the package/style rules, and any repo-specific layout (e.g. which module holds the plugin.yml).
- **Do-not-do list** — anything the plugin repo must never do: hand-formatting, unpinned versions, committing `run/` server state, mixing unrelated changes into a commit.
- **Verification commands** — the exact commands that prove a change is complete (`./gradlew clean check`, plus runtime/network verification where applicable).

Write it from the actual scaffold and convention decisions — never a generic placeholder. Update it when the repo's conventions change, so the file never drifts from reality. Example fragment:

```markdown
# AGENTS.md — ExamplePlugin

Paper plugin. Conventions: `./gradlew clean check` must pass before
completion; versions are CalVer from `ci-release`; Google Java style
enforced via Spotless/Checkstyle; one logical change per commit.
Never commit `run/` server state. To verify: `./gradlew clean check`.
```

## GitHub repository files

Every scaffold includes a basic GitHub community-file set under `.github/` and a root `AGENTS.md`. These are local repository files; making the GitHub repository a reusable template is optional and requires repository-admin access.

### Community files (`.github/`)

Create these files with the repo's own content, not placeholder text:

- `.github/ISSUE_TEMPLATE/bug_report.yml` — bug report form: environment (Minecraft/Paper version, plugin version), steps to reproduce, expected vs actual behavior, logs/relevant output.
- `.github/ISSUE_TEMPLATE/feature_request.yml` — feature request form: problem being solved, proposed behavior, acceptance criteria, alternatives considered.
- `.github/PULL_REQUEST_TEMPLATE.md` — change summary, what was tested (exact commands), and a checklist mirroring the repo's conventions (for example, `./gradlew clean check` passes and the change is one logical commit).
- `.github/CONTRIBUTING.md` — setup, build, test, and pull-request process; point to the root `AGENTS.md` for agent conventions.
- `.github/CODE_OF_CONDUCT.md` — a brief standard code of conduct, such as Contributor Covenant.

Keep the files aligned with the scaffold's actual commands and quality gates — a template that references commands the repo does not have teaches wrong conventions.

### Optional GitHub template-repository setting

Only when the user requests a reusable GitHub template, enable **Settings → General → Template repository** after the repository is pushed. This is a remote repository-admin setting, not part of local project scaffolding. Document that consumers should choose **Use this template** in the README or `CONTRIBUTING.md`.

### Verification

```bash
test -f AGENTS.md
test -f .github/ISSUE_TEMPLATE/bug_report.yml
test -f .github/ISSUE_TEMPLATE/feature_request.yml
test -f .github/PULL_REQUEST_TEMPLATE.md
test -f .github/CONTRIBUTING.md
test -f .github/CODE_OF_CONDUCT.md
git diff --check
```

The generated `AGENTS.md` must name the repo's exact verification command, and each template must reference real files and commands from the scaffold.

## Verify the setup

```bash
./gradlew clean check           # canonical quality gate: Spotless, Checkstyle, PMD, SpotBugs; FAILS on violations
./gradlew spotlessApply         # fixes formatting in place (local use only, never in CI)
./gradlew runServer             # downloads Paper 26.2, launches test server with the plugin jar
```

## Development network (optional)

**REQUIRED SUB-SKILL:** Use `development-network` when this plugin must run behind the local Velocity proxy, share a network with other plugin projects, or attach an already-running Paper server. That skill is the source of truth for ownership, lifecycle, ports, forwarding, and offline-mode preflight. Keep `runServer` for a standalone Paper process.

Wire the Gradle plugin through the existing composite build:

```kotlin
// settings.gradle.kts
includeBuild("/path/to/plugin-multiplexer/network")
// If this repository vendors the harness instead:
includeBuild("./development-network/network")

// build.gradle.kts — add to the existing plugins block
plugins {
    id("io.github.development-network")
}
```

For a shared network, start the harness once, then add managed or external backends with the shell workflows:

```bash
# Start the proxy, lobby, and initial managed backend.
BACKENDS='dev' ./development-network/bin/dev-network.sh

# Register another managed backend with its server directory.
BASE=/path/to/development-network \
  ./development-network/bin/register-backend.sh hero 30070 /path/to/project/run

# Join an already-running external Paper server without owning its lifecycle.
EXTERNAL_DIR=/path/to/project/run \
BASE=/path/to/development-network \
BACKENDS='dev hero' \
EXTERNAL_BACKENDS='hero' \
PORT_HERO=30070 \
./development-network/bin/dev-network.sh
```

For a one-project network, use the Gradle plugin tasks:

```bash
./gradlew runNetwork    # proxy + lobby + this plugin's managed backend
./gradlew networkTest   # read-only checks against a running network
./gradlew restartNetwork -PnetworkBackend=<name>
```

The current Gradle plugin exposes `runNetwork`, `networkTest`, and `restartNetwork`. Use the shell registration workflows when multiple plugin projects share a network. Before any network task or client connection, complete the `development-network` offline-mode preflight: verify the active Velocity proxy, lobby, and every backend use `online-mode=false`. Set that mode only for a proxy this workflow owns; for an existing or external proxy with unknown or online mode, stop and ask the user. Proxy and Paper settings are independent.

## Common mistakes (observed in baseline testing)

| Wrong | Right | Why |
|---|---|---|
| `paper-api:26.2-R0.1-SNAPSHOT` | `paper-api:26.2.build.+` | `-R0.1-SNAPSHOT` scheme is gone in 26.x; old coordinates fail resolution |
| run-paper `2.3.1` | `3.1.0` | stale major; 3.x targets current Minecraft |
| `com.github.sherter.google-java-format` | Spotless + `googleJavaFormat` | sherter plugin is abandoned |
| Gradle `8.x` wrapper | `9.7.1` | current stable |
| `com.example.*` package | `io.github.<name>.*` | Paper convention: reverse-domain |
| `version = "1.0.0"` | CalVer from `GITHUB_RUN_NUMBER` | Use the `ci-release` convention; versions must be unique and sortable |
| 4-space indent by hand | `./gradlew spotlessApply` | Google style is 2-space; enforce, don't hand-format |
| `api-version: 26.2` unquoted | `api-version: '26.2'` | YAML parses it as float |
| Local/custom Checkstyle rules drift from Google Checks | load the pinned `google_checks.xml` URL above | Spotless and Checkstyle must enforce the same documented Google Java Style contract |
| `id("checkstyle") version "…"` | `checkstyle { toolVersion = "13.11.0" }` | Checkstyle and PMD are Gradle built-in plugins; pin analyzer tools, not plugin versions |
| hardcoded `dependsOn("spotbugsTest")` | `dependsOn(tasks.withType<SpotBugsTask>())` | SpotBugs task names vary by source set; wire by task type so missing tasks are not referenced |
| `ignoreFailures = true` / `isIgnoreFailures = true` | leave failures enabled (default or explicit `false`) | report-only analyzers do not gate CI |
| analyzers configured but not attached to `check` | `tasks.named("check") { dependsOn(tasks.withType<…>()) }` | `./gradlew clean check` must fail when any analyzer fails |
| unpinned `main` URL for `google_checks.xml` | use the version-tagged `checkstyle-13.11.0` URL above | The rule set must change only with the pinned Checkstyle version |
| Putting `includeBuild` in `build.gradle.kts` | Put it in `settings.gradle.kts`, and apply the network plugin inside the existing `plugins` block | Composite build discovery happens in settings; a second plugins block is invalid |
| Using `runServer` for a backend in a shared network | Start the harness once with `dev-network.sh`, then use `register-backend.sh` for a managed server directory or the `EXTERNAL_BACKENDS`/`boot-external.sh` workflow for an already-running server; reserve `runNetwork` for one-project convenience | The task or script that owns the Paper process must be explicit |
| Starting or connecting before the development-network offline-mode preflight | Verify the active Velocity proxy, lobby, and every backend use `online-mode=false`; set an owned proxy offline, or stop and ask before changing or proceeding with an external proxy | Proxy and Paper settings are independent, and an unknown external proxy mode is not permission to proceed |

Reproducibility option: after first resolve, pin the exact paper-api build (e.g. `26.2.build.112-stable`) instead of `+`.

## Version bumps

On a Minecraft update, exactly three values change together: `paper-api` coordinate, `runServer.minecraftVersion`, `api-version`. Re-verify all three against docs.papermc.io before committing — never assume the new scheme matches the old one.
