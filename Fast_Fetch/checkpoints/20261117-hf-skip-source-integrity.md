# HF full-gate: --skip-source-integrity + no-sidecar reads — 2026-11-17 ~09:05 PKT

Branch `muse/hf-backup-fullgate-20261117` (from `23eed704`), NOT merged.

## Why
File 1 took ~100 min, not ~45: the source-side `PRAGMA integrity_check` +
counts scans run ~9 MB/s on this 1-vCPU box. The source was already
integrity-checked at gate time, and the restore side re-checks it in
`verify_fullgate` (step 5) — so re-scanning the source buys nothing. The
flag skips only the source-side scan; the restore-side check stays the gate.

## Code (`Fast_Fetch/backup_manager.py`, stdlib only)
- New `--skip-source-integrity` CLI flag (with `--full-gate` only), threaded
  through `run_fullgate` → `create_fullgate_archive` →
  `_fullgate_db_info(db_path, skip_integrity=...)`. A skipped manifest
  records `database.integrity_check: "skipped"` — honest, never "ok".
  `verify_fullgate` is untouched: it always calls `_fullgate_db_info` with
  the default, so a skipped-source manifest still gets a full restore-side
  check (integrity + page geometry + counts vs manifest).
- Sidecar wart, mechanism corrected by measurement: the progress log's open
  item assumed a second read-write connection created the source `-shm`/`-wal`
  sidecars. Measured on a WAL-mode fixture: a plain read-write open leaves
  NO sidecar (it cleans up on close), while a plain `mode=ro` open DOES
  create `-shm`/`-wal` — and, being read-only, cannot delete them on close.
  The real fix is `immutable=1` in the URI (`?mode=ro&immutable=1`): counts
  + integrity_check both work, zero files created. Safe here because the file
  is a finished `.backup` snapshot with no `-wal` sibling, and the `.zst`
  archives the main file only — manifest, archive and restore all see
  exactly the same bytes. Counts now run on the same connection via
  `_db_row_counts(db_path, conn=conn)` (borrowed, never closed there); the
  standalone caller in `create_backup_archive` is unchanged.

## Tests (`Fast_Fetch/test_backup_manager.py`, FullGateTests)
- `test_db_info_skip_integrity_records_skipped_and_keeps_counts`
- `test_db_info_leaves_no_sidecar_on_wal_db` (WAL-mode fixture, checkpointed,
  sidecars removed first; asserts only the `.db` remains)
- `test_manifest_records_skipped_integrity_with_flag`
- `test_main_fullgate_passes_skip_flag` (flag on → True, default → False)
- Local: `test_backup_manager.py` 36/36 (was 32/32, +4 new), py_compile clean.
  CI is the gate for the full 108-suite run.

## Next (same agent)
- Push branch, Backend CI green, then file 2 (`gate-backup-logos-20261114.db`)
  with the flag, oldest-first, all 6 verify steps, delete only after they
  pass. Re-check df + running jobs (sweep!) before starting; never overlap
  the 03:45 slim run, a gate, or a sweep. Then the ten `20261116` files.
- OCI second copy still blocked (needs a customer secret key — owner/console).
