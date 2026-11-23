# Resolver-Maintainer Dataset — Phase 0 (dataset only, NO training)

Built 23 Nov 2026 from live main `d7322287` (read-only copies in `/tmp/rm-live/`).
No training run started — the title-cleaner run 5 holds the one Glimmer slot.

## Files

| file | size | contents |
|---|---|---|
| `pairs_raw.jsonl` | 757 KB | 371 `{job, input, target, source}` pairs |
| `sft_train.jsonl` | 792 KB | 315 Fireworks SFT examples |
| `sft_eval.jsonl` | 148 KB | 56 Fireworks SFT examples |
| `sft_stats.json` | 2.3 KB | counts, system prompts, split note |
| `extract_pairs.py` | 35 KB | extractor (read-only sources) |
| `build_sft.py` | 4.8 KB | SFT builder (twin of the title cleaner's) |
| `scrub_check.py` | 3.6 KB | scrub verification (must be CLEAN) |

## Pair counts per job (train + eval)

| job (final) | scoped jobs | train | eval | total |
|---|---|---|---|---|
| host-analysis | 1 + 2 merged | 206 | 36 | 242 |
| resolver-drafting | 4 (kept) | 82 | 15 | 97 |
| registry-drift | 3 + 5 merged | 27 | 5 | 32 |
| **total** | | **315** | **56** | **371** |

## Planning-rule decisions (measured, not guessed)

Every scoped job measured under ~1,000 pairs, so per the planning rule:

- **Job 1 (flow classification) + job 2 (failure diagnosis) → merged into
  host-analysis (242).** Both read host evidence and name what is happening;
  the merged output is flow type + failure class + confidence + classifier
  category/backend + evidence lines. Sources: sweep-doc sections 34, HAR
  summaries 30, probe-JSON sites 70, classifier-table rows 31,
  classifier-test URLs 72, coverage-map rows 5.
- **Job 3 (domain-move proposal) → merged into job 5 (registry correction)
  as registry-drift (32).** Same drift evidence, overlapping output (new
  canonical domain + registry patch). Sources: rotation-history 23,
  registry diffs 9 (registry-corrections branch, e055da78 reconcile,
  eb812ab4 hdtvb move, 835db843 9xmovies move).
- **Job 4 (resolver drafting) kept separate (97).** Code generation has no
  neighbour to merge into. Gold set: 51 unwrap functions (current_hosts 13
  + fast_fetch 14 + host_resolvers 24) + 46 mock-based test variants (capped
  at 5 per function; 104 test functions had no usable mock and were
  skipped). **Flagged thin for Phase 2** — the per-job functional eval
  (generated code must parse, pass the resolver test suite, resolve a live
  sample) is the real gate for this job.

## Eval split deviation

Scope specified a 500-example eval. The measured dataset is 371 pairs
total, so a 500-example eval would leave almost nothing to train on.
Used stratified 15% eval per job instead (seed 42): 56 eval / 315 train.
Recorded in `sft_stats.json` (`eval_note`).

## Scrub check (mandatory, CLEAN)

Command: `python3 scrub_check.py` (checks `sft_train.jsonl` +
`sft_eval.jsonl`).

Result: **CLEAN — 371 examples, 0 hits** (secret key=value, long
token-like runs ≥32 chars, mixed-case tokens ≥20 chars all zero).

Scrubbing rules (in `extract_pairs.py::scrub_text`): secret key=value
pairs, query-param values ≥32 chars (≥16 for token-like param names),
long token-like runs ≥32 chars, mixed-case token-like runs ≥20 chars
(upper+lower+digit). 133 `<redacted>` markers in `pairs_raw.jsonl`.
Legitimate identifiers are NOT redacted (verified: 0 pairs with a
redacted `def` name; `decode_greenmotors_payload` intact; the stale
mxdrop token `48AjKsiDaW4sYoHGdYvQiA` appears in 0 pairs).

**No raw `.har` file was ever opened.** Only the scrubbed
`*.summary.json` request sequences (method/url/status/mime) were used.
A scrubber bug found during review (regex lookaheads not constrained to
the token) was over-redacting function names; fixed to a Python filter
and the dataset was rebuilt.

## Sources that yielded nothing (with why)

- **Raw HAR bodies (32 `.har` files, ~200 MB): not used.** They contain
  ephemeral session tokens; the scrubbed `*.summary.json` files carry the
  flow and were used instead (30 pairs).
- **`~/workspace/resolver-survey-20261123.md` (201 lines): 0 pairs.**
  Per-tool coverage (yt-dlp / ResolveURL / Cloudstream), no per-host
  examples to extract.
- **`~/workspace/host-census-20261123.md` (918 lines): 0 direct pairs.**
  Its verdicts (cracked 36 / uncracked 4 / removed 10 / unknown 761)
  overlap the coverage map; used only as a cross-check.
- **Coverage map (811 rows): 5 pairs.** Most MISS notes are the generic
  "no branch in resolve_live_source dispatch" with no named cause, so
  they do not map to a failure class. The 5 that name a cause
  (egress-blocked 4, rate-limited 1) were kept.
- **Egress matrix (96 lines) and ID-swap docs: 0 direct pairs.** Used as
  mapping sources for the failure/flow ground-truth maps in
  `extract_pairs.py` (egress-blocked classes; HubCloud/FSL layer split).
- **Registry history beyond the 4 diffs: 0 extra pairs.** The 15 commits
  touching `domain_registry.json` mostly reword entries; the real moves
  are the 4 diffs used + the 40 rotation-history entries (23 transitions).

## Ground truth

Flow / failure labels come from hand-built maps (`FLOW_MAP`, `FAIL_MAP`)
in `extract_pairs.py`, derived from the verified sweep doc. Classifier
category/backend come from running the live `host_classifier.classify()`
on each URL (the tests pass on main, so this equals the expected value).

## Phase 2 notes

- Train only after title-cleaner run 5 finishes (one Glimmer slot).
- **Resume-from-checkpoint is now BUILT** (15:28 PKT 23 Nov, parent-verified):
  `train_sft.py` has `--resume` / `--resume-session` / `--checkpoint` /
  `--resume-step` / `--strict-resume`; procedure in
  `~/workspace/glimmer-training/RESUME.md`. It must be used for this run —
  at the ~2h reboot cadence a multi-day run cannot finish from scratch.
  (Whether a deleted session's DCP still LOADS is unproven until a real
  resume; it falls back to fresh unless `--strict-resume`.)
- Free Glimmer SFT window ends 30 Nov 2026; fallbacks per the scope doc
  §6 (Kaggle needs the user's ID verification; Colab free T4; paid
  Fireworks inside the $25 cap needs the user's go-ahead).
- The dataset is small (371 pairs). If Phase 2 underperforms, grow job 4
  first (more mock-based variants from the 104 skipped test functions,
  and the importer test files outside the scoped 8).
