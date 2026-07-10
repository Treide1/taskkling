# Prior art: file-based and git-native task/issue stores

Research for the task-structure redesign (branch `t3code/task-structure-redesign`).
Question: how do file-based / git-native issue and task trackers structure records,
links (dependency edges), and deletion -- and what transfers to a one-markdown-file-
per-task store with YAML frontmatter, a `depends` DAG, and git as the history mechanism
at 1k-10k task scale?

Note on location: the repo had no `docs/research/` convention before this document
(docs/ held DESIGN.md, DOMAIN_LANGUAGE.md, RELEASING.md, adr/). This directory was
created for research write-ups.

For each system, four axes are captured:
(a) where links/edges live -- per-record inline vs central ledger/index;
(b) how edge consistency is maintained -- on read, on write, or by construction;
(c) how archive/deletion works relative to version history;
(d) known scaling limits and performance techniques.

All claims are cited to primary sources (official docs, specs, source code).
Researched 2026-07-10.

---

## 1. Taskwarrior (2.x flat files -> 3.x TaskChampion SQLite)

### Record model

- Taskwarrior 2.x stored tasks as lines in flat files with the `.data` suffix
  (pending, completed, undo). Taskwarrior 3.x replaced this wholesale:
  "Taskwarrior 2.x stored tasks in files with the `.data` suffix. Taskwarrior 3.x
  stores tasks in `taskchampion.sqlite3`". Users must import old data; the legacy
  files are then deleted.
  Source: https://taskwarrior.org/docs/upgrade-3/
- A task is an unordered key/value map of string keys and string values, keyed by
  UUID: "Tasks are stored internally as a key/value map with string keys and values."
  All fields are optional. Unrecognized keys are user-defined attributes (UDAs).
  Sources: https://taskwarrior.org/docs/task/ ,
  https://gothenburgbitfactory.org/taskchampion/storage.html
- Every task always has a UUID and "are always addressable by UUID"; the small
  integer IDs users type are a volatile "working set" mapping (integer -> UUID)
  that is rebuilt ("gc") and explicitly "not synchronized with the server and does
  not affect any consistency guarantees."
  Sources: https://taskwarrior.org/docs/ids/ ,
  https://gothenburgbitfactory.org/taskchampion/storage.html

### (a) Where edges live

- Inline, on the dependent (downstream) task, one key per edge:
  "`dep_<uuid>` -- indicates this task depends on another task identified by
  `<uuid>`; the value is ignored." Tags are analogous: `tag_<tag>`.
  Source: https://taskwarrior.org/docs/task/ (section "Keys")
- The JSON export/interchange format historically flattened this into a single
  attribute: "The 'depends' field is a string containing a comma-separated unique
  set of UUIDs", with a note that "in a future version of this specification, this
  will be changed to a JSON array of strings, like the 'tags' field."
  Source: https://github.com/GothenburgBitFactory/taskwarrior/blob/develop/doc/devel/rfcs/task.md
- There is no reverse ("blocks"/dependents) field on disk; reverse relations are
  computed by reports at read time. Edges are single-entry, one direction only.

### (b) Edge consistency

- No stored invariant to keep consistent -- one side owns the edge; the other side
  is derived. Dependency state (blocked/blocking) is recomputed on every read.
- 3.x is operation-based underneath: every change is an operation
  (`Create(uuid)`, `Delete(uuid, oldTask)`, `Update(uuid, property, old, new, ts)`,
  `UndoPoint()`); operations are reversible ("Undoing Delete creates a task,
  including all of the properties in oldTask") and are the unit of sync.
  Source: https://gothenburgbitfactory.org/taskchampion/storage.html
- Sync makes replicas agree on "a single, linear sequence of operations", using
  operational transformation to linearize concurrent edits; the server stores a
  branch-free chain of versions per client.
  Sources: https://gothenburgbitfactory.org/taskchampion/sync-model.html ,
  https://gothenburgbitfactory.org/taskchampion/sync-protocol.html

### (c) Archive / deletion

- Completion and deletion are status changes, not file removals. In 2.x the task
  physically moved from pending.data to completed.data; in both generations the
  task keeps its UUID forever and only "is moved out of the working set, and loses
  the ID [the small integer]".
  Source: https://taskwarrior.org/docs/ids/
- So Taskwarrior's "archive" is move-based at the storage layer (out of the hot
  working set) but flag-based at the model layer (status=completed/deleted; the
  record survives).

### (d) Scaling

- The working set exists purely for performance and ergonomics: it keeps the hot
  set small so "ID numbers of pending tasks remain small". Disabling gc "slows
  down almost every command." Reports over completed data "run slower over time
  because there is more data to read."
  Sources: https://taskwarrior.org/docs/ids/ , https://taskwarrior.org/docs/report/
- The 3.x move to SQLite traded human-readable flat files (reparsed on every
  invocation) for an indexed store plus an operation log -- i.e. at the scale and
  invocation frequency of a CLI tool, full-file reparse eventually lost.
  Source: https://taskwarrior.org/docs/upgrade-3/

Lesson shape: stable opaque UUIDs everywhere + volatile human-friendly numbers as
a derived mapping; edges inline on one endpoint only; "archive" = move out of the
hot set while keeping the record addressable.

---

## 2. git-bug (git-native, operations as commits on refs)

### Record model

- An entity (bug, etc.) is "a series of edit `Operation`s", not a snapshot --
  explicitly modeled on operation-based CRDTs. State is "compiled" by replaying
  operations from the first one.
  Source: https://github.com/git-bug/git-bug/blob/trunk/doc/design/data-model.md
- Storage maps directly onto git's object model: operations are grouped into an
  `OperationPack` (one editing session), serialized as JSON in a blob; a tree
  references the pack at `/ops` (plus optional `/media`); a commit references the
  tree; commits chain per entity; the chain head is stored at
  `refs/<namespace>/<entity-id>` (e.g. `refs/bugs/<id>`), with remote-tracking
  under `refs/remotes/<remote>/<namespace>/<entity-id>`.
  Sources: doc/design/data-model.md and
  https://github.com/git-bug/git-bug/blob/trunk/doc/spec/dag-entity.md
- IDs are content hashes: an operation's ID is the hash of its JSON serialization
  (with "a random nonce to ensure we have enough entropy"); the entity ID is the
  hash of the first operation. Prefix matching is used for human input
  (7-char truncation displayed).
  Source: doc/design/data-model.md

### (a) Where edges live

- Everything -- status changes, labels, comments, and cross-entity references such
  as authorship ("an author (a reference to another entity)") -- lives inline in
  the entity's own operation chain. There is no central edge file; the only
  central structures are derived caches (below).

### (b) Edge consistency

- By construction plus validation on replay. Concurrent edits produce a commit
  DAG (single root, one head after merge); ordering is deterministic: Lamport
  logical clocks first ("you can't rely on the time provided by other people"),
  then lexicographic order of pack IDs for concurrent packs. Clock values are
  even encoded as tree entry names (e.g. `create-clock-14`) pointing at empty
  blobs. Merge commits must carry "zero operations in their `ops` blob", and the
  spec requires strictly increasing clocks per commit; violations are rejected
  when an entity is read/replayed.
  Sources: doc/design/data-model.md , doc/spec/dag-entity.md

### (c) Archive / deletion in an immutable history

- The spec has no deletion or redaction mechanism at all: operations are immutable,
  unknown operation types "must be retained verbatim", and metadata set via
  `SetMetadataOperation` is "immutable: a key set once cannot be overridden."
  Source: doc/spec/dag-entity.md
- The only "delete" is local: `git-bug bug rm` will "Remove an existing bug in the
  local repository" -- i.e. delete the local ref; it "will only remove the local
  copy of the bug" and cannot touch remotes.
  Source: https://github.com/git-bug/git-bug/blob/trunk/doc/md/git-bug_bug_rm.md
- So in a fully git-native store, deletion degenerates to un-publishing; there is
  no archive tier, only open/closed status inside the entity.

### (d) Scaling

- git-bug interposes a mandatory cache layer between entities and every consumer
  (CLI, GraphQL, web/term UI). It keeps loaded bugs in memory, and "maintain[s]
  in memory and on-disk a pre-digested excerpt for each bug, allowing for fast
  querying the whole set of bugs without having to load them individually"
  (BugExcerpt/IdentityExcerpt), and locks the repo for writes.
  Source: https://github.com/git-bug/git-bug/blob/trunk/doc/design/architecture.md
- Cost profile: reading one entity means walking a ref's commit chain and JSON
  blobs; listing N entities without the excerpt cache would be N chain replays.
  The excerpt cache is the admission that replay-per-read does not scale to
  whole-set queries.

Lesson shape: append-only + content-addressed IDs give free merge semantics and
audit trail, but (1) deletion becomes nearly impossible by design, and (2) any
whole-set view forces a derived on-disk index. Also a warning: entity-per-ref and
operation-per-commit is opaque to humans -- records are not greppable files.

---

## 3. Obsidian Tasks + Dataview (markdown fields over a live index)

### Record model

- A "task" is a markdown list item line; metadata is inline on the line. Two field
  syntaxes exist: emoji signifiers (`id` = the ID emoji, `dependsOn` = the blocked
  emoji) and Dataview-style inline fields (`[id:: abcdef]`,
  `[dependsOn:: abcdef]`).
  Source: https://publish.obsidian.md/tasks/Getting+Started/Task+Dependencies
- Dataview reads two metadata channels: YAML frontmatter ("a common Markdown
  extension which allows for YAML metadata to be added to the top of a page") and
  inline `Key:: Value` fields anywhere in the body (bracketed `[key:: value]` when
  embedded in list items/tasks). Field names are sanitized (lowercased, spaces to
  dashes) for querying.
  Source: https://blacksmithgu.github.io/obsidian-dataview/annotation/add-metadata/

### (a) Where edges live

- Inline, and directionally split across the two endpoints -- but only one side is
  an edge. The blocking task carries an `id` ("one or more of the following
  allowed characters: a-z A-Z 0-9 _ -"); the blocked task carries
  `dependsOn` with "one or more id values of other tasks separated by commas".
  The `id` is an addressability declaration, not a reverse edge; the edge itself
  is stored once, on the dependent. "Blocking"/"blocked" views are computed by
  querying the index.
  Source: https://publish.obsidian.md/tasks/Getting+Started/Task+Dependencies
- Notable: IDs are short random alphanumerics, only assigned when a task first
  participates in a dependency -- addressability is opt-in per record.

### (b) Edge consistency

- Validation on read only. Nothing prevents `dependsOn` pointing at a nonexistent
  id; the query layer just resolves what it finds. Known sharp edges are
  documented rather than prevented: the edit modal "adding lots of dependencies
  in one step (perhaps 4 or more) can cause an error", and on recurrence "the next
  recurrence will intentionally have any id and dependsOn values removed" (edges
  do not survive record duplication).
  Source: https://publish.obsidian.md/tasks/Getting+Started/Task+Dependencies

### (c) Archive / deletion

- No system-level archive; a task line is deleted or moved like any text, and
  history is whatever the vault's sync/git provides. (Obsidian Tasks statuses are
  checkbox states in the text itself.)

### (d) Scaling

- The whole approach only works because of an aggressive derived index. Dataview
  describes itself as "a high-performance data index and query language over
  Markdown files" and claims it "scal[es] up to hundreds of thousands of annotated
  notes without issue."
  Source: https://github.com/blacksmithgu/obsidian-dataview (README)
- The index is kept live on file events and persisted: Dataview caches file
  metadata in IndexedDB so re-opening a vault avoids a full re-parse -- small
  vaults (<1000 notes) barely notice, "but large vaults and mobile devices will
  notice a very significant performance improvement".
  Sources: https://blacksmithgu.github.io/obsidian-dataview/changelog/ (0.4.x),
  https://github.com/blacksmithgu/obsidian-dataview/issues/1064
- Pattern: source of truth stays plain text; performance comes entirely from a
  rebuildable cache keyed by file size/mtime.

Lesson shape: inline single-direction edges + read-time resolution is workable at
large scale if (and only if) reads go through an index that is disposable and
rebuildable from the files. Frontmatter and inline fields can coexist, but every
extra syntax multiplies parser and tooling cost.

---

## 4. org-mode (PROPERTIES drawers, id: links, dual archiving, org-edna)

### Record model

- A task is a headline; structured metadata lives in a `:PROPERTIES: ... :END:`
  drawer "right below a headline, and its planning line"; "Each property is
  specified on a single line, with the key -- surrounded by colons -- first, and
  the value after it." Keys are case-insensitive; `KEY+` appends; property
  inheritance down the subtree is configurable (`org-use-property-inheritance`).
  Source: https://orgmode.org/manual/Property-Syntax.html

### (a) Where edges live

- Links are inline. Durable links use the `id:` link type against a globally
  unique `ID` property ("create and/or use a globally unique 'ID' property for
  the link", UUID by default via `org-id-method`); such a link "works even if the
  entry is moved from file to file."
  Source: https://orgmode.org/manual/Handling-Links.html
- Task dependencies (org-edna, successor of org-depend) are stored as `BLOCKER`
  and `TRIGGER` properties in the drawer of the affected heading. Targets are
  found by finder expressions: relative (`previous-sibling`, `children`, ...),
  by id (`ids(UUID)`), by file, or by match query. So the edge lives on the
  blocked task, and can be *intensional* (a query) rather than an id list.
  Source: https://www.nongnu.org/org-edna-el/

### (b) Edge consistency

- Resolution on use, backed by a persistent side index for ids: org-id "works by
  maintaining a hash table for IDs and writing this table to disk when exiting
  Emacs" (`org-id-locations-file`); on a miss it rescans "all agenda files, all
  associated archives, all open Org files, and all files currently mentioned in
  `org-id-locations`" before giving up. The index is a cache of id -> file; it is
  never the source of truth and is repaired by re-scan.
  Source: https://raw.githubusercontent.com/bzg/org-mode/main/lisp/org-id.el
- org-edna evaluates BLOCKER at state-change time (blocking the TODO->DONE edit),
  i.e. enforcement on write of the *status*, not on write of the edge.

### (c) Archive / deletion -- org has BOTH archiving modes

- Move-based: `org-archive-subtree` (C-c C-x C-s) moves the subtree "to the
  location given by `org-archive-location`" -- by default a sibling file named
  `<file>_archive`; a per-file `#+ARCHIVE:` directive or per-entry `ARCHIVE`
  property can override. Because the move destroys context, org records it as
  properties on the archived entry: "the file from where the entry came, its
  outline path, the archiving time etc." (`org-archive-save-context-info`).
  Source: https://orgmode.org/manual/Moving-subtrees.html
- In-place: tagging a headline `:ARCHIVE:` keeps it in the file but (1) the
  subtree stays folded during cycling, (2) sparse-tree "matches in archived
  subtrees are not exposed", (3) "the content of archived trees is ignored" for
  agenda construction (temporarily overridable with `v a`), (4) "Archived trees
  are not exported, only the headline is", (5) column view skips them. There is
  also a hybrid: `org-archive-to-archive-sibling` moves the entry under a local
  "Archive" sibling heading, "retaining a lot of its original context, including
  inherited tags and approximate position in the outline."
  Source: https://orgmode.org/manual/Internal-archiving.html
- The manual states the motive for move-based archiving plainly: it keeps
  "working files compact and global searches like the construction of agenda
  views fast."
  Source: https://orgmode.org/manual/Archiving.html

### (d) Scaling

- Same pattern as elsewhere: full-text scan is the baseline, and the two
  mitigations are (1) shrink the hot set (archive files exist for exactly this)
  and (2) side caches (org-id-locations; org also caches agenda parsing). The
  in-place ARCHIVE tag notably does NOT help scan cost -- every reader still
  parses the entry and then filters it out, which is why the manual pushes
  move-based archiving for performance.

Lesson shape: org is the strongest precedent for offering BOTH archive modes with
distinct semantics: in-place tag = "hide from views, keep context/links intact";
move to archive file = "shrink the working set", with provenance properties
stamped on the record at move time so context survives the move.

---

## 5. Fossil ticket system (append-only artifacts + shunning)

### Record model

- A ticket is not a record; it is the fold of an append-only stream of immutable
  "ticket change artifacts": "Each ticket change artifact corresponds to a single
  change to a ticket. The act of creating a ticket is considered a change." Each
  artifact carries ticket id, timestamp, user, and key/value pairs.
  Source: https://fossil-scm.org/home/doc/trunk/www/tickets.wiki

### (a) Where edges live

- Inline in the artifacts (as key/value fields on ticket changes). The relational
  view is derived: the TICKET/TICKETCHNG SQL tables are pure caches -- "display of
  tickets is accomplished using SQL tables but ... recording and syncing of ticket
  information is accomplished using ticket change artifacts."
  Source: https://fossil-scm.org/home/doc/trunk/www/tickets.wiki

### (b) Edge consistency

- By construction/replay: "To determine the current state of a particular ticket,
  Fossil orders the change artifacts for that ticket from oldest to most recent,
  then applies each change in time stamp order." The tables "can always be
  reconstructed from the ticket change artifacts" and are rebuilt when new
  artifacts arrive (or via `fossil rebuild`).
  Source: https://fossil-scm.org/home/doc/trunk/www/tickets.wiki

### (c) Deletion in an immutable store: shunning

- "Fossil is designed to keep all historical content forever"; deletion is
  deliberately hard. The escape hatch is a per-repository "shun list" of artifact
  hashes: "Fossil will refuse to push or pull any shunned artifact"; the bytes
  are physically removed only "whenever the repository is reconstructed using the
  'rebuild' command" (the shun list itself survives the rebuild).
  Source: https://fossil-scm.org/home/doc/trunk/www/shunning.wiki
- Crucially the shun list "is part of the local state" and "does not propagate to
  a remote repository using the normal 'sync' mechanism" -- deletion cannot be
  performed remotely, by design; and shunning structural artifacts (check-ins
  with descendants) is warned against because later artifacts reference them.
  Source: https://fossil-scm.org/home/doc/trunk/www/shunning.wiki

Lesson shape: an immutable/append-only design must invent a second, out-of-band
mechanism (a denylist plus a compaction pass) to get deletion back -- and that
mechanism deliberately does not replicate. For a git-backed file store the exact
analogue already exists: removing a file deletes it from the working tree while
git history retains it, and true redaction requires history rewrite (the git
equivalent of `rebuild`). Designing "delete" as "remove from working set" and
accepting that git keeps the bytes is the same trade Fossil made, minus the
custom machinery.

---

## 6. beancount / plain-text accounting (the double-entry analogy)

### Record model

- The ledger is one or more plain text files of dated directives; beancount
  "maintains an in-memory data structure of all entries without a database
  backend" -- the entire file set is parsed, sorted by date, and validated on
  every run. All reports are derived by replaying "the entire stream of
  transactions"; nothing derived is stored.
  Sources: https://beancount.github.io/docs/beancount_language_syntax/ ,
  https://beancount.github.io/docs/the_double_entry_counting_method/

### The double-entry invariant

- Every transaction has 2+ postings and "the sum of all the amounts on its
  postings equals ZERO, in all currencies." A violation is a load-time error.
  Source: https://beancount.github.io/docs/beancount_language_syntax/
- The redundancy is the point: single-entry (a categorized list) has no built-in
  verification; double-entry records each flow in two accounts, so bookkeeping
  errors surface as imbalance instead of silently wrong totals.
  Source: https://beancount.github.io/docs/the_double_entry_counting_method/
- Two mechanics matter for the analogy:
  1. Interpolation: "you may elide the amount of (at most) one posting" and the
     system derives it from the zero-sum rule -- i.e. even in a double-entry
     system, one side can be authoritative and the other computed at read time.
     Source: https://beancount.github.io/docs/beancount_language_syntax/
  2. Balance assertions: a directive declaring "the number of units ... in some
     account should equal some expected value" at a date, checked at load; it
     catches drift such as duplicated imports. Redundant declarations exist to
     be *checked*, never to be trusted over the primary data.
     Source: https://beancount.github.io/docs/beancount_language_syntax/

### What the redundancy buys and costs (applied to dependency edges)

- Buys: storing an edge at both endpoints (`depends` on the dependent AND
  `dependents` on the blocker) would make certain corruptions detectable -- a
  half-recorded edge shows up as an imbalance, like a transaction that does not
  sum to zero.
- Costs: (1) every edge mutation touches two files, doubling git churn and merge
  conflict surface (two concurrent edits to unrelated edges of the same popular
  blocker now collide); (2) an invariant checker must run on every load, and
  every mismatch needs a repair story (which side wins?); (3) it contradicts the
  interpolation insight -- when one side can always be recomputed from a full
  scan, the second copy is a cache with a consistency obligation, not new
  information. Beancount's imbalance check works because the two postings are
  *independent facts* (money left A; money entered B) that can disagree
  meaningfully. `dependents` is not an independent fact; it is exactly the
  transpose of `depends`. A checker can only ever conclude "the cache is stale."
- The genuinely transferable device is the balance assertion, not double entry:
  optional, checkable, dated declarations ("milestone X has N open tasks",
  "task Y has no dependents") that a validator confirms against the computed
  graph -- redundancy used purely for error detection, stored sparsely, never
  authoritative.

---

## Cross-system summary

| system | edge storage | consistency | deletion/archive | scale technique |
|---|---|---|---|---|
| Taskwarrior | inline on dependent (`dep_<uuid>` keys) | derived reverse, recompute on read | status change; hot/cold split (working set / completed) | working-set gc; 3.x SQLite replica + op log |
| git-bug | inline ops in per-entity commit chain | by construction (Lamport clocks, replay validation) | none in spec; local ref removal only | mandatory on-disk excerpt cache |
| Obsidian Tasks/Dataview | inline on dependent (`dependsOn`); blocker holds opt-in `id` | resolve on read, no enforcement | plain text edit; no system archive | live index persisted to IndexedDB |
| org-mode | inline drawer properties; `id:` links; edna `BLOCKER` on blocked task | resolve on use; id->file side cache with rescan repair | BOTH: move to `_archive` file (with provenance props) and in-place `:ARCHIVE:` tag | shrink hot set via archive files; org-id-locations cache |
| Fossil tickets | inline k/v in append-only artifacts | fold/replay by timestamp; SQL tables are rebuildable caches | shun list (local, non-syncing) + rebuild compaction | derived TICKET tables |
| beancount | n/a (postings) -- but: dual-sided by invariant | zero-sum checked on every load; one side may be elided/derived | n/a (append text; VCS is history) | full reparse per run, no cache, viable at 10k+ directives |

Convergent findings:

1. Nobody uses a central edge file as source of truth. Every system stores the
   edge inline in exactly one endpoint's record. Central structures exist in
   almost every system, but always as *derived, disposable* indexes (Fossil's
   TICKET table, git-bug excerpts, Dataview's IndexedDB cache, org-id-locations,
   Taskwarrior's working set).
2. The stored edge direction is consistently "dependent declares its upstream"
   (Taskwarrior `dep_`, Obsidian `dependsOn`, org-edna `BLOCKER`). The reverse
   view (dependents/blocking) is always computed.
3. Consistency is achieved by construction or on read -- not by keeping two
   stored copies in sync. Where redundancy exists (beancount), it is either
   between independent facts, or an assertion that exists only to be checked.
4. Deletion in append-only/immutable designs is painful and bolted on (Fossil
   shunning; git-bug simply lacks it). Mutable-file designs get deletion for
   free and rely on the VCS for history.
5. Move-based archiving is the performance lever (org manual says so explicitly;
   Taskwarrior's pending/completed split is the same idea); in-place flags are
   the context-preservation lever. org-mode supports both because they solve
   different problems.

---

## Transferable decisions

Target: one markdown file per task, YAML frontmatter, `depends: [ids]` DAG,
chronological milestones + tags, git as history, 1k-10k tasks.

### 1. Inline `depends` vs central edge file vs double-ledger

**Keep single-entry inline `depends` on the dependent. Do not add a central edge
file. Do not store `dependents`.**

- Inline-on-dependent matches every surveyed system's stored direction, and it
  matches git's merge model: adding an edge touches one file (the task gaining a
  blocker), so two people adding different deps to different tasks never
  conflict; two people editing the same task's deps conflict on exactly the file
  a human would expect to inspect.
- A central edge file (all edges in one ledger) inverts that: every dependency
  change in the whole workspace contends on one file -- the worst possible git
  merge hotspot -- and a task file no longer tells its own story when read alone.
  No surveyed system does this for source of truth; the central artifacts are
  always caches (Fossil TICKET, git-bug excerpts, Dataview index). If a central
  file ever appears in taskkling it should have exactly that status: a
  regenerable export/index, never hand-edited, ideally gitignored.
- Double-ledger (store `depends` AND `dependents`) fails the beancount test: the
  two sides are not independent facts, so the "imbalance check" can only detect
  cache staleness it itself introduced. It doubles write amplification and merge
  surface (popular blockers become conflict magnets), and it contradicts the
  repo's own rule that computed attributes are "derived at read time and never
  stored" (DOMAIN_LANGUAGE.md section 6 already lists `dependents` as computed).
  At 1k-10k tasks, computing the transpose is one full scan -- the same scan the
  export already does. Beancount reparses and revalidates its entire ledger on
  every run at comparable record counts with no cache at all.
- What IS worth stealing from beancount: load-time validation as a hard error
  channel (unknown id in `depends` = report it, like a non-zero transaction --
  taskkling's "dangling reference counts as unmet" already does the graceful
  half; a `doctor`/`check` verb should do the loud half), and optional
  assertion-style redundancy (sparse, checkable declarations) rather than
  mirrored fields.

### 2. Move-based archiving vs in-place flags

**Use both, for different purposes -- org-mode is the precedent -- with move-based
as the working-set mechanism and status flags as the semantic mechanism.**

- The current design (DOMAIN_LANGUAGE.md section 8: `status: done/dropped` in
  place, then a `cleanup` sweep moves closed tasks to a flat archive dir; delete
  = move to trash + cascade prune) is exactly the org-mode dual model and the
  Taskwarrior working-set model, and prior art endorses it:
  - In-place status flag first (like `:ARCHIVE:` / Taskwarrior status change):
    the task keeps participating in the graph while its closure is fresh --
    dependents' `depends` entries still resolve, and "resolved" edges can render
    muted rather than dangling.
  - Move on sweep (like `org-archive-subtree` / the pending->completed split):
    the whole point, per the org manual, is keeping the hot directory small so
    every full-scan reader (export, list, UI load) stays fast. At 1k-10k tasks a
    full scan of *active* tasks is trivially fast if closed tasks are moved out;
    it degrades steadily if they are not (Taskwarrior's "more data to read"
    slowdown on completed-inclusive reports).
- Two org-mode details worth copying at move time:
  1. Stamp provenance on the moved record (org's ARCHIVE_TIME / ARCHIVE_FILE /
     ARCHIVE_OLPATH via `org-archive-save-context-info`): archived task
     frontmatter should record when it was archived and any context that the
     move destroys, so the archive file is self-describing without git
     archaeology.
  2. Readers must have a cheap "include archives" mode (org agenda's `v a`):
     export/list should be able to widen to the archive dir on demand without
     that being the default.
- Git interplay: because `git mv` preserves history, move-based archiving costs
  nothing in recoverability -- `git log --follow` still works, and Fossil's
  lesson applies: in a VCS-backed store, "delete" can only ever mean "remove
  from the working tree"; the bytes persist in history unless the human rewrites
  it. Therefore trash/restore is purely a UX convenience layer over what git
  already guarantees, which argues for keeping trash simple (flat dir, no
  metadata beyond the cascade-prune record needed for `restore` to "report
  non-rewired edges").
- One caution against archiving *too* eagerly: a closed task that is still the
  target of an open task's `depends` id will become a dangling reference the
  moment it moves, unless the resolver also looks in the archive (or the sweep
  refuses/warns while live dependents exist). Pick one explicitly: either the
  reader resolves ids across active+archive for edge-status purposes, or the
  `cleanup` sweep prunes/warns like `delete` does. Obsidian's silent
  id-stripping on recurrence shows how quietly edges rot when nobody owns this.

### 3. IDs and addressing

- Stable, opaque, never-reused ids are universal (Taskwarrior UUIDs, git-bug
  content hashes, org-id UUIDs). taskkling's short random ids (`t-2vtv` style)
  sit in the same family as Obsidian Tasks' short ids; the prior-art warning is
  Taskwarrior's: never let a human-convenient *positional* number leak into
  stored data -- if sequence numbers are ever wanted, make them a derived,
  explicitly volatile working-set mapping.
- Prefix matching for human input (git-bug displays 7 chars, accepts prefixes;
  Taskwarrior accepts UUID prefixes) is cheap and worth having in the CLI.

### 4. Scale posture for 1k-10k

- Full parse per invocation is fine at this scale -- beancount proves plain-text
  full-load works well past 10k records, and Dataview handles orders of
  magnitude more *with* a cache. The systems that added SQLite/excerpt caches
  (Taskwarrior 3, git-bug) did so for workloads (constant re-invocation, whole-
  set queries over slow substrates) that only start to bite past this range.
- The design rule that keeps the door open: any future index must be a pure
  cache, rebuildable from the task files, invalidated by file size/mtime
  (Dataview) or rebuilt wholesale (Fossil `rebuild`), and never a write target.
  Source-of-truth stays the markdown files; the JSON export already is this
  pattern (a derived whole-set projection), which is exactly where an on-disk
  cache would slot in if 10k+ ever hurts.
- Keep the hot set small structurally (archive sweep) before reaching for a
  cache -- that is the cheapest lever in every surveyed system.

### 5. One syntax, one channel

- Obsidian's two field syntaxes (emoji + inline fields) and org's
  properties-plus-tags-plus-planning-lines show how metadata channels multiply
  parser cost and user confusion. taskkling's "frontmatter keys are FROZEN"
  discipline (DOMAIN_LANGUAGE.md section 9) is the right instinct: exactly one
  stored syntax (YAML frontmatter), body text stays free-form, and every
  computed attribute stays out of the file.
