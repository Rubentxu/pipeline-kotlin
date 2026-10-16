#!/usr/bin/env python3
"""Behavioural tests for verify-published-release.py (B4.6).

Every test builds a REAL distribution-shaped ZIP (archive root + an inner
application JAR whose manifest carries Implementation-Version), serves it from a
REAL loopback HTTP mirror, and drives the tool through its CLI against that
mirror. Nothing touches the network beyond the mirror, and the tool is never
reimplemented: the identity reading it delegates to
`emit-candidate-receipt.py` is the production reader.

Claims pinned here:
  - a release with asset + sidecar + inventory that all agree is VERIFIED;
  - a sidecar describing different bytes is MISMATCH (the download cannot be
    accepted by the API's own word);
  - a wrong archive root or a wrong embedded version is MISMATCH (exact token);
  - an inventory that disagrees with the sidecar is MISMATCH;
  - no sidecar at all is MISSING: "it downloaded" is not identity;
  - a missing asset is MISSING, a dead mirror is BLOCKED (different causes,
    different exit codes).
"""
from __future__ import annotations

import functools
import http.server
import importlib.util
import io
import json
import pathlib
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "verify-published-release.py"


def load_module():
    spec = importlib.util.spec_from_file_location("verify_published_release", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def build_distribution_zip(path: pathlib.Path, *, version: str,
                           archive_root: str | None = None,
                           embedded_version: str | None = None) -> str:
    """A ZIP shaped like the real distribution, with the app JAR inside.

    Returns the sha256 of the bytes written.
    """
    root = archive_root or f"pipelinek-{version}"
    inner = embedded_version if embedded_version is not None else version
    jar_buffer = io.BytesIO()
    with zipfile.ZipFile(jar_buffer, "w") as jar:
        jar.writestr("META-INF/MANIFEST.MF",
                     f"Manifest-Version: 1.0\nImplementation-Version: {inner}\n")
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr(f"{root}/bin/pipelinek", "#!/bin/sh\necho pipelinek\n")
        # The real layout, so the production reader's regex is what matches:
        # pipelinek-<v>/lib/pipeline-application-<v>.jar
        zf.writestr(f"{root}/lib/pipeline-application-{version}.jar",
                    jar_buffer.getvalue())
    mod = load_module()
    return mod.sha256_file(path)


class Mirror:
    """A real loopback HTTP server serving a directory."""

    def __init__(self, case: unittest.TestCase):
        self.case = case
        self.tmp = tempfile.TemporaryDirectory()
        case.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)

    def serve(self) -> str:
        handler = functools.partial(http.server.SimpleHTTPRequestHandler,
                                    directory=str(self.root))
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        httpd = http.server.ThreadingHTTPServer(("127.0.0.1", port), handler)
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        self.case.addCleanup(httpd.shutdown)
        self.case.addCleanup(httpd.server_close)
        return f"http://127.0.0.1:{port}"


class ReleaseVerification(unittest.TestCase):
    VERSION = "0.61.0"

    def setUp(self) -> None:
        self.mirror = Mirror(self)
        self.asset_name = f"pipelinek-{self.VERSION}.zip"

    def _assets(self, *, archive_root=None, embedded_version=None,
                sidecar_digest=None, inventory_digest=None, sidecar=True,
                inventory=False) -> str:
        asset = self.mirror.root / self.asset_name
        digest = build_distribution_zip(asset, version=self.VERSION,
                                        archive_root=archive_root,
                                        embedded_version=embedded_version)
        if sidecar:
            (self.mirror.root / f"{self.asset_name}.sha256").write_text(
                f"{sidecar_digest or digest}  {self.asset_name}\n")
        if inventory:
            (self.mirror.root / "SHA256SUMS").write_text(
                f"{inventory_digest or digest}  {self.asset_name}\n")
        return digest

    def _run(self, base_url: str, *extra: str):
        out = subprocess.run(
            [sys.executable, str(SCRIPT), "--tag", "v0.61.0-rc1",
             "--version", self.VERSION, "--base-url", base_url, "--json", *extra],
            capture_output=True, text=True, timeout=120)
        payload = json.loads(out.stdout) if out.stdout.strip() else {}
        return out.returncode, payload, out.stderr

    def test_verified_release(self):
        self._assets(inventory=True)
        rc, payload, err = self._run(self.mirror.serve())
        self.assertEqual(rc, 0, err)
        self.assertEqual(payload["verdict"], "VERIFIED")
        self.assertEqual(payload["sidecar"], f"{self.asset_name}.sha256")
        self.assertTrue(all(c["verdict"] == "PASS" for c in payload["checks"]))

    def test_sidecar_describing_other_bytes_is_mismatch(self):
        """Mutation: trust the API/asset instead of the sidecar -> a swapped
        artefact passes verification."""
        self._assets(sidecar_digest="0" * 64)
        rc, payload, _ = self._run(self.mirror.serve())
        self.assertEqual(rc, 1)
        self.assertEqual(payload["verdict"], "MISMATCH")
        self.assertEqual(payload["reason"], "digest disagreement")

    def test_wrong_archive_root_is_mismatch(self):
        self._assets(archive_root="pipelinek-0.60.0")
        rc, payload, _ = self._run(self.mirror.serve())
        self.assertEqual(rc, 1)
        self.assertEqual(payload["verdict"], "MISMATCH")

    def test_wrong_embedded_version_is_mismatch(self):
        """Mutation: accept a prefix instead of an exact token -> 0.61.0 inside
        0.61.0-rc1 would pass, which is the v0.43.0 defect."""
        self._assets(embedded_version="0.61.0-rc1")
        rc, payload, _ = self._run(self.mirror.serve())
        self.assertEqual(rc, 1)
        self.assertEqual(payload["verdict"], "MISMATCH")

    def test_inventory_disagreement_is_mismatch(self):
        self._assets(inventory=True, inventory_digest="1" * 64)
        rc, payload, _ = self._run(self.mirror.serve())
        self.assertEqual(rc, 1)
        self.assertEqual(payload["verdict"], "MISMATCH")
        self.assertEqual(payload["reason"], "inventory disagreement")

    def test_no_independent_digest_is_missing_not_verified(self):
        """Mutation: treat "downloaded successfully" as verification -> a release
        with no independent digest is accepted."""
        self._assets(sidecar=False, inventory=False)
        rc, payload, _ = self._run(self.mirror.serve())
        self.assertEqual(rc, 4)
        self.assertEqual(payload["verdict"], "MISSING")
        self.assertIn("not an independent check", payload["reason"])

    def test_inventory_alone_is_an_independent_digest(self):
        """A release that ships SHA256SUMS and no per-asset sidecar is still
        verifiable: the inventory is a published file, not the API's own word.
        Mutation: require the sidecar specifically -> a legitimate release is
        reported MISSING and a consumer would rebuild what it already has."""
        self._assets(sidecar=False, inventory=True)
        rc, payload, _ = self._run(self.mirror.serve())
        self.assertEqual(rc, 0, payload)
        self.assertEqual(payload["verdict"], "VERIFIED")
        self.assertEqual(payload["sidecar"], "SHA256SUMS")

    def test_missing_asset_is_missing(self):
        self.mirror.root.mkdir(parents=True, exist_ok=True)
        rc, payload, _ = self._run(self.mirror.serve())
        self.assertEqual(rc, 4)
        self.assertEqual(payload["verdict"], "MISSING")

    def test_dead_mirror_is_blocked_not_missing(self):
        """Mutation: collapse every download failure into one verdict -> a network
        problem reads as "not published", which invites a rebuild."""
        self._assets()
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            dead_port = probe.getsockname()[1]
        rc, payload, _ = self._run(f"http://127.0.0.1:{dead_port}")
        self.assertEqual(rc, 3)
        self.assertEqual(payload["verdict"], "BLOCKED")

    def test_zip_sidecar_name_is_also_accepted(self):
        self._assets()
        asset = self.mirror.root / f"{self.asset_name}.sha256"
        digest = load_module().sha256_file(self.mirror.root / self.asset_name)
        asset.unlink()
        (self.mirror.root / "zip.sha256").write_text(
            f"{digest}  v2/pipeline-application/build/distributions/{self.asset_name}\n")
        rc, payload, _ = self._run(self.mirror.serve())
        self.assertEqual(rc, 0, payload)
        self.assertEqual(payload["sidecar"], "zip.sha256")


if __name__ == "__main__":
    unittest.main(verbosity=2)
