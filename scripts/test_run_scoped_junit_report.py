#!/usr/bin/env python3
"""Tests for run-scoped-junit-report.py.

Each test states the claim it defends and the mutation that would break it.
A behavioural claim without a killing mutation is characterisation, and this
repository does not accept characterisation dressed as a gate.

The subject under test is the report tool, and it is executed as a
subprocess against real JUnit XML laid out in a temporary directory. No
algorithm is reimplemented here: the counts come from the tool.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOL = os.path.join(ROOT, "scripts", "run-scoped-junit-report.py")

SUITE = (
    '<testsuite name="{name}" tests="{t}" skipped="{s}" failures="{f}" '
    'errors="{e}"></testsuite>'
)


class Harness(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = self.tmp.name
        self.results = os.path.join(self.root, "v2")
        os.makedirs(self.results)
        # The boundary MUST be created before the results. Creating it after
        # excludes the very run it was meant to capture, which is the failure
        # mode the tool now fails closed on.
        self._boundary = os.path.join(self.root, ".boundary")
        open(self._boundary, "w", encoding="utf-8").close()

    def xml(self, sub: str, name: str, *, t=1, s=0, f=0, e=0, age_s=0.0) -> str:
        d = os.path.join(self.results, sub, "build", "test-results", "test")
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, f"TEST-{name}.xml")
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(SUITE.format(name=name, t=t, s=s, f=f, e=e))
        if age_s:
            os.utime(p, (os.path.getmtime(p) - age_s, os.path.getmtime(p) - age_s))
        return p

    def boundary(self) -> str:
        return self._boundary

    def run_raw(self, *extra: str) -> subprocess.CompletedProcess:
        """Run the tool without assuming it produced JSON on stdout."""
        head = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        cmd = [
            sys.executable,
            TOOL,
            "--head",
            head,
            "--results-dir",
            self.results,
            "--json",
            *extra,
        ]
        return subprocess.run(cmd, capture_output=True, text=True, cwd=ROOT)

    def run_tool(self, *extra: str) -> tuple[int, dict]:
        r = self.run_raw(*extra)
        return r.returncode, json.loads(r.stdout)


class FreshVsCarriedOver(Harness):
    def test_fresh_only_is_reported_as_the_run(self) -> None:
        """Claim: the reported result is the run's, not the directory's.

        Mutation: drop the boundary split and return every file. That reports
        4 classes / 8 tests instead of 2 / 4 and re-creates the W4 defect.
        """
        self.xml("m1", "Fresh", t=2, age_s=0)
        self.xml("m2", "Fresh2", t=2)
        self.xml("m1", "Old", t=100, age_s=3600)
        code, out = self.run_tool("--boundary-file", self.boundary())
        self.assertEqual(code, 0)
        self.assertEqual(out["fresh"]["tests"], 4, "fresh count must exclude carried-over")
        self.assertEqual(out["carried_over"]["tests"], 100)
        self.assertEqual(out["fresh_files"], 2)
        self.assertEqual(out["carried_over_files"], 1)

    def test_carried_over_is_reported_not_silently_dropped(self) -> None:
        """Claim: the excluded set is auditable, not invisible.

        Mutation: stop reporting carried_over entirely. The tool would still
        return correct fresh counts, and the evidence gap would be invisible.
        """
        self.xml("m1", "Fresh", t=1)
        self.xml("m1", "Old", t=50, age_s=3600)
        _, out = self.run_tool("--boundary-file", self.boundary())
        self.assertEqual(out["carried_over"]["tests"], 50)
        self.assertEqual(out["carried_over_files"], 1)


class FailClosed(Harness):
    def test_failures_make_the_verdict_fail(self) -> None:
        """Claim: a red run cannot be reported as a green gate.

        Mutation: compute ok from the class count or return 0 unconditionally.
        """
        self.xml("m1", "Red", t=1, f=1)
        code, out = self.run_tool("--boundary-file", self.boundary())
        self.assertEqual(code, 1, "a failure must yield exit 1")
        self.assertEqual(out["verdict"], "FAIL")

    def test_errors_also_fail_even_with_zero_failures(self) -> None:
        """Claim: errors are a distinct outcome from failures.

        Mutation: check only `failures == 0`, ignoring `errors`. An
        infrastructure error would then read as a green gate.
        """
        self.xml("m1", "Err", t=1, f=0, e=1)
        code, out = self.run_tool("--boundary-file", self.boundary())
        self.assertEqual(code, 1)
        self.assertEqual(out["verdict"], "FAIL")

    def test_empty_results_dir_refuses_to_report_green(self) -> None:
        """Claim: no evidence is not evidence of success.

        Mutation: return 0 with zero counts when the directory is empty.
        """
        r = self.run_raw()
        self.assertEqual(r.returncode, 2, "an empty results directory must not report PASS")
        self.assertEqual(r.stdout.strip(), "", "a refusal must not print a report body")

    def test_boundary_after_the_run_fails_closed(self) -> None:
        """Claim: a boundary created after the results cannot certify zero tests.

        This is the dangerous direction: without the guard the tool reports an
        empty THIS RUN row and PASS over zero tests, which looks like a gate.

        Mutation: delete the `if not fresh: return 2` branch.
        """
        self.xml("m1", "Result", t=3)
        late = os.path.join(self.root, ".late-boundary")
        open(late, "w", encoding="utf-8").close()
        os.utime(late, (os.path.getmtime(late) + 60, os.path.getmtime(late) + 60))
        r = self.run_raw("--boundary-file", late)
        self.assertEqual(r.returncode, 2, "an empty fresh set must fail closed")
        self.assertIn("created", r.stderr)

    def test_skips_do_not_make_the_verdict_fail(self) -> None:
        """Claim: skips are reported and counted but are not a red gate.

        Mutation: treat skipped > 0 as failure. The 133 W4 skips would then
        block every gate and the tool would be unusable.
        """
        self.xml("m1", "Skipped", t=5, s=5)
        code, out = self.run_tool("--boundary-file", self.boundary())
        self.assertEqual(code, 0)
        self.assertEqual(out["verdict"], "PASS")
        self.assertEqual(out["fresh"]["skipped"], 5)


class Provenance(Harness):
    def test_head_mismatch_is_rejected(self) -> None:
        """Claim: a report cannot be bound to a SHA that did not produce it.

        Mutation: drop the `head_matches` check. A receipt written for one
        commit would then be inheritable by any later commit, which is the
        exact inheritance AGENTS.md forbids.
        """
        self.xml("m1", "Fresh", t=1)
        r = subprocess.run(
            [
                sys.executable,
                TOOL,
                "--head",
                "0" * 40,
                "--results-dir",
                self.results,
                "--json",
                "--boundary-file",
                self.boundary(),
            ],
            capture_output=True,
            text=True,
            cwd=ROOT,
        )
        self.assertEqual(r.returncode, 2, "head mismatch must fail closed")
        self.assertIn("does not match", r.stderr)

    def test_report_states_the_provenance_fields(self) -> None:
        """Claim: the report is attributable.

        Mutation: emit counts without head/branch/tree/argv/digest, leaving a
        bare number that no later reader can bind to a tree.
        """
        self.xml("m1", "Fresh", t=3)
        _, out = self.run_tool(
            "--boundary-file", self.boundary(), "--", "--tests", "Foo*"
        )
        for k in ("head", "branch", "subject", "tree", "gradle_argv", "fresh_set_sha256"):
            self.assertIn(k, out)
            self.assertTrue(out[k] not in (None, "", []), f"{k} must be populated")
        self.assertEqual(out["gradle_argv"], ["--tests", "Foo*"])

    def test_fresh_set_digest_changes_when_results_change(self) -> None:
        """Claim: the digest identifies the result set, not just the tree.

        Mutation: hash only the file names. Two different runs with the same
        class names but different counts would share a digest.
        """
        self.xml("m1", "Same", t=1)
        _, first = self.run_tool("--boundary-file", self.boundary())
        self.xml("m1", "Same", t=9)
        _, second = self.run_tool("--boundary-file", self.boundary())
        self.assertNotEqual(
            first["fresh_set_sha256"],
            second["fresh_set_sha256"],
            "same-named class with different counts must change the digest",
        )

    def test_missing_boundary_file_is_declared_as_inference(self) -> None:
        """Claim: an inferred boundary is not presented as a measurement.

        Mutation: drop the `boundary_source` wording. The reader would have no
        way to know the split came from a clock heuristic rather than a marker.
        """
        self.xml("m1", "Fresh", t=1)
        _, out = self.run_tool()
        self.assertIn("NO boundary file", out["boundary_source"])


class MalformedInput(Harness):
    def test_unreadable_xml_is_counted_not_silently_dropped(self) -> None:
        """Claim: a corrupt file is visible, not quietly subtracted.

        Mutation: `except: pass` without counting. The totals would look
        clean while a class silently vanished from the evidence.
        """
        self.xml("m1", "Good", t=1)
        d = os.path.join(self.results, "m1", "build", "test-results", "test")
        with open(os.path.join(d, "TEST-Broken.xml"), "w", encoding="utf-8") as fh:
            fh.write("<testsuite truncated")
        code, out = self.run_tool("--boundary-file", self.boundary())
        self.assertEqual(out["fresh"].get("unreadable_files"), 1)
        self.assertEqual(out["fresh"]["tests"], 1, "the good file is still counted")

    def test_unrelated_dirs_are_not_scanned(self) -> None:
        """Claim: only build/test-results trees are evidence.

        Mutation: scan every `TEST-*.xml` anywhere under the results root.
        """
        d = os.path.join(self.results, "docs")
        os.makedirs(d)
        with open(os.path.join(d, "TEST-NotAResult.xml"), "w", encoding="utf-8") as fh:
            fh.write(SUITE.format(name="x", t=99, s=0, f=0, e=0))
        # No real results exist, so the tool refuses rather than reporting an
        # empty PASS. Asserting JSON here would demand the very false green the
        # guard exists to prevent.
        r = self.run_raw("--boundary-file", self.boundary())
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout.strip(), "")

    def test_non_results_tree_is_not_evidence(self) -> None:
        """Claim: a TEST-*.xml outside build/test-results is not a result.

        Same mutation as above, in the shape that actually occurs: a stray
        XML at a module root.
        """
        d = os.path.join(self.results, "m1")
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "TEST-Stray.xml"), "w", encoding="utf-8") as fh:
            fh.write(SUITE.format(name="x", t=77, s=0, f=0, e=0))
        self.xml("m1", "Real", t=1)
        _, out = self.run_tool("--boundary-file", self.boundary())
        self.assertEqual(out["fresh"]["tests"], 1)
        self.assertEqual(out["fresh_files"], 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
