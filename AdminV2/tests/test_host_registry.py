"""Tests for the panel host-registry feature (2026-11-18).

The admin panel edits host_registry.json — the same file the prober
and the resolver read — so the operator can fix a moved download
host himself. Fixtures are MOCK only; no real credential, token, or
URL is used anywhere in these tests.

Pinned here:
* list/get read the JSON through the live store (no admin-DB table);
  an unknown host 404s; no live store means LiveUnavailable;
* validate is a dry run (nothing is written) and reports the
  checker's verdict;
* apply writes the entry atomically, takes a backup before the
  first live write, and audits the change; a failed check refuses
  unless force is set, and a force-saved entry is recorded
  unverified (never applied at serve time);
* writes are owner-only (an auditor is refused), and the routes
  enforce auth + CSRF the same way the domains routes do.
"""
import glob
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from helpers import make_service  # noqa: E402
from admin_v2.errors import ApiError  # noqa: E402
from admin_v2.live_stores import LiveStores, LiveUnavailable  # noqa: E402
from admin_v2.server import AdminApp  # noqa: E402


def good_host_checker(old_host, new_host):
    return {"ok": True, "method": "root-redirect",
            "detail": "https://%s/ -> https://%s/" % (old_host, new_host)}


def bad_host_checker(old_host, new_host):
    return {"ok": False, "method": None,
            "detail": "%s does not redirect to %s" % (old_host, new_host)}


def _seed_registry(path):
    data = {
        "version": "2.0",
        "updated_at": "2026-11-18T00:00:00Z",
        "hosts": {
            "hubcloud.ist": {
                "current_domain": "hubcloud.mov",
                "evidence": "audit-confirmed",
                "status": "active",
                "last_verified": "2026-11-18T00:00:00Z",
                "verified_by": "audit",
                "rows_affected": 167866,
                "dead_rows": 929,
                "known_mirrors": ["https://hubcloud.mov"],
                "rotation_history": [
                    {"domain": "hubcloud.mov", "first_seen": "2026-11-18T00:00:00Z",
                     "last_seen": "2026-11-18T00:00:00Z", "status": "active",
                     "evidence": "audit-confirmed"}],
            },
            "mobilejsr.com": {
                "current_domain": "",
                "evidence": "audit-dead",
                "status": "dead",
                "last_verified": "2026-11-18T00:00:00Z",
                "verified_by": "audit",
                "rows_affected": 110092,
                "dead_rows": 1961,
                "known_mirrors": [],
                "rotation_history": [],
            },
        },
    }
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")
    return data


class ApiClient:
    """Minimal JSON client over AdminApp.handle (mirrors test_api.py)."""

    def __init__(self, app):
        self.app = app
        self.cookie = None
        self.csrf = None

    def call(self, method, path, body=None, headers=None, raw_headers=None):
        h = {"Content-Type": "application/json"}
        if self.cookie:
            h["Cookie"] = self.cookie
        if self.csrf:
            h["X-CSRF-Token"] = self.csrf
        if headers:
            h.update(headers)
        if raw_headers is not None:
            h = raw_headers
        data = json.dumps(body).encode() if body is not None else b""
        status, ctype, payload, cookie, location = self.app.handle(
            method, path, {}, h, data)
        if cookie:
            self.cookie = cookie.split(";")[0]
        out = payload.decode()
        try:
            parsed = json.loads(out)
        except Exception:
            parsed = {"_html": out}
        return status, parsed, location

    def login(self, username="owner", password="correct horse battery staple"):
        status, data, _ = self.call("POST", "/admin/login",
                                    {"username": username, "password": password})
        assert status == 200, data
        self.csrf = data["data"]["csrf_token"]
        return data


class TestHostRegistryService(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix="host-reg-panel-")
        self.reg_path = os.path.join(self.tmpdir, "host_registry.json")
        _seed_registry(self.reg_path)
        self.svc, self.owner, self.auditor = make_service()
        self.svc.live = LiveStores(host_registry_path=self.reg_path)

    def test_list_hosts(self):
        res = self.svc.list_hosts(self.owner)
        self.assertEqual(res["count"], 2)
        self.assertEqual(res["hosts"][0]["old_host"], "hubcloud.ist")
        self.assertEqual(res["hosts"][0]["current_domain"], "hubcloud.mov")
        self.assertEqual(res["hosts"][1]["status"], "dead")

    def test_get_host_and_unknown_404(self):
        got = self.svc.get_host(self.owner, "hubcloud.ist")
        self.assertEqual(got["current_domain"], "hubcloud.mov")
        self.assertEqual(got["rows_affected"], 167866)
        with self.assertRaises(ApiError):
            self.svc.get_host(self.owner, "nope.example")

    def test_list_requires_live_store(self):
        self.svc.live = None
        with self.assertRaises(LiveUnavailable):
            self.svc.list_hosts(self.owner)

    def test_validate_is_dry_run(self):
        res = self.svc.validate_host(self.owner, "hubcloud.ist",
                                     {"new_host": "hubcloud.new"},
                                     checker=good_host_checker)
        self.assertTrue(res["ok"])
        self.assertEqual(res["method"], "root-redirect")
        # Nothing was written: the entry still points at .mov.
        self.assertEqual(
            self.svc.get_host(self.owner, "hubcloud.ist")["current_domain"],
            "hubcloud.mov")

    def test_validate_reports_failure(self):
        res = self.svc.validate_host(self.owner, "hubcloud.ist",
                                     {"new_host": "hubcloud.new"},
                                     checker=bad_host_checker)
        self.assertFalse(res["ok"])
        self.assertIn("does not redirect", res["detail"])

    def test_validate_without_checker_cannot_pass(self):
        res = self.svc.validate_host(self.owner, "hubcloud.ist",
                                     {"new_host": "hubcloud.new"})
        self.assertFalse(res["ok"])
        self.assertIn("no checker wired", res["detail"])

    def test_apply_writes_active_and_backs_up(self):
        res = self.svc.apply_host(self.owner, "hubcloud.ist",
                                  {"new_host": "hubcloud.new"},
                                  checker=good_host_checker)
        self.assertEqual(res["current_domain"], "hubcloud.new")
        self.assertEqual(res["previous_domain"], "hubcloud.mov")
        self.assertEqual(res["status"], "active")
        self.assertEqual(res["evidence"], "manual")
        self.assertIsNotNone(res["backup"])
        self.assertEqual(len(glob.glob(self.reg_path + ".bak-*")), 1)
        got = self.svc.get_host(self.owner, "hubcloud.ist")
        self.assertEqual(got["current_domain"], "hubcloud.new")
        self.assertEqual(got["verified_by"], "panel")
        # The audit trail records the change.
        row = self.svc.admin.execute(
            "SELECT * FROM admin_audit_log WHERE action='host.apply' "
            "ORDER BY id DESC LIMIT 1").fetchone()
        self.assertIsNotNone(row)

    def test_apply_adds_new_mapping_from_body(self):
        res = self.svc.apply_host(self.owner, None,
                                  {"old_host": "fresh.old",
                                   "new_host": "fresh.new"},
                                  checker=good_host_checker)
        self.assertEqual(res["old_host"], "fresh.old")
        self.assertEqual(res["status"], "active")
        self.assertEqual(
            self.svc.get_host(self.owner, "fresh.old")["current_domain"],
            "fresh.new")

    def test_apply_failed_check_refuses_without_force(self):
        with self.assertRaises(ApiError):
            self.svc.apply_host(self.owner, "hubcloud.ist",
                                {"new_host": "hubcloud.new"},
                                checker=bad_host_checker)
        self.assertEqual(
            self.svc.get_host(self.owner, "hubcloud.ist")["current_domain"],
            "hubcloud.mov")

    def test_apply_failed_check_with_force_records_unverified(self):
        res = self.svc.apply_host(self.owner, "hubcloud.ist",
                                  {"new_host": "hubcloud.new", "force": True},
                                  checker=bad_host_checker)
        self.assertEqual(res["status"], "unverified")
        self.assertEqual(res["evidence"], "manual-unverified")
        got = self.svc.get_host(self.owner, "hubcloud.ist")
        self.assertEqual(got["status"], "unverified")
        self.assertEqual(got["verified_by"], "panel-force")

    def test_apply_is_owner_only(self):
        with self.assertRaises(ApiError):
            self.svc.apply_host(self.auditor, "hubcloud.ist",
                                {"new_host": "hubcloud.new"},
                                checker=good_host_checker)

    def test_list_is_readable_by_auditor(self):
        res = self.svc.list_hosts(self.auditor)
        self.assertEqual(res["count"], 2)


class TestHostRegistryRoutes(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.mkdtemp(prefix="host-reg-routes-")
        self.reg_path = os.path.join(self.tmpdir, "host_registry.json")
        _seed_registry(self.reg_path)
        self.svc, self.owner, self.auditor = make_service()
        self.svc.live = LiveStores(host_registry_path=self.reg_path)
        self.app = AdminApp(self.svc, host_checker=good_host_checker)
        self.client = ApiClient(self.app)
        self.client.login()

    def test_unauthenticated_hosts_401(self):
        anon = ApiClient(self.app)
        status, data, _ = anon.call("GET", "/admin/api/v2/hosts",
                                    raw_headers={})
        self.assertEqual(status, 401)

    def test_list_route(self):
        status, data, _ = self.client.call("GET", "/admin/api/v2/hosts")
        self.assertEqual(status, 200)
        self.assertEqual(data["data"]["count"], 2)

    def test_get_route(self):
        status, data, _ = self.client.call(
            "GET", "/admin/api/v2/hosts/hubcloud.ist")
        self.assertEqual(status, 200)
        self.assertEqual(data["data"]["current_domain"], "hubcloud.mov")

    def test_validate_route(self):
        status, data, _ = self.client.call(
            "POST", "/admin/api/v2/hosts/hubcloud.ist/validate",
            {"new_host": "hubcloud.new"})
        self.assertEqual(status, 200)
        self.assertTrue(data["data"]["ok"])

    def test_apply_route(self):
        status, data, _ = self.client.call(
            "POST", "/admin/api/v2/hosts/hubcloud.ist/apply",
            {"new_host": "hubcloud.new"})
        self.assertEqual(status, 200)
        self.assertEqual(data["data"]["current_domain"], "hubcloud.new")

    def test_add_route(self):
        status, data, _ = self.client.call(
            "POST", "/admin/api/v2/hosts",
            {"old_host": "fresh.old", "new_host": "fresh.new"})
        self.assertEqual(status, 200)
        self.assertEqual(data["data"]["old_host"], "fresh.old")

    def test_hosts_page_renders(self):
        status, data, _ = self.client.call("GET", "/admin/hosts")
        self.assertEqual(status, 200)
        self.assertIn("hubcloud.ist", data["_html"])
        self.assertIn("hubcloud.mov", data["_html"])
        self.assertIn("Add a mapping", data["_html"])

    def test_hosts_page_without_live_store(self):
        self.svc.live = None
        status, data, _ = self.client.call("GET", "/admin/hosts")
        self.assertEqual(status, 200)
        self.assertIn("not available", data["_html"])


if __name__ == "__main__":
    unittest.main()
