---
name: docs-maintenance
description: Use when adding or maintaining end-user documentation for a repository, creating or updating a Fumadocs docs site, writing consumer or feature docs, structuring a page from basics to advanced, styling a docs site with design tokens, wiring meta.json sidebars, checking docs links, or keeping docs from drifting after a feature changes. Triggers include Fumadocs, content/docs, MDX pages, meta.json sidebars, player guides, feature docs, docs theming, and docs verification.
---

# Docs Maintenance (Fumadocs)

Every repository carries end-user documentation. Docs are a **consumer surface**, not an engineering artifact: they describe what a person sees, does, and decides — never packages, handlers, or config keys.

Core principle: **every page climbs from floor to ceiling — concept first, day-one basics next, advanced last, each level explicitly labelled.**

## Coverage vs. publishing

Two separate decisions. **Coverage is universal:** every repo owns `content/docs/` holding `index.mdx`, at least one feature page, and a `meta.json`. That is plain MDX — no Next app, no `node_modules`, no deploy target.

**Publishing is centralised:** a hub is a full Fumadocs Next app that renders content and ships it. Two exist — `site-docs` (product/platform) and `alkahest-docs` (server, whose `player-guides/` already aggregates the economy, territories, and jobs repos). Add a hub only when an audience needs its own site; otherwise an existing hub renders the repo's content. Never scaffold an app per repo: there are 162, and they cannot be held on matching Fumadocs versions.

**Declare the audience.** It selects vocabulary, not whether docs exist:

| Repo kind | End user | Floor | Ceiling |
|---|---|---|---|
| Gameplay plugin | Player | first command that works | operator- and plugin-driven machinery |
| Product/platform | Customer | create the first thing | billing, quotas, limits |
| Library, transpiler, internal tool | Developer consuming it | install and one working call | extension points, tuning |

A repo with no players still has consumers. Where there is no gameplay or customer surface — `x2x-*`, transpilers, build tooling — do not skip docs and do not invent a user. Document for **the next developer who has to depend on this**: same ladder, developer vocabulary, and `index.mdx` plus one `getting-started.mdx` is a pass, not a shortfall. State plainly what it will not do; for an internal tool that is often the most valuable section. With no external consumer at all, the audience is the maintainer six months from now and the ceiling is "how to change this safely."

## The floor-to-ceiling ladder

Five levels, in order. **Never open with a command** — a command is evidence of a concept, not the concept.

1. **The idea.** What the thing fundamentally is, in plain words, no commands. "The server runs an economy on a ledger — money is entries, not items."
2. **The basics.** What someone needs on day one, assuming no prior knowledge. Label it **basics**.
3. **Everyday.** The few commands or calls actually reached for daily, each inside a sentence saying when and why. Most readers stop here. Label it **Everyday**.
4. **Advanced.** Later topics, and machinery operators or other systems drive. Open with `<Callout type="info">Advanced — you do not need this to get started.</Callout>`.
5. **What it means for you.** Practical advice and the surprises worth warning about.

Explicit level labels are mandatory; advanced material must never hide inside the basics. Write prose, not fragments — commands belong inside sentences, and a command table is a cheat sheet after the explanation, never the explanation.

Translate implementation into observables: a permission node becomes "operators only"; a `0.5` growth factor becomes "crops grow at half speed in Winter"; package names and handlers are omitted entirely. Document only behaviour the source supports — leave gaps as gaps rather than inventing.

## Workflow

```text
content/docs/
  meta.json          # ordered: slugs, folder names, "---Section---" separators
  index.mdx          # landing page, <Cards> to the main pages
  <feature>.mdx
  <section>/
    meta.json        # children, "index" first
    index.mdx        # section landing page
```

1. Read the source for the behaviour you are documenting, and write only what it supports.
2. Copy `references/page-template.mdx` and fill in the five levels.
3. Add the page to its `meta.json` — an unlisted page never reaches the sidebar.
4. Link it with absolute docs paths (`/docs/player-guides/economy`), which is what makes links checkable.
5. Run the gate, then build if the repo is a hub.

Frontmatter needs `title` and `description`. **Quote any value containing a colon** — unquoted, the MDX YAML parse fails with "Nested mappings are not allowed".

## Verify

Copy `references/verify-docs.mjs` to `scripts/verify-docs.mjs` and wire it to `npm test`. It needs no dependencies and skips the hub-only assertions when there is no `app/`.

```bash
npm test                                        # or: node scripts/verify-docs.mjs
node scripts/verify-docs.mjs --ladder=player-guides
node verify-docs.mjs --root=../modular-economy  # check a repo from outside it
npm run build                                   # hub only
npm run types:check                             # hub only
```

The gate checks structure, navigation, links, and styling — never appearance, and never that MDX and JSX compile. A hub is not verified until `npm run build` passes, plus `npm run dev` when the layout changed.

**Docs drift is the default failure.** Prevent it structurally: a feature change and its docs change land in the same commit. A behaviour change with no docs diff is incomplete.

## References

- `references/page-template.mdx` — a complete page showing all five levels
- `references/stack-and-styling.md` — pinned versions (verified 2026-08-21), source wiring, CSS token contract
- `references/gate.md` — what the gate does and does not check, `--root`, ladder scoping

## Common mistakes

| Wrong | Right | Why |
|---|---|---|
| Page opens with a command table | open with the idea; commands at "Everyday" | a reference is not a guide; the reader does not know the feature yet |
| Advanced material mixed into basics | explicit `basics` / `Everyday` / `Advanced` labels | readers cannot tell what is optional |
| Skipping docs for a library or internal tool | reduced contract, developer audience | "no players" is not "no consumers" |
| `description: How Credits work: the ledger` | `description: "How Credits work: the ledger"` | an unquoted colon breaks the MDX YAML parse at build |
| `meta.json` and files out of sync | every page listed, every listed slug real | an unlisted page never appears in the sidebar; a dangling slug breaks navigation |
| Renaming a page without updating links to it | update every `/docs/*` reference | the old path 404s silently until someone clicks it |
| Treating a passing `npm test` as done | also run `npm run build` | the gate checks structure, not that MDX and JSX compile |
| Naming a permission node or config key | "operators only", or the behaviour it produces | an implementation detail is not an end-user fact |
| Fragment bullets ("Quick to mine.") | "Runebrick breaks fast, so early on it is a cheap building block" | fragments read as a spec, not a guide |
| Scaffolding a Next app per repo | content-only repos rendered by a hub | 162 apps cannot be held on matching versions |
| Hardcoding a hex color in a page | a CSS variable from the token layer | a hex cannot respond to dark mode and drifts from brand |
| Inventing behaviour the source does not state | leave the gap | fabricated docs are worse than missing docs |
