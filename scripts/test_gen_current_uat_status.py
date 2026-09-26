#!/usr/bin/env python3
"""Unit tests for gen-current-uat-status.py.

Run:
    python3 scripts/test_gen_current_uat_status.py
"""
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "gen-current-uat-status.py"


def _load_mod():
    spec = importlib.util.spec_from_file_location("gen_current_uat_status", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class GenCurrentUatStatusTests(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_mod()

    def test_uat_ids_complete(self):
        ids = self.mod.UAT_IDS
        self.assertEqual(len(ids), 27)
        self.assertEqual(ids[0], "UAT-RP-001")
        self.assertEqual(ids[-1], "UAT-RP-027")

    def test_classify_status_covered(self):
        matches = [(pathlib.Path("r.md"), "UAT-RP-005 COVERED via rp010 tests")]
        self.assertEqual(self.mod.classify_status(matches), "COVERED")

    def test_classify_status_partial(self):
        matches = [(pathlib.Path("r.md"), "UAT-RP-005 PARTIAL inv 3 KNOWN_LIMITATION")]
        self.assertEqual(self.mod.classify_status(matches), "PARTIAL")

    def test_classify_status_fail_proven(self):
        matches = [(pathlib.Path("r.md"), "UAT-RP-005 FAIL_PROVEN at production")]
        self.assertEqual(self.mod.classify_status(matches), "FAIL_PROVEN")

    def test_classify_status_not_run(self):
        matches = []
        self.assertEqual(self.mod.classify_status(matches), "NOT_RUN")

    def test_classify_status_referenced_when_only_narrative(self):
        matches = [(pathlib.Path("r.md"), "UAT-RP-026 Remote (RP-8) lease/fencing")]
        # No status marker in narrative line -> REFERENCED
        self.assertEqual(self.mod.classify_status(matches), "REFERENCED")

    def test_scan_receipts_finds_uat_ids(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmpdir = pathlib.Path(tmp)
            (tmpdir / "a.md").write_text("UAT-RP-005 COVERED\n")
            (tmpdir / "b.md").write_text("UAT-RP-026 Remote profile\n")
            found = self.mod.scan_receipts(tmpdir)
            self.assertIn("UAT-RP-005", found)
            self.assertEqual(len(found["UAT-RP-005"]), 1)
            self.assertEqual(found["UAT-RP-005"][0][0].name, "a.md")
            self.assertEqual(len(found["UAT-RP-026"]), 1)
            self.assertEqual(len(found["UAT-RP-001"]), 0)

    def test_scan_receipts_handles_missing_dir(self):
        with tempfile.TemporaryDirectory() as tmp:
            missing = pathlib.Path(tmp) / "does_not_exist"
            found = self.mod.scan_receipts(missing)
            self.assertEqual(len(found), 27)
            self.assertEqual(len(found["UAT-RP-001"]), 0)

    def test_latest_receipt_returns_path(self):
        matches = [(pathlib.Path("docs/r.md"), "x")]
        self.assertEqual(self.mod.latest_receipt(matches), "docs/r.md")
        self.assertIsNone(self.mod.latest_receipt([]))

    def test_render_markdown_includes_all_uats(self):
        uat_status = {uid: [] for uid in self.mod.UAT_IDS}
        uat_status["UAT-RP-005"] = [(pathlib.Path("rp010.md"), "UAT-RP-005 PARTIAL")]
        md = self.mod.render_markdown(uat_status, "abc1234")
        for uid in self.mod.UAT_IDS:
            self.assertIn(f"`{uid}`", md)
        self.assertIn("**PARTIAL:** 1", md)
        self.assertIn("**NOT_RUN:** 26", md)
        self.assertIn("`abc1234`", md)


if __name__ == "__main__":
    unittest.main(verbosity=2)
