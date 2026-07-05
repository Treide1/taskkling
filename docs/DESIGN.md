# taskkling — design principles & visual language

This document is the canonical description of taskkling's visual language: a dark,
terminal-adjacent graph view. The Compose Desktop app (`:ui`) implements it natively; when
the app and this document disagree, this document is the contract — fix the app, or amend
the contract deliberately. (Origin: extracted from the throwaway `spike/index.html`
reference implementation, since untracked.)

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
4. **Focus by dimming, not by zooming.** Selecting a node highlights its neighborhood (the node,
   its blockers, its dependents, the edges between them) and *dims everything else*. The graph
   never rearranges under the user.
5. **Density over whitespace.** A card shows id, title, and a full row of metadata tags in
   ~96px. Field labels are small uppercase captions. This is a power tool; respect the user's
   screen real estate.
6. **The UI renders, it doesn't reason.** All semantics come from `taskkling export` (stored +
   computed fields). The only derivation the UI performs is the primary-state precedence below —
   a pure presentation choice.
7. **Redundant encoding.** State is never carried by color alone: every card also shows a
   textual state pill, dropped titles are struck through, done/dropped titles are de-emphasized.

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
| `blocked` | `#f85149` | red — unmet dependencies |
| `waiting` | `#d29922` | amber — waiting on external |
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
- **Panning**: an LMB drag that starts on the background (not on a card) pans the canvas —
  pointer delta = scroll delta (1:1), no inertia, clamped to the same bounds as wheel
  scrolling. Cursor: grab (hand) over the background at rest, grabbing (move) while
  dragging. Drags that start on a card do nothing; card click/hover is untouched.
- Clicking empty canvas clears the selection (a background drag past the ~3px slop is a
  pan, not a click).

## 6. Task cards (nodes)

Geometry & resting look:

- Size: **210 wide**, **min-height 96**; grows with content.
- Surface `panel`, corner radius **7**, border **1px `line`** — except the **left edge: a 4px
  accent border** in the primary-state color. The accent edge is the card's loudest state cue.
- Padding: 8 vertical, 10 horizontal.
- Content, top-down: id (11, `faint`) → title (12.5, `txt`, wraps) → wrapping tag row (gap 4).

Card states (composable, in addition to primary-state accent):

| state | treatment |
|------------|----------------------------------------------------------------------------|
| hover | lift: translate **y −1px** + shadow `0 4px 18px rgba(0,0,0,.5)` |
| selected | ring: **2px `accent`** outline + deeper shadow `0 6px 22px rgba(0,0,0,.55)` |
| dimmed | whole card at **28% opacity** (when a selection excludes it) |
| done/dropped | title in `muted`; dropped additionally struck through |

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
- Resting: stroke `line`, width **1.6**. Highlighted (touches the selection): stroke `accent`,
  width **2.4**, arrowhead `accent`.
- When a selection is active, non-highlighted edges drop to **20% opacity**.
- Edges render *under* cards and never capture pointer input.

## 8. Tags, pills, chips

Small rounded capsules, radius 10, padding ~1×7, size 10.

- **Tag (outline)**: `panel2` fill, 1px `line` border, `muted` text. Prefixes carry meaning:
  `#` thread, `⛓ n` dependency count, `⏳ date` defer, `⌛ text` waiting-on.
- **Card tag order** (fixed): state pill, `#thread`, priority, `⛓ deps`, due/overdue,
  `⏳ defer`, `⌛ waiting-on`.
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
  + faint context suffix), a `muted` "generated …" timestamp from the export, and count chips
  for ready / blocked / waiting / done pushed to the right.
- **Detail panel** (right, **320 wide**, `panel`, 1px `line` left border, padding 16):
  - Empty state: centered `muted` hint ("Select a node to inspect…").
  - Selected: title → id → labeled fields (status, thread, priority, waiting on, due, defer,
    created, closed) → computed flag chips → reference lists (depends / blockers / dependents).
  - Absent values render as `faint` "—" rather than disappearing, so the panel shape is stable.
  - **Reference ids are links**: `accent` colored, click navigates the selection to that task.
  - Mutation actions (done / drop / reopen) live here; style them as quiet outline buttons
    (`panel2` fill, `line` border, `txt` label) — utilitarian, not primary-colored. While a
    mutation is in flight they render disabled (0.4 alpha, no hand cursor); the UI never
    blocks on the CLI subprocess and refreshes from its returned export.
- **Legend** (bottom, `panel`, 1px `line` top border, padding 8×16): one 12px rounded swatch +
  `muted` label per state, plus the reading hint "→ blocker points to blocked task" pushed
  right.

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
(unchanged); height `2·pad + max over columns of (Σ measured heights + (n−1)·row_gap)`.

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
