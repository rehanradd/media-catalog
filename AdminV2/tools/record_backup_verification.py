"""Record a verified backup (PNL-08) — the gate's half of the panel's
backup precondition.

The gate verifies a backup by hand (PRAGMA integrity_check "ok" +
counts exact against the live DB at backup time) and then calls this
tool to leave the machine-readable record the panel's BackupGuard
reads before any live write:

    python3 AdminV2/tools/record_backup_verification.py \\
        --backup /home/ubuntu/gate-backup-20261123-054718-fix1.db \\
        --store catalog \\
        --source /home/ubuntu/radd-stream-data/merged_catalog_20261025.db \\
        --integrity ok --counts-exact --verified-by gate-fix1

What it does:
* refuses unless --integrity is exactly "ok" AND --counts-exact is
  passed — the gate asserts both; this tool never invents them
  (a manifest that says integrity_check "skipped" does NOT qualify);
* reads the counts OUT of the backup file itself (read-only
  sqlite3, mode=ro) so the recorded counts provably match the file,
  not a mistyped command line: catalog needs posts / releases /
  download_sources / cat_links + page_count; ledger needs
  user_accounts / payment_transactions (+ coin_ledger when present);
* writes <backup-stem>.verified.json next to the backup (or into
  --marker-dir), atomically (temp file + os.replace).

What it never does: touch the live database (only checks the source
path exists, to catch a typo that would authorise the wrong DB),
modify the backup file, or run integrity_check itself — the full
check takes minutes on a 10 GB file and the gate already ran it;
the --integrity value IS the gate's result, recorded verbatim.

Exit 0 = marker written (prints its path). Exit 2 = refused, with
the reason on stderr and no marker written. Re-recording the same
backup replaces its marker (a re-verification overwrites the old
record); it never deletes anything.
"""
import argparse
import json
import os
import sqlite3
import sys
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from admin_v2.backup_guard import FORMAT, MARKER_SUFFIX  # noqa: E402

CATALOG_COUNT_TABLES = ("posts", "releases", "download_sources", "cat_links")
LEDGER_REQUIRED_TABLES = ("user_accounts", "payment_transactions")
LEDGER_OPTIONAL_TABLES = ("coin_ledger",)


def _read_counts(backup_path, store):
    """Counts straight from the backup file (read-only). Returns a
    dict, or raises ValueError with the reason the file cannot back
    this store."""
    uri = "file:%s?mode=ro" % backup_path
    try:
        conn = sqlite3.connect(uri, uri=True, timeout=10.0)
    except sqlite3.Error as exc:
        raise ValueError("cannot open backup as a database: %s" % exc)
    try:
        tables = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        if store == "catalog":
            missing = [t for t in CATALOG_COUNT_TABLES if t not in tables]
            if missing:
                raise ValueError(
                    "backup is not a catalog database (missing tables: %s)"
                    % ", ".join(missing))
            counts = {}
            for table in CATALOG_COUNT_TABLES:
                counts[table] = conn.execute(
                    "SELECT COUNT(*) AS n FROM %s" % table).fetchone()[0]
            counts["page_count"] = conn.execute(
                "PRAGMA page_count").fetchone()[0]
            return counts
        missing = [t for t in LEDGER_REQUIRED_TABLES if t not in tables]
        if missing:
            raise ValueError(
                "backup is not a ledger database (missing tables: %s)"
                % ", ".join(missing))
        counts = {}
        for table in LEDGER_REQUIRED_TABLES + LEDGER_OPTIONAL_TABLES:
            if table in tables:
                counts[table] = conn.execute(
                    "SELECT COUNT(*) AS n FROM %s" % table).fetchone()[0]
        return counts
    except sqlite3.Error as exc:
        raise ValueError("cannot read counts from backup: %s" % exc)
    finally:
        conn.close()


def record(backup, store, source, integrity, counts_exact, verified_by,
           marker_dir=None, sha256=None):
    """Write the marker. Returns (marker_path, marker_dict) on success;
    raises ValueError with a plain-words reason on any refusal."""
    if not backup or not os.path.isfile(backup):
        raise ValueError("backup file does not exist: %s" % backup)
    if os.path.getsize(backup) == 0:
        raise ValueError("backup file is empty: %s" % backup)
    if not source or not os.path.exists(source):
        raise ValueError("live source database does not exist: %s "
                         "(refusing — a typo here would authorise the "
                         "wrong database)" % source)
    if integrity != "ok":
        raise ValueError(
            "integrity_check is %r, not \"ok\" — run the full PRAGMA "
            "integrity_check on the backup first; \"skipped\" does not "
            "qualify" % (integrity,))
    if not counts_exact:
        raise ValueError(
            "counts were not verified exact: compare the backup counts "
            "with the live counts at backup time first, then pass "
            "--counts-exact")
    counts = _read_counts(backup, store)
    if store == "catalog" and any(v <= 0 for v in counts.values()):
        raise ValueError("catalog backup counts must all be positive: %s"
                         % (counts,))
    backup_abs = os.path.abspath(backup)
    marker_dir = marker_dir or os.path.dirname(backup_abs)
    if not os.path.isdir(marker_dir):
        raise ValueError("marker directory does not exist: %s" % marker_dir)
    stem = os.path.basename(backup_abs)
    if stem.endswith(".db"):
        stem = stem[:-3]
    marker_path = os.path.join(marker_dir, stem + MARKER_SUFFIX)
    st = os.stat(backup_abs)
    marker = {
        "format": FORMAT,
        "store": store,
        "source_path": os.path.abspath(source),
        "backup_path": backup_abs,
        "backup_bytes": st.st_size,
        "backup_mtime": int(st.st_mtime),
        "verified_at": datetime.now(timezone.utc).isoformat(),
        "verified_by": verified_by,
        "integrity_check": "ok",
        "counts": counts,
        "counts_exact": True,
    }
    if sha256:
        marker["backup_sha256"] = sha256
    tmp_path = marker_path + ".tmp-%d" % os.getpid()
    try:
        with open(tmp_path, "w", encoding="utf-8") as fh:
            json.dump(marker, fh, indent=2, sort_keys=True)
            fh.write("\n")
        os.replace(tmp_path, marker_path)
    except OSError as exc:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise ValueError("cannot write marker %s: %s" % (marker_path, exc))
    return marker_path, marker


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Record a verified gate backup for the panel's "
                    "backup precondition (PNL-08).")
    parser.add_argument("--backup", required=True,
                        help="backup database file just verified")
    parser.add_argument("--store", required=True,
                        choices=("catalog", "ledger"),
                        help="which live store this backup covers")
    parser.add_argument("--source", required=True,
                        help="live database file the backup was taken from")
    parser.add_argument("--integrity", required=True,
                        help="result of the gate's PRAGMA integrity_check; "
                             "must be exactly 'ok'")
    parser.add_argument("--counts-exact", action="store_true",
                        help="assert the gate compared the backup counts "
                             "with the live counts at backup time and they "
                             "matched exactly")
    parser.add_argument("--verified-by", default="gate",
                        help="label for who verified it (default: gate)")
    parser.add_argument("--marker-dir", default=None,
                        help="directory for the marker (default: the "
                             "backup's own directory)")
    parser.add_argument("--sha256", default=None,
                        help="optional sha256 of the backup file, recorded "
                             "as-is (never recomputed here)")
    args = parser.parse_args(argv)
    try:
        marker_path, marker = record(
            args.backup, args.store, args.source, args.integrity,
            args.counts_exact, args.verified_by, args.marker_dir,
            args.sha256)
    except ValueError as exc:
        print("refused: %s" % exc, file=sys.stderr)
        return 2
    print("marker written: %s" % marker_path)
    print("store=%s verified_at=%s counts=%s"
          % (marker["store"], marker["verified_at"], marker["counts"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
