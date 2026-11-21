#!/usr/bin/env python3
"""1fichier direct-link resolver (stdlib only) — TrueLink/nurlresolver port.

Port of two MIT references, logic only (no vendored code):

- TrueLink 1.4.8, ``src/truelink/resolvers/fichier.py`` (5hojib/truelink,
  MIT): POST the file URL, scrape
  ``//a[@class="ok btn-general btn-orange"]/@href``, classify the
  ``<div class="ct_warn">`` warnings (wait / bad password / premium /
  password-required / first-download validation).
- nurlresolver 4.0.0, ``fichier.js`` + ``BaseResolver.js`` +
  ``utils/helper.js`` (MIT): GET the file page, scrape the title from
  ``.premium tr:first-child td.normal:last-child``, POST the first
  form's hidden inputs back to the page URL (Referer set, cookies
  kept), scrape ``a.btn-general``.

Chain (no wait, no captcha): GET file page -> POST hidden form (``a=1``)
-> scrape the direct link. The POST goes to the page's own URL, exactly
as both references do — the form's ``action`` attribute is ignored.

Live use needs WARP egress: 1fichier locks the Oracle VM's IP (error
#350 — do NOT probe 1fichier from the Oracle VM or from hatch, no
exceptions), so live proof waits on the WARP-in-production decision
(owner-only). This module never fetches 1fichier itself; it only
implements the chain over a caller-supplied ``fetch`` with the same
call shape as ``current_hosts.default_fetch``.

Return shape follows the repo's ``_unwrap_*`` convention:
``(backend, candidates, failure)`` where a candidate is
``{"url", "referer", "kind_hint": "file", "title"}`` and ``failure`` is
None on success or one of the honest classes below. ``dead_file`` is
only ever a 404 (or the site's own removed-file signal); a 403 is
``blocked``, a transport failure is ``transport``, everything
unproven is ``unverified``.

Standalone: imports only stdlib. The parent wires ``_unwrap_1fichier``
into ``current_hosts.py`` (STAGE1_BACKENDS, INDEPENDENCE_GROUP,
preference lists, ``classify_backend``, ``resolve_copy`` dispatch)
after this lane lands.
"""

from __future__ import annotations

import html as _html
import re
import urllib.parse

BACKEND = "1fichier"

#: Failure classes this unwrapper can return (besides the shared
#: "blocked" / "transport" / "unverified" / "dead_file").
FAILURE_WAIT = "1fichier_wait"  # "you must wait N minutes" — retry later
FAILURE_NEEDS_PASSWORD = "1fichier_needs_password"
FAILURE_BAD_PASSWORD = "1fichier_bad_password"
FAILURE_PREMIUM = "1fichier_premium"
FAILURE_NEEDS_VALIDATION = "1fichier_needs_validation"  # first-download
# validation: the IP must first validate a download in a browser
# (the error #350-adjacent case — never a dead file).

#: TrueLink's validation string, checked anywhere in the POST body.
_VALIDATION_TEXT = (
    "In order to access this file, you will have to validate "
    "a first download."
)

_REDIRECT_STATUSES = frozenset({301, 302, 303, 307, 308})

_FORM_RE = re.compile(
    r"""<form\b[^>]*>(.*?)</form\s*>""", re.IGNORECASE | re.DOTALL)
_INPUT_RE = re.compile(r"""<input\b[^>]*>""", re.IGNORECASE)
_ATTR_RE = re.compile(
    r"""([A-Za-z_:][\w:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""")
_ANCHOR_RE = re.compile(r"""<a\b[^>]*>""", re.IGNORECASE)
_TABLE_RE = re.compile(
    r"""<table\b[^>]*class\s*=\s*["'][^"']*\bpremium\b[^"']*["'][^>]*>"""
    r"""(.*?)</table\s*>""", re.IGNORECASE | re.DOTALL)
_ROW_RE = re.compile(r"""<tr\b[^>]*>(.*?)</tr\s*>""", re.IGNORECASE | re.DOTALL)
_CELL_RE = re.compile(
    r"""<td\b[^>]*class\s*=\s*["'][^"']*\bnormal\b[^"']*["'][^>]*>"""
    r"""(.*?)</td\s*>""", re.IGNORECASE | re.DOTALL)
_WARN_RE = re.compile(
    r"""<div\b[^>]*class\s*=\s*["'][^"']*\bct_warn\b[^"']*["'][^>]*>"""
    r"""(.*?)</div\s*>""", re.IGNORECASE | re.DOTALL)
_TAG_RE = re.compile(r"""<[^>]+>""")


def is_1fichier_url(url: str) -> bool:
    """True when the URL's host is 1fichier.com (any subdomain)."""
    try:
        host = urllib.parse.urlsplit(str(url or "")).hostname or ""
    except Exception:
        return False
    return host == "1fichier.com" or host.endswith(".1fichier.com")


def _attrs(tag: str) -> dict:
    """Lower-cased attribute dict for one HTML tag (quoted or bare)."""
    out = {}
    for match in _ATTR_RE.finditer(tag or ""):
        name = match.group(1).lower()
        value = next(
            (g for g in match.groups()[1:] if g is not None), "")
        out[name] = _html.unescape(value)
    return out


def _strip_tags(text: str) -> str:
    """Visible text of an HTML fragment, entities decoded, squashed."""
    return re.sub(r"""\s+""", " ",
                  _html.unescape(_TAG_RE.sub(" ", text or ""))).strip()


def _cookies_from_set_cookie(values) -> dict:
    """name=value pairs from Set-Cookie headers (attributes dropped).

    Same shape as current_hosts._cookies_from_set_cookie; re-implemented
    here so this module stays standalone.
    """
    out = {}
    for raw in values or []:
        pair = (raw or "").split(";", 1)[0]
        if "=" in pair:
            name, value = pair.split("=", 1)
            name = name.strip()
            if name:
                out[name] = value.strip()
    return out


def parse_hidden_form(html_text: str) -> dict | None:
    """Hidden fields of the page's FIRST form, or None when no form.

    Mirrors nurlresolver's parseHiddenFormV2: every <input> in form[0]
    contributes its name/value; an input without a name, or without a
    value attribute, is skipped. The form's action is deliberately
    ignored — the chain always POSTs back to the page URL.
    """
    form = _FORM_RE.search(html_text or "")
    if not form:
        return None
    fields = {}
    for tag in _INPUT_RE.findall(form.group(1)):
        attrs = _attrs(tag)
        if "name" in attrs and "value" in attrs:
            fields[attrs["name"]] = attrs["value"]
    return fields


def scrape_btn_general_link(html_text: str) -> str | None:
    """Direct-link href from the POST response body, or None.

    Prefers TrueLink's exact button (``ok btn-general btn-orange``),
    falls back to any ``btn-general`` anchor — nurlresolver's
    ``a.btn-general`` selector. Attribute order (class before href or
    href before class) does not matter.
    """
    fallback = None
    for tag in _ANCHOR_RE.findall(html_text or ""):
        attrs = _attrs(tag)
        classes = set((attrs.get("class") or "").split())
        href = (attrs.get("href") or "").strip()
        if not href or "btn-general" not in classes:
            continue
        if "ok" in classes and "btn-orange" in classes:
            return href
        if fallback is None:
            fallback = href
    return fallback


def scrape_premium_title(html_text: str) -> str | None:
    """File name from the premium table, or None.

    nurlresolver's ``.premium tr:first-child td.normal:last-child``:
    first row of the ``premium`` table, last ``td.normal`` in it.
    """
    table = _TABLE_RE.search(html_text or "")
    if not table:
        return None
    row = _ROW_RE.search(table.group(1))
    if not row:
        return None
    cells = _CELL_RE.findall(row.group(1))
    if not cells:
        return None
    title = _strip_tags(cells[-1])
    return title or None


def warn_texts(html_text: str) -> list:
    """Visible text of every ``<div class="ct_warn">`` block."""
    return [_strip_tags(m) for m in _WARN_RE.findall(html_text or "")]


def _classify_no_link(body: str, password_given: bool) -> str:
    """Failure class when the POST answered 200 but carried no link.

    Mirrors TrueLink fichier.py's warning order: the validation string
    first, then the ct_warn blocks (wait / bad password / premium /
    password-required). Anything else is "unverified" — the page
    structure changed, not a dead file.
    """
    if _VALIDATION_TEXT in body:
        return FAILURE_NEEDS_VALIDATION
    warns = warn_texts(body)
    if not warns:
        return "unverified"
    joined = " | ".join(warns).lower()
    if "you must wait" in joined:
        return FAILURE_WAIT
    if "bad password" in joined:
        return FAILURE_BAD_PASSWORD
    if "you have to create a premium account" in joined:
        return FAILURE_PREMIUM
    if ("protect access to this file" in joined
            or "enter the password" in joined):
        if not password_given:
            return FAILURE_NEEDS_PASSWORD
        # A password was given and the page still demands one: TrueLink
        # falls through to its generic extraction failure here too.
        return "unverified"
    return "unverified"


def _unwrap_1fichier(saved_url: str, fetch, password=None) -> tuple:
    """1fichier file page -> hidden-form POST -> btn-general link.

    ``fetch`` is the repo's default_fetch shape:
    ``fetch(url, referer=..., method=..., data=..., cookies=...,
    follow_redirects=...)`` returning a FetchResult (``.status``,
    ``.text()``, ``.final_url``, ``.headers``, ``.set_cookies``).
    ``password`` adds TrueLink's ``pass`` field (``::password`` URLs).
    """
    page = fetch(saved_url)
    if page.status == 404:
        return BACKEND, [], "dead_file"
    if page.status != 200:
        return BACKEND, [], ("blocked" if page.status == 403 else
                             "transport" if page.status == 0 else
                             "unverified")
    final_url = page.final_url or saved_url
    title = scrape_premium_title(page.text())
    fields = parse_hidden_form(page.text())
    if fields is None:
        return BACKEND, [], "unverified"
    if password:
        fields["pass"] = password
    jar = _cookies_from_set_cookie(page.set_cookies)
    resp = fetch(final_url, referer=final_url, method="POST", data=fields,
                 cookies=jar or None, follow_redirects=False)
    if resp.status in _REDIRECT_STATUSES:
        location = (resp.headers or {}).get("location") or ""
        if location:
            # The POST minted the file by redirect (the megaup-style
            # mint): the Location IS the direct link.
            direct = urllib.parse.urljoin(final_url, location.strip())
            return BACKEND, [{"url": direct, "referer": final_url,
                              "kind_hint": "file", "title": title}], None
        return BACKEND, [], "unverified"
    if resp.status == 404:
        return BACKEND, [], "dead_file"
    if resp.status != 200:
        return BACKEND, [], ("blocked" if resp.status == 403 else
                             "transport" if resp.status == 0 else
                             "unverified")
    body = resp.text()
    link = scrape_btn_general_link(body)
    if link:
        direct = urllib.parse.urljoin(resp.final_url or final_url,
                                      link.strip())
        return BACKEND, [{"url": direct,
                          "referer": resp.final_url or final_url,
                          "kind_hint": "file", "title": title}], None
    return BACKEND, [], _classify_no_link(body, bool(password))
