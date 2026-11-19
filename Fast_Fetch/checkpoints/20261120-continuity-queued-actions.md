# Queued quiet-window actions (rotated out of CONTINUITY.md 2026-11-20)

Moved verbatim from `Fast_Fetch/CONTINUITY.md` by Lane C (Phase C, 2026-11-20) to bring the index back under its 12,000 B ceiling; nothing reworded. Index item 7 points here.

## 🕒 Queued quiet-window actions (run only when `cat /proc/loadavg` 1-min is under 4)

| # | exact command | expected cost | why |
|---|---|---|---|
| 1 | `python3 -S Fast_Fetch/site_scout.py https://movibd.com --max-samples 20 --timeout 12 --retries 1` | ~26 requests to one host, minutes, serial at 1 in-flight per host | measured from this box 2026-10-30: `movibd.com` answered real HTML with a sitemap; `.online` is a parked stub, `.top` challenge-blocked (blueprints `movibd_blueprint.json`, `movibd_com_blueprint.json`) |
| 2 | new `Fast_Fetch/importers/import_movibd.py` + `test_movibd_import.py` writing to **`movibd.db` staging only** | unknown N posts; price = `N × 2 pages × 1.1 s / distinct_hosts` — never into `fast_fetch.db` or the merged catalog | repo rule 9 (per-site staging DB) |
| 3 | the adult-twin count against the live 6.4 GB catalog — **no command exists yet, writing it is the task** | a single `WHERE is_18_plus=1` count measured 26.46 s by qoder4 2026-10-30, so a scan of that size is a load-under-4-only action | the re-key residual in item 5 |
| 4 | the 3.7 M-link health sweep | ≈ 7 days thread-bound (`docs/LINK_HEALTH_SWEEP.md` §1) | not scheduled; design doc only |
| 5 | `nice -n 19 python3 -S Fast_Fetch/importers/import_humtv.py sync --db /home/ubuntu/radd-stream-data/humtv.db` then `nice -n 19 python3 -S Fast_Fetch/importers/import_humtv.py crawl --db /home/ubuntu/radd-stream-data/humtv.db --workers 1 --delay 0.5` | sync ≈ 1–2 min (57 REST pages); crawl ≈ 2.5–4.5 h serial for 5,625 pages (`workers != 1` refused in code); verify `--limit 200` ≈ 5 min | **Start only at load1 < 3.5; stop (Ctrl-C) at 4.0.** Self-stops too: MemAvailable < 2,500 MB or SwapFree < 800 MB refuses, a 429 pauses and the row stays queued, Ctrl-C leaves the rest `scraped=0` and resumable. Staging vault only |
| 6 | `nice -n 19 python3 -S Fast_Fetch/importers/test_dub_collision_census.py --census > /tmp/q2_census_20261031.json` | 1–3 min estimated; nearest comparable measured 12.0 s at load 0.91 | fills the three headline numbers in the census checkpoint; the script exits 2 above `LOAD_CEILING = 4.0` |

