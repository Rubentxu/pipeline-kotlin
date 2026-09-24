#!/usr/bin/env python3
# test_consult_harness_verdict.py — Tests focalizados del consumidor mínimo.
#
# These tests inject a fake transport into consult-harness-verdict's
# fetch_* functions; the production script does NOT change shape to
# make tests green. Tests cover:
#
#   - MISSING (no verdict.json in harness)
#   - INVALID ZIP (verdict zip_sha256 does not match product release)
#   - INVALID PUBLISHER (publisher not in allowlist)
#   - PASS (clean PASS)
#   - FAIL_REPRODUCIBLE without --open-issue -> exit 2, no gh call
#   - FAIL_REPRODUCIBLE with --open-issue but no gh creds -> exit 75,
#     no stack trace, ISSUE_PENDING printed
#   - FAIL_NON_REPRODUCIBLE -> exit 0 (not actionable as motor issue)
#
# Authority: AGENTS.md §"V2 testing rules" (unit tests, TDD, RED/GREEN,
# mock only when needed for determinism; here the production path is
# GitHub API and tests must not hit the network).

import importlib.util
import io
import json
import os
import sys
import unittest
from contextlib import redirect_stdout, redirect_stderr
from unittest import mock


HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE  # scripts/ lives at the repo root
SCRIPT = os.path.join(ROOT, "consult-harness-verdict.py")


def _load_module():
    spec = importlib.util.spec_from_file_location("consult_harness_verdict",
                                                   SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _run_with_args(mod, argv, gh_stderr=None):
    """Invoke mod.main(argv) capturing stdout/stderr and the gh stub if any."""
    out = io.StringIO()
    err = io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        try:
            rc = mod.main(argv)
        except SystemExit as exc:
            rc = exc.code
    return rc, out.getvalue(), err.getvalue()


class _Base(unittest.TestCase):
    def setUp(self):
        self.mod = _load_module()
        self.tmp_receipts = self._mkdtemp()

    def _mkdtemp(self):
        import tempfile
        return tempfile.mkdtemp(prefix="consult-receipts-")

    def _patch_receipt_dir(self):
        # Patch _print_receipt so we don't pollute the real docs tree.
        return mock.patch.object(self.mod, "_print_receipt",
                                  return_value="/tmp/fake-receipt.json")

    def _stub_verdict(self, verdict, release_sha="deadbeef"):
        return mock.patch.object(self.mod, "fetch_verdict",
                                  return_value=(verdict, {
                                      "path": f"evidence/x/verdict.json",
                                      "sha": release_sha,
                                      "html_url": "https://example/verdict",
                                      "last_commit_sha": "f00d",
                                  }))

    def _stub_release(self, sha256):
        return mock.patch.object(
            self.mod, "fetch_product_release_zip_sha256",
            return_value=(sha256, {"id": 1, "tag_name": "vX"}))


class MissingCase(_Base):
    def test_missing_returns_4(self):
        with mock.patch.object(self.mod, "fetch_verdict",
                                return_value=(None, None)):
            rc, out, err = _run_with_args(self.mod, ["--candidate", "v0"])
        self.assertEqual(rc, 4)
        self.assertIn("MISSING", out)


class InvalidZipCase(_Base):
    def test_zip_mismatch_is_invalid(self):
        verdict = {
            "result": "PASS",
            "publisher": "pipelinek-harness[bot]",
            "zip_sha256": "aaaa" * 16,  # fake
            "scenario": "S1",
            "harness_sha": "ca2a91c0",
        }
        with self._stub_verdict(verdict), \
             self._stub_release("bbbb" * 16), \
             self._patch_receipt_dir():
            rc, out, err = _run_with_args(self.mod, ["--candidate", "vX"])
        self.assertEqual(rc, 3)
        self.assertIn("INVALID", out)
        self.assertIn("zip_match=False", out)


class InvalidPublisherCase(_Base):
    def test_unknown_publisher_is_invalid(self):
        verdict = {
            "result": "PASS",
            "publisher": "evil-publisher",
            "zip_sha256": "1234" * 16,
            "scenario": "S1",
            "harness_sha": "ca2a91c0",
        }
        with self._stub_verdict(verdict), \
             self._stub_release("1234" * 16), \
             self._patch_receipt_dir(), \
             mock.patch.dict(os.environ, {"PIPELINEK_ALLOWED_AUTHORS": ""}):
            rc, out, err = _run_with_args(self.mod, ["--candidate", "vX"])
        self.assertEqual(rc, 3)
        self.assertIn("INVALID", out)
        self.assertIn("publisher_ok=False", out)


class PassCase(_Base):
    def test_pass_is_zero(self):
        sha = "1234" * 16
        verdict = {
            "result": "PASS",
            "publisher": "pipelinek-harness[bot]",
            "zip_sha256": sha,
            "scenario": "S1",
            "harness_sha": "ca2a91c0",
        }
        with self._stub_verdict(verdict), \
             self._stub_release(sha), \
             self._patch_receipt_dir():
            rc, out, err = _run_with_args(self.mod, ["--candidate", "vX"])
        self.assertEqual(rc, 0)
        self.assertIn("status=PASS", out)


class FailReproducibleCase(_Base):
    def test_fail_reproducible_no_open_issue(self):
        sha = "dead" * 16
        verdict = {
            "result": "FAIL",
            "failure_kind": "reproducible",
            "publisher": "pipelinek-harness[bot]",
            "zip_sha256": sha,
            "scenario": "spring-rest-petclinic",
            "symptom": "Pipeline failed at step sh",
            "cause": "OOM in shell step under load",
            "harness_sha": "ca2a91c0",
        }
        with self._stub_verdict(verdict), \
             self._stub_release(sha), \
             self._patch_receipt_dir():
            rc, out, err = _run_with_args(
                self.mod, ["--candidate", "vX"])
        self.assertEqual(rc, 2)
        self.assertIn("status=FAIL_REPRODUCIBLE", out)
        self.assertIn("NOT_REQUESTED", out)

    def test_fail_reproducible_open_issue_no_creds(self):
        sha = "beef" * 16
        verdict = {
            "result": "FAIL",
            "failure_kind": "reproducible",
            "publisher": "pipelinek-harness[bot]",
            "zip_sha256": sha,
            "scenario": "spring-rest-petclinic",
            "symptom": "OOM",
            "cause": "memory cap",
            "harness_sha": "ca2a91c0",
        }
        # gh returns 404 (unauthenticated) -> ISSUE_PENDING, exit 75.
        fake_proc = mock.Mock(returncode=1, stdout="",
                              stderr="gh: not logged in")
        with self._stub_verdict(verdict), \
             self._stub_release(sha), \
             self._patch_receipt_dir(), \
             mock.patch.object(self.mod.subprocess, "run",
                               return_value=fake_proc):
            rc, out, err = _run_with_args(
                self.mod, ["--candidate", "vX", "--open-issue"])
        self.assertEqual(rc, 75)
        self.assertIn("ISSUE_PENDING", out)
        # No Python traceback in stderr.
        self.assertNotIn("Traceback", err)


class FailNonReproducibleCase(_Base):
    def test_fail_non_reproducible_is_zero(self):
        sha = "cafe" * 16
        verdict = {
            "result": "FAIL",
            "failure_kind": "transient",
            "publisher": "pipelinek-harness[bot]",
            "zip_sha256": sha,
            "scenario": "spring-rest-petclinic",
            "harness_sha": "ca2a91c0",
        }
        with self._stub_verdict(verdict), \
             self._stub_release(sha), \
             self._patch_receipt_dir():
            rc, out, err = _run_with_args(self.mod, ["--candidate", "vX"])
        self.assertEqual(rc, 0)
        self.assertIn("status=FAIL_NON_REPRODUCIBLE", out)


class IdempotencyCase(_Base):
    def test_fingerprint_stable(self):
        sha = "abcd" * 16
        verdict = {
            "result": "FAIL",
            "failure_kind": "reproducible",
            "publisher": "pipelinek-harness[bot]",
            "zip_sha256": sha,
            "scenario": "S1",
            "symptom": "x",
            "cause": "y",
            "harness_sha": "ca2a91c0",
        }
        with self._stub_verdict(verdict), \
             self._stub_release(sha), \
             self._patch_receipt_dir():
            fp_a = self.mod.compute_issue_fingerprint(
                "vX", verdict, sha)
        # Same inputs -> same fingerprint (idempotency for dedup).
        with self._stub_verdict(verdict), \
             self._stub_release(sha), \
             self._patch_receipt_dir():
            fp_b = self.mod.compute_issue_fingerprint(
                "vX", verdict, sha)
        self.assertEqual(fp_a, fp_b)
        self.assertEqual(len(fp_a), 64)


class JsonOutputCase(_Base):
    def test_json_output_is_valid_json(self):
        sha = "1234" * 16
        verdict = {
            "result": "PASS",
            "publisher": "pipelinek-harness[bot]",
            "zip_sha256": sha,
            "scenario": "S1",
            "harness_sha": "ca2a91c0",
        }
        with self._stub_verdict(verdict), \
             self._stub_release(sha), \
             self._patch_receipt_dir():
            rc, out, err = _run_with_args(
                self.mod, ["--candidate", "vX", "--json-output"])
        self.assertEqual(rc, 0)
        parsed = json.loads(out)
        self.assertEqual(parsed["status"], "PASS")
        self.assertEqual(parsed["zip_match"], True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
