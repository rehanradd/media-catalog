"""PNL-07: every tuning control has a real consumer — none is stored-only.

The 2026-11-05 grep behind the old "13 of 20 controls are stored-only"
labels predates the wiring that landed since: the 11 backend knobs are
read by Fast_Fetch through panel_feed (tuning_knob / read_tuning_knobs)
and the other 13 ride the public remote-config / panel feed the app
polls. These tests pin that, two ways:

1. The panel's own labels (services.tuning_consumers) name a real
   consumer for every key in store.TUNING_DEFAULTS — no key answers
   the "stored, not yet applied" fallback, and the label keys are
   exactly the tuning keys (no dead entries for keys that are not
   tuning controls, as the old vip_* entries were).
2. The round trip: a value saved with put_tuning comes back through
   the backend reader that claims to consume it (panel_feed, read
   against the same fixture panel database the service just wrote).
   A label that names a consumer the code never calls would pass (1)
   and fail (2). The round trip caught a real bug while it was being
   written: read_tuning_knobs() did one bulk SELECT over all 13
   backend knobs, but the panel only stores 11 of them — the two
   quarantine knobs have no panel column — so the SELECT failed and
   the bulk reader answered defaults for every knob on any real
   panel database. panel_feed now reads each knob on its own
   (test_panel_feed.py pins the real-panel shape).

Fixtures are MOCK only; no real credential, token, URL, or
transaction id is used anywhere in these tests.
"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from helpers import make_service  # noqa: E402
from admin_v2.server import AdminApp  # noqa: E402
from admin_v2.store import TUNING_DEFAULTS  # noqa: E402

# panel_feed lives in Fast_Fetch/; it is the backend reader whose
# consumers the labels name, so import it the way test_panel_feed.py
# does (Fast_Fetch/ on sys.path).
_REPO_ROOT = os.path.dirname(os.path.dirname(
    os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(_REPO_ROOT, "Fast_Fetch"))
import panel_feed  # noqa: E402

STORED_ONLY = "stored, not yet applied by the backend"

#: The 11 knobs the backend reads through panel_feed.tuning_knob /
#: read_tuning_knobs, and where each one bites.
BACKEND_KNOBS = {
    "resolver_concurrency": "Fast_Fetch/fast_fetch.py batch resolver",
    "candidate_cap": "Fast_Fetch/api_v2.py play route",
    "cache_ttl_h": "Fast_Fetch/resolved_cache.py resolved cache",
    "warmup_count": "Fast_Fetch/prefetch_worker.py prefetch pool",
    "warmup_interval_h": "Fast_Fetch/resolved_cache.py prefetch pool",
    "rate_limit_play_per_min": "Fast_Fetch/viewer.py play rate limit",
    "import_cap": "Fast_Fetch/fast_fetch.py catalog sync",
    "import_concurrency": "Fast_Fetch/fast_fetch.py catalog sync",
    "prefetch_queue": "Fast_Fetch/prefetch_worker.py prefetch pool",
    "prefetch_concurrency": "Fast_Fetch/prefetch_worker.py prefetch pool",
    "domain_check_interval_min": "Fast_Fetch/domain_resolver.py domain re-verify",
}

#: Non-default values (inside the panel's own TUNING_BOUNDS) for the
#: round-trip tests: far enough from the seed defaults that a reader
#: answering defaults instead of the stored value fails loudly.
BACKEND_VALUES = {
    "resolver_concurrency": 4, "candidate_cap": 20, "cache_ttl_h": 12,
    "warmup_count": 50, "warmup_interval_h": 48,
    "rate_limit_play_per_min": 60, "import_cap": 100,
    "import_concurrency": 3, "prefetch_queue": 250,
    "prefetch_concurrency": 2, "domain_check_interval_min": 120,
}


def _login(app):
    status, _ctype, payload, cookie, _loc = app.handle(
        "POST", "/admin/login", {},
        {"Content-Type": "application/json"},
        json.dumps({"username": "owner",
                    "password": "correct horse battery staple"}).encode())
    assert status == 200, payload.decode()
    return cookie.split(";")[0]


class TestTuningConsumers(unittest.TestCase):
    def setUp(self):
        self.svc, self.owner, self.auditor = make_service()
        self.app = AdminApp(self.svc)
        self.cookie = _login(self.app)

    def test_every_knob_has_a_consumer_label(self):
        consumers = self.svc.tuning_consumers(self.owner)
        # The label keys are exactly the tuning keys: no missing knob,
        # and no dead entry for a key that is not a tuning control.
        self.assertEqual(set(consumers), set(TUNING_DEFAULTS))
        for name in TUNING_DEFAULTS:
            self.assertNotEqual(consumers[name], STORED_ONLY, name)
            self.assertTrue(consumers[name], name)

    def test_backend_knob_labels_name_the_reader(self):
        consumers = self.svc.tuning_consumers(self.owner)
        for name in BACKEND_KNOBS:
            self.assertIn("panel_feed.tuning_knob", consumers[name], name)
            # The reader must actually know the knob: a label naming a
            # knob panel_feed never heard of is a lie in the other direction.
            self.assertIn(name, panel_feed.TUNING_KNOBS, name)
        self.assertTrue(callable(panel_feed.read_tuning_knobs))

    def test_app_knob_labels_name_their_channel(self):
        consumers = self.svc.tuning_consumers(self.owner)
        for name in ("free_quality_cap", "theme_mode", "theme_accent",
                     "theme_dynamic_color", "theme_hero_autoplay",
                     "theme_hero_interval_s", "hero_max", "tabs",
                     "disclaimer_text", "disclaimer_version",
                     "disclaimer_email"):
            self.assertIn("remote-config", consumers[name], name)
        for name in ("home_sections_max", "section_cap"):
            self.assertIn("panel feed", consumers[name], name)
        for reader in ("read_theme", "read_tabs", "read_disclaimer",
                       "read_section_caps", "read_free_quality_cap"):
            self.assertTrue(callable(getattr(panel_feed, reader)), reader)

    def test_stored_backend_knob_reaches_the_reader(self):
        self.svc.put_tuning(self.owner, dict(BACKEND_VALUES, reason="test"))
        knobs = panel_feed.read_tuning_knobs(self.svc.admin_path)
        for name, value in BACKEND_VALUES.items():
            self.assertEqual(knobs[name], value, name)

    def test_stored_app_knobs_reach_the_readers(self):
        self.svc.put_tuning(self.owner, {
            "free_quality_cap": "480p", "theme_mode": "light",
            "theme_accent": "#123ABC", "theme_dynamic_color": 1,
            "theme_hero_autoplay": 0, "theme_hero_interval_s": 8,
            "hero_max": 3, "tabs": "home,search,live_tv",
            "disclaimer_text": "Test disclaimer", "disclaimer_version": 7,
            "disclaimer_email": "takedown@example.org",
            "home_sections_max": 5, "section_cap": 10,
            "reason": "test"})
        path = self.svc.admin_path
        self.assertEqual(panel_feed.read_theme(path), {
            "mode": "light", "accent": "#123ABC", "dynamic_color": True,
            "hero_autoplay": False, "hero_interval_s": 8, "hero_max": 3})
        self.assertEqual(panel_feed.read_tabs(path),
                         ["home", "search", "live_tv"])
        self.assertEqual(panel_feed.read_disclaimer(path), {
            "text": "Test disclaimer", "version": 7,
            "email": "takedown@example.org"})
        self.assertEqual(panel_feed.read_free_quality_cap(path), "480p")
        self.assertEqual(panel_feed.read_section_caps(path),
                         {"sections_max": 5, "section_cap": 10})

    def test_tuning_page_labels_every_control_applies(self):
        status, _c, payload, _k, _l = self.app.handle(
            "GET", "/admin/tuning", {}, {"Cookie": self.cookie}, b"")
        self.assertEqual(status, 200)
        html = payload.decode()
        self.assertNotIn("Stored only", html)
        # One "Applies:" note per rendered control: 24 tuning keys.
        self.assertEqual(html.count("Applies:"), len(TUNING_DEFAULTS))
        for name in TUNING_DEFAULTS:
            self.assertIn('name="%s"' % name, html)


if __name__ == "__main__":
    unittest.main()
