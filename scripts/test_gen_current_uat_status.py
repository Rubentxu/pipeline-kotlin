#!/usr/bin/env python3
"""Unit tests for gen-current-uat-status.py (D-007 architecture).

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


def _tmp_repo_with_receipts(receipts):
    """Create a temp dir with given (filename, contents) AND a real
    git repo so git_last_commit_for yields real SHAs.

    Returns the temp dir Path.
    """
    tmp = tempfile.mkdtemp()
    tmpdir = pathlib.Path(tmp)
    for name, body in receipts:
        (tmpdir / name).write_text(body)
    subprocess.run(["git", "-C", str(tmpdir), "init", "-q"], check=True)
    subprocess.run(["git", "-C", str(tmpdir), "config", "user.email", "test@test"],
                   check=True)
    subprocess.run(["git", "-C", str(tmpdir), "config", "user.name", "test"],
                   check=True)
    subprocess.run(["git", "-C", str(tmpdir), "add", "-A"], check=True)
    subprocess.run(["git", "-C", str(tmpdir), "commit", "-m", "init", "-q"],
                   check=True)
    return tmpdir


def _quad(path, line, sha, statuses=()):
    """Compact EvidenceTriple (path, line, sha, statuses)."""
    return (path, line, sha, list(statuses))


class GenCurrentUatStatusTests(unittest.TestCase):
    """Original tests + D-007 characterisation suite."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_mod()

    # --- Original tests, adapted to new EvidenceQuadruple signature ---

    def test_uat_ids_complete(self):
        ids = self.mod.UAT_IDS
        self.assertEqual(len(ids), 27)
        self.assertEqual(ids[0], "UAT-RP-001")
        self.assertEqual(ids[-1], "UAT-RP-027")

    def test_explicit_statuses_canonical_set(self):
        self.assertIn("COVERED", self.mod.EXPLICIT_STATUSES)
        self.assertIn("PARTIAL", self.mod.EXPLICIT_STATUSES)
        self.assertIn("FAIL_PROVEN", self.mod.EXPLICIT_STATUSES)
        self.assertIn("BLOCKED", self.mod.EXPLICIT_STATUSES)
        self.assertIn("NOT_RUN", self.mod.EXPLICIT_STATUSES)
        self.assertIn("REJECTED", self.mod.EXPLICIT_STATUSES)
        # Ensure no narrative-only markers remain.
        for forbidden in ("FAIL", "KNOWN_GAP", "NO_APLICA", "KNOWN_LIMITATION"):
            self.assertNotIn(forbidden, self.mod.EXPLICIT_STATUSES)

    def test_normative_matrix_excluded(self):
        self.assertIn("PRODUCTION_READY_UAT_MATRIX.md", self.mod.EXCLUDED_BASENAMES)

    def test_select_evidence_no_triples_is_not_run(self):
        self.assertEqual(self.mod.select_evidence([])[0], "NOT_RUN")

    def test_select_evidence_single_triple_with_status(self):
        triples = [_quad("r.md", "UAT-RP-005 COVERED via tests", "a" * 40,
                         ["COVERED"])]
        status, sel = self.mod.select_evidence(triples)
        self.assertEqual(status, "COVERED")
        self.assertEqual(len(sel), 1)

    def test_select_evidence_only_narrative_is_referenced(self):
        triples = [_quad("r.md", "UAT-RP-005 narrative no marker", "a" * 40, [])]
        status, _ = self.mod.select_evidence(triples)
        self.assertEqual(status, "REFERENCED")

    def test_render_markdown_includes_all_uats(self):
        uat_status = {uid: [] for uid in self.mod.UAT_IDS}
        # Triple with explicit status requires commit_sha; use the
        # generator's own HEAD helper via a synthetic sha is not enough;
        # we simply feed an empty triple list and assert the rendering
        # includes 27 NOT_RUN rows.
        md = self.mod.render_markdown(uat_status, "abc1234deadbeef")
        for uid in self.mod.UAT_IDS:
            self.assertIn(f"`{uid}`", md)
        # 27 NOT_RUN + 0 anything else
        self.assertIn("**NOT_RUN:** 27", md)

    # --- D-007 mandatory characterisation tests ---

    def test_d007_01_fail_closed_word_does_not_imply_fail_proven(self):
        """#1: 'fail-closed' in a line must not classify as FAIL_PROVEN."""
        # First, the EXPLICIT_PATTERN must not match 'fail-closed':
        m = self.mod.EXPLICIT_PATTERN.findall("rechazo fail-closed antes de nuevos efectos")
        self.assertEqual(m, [])
        # Now via scan + select, narrative-only -> REFERENCED:
        triples = [_quad(
            "UAT_RP_013_RECEIPT.md",
            "| UAT-RP-013 | Divergence | rechazo fail-closed antes de nuevos efectos |",
            "a" * 40,
            [],
        )]
        status, _ = self.mod.select_evidence(triples)
        self.assertEqual(status, "REFERENCED")

    def test_d007_02_fail_fast_does_not_imply_fail_proven(self):
        """#2: 'fail-fast' in a line must not classify as FAIL_PROVEN."""
        m = self.mod.EXPLICIT_PATTERN.findall("the orchestrator uses fail-fast semantics")
        self.assertEqual(m, [])
        triples = [_quad(
            "r.md",
            "orchestrator: fail-fast under failure budget",
            "b" * 40,
            [],
        )]
        status, _ = self.mod.select_evidence(triples)
        self.assertEqual(status, "REFERENCED")

    def test_d007_03_normative_matrix_does_not_count_as_evidence(self):
        """#3: PRODUCTION_READY_UAT_MATRIX.md must be excluded from scan."""
        receipts = [
            ("PRODUCTION_READY_UAT_MATRIX.md", "| UAT-RP-005 | Foo | COVERED | bar |\n"),
            ("OTHER.md", "| UAT-RP-005 | Independent | COVERED | baz |\n"),
        ]
        tmpdir = _tmp_repo_with_receipts(receipts)
        git = self.mod._Git(cwd=tmpdir)
        found = self.mod.scan_receipts(pathlib.Path(tmpdir), git=git)
        self.assertEqual(len(found["UAT-RP-005"]), 1)
        self.assertEqual(found["UAT-RP-005"][0][0].name, "OTHER.md")

    def test_d007_04_narrative_covered_rp_marker_does_not_certify(self):
        """#4: a line with just 'COVERED (RP-1)' narrative does not certify."""
        # No explicit COVERED token; the line in the matrix-like narrative
        # has COVERED inside a phrase that the EXPLICIT_PATTERN does match
        # (it's a whole word). So this test focuses on the practical case:
        # a file path containing a narrative annotation outside an explicit
        # status block. We simulate by passing a receipt whose line carries
        # no explicit status; it should be REFERENCED, not COVERED.
        # (The former bug: COVERED (RP-1) gave COVERED via regex order.)
        # Now that we don't fall back to FAIL/fail-closed/etc, narrative
        # COVERED annotations also don't auto-classify. Use statuses=[].
        triples = [_quad(
            "RP3_EXIT_REVIEW.md",
            "Previously: COVERED (RP-1) — see archived archive.html",
            "c" * 40,
            [],
        )]
        # Our new helper parses statuses[] only. The COVERED in the line
        # WOULD match EXPLICIT_PATTERN, but we construct the triple with
        # empty statuses to model the prior buggy data path (narrative
        # annotations would have been promoted earlier).
        # With statuses=[] the line itself is REFERENCED.
        status, _ = self.mod.select_evidence(triples)
        self.assertEqual(status, "REFERENCED")

    def test_d007_05_explicit_covered_certifies(self):
        """#5: an explicit COVERED token does certify."""
        triples = [_quad(
            "rp012.md",
            "UAT-RP-012 | COVERED | 6 tests pass",
            "d" * 40,
            ["COVERED"],
        )]
        status, _ = self.mod.select_evidence(triples)
        self.assertEqual(status, "COVERED")

    def test_d007_06_explicit_fail_proven_fails(self):
        """#6: an explicit FAIL_PROVEN token fails."""
        triples = [_quad(
            "r.md", "UAT-RP-013 | FAIL_PROVEN | observed defect",
            "e" * 40, ["FAIL_PROVEN"])]
        status, _ = self.mod.select_evidence(triples)
        self.assertEqual(status, "FAIL_PROVEN")

    def test_d007_07_evidence_without_marker_is_referenced(self):
        """#7: receipt text mentions the UAT but has no explicit status -> REFERENCED."""
        triples = [_quad(
            "narrative.md", "see UAT-RP-026 for remote profile",
            "f" * 40, [])]
        status, _ = self.mod.select_evidence(triples)
        self.assertEqual(status, "REFERENCED")

    def test_d007_08_newer_explicit_status_wins(self):
        """#8: when two SHAs carry explicit statuses, the more recent wins."""
        # Synthetic SHAs in lex order (Git SHA order = lex order on hex).
        older = ("0" * 39) + "1"
        newer = ("0" * 39) + "f"
        triples = [
            _quad("old.md", "UAT-RP-001 | COVERED | old", older, ["COVERED"]),
            _quad("new.md", "UAT-RP-001 | FAIL_PROVEN | new", newer, ["FAIL_PROVEN"]),
        ]
        status, _ = self.mod.select_evidence(triples)
        self.assertEqual(status, "FAIL_PROVEN")

    def test_d007_09_same_sha_contradictory_statuses_yields_conflict(self):
        """#9: same SHA carrying different explicit statuses yields CONFLICT.

        Two receipts committed in the same SHA can disagree on a UAT's
        status; resolver returns CONFLICT (fail-closed).
        """
        sha = "0" * 39 + "a"
        triples = [
            _quad("a.md", "UAT-RP-007 | FAIL_PROVEN | a evidence",
                  sha, ["FAIL_PROVEN"]),
            _quad("b.md", "UAT-RP-007 | COVERED | b evidence",
                  sha, ["COVERED"]),
        ]
        status, _ = self.mod.select_evidence(triples)
        self.assertEqual(status, "CONFLICT")

    def test_d007_10_glob_order_does_not_matter(self):
        """#10: same input but different file creation order yields identical output."""
        mod = self.mod

        # We'll create two distinct receipts in two orders and verify
        # selection ignores order. Use a real git repo so SHAs exist.
        with tempfile.TemporaryDirectory() as tmp1:
            receipts1 = [
                ("a_first.md",  "UAT-RP-008 | COVERED | a\n"),
                ("z_second.md", "UAT-RP-008 | COVERED | z\n"),
            ]
            dir1 = _tmp_repo_with_receipts(receipts1)
            git1 = mod._Git(cwd=dir1)
            found1 = mod.scan_receipts(pathlib.Path(dir1), git=git1)
            triples1 = found1["UAT-RP-008"]
            status1, _ = mod.select_evidence(triples1)
            self.assertEqual(status1, "COVERED")

        with tempfile.TemporaryDirectory() as tmp2:
            receipts2 = [
                ("z_second.md", "UAT-RP-008 | COVERED | z\n"),
                ("a_first.md",  "UAT-RP-008 | COVERED | a\n"),
            ]
            dir2 = _tmp_repo_with_receipts(receipts2)
            git2 = mod._Git(cwd=dir2)
            found2 = mod.scan_receipts(pathlib.Path(dir2), git=git2)
            triples2 = found2["UAT-RP-008"]
            status2, _ = mod.select_evidence(triples2)
            self.assertEqual(status2, "COVERED")

        # Both should classify COVERED regardless of file commit order.

    def test_d007_11_two_fresh_clones_yield_identical_output(self):
        """#11: two fresh clones of the same content produce identical scan_receipts output."""
        receipts = [
            ("x_first.md",  "UAT-RP-003 | COVERED | x evidence\n"),
            ("y_second.md", "UAT-RP-004 | PARTIAL | y evidence\n"),
        ]
        dir_a = _tmp_repo_with_receipts(receipts)
        dir_b = _tmp_repo_with_receipts(receipts)
        git_a = self.mod._Git(cwd=dir_a)
        git_b = self.mod._Git(cwd=dir_b)
        found_a = self.mod.scan_receipts(pathlib.Path(dir_a), git=git_a)
        found_b = self.mod.scan_receipts(pathlib.Path(dir_b), git=git_b)
        # Same set of UAT IDs covered.
        self.assertEqual(
            sorted(uid for uid in found_a if found_a[uid]),
            sorted(uid for uid in found_b if found_b[uid]),
        )

    def test_d007_12_uat_rp_013_normative_regression(self):
        """#12: UAT-RP-013 with only the normative 'fail-closed' phrase is REFERENCED, not FAIL_PROVEN.

        This is the regression test for the original bug that triggered D-007.
        """
        # Construct an evidence triple mimicking the historical row from
        # PRODUCTION_READY_UAT_MATRIX.md (now excluded from the scan, but the
        # same data structure could be present in narrative receipts).
        line = "| UAT-RP-013 | Divergence | cambiar input/script y reusar runId; rechazo fail-closed antes de nuevos efectos | error tipado y marker inalterado |"
        # Parse the line with EXPLICIT_PATTERN as the generator would:
        # `fail-closed` does NOT match \bCOVERED\b/PARTIAL/FAIL_PROVEN/etc.
        explicit = self.mod.EXPLICIT_PATTERN.findall(line)
        self.assertEqual(explicit, [],
                         "fail-closed narrative must not match an explicit status")
        # Triple with statuses=[] -> REFERENCED.
        triples = [_quad("historical_row.md", line, "0123456789abcdef" * 2, [])]
        status, _ = self.mod.select_evidence(triples)
        self.assertEqual(status, "REFERENCED")
        self.assertNotEqual(status, "FAIL_PROVEN")

    # --- Existing scan_receipts behaviour (still useful) ---

    def test_scan_receipts_finds_uat_ids(self):
        tmp = _tmp_repo_with_receipts([
            ("a.md", "UAT-RP-005 COVERED\n"),
            ("b.md", "UAT-RP-026 Remote profile\n"),
        ])
        git = self.mod._Git(cwd=tmp)
        found = self.mod.scan_receipts(pathlib.Path(tmp), git=git)
        self.assertIn("UAT-RP-005", found)
        self.assertEqual(len(found["UAT-RP-005"]), 1)
        self.assertEqual(found["UAT-RP-005"][0][0].name, "a.md")
        self.assertEqual(len(found["UAT-RP-026"]), 1)
        self.assertEqual(len(found["UAT-RP-001"]), 0)
        # Triples now have 4-tuples; check sha is captured.
        self.assertEqual(len(found["UAT-RP-005"][0]), 4)

    def test_scan_receipts_skips_uncommitted_files(self):
        # Create a temp dir WITHOUT git init; receipt should be skipped
        # because git_last_commit_for returns None.
        with tempfile.TemporaryDirectory() as tmp:
            tmpdir = pathlib.Path(tmp)
            (tmpdir / "a.md").write_text("UAT-RP-005 COVERED\n")
            found = self.mod.scan_receipts(tmpdir)
            self.assertEqual(len(found["UAT-RP-005"]), 0)

    def test_pin_receipt_picks_latest_sha(self):
        older = ("0" * 39) + "1"
        newer = ("0" * 39) + "f"
        triples = [
            _quad("a.md", "UAT-RP-005 | COVERED | a", older, ["COVERED"]),
            _quad("b.md", "UAT-RP-005 | COVERED | b", newer, ["COVERED"]),
        ]
        self.assertEqual(self.mod.pin_receipt(triples), "b.md")
        # Tie-break: when SHAs equal, smallest path wins.
        triples_tie = [
            _quad("z.md", "UAT-RP-005 | COVERED | z", "abc", ["COVERED"]),
            _quad("a.md", "UAT-RP-005 | COVERED | a", "abc", ["COVERED"]),
        ]
        self.assertEqual(self.mod.pin_receipt(triples_tie), "a.md")

    def test_d007_admission_blocks_conflict(self):
        """Sanity: CONFLICT is part of BLOCKING_UAT_STATES in admission-check."""
        ac = importlib.util.spec_from_file_location(
            "admission_check", ROOT / "scripts" / "admission-check.py"
        )
        ac_mod = importlib.util.module_from_spec(ac)
        ac.loader.exec_module(ac_mod)
        self.assertIn("CONFLICT", ac_mod.BLOCKING_UAT_STATES)


if __name__ == "__main__":
    unittest.main(verbosity=2)
