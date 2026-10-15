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
import pathlib
import sys
import unittest
from contextlib import redirect_stdout, redirect_stderr
from unittest import mock


HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(HERE)
ROOT = HERE  # scripts/ lives at the repo root
SCRIPT = os.path.join(ROOT, "consult-harness-verdict.py")
RECEIPTS_IN_REPO = os.path.join(REPO_ROOT, "docs", "v2", "07-uat", "RECEIPTS", "consult")


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
        # HARNESS FIDELITY LAW §4: no test may write receipts into the tracked
        # tree. The receipt root is injectable and EVERY test gets the temp
        # root, so a future case that forgets _patch_receipt_dir() is still
        # hermetic.
        env = mock.patch.dict(os.environ,
                              {"PIPELINEK_CONSULT_RECEIPT_ROOT": self.tmp_receipts})
        env.start()
        self.addCleanup(env.stop)

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


class FailureKindIsRequired(_Base):
    def _verdict(self, **overrides):
        base = {
            "result": "FAIL",
            "publisher": "pipelinek-harness[bot]",
            "zip_sha256": "dead" * 16,
            "scenario": "S1",
            "harness_sha": "ca2a91c0",
        }
        base.update(overrides)
        return base

    def test_fail_without_failure_kind_is_invalid(self):
        """Claim: a FAIL with no discriminator is not silently 'not actionable'.

        Mutation: keep the bare `elif result == FAIL` arm; an absent
        failure_kind exits 0 exactly like PASS, with no receipt distinction.
        """
        verdict = self._verdict()
        with self._stub_verdict(verdict), self._stub_release("dead" * 16), \
             self._patch_receipt_dir():
            rc, out, err = _run_with_args(self.mod, ["--candidate", "vX"])
        self.assertEqual(rc, 3, "a missing failure_kind is a missing critical field")
        self.assertIn("INVALID", out)

    def test_fail_with_unknown_failure_kind_is_invalid(self):
        """Claim: an unknown discriminator is not a non-reproducible verdict.

        Mutation: default the unknown branch to FAIL_NON_REPRODUCIBLE.
        """
        verdict = self._verdict(failure_kind="weird-unknown-value")
        with self._stub_verdict(verdict), self._stub_release("dead" * 16), \
             self._patch_receipt_dir():
            rc, out, err = _run_with_args(self.mod, ["--candidate", "vX"])
        self.assertEqual(rc, 3)
        self.assertIn("INVALID", out)

    def test_failure_kind_is_normalised(self):
        """Claim: case/whitespace do not change the discrimination.

        Mutation: compare raw; 'Reproducible' would fall into the invalid
        branch although it states the same fact.
        """
        verdict = self._verdict(failure_kind="  Reproducible ")
        with self._stub_verdict(verdict), self._stub_release("dead" * 16), \
             self._patch_receipt_dir():
            rc, out, err = _run_with_args(self.mod, ["--candidate", "vX"])
        self.assertEqual(rc, 2)
        self.assertIn("FAIL_REPRODUCIBLE", out)


class TypedFieldsCase(_Base):
    def test_non_string_result_field_is_invalid(self):
        """Claim: a JSON object with wrong field types is INVALID, not a crash.

        Mutation: call .upper() on the raw value; {"result": 1} raises
        AttributeError and escapes the documented exit codes with a traceback.
        """
        verdict = {
            "result": 1,
            "publisher": "pipelinek-harness[bot]",
            "zip_sha256": "dead" * 16,
            "scenario": "S1",
            "harness_sha": "ca2a91c0",
        }
        with self._stub_verdict(verdict), self._stub_release("dead" * 16), \
             self._patch_receipt_dir():
            rc, out, err = _run_with_args(self.mod, ["--candidate", "vX"])
        self.assertEqual(rc, 3)
        self.assertNotIn("Traceback", err)
        self.assertIn("INVALID", out)

    def test_top_level_json_list_is_invalid(self):
        """Claim: a non-object verdict.json is refused with a typed verdict.

        Mutation: index the parsed document as a dict; a list raises
        AttributeError before any receipt is written.
        """
        with self._stub_verdict(["not", "an", "object"]), \
             self._stub_release("dead" * 16), self._patch_receipt_dir():
            rc, out, err = _run_with_args(self.mod, ["--candidate", "vX"])
        self.assertEqual(rc, 3)
        self.assertNotIn("Traceback", err)
        self.assertIn("INVALID", out)


class MinimalEvidenceCase(_Base):
    def test_pass_requires_scenario_and_harness_sha(self):
        """Claim: PASS needs the declared verification fields present.

        Mutation: classify PASS on the result field alone; a verdict with
        empty scenario and harness_sha then reports status=PASS, exit 0, and
        a receipt whose verification surface is empty.
        """
        sha = "1234" * 16
        verdict = {
            "result": "PASS",
            "publisher": "pipelinek-harness[bot]",
            "zip_sha256": sha,
            "scenario": "",
            "harness_sha": "",
        }
        with self._stub_verdict(verdict), self._stub_release(sha), \
             self._patch_receipt_dir():
            rc, out, err = _run_with_args(self.mod, ["--candidate", "vX"])
        self.assertEqual(rc, 3)
        self.assertIn("INVALID", out)

    def test_zip_sha256_must_be_64_hex(self):
        """Claim: a digest that matches itself is not a verified digest.

        Mutation: compare strings without shape validation; 'abc' == 'abc'
        yields zip_match=True and PASS.
        """
        verdict = {
            "result": "PASS",
            "publisher": "pipelinek-harness[bot]",
            "zip_sha256": "abc",
            "scenario": "S1",
            "harness_sha": "ca2a91c0",
        }
        with self._stub_verdict(verdict), self._stub_release("abc"), \
             self._patch_receipt_dir():
            rc, out, err = _run_with_args(self.mod, ["--candidate", "vX"])
        self.assertEqual(rc, 3)
        self.assertIn("INVALID", out)


class ZipSidecarCase(_Base):
    class _ReadableResponse:
        """A sidecar body that a naive implementation WOULD accept."""

        def __init__(self, body: bytes):
            self._body = body

        def read(self) -> bytes:
            return self._body

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    def _readable_urlopen(self, calls):
        def fake(url, timeout=30):
            calls.append(url)
            return self._ReadableResponse(b"f" * 64 + b"\n")
        return fake

    def test_sha256_asset_must_match_candidate_name(self):
        """Claim: the sidecar read must belong to the candidate's ZIP.

        Mutation: accept any *.zip.sha256. The fake body is readable, so a
        suffix-matching implementation returns a digest for FOREIGN bytes;
        this test then fails on both assertions.
        """
        assets = [{"name": "pipelinek-OTHER.zip.sha256",
                   "browser_download_url": "https://example/other"}]
        calls = []
        with mock.patch.object(self.mod, "_gh_get",
                               return_value={"assets": assets}), \
             mock.patch.object(self.mod.urllib.request, "urlopen",
                               side_effect=self._readable_urlopen(calls)):
            sha, _release = self.mod.fetch_product_release_zip_sha256("repo", "vX")
        self.assertIsNone(sha)
        self.assertEqual(calls, [], "must not fetch a foreign sidecar")

    def test_multiple_sha256_siblings_fail_closed(self):
        """Claim: ambiguous sidecars are refused, not last-wins.

        Mutation: iterate and keep the last readable value; two siblings
        silently decide the digest by asset order.
        """
        same = [{"name": "pipelinek-vX.zip.sha256",
                 "browser_download_url": "https://example/a"},
                {"name": "pipelinek-vX.zip.sha256",
                 "browser_download_url": "https://example/b"}]
        calls = []
        with mock.patch.object(self.mod, "_gh_get", return_value={"assets": same}), \
             mock.patch.object(self.mod.urllib.request, "urlopen",
                               side_effect=self._readable_urlopen(calls)):
            sha, _release = self.mod.fetch_product_release_zip_sha256("repo", "vX")
        self.assertIsNone(sha)
        self.assertEqual(calls, [], "ambiguous siblings must not be fetched at all")


class ReceiptHermeticityCase(_Base):
    def test_missing_case_writes_under_the_env_root_only(self):
        """Claim: running a case may not fabricate a receipt in the repo.

        Mutation: drop the env override and the MissingCase patch; the suite
        writes docs/v2/07-uat/RECEIPTS/consult/<candidate>-<ts>/verdict.json
        into the tracked tree on every run (HARNESS FIDELITY LAW §4).
        """
        before = set(os.listdir(RECEIPTS_IN_REPO)) if os.path.isdir(RECEIPTS_IN_REPO) else set()
        with mock.patch.object(self.mod, "fetch_verdict",
                               return_value=(None, None)):
            rc, out, err = _run_with_args(
                self.mod, ["--candidate", "v-hermetic-probe"])
        self.assertEqual(rc, 4)
        after = set(os.listdir(RECEIPTS_IN_REPO)) if os.path.isdir(RECEIPTS_IN_REPO) else set()
        self.assertEqual(before, after,
                         "the test run must not write into the tracked receipts dir")
        written = list(pathlib.Path(self.tmp_receipts).glob(
            "v-hermetic-probe-*/verdict.json"))
        self.assertEqual(len(written), 1,
                         "the MISSING receipt must land under the injected root")


if __name__ == "__main__":
    unittest.main(verbosity=2)
