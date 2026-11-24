# Media Catalog

A media-catalog and source-link service with a REST API and an Android client.
The backend aggregates public catalog metadata and source links into SQLite;
the app browses it and plays it. This file is the only reference doc in the
repo — its figures are the 2026-11-17 snapshot, verified against the live system then; several counts
have moved since, so re-measure before quoting a number.
Agent manual: `AGENTS.md`. Session logs: `Fast_Fetch/CONTINUITY.md`,
`App/docs/SESSION_HISTORY.md`, `Fast_Fetch/checkpoints/`,
`docs/checkpoints/`.

## Where the code lives (one-folder rule, 2026-11-17)

- **Working folder: `/home/ubuntu/radd-stream-work`** — the only place work
  happens. Clean clone of `main`. Editor link:
  `https://radd-stream.ccwu.cc/?folder=/home/ubuntu/radd-stream-work`
- **Live tree: `/opt/radd-stream`** — look, never edit. It moves only through
  the gate deploy below.
- **Repo:** `https://github.com/rehanradd/media-catalog`, one branch (`main`).
  `/tmp/muse-panel-push` and `/tmp/muse-r2-push` on the VM are Muse's
  machinery — never touch them.

## Domains (verified 2026-11-17)

- **Primary: `https://radd-stream.ccwu.cc`** — Cloudflare Free, proxied,
  SSL Full. Certificate expires 2027-02-14.
- **Panel / API host and backup: `https://raddstream.duckdns.org`**
- **IP fallback: `http://92.4.95.252`** (plain HTTP)

Source of truth for the app's server list is the blog payload — when a
domain changes, update the payload and this section together:

`{"status":"ok","primary":"https://radd-stream.ccwu.cc","backups":["https://raddstream.duckdns.org","http://92.4.95.252"],"v":"1.0.0"}`

## How it runs (live, verified 2026-11-17)

- **API:** `radd-stream.service` — `fast_fetch.py serve` on `127.0.0.1:8080`
  behind nginx. `GET /api/v1/posts` answers 200. Catalog: 225,517 posts.
- **Panel:** AdminV2 `run.py --base-dir ./data-live --port 18080
  --strict-host --background-jobs`, run from
  `/home/ubuntu/admin-v2-workspace-20261105` with six `RADD_*` env vars and a
  real `RADD_TOTP_KEY` (stored in the workspace `.panel-totp-key`, mode 600).
  The panel runs outside systemd — never `systemctl restart` the panel unit.
  `/admin` answers 303 (login redirect) when healthy.
- **nginx:** `/etc/nginx/sites-enabled/radd-stream` is a SEPARATE FILE, not a
  symlink to `sites-available`. Edit the enabled file, verify with
  `sudo nginx -T`. Gzip and posts caching are live.
- **Live DB:** `/home/ubuntu/radd-stream-data/merged_catalog_20261025.db`
  (same dir holds `user_state.db`). Site registry: 27 sites
  (`Fast_Fetch/domain_registry.json` is the source of truth for domains).
- **Watchdog:** `Fast_Fetch/radd_watchdog.py`, monitor/alert only. Timers:
  light every 15 min, medium hourly, heavy Wed 02:00.
- **Backups:** gate backups go to the private Hugging Face dataset
  `radddao/radd-stream-gate-backups`. All 11 done and verified 2026-11-17.
  The three newest gate backups stay on disk as rollback nets — never
  delete them.

## Build, test, push, deploy

- **CI is the gate.** `backend-ci.yml` (108 test steps), `ci.yml` (Android
  build + test); 6 workflows total. No local Gradle; APKs come from GitHub
  Actions only — debug APK from CI, signed release only on the owner's tag
  (never create, move, or delete a tag). Current signed release: `v1.0.4`.
- **Push route (bundle):** commit locally, `git bundle` the new commit(s),
  `scp` the bundle to the VM, fresh clone in `/tmp` on the VM (never the
  live tree), fetch the bundle, push with `~/bin/git-askpass.sh` and
  `GIT_TERMINAL_PROMPT=0` in an interactive bash, verify with
  `git ls-remote`. After every push, fast-forward both VM clones with
  `git reset --soft origin/main` (a plain fast-forward is blocked by staged
  files there — never touch them).
- **Gate deploy (the only way the live tree moves):** copy the live DB with
  `sqlite3 <live.db> ".backup <copy.db>"` (never plain `cp` on a WAL-mode
  DB) → verify integrity + counts → fetch (retry with `sudo` on failure —
  known transient) and fast-forward the live tree → restart
  `radd-stream.service` → live-test posts, search, remote-config. Never
  during a prefetch sweep; check free disk vs DB size first; assert
  `HEAD` equals the target after the merge.
- **Emulator testing** is paused by owner order (2026-11-17); the known fix
  is recorded in `AGENTS.md` (emulator options must include `-noaudio`).
- **Flags:** quality gate OFF; coins removed 2026-11-17; telemetry is
  aggregates-only.

## Architecture in brief

- **`Fast_Fetch/`** — Python backend, stdlib only. `fast_fetch.py` (engine,
  9,331 lines), `viewer.py` (API server, 8,180 lines), `redact.py` (edge
  redaction), `importers/` (26 site importers), `backup_manager.py`.
- **`App/`** — Android app in Kotlin/Compose (Home, Search, Player, Detail,
  My List, Downloads, Live TV, Settings). Room v8, Media3 1.3.1. Direct APK
  distribution — no Play Store.
- **`AdminV2/`** — operator panel (`server.py`, `pairing.py`, `services.py`,
  `store.py`). Its tests run in `backend-ci.yml`.
- **Key routes:** `/api/v1/posts?q=` (search — there is no bare
  `/api/v1/search` route), `/api/v1/app/remote-config`, `/api/v2/play`,
  `/api/v2/works`. Press a stream handle with a plain no-follow GET:
  302 = plays, 402 = quality gate, 404 = no playable source for that handle.
- **Adult content:** stored and flagged (`is_18_plus`), never served on any
  public surface. The full law is in `AGENTS.md`.

## Key live facts (2026-11-17)

- Live tree and main agree; API and panel both healthy. Public catalog
  total ~203,300. Press-banking is live (second press answers in ~0.02 s).
- SSH to the VM dies at ~280 s — keep remote commands short, or redirect
  output to a VM-side file and fetch it separately.
