#!/usr/bin/env python3
"""Nightly research sweep — host analysis from public sources only.

Probes every non-retired site in ``Fast_Fetch/domain_registry.json`` from
the runner's own egress (GitHub-hosted ubuntu, public internet) and records
what the registry got wrong: a base URL that moved, died, or rate-limits,
a sitemap endpoint that stopped answering, a mirror that is alive while
the canonical base is dead.

What this is NOT:
  - Not a gate. Dead sites are FINDINGS, not failures: the script exits 0
    when the sweep completes, however many sites are down. Only an internal
    error (registry unreadable, output unwritable) exits non-zero.
  - Not a resolver run. It never touches the live catalog, the Oracle VM,
    or any secret. No credentials exist in this job at all.
  - Not a crawl. One base request + one sitemap request + one API request
    per site at most (plus up to 2 mirrors, only when the base fails),
    paced ~1.2 s apart, 20 s timeout each, bounded by a global time
    budget (default 45 min). No response body is stored — only status,
    final URL, byte length and a sitemap sniff verdict.

Output: ``<out>/report.json`` (machine-readable) + ``<out>/SUMMARY.md``.

    python3 -S .github/scripts/research_sweep.py --out research-sweep/123
    python3 -S .github/scripts/research_sweep.py --limit 2 --time-budget-minutes 5

Stdlib only (urllib), same as the rest of the backend.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REGISTRY = ROOT / "Fast_Fetch" / "domain_registry.json"

#: A browser-shaped UA: several of these sites answer 403 to a bare
#: python-urllib UA while serving a real browser. We identify as Chrome
#: because the probe is a plain public GET, not a bypass of anything.
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
REQUEST_TIMEOUT = 20
SNIFF_BYTES = 65536
MAX_MIRRORS_ON_FAILURE = 2


class _RedirectRecorder(urllib.request.HTTPRedirectHandler):
    """Records every hop instead of silently following the chain."""

    def __init__(self) -> None:
        super().__init__()
        self.hops: list[str] = []

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        self.hops.append(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _probe(url: str, timeout: int = REQUEST_TIMEOUT) -> dict:
    """One GET. Returns status/final URL/hops/ms, or an error string.

    Reads at most SNIFF_BYTES of the body (enough to sniff a sitemap);
    the body itself is never stored or returned.
    """
    recorder = _RedirectRecorder()
    opener = urllib.request.build_opener(recorder)
    req = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT,
        "Accept": ACCEPT,
        "Accept-Language": "en-US,en;q=0.9",
    })
    started = time.monotonic()
    try:
        with opener.open(req, timeout=timeout) as resp:
            chunk = resp.read(SNIFF_BYTES + 1)
            return {
                "status": resp.status,
                "final_url": resp.geturl(),
                "hops": list(recorder.hops),
                "ms": round((time.monotonic() - started) * 1000),
                "bytes": len(chunk),
                "content_type": (resp.headers.get_content_type() or ""),
                "sniff": _sniff(chunk),
                "error": None,
            }
    except urllib.error.HTTPError as e:
        return {
            "status": e.code,
            "final_url": url,
            "hops": list(recorder.hops),
            "ms": round((time.monotonic() - started) * 1000),
            "bytes": 0,
            "content_type": "",
            "sniff": None,
            "error": f"HTTP {e.code}",
        }
    except Exception as e:  # timeout, DNS, TLS, reset — all one bucket
        return {
            "status": None,
            "final_url": url,
            "hops": list(recorder.hops),
            "ms": round((time.monotonic() - started) * 1000),
            "bytes": 0,
            "content_type": "",
            "sniff": None,
            "error": f"{type(e).__name__}: {str(e)[:120]}",
        }


def _sniff(chunk: bytes) -> str | None:
    """What the first bytes look like — XML sitemap, HTML, or other."""
    head = chunk[:2048].decode("utf-8", errors="ignore").lstrip()
    if "<urlset" in head or "<sitemapindex" in head:
        return "xml-sitemap"
    if head.startswith("<?xml"):
        return "xml"
    if head.lower().startswith("<!doctype html") or head.lower().startswith("<html"):
        return "html"
    if head.startswith("{") or head.startswith("["):
        return "json"
    return "other" if head else "empty"


def _host(url: str) -> str:
    return (urllib.parse.urlparse(url).hostname or "").lower()


def _probe_site(name: str, entry: dict, pace: float) -> dict:
    """Base + sitemap + API probes for one registry site, then its verdict."""
    base = entry.get("active_base_url") or ""
    result: dict = {"site": name, "registry_url": base,
                    "status": entry.get("status") or "unknown"}
    base_probe = _probe(base) if base else {
        "status": None, "final_url": "", "hops": [], "ms": 0,
        "bytes": 0, "content_type": "", "sniff": None,
        "error": "no active_base_url in registry",
    }
    result["base"] = base_probe
    time.sleep(pace)

    sitemap_ep = entry.get("sitemap_endpoint") or ""
    if sitemap_ep and base:
        result["sitemap_endpoint"] = sitemap_ep
        result["sitemap"] = _probe(urllib.parse.urljoin(base, sitemap_ep))
        time.sleep(pace)
    api_ep = entry.get("api_endpoint") or ""
    if api_ep and base:
        result["api"] = _probe(urllib.parse.urljoin(base, api_ep))
        time.sleep(pace)

    base_ok = base_probe["status"] == 200
    moved = base_ok and _host(base_probe["final_url"]) != _host(base)
    if base_probe["status"] == 429:
        verdict = "rate-limited"
    elif not base_ok:
        verdict = "down"
    elif moved:
        verdict = "moved"
    elif result.get("sitemap") and result["sitemap"]["status"] != 200:
        verdict = "sitemap-dead"
    else:
        verdict = "ok"

    # The base is dead: is any known mirror alive? That is the drift
    # signal the registry correction needs — the new canonical domain.
    mirrors_tried: list[dict] = []
    if verdict == "down":
        for mirror in (entry.get("known_mirrors") or [])[:MAX_MIRRORS_ON_FAILURE]:
            if mirror == base:
                continue
            m = _probe(mirror)
            mirrors_tried.append({"url": mirror, "status": m["status"],
                                  "final_url": m["final_url"],
                                  "error": m["error"]})
            time.sleep(pace)
            if m["status"] == 200:
                verdict = "mirror-alive"
                break
    result["mirrors_tried"] = mirrors_tried
    result["verdict"] = verdict
    return result


def _summary_md(report: dict) -> str:
    lines = [
        "# Research sweep — %s" % report["finished_at"],
        "",
        "Registry: `%s` (%d sites probed, %d skipped as retired%s)." % (
            report["registry"], report["probed"], report["skipped"],
            ", partial — time budget hit" if report["partial"] else ""),
        "",
        "## Verdicts",
        "",
    ]
    for verdict, count in sorted(report["verdict_counts"].items()):
        lines.append("- %s: %d" % (verdict, count))
    lines += ["", "## Needs attention (not ok)", ""]
    bad = [r for r in report["results"] if r["verdict"] != "ok"]
    if not bad:
        lines.append("None — every probed site answered 200 at its registry URL.")
    for r in bad:
        base = r["base"]
        lines.append("- **%s** — %s (registry `%s`, final `%s`, status %s%s)" % (
            r["site"], r["verdict"], r["registry_url"],
            base["final_url"] or "—",
            base["status"] if base["status"] is not None else "error",
            (", " + base["error"]) if base["error"] else ""))
        if r.get("sitemap") and r["sitemap"]["status"] != 200:
            lines.append("  - sitemap `%s` → status %s" % (
                r["site"] and (r.get("sitemap_endpoint") or ""),
                r["sitemap"]["status"]))
        for m in r["mirrors_tried"]:
            lines.append("  - mirror `%s` → status %s" % (m["url"], m["status"]))
    lines += ["",
              "Full machine-readable report: `report.json` in this artifact.",
              "A verdict here is research input for the next registry",
              "correction — it changes nothing by itself."]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    global REQUEST_TIMEOUT
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--registry", default=str(DEFAULT_REGISTRY))
    ap.add_argument("--out", required=True,
                    help="output directory for report.json + SUMMARY.md")
    ap.add_argument("--limit", type=int, default=0,
                    help="probe at most N sites (0 = all)")
    ap.add_argument("--time-budget-minutes", type=float, default=45)
    ap.add_argument("--pace-seconds", type=float, default=1.2)
    ap.add_argument("--timeout", type=int, default=REQUEST_TIMEOUT)
    args = ap.parse_args(argv)
    REQUEST_TIMEOUT = args.timeout

    try:
        registry = json.loads(Path(args.registry).read_text(encoding="utf-8"))
    except Exception as e:
        print(f"ERROR: cannot read registry {args.registry}: {e}", file=sys.stderr)
        return 2
    sites = registry.get("sites") or {}
    names = sorted(sites)
    todo = [n for n in names if (sites[n].get("status") or "") != "retired"]
    skipped = len(names) - len(todo)
    if args.limit:
        todo = todo[: args.limit]

    started = time.monotonic()
    budget_s = args.time_budget_minutes * 60
    results: list[dict] = []
    partial = False
    for name in todo:
        if time.monotonic() - started > budget_s:
            partial = True
            print(f"budget hit after {len(results)} sites — stopping gracefully")
            break
        results.append(_probe_site(name, sites[name], args.pace_seconds))
        r = results[-1]
        print(f"{name}: {r['verdict']} "
              f"(base {r['base']['status']}, {r['base']['ms']} ms)")

    counts: dict[str, int] = {}
    for r in results:
        counts[r["verdict"]] = counts.get(r["verdict"], 0) + 1
    report = {
        "finished_at": datetime.now(timezone.utc).isoformat(),
        "registry": str(args.registry),
        "probed": len(results),
        "skipped": skipped,
        "partial": partial,
        "elapsed_s": round(time.monotonic() - started, 1),
        "verdict_counts": counts,
        "results": results,
    }
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "report.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8")
    (out / "SUMMARY.md").write_text(_summary_md(report), encoding="utf-8")
    print(f"\n{len(results)} sites, {counts}, "
          f"{report['elapsed_s']} s → {out}/report.json + SUMMARY.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
