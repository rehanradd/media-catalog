"""Run the Admin Panel v2 server (NOT a live deploy).

    python3 run.py --base-dir ./data --port 8080

Data (admin_v2.db + fixture DBs) lives under --base-dir. Fixture DBs
contain MOCK works/accounts only and must never be copied to a live
system as real data. Live deployment is a separate gated step:
branch + hosted CI + backup + live test + rollback note.

Live stores (optional, read from env; any WRITE to them needs a
backup before first live use — see the Phase 2 handoff):
    RADD_CATALOG_DB      merged catalog DB (posts, app_remote_config, ...)
    RADD_USER_STATE_DB   user_state.db (accounts + payment ledger)
    RADD_DOMAIN_REGISTRY domain_registry.json
    RADD_HOST_REGISTRY   host_registry.json (download hosts; default is
                         next to the domain registry, else the fleet dir)
    RADD_STATUS_DIR      directory of <site>_status.json files
    RADD_CERT_PATH       fullchain.pem for the cert-expiry read
    RADD_FLEET_DIR       Fast_Fetch dir (crawler scripts)
    RADD_TOTP_KEY        base64 TOTP encryption key (rotated 2026-11-11;
                         the shipped default must never be used live).
  --background-jobs starts the opt-in staggered domain-health +
  daily-analytics thread (OD-10). Off by default.
"""
import argparse
import base64
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# PNL-01 redesign (§0A N3/D9): the code-copy drift check runs BEFORE
# any admin_v2 import, so a diverged copy cannot be masked by the
# import itself. The admin_v2 imports are lazy inside main() for the
# same reason.
_DRIFT_CHECK_FILES = ("services.py", "live_stores.py", "store.py",
                      "errors.py")


def _check_code_copies(repo_copy=None, root_copy=None):
    """SHA-256 drift guard for the two panel code copies (D9).

    The live layout keeps a second copy of the panel package at the
    workspace root (admin_v2/, run.py, tests/) beside the repo copy
    (AdminV2/admin_v2/). If both exist they must be byte-identical;
    a silent drift means the running code is not the reviewed code.
    Returns a list of divergent relative paths (empty = identical or
    single-copy layout, e.g. running from inside AdminV2/ itself).
    The paths are injectable for tests; production passes none."""
    if repo_copy is None or root_copy is None:
        here = os.path.dirname(os.path.abspath(__file__))
        repo_copy = os.path.join(here, "admin_v2")
        root_copy = os.path.join(os.path.dirname(here), "admin_v2")
    if not (os.path.isdir(repo_copy) and os.path.isdir(root_copy)):
        return []
    divergent = []
    for name in _DRIFT_CHECK_FILES:
        a = os.path.join(repo_copy, name)
        b = os.path.join(root_copy, name)
        try:
            with open(a, "rb") as f:
                ha = hashlib.sha256(f.read()).hexdigest()
            with open(b, "rb") as f:
                hb = hashlib.sha256(f.read()).hexdigest()
        except OSError:
            divergent.append(name)
            continue
        if ha != hb:
            divergent.append(name)
    return divergent


def _live_paths_ok():
    """Live-catalog paths the panel needs before it may serve (§0A N3).

    Returns a list of problems (empty = fine). Only a MISSING
    RADD_CATALOG_DB setting aborts the boot: with no path configured
    at all there is no live store to configure, and a production
    panel must not start as if it could publish. A configured path
    whose file is missing (or vanishes later) is NOT a boot problem —
    the panel starts and every publish refuses with 503
    live_unavailable (N3 configured-but-unavailable)."""
    catalog = os.environ.get("RADD_CATALOG_DB", "").strip()
    if not catalog:
        return ["RADD_CATALOG_DB is not set"]
    return []


def _totp_key():
    """TOTP encryption key from RADD_TOTP_KEY (base64), or None to let
    the Service use its default (fixture only — never live)."""
    raw = os.environ.get("RADD_TOTP_KEY", "").strip()
    if not raw:
        return None
    try:
        key = base64.b64decode(raw, validate=True)
    except Exception:
        return None
    return key if len(key) >= 16 else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-dir", default="./data")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--strict-host", action="store_true",
                        help="Gate mode: /admin only on the panel domain over HTTPS.")
    parser.add_argument("--background-jobs", action="store_true",
                        help="Start the opt-in staggered domain-health + daily analytics thread.")
    args = parser.parse_args()
    # D9 first, before any admin_v2 import: a diverged second copy
    # must abort the boot, not serve the wrong code.
    divergent = _check_code_copies()
    if divergent:
        print("REFUSING TO START: panel code copies diverged: %s"
              % ", ".join(divergent), file=sys.stderr)
        print("Sync AdminV2/admin_v2/ and the root admin_v2/ copies "
              "(gate-time diff -r) and restart.", file=sys.stderr)
        sys.exit(2)
    # §0A N3: the panel serves only with a live catalog configured.
    # (A configured path whose file is missing is a publish-time 503,
    # not a boot failure — see _live_paths_ok.)
    problems = _live_paths_ok()
    if problems:
        print("REFUSING TO START: %s" % "; ".join(problems),
              file=sys.stderr)
        print("Set RADD_CATALOG_DB to the live catalog.", file=sys.stderr)
        sys.exit(2)
    # Lazy imports: only after the drift guard has passed.
    from admin_v2.domain_check import check_domain
    from admin_v2.live_stores import check_host_move, check_stream_url
    from admin_v2.server import serve
    from admin_v2.services import Service
    totp_key = _totp_key()
    svc = Service(
        args.base_dir,
        totp_key=totp_key if totp_key is not None else b"workspace-totp-key-change-at-gate",
        live_catalog_path=os.environ.get("RADD_CATALOG_DB"),
        live_ledger_path=os.environ.get("RADD_USER_STATE_DB"),
        live_registry_path=os.environ.get("RADD_DOMAIN_REGISTRY"),
        live_host_registry_path=os.environ.get("RADD_HOST_REGISTRY"),
        live_status_dir=os.environ.get("RADD_STATUS_DIR"),
        live_cert_path=os.environ.get("RADD_CERT_PATH"),
        live_fleet_base_dir=os.environ.get("RADD_FLEET_DIR"),
    )
    if args.background_jobs:
        svc.start_background_jobs(domain_checker=check_domain)
    host_checker = lambda old, new: check_host_move(
        old, new,
        fleet_base_dir=os.environ.get("RADD_FLEET_DIR")
        or "/opt/radd-stream/Fast_Fetch")
    serve(svc, host=args.host, port=args.port, strict_host=args.strict_host,
          domain_checker=check_domain, stream_checker=check_stream_url,
          host_checker=host_checker)


if __name__ == "__main__":
    main()
