#!/usr/bin/env python3
"""
import_7starhd.py — Dedicated staging importer and scraper for 7StarHD (https://7starhd.rich) and 10xflix (https://10xflix.beer/m).

Key Features:
  1. Dedicated staging SQLite database: 7starhd.db (isolated from production fast_fetch.db).
  2. Full WP REST API sync: Paginates /wp-json/wp/v2/posts?per_page=100 across 23,400+ titles.
     Sync stores the full REST post (with _embed=1) as raw_json, so
     extraction runs off the post itself — no per-post page fetch
     (WP REST adoption, 2026-11-23).
  3. Clean Metadata Extraction:
     - Title, year, audio/language, poster image, synopsis, IMDb rating,
       genres, cast, director, release date, 18+ flag — all from the
       source's own info block and categories, never fabricated.
  4. Precise Quality & File Size Extraction:
     - Extracts per-release button text: "1080p [2.48GB]", "720p [1.40GB]", "480p [481MB]"
     - Captures exact resolution tier and file size.
  5. Multi-Mirror MobileJSR Bridge Resolution:
     - Session cookie & CSRF negotiation unlocks direct mirrors:
       - HubCloud FSL Cloudflare R2 direct stream (r2.cloudflarestorage.com)
       - HubDrive direct Google Drive mirror
       - FilePress Google Drive direct
       - 1fichier, GoFile, Streamtape, Voe
  6. Resumable via sitemap_cache table in staging SQLite database.
  7. 100% Python Standard Library. Zero external pip packages.

Usage:
    python Fast_Fetch/import_7starhd.py --sync-api               # Ingest all WP REST API post URLs
    python Fast_Fetch/import_7starhd.py --limit 10               # Scrape 10 pending titles
    python Fast_Fetch/import_7starhd.py --workers 5 --delay 0.3 # Full background run
    python Fast_Fetch/import_7starhd.py --status                 # Show current database counts
"""

from __future__ import annotations

import argparse
import concurrent.futures
import html
import json
import os
import re
import signal
import sqlite3
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

# Add Fast_Fetch to sys.path
BASE_DIR = Path(__file__).parent.resolve()
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import fast_fetch
import wp_rest
from config import staging_db_path
from domain_resolver import get_active_base_url, resolve_if_dead

DEFAULT_BASE_URL = "https://7starhd.surgery"
SITE_NAME = "7starhd"
DEFAULT_DB_PATH = staging_db_path("7starhd")
STATUS_FILE = BASE_DIR / "7starhd_status.json"

STOP_REQUESTED = False


def _sigint_handler(signum, frame):
    global STOP_REQUESTED
    print("\n[!] Graceful shutdown requested... Finishing current requests.", flush=True)
    STOP_REQUESTED = True


signal.signal(signal.SIGINT, _sigint_handler)
signal.signal(signal.SIGTERM, _sigint_handler)


def get_ssl_context():
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


MAX_SAFE_WORKERS = 6
MIN_REQUEST_DELAY = 0.3


class SevenStarHDClient:
    """HTTP Client with rate limiting and retry handling for 7StarHD / 10xflix & MobileJSR."""

    def __init__(self, base_url: str | None = None, request_interval: float = 0.3, retries: int = 3):
        # Session 106: the registry is the source of truth for the
        # live domain; an explicit base_url (CLI --base-url) still wins.
        self.base_url = (base_url or get_active_base_url(SITE_NAME, DEFAULT_BASE_URL)).rstrip("/")
        self.request_interval = max(0.0, request_interval)
        self.retries = max(1, retries)
        self.ssl_ctx = get_ssl_context()
        self._next_request_at = 0.0
        self._lock = threading.Lock()

    def _wait(self):
        with self._lock:
            now = time.monotonic()
            if now < self._next_request_at:
                time.sleep(self._next_request_at - now)
            self._next_request_at = time.monotonic() + self.request_interval

    def get_text(self, url: str, referer: str | None = None, timeout: int = 15) -> tuple[int, str, str]:
        headers = {
            "User-Agent": fast_fetch.USER_AGENT,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9",
        }
        if referer:
            headers["Referer"] = referer

        for attempt in range(self.retries):
            if STOP_REQUESTED:
                return 0, url, ""
            self._wait()
            try:
                req = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(req, context=self.ssl_ctx, timeout=timeout) as resp:
                    code = resp.status
                    final_url = resp.geturl()
                    charset = resp.headers.get_content_charset() or "utf-8"
                    raw = resp.read()
                    text = raw.decode(charset, errors="replace")
                    return code, final_url, text
            except urllib.error.HTTPError as e:
                if e.code in (404, 410):
                    return e.code, url, ""
                if attempt == self.retries - 1:
                    return e.code, url, ""
                time.sleep(1.0 * (attempt + 1))
            except Exception:
                if attempt == self.retries - 1:
                    return 0, url, ""
                time.sleep(1.0 * (attempt + 1))
        return 0, url, ""

    def get_json(self, url: str, timeout: int = 15) -> tuple[int, dict | list | None, dict]:
        code, final_url, text = self.get_text(url, timeout=timeout)
        if code != 200 or not text:
            return code, None, {}
        try:
            data = json.loads(text)
            return code, data, {}
        except Exception:
            return code, None, {}


def init_staging_db(db_path: Path):
    """Initializes 7StarHD staging database with schema."""
    db_path.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(str(db_path)) as conn:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.executescript("""
            CREATE TABLE IF NOT EXISTS sitemap_cache (
                loc TEXT PRIMARY KEY,
                lastmod TEXT,
                slug TEXT,
                scraped INTEGER DEFAULT 0,
                site TEXT DEFAULT '7starhd'
            );
            CREATE INDEX IF NOT EXISTS idx_sm_scraped ON sitemap_cache(scraped);
            CREATE INDEX IF NOT EXISTS idx_sm_site ON sitemap_cache(site);
        """)
        conn.commit()
    fast_fetch.DB_PATH = db_path
    fast_fetch.init_db()


def _ensure_sitemap_raw_json(db_path: Path) -> None:
    """WP REST adoption (2026-11-23): the queue carries the full REST post
    (``raw_json``) so extraction needs no per-post page fetch. Neither the
    local ``init_staging_db`` schema nor the shared ``fast_fetch`` schema
    has such a column; add it when missing."""
    with sqlite3.connect(str(db_path)) as conn:
        cols = {c[1] for c in conn.execute(
            "PRAGMA table_info(sitemap_cache);").fetchall()}
        if "raw_json" not in cols:
            conn.execute("ALTER TABLE sitemap_cache ADD COLUMN raw_json TEXT;")
            conn.commit()


def sync_api_to_db(client: SevenStarHDClient, db_path: Path, max_pages: int | None = None, site_name: str = "7starhd", start_page: int = 1) -> int:
    """
    Ingests all post URLs from WordPress REST API (/wp-json/wp/v2/posts?per_page=100)
    into sitemap_cache table in staging SQLite database.
    """
    init_staging_db(db_path)
    _ensure_sitemap_raw_json(db_path)
    print(f"\n[*] Probing {site_name.upper()} WP REST API at {client.base_url}/wp-json/wp/v2/posts...")
    probe_url = f"{client.base_url}/wp-json/wp/v2/posts?per_page=100"
    headers = {"User-Agent": fast_fetch.USER_AGENT}
    req = urllib.request.Request(probe_url, headers=headers)
    try:
        with urllib.request.urlopen(req, context=client.ssl_ctx, timeout=12) as resp:
            total_posts_header = resp.headers.get("X-WP-Total")
            total_pages_header = resp.headers.get("X-WP-TotalPages")
            total_posts = int(total_posts_header) if total_posts_header else 0
            if total_pages_header:
                total_pages = int(total_pages_header)
            elif total_posts > 0:
                total_pages = (total_posts + 99) // 100
            else:
                total_pages = 250
    except Exception as e:
        print(f"[!] Warning: Could not probe WP API headers: {e}. Defaulting to progressive sync.", flush=True)
        total_posts = 0
        total_pages = 250

    pages_to_fetch = min(total_pages, max_pages) if max_pages else total_pages
    print(f"[✓] {site_name.upper()} API reports {total_posts:,} total posts across {total_pages} pages.")
    print(f"[*] Ingesting pages {start_page} to {pages_to_fetch} (100 posts/page)...", flush=True)

    with sqlite3.connect(str(db_path)) as conn:
        existing_slugs = set(
            row[0] for row in conn.execute(
                "SELECT slug FROM sitemap_cache WHERE site = ? AND slug IS NOT NULL", (site_name,)
            ).fetchall()
        )
        existing_locs = set(
            row[0] for row in conn.execute(
                "SELECT loc FROM sitemap_cache WHERE site = ? AND loc IS NOT NULL", (site_name,)
            ).fetchall()
        )
    print(f"[*] Pre-loaded {len(existing_slugs):,} existing slugs and {len(existing_locs):,} existing locs from {db_path.name}.", flush=True)

    records = []
    total_new = 0
    total_existing = 0
    consecutive_empty_new = 0

    for page in range(start_page, pages_to_fetch + 1):
        if STOP_REQUESTED:
            break
        # _embed=1 carries wp:term (category names — incl. "18+ Adult")
        # inside each post; the whole post is stored as raw_json so the
        # worker extracts from it instead of fetching the page.
        page_url = f"{client.base_url}/wp-json/wp/v2/posts?per_page=100&page={page}&_embed=1"
        p_code, p_data, _ = client.get_json(page_url)
        if p_code != 200 or not isinstance(p_data, list):
            if p_code in (400, 404) or (isinstance(p_data, dict) and p_data.get("code") == "rest_post_invalid_page_number"):
                print(f"    [*] Reached end of catalog pagination at page {page}.", flush=True)
                break
            print(f"    [!] Error fetching page {page} (HTTP {p_code})", flush=True)
            continue

        page_new = 0
        for item in p_data:
            post_url = item.get("link")
            if not post_url:
                continue
            slug = item.get("slug") or post_url.rstrip("/").split("/")[-1]
            modified = item.get("modified")
            
            if (slug and slug in existing_slugs) or (post_url in existing_locs):
                total_existing += 1
            else:
                records.append((post_url, modified, slug, 0, site_name,
                                json.dumps(item)))
                if slug:
                    existing_slugs.add(slug)
                existing_locs.add(post_url)
                page_new += 1
                total_new += 1

        print(f"    [Page {page:3d}] Found {page_new} brand new, {len(p_data) - page_new} existing (Cumulative New: {total_new:,})", flush=True)

        if len(records) >= 500:
            with fast_fetch._DB_WRITE_LOCK:
                with fast_fetch.get_db() as conn:
                    conn.executemany(
                        """
                        INSERT INTO sitemap_cache (loc, lastmod, slug, scraped, site, raw_json)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT(loc) DO UPDATE SET
                            lastmod = COALESCE(excluded.lastmod, sitemap_cache.lastmod),
                            slug = COALESCE(excluded.slug, sitemap_cache.slug),
                            raw_json = COALESCE(excluded.raw_json, sitemap_cache.raw_json)
                        """,
                        records,
                    )
                    conn.commit()
            records.clear()

        if page_new == 0:
            consecutive_empty_new += 1
            if max_pages is None and consecutive_empty_new >= 3:
                print(f"[*] 3 consecutive pages with 0 new titles reached. Stopping sync early.", flush=True)
                break
        else:
            consecutive_empty_new = 0

    if records:
        with fast_fetch._DB_WRITE_LOCK:
            with fast_fetch.get_db() as conn:
                conn.executemany(
                    """
                    INSERT INTO sitemap_cache (loc, lastmod, slug, scraped, site, raw_json)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(loc) DO UPDATE SET
                        lastmod = COALESCE(excluded.lastmod, sitemap_cache.lastmod),
                        slug = COALESCE(excluded.slug, sitemap_cache.slug),
                        raw_json = COALESCE(excluded.raw_json, sitemap_cache.raw_json)
                    """,
                    records,
                )
                conn.commit()

    print(f"[✓] Successfully ingested {total_new:,} brand new titles into sitemap_cache ({total_existing:,} skipped/already known).\n", flush=True)
    return total_new


def parse_7starhd_release_links(html_content: str) -> list[dict]:
    """
    Parses MobileJSR download links and nearby quality/size info from post content.
    Matches formats like:
      <a href="https://mobilejsr.com/view/..." ...>1080p [2.48GB]</a>
      <a href="https://mobilejsr.com/view/..." ...>720p [1.40GB]</a>
    """
    pattern = re.compile(
        r'href=["\'](https?://mobilejsr\.com/view/[a-zA-Z0-9_\-]+)["\'][^>]*>(.*?)</a>',
        re.I | re.S,
    )
    raw_matches = pattern.findall(html_content)
    releases = []
    seen_urls = set()

    for jsr_url, anchor_text in raw_matches:
        clean_text = html.unescape(re.sub(r'<[^>]+>', '', anchor_text).strip())
        # Screenshot anchors are preview images, not downloads: skip them.
        if re.search(r"screen\s?sho+?t", clean_text, re.I):
            continue

        # Quality extraction
        qual_m = re.search(r'\b(2160p|4k|1080p|720p|480p)\b', clean_text, re.I)
        qual = qual_m.group(1).lower() if qual_m else "720p"
        # Dedup on (url, quality): one bridge URL serves several quality tiers.
        if (jsr_url, qual) in seen_urls:
            continue
        seen_urls.add((jsr_url, qual))

        # Size extraction
        size_m = re.search(r'\[?(\d+(?:\.\d+)?\s*(?:GB|MB))\]?', clean_text, re.I)
        size_str = size_m.group(1).strip() if size_m else ""

        # Codec extraction
        codec = "x264"
        if re.search(r'\b(hevc|x265|10bit)\b', clean_text, re.I):
            codec = "x265"

        releases.append({
            "jsr_url": jsr_url,
            "quality": qual,
            "size": size_str,
            "codec": codec,
            "label": clean_text,
        })

    return releases


def extract_post_data_7starhd(
    post_url: str,
    page_html: str,
    site_name: str = "7starhd",
    resolve_direct: bool = True,
    rest_post: dict | None = None,
) -> dict | None:
    """Parses full media metadata, releases, and MobileJSR mirror streams
    from a 7StarHD post.

    WP REST adoption (2026-11-23): when ``rest_post`` (one WP REST post
    dict) is given, everything comes from it — title.rendered, the
    labelled info block in ``content.rendered`` (IMDB Ratings, Released
    Date, Genres, Languages, Director, Host, Judges, Stars, Plot), the
    poster (first content image; featured_media is 0 here) and the
    per-quality MobileJSR buttons in the content — and no page fetch is
    needed. Without it the old page-HTML path runs, with the same label
    parsing so neither path fabricates metadata.
    """
    slug = post_url.rstrip("/").split("/")[-1]

    # 1. Post title & metadata
    if rest_post is not None:
        # REST: title.rendered IS the post title. (On the page, the
        # <title> tag is static site branding and the <h2> only repeats
        # the title.) The release buttons and the info block both live
        # in content.rendered.
        raw_title = html.unescape(
            str((rest_post.get("title") or {}).get("rendered") or slug))
        page_html = (rest_post.get("content") or {}).get("rendered") or ""
    else:
        # 7StarHD and 10xflix put the full post title in the first <h2> heading (e.g. <h2>The Punisher (2026)...</h2>)
        # The <title> tag is static generic site branding ("7StarHD" or "10xflix").
        raw_title = None
        h2_matches = re.findall(r"<h2[^>]*>(.*?)</h2>", page_html, re.I | re.S)
        for h2_candidate in h2_matches:
            cand = re.sub(r"<[^>]+>", "", h2_candidate).strip()
            cand_lower = cand.lower()
            if cand and cand_lower not in ("7starhd", "10xflix", "screenshots", "download links", "trailer"):
                raw_title = html.unescape(cand)
                break

        if not raw_title:
            title_m = re.search(r"<title>(.*?)</title>", page_html, re.I)
            if title_m:
                cand = (
                    title_m.group(1)
                    .split(" – 7StarHD")[0]
                    .split(" - 7StarHD")[0]
                    .split(" – 10xflix")[0]
                    .split(" - 10xflix")[0]
                    .split(" | ")[0]
                    .strip()
                )
                if cand.lower() not in ("7starhd", "10xflix", ""):
                    raw_title = html.unescape(cand)

        if not raw_title:
            raw_title = slug.replace("-", " ").title()

    year_m = re.search(r"\b(19\d{2}|20\d{2})\b", raw_title)
    year = int(year_m.group(1)) if year_m else None
    if year is None and rest_post is not None:
        # REST only: fall back to the year in the content heading line
        # (the text before the first info label) — later dates (episode
        # air dates) are not the title year.
        _head = wp_rest.content_text(rest_post)
        _imdb_at = _head.find("IMDb:")
        if _imdb_at > 0:
            _head = _head[:_imdb_at]
        _year_m = re.search(r"\b(19\d{2}|20\d{2})\b", _head)
        if _year_m:
            year = int(_year_m.group(1))

    # Strip year and trailing resolution/quality noise
    clean_title = re.sub(r"\s*[\(\[]?\b(19\d{2}|20\d{2})\b.*$", "", raw_title).strip()
    clean_title = re.sub(
        r"\s*(?:2160p|4k|1080p|720p|480p|web-dl|bluray|hdrip|hdtc|hdtv|esubs|dual audio|hindi|org|cleaned|hq|season\s*\d+|s\d+|series).*$",
        "",
        clean_title,
        flags=re.I,
    ).strip()
    if not clean_title:
        clean_title = slug.replace("-", " ").title()

    content_type = "movie"
    # SxxEyy is the usual series form here ("S017E12", "S01E020"): the
    # plain S\d+ alternative needs a word boundary after the digits,
    # which "S017E12" does not have (the E follows the digits), so the
    # compact season+episode form is matched first; a bare "Series"
    # word also marks a series.
    if re.search(r'\b(?:Season\s*\d+|S\d+\s*E\d+|S\d+|Complete\s+Series|Series|Episodes?)\b', raw_title, re.I):
        content_type = "series"

    canonical_key = fast_fetch.generate_canonical_key(clean_title, year)

    # Labels from the post text (both paths): the info block carries
    # Title / IMDB Ratings / Released Date / Genres / Languages /
    # Director / Host / Judges / Stars / Quality / Plot.
    labels = wp_rest.post_labels(rest_post) if rest_post is not None \
        else wp_rest.parse_labelled_text(wp_rest.strip_html(page_html))

    # 2. Poster image
    if rest_post is not None:
        # featured_media is 0 on this site and there is no embedded
        # media; the poster is the first content image.
        poster_url = wp_rest.poster_from_post(rest_post)
    else:
        poster_url = None
        img_m = re.search(r'<img[^>]+src=["\'](https?://[^"\']+\.(?:jpg|jpeg|png|webp))["\']', page_html, re.I)
        if img_m:
            poster_url = img_m.group(1)
        else:
            og_m = re.search(r'<meta property=["\']og:image["\'] content=["\']([^"\']+)["\']', page_html, re.I)
            if og_m:
                poster_url = og_m.group(1)

    # 3. Synopsis & Language
    if rest_post is not None:
        # The excerpt is the same metadata dump as the info block, never
        # a synopsis — only a real Plot:/Storyline: label counts.
        synopsis = labels.get("story")
    else:
        synopsis = None
        desc_m = re.search(r'<meta name=["\']description["\'] content=["\']([^"\']+)["\']', page_html, re.I)
        if desc_m:
            synopsis = html.unescape(desc_m.group(1).strip())
        elif labels.get("story"):
            synopsis = labels["story"]

    language = None
    raw_lower = raw_title.lower()
    if "dual audio" in raw_lower:
        language = "Hindi + English"
    elif "punjabi" in raw_lower:
        language = "Punjabi"
    elif "south" in raw_lower or "tamil" in raw_lower or "telugu" in raw_lower:
        language = "South Hindi Dubbed"
    elif "english" in raw_lower:
        language = "English"
    if language is None:
        # The info block's Languages: label (e.g. "Hindi", "English").
        if labels.get("languages"):
            language = re.sub(r"\s*\([^)]*\)", "", labels["languages"]).strip() or None
        if language is None:
            language = "Hindi"

    # Metadata from the source's own labels — never fabricated: the old
    # code banked ["Action", "Drama"] for EVERY post and None for the
    # rating. Genres come from the Genres: label (else filtered
    # categories); rating from the IMDb block (N/A -> None); cast =
    # Stars + Judges + Host (all on-screen talent).
    if rest_post is not None:
        genres_list = wp_rest.genres_from_post(rest_post)
    else:
        genres_list = labels.get("genres")
    genres = json.dumps(genres_list) if genres_list else None
    imdb_rating = labels.get("imdb_rating")
    cast_parts: list[str] = []
    for _key in ("cast", "judges", "host"):
        for _name in labels.get(_key) or []:
            if _name not in cast_parts:
                cast_parts.append(_name)
    cast_names = ", ".join(cast_parts) if cast_parts else None
    director = labels.get("director")
    release_date = labels.get("release_date")
    # 18+: the source says so itself — an "18+ Adult" category, "[18+]"
    # in the title, or an Erotic/Adult genre label.
    _adult_genres = {"erotic", "adult", "xxx", "porn", "pornographic"}
    _cat_names: list[str] = []
    if rest_post is not None:
        for _group in (rest_post.get("_embedded") or {}).get("wp:term") or []:
            _cat_names.extend(
                t.get("name", "") for t in _group if isinstance(t, dict))
    is_18_plus = 1 if (
        "[18+]" in raw_title
        or any("18+" in c for c in _cat_names)
        or any(g.lower() in _adult_genres for g in (genres_list or []))
    ) else 0

    # 4. Extract MobileJSR releases
    button_releases = parse_7starhd_release_links(page_html)
    if not button_releases:
        return None

    releases = []
    for btn in button_releases:
        jsr_url = btn["jsr_url"]
        qual = btn["quality"]
        size_str = btn["size"]
        codec = btn["codec"]

        rel_title = f"{clean_title} {qual.upper()}"
        if year:
            rel_title = f"{clean_title} ({year}) {qual.upper()}"
        if size_str:
            rel_title += f" [{size_str}]"

        sources = []
        if resolve_direct:
            sources = fast_fetch.unwrap_mobilejsr(jsr_url)
        else:
            sources = [{
                "source_name": "MobileJSR Gateway",
                "service": "mobilejsr",
                "resource_id": jsr_url.rstrip("/").split("/")[-1],
                "source_url": jsr_url,
                "final_url": jsr_url,
                "playback_type": "url",
            }]

        releases.append({
            "release_title": rel_title,
            "quality": qual,
            "codec": codec,
            "size": size_str,
            "languages": language,
            "season": None,
            "episode": None,
            "intermediate_url": jsr_url,
            "source_site": site_name,
            "sources": sources,
        })

    return {
        "url": post_url,
        "slug": slug,
        "title": raw_title,
        "clean_title": clean_title,
        "year": year,
        "content_type": content_type,
        "seasons": None,
        "poster_url": poster_url,
        "backdrop_urls": None,
        "imdb_rating": imdb_rating,
        "synopsis": synopsis,
        "genres": genres,
        "cast_names": cast_names,
        "director": director,
        "release_date": release_date,
        "is_18_plus": is_18_plus,
        "language": language,
        "source_site": site_name,
        "canonical_key": canonical_key,
        "releases": releases,
    }



def scrape_7starhd_worker(
    post_url: str,
    client: SevenStarHDClient,
    site_name: str = "7starhd",
    resolve_direct: bool = True,
    raw_json_str: str | None = None,
) -> tuple[bool, int, int]:
    """Worker task: extracts releases & sources from a post, persists to SQLite.

    WP REST adoption (2026-11-23): when the queue row carries ``raw_json``
    (the full REST post from sync), extraction runs off it and the worker
    makes NO page fetch at all. Rows synced before the adoption (no
    raw_json) fall back to fetching the post page.
    """
    data = None
    if raw_json_str:
        try:
            rest_post = json.loads(raw_json_str)
        except (json.JSONDecodeError, TypeError):
            rest_post = None
        if isinstance(rest_post, dict) and rest_post.get("content"):
            data = extract_post_data_7starhd(
                post_url=post_url,
                page_html="",
                site_name=site_name,
                resolve_direct=resolve_direct,
                rest_post=rest_post,
            )
    if data is None:
        code, final_url, html_text = client.get_text(post_url)
        if code != 200 or not html_text:
            return False, 0, 0

        data = extract_post_data_7starhd(
            post_url=final_url,
            page_html=html_text,
            site_name=site_name,
            resolve_direct=resolve_direct,
        )
    if not data or not data.get("releases"):
        with fast_fetch._DB_WRITE_LOCK:
            with fast_fetch.get_db() as conn:
                conn.execute(
                    "UPDATE sitemap_cache SET scraped = -1 WHERE loc = ? AND site = ?",
                    (post_url, site_name),
                )
                conn.commit()
        return False, 0, 0

    rel_count = len(data["releases"])
    src_count = sum(len(r.get("sources", [])) for r in data["releases"])

    with fast_fetch._DB_WRITE_LOCK:
        post_id = fast_fetch.save_scraped_data(data, enrich_metadata=False, ensure_db=False)
        if post_id:
            with fast_fetch.get_db() as conn:
                conn.execute(
                    "UPDATE sitemap_cache SET scraped = 1 WHERE loc = ? AND site = ?",
                    (post_url, site_name),
                )
                conn.commit()

    return True, rel_count, src_count


def get_db_stats(db_path: Path, site_name: str = "7starhd") -> dict:
    """Queries current staging SQLite database row metrics."""
    if not db_path.exists():
        return {"exists": False}
    with sqlite3.connect(str(db_path)) as conn:
        c = conn.cursor()
        c.execute("SELECT count(*) FROM posts")
        posts = c.fetchone()[0]
        c.execute("SELECT count(*) FROM releases")
        releases = c.fetchone()[0]
        c.execute("SELECT count(*) FROM download_sources")
        sources = c.fetchone()[0]
        c.execute("SELECT count(*) FROM download_sources WHERE service LIKE '%r2%' OR final_url LIKE '%.r2.%'")
        direct_r2 = c.fetchone()[0]
        c.execute("SELECT count(*) FROM download_sources WHERE service = 'hubcloud' OR service = 'fsl_r2'")
        hubcloud = c.fetchone()[0]
        c.execute("SELECT count(*) FROM download_sources WHERE service = 'filepress'")
        filepress = c.fetchone()[0]
        c.execute("SELECT count(*) FROM sitemap_cache WHERE site = ? AND scraped = 1", (site_name,))
        scraped_sm = c.fetchone()[0]
        c.execute("SELECT count(*) FROM sitemap_cache WHERE site = ? AND scraped = 0", (site_name,))
        pending_sm = c.fetchone()[0]
        c.execute("SELECT count(*) FROM sitemap_cache WHERE site = ? AND scraped = -1", (site_name,))
        review_sm = c.fetchone()[0]
        c.execute("SELECT count(*) FROM sitemap_cache WHERE site = ?", (site_name,))
        total_sm = c.fetchone()[0]

    return {
        "exists": True,
        "posts": posts,
        "releases": releases,
        "sources": sources,
        "direct_r2": direct_r2,
        "hubcloud": hubcloud,
        "filepress": filepress,
        "sitemap_scraped": scraped_sm,
        "sitemap_pending": pending_sm,
        "sitemap_review": review_sm,
        "sitemap_total": total_sm,
    }


def print_status(db_path: Path, site_name: str = "7starhd"):
    """Displays terminal formatted status."""
    stats = get_db_stats(db_path, site_name=site_name)
    print("=" * 60)
    print(f" 📊 {site_name.upper()} Staging DB Status ({db_path.name})")
    print("=" * 60)
    if not stats["exists"]:
        print(f" Database file not found at {db_path}")
        return

    print(f" Total Catalog Posts    : {stats['posts']:,}")
    print(f" Total Releases Found   : {stats['releases']:,}")
    print(f" Total Direct Sources   : {stats['sources']:,}")
    print(f" ⚡ HubCloud Streams     : {stats['hubcloud']:,}")
    print(f" ⚡ Cloudflare R2 Direct : {stats['direct_r2']:,}")
    print(f" ⚡ FilePress Google CDN : {stats['filepress']:,}")
    print("-" * 60)
    print(f" Sitemap / API Cache    : {stats['sitemap_scraped']:,} / {stats['sitemap_total']:,} scraped")
    print(f" Pending / Review       : {stats['sitemap_pending']:,} pending, {stats['sitemap_review']:,} review rows")
    print("=" * 60)


def requeue_failed_urls(db_path: Path, site_name: str = "7starhd") -> int:
    """Requeue rows previously marked for no-release review."""
    fast_fetch.DB_PATH = db_path
    with fast_fetch._DB_WRITE_LOCK:
        with fast_fetch.get_db() as conn:
            cursor = conn.execute(
                "UPDATE sitemap_cache SET scraped = 0 WHERE site = ? AND scraped = -1",
                (site_name,),
            )
            conn.commit()
            return cursor.rowcount


def run_7starhd_scraper(
    base_url: str | None = None,
    db_path: Path = DEFAULT_DB_PATH,
    limit: int | None = None,
    workers: int = 5,
    delay: float = 0.3,
    site_name: str = "7starhd",
    resolve_direct: bool = True,
    retry_failed: bool = False,
):
    """
    Multi-threaded crawler orchestrator for 7StarHD / 10xflix into dedicated staging SQLite DB.
    """
    # Session 106: the base comes from the registry for the requested site
    # unless the caller passed one explicitly.
    base_url = (base_url or get_active_base_url(
        site_name, DEFAULT_BASE_URL)).rstrip("/")
    if not 1 <= workers <= MAX_SAFE_WORKERS:
        raise ValueError(f"workers must be between 1 and {MAX_SAFE_WORKERS}")
    if delay < MIN_REQUEST_DELAY:
        raise ValueError(f"delay must be at least {MIN_REQUEST_DELAY}s")

    init_staging_db(db_path)
    _ensure_sitemap_raw_json(db_path)
    fast_fetch.DB_PATH = db_path

    if retry_failed:
        requeued = requeue_failed_urls(db_path, site_name=site_name)
        print(f"[*] Requeued {requeued:,} review rows for retry.", flush=True)

    client = SevenStarHDClient(base_url=base_url, request_interval=delay)

    # 1. Fetch pending URLs from cache
    with sqlite3.connect(str(db_path)) as conn:
        c = conn.cursor()
        c.execute(
            "SELECT loc, raw_json FROM sitemap_cache WHERE site = ? AND scraped = 0 ORDER BY rowid ASC",
            (site_name,),
        )
        pending_urls = [(row[0], row[1]) for row in c.fetchall()]

    # If cache is empty, automatically sync API
    if not pending_urls:
        print(f"[*] No pending URLs found in cache. Starting automatic API sync for {site_name}...", flush=True)
        sync_api_to_db(client, db_path, site_name=site_name)
        with sqlite3.connect(str(db_path)) as conn:
            c = conn.cursor()
            c.execute(
                "SELECT loc, raw_json FROM sitemap_cache WHERE site = ? AND scraped = 0 ORDER BY rowid ASC",
                (site_name,),
            )
            pending_urls = [(row[0], row[1]) for row in c.fetchall()]

    if limit is not None:
        pending_urls = pending_urls[:max(0, limit)]

    total_tasks = len(pending_urls)
    print("=" * 65)
    print(f" 🚀 {site_name.upper()} Multi-Threaded Ingestion Pipeline")
    print(f"    Staging DB       : {db_path.name}")
    print(f"    Pending Titles   : {total_tasks:,}")
    print(f"    Worker Threads   : {workers} (delay: {delay}s)")
    print(f"    Direct Resolvers : {'Enabled (HubCloud/FilePress/R2)' if resolve_direct else 'Disabled'}")
    print("=" * 65)

    if total_tasks == 0:
        print("[✓] All titles already scraped. Nothing to do!")
        return

    counters = {
        "completed": 0,
        "releases": 0,
        "sources": 0,
        "failed": 0,
        "skipped": 0,
        "start_time": time.time(),
    }

    thread_local = threading.local()

    def get_thread_client() -> SevenStarHDClient:
        if not hasattr(thread_local, "client"):
            thread_local.client = SevenStarHDClient(base_url=base_url, request_interval=delay)
        return thread_local.client

    def process_url(url_and_raw):
        if STOP_REQUESTED:
            return False, 0, 0
        url, raw_json_str = url_and_raw
        c = get_thread_client()
        return scrape_7starhd_worker(
            url, c, site_name=site_name, resolve_direct=resolve_direct,
            raw_json_str=raw_json_str)

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        future_map = {executor.submit(process_url, u): u[0] for u in pending_urls}
        for future in concurrent.futures.as_completed(future_map):
            if STOP_REQUESTED:
                break
            try:
                ok, rels, srcs = future.result()
                if ok:
                    counters["completed"] += 1
                    counters["releases"] += rels
                    counters["sources"] += srcs
                elif rels == 0 and srcs == 0:
                    counters["skipped"] += 1
                else:
                    counters["failed"] += 1
            except Exception:
                counters["failed"] += 1

            processed = counters["completed"] + counters["failed"] + counters["skipped"]
            if processed % 10 == 0 or processed == total_tasks:
                elapsed = time.time() - counters["start_time"]
                speed = processed / max(1, elapsed)
                remaining = max(0, total_tasks - processed)
                eta_sec = remaining / max(0.01, speed)
                eta_str = time.strftime("%H:%M:%S", time.gmtime(eta_sec))
                print(
                    f"    [{processed}/{total_tasks}] "
                    f"releases={counters['releases']:,} sources={counters['sources']:,} "
                    f"failed={counters['failed']} speed={speed:.2f} titles/sec ETA: {eta_str}",
                    flush=True,
                )

                try:
                    with open(STATUS_FILE, "w", encoding="utf-8") as sf:
                        json.dump({
                            "site": site_name,
                            "total": total_tasks,
                            "completed": counters["completed"],
                            "skipped": counters["skipped"],
                            "pending": remaining,
                            "releases": counters["releases"],
                            "sources": counters["sources"],
                            "failed": counters["failed"],
                            "speed": round(speed, 2),
                            "eta": eta_str,
                            "updated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                        }, sf, indent=2)
                except Exception:
                    pass

    total_time = time.time() - counters["start_time"]
    print(
        f"\n[✓] {site_name.upper()} scrape finished in {total_time:.1f}s: "
        f"{counters['completed']} completed, {counters['releases']:,} releases, "
        f"{counters['sources']:,} sources, {counters['failed']} failed.\n"
    )
    print_status(db_path, site_name=site_name)


def parse_args():
    parser = argparse.ArgumentParser(description="Dedicated Staging Importer for 7StarHD / 10xflix")
    parser.add_argument("--sync-api", action="store_true", help="Ingest all post URLs from WP REST API")
    parser.add_argument("--start-page", type=int, default=1, help="Starting WP REST API page to ingest")
    parser.add_argument("--max-pages", type=int, default=None, help="Maximum WP REST API pages to ingest")
    parser.add_argument("--limit", type=int, default=None, help="Limit number of posts to scrape")
    parser.add_argument("--workers", type=int, default=5, help="Number of concurrent scraper workers")
    parser.add_argument("--delay", type=float, default=0.3, help="Request rate-limit delay in seconds")
    parser.add_argument("--base-url", default=None, help="Base domain for site (default: registry live domain)")
    parser.add_argument("--db-path", default=str(DEFAULT_DB_PATH), help="Path to staging SQLite database")
    parser.add_argument("--site", default="7starhd", choices=["7starhd", "10xflix"], help="Target site name")
    parser.add_argument("--status", action="store_true", help="Print staging database stats and exit")
    parser.add_argument("--retry-failed", action="store_true", help="Requeue previously reviewed no-release URLs")
    parser.add_argument("--no-resolve-direct", action="store_true", help="Skip unwrapping MobileJSR direct streams")
    return parser.parse_args()


def main():
    args = parse_args()
    db_path = Path(args.db_path).resolve()
    # Session 106: the base comes from the registry for the requested site
    # (the fallback is the live /m/ base; the registry wins when present).
    site_key = "10xflix" if args.site == "10xflix" else "7starhd"
    base_url = args.base_url or get_active_base_url(
        site_key, DEFAULT_BASE_URL if site_key == "7starhd" else "https://10xflix.beer/m")

    # Auto-adjust default DB path if site is 10xflix
    if args.site == "10xflix" and args.db_path == str(DEFAULT_DB_PATH):
        db_path = BASE_DIR / "10xflix.db"

    client = SevenStarHDClient(base_url=base_url, request_interval=args.delay)

    if args.status:
        print_status(db_path, site_name=args.site)
        return

    if args.sync_api:
        count = sync_api_to_db(client, db_path, max_pages=args.max_pages, site_name=args.site, start_page=args.start_page)
        if count == 0:
            # Empty sync: retry once on the chain's verdict, and only then.
            new_base = resolve_if_dead(site_key)
            if new_base:
                print(f"[!] {args.site}: sync found 0 posts and the cached domain is dead; chain found {new_base}; retrying once", flush=True)
                client = SevenStarHDClient(base_url=new_base, request_interval=args.delay)
                sync_api_to_db(client, db_path, max_pages=args.max_pages, site_name=args.site, start_page=args.start_page)
        return

    run_7starhd_scraper(
        base_url=base_url,
        db_path=db_path,
        limit=args.limit,
        workers=args.workers,
        delay=args.delay,
        site_name=args.site,
        resolve_direct=not args.no_resolve_direct,
        retry_failed=args.retry_failed,
    )


if __name__ == "__main__":
    main()
