#!/usr/bin/env python3
"""Sweep analytics — read-only, runs against a BACKUP copy, never live.
Streams unchecked download_sources rows and reports:
  - distinct source_url / final_url counts (dedup headroom)
  - distinct hosts + top-20 hosts by unchecked rows (serialisation wall)
  - presigned/expiry-in-URL share (verdicts earnable without probing)
  - rows per host buckets (>10k, 1k-10k, <1k)
Usage: python3 sweep_analytics.py /path/to/backup.db
"""
import sqlite3
import sys
from collections import Counter
from urllib.parse import urlsplit

DB = sys.argv[1]
conn = sqlite3.connect(f"file:{DB}?mode=ro", uri=True, timeout=30)
cur = conn.cursor()

total = cur.execute(
    "SELECT COUNT(*) FROM download_sources WHERE checked_at IS NULL"
).fetchone()[0]
print(f"unchecked_rows={total}")

seen_url = set()
seen_final = set()
hosts = Counter()
presigned = 0
amz = 0
expires_param = 0
n = 0
for (url, final) in cur.execute(
    "SELECT source_url, final_url FROM download_sources WHERE checked_at IS NULL"
):
    n += 1
    if url:
        seen_url.add(url)
        low = url.lower()
        if "x-amz-expires" in low or "x-amz-date" in low:
            amz += 1
        if "expires=" in low:
            expires_param += 1
        if "x-amz-" in low or "expires=" in low:
            presigned += 1
        try:
            h = (urlsplit(url).hostname or "").lower()
        except ValueError:
            h = ""
        hosts[h or "(bad)"] += 1
    if final:
        seen_final.add(final)
    if n % 1_000_000 == 0:
        print(f"...streamed {n}", flush=True)

print(f"distinct_source_url={len(seen_url)}")
print(f"distinct_final_url={len(seen_final)}")
print(f"distinct_hosts={len(hosts)}")
print(f"presigned_or_expires_param={presigned} amz={amz} expires_param={expires_param}")
print("top20_hosts:")
for h, c in hosts.most_common(20):
    print(f"  {c:>8} {h}")
buckets = Counter()
for c in hosts.values():
    if c > 10000:
        buckets[">10k"] += 1
    elif c > 1000:
        buckets["1k-10k"] += 1
    else:
        buckets["<1k"] += 1
print(f"host_buckets={dict(buckets)}")
rows_big = sum(c for c in hosts.values() if c > 10000)
print(f"rows_on_gt10k_hosts={rows_big} ({rows_big / max(total, 1):.1%})")
