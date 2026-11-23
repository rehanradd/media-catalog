#!/usr/bin/env python3
"""wp_rest.py — shared WordPress REST helpers for the WP-site importers.

One client covers every proven WP site (bolly4u, ssrmovies, 7starhd,
9xmovies, moviesdrive, moviezguru, rareanimes, hindianimeszone): the live
shape is the same on all eight — ``GET {base}/wp-json/wp/v2/posts`` with
``?per_page=N&page=M`` and ``X-WP-Total`` / ``X-WP-TotalPages`` headers,
no auth for reads, and the full post HTML pre-rendered in
``content.rendered``.

What the source carries per post (measured live 2026-11-23, see
``/tmp/wp-samples/`` on the Oracle VM):

* ``title.rendered`` — full title, qualities/sizes in the string.
* ``content.rendered`` — the post HTML. Its plain text carries a labelled
  info block on most sites (``IMDB Rating: 6.8/10``, ``Genres: Drama,
  Family``, ``Director: …`` / ``Creators: …``, ``Stars Cast: …`` /
  ``Stars: …`` / ``Judges: …`` / ``Host: …``, ``Languages: …``,
  ``Film Story: …`` / ``Storyline: …`` / ``Plot: …``) plus the per-quality download anchors.
* ``excerpt.rendered`` — a truncated synopsis start (ends in ``[…]``).
* ``categories`` (ids) + ``_embedded.wp:term`` (names). Category NAMES
  are usually quality/language buckets (``720p Movies``,
  ``Telugu 300Mb``) — NOT genres. Real genres come from the ``Genres:``
  label in the content text; categories are only a fallback, filtered.
* ``_embedded.wp:featuredmedia[0].source_url`` — the poster, on the
  sites that embed it. Some sites embed a site-index object instead of
  media under that key (ssrmovies), so the media object is validated
  before use; the fallback is the first ``<img>`` in the content.
* ``yoast_head_json`` (9xmovies) — ``og_description`` / ``og_image``.

Rules the helpers enforce (site metadata FIRST, never fabricate):

* A quality is only what a heading/link label says; no ``720P``
  default. A codec is only what the text says (``x264``/``x265``/HEVC);
  no hardcoded codec.
* ``genres`` never come from unfiltered categories, and never from a
  hardcoded list (the old ``["Action", "Drama"]`` fabrication).
* A rating is only a parsed ``IMDb: N.N/10`` float; otherwise ``None``.
* No live network in tests: every function takes plain data (a post
  dict, a text string) or a ``get_json`` callable the caller injects.

Stdlib only.
"""

from __future__ import annotations

import html as html_mod
import re
import urllib.parse

WP_POSTS_PATH = "/wp-json/wp/v2/posts"
WP_CATEGORIES_PATH = "/wp-json/wp/v2/categories"
WP_PER_PAGE = 100

_USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124 Safari/537.36"
)


class WPReSTError(Exception):
    """A WP REST call failed (non-200, non-400, non-429)."""


class WPReSTRateLimited(Exception):
    """The site answered 429: pause the run, do not spend attempts."""


QUALITY_RE = re.compile(r"\b(2160p|1440p|1080p|720p|480p|360p|240p|4k)\b", re.I)
SIZE_RE = re.compile(r"\b\d+(?:\.\d+)?\s?(?:mb|gb)\b", re.I)
YEAR_RE = re.compile(r"\b(?:19\d{2}|20\d{2})\b")
CODEC_RE = re.compile(r"\b(x264|x265|hevc|avc|xvid)\b", re.I)
IMDB_RE = re.compile(
    r"IMD[Bb]\s*(?:Ratings?|Rating)?\s*:\s*-?\s*([0-9]+(?:\.[0-9]+)?)\s*(?:/10)?"
)

# Words that mark a category as a bucket, not a genre.
_LANGUAGE_WORDS = (
    "hindi", "tamil", "telugu", "malayalam", "kannada", "punjabi",
    "bengali", "marathi", "gujarati", "english", "korean", "turkish",
    "chinese", "japanese", "urdu",
)
_STATUS_WORDS = ("completed", "ongoing", "complete", "series", "season")
_GENERIC_CATEGORIES = (
    "movies", "movie", "tv shows", "tv show", "animes", "anime",
    "web series", "webseries", "uncategorized", "all movies",
    "hindi dubbed", "hindi dub", "hindi sub", "dual audio",
    "multi audio", "south hindi dubbed", "television", "tv",
    "hollywood", "bollywood", "tollywood", "kollywood", "mollywood",
    "sandalwood",
)

# Canonical label -> regexes matching the label as printed in post text.
# Order matters: longer / more specific labels first so "Stars Cast:" wins
# over "Stars:" and "Film Story:" wins over "Story:".
_LABEL_PATTERNS: list[tuple[str, str]] = [
    ("imdb_rating", r"IMD[Bb]\s*(?:Ratings?|Rating)?\s*:"),
    ("release_date", r"(?:Released\s+Year|Released\s+Date|Release\s+Date)\s*:"),
    ("director", r"(?:Director(?:/Creator)?|Directed\s+by|Creators?)\s*:"),
    ("cast", r"(?:Stars?\s+Cast|Star\s+Cast|Cast|Starring|Stars?)\s*:"),
    ("judges", r"Judges?\s*:"),
    ("host", r"Hosts?\s*:"),
    ("genres", r"Genres?\s*:"),
    ("languages", r"Languages?\s*:"),
    ("quality", r"(?:Video\s+)?Quality\s*:"),
    ("episode_size", r"Episode\s+Size\s*:"),
    ("story", r"(?:Film\s+Story|Story|Synopsis|Plot|Storyline)\s*(?::|[-–]\s)"),
]
_LABEL_RE = re.compile(
    "|".join(f"(?P<{key}>{pat})" for key, pat in _LABEL_PATTERNS), re.I
)

# Markers that end a story/synopsis value: the download section starts.
_STORY_STOP_RE = re.compile(
    r"(?:Watch\s+Online|Download\s+Links?|Free\s+Download|"
    r"Download\s+\[|\|\||Screenshots)",
    re.I,
)

_IMG_SRC_RE = re.compile(
    r"<img\b[^>]*\bsrc=[\"']([^\"']+)[\"']", re.I
)
_IMG_EXT_RE = re.compile(r"\.(?:jpg|jpeg|png|webp)(?:[?#]|$)", re.I)


def strip_html(text: str) -> str:
    """Tags -> spaces, entities unescaped, whitespace collapsed."""
    if not text:
        return ""
    out = html_mod.unescape(re.sub(r"<[^>]+>", " ", text))
    out = re.sub(r"\s+", " ", out)
    return out.strip()


def content_text(post: dict) -> str:
    """Plain text of a WP post's ``content.rendered``."""
    return strip_html((post.get("content") or {}).get("rendered") or "")


def excerpt_text(post: dict) -> str:
    """Plain text of a WP post's ``excerpt.rendered`` (truncated, ``[…]``)."""
    text = strip_html((post.get("excerpt") or {}).get("rendered") or "")
    return re.sub(r"\s*\[…\]\s*$", "", text).strip()


def parse_imdb_rating(text: str) -> float | None:
    """``IMDb: 6.5/10`` / ``IMDB Ratings: 7.0/10`` -> float, else None."""
    if not text:
        return None
    m = IMDB_RE.search(text)
    if not m:
        return None
    try:
        value = float(m.group(1))
    except ValueError:
        return None
    # A literal 0.0 is a site placeholder for "not rated" (moviesdrive
    # banks "IMDb Rating:- 0.0/10" on unrated titles) — never bank it.
    return value if 0.0 < value <= 10.0 else None


def _clean_label_value(key: str, raw: str) -> str:
    value = re.sub(r"\s+", " ", raw or "").strip(" :–—-|")
    if key == "story":
        m = _STORY_STOP_RE.search(value)
        if m:
            value = value[: m.start()].strip(" :–—-|")
    # A value that ran into the next section's heading junk.
    value = re.split(r"\s*\|\|\s*", value)[0].strip(" :–—-|")
    # Trailing section-marker emoji / dashes / pipes (moviesdrive puts a
    # "🖼️" between the storyline and its SCREENSHOTS heading; other sites
    # leave "||" or "—" at value ends). Keep sentence-ending punctuation
    # and the ellipsis.
    value = re.sub(r"[^\w.!?…)\]}]+$", "", value)
    return value


def _split_list(value: str) -> list[str]:
    parts = []
    for piece in re.split(r"[,/|]|\s+and\s+", value or ""):
        token = re.sub(r"\s+", " ", piece).strip(" .")
        # Oxford-comma lists ("A, B, and C") leave a leading "and".
        if token.lower().startswith("and "):
            token = token[4:].lstrip()
        if token:
            parts.append(token)
    return parts


def parse_labelled_text(text: str) -> dict:
    """Parse the ``Label: value`` info block from plain post text.

    Returns only the labels actually present; nothing is defaulted.
    ``genres`` and ``cast`` are lists; ``imdb_rating`` is a float;
    the rest are strings.
    """
    out: dict = {}
    if not text:
        return out
    matches = list(_LABEL_RE.finditer(text))
    if not matches:
        return out
    for i, m in enumerate(matches):
        key = m.lastgroup or ""
        if not key:
            continue
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        value = _clean_label_value(key, text[start:end])
        if not value:
            continue
        if key == "imdb_rating":
            rating = parse_imdb_rating(m.group(0) + " " + value)
            if rating is not None:
                out["imdb_rating"] = rating
        elif key in ("genres", "cast", "judges", "host"):
            items = [
                t for t in _split_list(value)
                if not QUALITY_RE.search(t) and not SIZE_RE.search(t)
            ]
            if items:
                out[key] = items
        elif key in ("story", "director", "languages", "release_date",
                     "quality"):
            if len(value) > 200 and key != "story":
                value = value[:200].strip()
            if key == "story" and len(value) < 15:
                continue
            out[key] = value
    return out


def post_labels(post: dict) -> dict:
    """Labelled info from a WP post dict (content text, then excerpt)."""
    labels = parse_labelled_text(content_text(post))
    if "story" not in labels and "imdb_rating" not in labels:
        for key, value in parse_labelled_text(excerpt_text(post)).items():
            labels.setdefault(key, value)
    return labels


def poster_from_post(post: dict) -> str | None:
    """Poster URL for a WP post dict.

    Order: validated ``_embedded`` featured media, first content ``<img>``,
    Yoast ``og:image``. The embedded entry is validated as real media:
    some sites put the site-index object under ``wp:featuredmedia``
    (ssrmovies), which has no ``source_url`` at all.
    """
    embedded = post.get("_embedded") or {}
    media = embedded.get("wp:featuredmedia") or []
    if media and isinstance(media, list):
        first = media[0] or {}
        url = first.get("source_url") or ""
        media_type = (first.get("media_type") or "").lower()
        mime = (first.get("mime_type") or "").lower()
        if url.startswith(("http://", "https://")) and (
            media_type == "image" or mime.startswith("image/")
            or (not media_type and not mime)
        ):
            return url
    content = (post.get("content") or {}).get("rendered") or ""
    for src in _IMG_SRC_RE.findall(content):
        src = html_mod.unescape(src).strip()
        if src.startswith(("http://", "https://")) and _IMG_EXT_RE.search(src):
            return src
    yoast = post.get("yoast_head_json") or {}
    og = yoast.get("og_image") or []
    if og and isinstance(og, list):
        url = (og[0] or {}).get("url") or ""
        if url.startswith(("http://", "https://")):
            return url
    return None


def synopsis_from_post(post: dict) -> str | None:
    """Synopsis: the ``Film Story:`` label, else the excerpt text."""
    labels = post_labels(post)
    story = labels.get("story")
    if story:
        return story
    excerpt = excerpt_text(post)
    return excerpt if len(excerpt) >= 40 else None


def _is_genre_category(name: str) -> bool:
    """True only if a WP category name looks like a real genre."""
    clean = re.sub(r"\s+", " ", (name or "").strip().lower())
    if not clean or clean in _GENERIC_CATEGORIES:
        return False
    if QUALITY_RE.search(clean) or SIZE_RE.search(clean):
        return False
    if YEAR_RE.search(clean):
        return False
    if any(re.search(rf"\b{w}\b", clean) for w in _LANGUAGE_WORDS):
        return False
    if any(re.search(rf"\b{w}\b", clean) for w in _STATUS_WORDS):
        return False
    if clean.endswith(" movies") or clean.endswith(" movie"):
        return False
    return True


def genres_from_categories(names: list[str] | None) -> list[str] | None:
    """Filtered, title-cased genre names from a raw WP category list.

    Same filter as the category path of :func:`genres_from_post`, but
    without touching the post's labels or excerpt — for importers whose
    excerpts are wrong-title and whose label parse is content-only.
    """
    out: list[str] = []
    for name in names or []:
        name = (name or "").strip()
        if not name or not _is_genre_category(name):
            continue
        # WP categories are often ALL-CAPS ("DRAMA"); bank the
        # title-cased form. The label path keeps the source casing.
        name = name.title()
        if name not in out:
            out.append(name)
    return out or None


def genres_from_post(post: dict) -> list[str] | None:
    """Genres for a WP post dict: ``Genres:`` label first, then filtered
    ``_embedded`` category names. Returns None when neither carries one."""
    labels = post_labels(post)
    if labels.get("genres"):
        return list(labels["genres"])
    embedded = post.get("_embedded") or {}
    raw_names: list[str] = []
    for group in embedded.get("wp:term") or []:
        if not isinstance(group, list):
            continue
        for term in group:
            if not isinstance(term, dict):
                continue
            if (term.get("taxonomy") or "category") != "category":
                continue
            raw_names.append((term.get("name") or "").strip())
    return genres_from_categories(raw_names)


def _norm_headers(headers: dict | None) -> dict:
    return {(k or "").lower(): v for k, v in (headers or {}).items()}


def _posts_url(base_url: str, page: int, per_page: int, embed: bool,
               after: str | None) -> str:
    query = {"per_page": per_page, "page": page}
    if embed:
        query["_embed"] = 1
    if after:
        query["after"] = after
    return (
        base_url.rstrip("/") + WP_POSTS_PATH + "?"
        + urllib.parse.urlencode(query)
    )


def paginate_posts(get_json, base_url: str, per_page: int = WP_PER_PAGE,
                   embed: bool = True, after: str | None = None,
                   max_pages: int | None = None):
    """Yield ``(page, posts, total_posts, total_pages)`` for every WP
    posts page, newest first.

    ``get_json(url)`` is the caller's transport and returns
    ``(status, data, headers)``. 429 raises :class:`WPReSTRateLimited`;
    any other non-200 (except a 400 on an out-of-range page, which ends
    pagination) raises :class:`WPReSTError`. The loop also ends on an
    empty page, so a site that stops honouring ``X-WP-TotalPages``
    cannot spin forever.
    """
    page = 1
    total_pages: int | None = None
    while True:
        if max_pages is not None and page > max_pages:
            return
        if total_pages is not None and page > total_pages:
            return
        url = _posts_url(base_url, page, per_page, embed, after)
        code, data, headers = get_json(url)
        if code == 429:
            raise WPReSTRateLimited(f"WP REST 429 at {url}")
        if code == 400:
            return
        if code != 200 or not isinstance(data, list):
            raise WPReSTError(f"WP REST {code} at {url}")
        if not data:
            return
        heads = _norm_headers(headers)
        try:
            total_posts = int(heads.get("x-wp-total") or len(data))
        except ValueError:
            total_posts = len(data)
        try:
            total_pages = int(heads.get("x-wp-totalpages") or 1)
        except ValueError:
            total_pages = 1
        yield page, data, total_posts, total_pages
        page += 1


def fetch_categories(get_json, base_url: str,
                     per_page: int = WP_PER_PAGE) -> dict[int, str]:
    """``{category id: name}`` for a site, paged until a short page.

    Enrichment only: any failure returns what was fetched so far (possibly
    ``{}``) instead of raising.
    """
    out: dict[int, str] = {}
    page = 1
    while True:
        url = (
            base_url.rstrip("/") + WP_CATEGORIES_PATH + "?"
            + urllib.parse.urlencode({"per_page": per_page, "page": page})
        )
        try:
            code, data, _headers = get_json(url)
        except Exception:
            return out
        if code != 200 or not isinstance(data, list) or not data:
            return out
        for item in data:
            if isinstance(item, dict) and item.get("id") is not None:
                out[item["id"]] = (item.get("name") or "").strip()
        if len(data) < per_page:
            return out
        page += 1
