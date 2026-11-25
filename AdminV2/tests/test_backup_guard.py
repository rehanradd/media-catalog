"""Backup precondition (PNL-08): a live write needs a fresh verified
backup, and fails closed without one. Every check here runs against
TEMP files only; no test ever touches a live DB, and no test ever
creates a real backup — the guard only ever READS markers."""
import json
import os
import sqlite3
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))), "tools"))
from helpers import make_service  # noqa: E402
from admin_v2.backup_guard import BackupGuard, FORMAT  # noqa: E402
from admin_v2.errors import ApiError  # noqa: E402
from admin_v2.live_stores import LiveStores  # noqa: E402
from admin_v2.services import Service  # noqa: E402
from record_backup_verification import main as record_main  # noqa: E402
import record_backup_verification as recorder  # noqa: E402

CATALOG_DDL = """
CREATE TABLE app_remote_config (id INTEGER PRIMARY KEY CHECK (id = 1),
  vip_monthly_pkr INTEGER NOT NULL DEFAULT 200,
  vip_yearly_pkr INTEGER NOT NULL DEFAULT 1500,
  maintenance_mode INTEGER NOT NULL DEFAULT 0,
  feature_quality_gate INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0);
CREATE TABLE posts (id INTEGER PRIMARY KEY, title TEXT,
  is_18_plus INTEGER NOT NULL DEFAULT 0);
CREATE TABLE releases (id INTEGER PRIMARY KEY, post_id INTEGER);
CREATE TABLE download_sources (id INTEGER PRIMARY KEY, release_id INTEGER,
  status TEXT NOT NULL DEFAULT 'active', is_resumable INTEGER NOT NULL DEFAULT 0);
CREATE TABLE cat_links (id INTEGER PRIMARY KEY, state TEXT);
"""

LEDGER_DDL = """
CREATE TABLE user_accounts (id INTEGER PRIMARY KEY, key_hash TEXT);
CREATE TABLE payment_transactions (id INTEGER PRIMARY KEY, account_id INTEGER,
  plan TEXT, amount_pkr INTEGER, method TEXT, tid TEXT,
  status TEXT, submitted_at INTEGER);
CREATE TABLE movie_requests (id INTEGER PRIMARY KEY, status TEXT);
"""


def _now():
    return datetime.now(timezone.utc)


def _marker(backup_path, source_path, store="catalog", age=None,
            integrity="ok", counts_exact=True, counts=None, fmt=FORMAT,
            verified_at=None, backup_bytes=None, backup_mtime=None):
    """A marker dict as the gate would record it."""
    if counts is None:
        if store == "catalog":
            counts = {"posts": 3, "releases": 2, "download_sources": 1,
                      "cat_links": 1, "page_count": 7}
        else:
            counts = {"user_accounts": 1, "payment_transactions": 0}
    if verified_at is None:
        verified_at = _now() - (age if age is not None else timedelta(hours=1))
    data = {
        "format": fmt,
        "store": store,
        "source_path": source_path,
        "backup_path": backup_path,
        "verified_at": verified_at.isoformat(),
        "verified_by": "test-gate",
        "integrity_check": integrity,
        "counts": counts,
        "counts_exact": counts_exact,
    }
    if backup_bytes is not None:
        data["backup_bytes"] = backup_bytes
    if backup_mtime is not None:
        data["backup_mtime"] = backup_mtime
    return data


class GuardBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="adminv2-backup-guard-")
        self.marker_dir = os.path.join(self.tmp, "markers")
        os.makedirs(self.marker_dir)
        self.backup_path = os.path.join(self.tmp, "gate-backup-test.db")
        with open(self.backup_path, "wb") as fh:
            fh.write(b"fake-backup-bytes")
        self.source_path = os.path.join(self.tmp, "live.db")
        with open(self.source_path, "wb") as fh:
            fh.write(b"fake-live-db")
        self.guard = BackupGuard(marker_dir=self.marker_dir)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write_marker(self, name="gate-backup-test.verified.json", **kw):
        kw.setdefault("backup_path", self.backup_path)
        kw.setdefault("source_path", self.source_path)
        data = _marker(**kw)
        path = os.path.join(self.marker_dir, name)
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(data, fh)
        return path, data

    def _backup_stat(self):
        st = os.stat(self.backup_path)
        return st.st_size, int(st.st_mtime)


class TestGuardRefusals(GuardBase):
    def test_no_marker_dir_configured_refuses(self):
        guard = BackupGuard(marker_dir=None)
        with self.assertRaises(ApiError) as ctx:
            guard.require("catalog", self.source_path)
        self.assertEqual(ctx.exception.code, "backup_required")
        self.assertEqual(ctx.exception.status, 409)
        self.assertIn("RADD_BACKUP_MARKER_DIR", str(ctx.exception))

    def test_missing_marker_dir_refuses(self):
        guard = BackupGuard(marker_dir=os.path.join(self.tmp, "no-such-dir"))
        with self.assertRaises(ApiError) as ctx:
            guard.require("catalog", self.source_path)
        self.assertEqual(ctx.exception.code, "backup_required")
        self.assertIn("does not exist", str(ctx.exception))

    def test_empty_marker_dir_refuses(self):
        with self.assertRaises(ApiError) as ctx:
            self.guard.require("catalog", self.source_path)
        self.assertEqual(ctx.exception.code, "backup_required")
        self.assertIn("no verified backup marker", str(ctx.exception))

    def test_stale_marker_refuses(self):
        self._write_marker(age=timedelta(hours=25))
        with self.assertRaises(ApiError) as ctx:
            self.guard.require("catalog", self.source_path)
        self.assertIn("past the", str(ctx.exception))

    def test_integrity_skipped_refuses(self):
        # The real 2026-11-22 gate manifest says integrity "skipped":
        # that is a backup that was never fully verified, and it must
        # not authorise a live write.
        self._write_marker(integrity="skipped")
        with self.assertRaises(ApiError) as ctx:
            self.guard.require("catalog", self.source_path)
        self.assertIn("integrity_check", str(ctx.exception))

    def test_counts_not_exact_refuses(self):
        self._write_marker(counts_exact=False)
        with self.assertRaises(ApiError) as ctx:
            self.guard.require("catalog", self.source_path)
        self.assertIn("not verified exact", str(ctx.exception))

    def test_missing_count_refuses(self):
        counts = {"posts": 3, "releases": 2, "download_sources": 1,
                  "cat_links": 1}  # no page_count
        self._write_marker(counts=counts)
        with self.assertRaises(ApiError) as ctx:
            self.guard.require("catalog", self.source_path)
        self.assertIn("page_count", str(ctx.exception))

    def test_wrong_store_marker_refuses(self):
        self._write_marker(store="ledger")
        with self.assertRaises(ApiError):
            self.guard.require("catalog", self.source_path)
        # ...but the same marker authorises the ledger it covers.
        self.guard.require("ledger", self.source_path)

    def test_source_path_mismatch_refuses(self):
        self._write_marker(source_path="/some/other/catalog.db")
        with self.assertRaises(ApiError) as ctx:
            self.guard.require("catalog", self.source_path)
        self.assertIn("backs up", str(ctx.exception))

    def test_backup_file_gone_refuses(self):
        size, mtime = self._backup_stat()
        self._write_marker(backup_bytes=size, backup_mtime=mtime)
        os.unlink(self.backup_path)
        with self.assertRaises(ApiError) as ctx:
            self.guard.require("catalog", self.source_path)
        self.assertIn("backup file is gone", str(ctx.exception))

    def test_backup_size_changed_refuses(self):
        size, mtime = self._backup_stat()
        self._write_marker(backup_bytes=size, backup_mtime=mtime)
        with open(self.backup_path, "ab") as fh:
            fh.write(b"more")
        with self.assertRaises(ApiError) as ctx:
            self.guard.require("catalog", self.source_path)
        self.assertIn("changed size", str(ctx.exception))

    def test_malformed_marker_refuses_not_crashes(self):
        with open(os.path.join(self.marker_dir, "bad.verified.json"),
                  "w", encoding="utf-8") as fh:
            fh.write("{not json")
        with self.assertRaises(ApiError) as ctx:
            self.guard.require("catalog", self.source_path)
        # Fail closed, and the report says the file could not be read.
        self.assertIn("could not be read", str(ctx.exception))

    def test_future_verified_at_refuses(self):
        self._write_marker(verified_at=_now() + timedelta(hours=1))
        with self.assertRaises(ApiError) as ctx:
            self.guard.require("catalog", self.source_path)
        self.assertIn("in the future", str(ctx.exception))

    def test_unknown_format_refuses(self):
        self._write_marker(fmt="radd-backup-verification/2")
        with self.assertRaises(ApiError) as ctx:
            self.guard.require("catalog", self.source_path)
        self.assertIn("format", str(ctx.exception))

    def test_non_marker_files_ignored(self):
        with open(os.path.join(self.marker_dir, "notes.txt"),
                  "w", encoding="utf-8") as fh:
            fh.write("not a marker")
        with self.assertRaises(ApiError):
            self.guard.require("catalog", self.source_path)

    def test_status_never_raises(self):
        st = self.guard.status("catalog", self.source_path)
        self.assertFalse(st["ok"])
        self.assertIsNone(st["marker"])
        st = self.guard.status("nonsense-store", self.source_path)
        self.assertFalse(st["ok"])


class TestGuardAllows(GuardBase):
    def test_fresh_marker_allows_and_reports(self):
        size, mtime = self._backup_stat()
        path, _ = self._write_marker(backup_bytes=size, backup_mtime=mtime,
                                     age=timedelta(hours=1))
        result = self.guard.require("catalog", self.source_path)
        self.assertTrue(result["ok"])
        self.assertEqual(result["marker"], path)
        self.assertLess(result["age_s"], 3600 * 2)

    def test_marker_at_age_boundary(self):
        # 23h old passes a 24h window; 25h old does not.
        self._write_marker(age=timedelta(hours=23))
        self.guard.require("catalog", self.source_path)

    def test_max_age_env_override(self):
        guard = BackupGuard.from_env(
            {"RADD_BACKUP_MARKER_DIR": self.marker_dir,
             "RADD_BACKUP_MAX_AGE_S": "60"})
        self._write_marker(age=timedelta(minutes=2))
        with self.assertRaises(ApiError):
            guard.require("catalog", self.source_path)
        self._write_marker(age=timedelta(seconds=10),
                           name="fresh.verified.json")
        guard.require("catalog", self.source_path)

    def test_max_age_unreadable_falls_back_to_default(self):
        guard = BackupGuard.from_env(
            {"RADD_BACKUP_MARKER_DIR": self.marker_dir,
             "RADD_BACKUP_MAX_AGE_S": "not-a-number"})
        self.assertEqual(guard.max_age_s, 24 * 3600)

    def test_guard_never_writes_anything(self):
        size, mtime = self._backup_stat()
        self._write_marker(backup_bytes=size, backup_mtime=mtime)
        before = sorted(os.listdir(self.marker_dir))
        backup_before = self._backup_stat()
        self.guard.require("catalog", self.source_path)
        with self.assertRaises(ApiError):
            BackupGuard(marker_dir=os.path.join(self.tmp, "empty")).require(
                "catalog", self.source_path)
        self.assertEqual(sorted(os.listdir(self.marker_dir)), before)
        self.assertEqual(self._backup_stat(), backup_before)

    def test_newest_marker_wins(self):
        self._write_marker(age=timedelta(hours=25),
                           name="old.verified.json")
        self._write_marker(age=timedelta(minutes=5),
                           name="new.verified.json")
        result = self.guard.require("catalog", self.source_path)
        self.assertTrue(result["ok"])
        self.assertTrue(result["marker"].endswith("new.verified.json"))


class TestRecorder(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="adminv2-backup-record-")
        self.marker_dir = os.path.join(self.tmp, "markers")
        os.makedirs(self.marker_dir)
        self.source_path = os.path.join(self.tmp, "live.db")
        conn = sqlite3.connect(self.source_path)
        conn.executescript(CATALOG_DDL)
        conn.execute("INSERT INTO app_remote_config (id) VALUES (1)")
        conn.commit()
        conn.close()
        self.backup_path = os.path.join(self.tmp, "gate-backup-test.db")
        conn = sqlite3.connect(self.backup_path)
        conn.executescript(CATALOG_DDL)
        conn.execute("INSERT INTO app_remote_config (id) VALUES (1)")
        conn.execute("INSERT INTO posts (id, title) VALUES (1, 'a')")
        conn.execute("INSERT INTO posts (id, title) VALUES (2, 'b')")
        conn.execute("INSERT INTO posts (id, title) VALUES (3, 'c')")
        conn.execute("INSERT INTO releases (id, post_id) VALUES (1, 1)")
        conn.execute("INSERT INTO releases (id, post_id) VALUES (2, 2)")
        conn.execute("INSERT INTO download_sources (id, release_id) "
                     "VALUES (1, 1)")
        conn.execute("INSERT INTO cat_links (id, state) VALUES (1, 'active')")
        conn.commit()
        conn.close()

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _args(self, **overrides):
        args = ["--backup", self.backup_path, "--store", "catalog",
                "--source", self.source_path, "--integrity", "ok",
                "--counts-exact", "--marker-dir", self.marker_dir]
        for key, value in overrides.items():
            args += ["--" + key.replace("_", "-"), value]
        return args

    def test_recorder_writes_marker_the_guard_accepts(self):
        self.assertEqual(record_main(self._args()), 0)
        marker = os.path.join(self.marker_dir,
                              "gate-backup-test.verified.json")
        self.assertTrue(os.path.isfile(marker))
        with open(marker, encoding="utf-8") as fh:
            data = json.load(fh)
        self.assertEqual(data["format"], FORMAT)
        self.assertEqual(data["store"], "catalog")
        self.assertEqual(data["integrity_check"], "ok")
        self.assertTrue(data["counts_exact"])
        # Counts were read out of the backup file itself.
        self.assertEqual(data["counts"]["posts"], 3)
        self.assertEqual(data["counts"]["releases"], 2)
        self.assertEqual(data["counts"]["download_sources"], 1)
        self.assertEqual(data["counts"]["cat_links"], 1)
        self.assertGreater(data["counts"]["page_count"], 0)
        guard = BackupGuard(marker_dir=self.marker_dir)
        result = guard.require("catalog", self.source_path)
        self.assertTrue(result["ok"])
        self.assertEqual(result["marker"], marker)

    def test_recorder_refuses_integrity_not_ok(self):
        self.assertEqual(record_main(self._args(integrity="skipped")), 2)
        self.assertEqual(os.listdir(self.marker_dir), [])

    def test_recorder_refuses_without_counts_exact(self):
        args = ["--backup", self.backup_path, "--store", "catalog",
                "--source", self.source_path, "--integrity", "ok",
                "--marker-dir", self.marker_dir]
        self.assertEqual(record_main(args), 2)
        self.assertEqual(os.listdir(self.marker_dir), [])

    def test_recorder_refuses_missing_backup(self):
        args = self._args()
        args[args.index("--backup") + 1] = os.path.join(self.tmp, "gone.db")
        self.assertEqual(record_main(args), 2)

    def test_recorder_refuses_missing_source(self):
        args = self._args()
        args[args.index("--source") + 1] = os.path.join(self.tmp, "gone.db")
        self.assertEqual(record_main(args), 2)

    def test_recorder_refuses_non_catalog_backup(self):
        empty = os.path.join(self.tmp, "empty.db")
        conn = sqlite3.connect(empty)
        conn.execute("CREATE TABLE unrelated (id INTEGER PRIMARY KEY)")
        conn.commit()
        conn.close()
        args = self._args()
        args[args.index("--backup") + 1] = empty
        self.assertEqual(record_main(args), 2)

    def test_recorder_never_touches_live_or_backup(self):
        before_backup = os.stat(self.backup_path)
        before_source = os.stat(self.source_path)
        self.assertEqual(record_main(self._args()), 0)
        after_backup = os.stat(self.backup_path)
        after_source = os.stat(self.source_path)
        self.assertEqual((before_backup.st_size, int(before_backup.st_mtime)),
                         (after_backup.st_size, int(after_backup.st_mtime)))
        self.assertEqual((before_source.st_size, int(before_source.st_mtime)),
                         (after_source.st_size, int(after_source.st_mtime)))

    def test_recorder_ledger(self):
        ledger_source = os.path.join(self.tmp, "ledger-live.db")
        ledger_backup = os.path.join(self.tmp, "ledger-backup.db")
        for path in (ledger_source, ledger_backup):
            conn = sqlite3.connect(path)
            conn.executescript(LEDGER_DDL)
            conn.execute("INSERT INTO user_accounts (id, key_hash) "
                         "VALUES (1, 'abc')")
            conn.commit()
            conn.close()
        args = ["--backup", ledger_backup, "--store", "ledger",
                "--source", ledger_source, "--integrity", "ok",
                "--counts-exact", "--marker-dir", self.marker_dir]
        self.assertEqual(record_main(args), 0)
        guard = BackupGuard(marker_dir=self.marker_dir)
        guard.require("ledger", ledger_source)
        with self.assertRaises(ApiError):
            guard.require("catalog", ledger_source)


class LiveWriteBase(unittest.TestCase):
    """A temp catalog copy + a guard pointed at a temp marker dir."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="adminv2-backup-live-")
        self.marker_dir = os.path.join(self.tmp, "markers")
        os.makedirs(self.marker_dir)
        self.catalog_path = os.path.join(self.tmp, "catalog.db")
        conn = sqlite3.connect(self.catalog_path)
        conn.executescript(CATALOG_DDL)
        conn.execute("INSERT INTO app_remote_config (id) VALUES (1)")
        conn.execute("INSERT INTO posts (id, title) VALUES (1, 'a')")
        conn.execute("INSERT INTO releases (id, post_id) VALUES (1, 1)")
        conn.execute("INSERT INTO download_sources (id, release_id) "
                     "VALUES (1, 1)")
        conn.execute("INSERT INTO cat_links (id, state) VALUES (1, 'active')")
        conn.commit()
        conn.close()
        self.guard = BackupGuard(marker_dir=self.marker_dir)
        self.live = LiveStores(catalog_path=self.catalog_path,
                               backup_guard=self.guard)

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _record_marker(self):
        args = ["--backup", self.catalog_path, "--store", "catalog",
                "--source", self.catalog_path, "--integrity", "ok",
                "--counts-exact", "--marker-dir", self.marker_dir]
        self.assertEqual(record_main(args), 0)

    def _catalog_row(self):
        conn = sqlite3.connect(self.catalog_path)
        conn.row_factory = sqlite3.Row
        row = conn.execute(
            "SELECT * FROM app_remote_config WHERE id=1").fetchone()
        conn.close()
        return dict(row)


class TestLiveWritesGuarded(LiveWriteBase):
    def test_save_remote_config_refused_without_marker(self):
        with self.assertRaises(ApiError) as ctx:
            self.live.save_remote_config({"vip_monthly_pkr": 201})
        self.assertEqual(ctx.exception.code, "backup_required")
        self.assertEqual(ctx.exception.status, 409)
        # The write never happened.
        self.assertEqual(self._catalog_row()["vip_monthly_pkr"], 200)

    def test_save_remote_config_allowed_with_fresh_marker(self):
        self._record_marker()
        self.live.save_remote_config({"vip_monthly_pkr": 201})
        self.assertEqual(self._catalog_row()["vip_monthly_pkr"], 201)

    def test_reads_are_never_gated(self):
        # An operator must still SEE the catalog when no backup exists:
        # reads go through _ro, which the guard never touches.
        counts = self.live.catalog_counts()
        self.assertEqual(counts["posts"], 1)

    def test_ledger_write_refused_without_marker(self):
        ledger_path = os.path.join(self.tmp, "ledger.db")
        conn = sqlite3.connect(ledger_path)
        conn.executescript(LEDGER_DDL)
        conn.commit()
        conn.close()
        ledger_live = LiveStores(ledger_path=ledger_path,
                                 backup_guard=self.guard)
        with self.assertRaises(ApiError) as ctx:
            ledger_live._rw(ledger_path, "ledger")
        self.assertEqual(ctx.exception.code, "backup_required")

    def test_guard_none_instance_is_unguarded(self):
        # Fixture/test instances (backup_guard=None) behave exactly as
        # before: no marker needed. Only live instances are guarded.
        unguarded = LiveStores(catalog_path=self.catalog_path)
        unguarded.save_remote_config({"vip_monthly_pkr": 202})
        self.assertEqual(self._catalog_row()["vip_monthly_pkr"], 202)


class TestServiceGuarded(LiveWriteBase):
    def setUp(self):
        super().setUp()
        self._old_env = dict(os.environ)
        os.environ["RADD_BACKUP_MARKER_DIR"] = self.marker_dir
        self.addCleanup(self._restore_env)
        self.svc, self.owner, _aud = make_service()
        # Wire the temp catalog in as the live store, with the guard
        # built from the environment exactly as run.py does.
        self.svc.live = LiveStores(
            catalog_path=self.catalog_path,
            backup_guard=BackupGuard.from_env())

    def _restore_env(self):
        os.environ.clear()
        os.environ.update(self._old_env)

    def test_put_monetization_refused_without_marker(self):
        with self.assertRaises(ApiError) as ctx:
            self.svc.put_monetization(self.owner, {"vip_monthly_pkr": 201})
        self.assertEqual(ctx.exception.code, "backup_required")
        # The live row is untouched; the panel draft stays staged so
        # the operator can record a backup and press Publish again
        # without re-entering the values.
        self.assertEqual(self._catalog_row()["vip_monthly_pkr"], 200)
        row = self.svc._config_row()
        self.assertEqual(row["vip_monthly_pkr"], 201)

    def test_put_monetization_allowed_with_fresh_marker(self):
        self._record_marker()
        out = self.svc.put_monetization(self.owner, {"vip_monthly_pkr": 201})
        self.assertEqual(out["publish_status"], "ok")
        self.assertEqual(self._catalog_row()["vip_monthly_pkr"], 201)

    def test_backup_status_reports(self):
        status = self.svc.backup_status()
        self.assertIn("catalog", status)
        self.assertFalse(status["catalog"]["ok"])
        self._record_marker()
        status = self.svc.backup_status()
        self.assertTrue(status["catalog"]["ok"])
        self.assertIsNotNone(status["catalog"]["marker"])


if __name__ == "__main__":
    unittest.main()
