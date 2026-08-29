# The docs gate

`verify-docs.mjs` is dependency-free Node. Copy it to `scripts/verify-docs.mjs`
and wire it to `npm test`. It auto-detects repo shape, skipping the hub-only
assertions (stack, wiring, routes, CSS) when there is no `app/` directory.

## Choosing the target

The gate resolves its target as its own parent directory — the repo root once
installed at `scripts/verify-docs.mjs`, which is why the installed form needs no
arguments. Run it from anywhere else and you must say which repo to check:

```bash
node scripts/verify-docs.mjs                     # installed: this repo
node verify-docs.mjs --root=../modular-economy   # another repo, run in place
DOCS_ROOT=../site-docs node verify-docs.mjs      # same, via environment
```

Without a target it looks for `content/docs` beside itself and fails saying so,
rather than silently checking the wrong tree.

## What it enforces, and what it cannot

| Area | Checked by the gate | Still manual |
|---|---|---|
| **Content** | frontmatter `title`/`description` on every page including nested ones; unquoted-colon values; `index.mdx` plus at least one page | voice, accuracy against source, ladder ordering |
| **Navigation** | every `meta.json` parses; every listed slug resolves to a file or folder; every page is reachable from a sidebar; section folders have their own `meta.json` | whether the order reads sensibly |
| **Links** | every internal `/docs/*` link and `href` resolves to a real page, including `<Cards>` and nested paths | external URLs — the gate makes no network calls |
| **Styling** | Tailwind and both Fumadocs CSS layers imported; every local `@import` target exists; no hardcoded hex in content | rendered appearance, dark mode, responsive layout |
| **Build** | — | `npm run build` is the only thing that proves MDX and JSX compile |
| **Ladder** | with `--ladder=<dir>`, guide pages carry basics/Everyday/Advanced labels | whether each level says the right thing |

Navigation is checked in both directions: a slug with no file is a dangling
sidebar entry, and a file listed nowhere renders at its URL but never appears in
the sidebar.

## Scoping the ladder check

`--ladder` takes an optional comma-separated list of directories:

```bash
node scripts/verify-docs.mjs --ladder=player-guides
```

Scope it to the directories that owe a ladder. Unscoped it also flags
contributor pages — `build-and-run.mdx`, `plugin-development.mdx` — which have no
player ladder to carry. Landing pages (`index.mdx`) are always exempt, since they
aggregate rather than teach.

## Replacing older scripts

The `verify-docs` scripts already in `site-docs` and `alkahest-docs` predate this
gate. They scan only the top level of `content/docs/`, so they miss nested
frontmatter, dangling slugs, and broken links. Replace them rather than running
both.
