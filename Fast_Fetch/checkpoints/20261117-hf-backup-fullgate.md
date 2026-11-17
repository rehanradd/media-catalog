# Session 2026-11-17 — HF full-gate backup push `[HF-BACKUP-FULLGATE]`

Owner order 06:49 PKT 17 Nov (full delegation) + standing disk order: push the 12 held
full gate backups (~120 GB) to Hugging Face, oldest-first, delete locally only after
verification. Research: `~/workspace/hf-backup-step3-20261117.md`.

## Measured (Step 0, 06:53 PKT)

- Disk 193 GB, 184 GB used, **9.4 GB free**. 12 gate backups confirmed (5 in
  `/home/ubuntu/radd-stream-data/`, 7 in `/home/ubuntu/`). Oldest:
  `gate-backup-trending-cache-20261112.db` (9,738,272,768 B). Newest `f3ad1e46`
  (17 Nov 03:33) is the rollback net — kept until the next gate.
- A prefetch sweep was running (PID 3453986, `--once --limit 200`, since 06:31) —
  no heavy work started during it. Load 0.92, MemAvailable ~10 GB.
- HF_TOKEN present in the VM `bash -ic` env (37 chars, value never printed).
  Tools on VM: zstd, git-lfs 3.4.1, sqlite3, python3 3.12.3.
- `/var/log/journal` is 3.3 GB — `sudo journalctl --vacuum-size=500M` (+ apt clean)
  frees ~3.1 GB of regenerable logs for the first file's restore-test headroom.

## Step 1 — repo created (06:5x PKT)

- `radddao/radd-stream-gate-backups`, **Private**, id `6afbb3bdfaa7b3a919bf2188`.
- Runbook correction: `POST /api/datasets` is not a create endpoint (404
  "Cannot POST /api/datasets") — the create endpoint is
  `POST /api/repos/create` with `{"name", "type": "dataset",
  "organization": "radddao", "private": true}`. Verified private via
  authenticated GET (`.gitattributes` already present as the only sibling).

## Step 2 — `--full-gate` mode (branch `muse/hf-backup-fullgate-20261117`, from main `94a55b1f`)

`Fast_Fetch/backup_manager.py` (stdlib only), +331 lines:

- `create_fullgate_archive(db, out_dir)`: streams `zstd -3 -T0 --stdout` (no second
  10 GB copy), manifest JSON with source/.zst bytes + SHA-256, page_count /
  freelist_count / page_size, `PRAGMA integrity_check`, counts (posts, releases,
  download_sources, **cat_links** — `_db_row_counts` extended), gate commit parsed
  from the filename. Own space guard: free ≥ 6 GiB (not the slim path's 2× guard).
- `upload_to_huggingface` gains `*.zst` in `HF_LFS_PATTERNS` (a `.zst` as a plain
  git blob fails the push — the easy-to-miss step); `run_fullgate` confirms the
  upload against `hf_remote_listing()` before reporting success.
- `verify_fullgate(name, manifest, repo, work_dir)`: the six checks in order —
  HF listing size, fresh-download SHA-256, `zstd -t`, decompress to scratch +
  integrity ok, page_count + four counts equal the manifest — returns
  `(ok, report)`; deletes its own scratch on the success path only.
- **Revision (same branch, before first push): streaming verify.** The literal
  download-then-`zstd -t`-then-decompress sequence does NOT fit this disk:
  free 11.25 GiB vs download 2.5 + scratch 9.07 GiB — short by ~339 MB even at
  zero margin. `_stream_restore` now streams the HF download straight through
  `zstd -d` into the scratch db, hashing the compressed bytes on the way; the
  frame check is the decompressor's own validation (what `zstd -t` does, by
  decompressing to /dev/null). Same six properties checked, peak disk = the
  restored db only (9.07 GiB). Space guard now needs source bytes + 256 MiB.
  Tests updated to patch `_stream_restore`; local still 32/32.
- CLI: `--full-gate DB`, `--verify-full-gate NAME --manifest PATH`,
  `--repo ID`, `--fullgate-out DIR`. The normal slim path is untouched.
- Tests: 9 new in `Fast_Fetch/test_backup_manager.py` (already in the CI gate —
  no workflow change): db-info/counts/pragmas, gate-commit parse, manifest shape,
  space-guard refusal, LFS `*.zst` tracking, verify happy path, SHA/counts
  mismatch failures, main exit codes. Local: **32/32 OK** (23 pre-existing + 9 new).
  zstd is faked by a copy in tests (no zstd on the hatch VM); the real binary is
  exercised on the Oracle VM.

## Status / next

- Branch pushed, backend CI pending — **NOT merged** (report the branch + CI run
  id; the merge queue owns main).
- First file (`trending-cache-20261112`) not yet pushed: compress + upload + the
  6-step verify run detached (nohup + log; SSH dies at ~280 s), then delete the
  local `.db` + `.zst` only on a full pass. One file at a time, oldest-first,
  never during the 03:45 slim run, a gate, or a sweep. ~40 min upload/file,
  ~80 min with the restore download — 11 deletable files is a multi-night job.
- File-1 verify headroom: vacuum the journal + apt clean first (~3.1 GB), delete
  the local `.zst` after the upload, then the download (2.6 GiB) + scratch
  decompress (9.1 GiB) fits in the ~12.5 GiB free with ~0.8 GiB margin.
- Second copy (OCI Object Storage Always Free, 20 GB, newest ~7) still owed:
  needs a customer secret key via OCI CLI/console — check for an OCI CLI config
  for the main tenancy; if none exists, that step needs the owner/console.
- The 4 held pre-merge/pre-apply files (~25 GB) are NOT in this plan.
