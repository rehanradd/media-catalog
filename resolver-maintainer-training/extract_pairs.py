#!/usr/bin/env python3
"""Extract (job, input, target) pairs for the resolver-maintainer model.

Phase 0 dataset build. Read-only: sources are the live tree (main d7322287,
copied to /tmp/rm-live/) and workspace sweep/census/HAR-summary docs.
NO raw .har file is ever opened (they contain ephemeral session tokens);
only the scrubbed *.summary.json request sequences are used.

Jobs (after the planning rule: every job measured under ~1,000 pairs):
  host-analysis   = job 1 (flow classification) MERGED with job 2 (failure
                    diagnosis). Both read host evidence and name what is
                    happening; the merged output is flow + failure + confidence
                    + classifier category/backend + evidence lines.
  resolver-drafting = job 4 (unchanged). 51 gold _unwrap_* / unwrap_* functions
                    (current_hosts 13 + fast_fetch 14 + host_resolvers 24) plus
                    mock-based test variants. Kept separate: code generation
                    has no neighbour to merge into; flagged thin for Phase 2.
  registry-drift  = job 3 (domain-move proposal) MERGED into job 5 (registry
                    correction). Same drift evidence, overlapping output
                    (new canonical domain + registry patch).

Flow / failure ground truth comes from hand-built maps (FLOW_MAP, FAIL_MAP)
derived from the verified sweep doc (~/workspace/host-api-sweep-20261123.md).
The maps are documented in this file; nothing is invented beyond them.

Scrubbing (mandatory): every input and target passes through scrub_text(),
which redacts long query values, token-like hex/base64 strings, cookies,
auth headers, and secret keywords. A separate scrub check greps the built
files afterwards and must come back clean.

Writes pairs_raw.jsonl: {"job", "input", "target", "source"}.
"""
import ast
import json
import re
import sys
import urllib.parse
from collections import Counter, defaultdict
from pathlib import Path

LIVE = Path("/tmp/rm-live")
WS = Path.home() / "workspace"
OUT = Path(__file__).resolve().parent / "pairs_raw.jsonl"

# ---------------------------------------------------------------------------
# Scrubbing
# ---------------------------------------------------------------------------
# Any query-param value >= 32 chars is a token/signature, not a name.
_QP_RE = re.compile(r"([?&][A-Za-z0-9_\-\.]{1,32}=)[^&\s\"']{32,}")
# Token-like query params (s/e/_t/token/sig/key/auth/exp/eid/dl_token): >= 16 chars.
_QP_TOKEN_RE = re.compile(
    r"([?&](?:s|e|_t|token|sig|signature|key|auth|exp|eid|dl_token|"
    r"v|id|h|t)=)[^&\s\"']{16,}")
# Long hex / base64url runs (>= 32 chars) anywhere.
_LONGTOKEN_RE = re.compile(r"\b[A-Za-z0-9_\-+/=]{32,}\b")
# Mixed-case token-like runs: >= 20 chars AND containing upper+lower+digit
# (stale per-file signatures in mock pages, e.g. 48AjKsiDaW4sYoHGdYvQiA).
# Implemented as a plain run + Python filter (regex lookaheads are NOT
# constrained to the token and would redact innocent identifiers).
_MIXED_RUN = re.compile(r"\b[A-Za-z0-9_\-]{20,}\b")


def _is_mixed_token(tok: str) -> bool:
    return (any(c.isupper() for c in tok)
            and any(c.islower() for c in tok)
            and any(c.isdigit() for c in tok))
# Secret-ish keywords and their values (key=value, "key": "value", key: value).
_SECRET_KV_RE = re.compile(
    r"(?i)((?:api[_-]?key|apikey|secret|token|passwd|password|auth|"
    r"cookie|set-cookie|cf_clearance|sessionid|session_id|access_token|"
    r"refresh_token|dl_token|authorization|x-api-key)\s*[:=]\s*"
    r"['\"]?)[^'\",\s;]+")
_SECRET_WORD_RE = re.compile(
    r"(?i)\b(api[_-]?key|secret|cf_clearance|set-cookie|authorization|"
    r"sessionid|session_id|access_token|refresh_token|dl_token)\b")

# Header names that must never appear with a value.
_BAD_HEADER_RE = re.compile(r"(?i)^(cookie|authorization|x-api-key|"
                            r"set-cookie|cf-clearance)\s*:")

# Short, legitimate URL path segments that must survive scrubbing.
_KEEP = re.compile(r"^[A-Za-z0-9_\-\.~/%]{1,31}$")


def scrub_text(s: str) -> str:
    """Redact tokens/cookies/auth material from a string. Idempotent."""
    if not s:
        return s
    # 1. Secret key=value pairs first (before long-token pass mangles them).
    s = _SECRET_KV_RE.sub(r"\1<redacted>", s)
    # 2. Long query-param values, and token-like params at >= 16 chars.
    s = _QP_RE.sub(r"\1<redacted>", s)
    s = _QP_TOKEN_RE.sub(r"\1<redacted>", s)
    # 3. Long token-like runs, except short path segments and known hosts.
    def _repl(m):
        tok = m.group(0)
        if _KEEP.match(tok):
            return tok
        # Keep version-ish / date-ish tokens and plain words.
        if re.fullmatch(r"[0-9\.\-]+", tok):
            return tok
        return "<redacted>"
    s = _LONGTOKEN_RE.sub(_repl, s)
    # 3b. Mixed-case token-like runs (>= 20 chars, upper+lower+digit).
    s = _MIXED_RUN.sub(
        lambda m: "<redacted>" if _is_mixed_token(m.group(0)) else m.group(0),
        s)
    # 4. Bare secret keywords (no value attached) -> still flag by removal.
    s = _SECRET_WORD_RE.sub("<redacted>", s)
    return s


def scrub_url(u: str) -> str:
    """Scrub a URL: redact long query values, keep scheme/host/path shape."""
    if not u:
        return u
    try:
        p = urllib.parse.urlsplit(u)
    except ValueError:
        return scrub_text(u)
    q = urllib.parse.parse_qsl(p.query, keep_blank_values=True)
    q2 = [(k, v if len(v) < 32 else "<redacted>") for k, v in q]
    query = urllib.parse.urlencode(q2)
    path = scrub_text(p.path)
    return urllib.parse.urlunsplit((p.scheme, p.netloc, path, query, ""))


def host_of(url: str) -> str:
    try:
        return (urllib.parse.urlsplit(str(url or "")).hostname or "").lower()
    except ValueError:
        return ""


def trunc(s: str, n: int) -> str:
    s = (s or "").strip()
    return s if len(s) <= n else s[:n] + " [...]"

# ---------------------------------------------------------------------------
# Hand-built ground-truth maps (from the verified sweep doc).
# Each entry: host key -> (flow type, failure class, confidence).
# Flow types: direct-link-in-HTML, page's-own-API-call, JS-minted signed URL,
#   form-post, redirect-chain, challenge-page.
# Failure classes: none, domain-moved, flow-changed, challenge-page,
#   host-dead, rate-limited, egress-blocked.
# ---------------------------------------------------------------------------
FLOW_MAP = {
    # hosts (current_hosts / host_resolvers / fast_fetch unwrap targets)
    "hubcloud": ("page's-own-API-call", "none", 0.9),
    "hubcdn": ("redirect-chain", "none", 0.9),
    "pixeldrain": ("direct-link-in-HTML", "none", 0.95),
    "gofile": ("page's-own-API-call", "none", 0.9),
    "gdflix": ("direct-link-in-HTML", "egress-blocked", 0.85),
    "filepress": ("page's-own-API-call", "challenge-page", 0.9),
    "fastdl": ("redirect-chain", "none", 0.95),
    "fastdl-beer": ("redirect-chain", "none", 0.95),
    "vcloud": ("redirect-chain", "none", 0.9),
    "streamtape": ("page's-own-API-call", "none", 0.9),
    "vidhide": ("page's-own-API-call", "none", 0.9),
    "mixdrop": ("page's-own-API-call", "none", 0.85),
    "dood": ("page's-own-API-call", "none", 0.85),
    "streamwish": ("redirect-chain", "flow-changed", 0.9),
    "filemoon": ("page's-own-API-call", "none", 0.85),
    "hubdrive": ("redirect-chain", "flow-changed", 0.8),
    "mega": ("page's-own-API-call", "none", 0.9),
    "multiup": ("direct-link-in-HTML", "host-dead", 0.7),
    "1fichier": ("form-post", "egress-blocked", 0.95),
    "greenmotors": ("JS-minted signed URL", "none", 0.9),
    "vegadrive": ("direct-link-in-HTML", "none", 0.9),
    "direct_cloud": ("page's-own-API-call", "egress-blocked", 0.95),
    "streamhg": ("page's-own-API-call", "none", 0.95),
    "iqsmart": ("page's-own-API-call", "none", 0.95),
    "vidrift": ("page's-own-API-call", "none", 0.95),
    "vidbolt": ("page's-own-API-call", "egress-blocked", 0.9),
    "vidly": ("page's-own-API-call", "challenge-page", 0.6),
    "filmu": ("page's-own-API-call", "challenge-page", 0.6),
    "vibuxer": ("page's-own-API-call", "none", 0.9),
    "modiplay": ("page's-own-API-call", "none", 0.9),
    "mobilejsr": ("direct-link-in-HTML", "host-dead", 0.95),
    "linkszilla": ("redirect-chain", "host-dead", 0.95),
    "uploadflix": ("direct-link-in-HTML", "host-dead", 0.95),
    "1xplayer": ("page's-own-API-call", "host-dead", 0.95),
    "voe": ("page's-own-API-call", "host-dead", 0.95),
    "clicknupload": ("direct-link-in-HTML", "flow-changed", 0.8),
    "jiofiles": ("direct-link-in-HTML", "host-dead", 0.8),
    "megaup": ("direct-link-in-HTML", "flow-changed", 0.85),
    "cineverse": ("page's-own-API-call", "none", 0.9),
    "doodstream": ("page's-own-API-call", "none", 0.85),
    "dood": ("page's-own-API-call", "none", 0.85),
    "gd-mirror": ("page's-own-API-call", "none", 0.95),
    "vidout": ("page's-own-API-call", "none", 0.9),
    "vidcore": ("page's-own-API-call", "challenge-page", 0.6),
    "mediafire": ("direct-link-in-HTML", "none", 0.8),
    "mp4moviez": ("direct-link-in-HTML", "none", 0.85),
    # sites (importer listing/extraction path)
    "7starhd": ("page's-own-API-call", "none", 0.9),
    "9xmovies": ("page's-own-API-call", "domain-moved", 0.95),
    "bolly4u": ("page's-own-API-call", "domain-moved", 0.95),
    "bollyflix": ("direct-link-in-HTML", "challenge-page", 0.95),
    "animeworld": ("page's-own-API-call", "flow-changed", 0.9),
    "castle": ("direct-link-in-HTML", "none", 0.85),
    "desicinemas": ("direct-link-in-HTML", "none", 0.85),
    "dramadog": ("page's-own-API-call", "none", 0.9),
    "filmyzilla": ("direct-link-in-HTML", "none", 0.85),
    "hdhub4u": ("direct-link-in-HTML", "domain-moved", 0.9),
    "hindianimeszone": ("page's-own-API-call", "none", 0.9),
    "hindidubbedmovies": ("direct-link-in-HTML", "none", 0.85),
    "hindilinks4u": ("direct-link-in-HTML", "none", 0.85),
    "katmoviehd": ("direct-link-in-HTML", "none", 0.85),
    "megacartoons": ("direct-link-in-HTML", "none", 0.9),
    "missav": ("direct-link-in-HTML", "egress-blocked", 0.8),
    "moviehax": ("direct-link-in-HTML", "none", 0.8),
    "moviesdrive": ("page's-own-API-call", "none", 0.9),
    "moviezguru": ("page's-own-API-call", "none", 0.9),
    "multimovies": ("page's-own-API-call", "none", 0.9),
    "rareanimes": ("page's-own-API-call", "none", 0.9),
    "rogmovies": ("page's-own-API-call", "none", 0.9),
    "toonworld4all": ("page's-own-API-call", "none", 0.8),
    "vegamovies": ("page's-own-API-call", "none", 0.9),
    "xprimehub": ("page's-own-API-call", "none", 0.9),
    "javmost": ("direct-link-in-HTML", "none", 0.9),
    "10xflix": ("page's-own-API-call", "domain-moved", 0.95),
    "harpalgeo": ("direct-link-in-HTML", "none", 0.9),
    "hdtvb": ("page's-own-API-call", "domain-moved", 1.0),
    "movienestbd": ("page's-own-API-call", "none", 0.9),
    "ssrmovies": ("page's-own-API-call", "none", 0.9),
    "themoviebox": ("page's-own-API-call", "none", 0.9),
    "world4ufree": ("page's-own-API-call", "domain-moved", 0.95),
}

# Coverage-map note -> failure class (keyword mapping, measured in the run).
FAIL_KEYWORDS = [
    (("cloudflare", "challenge", "turnstile", "just a moment"), "challenge-page"),
    (("ip locked", "egress", "warp-only", "tor-only", "tor ", "blocked from oracle"), "egress-blocked"),
    (("rate limit", "429", "limiter"), "rate-limited"),
    (("moved", "redirect"), "domain-moved"),
    (("flow changed", "changed since", "flow-changed"), "flow-changed"),
    (("dead",), "host-dead"),
]

# ---------------------------------------------------------------------------
# Classifier (ground truth for category/backend) — run the live module.
# ---------------------------------------------------------------------------
sys.path.insert(0, str(LIVE))
import host_classifier  # noqa: E402


def classify_evidence(url: str = "", service: str = ""):
    try:
        c = host_classifier.classify(url=url, service=service)
        return c.category, c.backend
    except Exception:
        return "unknown", "unknown"


def analysis_target(host_key, flow, failure, conf, url="", service="",
                    evidence_lines=None):
    cat, backend = classify_evidence(url=url, service=service or host_key)
    ev = "\n".join("- " + trunc(e, 200) for e in (evidence_lines or [])[:6])
    return (
        f"flow: {flow}\n"
        f"failure: {failure}\n"
        f"confidence: {conf}\n"
        f"category: {cat}\n"
        f"backend: {backend}\n"
        f"evidence:\n{ev}"
    )


def norm(s):
    s = (s or "").lower()
    s = re.sub(r"[^a-z0-9 ]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()

# ---------------------------------------------------------------------------
# Extractors
# ---------------------------------------------------------------------------
def extract_sweep_sections(pairs, stats):
    """One host-analysis pair per sweep-doc ### section whose host is mapped."""
    text = (WS / "host-api-sweep-20261123.md").read_text(encoding="utf-8")
    sections = re.split(r"^### ", text, flags=re.M)[1:]
    n = 0
    for sec in sections:
        head, _, body = sec.partition("\n")
        name = head.strip().split(" — ")[0].split(" - ")[0].strip()
        key = name.lower().replace(" ", "").replace(".", "")
        # heading names: GREENMOTORS, VIDDRIFT, "HDTVB -> HDBOSS.IN", etc.
        cand = None
        for k in FLOW_MAP:
            if k.replace("_", "").replace("-", "") in key or \
                    key in k.replace("_", "").replace("-", ""):
                cand = k
                break
        if cand is None:
            # try first word of heading
            first = name.split()[0].lower().strip(",")
            cand = first if first in FLOW_MAP else None
        if cand is None:
            stats["sweep_sections_unmapped"] += 1
            continue
        flow, failure, conf = FLOW_MAP[cand]
        ev = [l.strip("- ").strip() for l in body.splitlines()
              if l.strip().startswith("-")][:4]
        inp = (f"Host: {cand}\n"
               f"Sweep evidence (2026-11-23):\n{trunc(body.strip(), 1500)}")
        tgt = analysis_target(cand, flow, failure, conf,
                              service=cand, evidence_lines=ev)
        pairs.append({"job": "host-analysis", "input": scrub_text(inp),
                      "target": scrub_text(tgt), "source": "sweep-doc"})
        n += 1
    stats["sweep_sections"] = n


def extract_har_summaries(pairs, stats):
    """One host-analysis pair per HAR summary (scrubbed request sequence)."""
    summaries = sorted((WS / "host-har-20261123" / "vm-har").glob("*.summary.json"))
    n = 0
    for f in summaries:
        try:
            d = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            stats["har_summary_unreadable"] += 1
            continue
        entry = d.get("entry_url", "")
        host = host_of(entry)
        key = None
        for k in FLOW_MAP:
            if k.replace("_", "") in host.replace(".", "").replace("-", "") or \
                    host.startswith(k.replace("_", "-")):
                key = k
                break
        if key is None:
            # fall back to the job name (e.g. fastdl-beer-desktop)
            job = str(d.get("job", "")).split("-")[0]
            key = job if job in FLOW_MAP else None
        if key is None:
            stats["har_summary_unmapped"] += 1
            continue
        flow, failure, conf = FLOW_MAP[key]
        reqs = d.get("requests", [])[:12]
        lines = []
        for r in reqs:
            u = scrub_url(str(r.get("url", "")))
            lines.append(f"{r.get('method', 'GET')} {u} -> "
                         f"{r.get('status')} {r.get('mime', '')}")
        inp = (f"Host: {key}\n"
               f"Browser flow (HAR summary, entry {scrub_url(entry)}):\n"
               + "\n".join(lines))
        tgt = analysis_target(key, flow, failure, conf, url=entry,
                              evidence_lines=lines[:4])
        pairs.append({"job": "host-analysis", "input": scrub_text(inp),
                      "target": scrub_text(tgt), "source": "har-summary"})
        n += 1
    stats["har_summaries"] = n


def extract_probe_jsons(pairs, stats):
    """One host-analysis pair per probe-JSON site record whose site is mapped."""
    n = 0
    for f in sorted((WS / "sweep-probes").glob("*.json")):
        try:
            d = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        if not isinstance(d, dict):
            continue
        for site, rec in d.items():
            key = site.lower()
            if key not in FLOW_MAP:
                continue
            if not isinstance(rec, dict):
                continue
            flow, failure, conf = FLOW_MAP[key]
            bits = []
            probes = rec.get("probes", {})
            if isinstance(probes, dict):
                for pname, pr in list(probes.items())[:6]:
                    if isinstance(pr, dict):
                        bits.append(f"{pname}: {pr.get('status')} "
                                    f"{pr.get('ctype', '')} "
                                    f"{pr.get('bytes', '')}B")
            for field in ("post_hosts", "post_iframes", "post_scripts",
                          "post_anchors_dl", "rendered_hosts",
                          "rendered_bridge_hosts", "video_hosts"):
                v = rec.get(field)
                if v:
                    bits.append(f"{field}: {trunc(str(v), 300)}")
            snippet = ""
            for pname, pr in (probes.items() if isinstance(probes, dict) else []):
                if isinstance(pr, dict) and pr.get("snippet"):
                    snippet = trunc(pr["snippet"], 600)
                    break
            inp = (f"Host: {key}\n"
                   f"Probe evidence ({f.name}):\n" + "\n".join(bits[:8]))
            if snippet:
                inp += f"\nPage excerpt (scrubbed):\n{snippet}"
            tgt = analysis_target(key, flow, failure, conf, service=key,
                                  evidence_lines=bits[:4])
            pairs.append({"job": "host-analysis", "input": scrub_text(inp),
                          "target": scrub_text(tgt), "source": "probe-json"})
            n += 1
    stats["probe_json_sites"] = n


def extract_classifier_tables(pairs, stats):
    """One host-analysis pair per classifier table row whose host is mapped."""
    src = (LIVE / "host_classifier.py").read_text(encoding="utf-8")
    n = 0
    for m in re.finditer(
            r'\(\s*"([^"]+)"\s*,\s*(CATEGORY_[A-Z_]+)\s*,\s*"([^"]+)"\s*\)', src):
        marker, cat_const, backend = m.groups()
        key = None
        for k in FLOW_MAP:
            if k.replace("_", "") in marker.replace(".", "").replace("-", ""):
                key = k
                break
        if key is None:
            continue
        flow, failure, conf = FLOW_MAP[key]
        cat = {"CATEGORY_DIRECT_FILE": "direct_file",
               "CATEGORY_HLS_EMBED": "hls_embed",
               "CATEGORY_BRIDGE": "bridge",
               "CATEGORY_UNKNOWN": "unknown"}.get(cat_const, "unknown")
        inp = (f"Host: {key}\nClassify this host marker: {marker}\n"
               f"Known resolver: {backend}")
        tgt = (f"flow: {flow}\nfailure: {failure}\nconfidence: {conf}\n"
               f"category: {cat}\nbackend: {backend}\n"
               f"evidence:\n- host table marker {marker} maps to {backend}")
        pairs.append({"job": "host-analysis", "input": scrub_text(inp),
                      "target": scrub_text(tgt), "source": "classifier-table"})
        n += 1
    stats["classifier_table_rows"] = n


def extract_classifier_tests(pairs, stats):
    """Host-analysis pairs from test_host_classifier.py URL literals.

    Ground truth = the live classifier's own output on each URL (the tests
    pass on main, so this equals the expected value).
    """
    path = LIVE / "test_host_classifier.py"
    tree = ast.parse(path.read_text(encoding="utf-8"))
    urls = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            v = node.value.strip()
            if v.startswith("http"):
                urls.add(v)
    n = 0
    for url in sorted(urls):
        host = host_of(url)
        key = None
        for k in FLOW_MAP:
            if k.replace("_", "") in host.replace(".", "").replace("-", ""):
                key = k
                break
        if key is None:
            continue
        flow, failure, conf = FLOW_MAP[key]
        cat, backend = classify_evidence(url=url)
        inp = f"Host: {key}\nClassify this link:\n{scrub_url(url)}"
        tgt = (f"flow: {flow}\nfailure: {failure}\nconfidence: {conf}\n"
               f"category: {cat}\nbackend: {backend}\n"
               f"evidence:\n- link host {host} resolves to {backend}")
        pairs.append({"job": "host-analysis", "input": scrub_text(inp),
                      "target": scrub_text(tgt), "source": "classifier-test"})
        n += 1
    stats["classifier_test_urls"] = n
    stats["classifier_test_urls_total"] = len(urls)


def extract_coverage_map(pairs, stats):
    """Host-analysis pairs from coverage-map rows whose note maps to a failure."""
    text = (WS / "sweep-probes" / "coverage-map.md").read_text(encoding="utf-8")
    table = text.split("## Full table", 1)[1]
    n = 0
    nrows = 0
    mapped = Counter()
    for line in table.splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 7 or not cells[1].replace(",", "").isdigit():
            continue
        nrows += 1
        service, nrows_s, _wu, _exp, _non, status, note = cells[:7]
        note_l = note.lower()
        fail = None
        for kws, cls in FAIL_KEYWORDS:
            if any(k in note_l for k in kws):
                fail = cls
                break
        if fail is None:
            continue
        key = service.lower()
        flow = FLOW_MAP.get(key, (None, None, None))[0]
        if flow is None:
            # try stripping variant suffixes (e.g. fastdl-beer -> fastdl)
            base = re.split(r"[-.]", key)[0]
            flow = FLOW_MAP.get(base, (None, None, None))[0]
            key = base if flow else None
        if flow is None:
            continue
        conf = 0.8 if status in ("MISS", "UNUSED", "GENERIC") else 0.7
        inp = (f"Host: {service}\nCoverage: {nrows_s} rows, status {status}\n"
               f"Resolver note: {trunc(note.strip(), 400)}")
        tgt = analysis_target(key, flow, fail, conf, service=service,
                              evidence_lines=[trunc(note.strip(), 200)])
        pairs.append({"job": "host-analysis", "input": scrub_text(inp),
                      "target": scrub_text(tgt), "source": "coverage-map"})
        mapped[fail] += 1
        n += 1
    stats["coverage_map_rows"] = nrows
    stats["coverage_map_mapped"] = n
    stats["coverage_map_by_failure"] = dict(mapped)

# ---------------------------------------------------------------------------
# Job 4 — resolver drafting
# ---------------------------------------------------------------------------
def _func_source(path: Path, name: str):
    """Return (source, docstring) of a top-level function by name."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) \
                and node.name == name:
            src = ast.get_source_segment(path.read_text(encoding="utf-8"), node)
            return src, (ast.get_docstring(node) or "")
    return None, ""


def _gold_functions():
    """All unwrap functions across the three resolver modules."""
    gold = []  # (host, func_name, module, source)
    specs = [
        (LIVE / "current_hosts.py", r"^_unwrap_([a-z0-9_]+)$"),
        (LIVE / "fast_fetch.py", r"^unwrap_([a-z0-9_]+)$"),
        (LIVE / "host_resolvers_importers.py", r"^unwrap_([a-z0-9_]+)$"),
    ]
    for path, pat in specs:
        text = path.read_text(encoding="utf-8")
        tree = ast.parse(text)
        for node in tree.body:
            if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            m = re.match(pat, node.name)
            if not m:
                continue
            src = ast.get_source_segment(text, node)
            gold.append((m.group(1), node.name, path.name, src))
    return gold


def _sweep_flow_text(host: str) -> str:
    """Flow description for a host from the sweep doc (first matching lines)."""
    text = (WS / "host-api-sweep-20261123.md").read_text(encoding="utf-8")
    hits = []
    for line in text.splitlines():
        if host.lower() in line.lower() and len(line.strip()) > 40:
            hits.append(line.strip())
        if len(hits) >= 3:
            break
    return "\n".join(hits)[:1200]


def extract_gold_drafting(pairs, stats):
    """One drafting pair per gold unwrap function."""
    gold = _gold_functions()
    stats["gold_functions"] = len(gold)
    # Example patterns rotate deterministically (never the target itself).
    examples = [g for g in gold if len(g[3]) < 4000]
    n = 0
    for i, (host, fname, module, src) in enumerate(gold):
        ex = examples[(i + 1) % len(examples)]
        while ex[1] == fname:
            ex = examples[(examples.index(ex) + 1) % len(examples)]
        flow_text = _sweep_flow_text(host) or \
            f"Host {host}: resolver exists in {module}; see the example pattern."
        inp = (f"Host: {host}\n"
               f"Flow description:\n{trunc(flow_text, 1200)}\n"
               f"Existing unwrap pattern (style reference only, "
               f"different host — do not copy its host logic):\n"
               f"```python\n{trunc(ex[3], 2500)}\n```\n"
               f"Draft the {fname} function and a test for it, "
               f"in the repo's style (stdlib only, fetch injected, "
               f"returns (backend, candidates, failure) or the module's "
               f"established tuple).")
        tgt = f"```python\n{src}\n```"
        pairs.append({"job": "resolver-drafting", "input": scrub_text(inp),
                      "target": scrub_text(tgt), "source": "gold-function"})
        n += 1
    stats["gold_drafting_pairs"] = n


def extract_test_variant_drafting(pairs, stats):
    """Drafting pairs from mock-based unwrap tests (one per test function).

    Input = the test's longest string literal (the mock page, scrubbed) +
    flow description + a style pattern. Target = the gold function it tests.
    Capped at 5 variants per function to avoid near-duplicate flooding.
    """
    test_files = [
        LIVE / "test_current_hosts.py", LIVE / "test_host_resolvers.py",
        LIVE / "test_gdflix_unwrap.py", LIVE / "test_hubcdn_unwrap.py",
        LIVE / "test_multiup_unwrap.py", LIVE / "test_truelink_1fichier.py",
        LIVE / "test_clearance_loop.py",
    ]
    gold = {fname: (host, src) for host, fname, _m, src in _gold_functions()}
    # also map bare unwrap names (fast_fetch / host_resolvers use no underscore)
    per_fn = Counter()
    n = 0
    skipped_no_mock = 0
    for path in test_files:
        text = path.read_text(encoding="utf-8")
        tree = ast.parse(text)
        # Module-level string constants are the mock pages (PACKED_PAGE, ...).
        consts = {}
        for node in tree.body:
            if isinstance(node, ast.Assign) and len(node.targets) == 1 \
                    and isinstance(node.targets[0], ast.Name) \
                    and isinstance(node.value, ast.Constant) \
                    and isinstance(node.value.value, str):
                consts[node.targets[0].id] = node.value.value
        for node in tree.body:
            if not isinstance(node, ast.ClassDef):
                continue
            for fn in node.body:
                if not isinstance(fn, ast.FunctionDef) \
                        or not fn.name.startswith("test_"):
                    continue
                # which unwrap fn does this test call?
                called = set()
                lits = []
                for sub in ast.walk(fn):
                    if isinstance(sub, ast.Name) and sub.id in gold:
                        called.add(sub.id)
                    if isinstance(sub, ast.Attribute) and sub.attr in gold:
                        called.add(sub.attr)
                    if isinstance(sub, ast.Name) and sub.id in consts:
                        lits.append(consts[sub.id])
                    if isinstance(sub, ast.Constant) \
                            and isinstance(sub.value, str):
                        lits.append(sub.value)
                if len(called) != 1:
                    continue
                fname = next(iter(called))
                if per_fn[fname] >= 5:
                    continue
                mocks = [l for l in lits if len(l) >= 60]
                if not mocks:
                    skipped_no_mock += 1
                    continue
                host, src = gold[fname]
                mock = max(mocks, key=len)
                flow_text = _sweep_flow_text(host)
                inp = (f"Host: {host}\n"
                       f"Flow description:\n{trunc(flow_text, 800)}\n"
                       f"Mock page the test serves (scrubbed excerpt):\n"
                       f"{trunc(mock, 1200)}\n"
                       f"Draft the {fname} function and a test for it, "
                       f"in the repo's style (stdlib only, fetch injected).")
                tgt = f"```python\n{src}\n```"
                pairs.append({"job": "resolver-drafting",
                              "input": scrub_text(inp),
                              "target": scrub_text(tgt),
                              "source": "test-variant"})
                per_fn[fname] += 1
                n += 1
    stats["test_variant_pairs"] = n
    stats["test_variant_no_mock_skipped"] = skipped_no_mock
    stats["test_variant_per_fn"] = dict(per_fn)


# ---------------------------------------------------------------------------
# Registry drift (merged job 3 + job 5)
# ---------------------------------------------------------------------------
def extract_registry_drift(pairs, stats):
    """One pair per real domain move: rotation history + registry diffs."""
    reg = json.loads((LIVE / "domain_registry.json").read_text(encoding="utf-8"))
    sites = reg["sites"]
    seen = set()
    n = 0

    def emit(site, old, new, evidence, source):
        nonlocal n
        if (site, new) in seen or not old or not new or old == new:
            return
        seen.add((site, new))
        entry = sites.get(site, {})
        mirrors = entry.get("known_mirrors", [])
        inp = (f"Site: {site}\n"
               f"Registry entry before the move:\n"
               f"  active_base_url: {old}\n"
               f"  status: {entry.get('status', '')}\n"
               f"  api_type: {entry.get('api_type', '')}\n"
               f"  known_mirrors: {', '.join(mirrors[:6])}\n"
               f"Drift evidence:\n{trunc(evidence, 800)}\n"
               f"Propose the new canonical domain and the "
               f"domain_registry.json patch for this site.")
        patch = {
            "active_base_url": new,
            "rotation_history_append": {
                "domain": old, "seen_until": "see evidence"},
            "status": "active",
        }
        tgt = (f"new canonical domain: {new}\n"
               f"patch (domain_registry.json, site \"{site}\"):\n"
               f"{json.dumps(patch, indent=2)}")
        pairs.append({"job": "registry-drift", "input": scrub_text(inp),
                      "target": scrub_text(tgt), "source": source})
        n += 1

    # (a) rotation_history entries (old -> new, in order per site)
    for site, entry in sites.items():
        hist = entry.get("rotation_history", [])
        for j in range(len(hist) - 1):
            old = hist[j].get("domain", "")
            new = hist[j + 1].get("domain", "")
            ev = (f"rotation_history: {old} seen until "
                  f"{hist[j].get('seen_until', '?')} -> {new} seen from "
                  f"{hist[j + 1].get('seen_from', '?')}; "
                  f"tld_history: {', '.join(entry.get('tld_history', [])[:8])}")
            emit(site, old, new, ev, "rotation-history")
    stats["rotation_pairs"] = n

    # (b) registry diffs (real commits / branch). Track the current site
    # from the JSON key lines (site names are the 33 known registry keys);
    # a removed active_base_url followed by an added one for the same site
    # is a move. (git show output: context lines carry a leading space,
    # JSON is 2-space indented, so indent is not a reliable signal.)
    for diff_file in ("rm-regcorr.diff", "rm-reconcile.diff",
                      "rm-hubcdn.diff", "rm-9x.diff"):
        p = LIVE / diff_file
        if not p.exists():
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        cur_site = None
        old_url = new_url = None
        for line in text.splitlines():
            m = re.match(r"^[ +-]? *\"([A-Za-z0-9_\-]+)\":\s*\{\s*$", line)
            if m and m.group(1) in sites:
                cur_site = m.group(1)
                continue
            m = re.match(r"^-\s*\"active_base_url\":\s*\"([^\"]+)\"", line)
            if m:
                old_url = m.group(1)
                continue
            m = re.match(r"^\+\s*\"active_base_url\":\s*\"([^\"]+)\"", line)
            if m:
                new_url = m.group(1)
            if old_url and new_url and cur_site:
                ev = (f"registry diff {diff_file}: active_base_url "
                      f"{old_url} -> {new_url}")
                emit(cur_site, old_url, new_url, ev, "registry-diff")
                old_url = new_url = None
    stats["registry_drift_total"] = n


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    pairs = []
    stats = defaultdict(int)
    extract_sweep_sections(pairs, stats)
    extract_har_summaries(pairs, stats)
    extract_probe_jsons(pairs, stats)
    extract_classifier_tables(pairs, stats)
    extract_classifier_tests(pairs, stats)
    extract_coverage_map(pairs, stats)
    extract_gold_drafting(pairs, stats)
    extract_test_variant_drafting(pairs, stats)
    extract_registry_drift(pairs, stats)

    # Dedupe by (job, full normalized input, full normalized target).
    # (Truncating the key collapses distinct test variants of one host,
    # whose inputs share a flow-description prefix.)
    import hashlib
    seen = set()
    dedup = []
    for p in pairs:
        k = (p["job"],
             hashlib.sha256(norm(p["input"]).encode()).hexdigest(),
             hashlib.sha256(norm(p["target"]).encode()).hexdigest())
        if k in seen:
            continue
        seen.add(k)
        dedup.append(p)
    stats["raw_pairs"] = len(pairs)
    stats["dedup_pairs"] = len(dedup)
    stats["per_job"] = dict(Counter(p["job"] for p in dedup))
    stats["per_job_source"] = {
        j: dict(Counter(p["source"] for p in dedup if p["job"] == j))
        for j in sorted(set(p["job"] for p in dedup))
    }

    with open(OUT, "w", encoding="utf-8") as f:
        for p in dedup:
            f.write(json.dumps(p, ensure_ascii=False) + "\n")
    stats["out"] = str(OUT)
    print(json.dumps(stats, indent=2))


if __name__ == "__main__":
    main()
