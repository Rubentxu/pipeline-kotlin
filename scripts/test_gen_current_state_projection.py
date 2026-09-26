#!/usr/bin/env python3
"""Tests for gen-current-state-projection.py (PR-001 acceptance).

Authority: AGENTS.md §"V2 testing rules" (unit tests, TDD).
These tests verify the acceptance criteria from PR-ADR-001:
  - same repository state => byte-identical structural output
  - stale SHA detected when working-tree state changes
  - missing input handled gracefully (FAIL-LOUD, not silent)

Tests use isolated /tmp worktrees with mocked git/gh to avoid network
or live-repo dependence.
"""

import importlib.util
import io
import os
import pathlib
import sys
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "gen-current-state-projection.py"

# Load the module ONCE at test-file scope so @mock.patch.object decorators
# can find it via sys.modules.
_spec = importlib.util.spec_from_file_location("gen_current_state_projection",
                                               str(SCRIPT))
_mod = importlib.util.module_from_spec(_spec)
sys.modules["gen_current_state_projection"] = _mod
_spec.loader.exec_module(_mod)
MOD = _mod


def _run_with_args(mod, argv):
    """Invoke mod.main(argv) capturing stdout/stderr."""
    out = io.StringIO()
    err = io.StringIO()
    saved_argv = sys.argv[:]
    sys.argv = [str(SCRIPT)] + argv
    try:
        with redirect_stdout(out), redirect_stderr(err):
            try:
                rc = mod.main()
            except SystemExit as exc:
                rc = exc.code
    finally:
        sys.argv = saved_argv
    return rc, out.getvalue(), err.getvalue()


def _patches():
    """Return decorators to mock all external dependencies of the generator."""
    patches = [
        mock.patch.object(MOD, "gh_releases"),
        mock.patch.object(MOD, "gh_open_prs"),
        mock.patch.object(MOD, "gh_harness_issues"),
        mock.patch.object(MOD, "receipt_count"),
        mock.patch.object(MOD, "tech_debt_state"),
        mock.patch.object(MOD, "git_ahead_behind"),
        mock.patch.object(MOD, "git_dirty"),
        mock.patch.object(MOD, "git_origin_branch"),
        mock.patch.object(MOD, "git_origin_main"),
        mock.patch.object(MOD, "git_branch"),
        mock.patch.object(MOD, "git_head"),
    ]
    # Apply all and return the mocks (in reverse decorator order: deepest first)
    mocks = [p.start() for p in patches]
    return patches, mocks


def _stop_patches(patches):
    for p in patches:
        p.stop()


class DeterminismTests(unittest.TestCase):
    """PR-001 acceptance: same inputs => byte-identical structural output."""

    def setUp(self):
        self.tmp_out = tempfile.NamedTemporaryFile(
            mode="w", suffix=".md", delete=False
        )
        self.tmp_out.close()
        self.out_path = self.tmp_out.name

    def tearDown(self):
        if os.path.exists(self.out_path):
            os.unlink(self.out_path)

    def test_same_inputs_byte_identical_structural(self):
        """Generate twice with identical inputs; check OK-STRUCTURAL."""
        patches, mocks = _patches()
        try:
            (m_releases, m_prs, m_harness, m_rc, m_debt, m_ahead,
             m_dirty, m_obranch, m_omain, m_branch, m_head) = mocks

            m_head.return_value = "f79da219abd69c293f37cc14f9fcfa8561f942b2"
            m_branch.return_value = "wu/rp-053r-red-fixtures"
            m_omain.return_value = "acc903875d70f939713786d71a6331bb6ccf7dc9"
            m_obranch.return_value = "f79da219abd69c293f37cc14f9fcfa8561f942b2"
            m_dirty.return_value = []
            m_ahead.side_effect = RuntimeError("no remote")
            m_debt.return_value = {"active": [], "closed": ["001", "002"], "reserved": ["005"]}
            m_rc.return_value = (272, 108)
            m_harness.return_value = [
                {"number": 3, "title": "Candidate handoff: pipelinek v0.40.0-rc1",
                 "state": "OPEN", "createdAt": "2026-09-26T08:38:25Z"}
            ]
            m_prs.return_value = []
            m_releases.return_value = [
                {"tagName": "v0.40.0-rc1", "name": "rc1", "isPrerelease": True,
                 "publishedAt": "2026-09-26T08:37:59Z"}
            ]

            # First generation: write the file
            rc1, _, _ = _run_with_args(MOD, ["--out", self.out_path])
            self.assertEqual(rc1, 0, "first generation should succeed")

            # Second generation: check (no rewrite)
            rc2, out2, _ = _run_with_args(MOD, ["--out", self.out_path, "--check"])
            self.assertEqual(rc2, 0, f"check should pass; got rc={rc2} out={out2}")
            self.assertIn("OK", out2, f"expected OK; got {out2}")
        finally:
            _stop_patches(patches)

    def test_stale_working_tree_detected(self):
        """Working-tree change between runs is detected by --check."""
        patches, mocks = _patches()
        try:
            (m_releases, m_prs, m_harness, m_rc, m_debt, m_ahead,
             m_dirty, m_obranch, m_omain, m_branch, m_head) = mocks

            m_debt.return_value = {"active": [], "closed": [], "reserved": []}
            m_rc.return_value = (0, 0)
            m_harness.return_value = []
            m_prs.return_value = []
            m_releases.return_value = []
            m_head.return_value = "f79da219abd69c293f37cc14f9fcfa8561f942b2"
            m_branch.return_value = "main"
            m_omain.return_value = "f79da219abd69c293f37cc14f9fcfa8561f942b2"
            m_obranch.return_value = "f79da219abd69c293f37cc14f9fcfa8561f942b2"
            m_ahead.side_effect = RuntimeError("no remote")

            # Run 1: clean tree
            m_dirty.return_value = []
            rc1, _, _ = _run_with_args(MOD, ["--out", self.out_path])
            self.assertEqual(rc1, 0)

            # Run 2: tree now has a modified file
            m_dirty.return_value = [" M v2/pipeline-application/.../CoreEchoStep.kt"]
            rc2, out2, _ = _run_with_args(MOD, ["--out", self.out_path, "--check"])
            self.assertEqual(rc2, 1, "stale check must exit 1")
            self.assertIn("STALE", out2, f"expected STALE; got {out2}")
        finally:
            _stop_patches(patches)

    def test_missing_output_returns_code_2(self):
        """--check on missing output returns exit code 2 (MISSING)."""
        patches, mocks = _patches()
        try:
            (m_releases, m_prs, m_harness, m_rc, m_debt, m_ahead,
             m_dirty, m_obranch, m_omain, m_branch, m_head) = mocks

            missing_path = self.out_path + ".missing"
            m_head.return_value = "f79da219"
            m_branch.return_value = "main"
            m_omain.return_value = "f79da219"
            m_obranch.return_value = "f79da219"
            m_dirty.return_value = []
            m_ahead.side_effect = RuntimeError("no remote")
            m_debt.return_value = {"active": [], "closed": [], "reserved": []}
            m_rc.return_value = (0, 0)
            m_harness.return_value = []
            m_prs.return_value = []
            m_releases.return_value = []

            rc, out, _ = _run_with_args(MOD, ["--out", missing_path, "--check"])
            self.assertEqual(rc, 2, f"missing file should exit 2; got rc={rc}")
            self.assertIn("MISSING", out)
        finally:
            _stop_patches(patches)

    def test_next_wu_derives_from_harness_intake(self):
        """When harness has open intake issues, next WU = await verdict."""
        patches, mocks = _patches()
        try:
            (m_releases, m_prs, m_harness, m_rc, m_debt, m_ahead,
             m_dirty, m_obranch, m_omain, m_branch, m_head) = mocks

            m_debt.return_value = {"active": [], "closed": [], "reserved": []}
            m_rc.return_value = (0, 0)
            m_prs.return_value = []
            m_releases.return_value = []
            m_head.return_value = "f79da219"
            m_branch.return_value = "main"
            m_omain.return_value = "f79da219"
            m_obranch.return_value = "f79da219"
            m_dirty.return_value = []
            m_ahead.side_effect = RuntimeError("no remote")
            m_harness.return_value = [
                {"number": 3, "title": "Candidate handoff",
                 "state": "OPEN", "createdAt": "2026-09-26T08:38:25Z"}
            ]

            state_collect = {
                "generated_at": "test", "generator_sha": "test",
                "head": "x", "branch": "main", "origin_main": "x",
                "origin_branch": "x", "ahead": 0, "behind": 0,
                "dirty": [], "stable_release": None, "latest_prerelease": None,
                "open_prs": [], "harness_issues": m_harness.return_value,
                "receipt_count_total": 0, "receipt_count_week": 0,
                "tech_debt": {"active": [], "closed": [], "reserved": []},
                "output_sha": None,
            }
            nxt = MOD.derive_next_wu(state_collect)
            self.assertEqual(nxt["id"], "WAIT-FOR-HARNESS-VERDICT")
            self.assertIn("harness", nxt["source"].lower())
        finally:
            _stop_patches(patches)

    def test_strict_mode_fails_on_conflicting_candidate(self):
        """--strict flags a harness issue that references a different tag."""
        patches, mocks = _patches()
        try:
            (m_releases, m_prs, m_harness, m_rc, m_debt, m_ahead,
             m_dirty, m_obranch, m_omain, m_branch, m_head) = mocks

            m_debt.return_value = {"active": [], "closed": [], "reserved": []}
            m_rc.return_value = (0, 0)
            m_prs.return_value = []
            m_releases.return_value = [
                {"tagName": "v0.40.0-rc1", "name": "rc1", "isPrerelease": True,
                 "publishedAt": "2026-09-26T08:37:59Z"}
            ]
            m_head.return_value = "f79da219"
            m_branch.return_value = "main"
            m_omain.return_value = "f79da219"
            m_obranch.return_value = "f79da219"
            m_dirty.return_value = []
            m_ahead.side_effect = RuntimeError("no remote")
            # Harness issue references v0.39.1-rc4 but latest prerelease is rc1
            m_harness.return_value = [
                {"number": 99, "title": "Candidate handoff: pipelinek v0.39.1-rc4",
                 "state": "OPEN", "createdAt": "2026-09-25T00:00:00Z"}
            ]

            rc, _, err = _run_with_args(
                MOD, ["--out", self.out_path, "--strict"]
            )
            self.assertEqual(rc, 1, f"strict should fail-loud; got rc={rc}")
            self.assertIn("FAIL-LOUD", err)
            self.assertIn("v0.39.1-rc4", err)
            self.assertIn("v0.40.0-rc1", err)
        finally:
            _stop_patches(patches)


if __name__ == "__main__":
    unittest.main(verbosity=2)
