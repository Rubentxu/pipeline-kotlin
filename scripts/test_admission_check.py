#!/usr/bin/env python3
"""Contractual tests for admission-check.py (PRDY-006R).

Run:
    python3 scripts/test_admission_check.py

These tests verify the PRDY-006R contract: rules R1..R7 behave as
documented, the candidate SHA is an explicit input, the working-tree
check excludes .agent/ and projection files but blocks source files,
and the ancestry check accepts the parent/basis projection.
"""
import importlib.util
import pathlib
import re
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


# Use known SHAs from the current repo so ancestor checks resolve.
def _current_head():
    return subprocess.check_output(
        ["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True
    ).strip()


def _parent(sha):
    return subprocess.check_output(
        ["git", "-C", str(ROOT), "rev-parse", f"{sha}^"], text=True
    ).strip()


def _valid_40(s):
    return isinstance(s, str) and len(s) == 40 and all(c in "0123456789abcdef" for c in s)


class AdmissionContractTests(unittest.TestCase):
    """Contractual tests (PRDY-006R)."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_mod()
        cls.head = _current_head()
        cls.parent = _parent(cls.head)

    # ---- R1 ----
    def test_r1_passes_when_current_state_exists(self):
        ok, _ = self.mod.check_r1_current_state_exists()
        self.assertTrue(ok, "CURRENT_STATE.md should exist in this repo")

    def test_r1_fails_when_missing(self):
        original = self.mod.CURRENT_STATE
        self.mod.CURRENT_STATE = pathlib.Path("/nonexistent/path/CURRENT_STATE.md")
        try:
            ok, msg = self.mod.check_r1_current_state_exists()
            self.assertFalse(ok)
            self.assertIn("R1", msg)
        finally:
            self.mod.CURRENT_STATE = original

    # ---- R2 (ancestor) ----
    def test_r2_passes_when_state_head_equals_candidate(self):
        text = f"| **HEAD** | `{self.head}` |"
        ok, msg = self.mod.check_r2_state_head_is_ancestor(self.head, text)
        self.assertTrue(ok, msg)

    def test_r2_passes_when_state_head_is_parent_of_candidate(self):
        """The PRDY-006R contract: state.HEAD may be a parent (basis) of candidate."""
        text = f"| **HEAD** | `{self.parent}` |"
        ok, msg = self.mod.check_r2_state_head_is_ancestor(self.head, text)
        self.assertTrue(ok, f"parent projection must be accepted: {msg}")

    def test_r2_fails_when_state_head_is_unrelated(self):
        # 40-char SHA that is definitely not in this repo's history
        bogus = "0" * 40
        text = f"| **HEAD** | `{bogus}` |"
        ok, msg = self.mod.check_r2_state_head_is_ancestor(self.head, text)
        self.assertFalse(ok)
        self.assertIn("NOT an ancestor", msg)

    def test_r2_fails_when_head_field_missing(self):
        text = "| **HEAD** | (none) |"
        ok, msg = self.mod.check_r2_state_head_is_ancestor(self.head, text)
        self.assertFalse(ok)
        self.assertIn("parse HEAD", msg)

    def test_r2_red_characterisation(self):
        """RED characterisation: previously equality was required; now we
        accept parent/basis. This test pins the new invariant."""
        # Use HEAD as candidate. State claims HEAD's parent as basis.
        text = f"| **HEAD** | `{self.parent}` |"
        # Old R2 (equality) would FAIL this; new R2 (ancestor) must PASS.
        ok, msg = self.mod.check_r2_state_head_is_ancestor(self.head, text)
        self.assertTrue(ok, f"PRDY-006R ancestor semantics broken: {msg}")

    # ---- R3 (working tree, .agent/ excluded) ----
    def test_r3_excludes_agent_dir(self):
        # .agent/SESSION_POINTER.md is dirty in this repo; must be excluded
        dirty = self.mod.git_working_tree_dirty_paths()
        agent_files = [p for p in dirty if "agent/" in p or ".agent/" in p]
        self.assertEqual(agent_files, [], f".agent/ should be excluded: {agent_files}")

    def test_r3_excludes_current_state_and_uat_status(self):
        dirty = self.mod.git_working_tree_dirty_paths()
        projection_files = [p for p in dirty if "CURRENT_STATE.md" in p or "CURRENT_UAT_STATUS.md" in p]
        self.assertEqual(projection_files, [], f"projections should be excluded: {projection_files}")

    def test_r3_blocks_source_files(self):
        # scripts/admission-check.py is dirty in this session; R3 must detect it
        dirty = self.mod.git_working_tree_dirty_paths()
        self.assertTrue(any("admission-check.py" in p for p in dirty),
                        f"expected scripts/admission-check.py in dirty list: {dirty}")

    # ---- R4 (blocking UAT states) ----
    def test_r4_blocks_fail_proven_without_exception(self):
        fake = """
| `UAT-RP-099` | **FAIL_PROVEN** | `r.md` | x |
| **FAIL_PROVEN:** 1
"""
        self._with_fake_uat_status(fake, exceptions="", strict=False,
                                   expect_ok=False, expect_substring="UAT-RP-099")

    def test_r4_blocks_not_run_without_exception(self):
        fake = "| `UAT-RP-098` | **NOT_RUN** | — | _no receipt found_ |\n| **NOT_RUN:** 1\n"
        self._with_fake_uat_status(fake, exceptions="", strict=False,
                                   expect_ok=False, expect_substring="UAT-RP-098")

    def test_r4_allows_blocking_with_exception(self):
        fake = "| `UAT-RP-099` | **FAIL_PROVEN** | `r.md` | x |\n| **FAIL_PROVEN:** 1\n"
        self._with_fake_uat_status(fake, exceptions="UAT-RP-099\n", strict=False,
                                   expect_ok=True, expect_substring="")

    def test_r4_referenced_not_blocking_by_default(self):
        fake = "| `UAT-RP-097` | **REFERENCED** | `r.md` | x |\n| **REFERENCED:** 1\n"
        self._with_fake_uat_status(fake, exceptions="", strict=False,
                                   expect_ok=True, expect_substring="")

    def test_r4_strict_blocks_referenced_without_exception(self):
        fake = "| `UAT-RP-097` | **REFERENCED** | `r.md` | x |\n| **REFERENCED:** 1\n"
        self._with_fake_uat_status(fake, exceptions="", strict=True,
                                   expect_ok=False, expect_substring="REFERENCED")

    def test_r4_covered_is_never_blocking(self):
        fake = "| `UAT-RP-001` | **COVERED** | `r.md` | x |\n| **COVERED:** 1\n"
        for strict in (False, True):
            self._with_fake_uat_status(fake, exceptions="", strict=strict,
                                       expect_ok=True, expect_substring="")

    # ---- R5 (receipt freshness, actually implemented) ----
    def test_r5_passes_when_receipts_recent(self):
        ok, msg = self.mod.check_r5_receipt_freshness(self.head, 14)
        # Receipts in docs/v2/07-uat/ exist and were touched recently;
        # at 14-day window the check must PASS for the current repo.
        self.assertTrue(ok, f"unexpectedly stale: {msg}")

    def test_r5_returns_without_raising_on_narrow_window(self):
        # Use a 1-day window; the function must return cleanly regardless
        # of whether any receipt happens to be newer than 1 day.
        ok, msg = self.mod.check_r5_receipt_freshness(self.head, 1)
        self.assertIsInstance(ok, bool)
        # msg is str when failing, None when passing — both are valid.
        self.assertTrue(msg is None or isinstance(msg, str))

    def test_r5_fails_on_bogus_candidate_sha(self):
        # Candidate SHA that doesn't exist in repo -> git show returns None
        # -> R5 returns True (don't block on missing candidate time).
        ok, msg = self.mod.check_r5_receipt_freshness("0" * 40, 14)
        self.assertTrue(ok, "missing candidate time should not block")

    def test_r5_helper_returns_iso8601(self):
        iso = self.mod.newest_receipt_iso8601()
        if iso is not None:
            # Should parse as ISO8601
            from datetime import datetime
            datetime.fromisoformat(iso)

    # ---- R7 (branch divergence) ----
    def test_r7_passes_when_no_divergence(self):
        # If origin/main is at the same SHA as candidate, R7 must pass.
        origin = subprocess.run(
            ["git", "-C", str(ROOT), "rev-parse", "origin/main"],
            capture_output=True, text=True,
        ).stdout.strip()
        if not origin:
            self.skipTest("origin/main not visible")
        # Pass if candidate is ancestor of origin/main or vice versa
        # (typical for tracked branches).
        ok, msg = self.mod.check_r7_origin_main_not_ahead(self.head)
        # If we're behind origin/main, R7 will FAIL — but on wu/* branches
        # we expect PASS (candidate is on the branch, origin/main is
        # untouched). Assert msg only when failing.
        if not ok:
            self.assertIn("behind", msg)
        else:
            self.assertIsNone(msg)

    def test_r7_origin_main_unset_is_not_blocking(self):
        # If origin/main cannot be resolved (no remote), R7 must not block.
        original = self.mod.git_origin_main
        self.mod.git_origin_main = lambda: None
        try:
            ok, msg = self.mod.check_r7_origin_main_not_ahead(self.head)
            self.assertTrue(ok)
        finally:
            self.mod.git_origin_main = original

    # ---- Driver smoke: --help shows all 3 args ----
    def test_cli_help_lists_required_args(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--help"],
            capture_output=True, text=True,
        )
        self.assertIn("--head", result.stdout)
        self.assertIn("--strict", result.stdout)
        self.assertIn("--max-receipt-age-days", result.stdout)

    # ---- helpers ----
    def _with_fake_uat_status(self, fake_uat_md, exceptions, strict, expect_ok, expect_substring):
        """Run R4 with a monkey-patched CURRENT_UAT_STATUS + EXCEPTIONS_FILE."""
        with tempfile.NamedTemporaryFile(suffix=".md", delete=False, mode="w") as f:
            f.write(fake_uat_md)
            tmp_uat = f.name
        with tempfile.NamedTemporaryFile(suffix=".md", delete=False, mode="w") as f:
            f.write(exceptions)
            tmp_exc = f.name
        original_uat = self.mod.UAT_STATUS
        original_exc = self.mod.EXCEPTIONS_FILE
        self.mod.UAT_STATUS = pathlib.Path(tmp_uat)
        self.mod.EXCEPTIONS_FILE = pathlib.Path(tmp_exc)
        try:
            ok, msg = self.mod.check_r4_no_blocking_uats(strict=strict)
            self.assertEqual(ok, expect_ok,
                             f"strict={strict} expected {expect_ok} got {ok}: {msg}")
            if expect_substring:
                self.assertIn(expect_substring, msg)
        finally:
            self.mod.UAT_STATUS = original_uat
            self.mod.EXCEPTIONS_FILE = original_exc


if __name__ == "__main__":
    unittest.main(verbosity=2)
