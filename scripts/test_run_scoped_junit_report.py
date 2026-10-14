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
from pathlib import Path

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

    def run_text(self, *extra: str) -> subprocess.CompletedProcess:
        """Run the tool in its human-readable mode.

        JSON is the machine contract; the text report is what an operator reads
        when a gate refuses. Both are exercised, because a guard that only
        exists in one of them is a guard that can be routed around.
        """
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
            *extra,
        ]
        return subprocess.run(cmd, capture_output=True, text=True, cwd=ROOT)


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

    def test_digest_separates_same_named_suites_in_different_modules(self) -> None:
        """Claim: a result set is identified by its logical paths, not basenames.

        Two modules can each emit `TEST-Same.xml` — a parallel or forked suite
        naming the same class is common, and a basename digest gives those two
        files one identity. A digest that collides is not evidence of anything.

        Mutation: hash `os.path.basename(p)` instead of the path relative to the
        results root. Measured: under M5 both runs digest to the same value, so
        this row fails. It also failed for a second, worse reason the first
        version of the row hid: it compared a whole report dict against a
        digest string, which is never equal. A row that cannot fail is not a
        row.
        """
        self.xml("m1", "Same", t=1)
        _, first_out = self.run_tool("--boundary-file", self.boundary())
        first = first_out["fresh_set_sha256"]
        self.assertIsInstance(first, str, "the digest is a string, not a report")
        other = tempfile.TemporaryDirectory()
        self.addCleanup(other.cleanup)
        alt = Path(other.name)
        # A DIFFERENT module directory: the same suite name under two module
        # roots is the collision a basename digest cannot see.
        (alt / "m2" / "build" / "test-results" / "test").mkdir(parents=True)
        (alt / "m2" / "build" / "test-results" / "test" / "TEST-Same.xml").write_text(
            SUITE.format(name="Same", t=1, s=0, f=0, e=0), encoding="utf-8"
        )
        head = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        proc = subprocess.run(
            [
                sys.executable,
                TOOL,
                "--head",
                head,
                "--results-dir",
                str(alt),
                "--json",
                "--boundary-file",
                self.boundary(),
            ],
            capture_output=True,
            text=True,
            cwd=ROOT,
        )
        second = json.loads(proc.stdout)["fresh_set_sha256"]
        self.assertNotEqual(
            first,
            second,
            "the same basename under a different module path must not share a digest",
        )

    def test_digest_changes_when_only_the_file_bytes_change(self) -> None:
        """Claim: the digest identifies the evidence bytes, not only the counters.

        Two files with identical counters but different content (an extra
        testcase element, a different suite name) are different evidence. A
        counters-only digest reports them as the same run.

        Mutation: drop the per-file SHA-256 from the contribution and keep only
        the validated counters.
        """
        self.xml("m1", "Same", t=1)
        _, first = self.run_tool("--boundary-file", self.boundary())
        d = os.path.join(self.results, "m1", "build", "test-results", "test")
        with open(os.path.join(d, "TEST-Same.xml"), "w", encoding="utf-8") as fh:
            fh.write(
                SUITE.format(name="Same", t=1, s=0, f=0, e=0).replace(
                    "></testsuite>", '><testcase name="row"/></testsuite>'
                )
            )
        _, second = self.run_tool("--boundary-file", self.boundary())
        self.assertNotEqual(
            first["fresh_set_sha256"],
            second["fresh_set_sha256"],
            "identical counters with different bytes must change the digest",
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
    def test_unreadable_fresh_xml_invalidates_the_run(self) -> None:
        """Claim: evidence this run claims to have produced must be readable.

        The pre-fix tool counted a corrupt file as zero tests and still printed
        PASS/exit 0 (characterized at fdcadcfd, digest
        42fa130751b61c870613e770111b7679e87958f86873942b156bb11bf89c8941).
        Counting unreadable evidence as an empty success is the false green.

        Mutation: keep `unreadable` as an advisory count and compute `ok` from
        the remaining totals. The verdict returns to PASS over 1 test.
        """
        self.xml("m1", "Good", t=1)
        d = os.path.join(self.results, "m1", "build", "test-results", "test")
        with open(os.path.join(d, "TEST-Broken.xml"), "w", encoding="utf-8") as fh:
            fh.write("<testsuite truncated")
        r = self.run_text("--boundary-file", self.boundary())
        self.assertEqual(r.returncode, 2, "corrupt fresh evidence must not report PASS")
        self.assertIn("TEST-Broken.xml", r.stderr)
        self.assertIn("VERDICT: INVALID_EVIDENCE", r.stdout)

    def test_invalid_counter_invalidates_the_run_instead_of_reading_as_zero(self) -> None:
        """Claim: a counter that is not an integer is invalid, not zero.

        `failures="not-a-number"` used to fall back to 0, which is the exact
        value a clean run reports: the green gate is indistinguishable from a
        red one whose failure count failed to parse.

        Mutation: restore `except ValueError: out[k] = 0` in read_xml.
        """
        d = os.path.join(self.results, "m1", "build", "test-results", "test")
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, "TEST-BadCounter.xml")
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(
                '<testsuite name="BadCounter" tests="1" skipped="0" '
                'failures="not-a-number" errors="0"><testcase name="row"/></testsuite>'
            )
        r = self.run_text("--boundary-file", self.boundary())
        self.assertEqual(r.returncode, 2, "a non-integer counter must not report PASS")
        self.assertIn("TEST-BadCounter.xml", r.stderr)
        self.assertIn("not an integer", r.stderr)

    def test_invalid_fresh_evidence_is_a_typed_verdict_in_json(self) -> None:
        """Claim: the machine contract names each invalid file and its reason.

        Mutation: replace the list with an `unreadable_files` integer. The count
        survives, the reason does not, and a reader cannot tell an unparsable
        file from a dropped one.

        The verdict is INVALID_EVIDENCE rather than an absent report: the tool
        did read the run's results and can name exactly which ones it could not
        account for. Only a missing run (no XML, boundary after the results,
        HEAD mismatch) produces a bare refusal.
        """
        self.xml("m1", "Good", t=1)
        d = os.path.join(self.results, "m1", "build", "test-results", "test")
        with open(os.path.join(d, "TEST-Broken.xml"), "w", encoding="utf-8") as fh:
            fh.write("<testsuite truncated")
        code, out = self.run_tool("--boundary-file", self.boundary())
        self.assertEqual(code, 2)
        self.assertEqual(out["verdict"], "INVALID_EVIDENCE")
        self.assertEqual(out["exit_code"], 2)
        self.assertNotIn("unreadable_files", out["fresh"])
        self.assertEqual(len(out["fresh_invalid_evidence"]), 1)
        bad = out["fresh_invalid_evidence"][0]
        self.assertTrue(bad["path"].endswith("TEST-Broken.xml"), bad)
        self.assertIn("does not parse", bad["reason"])

    def test_text_report_names_the_invalid_file_and_its_reason(self) -> None:
        """Claim: the operator-facing report says what could not be accounted for.

        Mutation: drop the per-file stderr loop and keep only a count, or print
        nothing at all. The reader sees `VERDICT: INVALID_EVIDENCE` with no
        path and cannot act on it.
        """
        self.xml("m1", "Good", t=1)
        d = os.path.join(self.results, "m1", "build", "test-results", "test")
        with open(os.path.join(d, "TEST-Broken.xml"), "w", encoding="utf-8") as fh:
            fh.write("<testsuite truncated")
        r = self.run_text("--boundary-file", self.boundary())
        self.assertEqual(r.returncode, 2)
        self.assertIn("TEST-Broken.xml", r.stderr)
        self.assertIn("does not parse", r.stderr)
        self.assertIn("VERDICT: INVALID_EVIDENCE", r.stdout)

    def test_readable_counts_stay_visible_alongside_the_invalid_case(self) -> None:
        """Claim: invalidating the run does not erase what WAS readable.

        Mutation: return the invalid set without the readable totals. The
        operator loses the only information that distinguishes one corrupt
        class out of thousands from a wholly unreadable results tree.
        """
        self.xml("m1", "Good", t=4)
        d = os.path.join(self.results, "m1", "build", "test-results", "test")
        with open(os.path.join(d, "TEST-Broken.xml"), "w", encoding="utf-8") as fh:
            fh.write("<testsuite truncated")
        code, out = self.run_tool("--boundary-file", self.boundary())
        self.assertEqual(code, 2)
        self.assertEqual(out["fresh"]["tests"], 4)
        self.assertEqual(out["fresh_files"], 2)

    def test_unreadable_carried_over_is_warned_not_fatal(self) -> None:
        """Claim: excluded evidence cannot certify a run, but does not void it.

        The fresh set is what the run is judged on. A corrupt file from a
        previous run is already excluded, so making it fatal would let stale
        bytes from an unrelated run block a legitimate verdict — the mirror
        image of the defect being fixed.

        Mutation: check the invalid case over fresh+stale instead of fresh only.
        """
        self.xml("m1", "Fresh", t=2)
        d = os.path.join(self.results, "m1", "build", "test-results", "test")
        with open(os.path.join(d, "TEST-StaleBroken.xml"), "w", encoding="utf-8") as fh:
            fh.write("<testsuite truncated")
        os.utime(
            os.path.join(d, "TEST-StaleBroken.xml"),
            (os.path.getmtime(os.path.join(d, "TEST-StaleBroken.xml")) - 3600,) * 2,
        )
        code, out = self.run_tool("--boundary-file", self.boundary())
        self.assertEqual(code, 0, "stale evidence must not void a valid run")
        self.assertEqual(out["verdict"], "PASS")
        self.assertEqual(out["fresh"]["tests"], 2)
        self.assertEqual(len(out["carried_over_invalid_evidence"]), 1)

    def test_negative_counter_is_invalid(self) -> None:
        """Claim: a negative counter is malformed, not a discount.

        Mutation: drop the `value < 0` check and accept the parsed integer.
        """
        d = os.path.join(self.results, "m1", "build", "test-results", "test")
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "TEST-Neg.xml"), "w", encoding="utf-8") as fh:
            fh.write(
                '<testsuite name="Neg" tests="-3" skipped="0" failures="0" '
                'errors="0"></testsuite>'
            )
        r = self.run_text("--boundary-file", self.boundary())
        self.assertEqual(r.returncode, 2)
        self.assertIn("TEST-Neg.xml", r.stderr)
        self.assertIn("negative", r.stderr)

    def test_non_testsuite_xml_is_invalid(self) -> None:
        """Claim: a TEST-*.xml with no <testsuite> is not a result.

        Mutation: accept any parseable XML and default the counters to zero.
        """
        d = os.path.join(self.results, "m1", "build", "test-results", "test")
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "TEST-Other.xml"), "w", encoding="utf-8") as fh:
            fh.write("<properties><property name='x' value='y'/></properties>")
        r = self.run_text("--boundary-file", self.boundary())
        self.assertEqual(r.returncode, 2)
        self.assertIn("TEST-Other.xml", r.stderr)
        self.assertIn("testsuite", r.stderr)

    def test_unreadable_xml_is_no_longer_a_silent_subtraction(self) -> None:
        """Claim: the readable total and the invalid set are separate values.

        Mutation: keep only a count and drop the typed list, so a caller sees
        1 test and cannot learn that a second class was unparsable.
        """
        self.xml("m1", "Good", t=1)
        d = os.path.join(self.results, "m1", "build", "test-results", "test")
        with open(os.path.join(d, "TEST-Broken.xml"), "w", encoding="utf-8") as fh:
            fh.write("<testsuite truncated")
        _, out = self.run_tool("--boundary-file", self.boundary())
        self.assertEqual(out["fresh"]["tests"], 1, "the good file is still counted")
        self.assertEqual(out["fresh_files"], 2, "both files are still in the set")
        self.assertEqual(
            out["fresh_invalid_evidence"][0]["path"].split(os.sep)[-1],
            "TEST-Broken.xml",
        )

    def test_a_digest_that_is_not_a_digest_is_marked_as_such(self) -> None:
        """Claim: `fresh_set_sha256` is a digest or it says it is not.

        When the bytes of a fresh result cannot be read, the pre-fix helper
        returned the string `unreadable-bytes:<rel>:<errno>` from a function
        whose return value is, by name and by field, a SHA-256. A consumer
        that compared or logged it as one had no way to tell. That is the same
        defect class as `Optional`-shaped `None` for an unreadable count, one
        layer down: a string standing in for evidence that does not exist.

        Mutation: keep the old `unreadable-bytes:` prefix, or silently hash
        the empty string so the run still reports a plausible digest over
        fewer files than it claims.

        Measured, not assumed: an unreadable file fails at BOTH reads, so the
        run is INVALID_EVIDENCE *and* the digest field names itself. The first
        version of this row asserted exit 0 on the premise that a file could
        parse and then fail to hash; it cannot, because both reads open the
        same bytes. Asserting the premise instead of the observation would have
        been the third unfalsifiable row in this file.
        """
        self.xml("m1", "Good", t=1)
        target = os.path.join(
            self.results, "m1", "build", "test-results", "test", "TEST-Vanishing.xml"
        )
        with open(target, "w", encoding="utf-8") as fh:
            fh.write(SUITE.format(name="Vanishing", t=2, s=0, f=0, e=0))
        # Unreadable, not absent: `discover` still finds it and the run still
        # has fresh evidence, so the row exercises the digest path rather than
        # the "no XML at all" refusal.
        os.chmod(target, 0o000)
        self.addCleanup(os.chmod, target, 0o644)
        if os.access(target, os.R_OK):
            self.skipTest("the platform ignores chmod 000 (running as root?)")
        code, out = self.run_tool("--boundary-file", self.boundary())
        self.assertEqual(code, 2, "unreadable evidence is invalid evidence")
        self.assertEqual(out["verdict"], "INVALID_EVIDENCE")
        self.assertEqual(len(out["fresh_invalid_evidence"]), 1)
        digest = out["fresh_set_sha256"]
        self.assertNotEqual(len(digest), 64, f"not a digest: {digest}")
        self.assertTrue(
            digest.startswith("INVALID_DIGEST_NO_BYTES:"),
            f"an unhashable file must not be reported as a digest: {digest}",
        )
        self.assertIn("TEST-Vanishing.xml", digest)

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
