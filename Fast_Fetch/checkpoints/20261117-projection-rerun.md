# Projection re-run — 17 Nov 2026

## Verdict
**DONE, exit=0. Orphans 3,265,322 → 0.** Salaar's top candidate now presses
302 (was 404 in the 04:21 gate). Second title also 302. Gate-off flip verified
live as-served (quality_gate false). One anomaly: `GET /api/v2/works/73860`
detail shows 0 prints while the DB holds 8 releases + 23 links for that work —
stale detail-memo/snapshot artifact, self-heals at the memo TTL (~07:10 PKT);
the play route sees the fresh data (its top candidate pressed 302).

## Procedure (recorded, Nov 16 route — followed exactly)
In-place `catalog_v2.py project_db` on the live catalog, same invocation as the
Nov 16 re-run (plus `nice -n 5 ionice -c2 -n6 env RADD_PROJECT_PROGRESS=2000`,
from the Oct 30 queued command), launched detached via nohup so SSH drops
cannot kill it. Script: `/home/ubuntu/projection-run-20261117.sh`
(hatch copy `~/workspace/projection-run-20261117.sh`).
Command: `python3 -S catalog_v2.py /home/ubuntu/radd-stream-data/merged_catalog_20261025.db`
Log: `/tmp/projection-20261117.log` (VM).

## Times (PKT, 17 Nov)
- Start: 04:29:30. Projection PID 3426919, wrapper 3426917.
- Ledger read done: ~04:44 (862s) — `225484 posts -> 197084 works`.
- Pages/prints done: ~05:26 (3395s) — `1678296 prints, 4402713 links`.
- Finalized: 05:51 (4788s). Summary printed + `exit=0` by 06:04.
- Total: **5635.35s (~94 min)** — vs 2364s on Nov 16 (cold page cache + the
  initial nice/ionice throttling; see mid-run fix below).
- Pre-checks at launch: prefetch sweep inactive, load 1.01, disk 11G free,
  WAL 1.26 MB, posts 200 in 0.10s. Rollback net (untouched):
  `gate-backup-20261117-f3ad1e46.db` (10,158,940,160 B, integrity ok).

## Mid-run fix (04:56 PKT)
Rate had collapsed to ~400 works/min under `nice -n 5` + `ionice -c2 -n6`
(~30% CPU, D-state). Raised I/O priority live (`ionice -c2 -n0`, worked as
ubuntu) and CPU priority (`sudo -n renice -n 0`, worked) — rate recovered to
~5,000 works/min, then ~20,000/min on the small tail works. Lesson for the
next re-run: **do not nice/ionice the projection** — the box is idle in the
quiet window and the throttling nearly tripled the run. The Nov 16 run at
normal priority did it in 39 min.

## Disk during the run
- 11G free at launch → 6.3G by 04:42 (WAL 4.1G) → 5.9G 04:45–05:33 (WAL
  frozen at 4,528,015,992) → **lowest 4.6G at 05:38** (WAL 5.9G, FTS build) →
  8.5G at 05:42 (checkpoint) → 11G at 05:45 (WAL 18 MB) → 9.5G at 06:17.
- Abort threshold (free < 2.5G) was never approached after the 05:38 low.
  The WAL peak (~5.9G) is the number to plan headroom around: **a re-run
  needs ~6G of free disk beyond the DB size**.

## Counts: before → after
| | before | after |
|---|---|---|
| posts | 225,484 | 225,484 |
| releases | 1,987,330 | 1,987,330 |
| download_sources | 4,402,713 | 4,402,713 |
| cat_links | 4,139,909 | 4,074,237 (−65,672, fresh walk dedup) |
| cat_works | — | 197,084 |
| **orphan cat_links** | **3,265,322 (79%)** | **0** |
| catalog_rev | 10 | **11** |
| projected_at | 1794804713.19 (Nov 16 09:51) | 1794872605.02 (Nov 17 04:43) |

Projection summary (from the log): works 197,084; releases 1,678,296; links
4,402,713; works_safe 177,111; adult_works 19,973; releases_safe 1,534,818;
links_safe 3,616,754; episodes 207,772; seasons 210,896; parts 932;
identity_suspect 79,437; release_dups 309,034; ledger_release_mapped
1,987,330; unattributed_links 0; id_mapped 5,912,441. Links by state: active
1,738; dead 754; expired 819; unchecked 4,061,612; unknown 9,314.

**Note — cat ids remint every rebuild.** The gate's Salaar work id 73860 is
now "Naach Ga Ghuma (2024) Marathi"; the real Salaar (2023) is work **73644**
(39 prints). Any saved v2 work/release ids from before a projection are stale
by design — that is what `id_mapped` (5,912,441 rows this run) exists for.

## Verification (from the VM, after the run)
- Orphan query: `SELECT COUNT(*) FROM cat_links l LEFT JOIN download_sources
  d ON d.id=l.ledger_link_id WHERE d.id IS NULL` → **0**.
- integrity_check: launched detached 06:17 (PID 3450603, log
  `/tmp/integrity-20261117.log`) — **result: `ok`** (finished ~06:28, ~11 min).
  (First foreground attempt died on the known intermittent SSH exit-255 drop,
  not on a DB error; re-run detached per procedure.)
- Remote-config (`/api/v1/app/remote-config`): 200 in 0.11s,
  `feature_flags.quality_gate: false, coins: false`,
  `updated_at: 1794874800` — **the 05:20 panel flip is live as-served**.

## Salaar re-probe (true id 73644, "Salaar" 2023)
- `GET /api/v2/works/73644`: 200 in 0.04s, **39 prints, availability:
  available** (gate: 39 prints, 11 streamable — same shape, new ids).
- `POST /api/v2/play {"work_id": 73644}`: 200, playable true, reason null,
  chosen print **4K WEBRip 10-bit · H265 · Hindi + Telugu + Kannada + Tamil +
  Malayalam · ESubs** — the 4K print is now playable (gate OFF; in the 04:21
  gate it correctly answered `premium_quality_requires_vip`).
- **Press top stream candidate (link 3070998, cdn_direct, rank 1): 302 in
  11.82s** → `https://video-downloads.googleusercontent.com/...` — PLAYS.
  (Gate: top candidates pressed 404 in 0.08s.)

## Second sample (work 73860, Naach Ga Ghuma)
- `POST /api/v2/play`: 200, playable true, 4 candidates.
- **Press top candidate: 302 in 12.51s (cold) → same Google video-downloads
  host; repeat press 302 in 0.13s (press-banking working).**

## Third sample (work 61197, Sputnik 2020) — explains a 404 that is NOT the bug
- Detail: 10 prints, available. Play: 200, playable true — but
  `stream_candidates: 0` on its top three prints: every copy is
  download-only / cloud_page. The first probe pressed a download-only
  candidate's `stream_id` and got 404 in 39s — the API correctly refusing a
  non-stream handle, not a stale pointer. No action; the title is
  download-only until a streamable copy is ingested.

## Anomaly (record, not a blocker)
`GET /api/v2/works/73860` detail returns **0 prints, availability
unavailable**, while the DB holds 8 `cat_releases` + 23 `cat_links` for that
work — and the play route (same DB) finds 4 candidates and presses 302.
Salaar (73644, 39 prints) and Sputnik (61197, 10 prints) detail fine, so the
detail path is healthy in general. Most likely a detail-body memo entry built
from a mid-rebuild snapshot (the memo is keyed by revision vector; the
projection moved catalog_rev 10→11, but a build on a pooled connection
holding a mid-rebuild snapshot can memoize an empty body under the new key).
Self-heals at the 1h memo TTL (~07:10 PKT) — re-check then. No service
restart was done (forbidden by this task); if it persists past ~07:15, it
needs a code-level look at the memo key vs snapshot, not another re-run.

## Live-service spot-checks during the run (posts 200, from the VM)
04:35 0.14s · 04:42 0.20s · 04:45 0.41s · 04:49 0.51s · 04:54 0.12s ·
05:00 0.12s · 05:06 0.15s · 05:13 0.11s · 05:19 0.36s · 05:24 0.30s ·
05:29 0.10s · 05:33 0.12s · 05:38 0.51s · 05:42 0.25s · 05:45 0.67s ·
05:48 0.11s · 05:51 0.39s · 05:53 0.13s · 05:58 0.45s · 06:01 0.14s ·
06:04 0.35s. **Never down, never non-200.** The write lock blocked writes
only; reads served throughout. Load stayed 1.0–1.7.

## Still owed after this run
- integrity_check result (running, PID 3450603) — append here when it lands.
  **UPDATE 06:28: `ok` (log `/tmp/integrity-20261117.log`, ~11 min).**
- Re-check `works/73860` detail after ~07:10 (memo TTL expiry). **UPDATE
  06:28: still 0 prints / unavailable** (memo not yet expired, as expected —
  TTL runs to ~07:10). Posts 200 in 0.06s, disk 9.5G free at 06:28.
- **Durability:** orphans will recur after the next merge/re-scrape churn
  (same mechanism as this time: ~3.26M ids renumbered in ~18h). Options for
  later: re-run the projection after every merge cycle, or stop minting v2
  stream_ids from ledger ids (code change, out of scope here).
- Next re-run: run at normal priority (no nice/ionice) and keep ~6G free-disk
  headroom for the WAL peak.
