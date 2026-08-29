# Stack and styling (hub sites)

Applies to hub sites only. Content-only repos have no dependencies and no CSS.

## Pinned versions

Verified 2026-08-21 against `registry.npmjs.org`.

| Component | Version |
|---|---|
| `fumadocs-core` | **16.14.5** |
| `fumadocs-mdx` | **15.3.0** |
| `fumadocs-ui` | **`npm:@fumadocs/base-ui@16.14.5`** (alias) |
| `next` | **16.3.2** |
| `react` / `react-dom` | **19.2.x** |
| `tailwindcss` | **4.3.x** |

`fumadocs-ui` is installed as an npm alias to `@fumadocs/base-ui`, pinned to the
same version as `fumadocs-core`. Keep those two equal — they ship as a matched
pair, and the gate fails when they drift.

## Source wiring

Two layouts are both valid, and the gate accepts either:

- a standalone `source.config.ts` calling `defineDocs` (as in `site-docs`), or
- `defineDocs` imported from `fumadocs-mdx/macro` inside `lib/source.ts` (as in
  `alkahest-docs`), with no `source.config.ts` at all.

Either way `lib/source.ts` builds the loader from `fumadocs-core/source`, and
`app/docs/[[...slug]]/page.tsx` resolves pages through `source.getPage`.

## Styling contract

Every hub's stylesheet must begin with these three imports, in this order:

```css
@import 'tailwindcss';
@import 'fumadocs-ui/css/neutral.css';   /* or another Fumadocs color scheme */
@import 'fumadocs-ui/css/preset.css';
```

Beyond that, theming is CSS variables — never hardcoded values in components or
MDX pages. A hex in a page cannot respond to dark mode and drifts from brand.

Both hubs define their type scale and the Fumadocs layout knobs
(`--fd-sidebar-width`, `--fd-toc-width`, `--fd-header-height`) as custom
properties.

### Splitting tokens out

For anything past a type scale, split the layers as `site-docs` does:

- `tokens.css` — semantic light/dark tokens on `:root` / `.dark`, brand colors,
  and the `--color-fd-*` bridge that re-themes Fumadocs itself.
- `theme.css` — the Tailwind `@theme inline` bridge mapping those variables to
  utilities. No full color ramps; Tailwind v4 ships defaults.
- `global.css` — the three imports above, then `./tokens.css` and `./theme.css`,
  then base and prose rules.

A single-file `global.css` (as in `alkahest-docs`) is fine only while a repo has
no brand tokens of its own. Either way, brand color is defined once and consumed
everywhere else.
