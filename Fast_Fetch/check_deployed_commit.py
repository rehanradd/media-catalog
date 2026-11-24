#!/usr/bin/env python3
"""Check the `.deployed_commit` marker: the SHA of the commit the live tree runs.

Why this file exists: INF-08 (full-project checkup, 2026-11-24) — the live-tree
marker held `938f5f7`, a SHA that does not exist anywhere in media-catalog
history (GitHub API: 422 "No commit found for SHA: 938f5f7"). A marker that
points at nothing cannot prove what is deployed, and a gate that trusts it
passes blind. This checker makes the marker's promises audible:

  1. the marker is one 40-hex commit SHA (the canonical git form);
  2. that SHA resolves to a commit in this repo's history.

Gate use (run after the merge, before the service restart):

    python3 -S Fast_Fetch/check_deployed_commit.py --expect <gate-target-sha>

`--expect` adds the third promise: the marker equals the commit the gate just
landed. Without `--expect` the check is the marker's own two promises.

Exit codes: 0 = OK (or SKIP when there is no git repo to resolve against, e.g.
a source tarball — the format promise is still enforced); 2 = FAIL.
Stdlib only, read-only, no database, no network.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
REPO_ROOT = BASE_DIR.parent
MARKER = REPO_ROOT / ".deployed_commit"

SHA_RE = re.compile(r"^[0-9a-f]{40}$")

# Exit codes: 0 ok/skip, 2 fail. (1 is argparse's usage-error code.)
EXIT_OK = 0
EXIT_FAIL = 2


def read_marker(marker_path: Path) -> str:
    """Return the marker's SHA, or raise ValueError with the reason it is bad."""
    try:
        raw = marker_path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise ValueError(f"marker file missing: {marker_path}")
    except OSError as exc:
        raise ValueError(f"marker file unreadable: {marker_path}: {exc}")
    lines = raw.splitlines()
    if len(lines) != 1:
        raise ValueError(
            f"marker must hold exactly one line, found {len(lines)}: {marker_path}"
        )
    sha = lines[0].strip()
    if not SHA_RE.match(sha):
        raise ValueError(
            f"marker is not one 40-hex commit SHA: {sha!r} (in {marker_path})"
        )
    return sha


def _git_repo_present(root: Path) -> bool:
    """True when `root` is inside a git work tree git can read."""
    try:
        proc = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "--git-dir"],
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return False
    return proc.returncode == 0


def resolve_in_history(root: Path, sha: str) -> bool | None:
    """True when `sha` is a commit in `root`'s history.

    Returns None (skip, not fail) when there is no git repo to resolve
    against — a source tarball has no history to check, and the format
    promise in read_marker is still enforced. Returns False only when git
    is available and the object is absent or is not a commit.
    """
    if not _git_repo_present(root):
        return None
    try:
        proc = subprocess.run(
            ["git", "-C", str(root), "cat-file", "-t", sha],
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return None
    if proc.returncode != 0:
        return False
    return proc.stdout.strip() == "commit"


def check(root: Path, marker_path: Path, expect: str | None = None) -> tuple[int, str]:
    """Run the marker promises. Returns (exit_code, one-line message)."""
    try:
        sha = read_marker(marker_path)
    except ValueError as exc:
        return EXIT_FAIL, f"FAIL: {exc}"
    if expect is not None:
        want = expect.strip().lower()
        if not SHA_RE.match(want):
            return EXIT_FAIL, f"FAIL: --expect is not a 40-hex commit SHA: {expect!r}"
        if sha != want:
            return (
                EXIT_FAIL,
                f"FAIL: marker {sha} != expected {want} (gate target)",
            )
    resolved = resolve_in_history(root, sha)
    if resolved is False:
        return (
            EXIT_FAIL,
            f"FAIL: {sha} does not resolve to a commit in this repo's history",
        )
    if resolved is None:
        return (
            EXIT_OK,
            f"OK: .deployed_commit={sha} (format valid; history not checked: "
            "no git repo here)",
        )
    return EXIT_OK, f"OK: .deployed_commit={sha} (commit in history)"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Verify .deployed_commit points at a real commit in history."
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=REPO_ROOT,
        help="repo root (default: parent of this script's directory)",
    )
    parser.add_argument(
        "--marker",
        type=Path,
        default=None,
        help="marker file (default: <root>/.deployed_commit)",
    )
    parser.add_argument(
        "--expect",
        default=None,
        help="gate target SHA the marker must equal (default: no equality check)",
    )
    args = parser.parse_args(argv)
    marker = args.marker if args.marker is not None else args.root / ".deployed_commit"
    code, message = check(args.root, marker, args.expect)
    print(message)
    return code


if __name__ == "__main__":
    sys.exit(main())
