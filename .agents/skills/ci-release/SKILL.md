---
name: ci-release
description: Use when setting up or editing GitHub Actions CI, deploying a static site or docs to GitHub Pages, choosing project versioning, configuring CalVer releases or nightly builds, publishing release artifacts, or adding README shields.io badges. Triggers include CI workflow files, GitHub Actions schedules, rolling nightly releases, CalVer, release tags, build-status badges, license badges, platform badges, GitHub Pages, deploy-pages, and configure-pages.
---

# CI and Release Engineering

Required conventions for projects maintained by these skills:

- Project versions use CalVer: **`YYYY.MM.DD.<github_run_number>`** in GitHub Actions.
- Every project has a scheduled nightly release published as a rolling `nightly` pre-release.
- Public-project READMEs use shields.io badges; private or internal projects do not.

Pins verified 2026-08-21 against the official action repositories and Shields.io documentation.

## Action pins

| Component | Version |
|---|---|
| `actions/checkout` | **v7** |
| `actions/setup-java` | **v5** |
| `gradle/actions/setup-gradle` | **v6** |
| Java toolchain | **25** |

Use the current major pins above. Re-verify them against the upstream release pages before changing them.

## CalVer

The canonical CI version is date plus the GitHub Actions run number:

```text
2026.08.21.142
YYYY.MM.DD.<github_run_number>
```

Use this Gradle Kotlin DSL version provider (the `project-setup` skill includes the same source-of-truth snippet):

```kotlin
val calverDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd"))

version = providers.gradleProperty("buildVersion")
    .orElse(
        providers.environmentVariable("GITHUB_RUN_NUMBER")
            .map { "$calverDate.$it" }
    )
    .orElse("$calverDate-SNAPSHOT")
    .get()
```

`buildVersion` is an explicit release override. Normal CI derives the run number from `GITHUB_RUN_NUMBER`; local builds use a dated `-SNAPSHOT` fallback. Stable release tags MUST equal the full built version, not a shortened date.

If a generated artifact contains a manifest or plugin descriptor with its own version, expand that value from Gradle's `version`; never maintain a second hardcoded version.

## Build workflow

Create `.github/workflows/ci.yml`:

```yaml
name: Build

on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew clean check
```

`check` is the canonical quality gate. It includes Spotless, Checkstyle, PMD, and SpotBugs configured by the `project-setup` skill. CI MUST invoke `./gradlew clean check`; a build that compiles but bypasses that gate is not a passing CI contract.

## Rolling nightly release

Create `.github/workflows/nightly.yml`:

```yaml
name: Nightly release

on:
  schedule:
    - cron: '0 4 * * *' # 04:00 UTC every day
  workflow_dispatch:

permissions:
  contents: write

jobs:
  nightly:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@v6
      - run: ./gradlew clean check
      - run: ./gradlew assemble
      - name: Replace rolling nightly release
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release delete nightly --cleanup-tag --yes || true
          gh release create nightly build/libs/*.jar \
            --prerelease \
            --title "Nightly $(date -u +%F)" \
            --notes "Automated nightly build from ${GITHUB_SHA}."
```

The `nightly` tag MUST be replaced, not reused at an old commit. The release MUST remain a pre-release. `workflow_dispatch` makes the same path manually testable without waiting for the cron schedule.

## Stable release

Stable releases use the exact CalVer version built by the workflow. A release workflow may be manual or tag-driven; the build override avoids a version mismatch at the date boundary:

```yaml
- name: Build release
  run: |
    VERSION="$(date -u +%Y.%m.%d).${GITHUB_RUN_NUMBER}"
    ./gradlew -PbuildVersion="$VERSION" clean check
    ./gradlew -PbuildVersion="$VERSION" assemble
    echo "VERSION=$VERSION" >> "$GITHUB_ENV"

- name: Publish release
  env:
    GH_TOKEN: ${{ github.token }}
  run: gh release create "$VERSION" build/libs/*.jar --title "$VERSION" --generate-notes
```

Stable releases are not marked `--prerelease`; their tag is the full `YYYY.MM.DD.<github_run_number>` value. The shell format uses UTC and `date -u +%Y.%m.%d`, matching the Gradle formatter above.

## README badges

Add shields.io badges **only when the repository is public**. Do not add them to private or internal repositories.

For a public project, include:

- CI/build status.
- License.
- Current release when the project publishes releases.
- Platform or target runtime when applicable. For a binary, show supported OS/architecture targets; for a Paper plugin, show the supported Paper/Minecraft version.

Replace `OWNER`, `REPO`, and workflow filenames with the project's values:

```markdown
[![Build](https://img.shields.io/github/actions/workflow/status/OWNER/REPO/ci.yml?branch=main&label=build)](https://github.com/OWNER/REPO/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/OWNER/REPO)](LICENSE)
[![Release](https://img.shields.io/github/v/release/OWNER/REPO)](https://github.com/OWNER/REPO/releases/latest)
![Platform](https://img.shields.io/badge/Paper-26.2-blue)
```

The workflow-status badge path uses the workflow filename (`ci.yml`), not the workflow's display name. Omit the platform badge when the project has no meaningful runtime or binary target.

## Verify

```bash
./gradlew clean check
```

For a public repository, manually dispatch `nightly.yml` once after adding it. Confirm that the build succeeds, the release is marked pre-release, the `nightly` tag points at the new commit, and the README badges resolve.


## GitHub Pages

Deploy a static site from a repository with GitHub Pages. Pages pins verified 2026-08-25 against the official `actions/starter-workflows` Pages templates and GitHub Docs. Use these pins; the raw starter template and the Docs page can drift apart, so record the source and date when you change them.

### Site types

- **User or organization site**: repository named `<owner>.github.io`; served from `https://<owner>.github.io/` (root). One per account.
- **Project site**: any other repository; served from `https://<owner>.github.io/<repository>/`. One per repository.

The site type decides the base path. A project site needs the framework configured with the repository name as base (e.g. Vite `base: "/<repository>/"`), or asset URLs resolve against the domain root and 404.

### Publishing source

Enable Pages in **Settings → Pages → Build and deployment → Source**.

- **Deploy from a branch**: pick a branch and folder (`/` root or `/docs`). Use this when no build control is needed. Commits pushed by a workflow using `GITHUB_TOKEN` do not trigger a branch-based Pages build.
- **GitHub Actions**: use a workflow when a build step is needed. GitHub Pages links to the workflow run that most recently deployed the site.

### Deploy workflow

For a site that is already built, create `.github/workflows/deploy-pages.yml`:

```yaml
name: Deploy static content to Pages

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: "pages"
  cancel-in-progress: false

jobs:
  deploy:
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4
      - name: Setup Pages
        uses: actions/configure-pages@v5
      - name: Upload artifact
        uses: actions/upload-pages-artifact@v3
        with:
          path: '.'
      - name: Deploy to GitHub Pages
        id: deployment
        uses: actions/deploy-pages@v5
```

`cancel-in-progress: false` lets an in-progress production deployment finish and skips runs queued between it and the latest; do not set it to `true` for Pages.

### Build and deploy

When the site needs a build step, build into `dist/` in one job and deploy in a second job that `needs` the build:

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: npm
      - run: npm ci
      - run: npm run build
      - uses: actions/configure-pages@v5
      - uses: actions/upload-pages-artifact@v3
        with:
          path: './dist'

  deploy:
    needs: build
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    runs-on: ubuntu-latest
    steps:
      - name: Deploy to GitHub Pages
        id: deployment
        uses: actions/deploy-pages@v5
```

The uploaded artifact must contain `index.html` at its top level (`dist/index.html`), or the site 404s.

### Pull-request validation

Build on `pull_request` without Pages permissions; deploy only on the default branch. A PR workflow must not receive `pages: write` or `id-token: write`.

### Custom domain

A `CNAME` file does **not** automatically add or remove a custom domain. Configure the domain in **Settings → Pages**, or via the Pages REST API. Verify the domain first to avoid takeover attacks.

- Subdomain (`www.example.com`, `blog.example.com`): `CNAME` record to your Pages hostname.
- Apex domain (`example.com`): `A`, `ALIAS`, or `ANAME` record.
- Prefer `www`; GitHub automatically attempts redirects between `www` and apex when both are configured.
- Enable **Enforce HTTPS** when available.
- If the site is disabled while a custom domain is configured, the domain is at risk of takeover — update or remove the DNS records.

### Privacy

Pages sites are publicly available on the internet even when the repository is private, **if your plan or organization allows it**. On Enterprise plans a site can be published privately. Never deploy secrets, keys, or unpublished data to the artifact.

### Fumadocs sites

For a Fumadocs/Next docs site, follow the [docs-maintenance](../docs-maintenance/SKILL.md) skill for content structure, the hub app, and the build gate. This section provides the Pages deploy workflow that ships the hub's build output; the two skills compose: `docs-maintenance` owns the site, `ci-release` owns the deployment.


## Common mistakes

| Wrong | Right | Why |
|---|---|---|
| `1.0.0` or another SemVer-only project version | `YYYY.MM.DD.<github_run_number>` | CalVer is the repository release convention and run numbers make same-day builds unique |
| `LocalDate.now().toString()` for the version date | UTC date formatted as `yyyy.MM.dd` | ISO hyphens violate the required dotted CalVer format |
| Dated nightly releases with an unbounded release list | Replace the rolling `nightly` pre-release | Consumers get one stable download target and the release list stays usable |
| Reusing the old `nightly` tag without moving it | Delete the release/tag, then recreate it | A reused tag can leave users downloading an old commit |
| Missing `contents: write` on the nightly job | Grant write permission only to the release workflow | GitHub refuses to create or replace releases without it |
| `github/workflow/status/...` badge URL | `github/actions/workflow/status/.../<workflow>.yml` | Shields.io uses the workflow-file endpoint |
| Badges in a private repository | No shields.io badges for private/internal projects | Public badge endpoints cannot reliably resolve private metadata |
| Hardcoded versions in both Gradle and a descriptor | Expand the descriptor from Gradle `version` | Two version sources drift |
| CI runs a task that bypasses `check` | Run `./gradlew clean check` | The canonical gate explicitly runs every analyzer wired into `check`; Gradle's standard `build` lifecycle also depends on `check` |
| CI runs `./gradlew spotlessCheck` alone | Run `./gradlew clean check` | Formatting passes while Checkstyle, PMD, or SpotBugs violations remain |
| Publishing immediately after `clean check` | run `./gradlew assemble` after the gate, then publish | `check` validates code but does not create `build/libs/*.jar`; assembly must happen before release creation |
| `cancel-in-progress: true` on a Pages workflow | `cancel-in-progress: false` | Cancelling an in-progress production deployment can leave the site half-published |
| Uploading a folder without `index.html` at its top level | Ensure `dist/index.html` exists before upload | The site 404s when the artifact root has no entry file |
| Relying on a `CNAME` file to set a custom domain | Configure the domain in Settings → Pages or via the API | A `CNAME` file alone does not add or remove a custom domain |
| Giving a pull-request workflow `pages: write` | Build on PRs without Pages permissions; deploy only on the default branch | PRs can deploy unreviewed content |
| Deploying secrets into a Pages artifact | Keep keys and credentials out of the artifact | Pages sites are publicly reachable even for private repos when the plan allows it |
