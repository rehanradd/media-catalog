# Phase 2 item 8 host verdicts — 2026-11-28 (backend loop, second half)

Re-probe of the three plan-named link hosts (backend-complete-plan-20261128.md
item 8: vikingfile 25,622 rows 404; dropgalaxy 42,348 unreachable; mobilejsr
/view/ 85,142 rows sampled 404). All reads 28 Nov ~21:50–22:00 PKT from the
Oracle VM. Script: `~/workspace/probe-phase2-hosts.py` (read-only: counts +
newest/oldest `source_url` samples from the live DB, plain GET probes).
Log: `/tmp/phase2-probe.out` (VM). Follow-ups in the same session: spaced
re-probe with 3–4s delays, DNS checks via `getent` + `nslookup 8.8.8.8`,
and the parked-domain check (same file code on the active domain).

## Verdicts

| Host | Live rows (status) | Probe result (Oracle egress) | Verdict | Next step |
|---|---|---|---|---|
| vikingfile | 25,769 total: 2,809 active, 10,198 dead, 6 expired, 12,756 unverified | 12/12 active URLs (newest + oldest) return HTTP 200; 4/4 dead-row URLs return 404, matching their marking | **ALIVE — no action.** The plan's "25,622 rows 404" premise is wrong for the active set. The 10,198 dead rows are correctly dead link-level rot. | Sweep pass for the 12,756 unverified rows; no deletion, no remap |
| dropgalaxy | 42,346 total: 42,029 active, 205 expired, 112 unverified. By domain: dgdrive.pro 16,028, dropgalaxy.vip 17,158, dgdrive.site 9,043 | Mixed, all consistent with alive-but-hostile from datacenter egress: 200s on dropgalaxy.vip, 429s on dgdrive.site (rate limit), connection resets (bot filter / transient). **dgdrive.pro does NOT resolve anywhere** — SERVFAIL from 8.8.8.8 and no entry from the VM resolver. Parked-domain check: the code `xuiu7grondjf` returns **200 on dropgalaxy.vip** | **ALIVE — no deletion.** dgdrive.pro is a dead mirror domain; the files live on dropgalaxy.vip (migration, not death — the vCloud pattern). | **Remap owed (separate branch, gate + backup):** rewrite the 16,028 dgdrive.pro URLs to dropgalaxy.vip. Second-egress / residential check owed to prove playability from a non-datacenter IP. Do NOT delete |
| mobilejsr | 110,418 total: 0 active, 110,092 dead, 326 unverified | File URLs confirmed 404 on re-probe (3/4 sampled dead rows). The `/view/` page itself returns 200 — the redirect page loads, the file behind it is dead. That is NOT a live host | **Already fully dead in the table — no deletion action.** The sweep already did the verdict work; the rows stay as dead audit history (zero data loss: record dead links' status, never discard). | Sweep pass for the 326 unverified rows. The /view/ links are a redirect layer — the real file hosts are elsewhere; nothing here carries a playable file |

## What this closes and what it does not

- **No deletions.** None of the three hosts meets the delete bar: vikingfile is
  alive, dropgalaxy is alive on its current domains, mobilejsr's rows are
  already dead in the table (nothing live to remove). The crack-or-delete
  rule is satisfied by the verdicts + this evidence, not by removals.
- **The plan's numbers were stale.** vikingfile's "25,622 rows 404" and
  mobilejsr's "85,142 rows sampled 404" describe older sweep states; the live
  counts above are current. Do not quote the plan's figures as current.
- **Follow-ups owed (not in this commit):** (1) dropgalaxy dgdrive.pro →
  dropgalaxy.vip remap for 16,028 rows — own branch, gate with backup first.
  (2) Second-egress / residential playability check for dropgalaxy.
  (3) Sweep passes for the unverified rows (vikingfile 12,756, dropgalaxy
  112, mobilejsr 326). (4) Sitemap backlog + crawl parse_error triage
  (item 9) and the catalog-drought check (item 10) are still open.
