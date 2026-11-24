# 2026-11-24 — INF-08 `.deployed_commit` re-pin (Phase 4)

Branch: `muse/phase4-inf08-deployed-commit-20261124`, from live HEAD `a6e27ad6`
(FIX1 landing, verified fresh via `gh api .../commits/main` + `git ls-remote`,
both agreeing on `a6e27ad601445561bfe2688a8807fb0ac30bdacd`).

## Finding

The repo-root `.deployed_commit` marker held `938f5f7` — a SHA that exists
nowhere in media-catalog history (`git cat-file -t 938f5f7` → "Not a valid
object name"; GitHub API 422 "No commit found for SHA: 938f5f7"). Nothing in
the repo reads the marker (code search: 0 hits); the gate scripts that assert
HEAD after merge live on the Oracle VM / per-gate local scripts, not in the
repo — so the repo-side fix is the marker itself plus a check the gate runner
can call.

## Change (4 files)

- `.deployed_commit` → `a6e27ad601445561bfe2688a8807fb0ac30bdacd` (full SHA).
- `Fast_Fetch/check_deployed_commit.py` (new, stdlib CLI): enforces the
  marker's promises — one 40-hex SHA; resolves to a commit via
  `git cat-file -t` (skips loudly, exit 0, when there is no git repo, e.g. a
  source tarball); `--expect <sha>` asserts the marker equals the gate
  target. Gate use after merge, before restart:
  `python3 -S Fast_Fetch/check_deployed_commit.py --expect <gate-target>`.
  Exit 0 ok/skip, 2 fail.
- `Fast_Fetch/test_deployed_commit.py` (new, 14 tests): format, history
  resolution (skips gracefully with no git; hard-fails when a repo lacks the
  object), checker behaviour incl. `--expect` match/mismatch, CLI exit
  codes. Deliberately no marker == HEAD test: the marker is the *last
  deployed* commit, so it legitimately lags HEAD after a merge; the next
  gate re-pins it.
- `.github/workflows/backend-ci.yml`: new step after test_trunk_continuity +
  header-list line (suite 132 by `🧪` count).

## Verification (local, hatch VM)

- New suite 14/14 OK; checker OK on the pinned marker, OK with
  `--expect a6e27ad6…`, FAIL (exit 2) on a mismatched `--expect`.
- `test_trunk_continuity.py` 7/7 OK.
- `test_ci_manifest.py` fails pre-commit only ("present on disk but NOT
  tracked in HEAD") — expected; passes once the files are committed.
- `test_docs_invariants.py`: the first session-log draft went into
  `CONTINUITY.md` and tripped its 12,000-byte budget (12,334), so the entry
  was reverted and the note moved here instead, per the rotation precedent.

## CI

Backend CI auto-triggers on push (Fast_Fetch/** + workflow paths). Android CI
does not auto-trigger (App/** only) — dispatched manually via
workflow_dispatch. Both verified by head SHA before reporting.

## Follow-ups

- Gate runner: add `check_deployed_commit.py --expect <target>` to the VM
  gate script's post-merge assertions (the gate script itself lives outside
  this repo, so that step is not in this branch).
- Each future gate re-pins `.deployed_commit` to the new live HEAD.
