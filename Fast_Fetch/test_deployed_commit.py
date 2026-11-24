#!/usr/bin/env python3
"""Offline suite for the `.deployed_commit` marker (INF-08).

The marker names the commit the live tree runs. INF-08 found it holding
`938f5f7` — a SHA that exists nowhere in media-catalog history (GitHub API:
422 "No commit found for SHA: 938f5f7"). This suite pins the two promises the
marker makes, plus the gate checker's behaviour:

  1. the marker is one 40-hex commit SHA (canonical git form);
  2. that SHA resolves to a commit in this repo's history;
  3. `check_deployed_commit.py --expect <sha>` fails on a mismatch and passes
     on a match, so the gate can assert the marker equals the landed target.

The marker is the *last deployed* commit, not the current HEAD: after this
branch merges, HEAD moves on while the marker still names the deployed
commit, so no test asserts marker == HEAD — only that the marker exists in
history. The next gate re-pins it to the new live HEAD.

Resolution (2) needs git history. CI checks out with `fetch-depth: 0`, so it
runs there; where there is no git repo at all the resolution test skips
gracefully (the format test still runs). A repo that HAS history but lacks
the object is a hard failure, never a skip.

Stdlib only. Read-only, no database, no network.
Run from the repo root: `python3 -S Fast_Fetch/test_deployed_commit.py`
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
REPO_ROOT = BASE_DIR.parent
MARKER = REPO_ROOT / ".deployed_commit"
CHECK = BASE_DIR / "check_deployed_commit.py"

sys.path.insert(0, str(BASE_DIR))

import check_deployed_commit as cdc  # noqa: E402

SHA_RE = re.compile(r"^[0-9a-f]{40}$")
ZERO_SHA = "0" * 40


def _git_available(root: Path) -> bool:
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


class MarkerFormatTests(unittest.TestCase):
    def test_marker_file_exists(self):
        self.assertTrue(MARKER.is_file(), f"missing marker file: {MARKER}")

    def test_marker_is_single_40_hex_sha(self):
        raw = MARKER.read_text(encoding="utf-8")
        lines = raw.splitlines()
        self.assertEqual(len(lines), 1, f"marker must hold one line: {raw!r}")
        self.assertRegex(lines[0].strip(), SHA_RE, "marker is not a 40-hex SHA")


class MarkerHistoryTests(unittest.TestCase):
    def test_marker_resolves_to_a_commit(self):
        sha = MARKER.read_text(encoding="utf-8").strip()
        if not _git_available(REPO_ROOT):
            self.skipTest("no git history available here")
        proc = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "cat-file", "-t", sha],
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(proc.returncode, 0, f"{sha} is not an object in history")
        self.assertEqual(proc.stdout.strip(), "commit", f"{sha} is not a commit")

    def test_checker_accepts_current_marker(self):
        code, message = cdc.check(REPO_ROOT, MARKER)
        self.assertEqual(code, 0, message)


class CheckerBehaviourTests(unittest.TestCase):
    def _write_marker(self, directory: Path, content: str) -> Path:
        marker = directory / ".deployed_commit"
        marker.write_text(content, encoding="utf-8")
        return marker

    def test_rejects_short_sha(self):
        with tempfile.TemporaryDirectory() as tmp:
            marker = self._write_marker(Path(tmp), "938f5f7\n")
            code, message = cdc.check(Path(tmp), marker)
            self.assertEqual(code, 2, message)

    def test_rejects_empty_marker(self):
        with tempfile.TemporaryDirectory() as tmp:
            marker = self._write_marker(Path(tmp), "")
            code, message = cdc.check(Path(tmp), marker)
            self.assertEqual(code, 2, message)

    def test_rejects_two_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            marker = self._write_marker(
                Path(tmp),
                "a6e27ad601445561bfe2688a8807fb0ac30bdacd\n"
                "a6e27ad601445561bfe2688a8807fb0ac30bdacd\n",
            )
            code, message = cdc.check(Path(tmp), marker)
            self.assertEqual(code, 2, message)

    def test_rejects_missing_marker(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, message = cdc.check(Path(tmp), Path(tmp) / ".deployed_commit")
            self.assertEqual(code, 2, message)

    def test_skips_resolution_without_a_repo(self):
        # A valid-format SHA in a directory with no git repo: format passes,
        # history cannot be checked, so the checker skips (exit 0) loudly.
        with tempfile.TemporaryDirectory() as tmp:
            marker = self._write_marker(
                Path(tmp), "a6e27ad601445561bfe2688a8807fb0ac30bdacd\n"
            )
            code, message = cdc.check(Path(tmp), marker)
            self.assertEqual(code, 0, message)
            self.assertIn("history not checked", message)

    def test_rejects_sha_absent_from_history(self):
        if not _git_available(REPO_ROOT):
            self.skipTest("no git available to build a repo fixture")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(
                ["git", "init", "-q", str(root)],
                check=True, capture_output=True, timeout=30,
            )
            subprocess.run(
                ["git", "-C", str(root), "config", "user.email", "t@t"],
                check=True, capture_output=True, timeout=30,
            )
            subprocess.run(
                ["git", "-C", str(root), "config", "user.name", "t"],
                check=True, capture_output=True, timeout=30,
            )
            (root / "f.txt").write_text("x", encoding="utf-8")
            subprocess.run(
                ["git", "-C", str(root), "add", "f.txt"],
                check=True, capture_output=True, timeout=30,
            )
            subprocess.run(
                ["git", "-C", str(root), "commit", "-qm", "x"],
                check=True, capture_output=True, timeout=30,
            )
            # ZERO_SHA is a valid-format SHA that no repo contains.
            marker = self._write_marker(root, ZERO_SHA + "\n")
            code, message = cdc.check(root, marker)
            self.assertEqual(code, 2, message)

    def test_accepts_sha_present_in_history(self):
        if not _git_available(REPO_ROOT):
            self.skipTest("no git available to build a repo fixture")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(
                ["git", "init", "-q", str(root)],
                check=True, capture_output=True, timeout=30,
            )
            subprocess.run(
                ["git", "-C", str(root), "config", "user.email", "t@t"],
                check=True, capture_output=True, timeout=30,
            )
            subprocess.run(
                ["git", "-C", str(root), "config", "user.name", "t"],
                check=True, capture_output=True, timeout=30,
            )
            (root / "f.txt").write_text("x", encoding="utf-8")
            subprocess.run(
                ["git", "-C", str(root), "add", "f.txt"],
                check=True, capture_output=True, timeout=30,
            )
            subprocess.run(
                ["git", "-C", str(root), "commit", "-qm", "x"],
                check=True, capture_output=True, timeout=30,
            )
            head = subprocess.run(
                ["git", "-C", str(root), "rev-parse", "HEAD"],
                check=True, capture_output=True, text=True, timeout=30,
            ).stdout.strip()
            marker = self._write_marker(root, head + "\n")
            code, message = cdc.check(root, marker)
            self.assertEqual(code, 0, message)

    def test_expect_flag_matches_and_mismatches(self):
        sha = "a6e27ad601445561bfe2688a8807fb0ac30bdacd"
        other = "b6e27ad601445561bfe2688a8807fb0ac30bdacd"
        with tempfile.TemporaryDirectory() as tmp:
            marker = self._write_marker(Path(tmp), sha + "\n")
            code, _ = cdc.check(Path(tmp), marker, expect=sha)
            self.assertEqual(code, 0)
            code, message = cdc.check(Path(tmp), marker, expect=other)
            self.assertEqual(code, 2, message)

    def test_expect_flag_rejects_malformed_expect(self):
        with tempfile.TemporaryDirectory() as tmp:
            marker = self._write_marker(
                Path(tmp), "a6e27ad601445561bfe2688a8807fb0ac30bdacd\n"
            )
            code, _ = cdc.check(Path(tmp), marker, expect="not-a-sha")
            self.assertEqual(code, 2)


class CheckerCliTests(unittest.TestCase):
    def test_cli_exit_codes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bad = root / "bad"
            bad.write_text("938f5f7\n", encoding="utf-8")
            proc = subprocess.run(
                [sys.executable, "-S", str(CHECK), "--root", str(root),
                 "--marker", str(bad)],
                capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(proc.returncode, 2, proc.stdout)
            good = root / "good"
            good.write_text(
                "a6e27ad601445561bfe2688a8807fb0ac30bdacd\n", encoding="utf-8"
            )
            proc = subprocess.run(
                [sys.executable, "-S", str(CHECK), "--root", str(root),
                 "--marker", str(good)],
                capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(proc.returncode, 0, proc.stdout)
            proc = subprocess.run(
                [sys.executable, "-S", str(CHECK), "--root", str(root),
                 "--marker", str(good), "--expect",
                 "b6e27ad601445561bfe2688a8807fb0ac30bdacd"],
                capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(proc.returncode, 2, proc.stdout)


if __name__ == "__main__":
    unittest.main()
