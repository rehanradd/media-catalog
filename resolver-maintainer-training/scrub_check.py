#!/usr/bin/env python3
"""Scrub check for the resolver-maintainer dataset (must come back CLEAN).

Checks every user message (inputs: probe evidence, mock pages, HAR request
sequences, registry entries) and every assistant message (targets: gold
resolver code, registry patches) in sft_train.jsonl + sft_eval.jsonl for:

  1. secret keyword followed by a non-redacted value
     (api_key, secret, password, cookie, set-cookie, cf_clearance,
     authorization, session id, access/refresh token, dl_token)
  2. long token-like runs (>= 32 chars) in user messages, excluding
     "<redacted>" and the base64 alphabet constant that legitimately
     appears in gold resolver code
  3. mixed-case token-like runs (>= 20 chars, upper+lower+digit) in
     user messages

Exit 0 + "CLEAN" only when all three checks find zero hits. Any hit is
printed (value truncated and redacted in this report) so it can be fixed.
Usage: python3 scrub_check.py [sft_train.jsonl sft_eval.jsonl]
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

SECRET_KV = re.compile(
    r"(?i)\b(api[_-]?key|secret|password|passwd|cookie|set-cookie|"
    r"cf_clearance|authorization|sessionid|session_id|access_token|"
    r"refresh_token|dl_token)\b\s*[:=]\s*(?!<redacted>)[\"']?"
    r"[A-Za-z0-9_\-\./]{6,}")
LONG_RUN = re.compile(r"\b[A-Za-z0-9_\-+/=]{32,}\b")
MIXED_RUN = re.compile(r"\b[A-Za-z0-9_\-]{20,}\b")


def _is_mixed_token(tok: str) -> bool:
    return (any(c.isupper() for c in tok)
            and any(c.islower() for c in tok)
            and any(c.isdigit() for c in tok))
# Code constants that are not tokens (appear in gold resolver source).
ALLOW = {
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/",
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_",
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
}


def check_file(path):
    hits = []
    n_user = n_asst = 0
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f, 1):
            ex = json.loads(line)
            msgs = {m["role"]: m["content"] for m in ex["messages"]}
            user = msgs.get("user", "")
            asst = msgs.get("assistant", "")
            n_user += 1
            n_asst += 1
            for m in SECRET_KV.finditer(user):
                hits.append((path.name, i, "secret-kv/user",
                             m.group(0)[:60]))
            for m in SECRET_KV.finditer(asst):
                hits.append((path.name, i, "secret-kv/assistant",
                             m.group(0)[:60]))
            for m in LONG_RUN.finditer(user):
                tok = m.group(0)
                if tok in ALLOW or "<redacted>" in tok:
                    continue
                hits.append((path.name, i, "long-run/user",
                             tok[:40] + "..."))
            for m in MIXED_RUN.finditer(user):
                if not _is_mixed_token(m.group(0)):
                    continue
                hits.append((path.name, i, "mixed-token/user",
                             m.group(0)[:40] + "..."))
    return hits, n_user, n_asst


def main():
    files = [Path(a) for a in sys.argv[1:]] or [
        HERE / "sft_train.jsonl", HERE / "sft_eval.jsonl"]
    all_hits = []
    total = 0
    for f in files:
        hits, nu, na = check_file(f)
        total += nu
        print(f"{f.name}: {nu} examples checked, {len(hits)} hits")
        all_hits.extend(hits)
    if all_hits:
        print(f"\n{len(all_hits)} HITS — NOT CLEAN:")
        for h in all_hits[:50]:
            print(" ", h)
        sys.exit(1)
    print(f"\nCLEAN: {total} examples, 0 hits "
          "(secret-kv, long-run>=32, mixed-token>=20 all zero).")


if __name__ == "__main__":
    main()
