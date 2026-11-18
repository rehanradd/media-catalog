#!/usr/bin/env python3
"""quality — one home for the release-quality spelling.

The catalog's ``releases.quality`` arrived fragmented: ``720p`` vs ``720P``
(593,105 vs 138,476 rows), a bare ``HD`` on 410,824 rows, and 33,032 empty
strings (DB checkup 2026-11-18, fix #6). The ladder, the app's quality filter
and the v2 quality score all read this column, so the spelling is normalized
exactly once, here, at every catalog write — ``fast_fetch.save_scraped_data``
(the single funnel for direct scrapes and for ``merge_catalogs``) — and by
``backfill_quality.py`` for the rows already stored. The staging databases
keep the raw string (Zero Data Loss: normalization is a catalog-write
decision, never a rewrite of the ledger).

Rules (in order):
  * blank / whitespace-only / missing → NULL (no quality claim at all);
  * lower-case + trim, so ``720P`` and `` 1080p `` land on one spelling;
  * ``hd`` → ``720p`` (a bare HD tag is a 720p stream on every site that
    writes it; the app's quality filter has no ``hd`` rung);
  * ``full hd`` / ``full-hd`` / ``fhd`` → ``1080p``.
Everything else keeps its lower-cased spelling (``4k``, ``2160p``, ``cam``,
``hls``, ``web-dl`` …) — only the spellings above are claims about a rung.
"""

from __future__ import annotations

#: Spellings that mean 1080p (checked after lower-casing + trimming).
_FULL_HD = frozenset({"full hd", "full-hd", "fhd"})


def normalize_quality(value) -> str | None:
    """Return the canonical quality spelling for ``value``, or None.

    None, a non-string, and a blank string all answer None: a missing
    quality is a missing claim, never an empty string in the column.
    """
    if value is None:
        return None
    if not isinstance(value, str):
        value = str(value)
    text = value.strip().lower()
    if not text:
        return None
    if text == "hd":
        return "720p"
    if text in _FULL_HD:
        return "1080p"
    return text
