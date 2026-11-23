"""WARP proxy routing for the resolver (Fast_Fetch/warp_proxy.py).

Offline tests: a fake CONNECT proxy + a fake origin server prove the
resolver's fetches route through the proxy when RADD_WARP_PROXY is set,
and go direct when it is not. No real network, no WARP daemon needed.

The fake proxy speaks both shapes a stdlib client sends it:
- CONNECT host:port (http.client with set_tunnel, and urllib for https)
- an absolute-URI request line (urllib's ProxyHandler for http)
"""

from __future__ import annotations

import http.client
import http.server
import os
import socket
import socketserver
import sys
import threading
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent / "importers"))

import warp_proxy
import current_hosts
import link_health as lh
import fast_probe
import host_resolvers as hr

ORIGIN_BODY = b"ORIGIN-OK"


class _OriginHandler(http.server.BaseHTTPRequestHandler):
    """A tiny origin server: every GET answers 200 ORIGIN-OK."""

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(ORIGIN_BODY)))
        self.end_headers()
        self.wfile.write(ORIGIN_BODY)

    def log_message(self, *args):  # noqa: D102 - silence the test server
        pass


class _FakeProxyHandler(socketserver.BaseRequestHandler):
    """An HTTP CONNECT proxy (and absolute-URI forwarder) for tests.

    Records every request line it sees in ``server.seen`` so tests can
    prove a fetch went through the proxy, then serves it from the origin.
    """

    def handle(self):
        self.request.settimeout(10)
        try:
            head = self._read_head()
        except OSError:
            return
        if not head:
            return
        line = head[0].decode("latin-1", "replace")
        self.server.seen.append(line)
        parts = line.split()
        if len(parts) < 2:
            return
        method, target = parts[0], parts[1]
        if method.upper() == "CONNECT":
            self._tunnel(target)
        else:
            self._forward(target, head)

    def _read_head(self):
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.request.recv(4096)
            if not chunk:
                return []
            buf += chunk
            if len(buf) > 65536:
                return []
        head, _rest = buf.split(b"\r\n\r\n", 1)
        return head.split(b"\r\n")

    def _tunnel(self, target):
        host, _, port = target.rpartition(":")
        try:
            upstream = socket.create_connection(
                (host.strip("[]"), int(port)), timeout=5)
        except OSError:
            self.request.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
            return
        self.request.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
        threading.Thread(target=_pipe, args=(self.request, upstream),
                         daemon=True).start()
        _pipe(upstream, self.request)
        upstream.close()

    def _forward(self, target, head):
        # Absolute-URI form (urllib http through a proxy): GET http://h/p
        from urllib.parse import urlsplit
        parts = urlsplit(target)
        host = parts.hostname or ""
        port = parts.port or 80
        path = parts.path or "/"
        if parts.query:
            path += "?" + parts.query
        try:
            upstream = socket.create_connection((host, port), timeout=5)
        except OSError:
            self.request.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
            return
        lines = [f"GET {path} HTTP/1.1".encode()]
        for raw in head[1:]:
            if raw.lower().startswith(b"proxy-"):
                continue
            lines.append(raw)
        lines.append(b"")
        lines.append(b"")
        upstream.sendall(b"\r\n".join(lines))
        try:
            while True:
                data = upstream.recv(65536)
                if not data:
                    break
                self.request.sendall(data)
        except OSError:
            pass
        upstream.close()


def _pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


class _ProxyServer(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, *args, **kwargs):
        self.seen = []
        super().__init__(*args, **kwargs)


def _start_origin():
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _OriginHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def _start_proxy():
    srv = _ProxyServer(("127.0.0.1", 0), _FakeProxyHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


class WarpConfigTests(unittest.TestCase):
    """RADD_WARP_PROXY -> proxy URL (or off)."""

    def _url(self, value):
        with mock.patch.dict(os.environ, {"RADD_WARP_PROXY": value}):
            return warp_proxy.proxy_url()

    def test_unset_is_off(self):
        env = {k: v for k, v in os.environ.items() if k != "RADD_WARP_PROXY"}
        with mock.patch.dict(os.environ, env, clear=True):
            self.assertIsNone(warp_proxy.proxy_url())
            self.assertFalse(warp_proxy.is_enabled())
            self.assertIsNone(warp_proxy.urllib_proxy_handler())

    def test_empty_is_off(self):
        self.assertIsNone(self._url(""))
        self.assertIsNone(self._url("   "))

    def test_truthy_values_use_the_default(self):
        for value in ("1", "true", "TRUE", "yes", "on", "auto"):
            self.assertEqual(warp_proxy.DEFAULT_PROXY_URL, self._url(value))

    def test_explicit_url_is_used_verbatim(self):
        url = "http://127.0.0.1:9999"
        self.assertEqual(url, self._url(url))
        self.assertEqual(("127.0.0.1", 9999),
                         warp_proxy.proxy_host_port(url))

    def test_proxy_host_port_defaults(self):
        self.assertEqual(("127.0.0.1", 40000),
                         warp_proxy.proxy_host_port(
                             warp_proxy.DEFAULT_PROXY_URL))

    def test_handler_carries_both_schemes(self):
        with mock.patch.dict(os.environ,
                             {"RADD_WARP_PROXY": "1"}):
            handler = warp_proxy.urllib_proxy_handler()
        self.assertIsNotNone(handler)
        self.assertEqual({"http": warp_proxy.DEFAULT_PROXY_URL,
                          "https": warp_proxy.DEFAULT_PROXY_URL},
                         handler.proxies)


class WarpConnectionTests(unittest.TestCase):
    """make_connection: direct when off, CONNECT-tunneled when on."""

    def test_direct_when_off(self):
        env = {k: v for k, v in os.environ.items() if k != "RADD_WARP_PROXY"}
        with mock.patch.dict(os.environ, env, clear=True):
            conn = warp_proxy.make_connection("example.com", 443, True, 5)
        self.assertEqual("example.com", conn.host)
        self.assertEqual(443, conn.port)
        self.assertIsNone(conn._tunnel_host)
        conn.close()

    def test_tunneled_when_on(self):
        with mock.patch.dict(os.environ, {"RADD_WARP_PROXY": "1"}):
            conn = warp_proxy.make_connection("example.com", 443, True, 5)
        self.assertEqual("127.0.0.1", conn.host)
        self.assertEqual(40000, conn.port)
        self.assertEqual("example.com", conn._tunnel_host)
        self.assertEqual(443, conn._tunnel_port)
        conn.close()

    def test_tunneled_http_too(self):
        with mock.patch.dict(os.environ, {"RADD_WARP_PROXY": "1"}):
            conn = warp_proxy.make_connection("example.com", 80, False, 5)
        self.assertEqual("127.0.0.1", conn.host)
        self.assertEqual("example.com", conn._tunnel_host)
        conn.close()


class WarpTunnelTests(unittest.TestCase):
    """A real CONNECT tunnel carries a request to the origin (offline)."""

    @classmethod
    def setUpClass(cls):
        cls.origin = _start_origin()
        cls.proxy = _start_proxy()
        cls.origin_port = cls.origin.server_address[1]
        cls.proxy_port = cls.proxy.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.origin.shutdown()
        cls.proxy.shutdown()

    def _proxy_url(self):
        return f"http://127.0.0.1:{self.proxy_port}"

    def test_http_tunnel_reaches_origin(self):
        with mock.patch.dict(os.environ,
                             {"RADD_WARP_PROXY": self._proxy_url()}):
            conn = warp_proxy.make_connection(
                "127.0.0.1", self.origin_port, False, 5)
            conn.request("GET", "/")
            resp = conn.getresponse()
            body = resp.read()
            conn.close()
        self.assertEqual(200, resp.status)
        self.assertEqual(ORIGIN_BODY, body)
        self.assertTrue(
            any(line.startswith("CONNECT 127.0.0.1:")
                for line in self.proxy.seen),
            f"proxy saw: {self.proxy.seen}")

    def test_link_health_raw_request_via_proxy(self):
        url = f"http://127.0.0.1:{self.origin_port}/x"
        with mock.patch.dict(os.environ,
                             {"RADD_WARP_PROXY": self._proxy_url()}):
            exch = lh._raw_request(url, "GET", {}, 5, 64)
        self.assertEqual(200, exch.status)
        self.assertEqual(ORIGIN_BODY, exch.body)
        self.assertEqual("", exch.error)

    def test_current_hosts_default_fetch_via_proxy(self):
        url = f"http://127.0.0.1:{self.origin_port}/y"
        with mock.patch.dict(os.environ,
                             {"RADD_WARP_PROXY": self._proxy_url()}):
            result = current_hosts.default_fetch(url, timeout=5)
        self.assertEqual(200, result.status)
        self.assertEqual(ORIGIN_BODY, result.body)
        self.assertTrue(
            any("127.0.0.1" in line and "/y" in line
                for line in self.proxy.seen),
            f"proxy saw: {self.proxy.seen}")

    def test_host_resolvers_http_request_via_proxy(self):
        url = f"http://127.0.0.1:{self.origin_port}/z"
        hr._OPENERS.clear()
        hr.close_keepalive_pool()
        with mock.patch.dict(os.environ,
                             {"RADD_WARP_PROXY": self._proxy_url()}):
            status, body, _headers, _final = hr.http_request(
                url, timeout=5, transport_retries=0)
        self.assertEqual(200, status)
        self.assertEqual(ORIGIN_BODY, body)
        # NOTE (2026-11-23, keep-alive): http_request used to go through
        # urllib's ProxyHandler, which sends the proxy an absolute-form
        # request line ("GET http://127.0.0.1:<port>/z") — the old
        # assertion matched "/z" in that line. The keep-alive pool
        # tunnels with http.client's set_tunnel instead, so the proxy
        # sees only "CONNECT 127.0.0.1:<port>" and the GET /z travels
        # inside the tunnel, invisible to the proxy. A CONNECT to the
        # origin port is the proof the fetch went through the proxy.
        self.assertTrue(
            any(line.startswith(f"CONNECT 127.0.0.1:{self.origin_port}")
                for line in self.proxy.seen),
            f"proxy saw: {self.proxy.seen}")

    def test_fast_probe_factory_tunnels_when_on(self):
        with mock.patch.dict(os.environ,
                             {"RADD_WARP_PROXY": self._proxy_url()}):
            conn = fast_probe.default_connection_factory(
                "example.com", 443, True, 5)
        self.assertEqual("127.0.0.1", conn.host)
        self.assertEqual(self.proxy_port, conn.port)
        self.assertEqual("example.com", conn._tunnel_host)
        self.assertEqual(443, conn._tunnel_port)
        conn.close()

    def test_fast_fetch_http_get_via_proxy(self):
        # unwrap_gdflix fetches through fast_fetch.http_get, which used a
        # raw urlopen (no proxy). It must now route through the proxy too.
        import fast_fetch
        url = f"http://127.0.0.1:{self.origin_port}/g"
        with mock.patch.dict(os.environ,
                             {"RADD_WARP_PROXY": self._proxy_url()}):
            status, final_url, body = fast_fetch.http_get(url, timeout=5)
        self.assertEqual(200, status)
        self.assertEqual(url, final_url)
        self.assertEqual("ORIGIN-OK", body)
        self.assertTrue(
            any("127.0.0.1" in line and "/g" in line
                for line in self.proxy.seen),
            f"proxy saw: {self.proxy.seen}")

    def test_direct_when_off_ignores_proxy(self):
        env = {k: v for k, v in os.environ.items() if k != "RADD_WARP_PROXY"}
        with mock.patch.dict(os.environ, env, clear=True):
            conn = fast_probe.default_connection_factory(
                "127.0.0.1", self.origin_port, False, 5)
            conn.request("GET", "/")
            resp = conn.getresponse()
            body = resp.read()
            conn.close()
        self.assertEqual(200, resp.status)
        self.assertEqual(ORIGIN_BODY, body)


if __name__ == "__main__":
    unittest.main()
