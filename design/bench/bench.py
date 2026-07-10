"""Benchmark taskkling CLI operations against synthetic stores.

Usage: python bench.py <taskkling.exe> <store_dir> [reps=3]

Times each operation reps times (median reported, ms). Mutations drift the
store by a handful of tasks/edges across the run - negligible at bench sizes.
Output: one CSV line per op on stdout: store,n_files,op,median_ms,runs_ms
"""
import random
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

def run(exe: str, root: Path, *args: str) -> tuple[float, str]:
    t0 = time.perf_counter()
    p = subprocess.run(
        [exe, *args, "--root", str(root)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    ms = (time.perf_counter() - t0) * 1000
    if p.returncode != 0:
        raise SystemExit(f"FAILED ({p.returncode}): {args}\n{p.stdout}\n{p.stderr}")
    return ms, p.stdout


def main() -> None:
    exe, root = sys.argv[1], Path(sys.argv[2])
    reps = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    rng = random.Random(7)

    files = sorted((root / "tasks").glob("t-*.md"))
    ids = [f.name.split("--")[0] for f in files]
    open_ids = [
        f.name.split("--")[0]
        for f in files
        if "status: open" in f.read_text(encoding="utf-8")
    ]
    n = len(files)

    # spawn/version baseline: process start + arg parse, no workspace I/O
    ops: list[tuple[str, callable]] = []
    def spawn_baseline(i: int) -> float:
        t0 = time.perf_counter()
        subprocess.run([exe, "--help"], capture_output=True)
        return (time.perf_counter() - t0) * 1000

    ops.append(("spawn_help", spawn_baseline))
    ops.append(("export", lambda i: run(exe, root, "export", "-q")[0]))
    ops.append(("export_body", lambda i: run(exe, root, "export", "-q", "--include-body")[0]))
    ops.append(("list_ready", lambda i: run(exe, root, "list", "-q", "--ready", "--id-only")[0]))
    ops.append(("get", lambda i: run(exe, root, "get", rng.choice(ids))[0]))
    # probes minted by `add` collect here; `link` targets a probe (a brand-new
    # task has no dependents, so the edge cannot cycle - but the full
    # cycle-check/validation cost still runs, which is what we measure).
    probes: list[str] = []

    def add_op(i: int, *extra: str) -> float:
        ms, out = run(exe, root, "add", f"bench probe {len(probes)}", *extra)
        m = re.search(r"\bt-[a-z0-9]{4}\b", out)
        if m:
            probes.append(m.group(0))
        return ms

    ops.append(("add", lambda i: add_op(i)))
    ops.append(("add_dep", lambda i: add_op(i, "--depends", rng.choice(open_ids))))
    ops.append(("done", lambda i: run(exe, root, "done", open_ids.pop(rng.randrange(len(open_ids))), "-q")[0]))
    ops.append(("link", lambda i: run(exe, root, "link", probes[i], "--depends", rng.choice(open_ids), "-q")[0]))
    ops.append(("delete", lambda i: run(exe, root, "delete", open_ids.pop(0), "-q")[0]))
    ops.append(("export_on_success", lambda i: run(exe, root, "done", open_ids.pop(), "-q", "--export-on-success")[0]))

    print("store,n_files,op,median_ms,runs_ms")
    for name, fn in ops:
        times = [round(fn(i), 1) for i in range(reps)]
        print(f"{root.name},{n},{name},{statistics.median(times):.1f},{'|'.join(map(str, times))}")


if __name__ == "__main__":
    main()
