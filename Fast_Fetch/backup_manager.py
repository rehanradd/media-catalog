#!/usr/bin/env python3
"""
backup_manager.py — Atomic SQLite Database Snapshots and Multi-Tier Cloud Backup

Features:
- Safe online SQLite snapshots using VACUUM INTO (never corrupts live WAL databases).
- Gzip compression and SHA-256 integrity verification.
- Direct API backup uploaders using 100% Python standard library (no pip packages):
    1. GitHub Releases API (using GITHUB_TOKEN)
    2. Telegram Bot Document API (using TELEGRAM_BOT_TOKEN & TELEGRAM_CHAT_ID)
    3. Backblaze B2 / Cloudflare S3 uploaders
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import hmac
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE_DIR = Path(__file__).parent.resolve()



def calculate_sha256(filepath: str | Path) -> str:
    """Calculate SHA-256 hash of a file in chunks."""
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def _db_row_counts(db_path: Path, conn=None) -> dict:
    """Row counts for a catalog database, tolerant of the per-site schema families.

    Pass an open connection to count on it instead of opening a second one:
    _fullgate_db_info lends its read-only, immutable connection so no extra
    open touches the file at all. A passed connection is borrowed, never
    closed here.
    """
    counts: dict[str, int] = {}
    own_conn = conn is None
    try:
        if own_conn:
            conn = sqlite3.connect(str(db_path))
    except sqlite3.Error:
        return counts
    try:
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for table in ("posts", "releases", "download_sources", "cat_links", "sources", "post_sources", "sitemap_cache"):
            if table in tables:
                counts[table] = conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        if "posts" in tables and any(
            r[1] == "is_18_plus" for r in conn.execute("PRAGMA table_info(posts)")
        ):
            counts["posts_adult"] = conn.execute(
                "SELECT COUNT(*) FROM posts WHERE is_18_plus = 1"
            ).fetchone()[0]
        if "sitemap_cache" in tables:
            counts["sitemap_pending"] = conn.execute(
                "SELECT COUNT(*) FROM sitemap_cache WHERE scraped = 0"
            ).fetchone()[0]
            counts["sitemap_failed"] = conn.execute(
                "SELECT COUNT(*) FROM sitemap_cache WHERE scraped = -1"
            ).fetchone()[0]
    except sqlite3.Error:
        pass
    finally:
        if own_conn:
            conn.close()
    return counts


def safe_vacuum_sqlite(source_db: str | Path, target_db: str | Path) -> bool:
    """
    Creates a clean, consistent snapshot of a live SQLite database.
    Does NOT disrupt active crawler processes writing to WAL.
    """
    source = Path(source_db)
    target = Path(target_db)
    if not source.exists():
        return False

    if target.exists():
        target.unlink()

    target.parent.mkdir(parents=True, exist_ok=True)

    try:
        conn = sqlite3.connect(str(source))
        conn.execute(f"VACUUM INTO '{target.as_posix()}'")
        conn.close()
        return True
    except Exception as e:
        print(f"[!] Safe vacuum failed for {source.name}: {e}", file=sys.stderr)
        return False


def _git_head_sha() -> str:
    """Short HEAD SHA of the checkout that produced this backup, empty if unavailable."""
    try:
        res = subprocess.run(
            ["git", "-C", str(BASE_DIR.parent), "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True, timeout=10,
        )
        return res.stdout.strip() if res.returncode == 0 else ""
    except (OSError, subprocess.SubprocessError):
        return ""


STAGING_ENV = "RADD_BACKUP_STAGING"
KEEP_ENV = "RADD_BACKUP_KEEP"
DEFAULT_KEEP = 5


class BackupSpaceError(OSError):
    """Raised before any bytes are written, when the filesystem cannot hold the snapshot."""


def staging_root() -> Path:
    """Where the vacuumed snapshots and the in-progress archive are built.

    Default is the system temp directory, which lives on the same filesystem as the archives
    and as the databases. RADD_BACKUP_STAGING points it at a filesystem that has the room.
    """
    override = os.environ.get(STAGING_ENV, "").strip()
    if override:
        root = Path(override)
        root.mkdir(parents=True, exist_ok=True)
        return root
    return Path(tempfile.gettempdir())


def free_bytes(path: Path) -> int:
    return shutil.disk_usage(str(path)).free


def required_bytes(db_paths: list[Path]) -> int:
    """Source bytes plus the compressed archive they produce, generously 2x the inputs."""
    return 2 * sum(db.stat().st_size for db in db_paths if db.exists())


def _check_space(db_paths: list[Path], work_dir: Path, out_dir: Path) -> None:
    need = required_bytes(db_paths)
    available = min(free_bytes(work_dir), free_bytes(out_dir))
    if need > available:
        raise BackupSpaceError(
            f"refusing to start: staging + output need {need / 2**30:.2f} GiB, "
            f"{available / 2**30:.2f} GiB free on {work_dir} / {out_dir}. "
            f"Set {STAGING_ENV} to a filesystem with room, or prune {out_dir}."
        )


def hf_remote_listing(repo_id: str | None = None) -> dict[str, int] | None:
    """Name -> size in bytes for the files on the Hugging Face dataset, None if unknown.

    A prune may only forget an archive this confirms exists off-site. None — no token, no
    network, a non-2xx, a truncated page — means "forget nothing", because a backup we never
    uploaded is the only copy we have.
    """
    repo_id = repo_id or os.environ.get("HF_DATASET_REPO", "")
    token = os.environ.get("HF_TOKEN", "")
    if not repo_id or not token:
        return None
    url = f"https://huggingface.co/api/datasets/{repo_id}/tree/main?recursive=true&pageSize=1000"
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            entries = json.loads(response.read().decode("utf-8"))
    except Exception as e:
        print(f"[!] Could not list {repo_id}: {e}", file=sys.stderr)
        return None
    if not isinstance(entries, list):
        return None
    listing: dict[str, int] = {}
    for entry in entries:
        if isinstance(entry, dict) and entry.get("type") == "file" and entry.get("path"):
            listing[entry["path"]] = int(entry.get("size") or 0)
    return listing


def prune_local_archives(
    out_dir: Path,
    *,
    keep: int = DEFAULT_KEEP,
    offsite: dict[str, int] | None = None,
) -> int:
    """Delete all but the newest `keep` archives, and only those confirmed off-site.

    Confirmed means the dataset carries the same name at the same byte length. Returns the
    number of files deleted; an archive the listing does not vouch for survives forever, which
    is the whole point — the old prune ranked by filename and deleted files that had failed to
    upload on the night their own run broke.
    """
    if offsite is None:
        offsite = hf_remote_listing()
    if offsite is None:
        print("[*] No off-site listing; keeping every local archive.", flush=True)
        return 0
    archives = sorted(out_dir.glob("radd-stream-backup_*.tar.gz"), key=lambda p: p.name, reverse=True)
    removed = 0
    for stale in archives[keep:]:
        try:
            size = stale.stat().st_size
        except OSError:
            continue
        if offsite.get(stale.name) != size:
            print(f"    keeping {stale.name}: not confirmed on Hugging Face", flush=True)
            continue
        try:
            stale.unlink()
            removed += 1
        except OSError:
            pass
    return removed


def create_backup_archive(
    db_paths: list[Path],
    output_dir: Path,
    prefix: str = "radd-stream-backup",
) -> tuple[Path, dict]:
    """
    Creates a compressed tar.gz containing safe snapshots and a manifest.

    Refuses with BackupSpaceError before writing anything if the filesystem cannot hold both
    the vacuumed copies and the archive, so a full disk fails loudly at the start instead of
    half-way through every database with "database or disk is full".
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    work_dir = staging_root()
    _check_space(db_paths, work_dir, output_dir)
    timestamp = time.strftime("%Y%m%d_%H%M%S", time.gmtime())
    archive_name = f"{prefix}_{timestamp}.tar.gz"
    archive_path = output_dir / archive_name

    manifest = {
        "timestamp": timestamp,
        "created_utc": time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime()),
        "source_commit": _git_head_sha(),
        "databases": {},
    }

    with tempfile.TemporaryDirectory(dir=work_dir, ignore_cleanup_errors=True) as tmp_dir:
        tmp_path = Path(tmp_dir)
        valid_files = []

        for db in db_paths:
            if not db.exists():
                continue
            snap_path = tmp_path / db.name
            print(f"[*] Safely vacuuming {db.name}...", flush=True)
            if safe_vacuum_sqlite(db, snap_path):
                sha = calculate_sha256(snap_path)
                size_mb = snap_path.stat().st_size / (1024 * 1024)
                manifest["databases"][db.name] = {
                    "sha256": sha,
                    "size_mb": round(size_mb, 2),
                    "counts": _db_row_counts(db),
                }
                valid_files.append(snap_path)

        manifest_file = tmp_path / "manifest.json"
        manifest_file.write_text(json.dumps(manifest, indent=2))
        valid_files.append(manifest_file)

        print(f"[*] Creating compressed archive {archive_name}...", flush=True)
        with tarfile.open(archive_path, "w:gz") as tar:
            for f in valid_files:
                tar.add(f, arcname=f.name)

    manifest["archive"] = {
        "filename": archive_name,
        "size_mb": round(archive_path.stat().st_size / (1024 * 1024), 2),
        "sha256": calculate_sha256(archive_path),
    }

    return archive_path, manifest


def upload_to_github_release(
    archive_path: Path,
    repo: str = "radddao/radd-stream",
    tag_prefix: str = "backup",
    token: str | None = None,
) -> str | None:
    """
    Uploads backup archive to GitHub Releases via REST API.
    Returns the release HTML URL on success.
    """
    token = token or os.environ.get("GITHUB_TOKEN", "")
    if not token:
        print("[!] GITHUB_TOKEN not set. Skipping GitHub release upload.", file=sys.stderr)
        return None

    tag_name = f"{tag_prefix}-{time.strftime('%Y%m%d-%H%M%S', time.gmtime())}"
    release_name = f"Database Backup {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}"

    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
        "User-Agent": "Radd-Stream-Backup/1.0",
    }

    create_payload = json.dumps({
        "tag_name": tag_name,
        "name": release_name,
        "body": f"Automated database snapshot backup containing compressed SQLite catalogs.\n\nSHA-256: `{calculate_sha256(archive_path)}`",
        "draft": False,
        "prerelease": True,
    }).encode("utf-8")

    try:
        req = urllib.request.Request(
            f"https://api.github.com/repos/{repo}/releases",
            data=create_payload,
            headers={**headers, "Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req) as resp:
            rel_data = json.loads(resp.read().decode("utf-8"))

        upload_url_raw = rel_data.get("upload_url", "")
        upload_url = upload_url_raw.split("{")[0] + f"?name={archive_path.name}"
        html_url = rel_data.get("html_url", "")

        with open(archive_path, "rb") as f:
            file_data = f.read()

        up_headers = {
            "Authorization": f"token {token}",
            "Content-Type": "application/gzip",
            "Content-Length": str(len(file_data)),
            "User-Agent": "Radd-Stream-Backup/1.0",
        }
        up_req = urllib.request.Request(upload_url, data=file_data, headers=up_headers, method="POST")
        with urllib.request.urlopen(up_req) as up_resp:
            if up_resp.status in (200, 201):
                print(f"[✓] Uploaded backup to GitHub Release: {html_url}", flush=True)
                return html_url
    except Exception as e:
        print(f"[!] GitHub release upload failed: {e}", file=sys.stderr)
        return None


def upload_to_telegram(
    archive_path: Path,
    bot_token: str | None = None,
    chat_id: str | None = None,
    caption: str = "",
) -> bool:
    """
    Uploads backup archive to a private Telegram channel via Bot API.
    Zero 3rd-party dependencies (pure standard library multipart encoder).
    """
    bot_token = bot_token or os.environ.get("TELEGRAM_BOT_TOKEN", "")
    chat_id = chat_id or os.environ.get("TELEGRAM_CHAT_ID", "")
    if not bot_token or not chat_id:
        print("[*] TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID not configured.", flush=True)
        return False

    url = f"https://api.telegram.org/bot{bot_token}/sendDocument"
    boundary = f"----WebKitFormBoundary{os.urandom(16).hex()}"

    body_parts = []
    # chat_id field
    body_parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n{chat_id}\r\n".encode("utf-8"))
    # caption field
    if caption:
        body_parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n{caption}\r\n".encode("utf-8"))
    # document field
    filename = archive_path.name
    header = f"--{boundary}\r\nContent-Disposition: form-data; name=\"document\"; filename=\"{filename}\"\r\nContent-Type: application/gzip\r\n\r\n".encode("utf-8")
    body_parts.append(header)

    with open(archive_path, "rb") as f:
        file_bytes = f.read()
    body_parts.append(file_bytes)
    body_parts.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))

    full_body = b"".join(body_parts)
    req = urllib.request.Request(
        url,
        data=full_body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Content-Length": str(len(full_body)),
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            res_json = json.loads(resp.read().decode("utf-8"))
            if res_json.get("ok"):
                print(f"[✓] Uploaded backup document to Telegram chat {chat_id}!", flush=True)
                return True
            else:
                print(f"[!] Telegram API returned error: {res_json}", file=sys.stderr)
                return False
    except Exception as e:
        print(f"[!] Telegram upload error: {e}", file=sys.stderr)
        return False


def _sign_aws4(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def _get_aws4_signing_key(secret_key: str, date_stamp: str, region_name: str, service_name: str) -> bytes:
    k_date = _sign_aws4(("AWS4" + secret_key).encode("utf-8"), date_stamp)
    k_region = hmac.new(k_date, region_name.encode("utf-8"), hashlib.sha256).digest()
    k_service = hmac.new(k_region, service_name.encode("utf-8"), hashlib.sha256).digest()
    return hmac.new(k_service, "aws4_request".encode("utf-8"), hashlib.sha256).digest()


def upload_to_cloudflare_r2(
    archive_path: Path,
    account_id: str | None = None,
    access_key: str | None = None,
    secret_key: str | None = None,
    bucket_name: str | None = None,
    object_name: str | None = None,
) -> bool:
    """
    Uploads backup archive directly to Cloudflare R2 using AWS SigV4.
    100% Python standard library (no pip packages or boto3 required).
    """
    account_id = account_id or os.environ.get("R2_ACCOUNT_ID", "")
    access_key = access_key or os.environ.get("R2_ACCESS_KEY_ID", "")
    secret_key = secret_key or os.environ.get("R2_SECRET_ACCESS_KEY", "")
    bucket_name = bucket_name or os.environ.get("R2_BUCKET_NAME", "radd-stream-backups")
    object_name = object_name or archive_path.name

    if not account_id or not access_key or not secret_key:
        print("[*] R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, or R2_SECRET_ACCESS_KEY not configured. Skipping R2 upload.", file=sys.stderr)
        return False

    with open(archive_path, "rb") as f:
        payload_bytes = f.read()

    host = f"{account_id}.r2.cloudflarestorage.com"
    endpoint_url = f"https://{host}/{bucket_name}/{object_name}"
    now = datetime.datetime.now(datetime.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    payload_hash = hashlib.sha256(payload_bytes).hexdigest()

    canonical_uri = f"/{bucket_name}/{object_name}"
    canonical_headers = f"host:{host}\nx-amz-content-sha256:{payload_hash}\nx-amz-date:{amz_date}\n"
    signed_headers = "host;x-amz-content-sha256;x-amz-date"
    canonical_request = f"PUT\n{canonical_uri}\n\n{canonical_headers}\n{signed_headers}\n{payload_hash}"

    credential_scope = f"{date_stamp}/auto/s3/aws4_request"
    string_to_sign = f"AWS4-HMAC-SHA256\n{amz_date}\n{credential_scope}\n{hashlib.sha256(canonical_request.encode('utf-8')).hexdigest()}"

    signing_key = _get_aws4_signing_key(secret_key, date_stamp, "auto", "s3")
    signature = hmac.new(signing_key, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    auth_header = f"AWS4-HMAC-SHA256 Credential={access_key}/{credential_scope}, SignedHeaders={signed_headers}, Signature={signature}"

    headers = {
        "Host": host,
        "x-amz-date": amz_date,
        "x-amz-content-sha256": payload_hash,
        "Authorization": auth_header,
        "Content-Type": "application/gzip",
        "Content-Length": str(len(payload_bytes)),
        "User-Agent": "Radd-Stream-Backup/1.0",
    }

    req = urllib.request.Request(endpoint_url, data=payload_bytes, headers=headers, method="PUT")
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            if resp.status in (200, 201):
                print(f"[✓] Uploaded backup to Cloudflare R2: s3://{bucket_name}/{object_name}", flush=True)
                return True
            else:
                print(f"[!] R2 returned status {resp.status}", file=sys.stderr)
                return False
    except Exception as e:
        print(f"[!] Cloudflare R2 upload error: {e}", file=sys.stderr)
        return False


def _run_git(args: list[str], cwd: Path, env: dict) -> subprocess.CompletedProcess:
    """Git with no credential helper and no token on the command line."""
    return subprocess.run(
        ["git", "-c", "credential.helper=", *args],
        cwd=str(cwd), capture_output=True, text=True, env=env,
    )


def _hf_git_env(tmp_dir: Path) -> dict:
    """Git environment that authenticates to Hugging Face through an askpass helper.

    The token stays in the environment: it never enters a remote URL, a process
    argument, .git/config, or any log.
    """
    helper = tmp_dir / "askpass.sh"
    helper.write_text(
        "#!/bin/sh\n"
        'case "$1" in\n'
        "  *Username*) printf '%s\\n' 'oauth' ;;\n"
        '  *) printf \'%s\\n\' "${HF_TOKEN:?HF_TOKEN is missing}" ;;\n'
        "esac\n"
    )
    helper.chmod(0o700)
    home = tmp_dir / "home"
    home.mkdir(exist_ok=True)
    env = dict(os.environ)
    env.update(
        GIT_ASKPASS=str(helper),
        GIT_TERMINAL_PROMPT="0",
        GIT_CONFIG_NOSYSTEM="1",
        HOME=str(home),
        XDG_CONFIG_HOME=str(home),
        # Every LFS object in the dataset, or none of them. This clone exists only to add one
        # new archive and push it; nothing here reads a stored blob, and a smudging checkout
        # downloads the whole dataset into this temporary directory on every run (measured
        # 2026-11-05: the nightly job pulled ~30 GiB of past archives each night, and the disk
        # filled). Pointer files are enough to commit and push.
        GIT_LFS_SKIP_SMUDGE="1",
    )
    return env


# LFS-tracked suffixes in a Hugging Face dataset clone. A .zst committed as a
# plain git blob fails the push (and a multi-GB blob would break the clone);
# keep this list in sync with the archive kinds upload_to_huggingface writes.
HF_LFS_PATTERNS = ("*.tar.gz", "*.db", "*.zip", "*.zst")


def upload_to_huggingface(
    archive_path: Path,
    manifest: dict | None = None,
    repo_id: str | None = None,
    extra_files: list[Path] | None = None,
) -> bool:
    """
    Uploads a backup archive plus its manifest to a Hugging Face dataset via Git LFS.
    100% Python standard library (no pip packages or huggingface_hub required).
    """
    repo_id = repo_id or os.environ.get("HF_DATASET_REPO", "")
    if not repo_id or not os.environ.get("HF_TOKEN"):
        print("[*] HF_DATASET_REPO or HF_TOKEN not configured. Skipping Hugging Face upload.", file=sys.stderr)
        return False

    remote_url = f"https://huggingface.co/datasets/{repo_id}"
    print(f"[*] Uploading {archive_path.name} to Hugging Face dataset {repo_id}...", flush=True)

    with tempfile.TemporaryDirectory(dir=staging_root(), ignore_cleanup_errors=True) as tmp_dir:
        tmp_path = Path(tmp_dir)
        env = _hf_git_env(tmp_path)
        repo_dir = tmp_path / "hf_repo"

        clone = _run_git(["clone", "--depth", "1", remote_url, str(repo_dir)], tmp_path, env)
        if clone.returncode != 0:
            head = _run_git(["-c", "init.defaultBranch=main", "init", str(repo_dir)], tmp_path, env)
            if head.returncode != 0:
                print(f"[!] Hugging Face clone and init both failed: {head.stderr.strip()}", file=sys.stderr)
                return False
            _run_git(["remote", "add", "origin", remote_url], repo_dir, env)
            _run_git(["checkout", "-b", "main"], repo_dir, env)
            branch = "main"
        else:
            shown = _run_git(["symbolic-ref", "--short", "HEAD"], repo_dir, env)
            branch = shown.stdout.strip() or "main"

        repo_dir.mkdir(parents=True, exist_ok=True)

        _run_git(["lfs", "install"], repo_dir, env)
        for pattern in HF_LFS_PATTERNS:
            _run_git(["lfs", "track", pattern], repo_dir, env)

        shutil.copy2(archive_path, repo_dir / archive_path.name)
        for extra in extra_files or []:
            shutil.copy2(extra, repo_dir / extra.name)
        if manifest:
            (repo_dir / "LATEST.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

        added = _run_git(["add", "-A"], repo_dir, env)
        if added.returncode != 0:
            print(f"[!] git add failed: {added.stderr.strip()}", file=sys.stderr)
            return False

        stamp = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime())
        committed = _run_git(
            [
                "-c", "user.name=radd-stream-backup",
                "-c", "user.email=backup@radd-stream.local",
                "commit",
                "-m", f"chore(backup): snapshot {stamp} ({archive_path.name})",
            ],
            repo_dir, env,
        )
        if committed.returncode != 0 and "nothing to commit" not in committed.stdout + committed.stderr:
            print(f"[!] git commit failed: {(committed.stderr or committed.stdout).strip()}", file=sys.stderr)
            return False

        pushed = _run_git(["push", "origin", branch], repo_dir, env)
        if pushed.returncode != 0:
            print(f"[!] Hugging Face push failed: {(pushed.stderr or pushed.stdout).strip()}", file=sys.stderr)
            return False

        print(f"[✓] Uploaded backup to Hugging Face dataset: https://huggingface.co/datasets/{repo_id}", flush=True)
        return True


# ---------------------------------------------------------------------------
# Full-gate mode: push one already-existing gate-backup .db to a dedicated
# private Hugging Face dataset, compressed with zstd, verified on restore.
#
# The slim path re-snapshots the LIVE databases (needs ~19 GB headroom, and its
# 2x space guard refuses on this disk); this mode streams the backup file that
# already exists, so it needs ~6 GB free and never makes a second 10 GB copy.
# ---------------------------------------------------------------------------

FULLGATE_REPO_DEFAULT = "radddao/radd-stream-gate-backups"
FULLGATE_MIN_FREE_BYTES = 6 * 2**30
FULLGATE_TABLES = ("posts", "releases", "download_sources", "cat_links")


def _fullgate_gate_commit(db_name: str) -> str:
    """Gate commit embedded in a gate-backup filename, '' when there is none."""
    match = re.match(r"gate-backup-(?:\d{8}-)?([0-9a-f]{7,40})\.db$", db_name)
    return match.group(1) if match else ""


def _fullgate_db_info(db_path: Path, skip_integrity: bool = False) -> dict:
    """Page geometry, integrity_check and row counts for a static backup file.

    Read-only open: a gate backup is a finished file, never a live WAL db, and
    the counts run on the same read-only connection so no -shm/-wal sidecar
    is created beside the source. immutable=1 is what actually does that: a
    plain mode=ro open still builds a WAL-index beside the file (and, being
    read-only, cannot clean it up on close), while immutable skips WAL
    recovery entirely. Safe here because the file is a finished .backup
    snapshot with no -wal sibling — and the .zst archives the main file only,
    so manifest, archive and restore all see exactly the same bytes.
    integrity_check on a 10 GB file takes minutes on one vCPU; callers run
    this detached. skip_integrity records "skipped" instead of running it —
    safe only because the source was integrity-checked at gate time AND the
    restore side re-checks it in verify_fullgate. Never use it for the verify
    path: verify_fullgate always calls this with the default.
    """
    info: dict = {"integrity_check": "unknown", "counts": {}}
    uri = "file:" + urllib.parse.quote(str(db_path), safe="/") + "?mode=ro&immutable=1"
    conn = sqlite3.connect(uri, uri=True)
    try:
        row = conn.execute(
            "SELECT page_count, freelist_count, page_size FROM "
            "pragma_page_count(), pragma_freelist_count(), pragma_page_size()"
        ).fetchone()
        info["page_count"], info["freelist_count"], info["page_size"] = (
            int(v) for v in row
        )
        if skip_integrity:
            info["integrity_check"] = "skipped"
        else:
            texts = [str(r[0]) for r in conn.execute("PRAGMA integrity_check").fetchall()]
            info["integrity_check"] = "ok" if texts == ["ok"] else "; ".join(texts)[:500]
        info["counts"] = _db_row_counts(db_path, conn=conn)
    finally:
        conn.close()
    return info


def _compress_zstd(source: Path, dest: Path, level: int = 3) -> float:
    """Stream source through `zstd -<level>` into dest. Returns wall seconds."""
    zstd = shutil.which("zstd")
    if not zstd:
        raise RuntimeError("zstd binary not found on PATH")
    started = time.time()
    with open(source, "rb") as fin, open(dest, "wb") as fout:
        res = subprocess.run(
            [zstd, f"-{level}", "-T0", "--stdout"],
            stdin=fin, stdout=fout, stderr=subprocess.PIPE, text=True,
        )
    if res.returncode != 0:
        raise RuntimeError(
            f"zstd failed (rc={res.returncode}): {res.stderr.strip()[:300]}"
        )
    return time.time() - started


def create_fullgate_archive(
    db_path: Path, out_dir: Path, zstd_level: int = 3,
    skip_source_integrity: bool = False,
) -> tuple[Path, Path, dict]:
    """Compress one gate-backup .db to .zst + manifest JSON. No second 10 GB copy."""
    db_path = Path(db_path)
    if not db_path.exists():
        raise FileNotFoundError(f"gate backup not found: {db_path}")
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    if free_bytes(out_dir) < FULLGATE_MIN_FREE_BYTES:
        raise BackupSpaceError(
            f"full-gate needs {FULLGATE_MIN_FREE_BYTES / 2**30:.0f} GiB free in {out_dir}, "
            f"has {free_bytes(out_dir) / 2**30:.2f} GiB."
        )
    zst_path = out_dir / (db_path.stem + ".zst")
    manifest_path = out_dir / (db_path.stem + ".manifest.json")
    print(f"[*] Compressing {db_path.name} with zstd -{zstd_level} ...", flush=True)
    seconds = _compress_zstd(db_path, zst_path, zstd_level)
    if skip_source_integrity:
        print("[*] Reading source geometry + counts (read-only; source "
              "integrity skipped — gate-time + restore-side checks apply) ...", flush=True)
    else:
        print("[*] Reading source geometry + integrity (read-only) ...", flush=True)
    info = _fullgate_db_info(db_path, skip_integrity=skip_source_integrity)
    manifest = {
        "mode": "full-gate",
        "created_utc": time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime()),
        "source_commit": _git_head_sha(),
        "gate_commit": _fullgate_gate_commit(db_path.name),
        "source": {
            "filename": db_path.name,
            "bytes": db_path.stat().st_size,
            "sha256": calculate_sha256(db_path),
        },
        "archive": {
            "filename": zst_path.name,
            "bytes": zst_path.stat().st_size,
            "sha256": calculate_sha256(zst_path),
            "compressor": f"zstd -{zstd_level}",
            "compress_seconds": round(seconds, 1),
        },
        "database": info,
    }
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(
        f"[✓] {zst_path.name}: {manifest['archive']['bytes'] / 2**30:.2f} GiB "
        f"({manifest['source']['bytes'] / manifest['archive']['bytes']:.2f}x, "
        f"{seconds:.0f}s), integrity={info['integrity_check']}",
        flush=True,
    )
    return zst_path, manifest_path, manifest


def run_fullgate(
    db_path: Path, out_dir: Path, repo_id: str,
    skip_source_integrity: bool = False,
) -> tuple[Path, Path, dict, bool]:
    """Compress + manifest + upload one gate backup. The bool is the upload verdict."""
    zst_path, manifest_path, manifest = create_fullgate_archive(
        db_path, out_dir, skip_source_integrity=skip_source_integrity
    )
    uploaded = upload_to_huggingface(
        zst_path, manifest=manifest, repo_id=repo_id, extra_files=[manifest_path]
    )
    if uploaded:
        listing = hf_remote_listing(repo_id)
        if listing is None or listing.get(zst_path.name) != zst_path.stat().st_size:
            print(
                f"[!] Upload reported success but the HF listing does not confirm "
                f"{zst_path.name}",
                file=sys.stderr,
            )
            return zst_path, manifest_path, manifest, False
    return zst_path, manifest_path, manifest, uploaded


def _stream_restore(
    repo_id: str, zst_name: str, dest_db: Path
) -> tuple[int, str, bool, str]:
    """Stream the .zst out of HF straight through `zstd -d` into dest_db.

    One pass does three checks at once: the SHA-256 is computed over the bytes
    as they arrive, and the decompressor validates the zstd frame (its checksum
    covers the decompressed bytes — exactly what `zstd -t` does, by
    decompressing to /dev/null). The .zst is never stored on disk: on this box
    the download + a scratch decompress together do NOT fit (short by ~339 MB
    even at zero margin), while the streaming pass needs only the restored db.

    Returns (bytes downloaded, sha256 hex, frame valid, zstd stderr snippet).
    """
    token = os.environ.get("HF_TOKEN", "")
    if not token:
        raise RuntimeError("HF_TOKEN not set; cannot download from a private dataset")
    zstd = shutil.which("zstd")
    if not zstd:
        raise RuntimeError("zstd binary not found on PATH")
    url = (
        f"https://huggingface.co/datasets/{repo_id}/resolve/main/"
        f"{urllib.parse.quote(zst_name)}"
    )
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    hasher = hashlib.sha256()
    downloaded = 0
    err_path = Path(str(dest_db) + ".zstd.err")
    with open(dest_db, "wb") as fout, open(err_path, "wb") as ferr:
        proc = subprocess.Popen(
            [zstd, "-d", "-q", "--stdout"],
            stdin=subprocess.PIPE, stdout=fout, stderr=ferr,
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as resp:
                while chunk := resp.read(1024 * 1024):
                    hasher.update(chunk)
                    downloaded += len(chunk)
                    try:
                        proc.stdin.write(chunk)
                    except BrokenPipeError:
                        break
            proc.stdin.close()
        except Exception:
            proc.kill()
            raise
        proc.wait()
    err_text = err_path.read_text(errors="replace").strip()[:300] if err_path.exists() else ""
    err_path.unlink(missing_ok=True)
    return downloaded, hasher.hexdigest(), proc.returncode == 0, err_text


def verify_fullgate(
    zst_name: str, manifest: dict, repo_id: str, work_dir: Path
) -> tuple[bool, dict]:
    """The verification steps, in order. Nothing is deleted by this function
    except its own scratch on the success path.

    1. HF listing shows the file at the manifest size.
    2. Fresh download SHA-256 matches the manifest (hashed over the stream).
    3. The zstd frame validates (checked by the streaming decompressor —
       `zstd -t` is the same check run against a stored file, which this disk
       cannot hold alongside the restored db; see _stream_restore).
    4. The streamed restore lands in scratch; PRAGMA integrity_check is ok.
    5. page_count + posts/releases/download_sources/cat_links equal the manifest.
    6. (Logging by the caller.) Returns (ok, report).
    """
    work_dir = Path(work_dir)
    work_dir.mkdir(parents=True, exist_ok=True)
    report: dict = {"file": zst_name, "repo": repo_id, "steps": {}}
    archive = manifest["archive"]
    database = manifest["database"]
    # Only the restored db is stored (streaming verify); the source byte count
    # is exact, so a 256 MiB margin is ample.
    need = int(manifest["source"]["bytes"]) + 256 * 2**20
    if free_bytes(work_dir) < need:
        report["steps"]["space"] = (
            f"FAIL need {need / 2**30:.2f} GiB free in {work_dir}, "
            f"have {free_bytes(work_dir) / 2**30:.2f} GiB"
        )
        return False, report

    # 1. HF listing confirms the file at the manifest size.
    listing = hf_remote_listing(repo_id)
    if listing is None:
        report["steps"]["listing"] = "FAIL could not read the HF listing"
        return False, report
    if listing.get(zst_name) != int(archive["bytes"]):
        report["steps"]["listing"] = (
            f"FAIL HF lists {zst_name} as {listing.get(zst_name)}, "
            f"manifest says {archive['bytes']}"
        )
        return False, report
    report["steps"]["listing"] = f"ok {archive['bytes']} bytes on HF"

    # 2 + 3. Stream the download through zstd -d into a scratch db, hashing
    # the compressed bytes on the way and letting the decompressor validate
    # the frame.
    stem = zst_name[:-4] if zst_name.endswith(".zst") else zst_name + ".restored"
    restored = work_dir / (stem + ".db")
    print(f"[*] Streaming {zst_name} from HF through zstd -d ...", flush=True)
    try:
        downloaded, sha, frame_ok, err = _stream_restore(
            repo_id, zst_name, restored
        )
    except Exception as e:  # noqa: BLE001 - a failed stream is a failed verify
        report["steps"]["download"] = f"FAIL {e}"
        return False, report
    report["steps"]["download"] = f"ok {downloaded} bytes"
    if sha != archive["sha256"]:
        report["steps"]["sha256"] = (
            f"FAIL streamed sha {sha} != manifest {archive['sha256']}"
        )
        restored.unlink(missing_ok=True)
        return False, report
    report["steps"]["sha256"] = "ok matches manifest"
    if not frame_ok:
        report["steps"]["zstd_frame"] = f"FAIL zstd -d failed: {err}"
        restored.unlink(missing_ok=True)
        return False, report
    report["steps"]["zstd_frame"] = "ok validated by streaming decompression"
    report["steps"]["decompress"] = f"ok {restored.stat().st_size} bytes"

    # 4. The restored db must pass integrity_check.
    info = _fullgate_db_info(restored)
    if info["integrity_check"] != "ok":
        report["steps"]["integrity_check"] = f"FAIL {info['integrity_check']}"
        return False, report
    report["steps"]["integrity_check"] = "ok"

    # 5. Page geometry + the four counts equal the manifest.
    mismatches = []
    for key in ("page_count", "freelist_count", "page_size"):
        if int(info[key]) != int(database[key]):
            mismatches.append(
                f"{key}: restored {info[key]} != manifest {database[key]}"
            )
    for table in FULLGATE_TABLES:
        if table in database["counts"] and int(info["counts"].get(table, -1)) != int(
            database["counts"][table]
        ):
            mismatches.append(
                f"{table}: restored {info['counts'].get(table)} "
                f"!= manifest {database['counts'][table]}"
            )
    if mismatches:
        report["steps"]["counts"] = "FAIL " + "; ".join(mismatches)
        return False, report
    report["steps"]["counts"] = "ok " + ", ".join(
        f"{t}={info['counts'].get(t)}" for t in FULLGATE_TABLES if t in info["counts"]
    )
    report["steps"]["page_count"] = f"ok {info['page_count']}"
    restored.unlink(missing_ok=True)  # scratch verified; free the room
    report["steps"]["scratch"] = "removed"
    return True, report


def snapshot_code(output_dir: Path) -> Path | None:
    """Packs the tracked source tree at HEAD so the code rides along with the data."""
    output_dir.mkdir(parents=True, exist_ok=True)
    sha = _git_head_sha() or "unknown"
    target = output_dir / f"radd-stream-code_{sha}.tar.gz"
    if target.exists():
        return target
    res = subprocess.run(
        ["git", "-C", str(BASE_DIR.parent), "archive", "--format=tar.gz", "-o", str(target), "HEAD"],
        capture_output=True, text=True, timeout=300,
    )
    if res.returncode != 0 or not target.exists():
        print(f"[!] Code snapshot failed: {res.stderr.strip()}", file=sys.stderr)
        return None
    return target


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="radd-stream Cloud Backup Manager")
    parser.add_argument("--dir", default="/home/ubuntu/radd-stream-data", help="Directory containing SQLite DBs")
    parser.add_argument("--out", default="/home/ubuntu/radd-stream-backups",
                        help="Directory to save backup archives. Not /tmp: this is the only "
                             "copy that exists before an upload finishes, and a cleaner or a "
                             "reboot takes /tmp with it.")
    parser.add_argument("--github", action="store_true", help="Upload to GitHub Releases")
    parser.add_argument("--telegram", action="store_true", help="Upload to Telegram channel")
    parser.add_argument("--r2", action="store_true", help="Upload to Cloudflare R2 bucket")
    parser.add_argument("--hf", action="store_true", help="Upload to Hugging Face dataset")
    parser.add_argument("--all", action="store_true", help="Upload to all configured services (GitHub + HF + Telegram + R2)")
    parser.add_argument("--dry-run", action="store_true", help="Snapshot & compress only, do not upload")
    parser.add_argument("--no-code", action="store_true", help="Do not pack the tracked source tree alongside the databases")
    parser.add_argument("--full-gate", metavar="DB", help="Push one existing gate-backup .db to the full-gate HF dataset (zstd + manifest + upload)")
    parser.add_argument("--verify-full-gate", metavar="NAME", help="Verify one pushed .zst against its manifest (download + restore test)")
    parser.add_argument("--manifest", metavar="PATH", help="Manifest JSON for --verify-full-gate")
    parser.add_argument("--repo", metavar="ID", help="Hugging Face dataset repo id (default: HF_FULLGATE_REPO env or radddao/radd-stream-gate-backups)")
    parser.add_argument("--fullgate-out", metavar="DIR", help="Directory for the .zst + manifest, or the verify scratch dir (default: the source file's directory)")
    parser.add_argument("--skip-source-integrity", action="store_true", help="With --full-gate only: skip the source PRAGMA integrity_check (minutes on one vCPU). The source was checked at gate time and the restore side re-checks it in --verify-full-gate; the manifest records integrity_check as 'skipped'.")
    args = parser.parse_args(argv)

    if args.full_gate:
        repo_id = args.repo or os.environ.get("HF_FULLGATE_REPO", "") or FULLGATE_REPO_DEFAULT
        out_dir = Path(args.fullgate_out) if args.fullgate_out else Path(args.full_gate).parent
        try:
            _zst, _mp, _man, uploaded = run_fullgate(
                Path(args.full_gate), out_dir, repo_id,
                skip_source_integrity=args.skip_source_integrity,
            )
        except (BackupSpaceError, FileNotFoundError, RuntimeError) as e:
            print(f"[!] full-gate failed: {e}", file=sys.stderr)
            return 1
        return 0 if uploaded else 1

    if args.verify_full_gate:
        repo_id = args.repo or os.environ.get("HF_FULLGATE_REPO", "") or FULLGATE_REPO_DEFAULT
        manifest_path = Path(args.manifest) if args.manifest else None
        if manifest_path is None or not manifest_path.exists():
            print("[!] --verify-full-gate needs --manifest <file>.manifest.json", file=sys.stderr)
            return 1
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        work_dir = (
            Path(args.fullgate_out)
            if args.fullgate_out
            else Path(staging_root()) / "fullgate-verify"
        )
        try:
            ok, report = verify_fullgate(args.verify_full_gate, manifest, repo_id, work_dir)
        except (BackupSpaceError, RuntimeError) as e:
            print(f"[!] verify failed: {e}", file=sys.stderr)
            return 1
        print(json.dumps(report, indent=2))
        return 0 if ok else 1

    data_dir = Path(args.dir)
    if not data_dir.exists():
        # Fallback to local workspace if running locally
        data_dir = Path("/home/runner/workspace/radd-stream/Fast_Fetch")

    dbs = sorted(data_dir.glob("*.db"))
    if not dbs:
        print(f"[!] No .db files found in {data_dir}", file=sys.stderr)
        return 1

    print(f"[*] Found {len(dbs)} databases: {[d.name for d in dbs]}", flush=True)
    out_dir = Path(args.out)
    try:
        archive, manifest = create_backup_archive(dbs, out_dir)
    except BackupSpaceError as e:
        print(f"[!] {e}", file=sys.stderr)
        return 1
    print(f"[✓] Created backup archive: {archive} ({manifest['archive']['size_mb']} MB)", flush=True)
    print(f"[*] Archive SHA-256: {manifest['archive']['sha256']}", flush=True)
    (out_dir / "LATEST_SHA256.txt").write_text(f"{manifest['archive']['sha256']}  {archive.name}\n")

    if args.dry_run:
        print("[*] Dry run complete. No off-site uploads requested.", flush=True)
        return 0

    failures = 0
    # A target named on the command line is a promise: if it does not happen, this run failed.
    # A target triggered only because its token happened to be in the environment stays
    # best-effort, exactly as it was before, and is reported rather than counted.
    explicit = {
        "github": args.github or args.all,
        "hf": args.hf or args.all,
        "telegram": args.telegram or args.all,
        "r2": args.r2 or args.all,
    }

    def _count(target: str, ok: bool) -> None:
        nonlocal failures
        if ok:
            return
        if explicit[target]:
            failures += 1
        else:
            print(f"[!] Optional {target} upload failed; not counted against this run.", file=sys.stderr)

    # 1. GitHub Releases
    if args.github or args.all or (not args.telegram and not args.r2 and not args.hf and os.environ.get("GITHUB_TOKEN")):
        _count("github", upload_to_github_release(archive) is not None)

    # 2. Hugging Face Datasets
    if args.hf or args.all or os.environ.get("HF_TOKEN"):
        extras = []
        if not args.no_code:
            code = snapshot_code(out_dir)
            if code:
                extras.append(code)
        uploaded = upload_to_huggingface(archive, manifest=manifest, extra_files=extras)
        if uploaded:
            # Only forget local copies once this run's archive is off-site AND every file being
            # forgotten is individually confirmed off-site. This is the unit's own cleanup: the
            # systemd ExecStart runs this module, not run_backup.py, so the retention policy has
            # to live on the path that actually executes.
            keep = int(os.environ.get(KEEP_ENV, DEFAULT_KEEP) or DEFAULT_KEEP)
            gone = prune_local_archives(out_dir, keep=keep)
            if gone:
                print(f"[✓] Pruned {gone} local archive(s) confirmed on Hugging Face, keeping {keep}.", flush=True)
        else:
            _count("hf", False)

    # 3. Telegram
    if args.telegram or args.all:
        caption = f"🚀 Radd-Stream DB Backup ({manifest['archive']['size_mb']} MB)\nDatabases: {', '.join(manifest['databases'].keys())}"
        _count("telegram", upload_to_telegram(archive, caption=caption))

    # 4. Cloudflare R2
    if args.r2 or args.all or os.environ.get("R2_ACCOUNT_ID"):
        _count("r2", upload_to_cloudflare_r2(archive))

    if failures:
        # Every one of these uploads failing used to exit 0, so `systemctl show -p Result=`
        # reported a green unit while the catalog had no off-site copy for weeks.
        print(f"[!] {failures} requested upload(s) failed; keeping every local archive.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

