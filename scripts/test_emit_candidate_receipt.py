#!/usr/bin/env python3
"""Behavioural tests for emit-candidate-receipt.py.

Hermetic: a real ZIP (with a real nested application JAR), a real git repo, a
manifest and a handoff are built in a temp directory, so every check is measured
against actual bytes.
"""
from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import pathlib
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "emit-candidate-receipt.py"

VERSION = "0.61.0"
ARCHIVE_ROOT = f"pipelinek-{VERSION}"


def load_module():
    spec = importlib.util.spec_from_file_location("emit_candidate_receipt", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def make_jar_bytes(implementation_version: str | None = VERSION) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as jar:
        manifest = "Manifest-Version: 1.0\n"
        if implementation_version is not None:
            manifest += f"Implementation-Version: {implementation_version}\n"
        jar.writestr("META-INF/MANIFEST.MF", manifest)
        jar.writestr("Main.class", b"\xca\xfe\xba\xbe")
    return buf.getvalue()


class Fixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="candidate-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        subprocess.run(["git", "-C", str(self.tmp), "init", "-q",
                        "--initial-branch=main"], check=True)
        subprocess.run(["git", "-C", str(self.tmp), "config", "user.email", "t@t"],
                       check=True)
        subprocess.run(["git", "-C", str(self.tmp), "config", "user.name", "t"],
                       check=True)
        subprocess.run(["git", "-C", str(self.tmp), "commit", "--allow-empty", "-q",
                        "-m", "init"], check=True)
        self.head = subprocess.run(["git", "-C", str(self.tmp), "rev-parse", "HEAD"],
                                   capture_output=True, text=True, check=True
                                   ).stdout.strip()
        self.zip_path = self.tmp / f"pipelinek-{VERSION}.zip"
        self.write_zip()
        self.manifest_path = self.tmp / "distribution-manifest.json"
        self.handoff_path = self.tmp / "candidate-handoff.json"
        self.write_docs()

    def write_zip(self, jar_version: str | None = VERSION) -> None:
        self.zip_path.unlink(missing_ok=True)
        with zipfile.ZipFile(self.zip_path, "w") as zf:
            zf.writestr(f"{ARCHIVE_ROOT}/lib/pipeline-application-{VERSION}.jar",
                        make_jar_bytes(jar_version))
            zf.writestr(f"{ARCHIVE_ROOT}/lib/pipeline-domain-{VERSION}.jar", b"lib")
            zf.writestr(f"{ARCHIVE_ROOT}/bin/pipelinek", b"#!/bin/sh\n")

    def digests(self) -> tuple[str, int]:
        digest = hashlib.sha256(self.zip_path.read_bytes()).hexdigest()
        return digest, self.zip_path.stat().st_size

    def write_docs(self, **handoff_overrides) -> None:
        digest, size = self.digests()
        manifest = {
            "schema_version": "pipelinek-distribution-manifest-2",
            "version": VERSION,
            "asset": {"name": self.zip_path.name, "archive_root": ARCHIVE_ROOT,
                      "size": size, "sha256": digest,
                      "implementation_version": VERSION},
            "source": {"git_commit": self.head},
            "sbom": None,
            "sha256sums": None,
        }
        handoff = {
            "schema_version": "pipelinek-candidate-2",
            "candidate_id": f"sha256:{digest}",
            "release_train": VERSION,
            "candidate_sequence": 1,
            "product_version": VERSION,
            "source_commit": self.head,
            "artifact": {"name": self.zip_path.name, "sha256": digest, "size": size,
                         "archive_root": ARCHIVE_ROOT,
                         "implementation_version": VERSION},
            "distribution_manifest": {"name": self.manifest_path.name,
                                      "sha256": hashlib.sha256(
                                          self.manifest_path.read_bytes()).hexdigest()
                                      if self.manifest_path.exists() else "",
                                      "built_at": self.head},
            "sbom": None,
        }
        handoff.update(handoff_overrides)
        self.manifest_path.write_text(json.dumps(manifest, indent=2))
        # The handoff declares the manifest digest, so write the manifest first
        # and then re-derive that one field.
        handoff["distribution_manifest"]["sha256"] = hashlib.sha256(
            self.manifest_path.read_bytes()).hexdigest()
        self.handoff_path.write_text(json.dumps(handoff, indent=2))

    def evaluate(self, **overrides):
        manifest = json.loads(self.manifest_path.read_text())
        handoff = json.loads(self.handoff_path.read_text())
        kwargs = dict(sbom_path=None, manifest_path=self.manifest_path,
                      expect_sha=None, observed_head=self.head)
        kwargs.update(overrides)
        return self.mod.evaluate(self.zip_path, manifest, handoff, **kwargs)

    def run_main(self, extra=()):
        out = self.tmp / "receipt.json"
        argv = ["--zip", str(self.zip_path), "--manifest", str(self.manifest_path),
                "--handoff", str(self.handoff_path), "--root", str(self.tmp),
                "--out", str(out), *extra]
        buf = io.StringIO()
        import contextlib
        with contextlib.redirect_stdout(buf):
            rc = self.mod.main(argv)
        return rc, buf.getvalue(), out


class ConsistentCandidate(Fixture):
    def test_every_check_passes(self) -> None:
        receipt = self.evaluate()
        self.assertEqual(receipt["verdict"], "PASS")
        failed = [c for c in receipt["checks"] if c["verdict"] != "PASS"]
        self.assertEqual(failed, [])

    def test_main_exits_zero_and_writes_the_receipt(self) -> None:
        rc, out, out_path = self.run_main()
        self.assertEqual(rc, 0, out)
        receipt = json.loads(out_path.read_text())
        self.assertEqual(receipt["verdict"], "PASS")
        self.assertEqual(receipt["zip"]["sha256"],
                         hashlib.sha256(self.zip_path.read_bytes()).hexdigest())
        self.assertEqual(receipt["git_head_observed"], self.head)

    def test_expect_sha_matching_is_accepted(self) -> None:
        rc, out, _ = self.run_main(["--expect-sha", self.head])
        self.assertEqual(rc, 0, out)


class Mismatches(Fixture):
    def test_wrong_artifact_digest_fails(self) -> None:
        """Claim: a handoff describing other bytes cannot certify these bytes.

        Mutation: trust the declared digest; a rewritten handoff would pass over
        any ZIP.
        """
        self.write_docs()
        handoff = json.loads(self.handoff_path.read_text())
        handoff["artifact"]["sha256"] = "0" * 64
        self.handoff_path.write_text(json.dumps(handoff))
        self.assertEqual(self.evaluate()["verdict"], "FAIL")

    def test_product_identity_law_rejects_token_prefix(self) -> None:
        """Claim: `0.61.0` inside `0.61.0-rc2` is a defect, not a match.

        Mutation: compare by substring; the v0.43.0 identity incident would be
        admitted.
        """
        self.write_docs(product_version=f"{VERSION}-rc2")
        receipt = self.evaluate()
        self.assertEqual(receipt["verdict"], "FAIL")
        law = [c for c in receipt["checks"]
               if c["check"] == "product identity law (exact tokens)"][0]
        self.assertEqual(law["verdict"], "FAIL")

    def test_embedded_version_mismatch_fails(self) -> None:
        self.write_zip(jar_version="0.60.0")
        self.write_docs()
        receipt = self.evaluate()
        self.assertEqual(receipt["verdict"], "FAIL")
        jar_check = [c for c in receipt["checks"]
                     if c["check"] == "app JAR carries Implementation-Version"][0]
        self.assertEqual(jar_check["verdict"], "PASS")  # version present, but wrong
        agree = [c for c in receipt["checks"]
                 if c["check"].startswith("implementation_version agrees")][0]
        self.assertEqual(agree["verdict"], "FAIL")

    def test_missing_embedded_version_fails(self) -> None:
        self.write_zip(jar_version=None)
        self.write_docs()
        receipt = self.evaluate()
        self.assertEqual(receipt["verdict"], "FAIL")

    def test_observed_head_mismatch_fails(self) -> None:
        self.assertEqual(self.evaluate(observed_head="0" * 40)["verdict"], "FAIL")

    def test_expect_sha_mismatch_fails(self) -> None:
        rc, out, _ = self.run_main(["--expect-sha", "0" * 40])
        self.assertEqual(rc, 1)
        self.assertIn("verdict=FAIL", out)

    def test_declared_manifest_digest_is_checked(self) -> None:
        self.write_docs()
        self.manifest_path.write_text(self.manifest_path.read_text() + "\n")
        receipt = self.evaluate()
        self.assertEqual(receipt["verdict"], "FAIL")
        check = [c for c in receipt["checks"]
                 if c["check"] == "distribution manifest digest == declared"][0]
        self.assertEqual(check["verdict"], "FAIL")


class Blocked(Fixture):
    def test_missing_handoff_is_blocked(self) -> None:
        self.handoff_path.unlink()
        rc, out, _ = self.run_main()
        self.assertEqual(rc, 2)
        self.assertEqual(out.strip(), "", "a blocked run prints no receipt body")

    def test_unreadable_manifest_is_blocked(self) -> None:
        self.manifest_path.write_text("{not json")
        rc, out, _ = self.run_main()
        self.assertEqual(rc, 2)

    def test_ambiguous_archive_root_fails_closed(self) -> None:
        """Claim: a ZIP without a single top folder cannot be admitted.

        Mutation: take the first top-level name; a flat or mixed archive would
        be accepted with an invented root.
        """
        self.zip_path.unlink()
        with zipfile.ZipFile(self.zip_path, "w") as zf:
            zf.writestr("bin/pipelinek", b"x")
            zf.writestr("other/readme", b"y")
        self.assertEqual(self.mod.zip_archive_root(self.zip_path), None)


if __name__ == "__main__":
    unittest.main(verbosity=2)
