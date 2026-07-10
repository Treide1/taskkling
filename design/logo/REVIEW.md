# taskkling logo — candidate review

Three refined candidates for the app identity. All use only DESIGN §3 tokens
(`bg #0E1116`, `line #2A3340`, `accent #4DA3FF`, `ready #3FB950`, `done #6E7681`,
`muted #8B97A7`, `txt #D6DEE8`) on the standard dark app tile, and every lockup
sets the double-k of the wordmark in accent blue (JetBrains Mono Bold, outlined
to paths from the repo's own bundled font).

Review sheets (rendered): `review/lockups.png`, `review/context.png` (16px
titlebar + README hero per candidate), `review/tiles-v2.png` (512/64/16 renders).

## Candidates

### 1. `c1b-ready-dag` — the ready chevron
Two **done** (gray) upstream nodes converge into one **ready** (green) node; the
converging accent-blue edges deliberately form a terminal `>` prompt. This is the
product's core query drawn literally: *a task is ready when all its deps are done.*

- ✅ Most on-message; encodes the ready-set semantics AND the CLI-first identity in one mark
- ✅ Crisp at 64px and 16px (solid shapes, no outlines to smear)
- ⚠️ Composition is directional (left→right), slightly less iconic as a perfect square motif

### 2. `c3-taskling-sprite` — the taskling
A pixel kobold ("task-**ling**": a little task creature) built from the same
rounded grid squares as the UI's legend swatches, with ready-green eyes.
Invader-adjacent, terminal-native.

- ✅ Most memorable and ownable; gives the project a mascot and a story
- ✅ Distinct silhouette survives 16px (as a creature blob, not as detail)
- ⚠️ Least "serious"; detail mushes below 32px; weakest semantic tie to the DAG

### 3. `c4b-bracket-node` — the bracket node
A markdown checkbox `[ ]` holding a ready-green node: *one markdown file per
task, queryable state inside.* Monospace-native, ASCII-representable (`[▪]`).

- ✅ Best micro-legibility of all three; timeless, quiet, very hacker-utilitarian
- ✅ Doubles as a text glyph — the brand survives in pure-terminal contexts
- ⚠️ Most generic shape language (bracket + square could be many dev tools)

## Explored and cut

- `c2-kk-monogram` (double-k drawn as graph edges with node dots): the most
  name-specific idea, but the weakest 16px legibility of the field — and the
  lockups already celebrate the double-k in accent blue, so the idea survives
  in the wordmark without carrying the icon. Files kept in this directory.
- `c1c` (open/outlined upstream deps): prettier at 512px, smears at 16px; also
  semantically wrong — deps must be *done*, not open, for the target to be ready.

## Recommendation

**`c1b-ready-dag`.** It is the only mark that is both *about this product
specifically* (ready-set semantics in state colors) and *about its culture*
(the `>` prompt), and it performs at every size. `c4b` is the safe classic,
`c3` the characterful wildcard — either would work; c1b 
says the most with the least.

## On pick: Phase 2 application

README block (merge-friendly top-of-file), `Window(icon=…)` + header lockup in
the Compose UI, `.ico/.icns/.png` into `nativeDistributions`, 1280×640 social
preview in `docs/assets/`, brand section in `docs/DESIGN.md`.
