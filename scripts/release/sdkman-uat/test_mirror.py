#!/usr/bin/env python3
"""Behavioural tests for the SDKMAN protocol mirror (first suite for this spike).

Hermetic and loopback-only: the canonical ZIP is a temp fixture with its real
digest, and the server is bound to 127.0.0.1 on an ephemeral port. Nothing here
touches the public internet or the operator's real archive.
"""
from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import pathlib
import shutil
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import zipfile
from http.server import ThreadingHTTPServer
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "mirror.py"


def load_module():
    spec = importlib.util.spec_from_file_location("sdkman_mirror", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Fixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="mirror-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.zip_path = self.tmp / "pipelinek-0.39.0.zip"
        with zipfile.ZipFile(self.zip_path, "w") as zf:
            zf.writestr("pipelinek-0.39.0/bin/pipelinek", b"#!/bin/sh\n")
        self.digest = hashlib.sha256(self.zip_path.read_bytes()).hexdigest()
        self.patches = [
            mock.patch.object(self.mod, "CANONICAL_ZIP_PATH", str(self.zip_path)),
            mock.patch.object(self.mod, "EXPECTED_SHA256", self.digest),
        ]
        for p in self.patches:
            p.start()
            self.addCleanup(p.stop)

    def start_server(self):
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), self.mod.SdkmanMirrorHandler)
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()

        def stop():
            httpd.shutdown()
            httpd.server_close()
            thread.join(timeout=5)

        self.addCleanup(stop)
        return f"http://127.0.0.1:{httpd.server_address[1]}"

    def get(self, base: str, path: str):
        req = urllib.request.Request(base + path)
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read(), dict(resp.headers)


class Endpoints(Fixture):
    def test_healthcheck_and_candidate_listing(self) -> None:
        base = self.start_server()
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(self.get(base, "/healthcheck")[1], b"ok")
            self.assertEqual(self.get(base, "/candidates/list")[1], b"pipelinek\n")
            self.assertEqual(self.get(base, "/candidates/default/pipelinek")[1], b"0.39.0")
            status, body, _ = self.get(
                base, "/candidates/validate/pipelinek/0.39.0/UNIVERSAL")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"valid")

    def test_versions_list_mentions_the_version(self) -> None:
        base = self.start_server()
        with contextlib.redirect_stderr(io.StringIO()):
            _, body, _ = self.get(
                base, "/candidates/pipelinek/UNIVERSAL/versions/list?current=&installed=")
        self.assertIn(b"0.39.0", body)

    def test_download_serves_the_exact_archive_bytes_and_digest_header(self) -> None:
        """Claim: the served bytes ARE the canonical archive.

        Mutation: serve a re-zipped or transformed archive; the digest header
        would disagree with the body and SDKMAN's checksum step would fail.
        """
        base = self.start_server()
        with contextlib.redirect_stderr(io.StringIO()):
            status, body, headers = self.get(
                base, "/download/pipelinek/0.39.0/UNIVERSAL")
        self.assertEqual(status, 200)
        self.assertEqual(body, self.zip_path.read_bytes())
        self.assertEqual(headers.get("X-Sdkman-Checksum-Sha256"), self.digest)
        self.assertEqual(headers.get("Content-Type"), "application/zip")

    def test_hook_is_sourced_safe(self) -> None:
        """Claim: the post-install hook never calls `exit`.

        The SDKMAN client SOURCES this script; an `exit` inside it would kill
        the caller shell instead of returning to it. The mirror's own docstring
        records this as the convention, and this test pins it.
        """
        base = self.start_server()
        with contextlib.redirect_stderr(io.StringIO()):
            status, body, _ = self.get(
                base, "/hooks/post/pipelinek/0.39.0/UNIVERSAL")
        text = body.decode()
        self.assertEqual(status, 200)
        self.assertIn("__sdkman_post_installation_hook", text)
        self.assertNotIn("exit", text)

    def test_unknown_path_is_404(self) -> None:
        base = self.start_server()
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                self.get(base, "/nope")
        self.assertEqual(ctx.exception.code, 404)


class CanonicalZipGuard(Fixture):
    def test_missing_zip_fails_closed(self) -> None:
        with mock.patch.object(self.mod, "CANONICAL_ZIP_PATH",
                               str(self.tmp / "absent.zip")):
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                with self.assertRaises(SystemExit) as ctx:
                    self.mod.assert_canonical_zip()
        self.assertEqual(ctx.exception.code, 2)
        self.assertIn("canonical ZIP not found", err.getvalue())

    def test_digest_mismatch_fails_closed(self) -> None:
        """Claim: a modified archive cannot be served as canonical.

        Mutation: skip the digest check; any locally modified ZIP would be
        served under the certified digest header.
        """
        with mock.patch.object(self.mod, "EXPECTED_SHA256", "0" * 64):
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                with self.assertRaises(SystemExit) as ctx:
                    self.mod.assert_canonical_zip()
        self.assertEqual(ctx.exception.code, 2)
        self.assertIn("SHA mismatch", err.getvalue())

    def test_matching_digest_passes(self) -> None:
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.mod.assert_canonical_zip()
        self.assertIn("canonical ZIP verified", err.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
