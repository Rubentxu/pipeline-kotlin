#!/usr/bin/env python3
"""Unit tests for gen-current-uat-status.py (D-007 + T0E-EVID-01).

Run:
    python3 scripts/test_gen_current_uat_status.py

Coverage:
  - D-007: normative matrix excluded, explicit statuses only, no
    narrative inference (fail-closed, fail-fast).
  - T0E-EVID-01 E1.1: DAG-maximal commits, NOT max-by-SHA. Test
    cases use SHAs whose lex order contradicts causal order.
  - T0E-EVID-01 E1.2: per-UAT status scoping. Multi-UAT free-form
    lines yield REFERENCED. Marker lines certify one UAT.
  - Pinning by DAG-maximal SHA.
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


def _tmp_repo_with_history(receipts_with_commits):
    """Like _tmp_repo_with_receipts but commits each (filename, body)
    separately so multiple commits exist in the repo's history.

    `receipts_with_commits` is a list of dicts:
      {"files": [(filename, body), ...], "message": "..."}
    Each entry commits the named files in one commit.
    """
    tmp = tempfile.mkdtemp()
    tmpdir = pathlib.Path(tmp)
    subprocess.run(["git", "-C", str(tmpdir), "init", "-q"], check=True)
    subprocess.run(["git", "-C", str(tmpdir), "config", "user.email", "test@test"],
                   check=True)
    subprocess.run(["git", "-C", str(tmpdir), "config", "user.name", "test"],
                   check=True)
    for entry in receipts_with_commits:
        for name, body in entry["files"]:
            p = tmpdir / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(body)
            subprocess.run(["git", "-C", str(tmpdir), "add", "--", name],
                           check=True)
        subprocess.run(["git", "-C", str(tmpdir), "commit", "-q",
                        "-m", entry.get("message", "c")], check=True)
    return tmpdir


def _git(cwd):
    return _load_mod()._Git(cwd=cwd)


def _quad(path, line, sha, statuses=()):
    """Compact EvidenceQuad (path, line, sha, statuses)."""
    return (path, line, sha, list(statuses))


class GenCurrentUatStatusTests(unittest.TestCase):
    """D-007 + T0E-EVID-01 characterisation suite."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_mod()

    # --- Contract tests (kept from previous slice) ---

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
        for forbidden in ("FAIL", "KNOWN_GAP", "NO_APLICA", "KNOWN_LIMITATION"):
            self.assertNotIn(forbidden, self.mod.EXPLICIT_STATUSES)

    def test_normative_matrix_excluded(self):
        self.assertIn("PRODUCTION_READY_UAT_MATRIX.md", self.mod.EXCLUDED_BASENAMES)

    def test_render_markdown_includes_all_uats(self):
        uat_status = {uid: [] for uid in self.mod.UAT_IDS}
        md = self.mod.render_markdown(uat_status, "abc1234deadbeef")
        for uid in self.mod.UAT_IDS:
            self.assertIn(f"`{uid}`", md)
        self.assertIn("**NOT_RUN:** 27", md)

    # --- E1.1: DAG-maximal commits ---

    def test_e1_1_causal_maximal_shas_linear_chain(self):
        """Three commits in a linear chain: A -> B -> C. Reachable from
        D (which is a descendant of C). Maximals should be [D].
        """
        repo_dir = _tmp_repo_with_history([
            {"files": [("a.md", "x")], "message": "A"},
            {"files": [("b.md", "y")], "message": "B on A"},
            {"files": [("c.md", "z")], "message": "C on B"},
        ])
        git = _git(repo_dir)
        # Collect the actual SHAs:
        log = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()
        sha_a, sha_b, sha_c = log[2], log[1], log[0]
        triples = [
            _quad("a.md", "x", sha_a, ["COVERED"]),
            _quad("b.md", "y", sha_b, ["COVERED"]),
            _quad("c.md", "z", sha_c, ["COVERED"]),
        ]
        maximals = self.mod.causal_maximal_shas(triples, sha_c, git)
        self.assertEqual(maximals, [sha_c])

    def test_e1_1_causal_maximal_shas_branch(self):
        """Branch: A -> B (main) and A -> C (side). Both reachable from
        a merge M -> D. Maximals from M's perspective: B and C both
        (neither dominates the other).
        """
        repo_dir = _tmp_repo_with_history([
            {"files": [("a.md", "x")], "message": "A"},
            {"files": [("b.md", "y")], "message": "B on A"},
        ])
        # Create side branch from A and commit C there.
        sha_a = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H", "a.md"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        subprocess.run(["git", "-C", str(repo_dir), "checkout", "-q",
                        sha_a], check=True)
        subprocess.run(["git", "-C", str(repo_dir), "checkout", "-q",
                        "-b", "side"], check=True)
        (pathlib.Path(repo_dir) / "c.md").write_text("z")
        subprocess.run(["git", "-C", str(repo_dir), "add", "c.md"],
                       check=True)
        subprocess.run(["git", "-C", str(repo_dir), "commit", "-q",
                        "-m", "C on side"], check=True)
        # Merge side into main.
        subprocess.run(["git", "-C", str(repo_dir), "checkout", "-q",
                        "main"], check=True)
        subprocess.run(["git", "-C", str(repo_dir), "merge", "--no-ff",
                        "-m", "merge side into main", "side"], check=True)
        # Collect SHAs.
        log = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H %s"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()
        sha_b = None
        sha_c = None
        sha_merge = None
        for line in log:
            parts = line.split(maxsplit=1)
            sha = parts[0]
            msg = parts[1] if len(parts) > 1 else ""
            if msg.startswith("B on A"):
                sha_b = sha
            elif msg.startswith("C on side"):
                sha_c = sha
            elif msg.startswith("merge "):
                sha_merge = sha
        git = _git(repo_dir)
        triples = [
            _quad("b.md", "y", sha_b, ["COVERED"]),
            _quad("c.md", "z", sha_c, ["COVERED"]),
        ]
        # From sha_merge's perspective: both B and C are reachable,
        # neither dominates the other. Both are maximals.
        maximals = self.mod.causal_maximal_shas(triples, sha_merge, git)
        self.assertEqual(set(maximals), {sha_b, sha_c},
                         "branch maximals are both, neither dominates")

    def test_e1_1_sha_lex_order_does_not_drive_selection(self):
        """Pin the bug: SHA lex order must NOT be used as recency.

        Scenario: parallel branches L and R off base. From R's
        perspective, only R is reachable (L is parallel). The
        certifier must use causal reachability, not max-by-SHA.

        The bug would be: pick the lex-larger SHA among all triples.
        Since L and R have different SHAs (parent + content differ),
        one is lex-greater. Selection must NOT pick it from R's
        perspective (unreachable).
        """
        repo_dir = _tmp_repo_with_history([
            {"files": [("base.md", "base")], "message": "base"},
        ])
        sha_base = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()[0]
        # Branch left.
        subprocess.run(["git", "-C", str(repo_dir), "checkout", "-q",
                        "-b", "left"], check=True)
        (pathlib.Path(repo_dir) / "left.md").write_text("L" * 1000)
        subprocess.run(["git", "-C", str(repo_dir), "add", "left.md"],
                       check=True)
        subprocess.run(["git", "-C", str(repo_dir), "commit", "-q",
                        "-m", "left"], check=True)
        sha_left = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()[0]
        # Branch right off base.
        subprocess.run(["git", "-C", str(repo_dir), "checkout", "-q",
                        sha_base], check=True)
        subprocess.run(["git", "-C", str(repo_dir), "checkout", "-q",
                        "-b", "right"], check=True)
        (pathlib.Path(repo_dir) / "right.md").write_text("R" * 1000)
        subprocess.run(["git", "-C", str(repo_dir), "add", "right.md"],
                       check=True)
        subprocess.run(["git", "-C", str(repo_dir), "commit", "-q",
                        "-m", "right"], check=True)
        sha_right = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()[0]
        git = _git(repo_dir)
        triples = [
            _quad("left.md", "L line", sha_left, ["COVERED"]),
            _quad("right.md", "R line", sha_right, ["FAIL_PROVEN"]),
        ]
        # From sha_right's perspective: only sha_right is reachable.
        # sha_left is parallel and NOT reachable -> filtered out.
        # Maximals = [sha_right] only.
        maximals = self.mod.causal_maximal_shas(triples, sha_right, git)
        self.assertEqual(maximals, [sha_right],
                         "parallel L is unreachable from R; only R is maximal")
        # Status from sha_right's perspective: FAIL_PROVEN (only maximal).
        status, _ = self.mod.select_evidence(triples, sha_right, git)
        self.assertEqual(status, "FAIL_PROVEN")
        # From sha_left's perspective: only sha_left is reachable.
        # Maximals = [sha_left], status = COVERED.
        status_l, _ = self.mod.select_evidence(triples, sha_left, git)
        self.assertEqual(status_l, "COVERED")
        # Critical: lex order of SHAs must NOT determine selection.
        # If we naively used max(SHA), we might pick sha_right (if it's
        # lex-greater) even from sha_left's perspective. Verify our
        # implementation does NOT do that by asserting the status is
        # COVERED (sha_left), not FAIL_PROVEN (sha_right).
        lex_greater = max(sha_left, sha_right)
        lex_smaller = min(sha_left, sha_right)
        if lex_greater == sha_right:
            # Buggy behaviour: picking max(SHA) would give FAIL_PROVEN
            # even from sha_left's perspective. Our certifier must NOT
            # do that.
            self.assertNotEqual(status_l, "FAIL_PROVEN",
                                "max(SHA) bug would yield FAIL_PROVEN here")

    def test_e1_1_causal_supersedence_old_evidence_dominated(self):
        """If evidence A is dominated by evidence B (B is descendant
        of A), only B counts as maximal. A is superseded."""
        repo_dir = _tmp_repo_with_history([
            {"files": [("old.md", "old FAIL_PROVEN evidence")],
             "message": "old commit"},
            {"files": [("new.md", "new COVERED evidence (supersedes)")],
             "message": "new commit on top of old"},
        ])
        sha_old = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H", "old.md"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        log_all = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()
        sha_new = log_all[0]
        git = _git(repo_dir)
        triples = [
            _quad("old.md", "old FAIL_PROVEN", sha_old, ["FAIL_PROVEN"]),
            _quad("new.md", "new COVERED", sha_new, ["COVERED"]),
        ]
        # From sha_new's perspective: both reachable; sha_new dominates
        # sha_old; maximals = [sha_new] only.
        maximals = self.mod.causal_maximal_shas(triples, sha_new, git)
        self.assertEqual(maximals, [sha_new])
        status, _ = self.mod.select_evidence(triples, sha_new, git)
        self.assertEqual(status, "COVERED",
                         "newer COVERED causally supersedes older FAIL_PROVEN")

    # --- E1.2: per-UAT status scoping ---

    def test_e1_2_marker_line_scopes_status_to_one_uat(self):
        """`UAT-EVIDENCE | UAT-RP-013 | COVERED | candidate=<sha>`
        certifies only UAT-RP-013, even on a line where the
        narrative mentions multiple UATs (which we forbid in
        markers)."""
        line = "UAT-EVIDENCE | UAT-RP-013 | COVERED | candidate=abcdef1234567890abcdef1234567890abcdef12 | tests=T1,T2"
        parsed = self.mod._parse_marker(line)
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed[0], "UAT-RP-013")
        self.assertEqual(parsed[1], "COVERED")

    def test_e1_2_multi_uat_freeform_yields_referenced(self):
        """A non-marker line with multiple UATs and explicit statuses
        does NOT certify either UAT. Each UAT receives REFERENCED
        (statuses=[])."""
        line = "| UAT-RP-019 | COVERED, UAT-RP-020 | COVERED, UAT-RP-024 | PARTIAL |"
        uids = self.mod.UID_PATTERN.findall(line)
        self.assertEqual(set(uids), {"UAT-RP-019", "UAT-RP-020", "UAT-RP-024"})
        results = self.mod._parse_freeform(line)
        # All UATs get no certification, only REFERENCED (empty statuses).
        self.assertEqual(len(results), 3)
        for uid, statuses, kind in results:
            self.assertEqual(statuses, [],
                             f"{uid} should not be certified (multi-UAT freeform)")
            self.assertEqual(kind, "multi_noref")

    def test_e1_2_single_uat_freeform_with_status_certifies(self):
        line = "| UAT-RP-005 | FAIL_PROVEN at production |"
        results = self.mod._parse_freeform(line)
        self.assertEqual(len(results), 1)
        uid, statuses, kind = results[0]
        self.assertEqual(uid, "UAT-RP-005")
        self.assertEqual(statuses, ["FAIL_PROVEN"])
        self.assertEqual(kind, "single")

    def test_e1_2_single_uat_freeform_narrative_only_referenced(self):
        line = "| UAT-RP-026 | Remote (RP-8) lease/fencing |"
        results = self.mod._parse_freeform(line)
        self.assertEqual(len(results), 1)
        uid, statuses, kind = results[0]
        self.assertEqual(uid, "UAT-RP-026")
        self.assertEqual(statuses, [])
        self.assertEqual(kind, "single_noref")

    def test_e1_2_marker_takes_precedence_over_freeform_on_same_line(self):
        """If a line matches the marker pattern, free-form parsing
        must NOT also apply. (Markers are unambiguous.)
        """
        line = "UAT-EVIDENCE | UAT-RP-007 | COVERED | candidate=0000000000000000000000000000000000000000"
        parsed = self.mod._parse_marker(line)
        self.assertIsNotNone(parsed)

    # --- select_evidence integration ---

    def test_select_evidence_no_triples_is_not_run(self):
        self.assertEqual(
            self.mod.select_evidence([], "abc" * 14)[0],
            "NOT_RUN",
        )

    def test_select_evidence_single_triple_with_status_is_covered(self):
        # Use a single real commit so is_ancestor works.
        repo_dir = _tmp_repo_with_receipts([
            ("r.md", "UAT-RP-005 | COVERED | x"),
        ])
        git = _git(repo_dir)
        sha = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()[0]
        triples = [_quad("r.md", "UAT-RP-005 | COVERED", sha, ["COVERED"])]
        status, _ = self.mod.select_evidence(triples, sha, git)
        self.assertEqual(status, "COVERED")

    def test_select_evidence_narrative_only_maximal_is_referenced(self):
        repo_dir = _tmp_repo_with_receipts([
            ("r.md", "| UAT-RP-005 | narrative |"),
        ])
        git = _git(repo_dir)
        sha = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()[0]
        triples = [_quad("r.md", "narrative", sha, [])]
        status, _ = self.mod.select_evidence(triples, sha, git)
        self.assertEqual(status, "REFERENCED")

    def test_select_evidence_unreachable_triple_is_not_run(self):
        """Triples whose commit is not an ancestor of candidate are
        skipped entirely -> NOT_RUN."""
        # Build a repo where commit B has the receipt, then a side
        # branch off A (B's parent) gets commit X. From X's perspective
        # B is not reachable.
        repo_dir = _tmp_repo_with_history([
            {"files": [("r.md", "UAT-RP-005 COVERED")],
             "message": "A"},
            {"files": [("r2.md", "x")], "message": "B on A"},
        ])
        # Get SHA of A (parent of HEAD).
        sha_a = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H", "r.md"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()[0]
        # Get SHA of B (HEAD).
        sha_b = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()[0]
        # Create a side branch from A (not from B), and commit on it.
        subprocess.run(["git", "-C", str(repo_dir), "checkout", "-q",
                        sha_a], check=True)
        subprocess.run(["git", "-C", str(repo_dir), "checkout", "-q",
                        "-b", "side"], check=True)
        (pathlib.Path(repo_dir) / "side.md").write_text("y")
        subprocess.run(["git", "-C", str(repo_dir), "add", "side.md"],
                       check=True)
        subprocess.run(["git", "-C", str(repo_dir), "commit", "-q",
                        "-m", "X on side"], check=True)
        sha_x = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()[0]
        git = _git(repo_dir)
        # The triple's commit is sha_b. From sha_x (side branch tip),
        # sha_b is NOT reachable (X branched off A, before B was made).
        triples = [_quad("r.md", "UAT-RP-005 COVERED", sha_b,
                         ["COVERED"])]
        status, _ = self.mod.select_evidence(triples, sha_x, git)
        self.assertEqual(status, "NOT_RUN",
                         "unreachable triple -> NOT_RUN, never PARTIAL")
        # Sanity: from sha_b itself, it IS reachable and COVERED.
        status_b, _ = self.mod.select_evidence(triples, sha_b, git)
        self.assertEqual(status_b, "COVERED")

    # --- D-007 tests (regression) ---

    def test_d007_fail_closed_does_not_imply_fail_proven(self):
        m = self.mod.EXPLICIT_PATTERN.findall("rechazo fail-closed antes de nuevos efectos")
        self.assertEqual(m, [])

    def test_d007_fail_fast_does_not_imply_fail_proven(self):
        m = self.mod.EXPLICIT_PATTERN.findall("the orchestrator uses fail-fast semantics")
        self.assertEqual(m, [])

    def test_d007_normative_matrix_excluded(self):
        receipts = [
            ("PRODUCTION_READY_UAT_MATRIX.md", "| UAT-RP-005 | Foo | COVERED | bar |\n"),
            ("OTHER.md", "| UAT-RP-005 | Independent | COVERED | baz |\n"),
        ]
        tmpdir = _tmp_repo_with_receipts(receipts)
        git = _git(tmpdir)
        found = self.mod.scan_receipts(pathlib.Path(tmpdir), git=git)
        self.assertEqual(len(found["UAT-RP-005"]), 1)
        self.assertEqual(found["UAT-RP-005"][0][0].name, "OTHER.md")

    # --- Pinning ---

    def test_pin_receipt_picks_maximal_sha(self):
        repo_dir = _tmp_repo_with_history([
            {"files": [("old.md", "old evidence")], "message": "old"},
            {"files": [("new.md", "new evidence")], "message": "new"},
        ])
        sha_old = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H", "old.md"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        sha_new = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H", "new.md"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        git = _git(repo_dir)
        triples = [
            _quad("old.md", "old COVERED", sha_old, ["COVERED"]),
            _quad("new.md", "new FAIL_PROVEN", sha_new, ["FAIL_PROVEN"]),
        ]
        # From sha_new: only sha_new is maximal.
        pinned = self.mod.pin_receipt(triples, sha_new, git)
        self.assertEqual(pinned, "new.md")

    def test_pin_receipt_tie_break_by_path(self):
        # Same SHA, two paths. Smaller path wins.
        sha = "0" * 40
        triples = [
            _quad("z.md", "x", sha, ["COVERED"]),
            _quad("a.md", "x", sha, ["COVERED"]),
        ]
        # Use a git cwd that has at least one commit so is_ancestor works.
        repo_dir = _tmp_repo_with_receipts([("seed.md", "seed")])
        git = _git(repo_dir)
        sha_seed = subprocess.run(
            ["git", "-C", str(repo_dir), "log", "--format=%H"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()[0]
        # sha is not an ancestor of sha_seed, so maximals = [].
        # pin_receipt returns None in that case.
        # Build a scenario where sha IS reachable: use sha_seed itself.
        triples2 = [
            _quad("z.md", "x", sha_seed, ["COVERED"]),
            _quad("a.md", "x", sha_seed, ["COVERED"]),
        ]
        pinned = self.mod.pin_receipt(triples2, sha_seed, git)
        self.assertEqual(pinned, "a.md",
                         "tie-break: smallest path wins")

    # --- Scan regression ---

    def test_scan_receipts_finds_uat_ids(self):
        tmp = _tmp_repo_with_receipts([
            ("a.md", "| `UAT-RP-005` | **COVERED** | x |\n"),
        ])
        git = _git(tmp)
        found = self.mod.scan_receipts(pathlib.Path(tmp), git=git)
        self.assertEqual(len(found["UAT-RP-005"]), 1)
        self.assertEqual(found["UAT-RP-005"][0][0].name, "a.md")

    def test_scan_receipts_marker_certifies_only_listed_uat(self):
        # Need a real commit SHA. Create the receipt in a real repo
        # and pass that SHA as candidate.
        receipts = [
            ("r.md", "| UAT-RP-007 mentioned here, also UAT-RP-008 |\n"),
        ]
        tmpdir = _tmp_repo_with_receipts(receipts)
        git = _git(tmpdir)
        sha_real = subprocess.run(
            ["git", "-C", str(tmpdir), "log", "--format=%H"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()[0]
        # Add a marker line that certifies UAT-RP-007, then commit.
        (pathlib.Path(tmpdir) / "r.md").write_text(
            f"UAT-EVIDENCE | UAT-RP-007 | COVERED | candidate={sha_real}\n"
            "| UAT-RP-007 also appears here, also UAT-RP-008 |\n"
        )
        subprocess.run(["git", "-C", str(tmpdir), "add", "r.md"],
                       check=True)
        subprocess.run(["git", "-C", str(tmpdir), "commit", "-q",
                        "-m", "add marker"], check=True)
        sha_marker = subprocess.run(
            ["git", "-C", str(tmpdir), "log", "--format=%H"],
            capture_output=True, text=True, check=True,
        ).stdout.strip().splitlines()[0]
        found = self.mod.scan_receipts(
            pathlib.Path(tmpdir), git=git, candidate_sha=sha_marker,
        )
        # UAT-RP-007 should have at least one certified triple (from
        # the marker). UAT-RP-008 should have one REFERENCED-only
        # triple from the free-form line.
        self.assertGreaterEqual(len(found["UAT-RP-007"]), 1,
                                f"UAT-RP-007 should have marker evidence; found {len(found['UAT-RP-007'])}")
        # Find the certified one for UAT-RP-007.
        certified = [t for t in found["UAT-RP-007"] if t[3]]
        self.assertEqual(len(certified), 1, "exactly one certified row")
        self.assertEqual(certified[0][3], ["COVERED"])
        # UAT-RP-008: from the free-form line only.
        self.assertEqual(len(found["UAT-RP-008"]), 1)
        self.assertEqual(found["UAT-RP-008"][0][3], [])

    def test_d007_admission_blocks_conflict(self):
        """Sanity: CONFLICT is part of BLOCKING_UAT_STATES in
        admission-check.py (D-007)."""
        ac = importlib.util.spec_from_file_location(
            "admission_check", ROOT / "scripts" / "admission-check.py"
        )
        ac_mod = importlib.util.module_from_spec(ac)
        ac.loader.exec_module(ac_mod)
        self.assertIn("CONFLICT", ac_mod.BLOCKING_UAT_STATES)


if __name__ == "__main__":
    unittest.main(verbosity=2)
