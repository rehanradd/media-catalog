# Playback telemetry beacon — session log rotated out of CONTINUITY.md 2026-11-16

- Playback beacon (2026-11-16, main 790e0474): aggregates-only per-(host x net) counters in usage_host_quality, ridden on POST /api/v2/account/progress as playback_beacon; 30-day window, 0.9/day decay, n>=20 floor. No new endpoint. Ranking use comes later.
