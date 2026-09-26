#!/usr/bin/env python3
"""Unit tests for admission-check.py.

Run:
    python3 scripts/test_admission_check.py
"""
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "admission-check.py"


def _load_mod():
    spec = importlib.util.spec_from_file_location("admission_check", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class AdmissionCheckTests(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_mod()

    def test_parse_state_head_sha(self):
        text = "| **HEAD** | `abc123def456abc123def456abc123def456abc1` | rest"
        self.assertEqual(self.mod.parse_state_head_sha(text), "abc123def456abc123def456abc123def456abc1")

    def test_parse_state_head_sha_missing(self):
        self.assertIsNone(self.mod.parse_state_head_sha("no head here"))

    def test_parse_origin_main_sha(self):
        text = "| **origin/main** | `123abc456abc123def456abc123def456abc1234` |"
        self.assertEqual(self.mod.parse_origin_main_sha(text), "123abc456abc123def456abc123def456abc1234")

    def test_parse_uat_status_summary(self):
        text = """
- **COVERED:** 23
- **PARTIAL:** 2
- **REFERENCED:** 2
"""
        counts = self.mod.parse_uat_status_summary(text)
        self.assertEqual(counts["COVERED"], 23)
        self.assertEqual(counts["PARTIAL"], 2)
        self.assertEqual(counts["REFERENCED"], 2)

    def test_load_exceptions_missing_file(self):
        # Default EXCEPTIONS_FILE is .agent/ADMISSION_EXCEPTIONS.md
        # which may or may not exist; the function returns empty set
        exceptions = self.mod.load_exceptions()
        self.assertIsInstance(exceptions, set)

    def test_r2_pass_when_head_matches(self):
        text = "| **HEAD** | `abcdef0123456789abcdef0123456789abcdef01` |"
        ok, msg = self.mod.check_r2_head_matches(text, "abcdef0123456789abcdef0123456789abcdef01")
        self.assertTrue(ok)
        self.assertIsNone(msg)

    def test_r2_fail_on_mismatch(self):
        # 40-char SHAs that differ
        sha_a = "a" * 40
        sha_b = "b" * 40
        text = f"| **HEAD** | `{sha_a}` |"
        ok, msg = self.mod.check_r2_head_matches(text, sha_b)
        self.assertFalse(ok)
        self.assertIn("HEAD mismatch", msg)

    def test_r1_pass_when_file_exists(self):
        # We know CURRENT_STATE.md exists in repo
        ok, _ = self.mod.check_r1_current_state_exists()
        self.assertTrue(ok)

    def test_r5_pass_when_no_divergence(self):
        # Same SHA in state and origin/main -> no divergence
        sha = "0" * 40
        text = f"| **HEAD** | `{sha}` |\n| **origin/main** | `{sha}` |"
        ok, msg = self.mod.check_r5_origin_main_not_ahead(text)
        self.assertTrue(ok)

    def test_r4_blocks_fail_proven_without_exception(self):
        # Construct fake UAT status with FAIL_PROVEN
        fake = """
| `UAT-RP-099` | **FAIL_PROVEN** | `r.md` | x |
| **FAIL_PROVEN:** 1
"""
        with tempfile.NamedTemporaryFile(suffix=".md", delete=False, mode="w") as f:
            f.write(fake)
            tmp = f.name
        # Monkey-patch UAT_STATUS temporarily
        original = self.mod.UAT_STATUS
        self.mod.UAT_STATUS = pathlib.Path(tmp)
        try:
            ok, msg = self.mod.check_r4_no_unexcluded_non_covered()
            self.assertFalse(ok)
            self.assertIn("UAT-RP-099", msg)
        finally:
            self.mod.UAT_STATUS = original

    def test_r4_allows_fail_proven_with_exception(self):
        # Create an exceptions file mentioning UAT-RP-099
        fake = """
| `UAT-RP-099` | **FAIL_PROVEN** | `r.md` | x |
| **FAIL_PROVEN:** 1
"""
        with tempfile.NamedTemporaryFile(suffix=".md", delete=False, mode="w") as f:
            f.write(fake)
            tmp_uat = f.name
        with tempfile.NamedTemporaryFile(suffix=".md", delete=False, mode="w") as f:
            f.write("UAT-RP-099 — operator-approved exception pending recert\n")
            tmp_exc = f.name
        original_uat = self.mod.UAT_STATUS
        original_exc = self.mod.EXCEPTIONS_FILE
        self.mod.UAT_STATUS = pathlib.Path(tmp_uat)
        self.mod.EXCEPTIONS_FILE = pathlib.Path(tmp_exc)
        try:
            ok, msg = self.mod.check_r4_no_unexcluded_non_covered()
            self.assertTrue(ok)
        finally:
            self.mod.UAT_STATUS = original_uat
            self.mod.EXCEPTIONS_FILE = original_exc


if __name__ == "__main__":
    unittest.main(verbosity=2)
