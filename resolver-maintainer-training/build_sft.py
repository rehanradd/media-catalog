#!/usr/bin/env python3
"""Build the Fireworks SFT JSONL for the resolver-maintainer model.

Twin of ~/workspace/glimmer-training/build_sft.py, with per-job system
prompts. Input: pairs_raw.jsonl lines {"job", "input", "target", "source"}.
Output (Fireworks SFT messages format, OpenAI chat JSONL):
  {"messages": [
    {"role": "system", "content": SYSTEM[job]},
    {"role": "user", "content": "<input>"},
    {"role": "assistant", "content": "<target>"}
  ]}

Split: stratified 15% eval per job, seed 42. (The scope's 500-example eval
assumed thousands of pairs; the measured dataset is 371 pairs total, so a
500-example eval would leave almost nothing to train on. 15% stratified
keeps every job represented in both splits; see sft_stats.json.)

Writes sft_train.jsonl, sft_eval.jsonl, sft_stats.json in this directory.
"""
import json
import random
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
IN = HERE / "pairs_raw.jsonl"
SEED = 42
EVAL_FRAC = 0.15

SYSTEM = {
    "host-analysis": (
        "You analyse a streaming host from observable evidence. Given the "
        "host, its probe evidence and the last-known resolver state, return "
        "only the flow type (one of: direct-link-in-HTML, "
        "page's-own-API-call, JS-minted signed URL, form-post, "
        "redirect-chain, challenge-page), the failure class (one of: none, "
        "domain-moved, flow-changed, challenge-page, host-dead, "
        "rate-limited, egress-blocked), a confidence from 0 to 1, the "
        "classifier category (direct_file, hls_embed, bridge, unknown) and "
        "backend, and the evidence lines. No extra text, no explanation."),
    "resolver-drafting": (
        "You write a Python resolver for a streaming host in the repo's "
        "style. Given a flow description, a page excerpt and one existing "
        "unwrap pattern as a style reference, return only the Python "
        "_unwrap_* function and a test for it, in the repo's style "
        "(standard library only, fetch injected). No extra text, "
        "no explanation."),
    "registry-drift": (
        "You correct a streaming site's registry entry from drift "
        "evidence. Given the site, its old registry entry, known mirrors "
        "and live drift evidence, return only the new canonical domain and "
        "the domain_registry.json patch for that site. No extra text, "
        "no explanation."),
}


def to_example(p):
    return {"messages": [
        {"role": "system", "content": SYSTEM[p["job"]]},
        {"role": "user", "content": p["input"]},
        {"role": "assistant", "content": p["target"]},
    ]}


def main():
    pairs = []
    with open(IN, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                pairs.append(json.loads(line))
    rng = random.Random(SEED)
    by_job = {}
    for p in pairs:
        by_job.setdefault(p["job"], []).append(p)
    train_pairs, eval_pairs = [], []
    eval_per_job = {}
    for job, rows in sorted(by_job.items()):
        rng.shuffle(rows)
        n_eval = max(1, round(len(rows) * EVAL_FRAC))
        eval_pairs.extend(rows[:n_eval])
        train_pairs.extend(rows[n_eval:])
        eval_per_job[job] = n_eval
    rng.shuffle(train_pairs)
    rng.shuffle(eval_pairs)

    for name, rows in (("sft_train", train_pairs), ("sft_eval", eval_pairs)):
        with open(HERE / f"{name}.jsonl", "w", encoding="utf-8") as f:
            for p in rows:
                f.write(json.dumps(to_example(p), ensure_ascii=False) + "\n")

    stats = {
        "total_pairs": len(pairs),
        "train": len(train_pairs),
        "eval": len(eval_pairs),
        "eval_frac": EVAL_FRAC,
        "eval_per_job": eval_per_job,
        "train_per_job": dict(Counter(p["job"] for p in train_pairs)),
        "eval_per_job_actual": dict(Counter(p["job"] for p in eval_pairs)),
        "per_job_total": dict(Counter(p["job"] for p in pairs)),
        "system_prompts": SYSTEM,
        "format": "Fireworks SFT messages JSONL (system/user/assistant)",
        "eval_note": (
            "Scope specified a 500-example eval; measured dataset is "
            f"{len(pairs)} pairs total, so a 500-example eval would leave "
            "almost nothing to train on. Used stratified 15% eval per job "
            "instead (seed 42)."),
        "planning_rule": (
            "All five scoped jobs measured under ~1,000 pairs: job 1 "
            "merged with job 2 into host-analysis; job 3 merged into job 5 "
            "as registry-drift; job 4 kept separate as resolver-drafting "
            "(code generation has no neighbour; flagged thin for Phase 2)."),
    }
    with open(HERE / "sft_stats.json", "w", encoding="utf-8") as f:
        json.dump(stats, f, indent=2)
    print(json.dumps(stats, indent=2))


if __name__ == "__main__":
    main()
