"""Merge lab: how do candidate dependency-storage schemes behave under git merges?

For each (scheme, scenario) this builds a tiny store in a fresh git repo,
applies semantic operations on two branches the way the tool would (deterministic
formatting), merges, and records:
  - whether git raised a textual conflict (and where)
  - whether a clean merge is semantically broken (dangling edges, asymmetric
    double-ledger entries)

Schemes:
  inline-flow    depends: [a, b] single line in the task file (status quo)
  inline-block   depends as YAML block list, one id per line
  double-ledger  inline-block + mirrored `dependents:` block in the other endpoint
  hoisted-file   no deps in task files; single tasks/_deps.txt, sorted "child <- parent" lines
  hoisted-dir    no deps in task files; tasks/_deps/<child>.txt, one parent id per line

Usage: python merge_lab.py <workdir>
Prints a CSV: scheme,scenario,conflict,conflict_files,dangling_edges,asymmetry,notes
"""
import shutil
import subprocess
import sys
from pathlib import Path

SCHEMES = ["inline-flow", "inline-block", "double-ledger", "hoisted-file", "hoisted-dir"]

# ---------------------------------------------------------------- store model

class Store:
    """In-memory task store rendered to disk per scheme."""

    def __init__(self, scheme: str, root: Path):
        self.scheme = scheme
        self.root = root
        self.tasks: dict[str, dict] = {}  # id -> {title, status, deps:[ids]}

    # -- semantic operations (what the tool would do) --

    def add(self, tid: str, title: str, deps: list[str] | None = None) -> None:
        self.tasks[tid] = {"title": title, "status": "open", "deps": list(deps or [])}

    def link(self, tid: str, dep: str) -> None:
        if dep not in self.tasks[tid]["deps"]:
            self.tasks[tid]["deps"].append(dep)

    def done(self, tid: str) -> None:
        self.tasks[tid]["status"] = "done"

    def drop(self, tid: str) -> None:
        self.tasks[tid]["status"] = "dropped"

    def retitle(self, tid: str, title: str) -> None:
        self.tasks[tid]["title"] = title

    def delete(self, tid: str) -> None:
        del self.tasks[tid]
        for t in self.tasks.values():  # cascade prune
            t["deps"] = [d for d in t["deps"] if d != tid]

    # -- rendering --

    def slug(self, tid: str) -> str:
        title = self.tasks[tid]["title"]
        return "".join(c if c.isalnum() else "-" for c in title.lower()).strip("-")[:40]

    def render(self) -> None:
        tasks_dir = self.root / "tasks"
        if tasks_dir.exists():
            shutil.rmtree(tasks_dir)
        tasks_dir.mkdir(parents=True)
        dependents: dict[str, list[str]] = {}
        for tid, t in self.tasks.items():
            for d in t["deps"]:
                dependents.setdefault(d, []).append(tid)

        for tid, t in self.tasks.items():
            lines = ["---", f"id: {tid}", f"title: {t['title']}", f"status: {t['status']}"]
            if self.scheme == "inline-flow":
                lines.append(f"depends: [{', '.join(t['deps'])}]")
            elif self.scheme in ("inline-block", "double-ledger"):
                if t["deps"]:
                    lines.append("depends:")
                    lines += [f"  - {d}" for d in sorted(t["deps"])]
                if self.scheme == "double-ledger" and dependents.get(tid):
                    lines.append("dependents:")
                    lines += [f"  - {d}" for d in sorted(dependents[tid])]
            lines += ["priority: normal", "created: 2026-07-01T10:00:00Z", "---", "",
                      f"Body of {tid}.", ""]
            (tasks_dir / f"{tid}--{self.slug(tid)}.md").write_text(
                "\n".join(lines), encoding="ascii", newline="\n")

        if self.scheme == "hoisted-file":
            edges = sorted(f"{tid} <- {d}" for tid, t in self.tasks.items() for d in t["deps"])
            (tasks_dir / "_deps.txt").write_text("\n".join(edges) + ("\n" if edges else ""),
                                                 encoding="ascii", newline="\n")
        if self.scheme == "hoisted-dir":
            dep_dir = tasks_dir / "_deps"
            dep_dir.mkdir()
            for tid, t in self.tasks.items():
                if t["deps"]:
                    (dep_dir / f"{tid}.txt").write_text(
                        "\n".join(sorted(t["deps"])) + "\n", encoding="ascii", newline="\n")


# ------------------------------------------------------------ git plumbing

def git(root: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(root), *args],
                          capture_output=True, text=True, check=check)


def commit_all(root: Path, msg: str) -> None:
    git(root, "add", "-A")
    git(root, "commit", "-q", "-m", msg, "--no-gpg-sign")


# ------------------------------------------------------- integrity checking

def check_integrity(root: Path, scheme: str) -> tuple[int, int, str]:
    """Parse the merged store; return (dangling_edges, asymmetry, notes)."""
    tasks_dir = root / "tasks"
    ids: set[str] = set()
    deps: dict[str, list[str]] = {}
    dependents_stored: dict[str, list[str]] = {}
    for f in sorted(tasks_dir.glob("t-*.md")):
        tid = f.name.split("--")[0]
        ids.add(tid)
        text = f.read_text(encoding="ascii", errors="replace")
        deps[tid] = []
        mode = None
        for line in text.splitlines():
            if line.startswith("depends: ["):
                inner = line[len("depends: ["):].rstrip("]").strip()
                deps[tid] = [x.strip() for x in inner.split(",") if x.strip()]
            elif line.strip() == "depends:":
                mode = "deps"
            elif line.strip() == "dependents:":
                mode = "dependents"
            elif line.startswith("  - ") and mode == "deps":
                deps[tid].append(line.strip()[2:])
            elif line.startswith("  - ") and mode == "dependents":
                dependents_stored.setdefault(tid, []).append(line.strip()[2:])
            elif not line.startswith("  "):
                if not line.strip() in ("depends:", "dependents:"):
                    mode = None
    if scheme == "hoisted-file":
        ledger = tasks_dir / "_deps.txt"
        if ledger.exists():
            for line in ledger.read_text(encoding="ascii").splitlines():
                if " <- " in line:
                    child, parent = line.split(" <- ")
                    deps.setdefault(child.strip(), []).append(parent.strip())
    if scheme == "hoisted-dir":
        for f in (tasks_dir / "_deps").glob("t-*.txt") if (tasks_dir / "_deps").exists() else []:
            child = f.stem
            deps.setdefault(child, []).extend(
                x.strip() for x in f.read_text(encoding="ascii").splitlines() if x.strip())

    dangling = sum(1 for tid, ds in deps.items() for d in ds if d not in ids)
    dangling += sum(1 for tid in deps if tid not in ids and deps[tid])  # orphan child entries

    asymmetry = 0
    if scheme == "double-ledger":
        derived: dict[str, set[str]] = {}
        for tid, ds in deps.items():
            for d in ds:
                derived.setdefault(d, set()).add(tid)
        for tid in ids:
            stored = set(dependents_stored.get(tid, []))
            if stored != derived.get(tid, set()):
                asymmetry += 1
    return dangling, asymmetry, ""


# ------------------------------------------------------------- scenarios

def base_store(scheme: str, root: Path) -> Store:
    s = Store(scheme, root)
    for i in range(1, 13):
        s.add(f"t-{i:04d}", f"base task number {i}")
    s.link("t-0010", "t-0001")  # existing gate edges
    s.link("t-0010", "t-0002")
    s.link("t-0005", "t-0004")
    return s

# each scenario: (name, ops_a, ops_b) where ops apply semantic changes
SCENARIOS = [
    ("same-task-two-deps",
     lambda s: s.link("t-0006", "t-0001"),
     lambda s: s.link("t-0006", "t-0002")),
    ("diff-task-deps",
     lambda s: s.link("t-0006", "t-0001"),
     lambda s: s.link("t-0007", "t-0002")),
    ("delete-vs-link",
     lambda s: s.delete("t-0004"),
     lambda s: s.link("t-0008", "t-0004")),
    ("status-vs-retitle",
     lambda s: s.done("t-0003"),
     lambda s: s.retitle("t-0003", "renamed third task")),
    ("gate-fanin-both-sides",
     lambda s: [s.link("t-0011", d) for d in ["t-0001", "t-0002", "t-0003", "t-0004", "t-0005"]],
     lambda s: [s.link("t-0011", d) for d in ["t-0006", "t-0007", "t-0008", "t-0009"]]),
    ("both-add-tasks",
     lambda s: s.add("t-a001", "task from branch a", ["t-0001"]),
     lambda s: s.add("t-b001", "task from branch b", ["t-0001"])),
    ("conflicting-status",
     lambda s: s.done("t-0003"),
     lambda s: s.drop("t-0003")),
]


def run_case(scheme: str, name: str, ops_a, ops_b, work: Path) -> str:
    root = work / f"{scheme}--{name}"
    root.mkdir(parents=True)
    git(root, "init", "-q", "-b", "main")
    git(root, "config", "user.email", "lab@example.com")
    git(root, "config", "user.name", "lab")

    s = base_store(scheme, root)
    s.render()
    commit_all(root, "base")

    git(root, "checkout", "-q", "-b", "side-a")
    sa = base_store(scheme, root)
    ops_a(sa)
    sa.render()
    commit_all(root, "a")

    git(root, "checkout", "-q", "main")
    git(root, "checkout", "-q", "-b", "side-b")
    sb = base_store(scheme, root)
    ops_b(sb)
    sb.render()
    commit_all(root, "b")

    git(root, "checkout", "-q", "side-a")
    merged = git(root, "merge", "--no-edit", "--no-gpg-sign", "side-b", check=False)
    conflict = merged.returncode != 0
    conflict_files = ""
    if conflict:
        status = git(root, "diff", "--name-only", "--diff-filter=U").stdout
        conflict_files = ";".join(p.strip() for p in status.splitlines() if p.strip())
        git(root, "merge", "--abort", check=False)
        return f"{scheme},{name},YES,{conflict_files},-,-,"

    dangling, asymmetry, notes = check_integrity(root, scheme)
    return f"{scheme},{name},no,,{dangling},{asymmetry},{notes}"


def main() -> None:
    work = Path(sys.argv[1])
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    print("scheme,scenario,conflict,conflict_files,dangling_edges,asymmetry,notes")
    for scheme in SCHEMES:
        for name, a, b in SCENARIOS:
            print(run_case(scheme, name, a, b, work))


if __name__ == "__main__":
    main()
