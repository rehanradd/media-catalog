"""Cloudflare WARP proxy routing for the resolver (stdlib only).

WARP runs in proxy mode on the Oracle VM: a local HTTP CONNECT proxy on
127.0.0.1:40000 (``warp-cli mode proxy``; warp-svc stays enabled and the
daemon stays up). Only the resolver's outbound fetches go through it — the
calls that turn a saved link into a playable file when you press Play, and
the link sweep's probes. The VM's normal internet route stays on Oracle
egress; the live API, the admin panel, nginx and everything users connect
to are untouched.

Enable with the environment variable RADD_WARP_PROXY::

    unset / ""            -> direct (default; the proxy is off)
    "1" / "true" / "yes" / "on" / "auto"
                          -> http://127.0.0.1:40000
    any other value       -> that value is the proxy URL

When enabled, the resolver's urllib openers get a ProxyHandler (the WARP
proxy accepts HTTP CONNECT, proven 20 Nov: ``curl -x
http://127.0.0.1:40000`` returned ``warp=on``) and the sweep's http.client
connections tunnel via ``set_tunnel`` (the stdlib's built-in CONNECT).
When disabled, every helper returns the direct equivalent, so the code
paths are identical to before.

Rollback: unset RADD_WARP_PROXY (and disconnect WARP). Egress returns to
the Oracle IP in under a minute; the code stays in git but does nothing
while the proxy is off. No data is touched.

The proxy is an HTTP CONNECT proxy, NOT SOCKS5: curl ``-x http://...``
and urllib's ProxyHandler both speak CONNECT to it. Do not add a SOCKS5
handshake — it is not needed and would not work.
"""

from __future__ import annotations

import http.client
import os
import urllib.parse
import urllib.request

ENV_VAR = "RADD_WARP_PROXY"
DEFAULT_PROXY_URL = "http://127.0.0.1:40000"
_TRUE_VALUES = frozenset({"1", "true", "yes", "on", "auto"})


def proxy_url() -> str | None:
    """The configured WARP proxy URL, or None when the proxy is off."""
    raw = os.environ.get(ENV_VAR, "")
    raw = (raw or "").strip()
    if not raw:
        return None
    if raw.lower() in _TRUE_VALUES:
        return DEFAULT_PROXY_URL
    return raw


def is_enabled() -> bool:
    """True when the resolver should route through the WARP proxy."""
    return proxy_url() is not None


def proxy_host_port(url: str | None = None) -> tuple[str, int] | None:
    """(host, port) of the proxy URL (default: the configured one)."""
    url = url if url is not None else proxy_url()
    if not url:
        return None
    parts = urllib.parse.urlsplit(url)
    host = parts.hostname or "127.0.0.1"
    port = parts.port or 40000
    return (host, port)


def urllib_proxy_handler() -> urllib.request.ProxyHandler | None:
    """A ProxyHandler for the WARP proxy, or None when the proxy is off."""
    url = proxy_url()
    if not url:
        return None
    return urllib.request.ProxyHandler({"http": url, "https": url})


def make_connection(host: str, port: int, https: bool, timeout: float,
                    context=None) -> http.client.HTTPConnection:
    """An http.client connection to (host, port), via the WARP proxy if on.

    When the proxy is enabled the connection is built against the proxy
    and ``set_tunnel`` makes http.client issue a CONNECT to the real
    target, so the proxy — not this process — resolves the hostname and
    opens the far end. For HTTPS the stock connect() wraps the tunnel
    with TLS and uses the target host for SNI. When the proxy is off
    this is exactly the direct connection the caller used before.
    """
    proxy = proxy_url()
    if proxy:
        phost, pport = proxy_host_port(proxy)
        if https:
            conn = http.client.HTTPSConnection(phost, pport, timeout=timeout,
                                               context=context)
        else:
            conn = http.client.HTTPConnection(phost, pport, timeout=timeout)
        conn.set_tunnel(host, port)
        return conn
    if https:
        return http.client.HTTPSConnection(host, port, timeout=timeout,
                                           context=context)
    return http.client.HTTPConnection(host, port, timeout=timeout)
