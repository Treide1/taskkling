"""Generate a synthetic taskkling workspace for scale benchmarks.

Usage: python gen_store.py <dir> <n_tasks> <shape>
  shape: sparse | gates | dense

Shapes (dependency structure):
  sparse - backlog-like: 15% of tasks carry 1-2 deps to random earlier tasks.
  gates  - milestone-like (mirrors the real dogfood store): groups of 50 tasks;
           each group ends in a gate task depending on 15 group members; every
           5th group's gate also depends on the previous gate (chronology).
  dense  - combinatorial stress: layered DAG, each task depends on up to 8
           random tasks from earlier layers (~8 edges/task).

Deterministic (seeded) so runs are reproducible.
"""
import random
import sys
from pathlib import Path

ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
THREADS = ["v0.1", "v0.2", "v0.3", "dx", "ui", "infra", "docs", "perf"]
BODY = (
    "Synthetic benchmark task. The body is a realistic three-line brief so\n"
    "parse cost is not trivially zero. It mentions acceptance criteria and\n"
    "links nothing. Lorem ipsum dolor sit amet, consetetur sadipscing elitr.\n"
)


def mint_ids(n: int, rng: random.Random) -> list[str]:
    seen: set[str] = set()
    ids = []
    while len(ids) < n:
        i = "t-" + "".join(rng.choice(ALPHABET) for _ in range(4))
        if i not in seen:
            seen.add(i)
            ids.append(i)
    return ids


def deps_for(shape: str, idx: int, ids: list[str], rng: random.Random) -> list[str]:
    if idx == 0:
        return []
    if shape == "sparse":
        if rng.random() < 0.15:
            k = rng.randint(1, min(2, idx))
            return rng.sample(ids[:idx], k)
        return []
    if shape == "gates":
        group, pos = divmod(idx, 50)
        if pos == 49:  # gate task closing the group
            base = group * 50
            deps = rng.sample(ids[base : base + 49], 15)
            if group % 5 == 0 and group > 0:
                deps.append(ids[group * 50 - 1])  # previous gate
            return deps
        if rng.random() < 0.10:
            return rng.sample(ids[:idx], 1)
        return []
    if shape == "dense":
        k = min(8, idx)
        return rng.sample(ids[:idx], k)
    raise SystemExit(f"unknown shape {shape!r}")


def main() -> None:
    root, n, shape = Path(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
    rng = random.Random(42)
    meta = root / ".taskkling"
    tasks = root / "tasks"
    for d in (meta, meta / "tmp", tasks, tasks / "archive", tasks / "trash"):
        d.mkdir(parents=True, exist_ok=True)
    (meta / "config.toml").write_text(
        'tasks_dir       = "tasks"\nid_prefix       = "t-"\nlock_timeout    = 30\n',
        encoding="utf-8",
    )

    ids = mint_ids(n, rng)
    edges = 0
    for idx, tid in enumerate(ids):
        deps = deps_for(shape, idx, ids, rng)
        edges += len(deps)
        status = "done" if rng.random() < 0.25 else "open"
        lines = [
            "---",
            f"id: {tid}",
            f"title: benchmark task number {idx} with a plausible title length",
            f"thread: {rng.choice(THREADS)}",
            f"status: {status}",
            f"depends: [{', '.join(deps)}]",
            "priority: normal",
            "created: 2026-07-01T10:00:00Z",
        ]
        if status == "done":
            lines.append("closed: 2026-07-05T10:00:00Z")
        lines += ["---", "", BODY]
        fname = f"{tid}--benchmark-task-number-{idx}-with-a-plausible-title.md"
        (tasks / fname).write_text("\n".join(lines), encoding="utf-8", newline="\n")

    print(f"{root}: {n} tasks, {edges} edges, shape={shape}")


if __name__ == "__main__":
    main()
