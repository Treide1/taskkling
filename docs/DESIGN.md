# taskkling — design principles & visual language

This document is the canonical description of taskkling's visual language: a dark,
terminal-adjacent graph view. The Compose Desktop app (`:ui`) implements it natively; when
the app and this document disagree, this document is the contract — fix the app, or amend
the contract deliberately.

Scope: visual + interaction design of the graph UI. For *what* the UI does (pure CLI client,
mutation flow, discovery), see PRD §6.3 and §13.

---

## 1. Design principles

1. **The graph is the interface.** taskkling's one visualization is the dependency DAG. No
   dashboards, no chrome for its own sake — a canvas of task cards, the edges between them, and
   one detail panel. Everything else (header, legend) is thin framing around that canvas.
2. **Terminal-adjacent, not terminal-cosplay.** The audience lives in editors and shells: dark
   surface, monospace type, dense small text, muted chrome. But we use what a real UI affords —
   smooth curves, elevation, motion — where it carries meaning.
3. **State is color; color is state.** Each task renders in exactly one *primary state*, and the
   state palette is the only saturated color on screen (plus the single accent for selection).
   If something is vivid, it means something.
4. **Focus by dimming, not by zooming.** The highlighted card's **Star** — the card, its
   blockers, its dependents, the edges between them — stays at full prominence and *everything
   else dims*. Without a pin the selected card is highlighted; **pinning makes the focus
   sticky**: the pinned card stays highlighted until unpinned, while selection moves freely
   (DOMAIN_LANGUAGE §7). The graph never rearranges under the user.
5. **Density over whitespace.** A card shows id, title, and a full row of metadata tags in
   ~96px. Field labels are small uppercase captions. This is a power tool; respect the user's
   screen real estate.
6. **The UI renders, it doesn't reason.** All semantics come from `taskkling export` (stored +
   computed fields). The only derivation the UI performs is the primary-state precedence below —
   a pure presentation choice.
7. **Redundant encoding.** State is never carried by color alone: every card also shows a
   textual state pill, dropped titles are struck through, done/dropped titles are de-emphasized.
8. **CLI-editable is UI-editable.** Every stored, CLI-editable attribute value is editable in
   the UI, through the same CLI verbs — the UI renders and forwards, it never grows its own
   write path (PRD §13). Graph-shaped interactions are explicitly excluded: `link`/`unlink`
   (the `depends` edges) stay CLI-only; the UI never edits the graph. Stamps (`created`,
   `closed`), `id`, and `computed.*` are not editable anywhere. The concrete field↔verb
   mapping lives in §9 — the one place to update when the CLI attribute surface changes
   (e.g. the task-store-v2 overhaul).

## 2. Primary state & precedence

A task's *primary state* drives its accent border, state pill, and legend bucket. Precedence
(first match wins), implemented as the UI's `stateOf()`:

| # | condition | state |
|---|--------------------------|------------|
| 1 | `status == "done"` | `done` |
| 2 | `status == "dropped"` | `dropped` |
| 3 | `status == "waiting"` | `waiting` |
| 4 | `computed.blocked` | `blocked` |
| 5 | `computed.deferred` | `deferred` |
| 6 | `computed.ready` | `ready` |
| 7 | otherwise | `open` |

`overdue` is *not* a primary state — it surfaces as a red `due` tag on the card (and keeps its
own color token).

## 3. Color tokens

### Chrome (surfaces, lines, text)

| token | hex | use |
|-----------|-----------|-----------------------------------------------------|
| `bg` | `#0e1116` | app/canvas background |
| `panel` | `#161b22` | cards, header, detail panel, legend |
| `panel2` | `#1c232d` | nested surfaces: tags, count chips, flag chips |
| `line` | `#2a3340` | all 1px borders + resting edge strokes |
| `txt` | `#d6dee8` | primary text |
| `muted` | `#8b97a7` | secondary text: labels, tag text, legend |
| `faint` | `#5a6675` | tertiary text: ids, placeholders, `open` accent |
| `accent` | `#4da3ff` | selection ring, highlighted edges, reference links |
| `dot` | `#1a212b` | canvas grid dots |

### State palette

| state | hex | notes |
|------------|-----------|--------------------------------------------|
| `ready` | `#3fb950` | green — actionable now |
| `blocked` | `#f85149` | red — blocked by unmet tasks |
| `waiting` | `#d29922` | amber — waiting on an external requirement |
| `deferred` | `#58a6ff` | blue — snoozed |
| `done` | `#6e7681` | gray — completed |
| `dropped` | `#484f58` | darker gray — abandoned |
| `open` | `#5a6675` | = `faint`; open but not ready/blocked |
| `overdue` | `#ff7b72` | salmon — `due` tag text when overdue |

Text placed *on* a filled state pill is `bg` (`#0e1116`), bold — except the `dropped` pill,
whose fill is too dark: it uses light text (`#c9d1d9`).

## 4. Typography

- **Family: monospace everywhere.** Bundle **JetBrains Mono** (OFL-1.1) as the app font,
  falling back to `FontFamily.Monospace`. One family, no serif/sans anywhere.
- **Base: 13px / 1.5 line-height.** The whole UI reads at caption sizes.

| element | size | weight / treatment |
|---------------------------|--------|-------------------------------------------------|
| app title (header) | 14 | bold; suffix (`· graph`) regular + `faint` |
| detail panel title | 14 | bold |
| card title | 12.5 | regular; `muted` when done/dropped; strike-through when dropped |
| body text, field values | 13 | regular |
| card id / panel id | 11 | `faint` |
| field labels (panel) | 11 | UPPERCASE, `muted`, +0.5 letter-spacing |
| header "generated" note | 11 | `muted` |
| legend labels | 11 | `muted` |
| tags / pills / flags | 10 | pills bold, tags regular |

(px values map 1:1 to `dp`/`sp` in Compose — see §10.)

## 5. The canvas

- Background `bg` with a **dotted grid**: 1px-radius dots of `dot` color on a **26px** square
  grid, anchored at the canvas origin (dots at `(1,1) + n·26`). It gives the void a tactile
  texture and a sense of scale while staying far below content contrast.
- The canvas scrolls in both axes; the grid pattern is fixed to graph space (scrolls with
  content), not to the viewport.
- The canvas covers **at least the viewport**: when the laid-out graph is smaller than the
  window, the grid, click-to-clear, and pan surface still extend to the window edges (the
  content size is clamped to the viewport, §10).
- **Panning**: an LMB drag that starts on the background (not on a card) pans the canvas —
  pointer delta = scroll delta (1:1), no inertia, clamped to the same bounds as wheel
  scrolling. Cursor: grab (hand) over the background at rest, grabbing (move) while
  dragging. Drags that start on a card do nothing; card click/hover is untouched.
- Clicking empty canvas clears the selection — never the pin (a background drag past the
  ~3px slop is a pan, not a click).

## 6. Task cards

Geometry & resting look:

- Size: **210 wide**, **min-height 96**; grows with content.
- Surface `panel`, corner radius **7**, border **1px `line`** — except the **left edge: a 4px
  accent border** in the primary-state color. The accent edge is the card's loudest state cue.
- Padding: 8 vertical, 10 horizontal.
- Content, top-down: id row — id (11, `faint`) left, a fixed **20×20 pin slot** right
  (glyph 20, inside a **36dp click target** that overflows the slot) → title (12.5, `txt`,
  wraps) → wrapping tag row (gap 4).

Card states (composable, in addition to primary-state accent):

| state | treatment |
|------------|----------------------------------------------------------------------------|
| hover | lift: translate **y −1px** + shadow `0 4px 18px rgba(0,0,0,.5)`; outline pin (`muted`) reveals in the pin slot of unpinned cards |
| selected | ring: **2px `accent`** outline + deeper shadow `0 6px 22px rgba(0,0,0,.55)` |
| pinned | filled pin (`accent`) always visible in the pin slot; the card is permanently inside the highlighted Star |
| dimmed | whole card at **28% opacity** (when outside the highlighted Star). A selected card outside Star(pinned) keeps its ring *at* the dimmed alpha — ring stays, dim stays |
| done/dropped | title in `muted`; dropped additionally struck through |

**Pinning** (single pin, session-only — never persisted): clicking the outline pin pins the
card *and* selects it; pinning while another card is pinned transfers the pin. Clicking the
filled pin unpins and leaves the selection untouched. The pin slot is a fixed reservation so
the hover reveal never re-measures the card.

Cursor: pointer/hand over cards.

## 7. Edges

Edges point **from a blocker to the task it blocks** (upstream → downstream, left → right).

- **Shape: horizontal-tangent cubic Bézier (the "S-curve").** From the **right-center** of the
  blocker card `(x1, y1)` to the **left-center** of the dependent card `(x2, y2)`:

  ```
  end = x2 − arrow                      # the stroke stops at the arrowhead's BASE (arrow ≈ 8px)
  dx = max(40, (end − x1) · 0.5)
  path = M x1,y1  C (x1+dx),y1  (end−dx),y2  end,y2
  ```

  Both control points are horizontal, so edges leave and enter cards flat and swing through an
  S between rows. The `40` floor keeps short/backward edges visibly curved.
- **Arrowhead at the target end**: small solid triangle (≈8px), tip at `(x2, y2)`, oriented
  along the path tangent, same color as the stroke, drawn over the stroke's end so the joint
  is seamless (the stroke itself stops at the head's base).
- Resting: stroke `line`, width **1.6**. Highlighted (touches the highlighted card — the
  pinned card if any, else the selected one): stroke `accent`, width **2.4**, arrowhead
  `accent`.
- While a card is highlighted, non-highlighted edges drop to **20% opacity**.
- Edges render *under* cards and never capture pointer input.

## 8. Tags, pills, chips

Small rounded capsules, radius 10, padding ~1×7, size 10.

- **Tag (outline)**: `panel2` fill, 1px `line` border, `muted` text. Prefixes carry meaning:
  `#` thread, `⛓ n` blocked-by count, `⏳ date` defer, `⌛ text` external requirement.
- **Card tag order** (fixed): state pill, `#thread`, priority, `⛓ blocked-by`, due/overdue,
  `⏳ defer`, `⌛ external requirement`.
- **State pill (filled)**: primary-state color fill, no border, bold `bg`-colored text (see §3
  for the `dropped` exception).
- **Semantic tag colors** (outline variant, tinted text + darker tinted border):
  - due/overdue: text `overdue`, border `#5a2a28`
  - defer: text `deferred`, border `#284263`
  - waiting: text `waiting`, border `#5a4a1a`
  - `!high` priority: text `blocked` (red), border `#5a2a28`
  - `low` priority: text `faint`
- **Count chips (header)**: `panel2` fill, `line` border, radius 12 — an 8px state-colored dot,
  a bold count, a `muted` label.
- **Flag chips (detail panel)**: the five computed flags (`ready blocked deferred overdue
  resurfaced`) always render; inactive = outline tag look, active = filled pill in the matching
  state color (overdue/resurfaced use `waiting` amber).

## 9. Framing: header, detail panel, legend

- **Header** (top, `panel`, 1px `line` bottom border, padding 10×16): app title (`taskkling`
  + faint context suffix), a `muted` "generated …" timestamp from the export with a **refresh
  button** beside it, and count chips for ready / blocked / waiting / done pushed to the right.
  - **Refresh button**: a small rounded-rect (radius 6) sized to the 11px note row, overall
    muted — `muted`-tinted circular-arrow glyph, transparent at rest; on hover it quietly
    lifts (`panel2` fill, 1px `line` border, `txt` tint, hand cursor). Click re-runs `export`
    through the same busy gate as mutations (never concurrently with one); while a CLI call
    is in flight it renders disabled (0.4 alpha).
  - **Settings cogwheel**: the same quiet icon-button chrome, appended at the end of the
    count-chip row (the header's right edge). Click opens a quiet dropdown (`panel2`
    surface) of workspace actions — "Archive tasks…" and "Prune tasks…", each opening its
    dialog. Every settings action runs a CLI verb through the mutation path and the app
    refreshes from the returned export; the menu persists nothing.
- **Detail panel** (right, **320 wide**, `panel`, 1px `line` left border, padding 16):
  - Empty state: centered `muted` hint ("Select a task to inspect…").
  - Selected: title → id → labeled fields (status, thread, priority, external requirement, due,
    defer, created, closed) → computed flag chips → reference lists (blocked by / blocker of).
    "blocked by" lists **every** upstream task; ids whose task is already `done` render
    *resolved* — `muted` + struck through, still clickable — while unmet ones stay `accent`.
    "blocker of" lists the downstream dependents. (UI labels are blocker-vocabulary
    translations of the contract's `depends`/`blockers`/`dependents` — DOMAIN_LANGUAGE §7.)
  - Absent values render as `faint` "—" rather than disappearing, so the panel shape is stable.
  - **Header id — click to copy**: the id in the header row reads `faint`, but sharpens to
    `txt` with a hand cursor on hover and copies the bare id (e.g. `t-60pe`) to the system
    clipboard on click — the fast path for handing an id to a dispatched agent. A brief
    `accent` "copied" hint trails the id and clears after ~1.2s, without shifting the row.
  - **Reference ids are links**: `accent` colored, click navigates the selection to that task
    AND pans the canvas to centre its card (150ms, clamped to the scroll bounds). Plain card
    clicks on the canvas never pan.
  - **Pinned-card return control**: while a task is pinned and its content is not on the panel
    (another selection, or the empty state), a small rounded-rect — filled pin (`accent`) +
    "→" — offers the way back. It sits in the panel's flow rather than floating: on a selection
    it trails the **id header row** (right-aligned beside the id); in the empty state it sits
    under the hint. Quiet chrome like the panel's other controls — transparent at rest, a
    `panel2` fill + `line` border lift on hover — so it can never occlude the task title.
    Clicking it re-selects the pinned task and pans its card back into view (the same navigate
    as reference links).
  - **Direct field editing** (principle 8): stored fields are edited in place on the panel;
    there are no separate mutation buttons (the former done/drop/reopen row is retired).
    - **Enum fields** open a value dropdown on click: the closed state renders like a plain
      field value with a hover affordance (hand cursor, subtle chevron); the open menu is a
      quiet outline surface (`panel2` fill, `line` border) with one `txt` item per value and
      the current value marked. Picking the current value is a no-op (no subprocess).
    - **Free-text fields**: clicking the value (or the faint "—" placeholder) swaps in an
      inline editor prefilled with the current value, with a row-trailing quiet **Save**
      outline button. Save runs the mapped verb; Escape cancels; changing the selection
      discards an open editor. Datetime validation is the CLI's — a rejected value surfaces
      the CLI error and keeps the editor open. An emptied clearable field clears the value.
    - Field↔verb mapping (the UI invents no verbs; this table is the update point when the
      CLI attribute surface changes):

      | field | edit | CLI |
      |----------------------|--------------------------------------|-----------------------------------------|
      | status | dropdown open/done/dropped/waiting | `reopen` / `done` / `drop` / `wait` |
      | priority | dropdown low/normal/high | `set -p <value>` |
      | title | text; cannot be emptied | `set --title <text>` |
      | thread, due, defer | text; empty clears | `set --thread/--due/--defer` (`--clear`) |
      | external requirement | text | `wait <id> --on <text>` — sets `status=waiting` (the CLI's own coupling); cleared only by status transitions |
      | depends ("blocked by") | **not editable** (principle 8) | `link`/`unlink` stay CLI-only |
      | id, created, closed, computed | not editable | — |

    - While a mutation is in flight every editing affordance renders disabled (0.4 alpha, no
      hand cursor); the UI never blocks on the CLI subprocess and refreshes from its returned
      export.
  - **Selectable text**: panel text is selectable and copyable. Interactive islands (dropdowns,
    inline editors, and the reference links' click-to-navigate) opt out of selection where the
    two gestures fight.
- **Settings dialogs** (archive / prune): a full-app scrim (black at 45%, click-away
  dismisses) with a centred 300-wide `panel` card, radius 7, padding 16. The card's 1px
  border, bold title, and confirm button carry the dialog's **alert accent**: archiving
  is `waiting` amber (noticeable, not alarming); pruning is `blocked` red (destructive).
  Body copy is `muted` 12 and states the consequence. One drawn-checkbox row per closed
  type (`done`, `dropped`, both pre-checked) with its live count from the current export;
  the checked fill uses the dialog accent. Buttons right-aligned: quiet `cancel` outline +
  an accent-bordered confirm, disabled while a CLI call is in flight or when the selection
  matches zero tasks. Confirm runs the mapped CLI verb(s) through the mutation path —
  archive: `cleanup` (`--only <type>` when narrowed); prune: the `delete` verb per matching
  task — and the app refreshes from the returned export.
- **Legend** (bottom, `panel`, 1px `line` top border, padding 8×16): one 12px rounded swatch +
  `muted` label per state.

## 10. Layout metrics (graph)

Layered layout (`:ui` `Layout.kt`): columns = dependency layers, order within a layer =
intra-column stacking order.

| constant | value |
|----------------|-------|
| card width | 210 |
| card min-height| 96 |
| column gap | 110 |
| row gap | 24 |
| canvas padding | 28 |

Vertical placement is **per-column stacking by measured height**: within a column, cards are
laid one after another top-to-bottom — the first at the canvas padding, each next one a
constant *row gap* below the previous card's **measured** bottom. A card taller than the
min-height (long title, many tags) simply pushes the ones below it down; it neither collides
nor leaves a fixed-grid gap. Columns are measured independently, so **cross-column row
alignment is deliberately dropped** — column k's card *i* need not line up with column j's
card *i*. Canvas size follows the content: width `2·pad + cols·card + (cols−1)·col_gap`
(unchanged); height `2·pad + max over columns of (Σ measured heights + (n−1)·row_gap)`;
each dimension clamped to **at least the viewport** so the canvas never falls short of the
window (§5).

Edge anchors use each card's **measured vertical center**: an edge runs from the source
card's right edge at its measured centerY to the target card's left edge at its measured
centerY (§7). Cards and edges are derived from the *same* measure pass, so the two never
disagree by a frame.

## 11. Motion

Motion is quick and functional — acknowledge, never entertain:

| what | duration |
|--------------------------------------|-----------|
| card hover lift + shadow | ~100–150ms |
| edge stroke/opacity, card dim/undim | ~150ms |

No entrance animations, no layout animation, nothing longer than 200ms.

## 12. Compose mapping notes

- **Units**: treat this document's px values as `dp` (text: `sp`) 1:1.
- **Theme**: define the §3 tokens once (e.g. an immutable `TaskklingTheme` object / CompositionLocal).
  Don't scatter hex literals through composables, and don't fight Material defaults — the
  Material theme is at most a carrier for these colors.
- **Dotted grid**: `Modifier.drawBehind` on the graph surface — `drawCircle(dot, 1.dp)` per grid
  point; grid is in content coordinates.
- **Edges**: one `Canvas`/`drawBehind` under the node layer; `Path().cubicTo(...)` per §7, with
  the arrow triangle rotated to the end tangent. `drawPath(style = Stroke(width, cap = Round))`.
- **Hover**: `hoverable` / `onPointerEvent(Enter/Exit)` per card (Compose Desktop supports
  pointer hover); drive lift with `animateDpAsState`, shadow via `Modifier.shadow` or a drawn
  ambient shadow.
- **Dimming**: `Modifier.alpha(0.28f)` on cards, `animateFloatAsState` for the transition;
  edge opacity animates in the edge layer.
- **Font**: bundle JetBrains Mono in `ui` resources; fall back to `FontFamily.Monospace`.
- **Smoke path**: the `TASKKLING_SMOKE=1` headless path (export → layout → exit) must keep
  working unchanged — it's the display-free verification hook.

## 13. Out of scope (for now)

- **Light theme.** The language is dark-first; a light variant is a deliberate later project,
  not a toggle to improvise.
- Zoom gestures, edge routing around cards, crossing minimisation — layout refinements
  tracked as tasks, not design debt. (Drag-to-pan shipped in §5.)
- Any UI-side derivation beyond §2 precedence.

## 14. Brand

The creature in the mark is **the taskkling** — the app's namesake and mascot:
two `done`-colored upstream nodes whose accent-colored edges converge — forming
a terminal `>` prompt — into one `ready`-colored node, awake with eyes. That
awake node *is* the taskkling. It draws §2's core derivation literally (*a task
is ready when all its deps are done*) in the §3 state palette. Branding leads
with this name: it is "the taskkling", never a codename.

- **Canonical sources**: `docs/assets/logo.svg` (128×128 tile) and
  `docs/assets/lockup.svg` (tile + wordmark). Everything else (ICO/ICNS/PNG,
  social preview) is generated from these with the pipeline in `design/logo/`
  (resvg-js renderer + fontTools wordmark outliner). The candidate exploration
  and its review record live in git history (`cb174a8..8e0cafa`), not the
  working tree.
- **Tile**: rounded square, `rx` 28/128, fill `bg`, 2px `line` border. The mark
  uses only §3 tokens: `done` deps, `accent` edges, `ready` node, `bg` eyes.
- **Wordmark**: JetBrains Mono Bold, outlined to paths from the bundled font;
  in the SVG lockup the double-k is set in `accent` (`tas`·`kk`·`ling`). The
  in-app header sets the name as plain `txt`-colored text — no accent split;
  brand presence in the header comes from the 18dp glyph.
- **Eyes are optional detail**: below ~32px they blur away and the mark must
  still read as deps→`>`→node. Never scale the eyes up to compensate.
- **Icon containers**: `ui/icons/taskkling.{ico,icns,png}` wired into
  `nativeDistributions`; `ui/src/main/resources/icons/` carries the window
  icon (PNG 256) and the header glyph (SVG).
