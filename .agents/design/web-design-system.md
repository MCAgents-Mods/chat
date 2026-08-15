---
name: web-design-system
description: Silver Glass — the visual language for every page MCAgentsClient serves in a browser, its tokens, components, and the rules that keep those pages working offline.
---

# Silver Glass — Web Design System

Every page this mod serves in a browser uses this system. That is the wallet bridge page,
the web console's knowledge and chat pages, and anything added later. A page that invents
its own palette is a defect, not a variation.

**Aesthetic:** white, silver, and transparent — light glassmorphism. Translucent panels,
hairline borders, soft shadows, and a fixed silver gradient backdrop. Calm, airy, neutral.

## The rule that outranks the rest here

**Self-contained, always.** No external font, script, stylesheet, CDN, or runtime network
call — ever. Everything ships inside the mod jar and is served from loopback.

This is not a preference imported from somewhere else; it is the offline-first mandate in
[`../rules/repository.md`](../rules/repository.md) applied to markup. A page that reaches
for a stylesheet on a CDN renders unstyled on the machine that needed it most: a player
with no internet, which is precisely the player this mod is built for. Inline small assets
as data URIs rather than fetching them.

## Principles

1. **Self-contained.** See above.
2. **Token-driven.** Every colour, space, radius, and shadow comes from a custom property.
   Never hard-code a hex value in a component.
3. **Layered CSS.** Root tokens → shared layout → shared components → per-section
   overrides. A page pulls exactly those layers plus its own.
4. **Progressive and accessible.** Semantic HTML first, native elements where they exist
   (`<details>` for an accordion), visible focus, sufficient contrast, usable down to about
   360px.
5. **Runtime-composed chrome.** Header and footer are shared partials injected client-side,
   so navigation lives in one file and every page stays in step.

## Tokens

Declared once on `:root`. Reuse these names verbatim.

### Palette — white and silver

| Token | Value | Use |
|---|---|---|
| `--white` | `#ffffff` | Pure surfaces, button text on accent |
| `--silver-050` | `#f6f8fb` | Lightest tint |
| `--silver-100` | `#eef1f6` | Background top |
| `--silver-200` | `#e2e7ee` | Background mid |
| `--silver-300` | `#d2d9e3` | Backdrop blobs |
| `--silver-400` | `#b9c2d0` | Muted fills |
| `--silver-500` | `#9aa6b8` | Bar starts, dividers |
| `--silver-600` | `#78859a` | Strong silver |

### Ink — text

| Token | Value | Use |
|---|---|---|
| `--ink-900` | `#1b2430` | Headings, strong text |
| `--ink-700` | `#333f4f` | Body text |
| `--ink-500` | `#5a6676` | Secondary and lead text |
| `--ink-400` | `#78859a` | Muted captions, eyebrows |

### Accent and intent

| Token | Value | Use |
|---|---|---|
| `--accent` | `#5b7189` | Cool steel accent |
| `--accent-strong` | `#3f5872` | Links, emphasis |
| `--accent-soft` | `rgba(91,113,137,.12)` | Accent chip and active backgrounds |
| `--sponsor` | `#d6336c` | Donation and call-to-heart accent |
| `--sponsor-soft` | `rgba(214,51,108,.10)` | Sponsor backgrounds |

Badges and callouts use success `#1f7a54`, warning `#9a6b16`; danger reuses `--sponsor`.

### Glass surfaces

| Token | Value | Use |
|---|---|---|
| `--glass` | `rgba(255,255,255,.62)` | Default panel and card fill |
| `--glass-strong` | `rgba(255,255,255,.90)` | Header and buttons — readable without blur |
| `--glass-faint` | `rgba(255,255,255,.40)` | Subtle inset blocks |
| `--glass-border` | `rgba(255,255,255,.75)` | Highlight border |
| `--hairline` | `rgba(120,133,154,.28)` | Divider and low-contrast border |
| `--surface-solid` | `rgba(255,255,255,.98)` | Near-opaque fallback for content-covering overlays |
| `--blur` | `saturate(160%) blur(14px)` | `backdrop-filter` for glass |

### Shadows, geometry, type

| Token | Value |
|---|---|
| `--shadow-sm` | `0 1px 2px rgba(27,36,48,.06), 0 2px 8px rgba(27,36,48,.05)` |
| `--shadow-md` | `0 6px 22px rgba(27,36,48,.10)` |
| `--shadow-lg` | `0 18px 48px rgba(27,36,48,.16)` |
| `--radius-sm` / `--radius` / `--radius-lg` | `10px` / `16px` / `22px` |
| `--maxw` | `1120px` |
| `--header-h` | `64px` |
| `--font` | system UI stack |
| `--mono` | system mono stack |

The page background is a fixed layered silver gradient — soft white highlights at the top
corners and a light silver wash toward the bottom, over
`linear-gradient(160deg, #eef1f6 → #dbe1ea)`, with `background-attachment: fixed` so
content scrolls over a stable field.

## The glass recipe

Every raised element:

```css
background: var(--glass);
-webkit-backdrop-filter: var(--blur);
backdrop-filter: var(--blur);
border: 1px solid var(--glass-border);
box-shadow: var(--shadow-sm);
border-radius: var(--radius);
```

Interactive cards lift on hover with `transform: translateY(-4px)` and `--shadow-lg`.
Transitions stay short, `.15s`–`.2s ease`.

### Overlays that cover content are never translucent

The recipe above is for surfaces sitting over the **fixed background**, not over other
content. Anything that fully covers page content — the mobile navigation panel, a dropdown,
a modal — uses an opaque background and must not depend on `backdrop-filter` for legibility:

```css
background: #ffffff;          /* or var(--surface-solid) */
-webkit-backdrop-filter: none;
backdrop-filter: none;
box-shadow: var(--shadow-md);
```

The reason is concrete: when such an overlay is nested inside an element that already has
`backdrop-filter` — a blurred sticky header, for instance — the child's background paint is
suppressed wherever `backdrop-filter` is unsupported or switched off, and the content behind
bleeds through. Blurring content you are deliberately hiding buys nothing anyway.

## Components

| Component | Class(es) | Notes |
|---|---|---|
| Hero banner | `.hero` | Large intro glass panel with a soft radial glow |
| Panel / section | `.panel`, `.section` | Grouped glass block |
| Card grid | `.card-grid` > `.card` | Auto-fill min 248px; `a.card` lifts on hover; inner `.card__icon/__title/__desc/__more` |
| Feature list | `.feature-list` | Stacked highlighted rows |
| Accordion | `details.acc` > `summary` + `.acc__body` | Native `<details>`, no JavaScript; `+`/`–` marker in CSS; group in `.accordion` |
| Code | `pre`, `code` | Dark `pre` (`#1c2431`), inline `code` tinted with accent; `.code-label` for a caption |
| Table | `.table-wrap` > `table` | Always wrap a table so it scrolls horizontally on its own |
| Badge / chip | `.badge` (`--accent`/`--ok`/`--warn`), `.chip`, `.chip-row` | Small status labels |
| Button | `.btn` (`--primary`/`--sponsor`), `.btn-row` | Pill buttons; primary and sponsor are gradient-filled |
| Callout | `.callout` (`--info`/`--warn`/`--danger`) | Icon and body aside |
| Definition list | `.deflist` > `.deflist__row` (`.deflist__term`) | Two-column term and description |
| Eyebrow / lead | `.eyebrow`, `.lead` | Section kicker and intro paragraph |
| Breadcrumbs | `.breadcrumbs` (`a`, `.sep`) | Page context trail |
| Dialog | `.scrim` > `.dialog` (`__title`/`__body`/`__field`/`__actions`) | Confirm and prompt; opaque per the overlay rule; **never `window.confirm` or `window.prompt`** |

Compose from these before writing new CSS. A per-section stylesheet is for genuinely
page-specific layout, not for restyling a component.

### Ask with the dialog, never with the browser

`window.confirm`, `window.prompt` and `window.alert` are not used anywhere in a page this
project serves. Three reasons, in the order they bite:

1. **The browser owns the wording and the button order.** "OK" is the same word for renaming
   a chat and for deleting one, so the control cannot say what it is about to do.
2. **They can be switched off.** Every major browser offers "prevent this page from creating
   more dialogs" after the second one; from then on `confirm()` returns false and `prompt()`
   returns null for the rest of the session. A destructive action guarded by a suppressed
   confirm is an action that has silently lost its guard.
3. **They cannot be styled, tested, or driven** the way the rest of the console can.

The dialog resolves rather than rejects when cancelled — cancelling is an ordinary answer,
not a failure — and focus lands on the text box, or on Cancel when there is none. Never on
the confirming button: the dialog opens under wherever the reader was already clicking.

## Layout and chrome

* **Container** — `.container`, max `--maxw`, centred. `.narrow` (880px) for reading pages.
* **Header** — sticky glass bar: `.site-header` > `.nav` with `.nav__brand`, `.nav__links`
  (`.nav__link`, active via `.is-active`), optional `.nav__dd` dropdown, mobile
  `.nav__toggle`. A `.nav__spacer` pushes the final link to the right.
* **Footer** — `.site-footer`, a brand column and link columns.
* **Responsive** — below 900px the nav collapses into a toggled panel; grids use
  `auto-fit`/`auto-fill minmax()`. The body never scrolls horizontally; wide content scrolls
  inside its own container.

## Runtime include system

Header and footer are injected at runtime so navigation lives in one place.

* **Partials** — `partials/header.html`, `partials/footer.html`. Internal links inside them
  use a `{{ROOT}}` token.
* **Loader** — `js/site.js` reads two globals the page sets in `<head>`:
  `window.SITE_ROOT` (relative path back to the root, `""`, `"../"`, `"../../"`) and
  `window.PAGE_SECTION` (for active-nav highlighting). It fetches the partials, replaces
  `{{ROOT}}`, wires the mobile menu and the active link, injects a favicon, and stamps the
  footer year.
* **Mount points** — `<div id="site-header"></div>` and `<div id="site-footer"></div>`.

It uses `fetch()`, so pages only work when served over HTTP. Opening one from the filesystem
shows unstyled chrome — that is expected, not a bug to route around.

## File organisation

```
css/
  main.css              :root tokens and base element styles
  shared/
    layout.css          header, nav, footer, breadcrumbs
    components.css      cards, accordions, tables, badges, buttons, callouts
  <section>/
    <section>.css       page-specific layout only
```

A page links them in order: `main.css` → `shared/layout.css` → `shared/components.css` →
`<section>/<section>.css`.

In this repository these live under `common/src/main/resources/mcagents/web/` and are
served from the jar. Adding a file there means it ships; the resource path is the URL path.

## Rebranding

Edit **tokens only** in `main.css` — palette, accent, radius, fonts — and every component
follows. To move off silver, adjust `--silver-*`, `--accent*`, and the body background
gradient, and leave the `--glass-*`, shadow, and geometry tokens alone so the feel survives.

## Accessibility checklist

* Semantic landmarks (`<header>`, `<nav>`, `<main>`, `<footer>`) and one `<h1>` per page.
* A visible `:focus-visible` outline.
* `aria-label` on nav and breadcrumb regions; decorative icons `aria-hidden`.
* Body ink on glass over the silver field meets WCAG AA. Do not put body text directly on
  `--glass-faint` without checking contrast.
* Every control reachable and operable by keyboard — the accordion is native `<details>`,
  the dropdown opens on `focus-within`, the mobile menu is a real `<button>`.
