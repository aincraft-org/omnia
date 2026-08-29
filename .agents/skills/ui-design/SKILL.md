---
name: ui-design
description: Use when designing or building player-facing UI for a Paper plugin — inventory GUIs, chat messages, headers, item names and lore — choosing colors, contrast, fonts, layout, or a centralized theme file. Triggers include chest GUIs, MiniMessage, Adventure components, WCAG/contrast, item lore, GUI layout, readable text, UI polish, design tokens, and theme configuration.
---

# UI Design (Paper GUIs & Adventure Text)

Design player-facing UI — inventory GUIs, chat messages, headers, item names — that is readable, consistent, and accessible. Core principle: **UI is a consumer surface; readability and consistency beat decoration. WCAG AA contrast is the floor, not a goal. Keep visual tokens in one theme file instead of scattering colors and materials through handlers.**

## The text stack: Adventure + MiniMessage

Paper exposes the Adventure text stack in its API, and `MiniMessage` is available without adding a dependency. Do not pin a separate Adventure/MiniMessage dependency unless a non-Paper module genuinely needs one.

- Player-facing strings come from MiniMessage (config-driven, human-readable).
- Programmatic composition uses the `Component` API.
- Never emit legacy `§` codes.

## Theme file: `theme.yml`

`theme.yml` at the root of this skill is the canonical format reference and the safe starting defaults. The file is **optional** — no plugin is required to ship it, and it is not a runtime file or a required API. Agents MAY copy it verbatim or adapt it to the project's existing configuration conventions (YAML, JSON, TOML, or a code object). Adapting keeps the same semantic roles with the same meanings; do not introduce a second configuration hierarchy next to an existing one just to match this reference.

### Format contract

- `version`: integer schema version. Loaders MUST reject unsupported versions at startup.
- `tokens.<name>.color`: an `#RRGGBB` foreground color.
- `tokens.<name>.weight`: `regular` or `bold`.
- `materials.<name>`: a Bukkit `Material` enum name.
- Token names are lowercase `[a-z][a-z0-9_]*`. The nine roles below are the semantic contract: each meaning is fixed and MUST NOT be repurposed. An adopted `theme.yml` keeps the canonical names; a project MAY add roles beyond the nine. Only when adapting the format to an existing configuration (JSON, TOML, code object, or the project's own config) may key names differ — a renamed key keeps its role's meaning on every screen.

### Default token roles

The hex values are starting defaults only — preserve an existing project's palette instead of forcing them. The roles and their meanings are the contract.

| Token | Default color | Default weight | Use for |
|---|---|---|---|
| `title` | `#FFAA00` | bold | GUI/section headers, titles |
| `money` | `#FFAA00` | regular | currency, prices, numeric values |
| `body` | `#FFFFFF` | regular | default player-facing text |
| `muted` | `#AAAAAA` | regular | hints, secondary info, timestamps |
| `accent` | `#55FFFF` | regular | interactive emphasis, focus indicators |
| `success` | `#55FF55` | regular | successful outcomes |
| `warning` | `#FFFF55` | regular | warnings, caution |
| `danger` | `#FF5555` | regular | errors, destructive actions |
| `border` | `#AAAAAA` | regular | frame borders, dividers, separators |

### Default material roles

| Material key | Default | Use for |
|---|---|---|
| `filler` | `GRAY_STAINED_GLASS_PANE` | empty slots on every screen |
| `back` | `ARROW` | navigation slot 45 (54-slot GUI) |
| `close` | `BARRIER` | navigation slot 49 |
| `next` | `ARROW` | navigation slot 53 |

Use one filler material and one set of navigation materials so every screen uses the same visual language (see "Inventory GUI layout").

### Adopting the format

Use these steps when the project adopts the format:

1. Copy `theme.yml` into the project's chosen resource/config location (commonly `src/main/resources/theme.yml`). Keep `version` and the token/material roles; change colors, weights, and materials to match the project palette.
2. Load the theme **once during plugin startup**. Validate everything: the `version` is supported, every color parses as `#RRGGBB`, every weight is `regular` or `bold`, every material resolves to a Bukkit `Material`. Apply operator overrides using the project's existing startup/config policy, then validate again. Reject invalid or incomplete values and fail startup — never silently skip a bad entry.
3. Freeze the validated result into one immutable `UiTheme` object; pass that object to renderers. Renderers resolve semantic tokens at build time — `theme.token("title").color()` / `.weight()`, `theme.material("filler")` — and never read hex codes or materials anywhere else.
4. Keep player copy and MiniMessage templates in `messages.yml`, separate from the presentation tokens in `theme.yml`. A theme change updates colors, weights, and materials without editing renderers or copy; a copy change updates `messages.yml` without touching the theme.
5. Validate every configured color against the actual rendered surface: body tokens need 4.5:1, large text (≥18pt regular or ≥14pt bold) 3:1, and borders/icons/focus indicators 3:1. Report the token name and measured ratio for invalid values.

### Validation and lookup rules

Every bad input is an error at startup, named by key; nothing is silently skipped:

- Unsupported `version` → fail startup.
- Missing fixed role → fail startup. The nine tokens and four materials above are all required in an adopted file.
- Invalid value → fail startup, name the key: a `color` that does not parse as `#RRGGBB`, a `weight` other than `regular` or `bold`, a material that does not resolve to a Bukkit `Material`.
- Extra keys are allowed (a project MAY add tokens/materials), but they follow the same rules and must not reuse a fixed role's meaning under a new name.
- Runtime lookups: `theme.token(name)` / `theme.material(name)` throw when the name is absent from the validated theme — a renderer typo fails fast. Never fall back to defaults silently.

### Minimal load, validate, and resolve

The loader runs once in plugin startup, validates, and freezes the theme (Paper 1.21+ API, illustrative — the shape matters, not the exact calls):

```java
ConfigSection file = YamlConfiguration.loadConfiguration(
        new InputStreamReader(plugin.getResource("theme.yml"), StandardCharsets.UTF_8));
if (file.getInt("version") != 1) throw new IllegalStateException("theme.yml: unsupported version");

UiTheme.Builder b = UiTheme.builder();
for (TokenRole role : TokenRole.FIXED) {  // title, money, body, muted, accent, success, warning, danger, border
    String hex    = file.getString("tokens." + role.id() + ".color");
    String weight = file.getString("tokens." + role.id() + ".weight", "regular");
    if (hex == null || !hex.matches("^#[0-9a-fA-F]{6}$")) {
        throw new IllegalStateException("theme.yml: invalid color for " + role.id() + ": " + hex);
    }
    if (!weight.equals("regular") && !weight.equals("bold")) {
        throw new IllegalStateException("theme.yml: invalid weight for " + role.id() + ": " + weight);
    }
    b.token(role, TextColor.fromHexString(hex),
            weight.equals("bold") ? TextDecoration.State.TRUE : TextDecoration.State.FALSE);
}
for (MaterialRole role : MaterialRole.FIXED) {  // filler, back, close, next
    String name = file.getString("materials." + role.id());
    Material m = name == null ? null
            : Registry.MATERIAL.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
    if (m == null) throw new IllegalStateException("theme.yml: invalid material for " + role.id() + ": " + name);
    b.material(role, m);
}
UiTheme theme = b.build();  // immutable; pass to every renderer
```

Then build a Component from a resolved token:

```java
UiToken title = theme.token("title");
Component header = Component.text("Your Homes").style(
    style -> style
        .color(title.color())
        .decoration(TextDecoration.BOLD, title.weight() == Weight.BOLD
            ? TextDecoration.State.TRUE : TextDecoration.State.FALSE)
);
```

Never put raw theme values into untrusted MiniMessage input — renderers own the token-to-MiniMessage conversion.

### Not adopting the format

If a project does not adopt a separate theme file, its existing config or code format can provide the palette through one theme adapter/object — one immutable object exposing the same roles. There is no need for a second configuration hierarchy. The semantic-token consistency rule still applies: the same role keeps the same meaning across every screen, even when the project renames keys or uses a different file format.

MiniMessage essentials (syntax per the official Adventure MiniMessage docs). These examples show syntax only; production renderers use semantic tokens from the adopted theme representation instead of copying literal colors.

```text
<color:#ffaa00>Gold</color>           hex color
<gradient:#ff5555:#55ffff>Title</gradient>
<bold>Header</bold> <italic>…</italic> <underlined>…</underlined> <strikethrough>…</strikethrough>
<newline>                             line break
<click:run_command:/homes>…</click>   run_command | suggest_command | open_url | copy_to_clipboard
<hover:show_text:'<yellow>Tooltip'>…</hover>
```

## Color and contrast: WCAG AA floor

WCAG 2.2 SC 1.4.3 (Contrast, Minimum, Level AA): **4.5:1 for normal text, 3:1 for large text** (≥18pt regular or ≥14pt bold). SC 1.4.11 (Non-text Contrast): **3:1 for UI component boundaries** (buttons, borders, icons, focus indicators). SC 1.4.1 (Use of Color): **color must not be the only visual cue** — pair with text, icon, or shape. Exceptions: inactive components, purely decorative text, logos.

Minecraft's default text is small — treat **4.5:1 as the floor for all body text**.

The table below is **illustrative** — ratios are computed against an **assumed dark background (#0F0F0F)** using the WCAG relative-luminance formula. Rendered inventory and chat backgrounds vary by client, resource pack, and GUI type; always measure against each actual rendered surface you ship.

| Color | Hex | Contrast (vs #0F0F0F) | AA normal (4.5) | AA large (3.0) |
|---|---|---|---|---|
| white | #FFFFFF | 19.17 | ✓ | ✓ |
| gray | #AAAAAA | 8.25 | ✓ | ✓ |
| gold | #FFAA00 | 10.04 | ✓ | ✓ |
| yellow | #FFFF55 | 17.97 | ✓ | ✓ |
| red | #FF5555 | 6.10 | ✓ | ✓ |
| green | #55FF55 | 14.44 | ✓ | ✓ |
| dark_green | #00AA00 | 6.16 | ✓ | ✓ |
| aqua | #55FFFF | 15.64 | ✓ | ✓ |
| dark_aqua | #00AAAA | 6.69 | ✓ | ✓ |
| light_purple | #FF55FF | 7.30 | ✓ | ✓ |
| blue | #5555FF | 3.77 | ✗ | ✓ |
| dark_purple | #AA00AA | 3.00 | ✗ | ✓ (borderline) |
| dark_gray | #555555 | 2.57 | ✗ | ✗ |
| dark_red | #AA0000 | 2.47 | ✗ | ✗ |
| dark_blue | #0000AA | 1.44 | ✗ | ✗ |
| black | #000000 | 1.10 | ✗ | ✗ |

Rules for any theme:

- Safe defaults on dark backgrounds: white, gray, gold, yellow, red, green, dark_green, aqua, dark_aqua, light_purple.
- Never use dark_gray, dark_red, dark_blue, dark_purple, blue, or black for body text on dark backgrounds.
- blue (#5555FF) and dark_purple pass only at large/bold sizes — reserve them for headers.

## Typography and fonts

Minecraft renders text with a fixed bitmap font — **font size is not freely controllable**. Hierarchy comes from weight, color, and spacing, not point size:

- **Title/header**: `title` token (default gold or yellow), bold.
- **Body**: `body` token (default white), regular.
- **Secondary/muted**: `muted` token (default gray).
- **Success**: `success` token (default green). **Danger/error**: `danger` token (default red).
- **Money/numbers**: `money` token (default gold).

Typography rules:

- Bold for headers only; never bold body text.
- Gradients and rainbow are decoration — one per screen, titles only, never body.
- Custom font faces require a **client resource pack**; without one the client falls back to the default font. Test every custom font with the resource pack absent to confirm the fallback stays readable.
- Never rely on color alone to convey state — pair with text or an icon (WCAG 1.4.1).

## Inventory GUI layout

- Sizes are multiples of 9, 9–54 for chest-type GUIs.
- Standard anatomy: row 0 = header, rows 1–4 = content, row 5 = footer (navigation).
- Fill empty slots with one consistent filler material from the adopted theme/config (the reference default is gray stained glass).
- Navigation in fixed slots: back (45), close (49), next (53) on a 54-slot GUI.
- Keep titles short — title width is a client-side rendering constraint, not a server-enforced limit. (The legacy `String`-title 32-char limit does not apply to Adventure `Component` titles.)
- Identify your inventories with a custom `InventoryHolder`, not by title or lore; check `inventory.getHolder(false) instanceof MyHolder` in the click listener.
- Cancel all `InventoryClickEvent`s and route by slot.

## Verify

```bash
./gradlew build spotlessCheck
./gradlew runServer
```

- Theme (when adopted): load it once, validate configured semantic tokens/materials, and verify every foreground/background pair against each actual rendered surface.
- Smoke: open each GUI, screenshot, check headers, body, and hover text are readable and consistent.
- Custom fonts: test with and without the resource pack installed; the fallback must remain readable.

## Common mistakes

| Wrong | Right | Why |
|---|---|---|
| dark_red/dark_blue/dark_gray text on dark GUI | white/gray/gold/yellow/green/red/aqua/light_purple | fails 4.5:1 on dark backgrounds |
| Hex/weight/material repeated in handlers | semantic token or material from the adopted theme/config | one theme change stays consistent across every screen |
| Treating `theme.yml` as mandatory project configuration | adopt or adapt its format only when useful | the file is an implementation guide, not a required runtime contract |
| Per-screen token names for one role | one shared role name per meaning | semantic consistency is the contract |
| Invalid theme entries silently ignored | reject at startup, name the offending key | silent fallbacks defer config errors to runtime |
| Blue text for body | blue only for large/bold headers | #5555FF is 3.77:1 — passes large, fails normal |
| Color alone signals state | pair color with text/icon | WCAG 1.4.1; color-blind players miss it |
| Gradient on body text | gradient only on titles | decoration hurts readability |
| Legacy § codes | MiniMessage/Component | Paper's stack is Adventure |
| Parsing lore for slot identity | PDC keys / InventoryHolder | lore is display, not data |
| Identifying inventory by title | custom InventoryHolder | other plugins/players can collide on titles |
| Inconsistent nav slots | fixed back/close/next positions | players learn one layout |
| Random fillers per screen | one consistent gray glass | visual noise |
| Per-request listeners | one registered listener | leaks (see performance-optimization) |
| Assuming a title character limit | keep titles short, verify against client | title width is a rendering constraint |
| Custom font without fallback testing | test with the resource pack absent | clients without the pack must stay readable |