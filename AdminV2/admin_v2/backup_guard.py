"""Backup precondition for live writes (PNL-08).

Before this module, every live write path in the panel ran on prose
alone: "live use needs a catalog backup before first use"
(live_stores.save_remote_config, refold_evidence, run_fleet and the
Service publish path all carried that note, and nothing enforced it).
The gate already verifies a backup by hand before every landing
(integrity_check "ok" + counts exact against the live DB at backup
time) — this module puts that same check in code, at the one funnel
every live SQLite write goes through (LiveStores._rw), and fails
closed with a plain-words message when there is no fresh verified
backup.

What "fresh verified backup" means, concretely
----------------------------------------------
A marker file ``<backup-stem>.verified.json`` next to a backup file,
written by the gate with AdminV2/tools/record_backup_verification.py
after it has verified that backup. A marker authorises a live write
to one store only when ALL of these hold:

* ``format`` is ``radd-backup-verification/1`` (unknown formats never
  authorise — a future format must not silently pass an old check);
* ``store`` is the store being written (``catalog`` or ``ledger``);
* ``source_path`` is exactly the live database file being written
  (a marker for another database authorises nothing);
* ``integrity_check`` is exactly ``"ok"`` (the gate's full
  ``PRAGMA integrity_check`` result — ``"skipped"`` does NOT count);
* ``counts_exact`` is true (the gate compared the backup counts with
  the live counts at backup time and they matched exactly);
* ``counts`` carries every required count for the store, as integers:
  catalog — posts, releases, download_sources, cat_links, page_count
  (all > 0); ledger — user_accounts, payment_transactions (>= 0);
* ``verified_at`` (ISO-8601 UTC) is no more than ``max_age_s`` old
  (default 24h, ``RADD_BACKUP_MAX_AGE_S``); a future timestamp never
  authorises (clock skew fails closed);
* the backup file still exists, with the recorded size (and mtime,
  when recorded) — a deleted or replaced backup authorises nothing.

The check is read-only-safe: it only lists one directory, reads
small JSON files and stats the backup file. It never opens a
database, never creates a backup, and never writes anything. The
gate owns creating and verifying backups; this module only reads the
record the gate left behind.

Configuration (environment, read by BackupGuard.from_env):
* ``RADD_BACKUP_MARKER_DIR`` — directory holding the gate backups
  and their ``*.verified.json`` markers (on the live VM: /home/ubuntu).
  Unset means "not configured": every live write is refused until
  the operator sets it. There is deliberately no default directory
  to guess — a wrong guess would authorise writes against the wrong
  backups.
* ``RADD_BACKUP_MAX_AGE_S`` — freshness window in seconds (default
  86400 = 24h). An unreadable value falls back to the default.

Registry-file writes (domain/host registry, Live TV) are NOT covered
here: each of those takes its own ``.bak`` copy of the file before
writing, so its backup exists by construction. Only the SQLite
catalog/ledger writes — the ones with no backup at all — go through
this guard.
"""
import json
import os
from datetime import datetime, timezone

from .errors import backup_required

FORMAT = "radd-backup-verification/1"
MARKER_SUFFIX = ".verified.json"
DEFAULT_MAX_AGE_S = 24 * 3600

# Required counts per store. Catalog counts are always > 0 on a real
# catalog; ledger counts may legitimately be 0 (no payments yet).
REQUIRED_COUNTS = {
    "catalog": (("posts", 1), ("releases", 1), ("download_sources", 1),
                ("cat_links", 1), ("page_count", 1)),
    "ledger": (("user_accounts", 0), ("payment_transactions", 0)),
}

_ENV_MARKER_DIR = "RADD_BACKUP_MARKER_DIR"
_ENV_MAX_AGE = "RADD_BACKUP_MAX_AGE_S"


def _parse_time(value):
    """ISO-8601 UTC timestamp -> aware datetime, or None.

    Accepts "2026-11-24T06:00:00+00:00", the same with a trailing Z,
    and the gate manifest's "2026-11-22 17:40:11 UTC" shape. Anything
    else (including a non-string) is not a timestamp — fail closed.
    """
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text:
        return None
    if text.endswith(("Z", "z")):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        try:
            parsed = datetime.strptime(text, "%Y-%m-%d %H:%M:%S %Z")
        except ValueError:
            return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _is_int(value):
    # bool is an int subclass; True/False are not counts.
    return isinstance(value, int) and not isinstance(value, bool)


class BackupGuard:
    """Read-only precondition: a fresh verified backup must exist
    before a live store is written. Constructed once (from env by the
    live entry point, explicitly in tests) and handed to LiveStores;
    None as a guard means "no guard" and is for fixture/test
    instances only — never for a live one."""

    def __init__(self, marker_dir=None, max_age_s=DEFAULT_MAX_AGE_S):
        self.marker_dir = (os.path.normpath(marker_dir)
                           if marker_dir else None)
        try:
            self.max_age_s = int(max_age_s)
        except (TypeError, ValueError):
            self.max_age_s = DEFAULT_MAX_AGE_S
        if self.max_age_s <= 0:
            self.max_age_s = DEFAULT_MAX_AGE_S

    @classmethod
    def from_env(cls, environ=None):
        """Build from RADD_BACKUP_MARKER_DIR / RADD_BACKUP_MAX_AGE_S.

        An unset marker dir stays unset (fail closed at check time);
        an unreadable max-age falls back to the 24h default.
        """
        env = environ if environ is not None else os.environ
        marker_dir = (env.get(_ENV_MARKER_DIR) or "").strip() or None
        max_age = (env.get(_ENV_MAX_AGE) or "").strip() or None
        if max_age is None:
            return cls(marker_dir=marker_dir)
        try:
            return cls(marker_dir=marker_dir, max_age_s=int(max_age))
        except (TypeError, ValueError):
            return cls(marker_dir=marker_dir)

    # -- marker loading -------------------------------------------------
    def _load_markers(self):
        """All *.verified.json files in the marker dir.

        Returns (markers, unreadable): markers is a list of
        (path, data-dict) for files that parsed as JSON objects;
        unreadable counts the files that did not (missing dir, bad
        JSON, wrong shape) — they never authorise anything, but the
        count is reported so a corrupt marker is visible instead of
        silently looking like "no backup". Pure reads, no writes.
        """
        markers, unreadable = [], 0
        if not self.marker_dir:
            return markers, unreadable
        try:
            names = sorted(os.listdir(self.marker_dir))
        except OSError:
            return markers, unreadable
        for name in names:
            if not name.endswith(MARKER_SUFFIX):
                continue
            path = os.path.join(self.marker_dir, name)
            try:
                with open(path, "r", encoding="utf-8") as fh:
                    data = json.load(fh)
            except (OSError, ValueError, UnicodeDecodeError):
                unreadable += 1
                continue
            if not isinstance(data, dict):
                unreadable += 1
                continue
            markers.append((path, data))
        return markers, unreadable

    # -- the check ------------------------------------------------------
    def _check_marker(self, data, store, source_path, now):
        """One marker against one intended write. Returns (ok, reason):
        ok True carries a short "verified ..." summary; ok False
        carries the plain-words reason this marker does not authorise
        the write. Never raises for bad marker content."""
        if data.get("format") != FORMAT:
            return False, "its format is not %s" % FORMAT
        if data.get("store") != store:
            return False, "it covers the %s store, not %s" % (
                data.get("store"), store)
        recorded_source = data.get("source_path")
        if not isinstance(recorded_source, str) or not recorded_source:
            return False, "it does not say which live database it backs up"
        if os.path.normpath(recorded_source) != os.path.normpath(source_path):
            return False, ("it backs up %s, not this %s database (%s)"
                           % (recorded_source, store, source_path))
        if data.get("integrity_check") != "ok":
            return False, ("its integrity_check is %r, not \"ok\" — "
                           "the backup was never fully verified"
                           % (data.get("integrity_check"),))
        if data.get("counts_exact") is not True:
            return False, ("its counts were not verified exact against "
                           "the live database at backup time")
        counts = data.get("counts")
        if not isinstance(counts, dict):
            return False, "it records no counts"
        for key, minimum in REQUIRED_COUNTS[store]:
            value = counts.get(key)
            if not _is_int(value) or value < minimum:
                return False, ("its counts are missing or invalid "
                               "(%s must be at least %d)" % (key, minimum))
        verified_at = _parse_time(data.get("verified_at"))
        if verified_at is None:
            return False, "its verified_at timestamp cannot be read"
        age_s = (now - verified_at).total_seconds()
        if age_s < 0:
            return False, "its verified_at timestamp is in the future"
        if age_s > self.max_age_s:
            return False, ("it was verified %s ago, past the %s freshness "
                           "limit" % (_age_words(age_s),
                                     _age_words(self.max_age_s)))
        backup_path = data.get("backup_path")
        if not isinstance(backup_path, str) or not backup_path:
            return False, "it does not name its backup file"
        try:
            st = os.stat(backup_path)
        except OSError:
            return False, "its backup file is gone (%s)" % backup_path
        recorded_bytes = data.get("backup_bytes")
        if _is_int(recorded_bytes) and st.st_size != recorded_bytes:
            return False, ("its backup file changed size since verification "
                           "(recorded %d bytes, now %d)"
                           % (recorded_bytes, st.st_size))
        recorded_mtime = data.get("backup_mtime")
        if _is_int(recorded_mtime) and int(st.st_mtime) != recorded_mtime:
            return False, ("its backup file changed since verification "
                           "(recorded mtime %d, now %d)"
                           % (recorded_mtime, int(st.st_mtime)))
        return True, ("verified backup %s, verified %s ago"
                      % (os.path.basename(backup_path), _age_words(age_s)))

    def status(self, store, source_path):
        """Read-only freshness report for one intended write.

        {"ok": bool, "reason": str, "marker": path-or-None,
         "age_s": int-or-None}. Never raises for a missing dir, bad
        JSON, or bad marker content — every failure is a report, so
        the live entry point can warn at startup without dying.
        """
        if store not in REQUIRED_COUNTS:
            return {"ok": False,
                    "reason": "unknown live store %r (expected catalog or "
                              "ledger)" % (store,),
                    "marker": None, "age_s": None}
        if not self.marker_dir:
            return {"ok": False,
                    "reason": ("no backup marker directory is configured — "
                               "set RADD_BACKUP_MARKER_DIR to the directory "
                               "holding the gate backups"),
                    "marker": None, "age_s": None}
        if not os.path.isdir(self.marker_dir):
            return {"ok": False,
                    "reason": ("backup marker directory does not exist: %s"
                               % self.marker_dir),
                    "marker": None, "age_s": None}
        markers, unreadable = self._load_markers()
        now = datetime.now(timezone.utc)
        mine = [(p, d) for (p, d) in markers if d.get("store") == store]
        if not mine:
            hint = ""
            if unreadable:
                hint = (" (%d marker file(s) in the directory could not "
                        "be read)" % unreadable)
            return {"ok": False,
                    "reason": ("no verified backup marker for the %s store "
                               "in %s%s" % (store, self.marker_dir, hint)),
                    "marker": None, "age_s": None}
        # Newest first (unparseable timestamps sort last, never first).
        mine.sort(key=lambda pd: _parse_time(pd[1].get("verified_at"))
                  or datetime.min.replace(tzinfo=timezone.utc),
                  reverse=True)
        for path, data in mine:
            ok, detail = self._check_marker(data, store, source_path, now)
            if ok:
                verified_at = _parse_time(data.get("verified_at"))
                return {"ok": True, "reason": detail, "marker": path,
                        "age_s": int((now - verified_at).total_seconds())}
        newest_path, newest_data = mine[0]
        _ok, detail = self._check_marker(newest_data, store, source_path, now)
        return {"ok": False,
                "reason": ("the newest verified backup marker for the %s "
                           "store (%s) does not authorise this write: %s"
                           % (store, os.path.basename(newest_path), detail)),
                "marker": newest_path, "age_s": None}

    def require(self, store, source_path):
        """Enforce the precondition or raise ApiError("backup_required").

        Returns the passing marker's status dict (with its path and
        age) so a caller that wants it can audit which backup covered
        the write. Refusals name the store, the marker directory, and
        what to do — the operator is never left guessing why the
        panel said no.
        """
        result = self.status(store, source_path)
        if result["ok"]:
            return result
        raise backup_required(
            "Live %s writes are refused: %s. Take a verified backup "
            "first and record it with "
            "AdminV2/tools/record_backup_verification.py — the panel "
            "will not write to the live %s database until then."
            % (store, result["reason"], store))


def _age_words(seconds):
    """Short human age: 90 -> "90s", 5400 -> "90m", 90000 -> "25h",
    200000 -> "2.3d". For refusal messages, not for precision."""
    seconds = int(seconds)
    if seconds < 60:
        return "%ds" % seconds
    if seconds < 3600:
        return "%dm" % (seconds // 60)
    if seconds < 86400:
        return "%dh" % (seconds // 3600)
    return "%.1fd" % (seconds / 86400.0)
