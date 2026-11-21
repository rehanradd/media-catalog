# CONTINUITY.md rotation — 2026-11-21 (1fichier wiring lane)

The oldest session-history bullet rotated out of
`Fast_Fetch/CONTINUITY.md` verbatim, to bring the index back under
its 12 KB budget. Nothing was reworded or summarized.

- **2026-11-21 — bbupload resolver (getdownload ajax):** `_unwrap_bbupload` (bridge page → GET /ajax.php?action=getdownload&data=<slug> → superduperload.site/get/<token>; no cookie/wait/captcha; null FileName/filesize = dead_file; superduperload URL passes through). `classify_probe` now accepts video magic under ANY non-HTML mime (the file host answers image/png on genuine Matroska bytes) — real PNGs still unverified. Registered in STAGE1_BACKENDS, preference lists, classify_backend, fast_fetch tuples, host_classifier (bridge + direct-file rows, service labels; AND rule: bridge row caps B, resolved file mints A). Tests: test_current_hosts 33→51, test_probe_classify 18→23, test_host_classifier 75→77. Live proof (Oracle egress): resolve_copy ok on BG863REX + 1UPSHVRN (file, video/x-matroska, resumable=False) and on the minted superduperload URL; one transient html_shell on 1UPSHVRN, passed on re-run.
