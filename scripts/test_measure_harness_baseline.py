#!/usr/bin/env python3
"""Behavioural tests for measure-harness-baseline.py.

Hermetic: the Gradle runner is injected, so no build is started here. The XML
snapshot is exercised against a real temp results directory, because freshness
(which files this run rewrote) is the property that keeps a cache-warm sample
from being read as a measurement.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import pathlib
import shutil
import tempfile
import time
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "measure-harness-baseline.py"

SUCCESS = ("BUILD SUCCESSFUL in 12s\n"
           "13 actionable tasks: 4 executed, 5 from cache, 4 up-to-date\n")
FAILED = ("BUILD FAILED in 3s\n"
          "1 actionable tasks: 1 executed, 0 from cache, 0 up-to-date\n")
UP_TO_DATE_ONLY = "BUILD SUCCESSFUL in 0.4s\n5 actionable tasks: 5 up-to-date\n"

PROFILE_HTML = """<html><body><table>
<tr><td>Startup</td><td>0.123s</td></tr>
<tr><td>Settings and buildSrc</td><td>0.250s</td></tr>
<tr><td>Loading Projects</td><td>0.400s</td></tr>
<tr><td>Configuring Projects</td><td>1.500s</td></tr>
<tr><td>Task Execution</td><td>10.250s</td></tr>
<tr><td>Total Build Time</td><td>12.500s</td></tr>
</table></body></html>
"""


def load_module():
    spec = importlib.util.spec_from_file_location("measure_harness_baseline", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Parsing(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def test_success_output_parses_time_and_split(self) -> None:
        parsed = self.mod.parse_gradle_output(SUCCESS)
        self.assertEqual(parsed["result"], "SUCCESS")
        self.assertEqual(parsed["reported_s"], 12.0)
        self.assertEqual(parsed["executed"], 4)
        self.assertEqual(parsed["from_cache"], 5)
        self.assertEqual(parsed["up_to_date"], 4)

    def test_failed_output_is_recorded_as_failed(self) -> None:
        """Mutation: match only SUCCESS; a failed build would look like UNKNOWN."""
        parsed = self.mod.parse_gradle_output(FAILED)
        self.assertEqual(parsed["result"], "FAILED")
        self.assertEqual(parsed["reported_s"], 3.0)

    def test_up_to_date_only_split(self) -> None:
        parsed = self.mod.parse_gradle_output(UP_TO_DATE_ONLY)
        self.assertEqual(parsed["executed"], 0)
        self.assertEqual(parsed["up_to_date"], 5)

    def test_two_category_split_is_parsed(self) -> None:
        """Claim: Gradle's truncated tail form is understood.

        Real data: a cold run printed "8 actionable tasks: 8 executed" with no
        cache/up-to-date categories; the first parser returned None for all
        three, so the sample said nothing about what executed.
        """
        parsed = self.mod.parse_gradle_output("BUILD SUCCESSFUL in 27s\n8 actionable tasks: 8 executed\n")
        self.assertEqual(parsed["executed"], 8)
        self.assertEqual(parsed["from_cache"], 0)
        self.assertEqual(parsed["up_to_date"], 0)

    def test_unparseable_output_is_unknown_not_success(self) -> None:
        parsed = self.mod.parse_gradle_output("some noise only\n")
        self.assertEqual(parsed["result"], "UNKNOWN")
        self.assertIsNone(parsed["reported_s"])

    def test_profile_html_yields_the_breakdown(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp) / "profile-x.html"
            p.write_text(PROFILE_HTML)
            parsed = self.mod.parse_profile_html(p)
        self.assertEqual(parsed["configuring_projects"], 1.5)
        self.assertEqual(parsed["executing_tasks"], 10.25)
        self.assertEqual(parsed["total"], 12.5)


class ResultsDir(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def test_module_task_maps_to_its_results_dir(self) -> None:
        d = self.mod.results_dir_for(":pipeline-domain:test", pathlib.Path("/r"), None)
        self.assertEqual(d, pathlib.Path("/r/v2/pipeline-domain/build/test-results/test"))

    def test_non_test_task_has_no_results_dir(self) -> None:
        self.assertIsNone(self.mod.results_dir_for("check", pathlib.Path("/r"), None))


class XmlFreshness(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="baseline-xml-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        d = self.tmp / "build" / "test-results" / "test"
        d.mkdir(parents=True)
        (d / "TEST-A.xml").write_text(
            '<testsuite name="A" tests="3" failures="0" errors="0" skipped="1"/>')
        (d / "TEST-B.xml").write_text(
            '<testsuite name="B" tests="2" failures="1" errors="0" skipped="0"/>')

    def test_totals_and_rewritten_count(self) -> None:
        """Claim: freshness is measured, not assumed.

        Mutation: report all files as this run's; a cache-warm invocation would
        then claim test counts it never produced.
        """
        past = time.time() - 3600
        snap = self.mod.xml_snapshot(self.tmp, past)
        self.assertEqual(snap["files"], 2)
        self.assertEqual(snap["rewritten_files"], 2)
        self.assertEqual(snap["tests"], 5)
        self.assertEqual(snap["failures"], 1)
        self.assertEqual(snap["skipped"], 1)
        future = time.time() + 3600
        snap2 = self.mod.xml_snapshot(self.tmp, future)
        self.assertEqual(snap2["rewritten_files"], 0)

    def test_missing_dir_is_zeroed_not_invented(self) -> None:
        snap = self.mod.xml_snapshot(self.tmp / "nope", time.time())
        self.assertEqual(snap["files"], 0)
        self.assertEqual(snap["tests"], 0)


class SummaryVerdict(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def _sample(self, rc: int, result: str, wall: float, executed: int) -> dict:
        return {"exit_code": rc, "result": result, "wall_s": wall,
                "executed": executed, "from_cache": 0, "up_to_date": 0}

    def test_failed_sample_makes_the_summary_fail(self) -> None:
        samples = [self._sample(0, "SUCCESS", 10.0, 3),
                   self._sample(1, "FAILED", 2.0, 1)]
        s = self.mod.summarize(":x:test", samples, {"rewritten_files": 1},
                               require_work=False)
        self.assertEqual(s["verdict"], "FAIL")
        self.assertEqual(s["failures"], 1)
        self.assertEqual(s["wall_s"]["median"], 6.0)
        self.assertTrue(s["small_sample"])

    def test_cache_warm_sample_is_marked_workless(self) -> None:
        samples = [self._sample(0, "SUCCESS", 0.4, 0)]
        s = self.mod.summarize(":x:test", samples, {"files": 0, "rewritten_files": 0, "tests": 0, "failures": 0,
                    "errors": 0, "skipped": 0, "unreadable_files": 0},
                               require_work=True)
        self.assertFalse(s["work_executed"])


class MainFlow(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="baseline-main-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def _run(self, argv, runner):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = self.mod.main(argv, runner=runner)
        return rc, out.getvalue()

    def test_failed_runs_are_recorded_and_exit_nonzero(self) -> None:
        def runner(_argv):
            return 1, FAILED

        out_path = self.tmp / "report.json"
        rc, out = self._run(["--task", ":x:test", "--runs", "2",
                             "--results-dir", str(self.tmp),
                             "--out", str(out_path)], runner)
        self.assertEqual(rc, 1, out)
        report = json.loads(out_path.read_text())
        summary = report["tasks"][0]["summary"]
        self.assertEqual(summary["verdict"], "FAIL")
        self.assertEqual(summary["failures"], 2)
        self.assertIn("run 2: exit=1 FAILED", out)

    def test_require_work_refuses_cache_warm_samples(self) -> None:
        def runner(_argv):
            return 0, UP_TO_DATE_ONLY

        out_path = self.tmp / "report.json"
        rc, out = self._run(["--task", ":x:test", "--runs", "2",
                             "--results-dir", str(self.tmp),
                             "--require-work", "--out", str(out_path)], runner)
        self.assertEqual(rc, 2)
        report = json.loads(out_path.read_text())
        self.assertEqual(report["tasks"][0]["summary"]["verdict"],
                         "REFUSED_NO_WORK")

    def test_successful_runs_exit_zero(self) -> None:
        def runner(_argv):
            return 0, SUCCESS

        rc, out = self._run(["--task", ":x:test", "--runs", "3",
                             "--results-dir", str(self.tmp)], runner)
        self.assertEqual(rc, 0, out)
        self.assertIn("verdict=PASS", out)

    def test_zero_runs_is_refused(self) -> None:
        rc, _ = self._run(["--task", ":x:test", "--runs", "0"], lambda _a: (0, ""))
        self.assertEqual(rc, 2)



class PercentileTests(unittest.TestCase):
    """Claim: the baseline names P50/P95 with a stated definition.

    The number is the product of this tool and it drives rule 4's budget, so the
    statistic is pinned directly instead of being trusted.
    """

    def test_percentile_interpolates_between_ranks(self):
        """Mutation: return ordered[round(rank)] -> the p95 of the measured
        sample jumps by up to a full sample and the budget drifts with it."""
        mod = load_module()
        values = [1.0, 2.0, 3.0, 4.0, 18.674]
        # rank = 4 * 0.95 = 3.8 -> 4.0 + (18.674 - 4.0) * 0.8
        self.assertAlmostEqual(mod.percentile(values, 95), 15.739, places=3)

    def test_percentile_of_single_value_is_that_value(self):
        mod = load_module()
        self.assertEqual(mod.percentile([2.5], 95), 2.5)

    def test_percentile_of_empty_is_none(self):
        mod = load_module()
        self.assertIsNone(mod.percentile([], 95))

    def test_summary_flags_p95_unreliable_below_twenty_runs(self):
        """Mutation: report p95 without the flag -> a five-sample maximum reads
        as a tail estimate and a reader derives a budget from noise."""
        mod = load_module()
        samples = [{"wall_s": float(i), "reported_s": 1.0, "executed": 0,
                    "from_cache": 0, "up_to_date": 1, "exit_code": 0,
                    "result": "SUCCESS"} for i in range(1, 6)]
        summary = mod.summarize(":m:detekt", samples, {"files": 0, "rewritten_files": 0, "tests": 0, "failures": 0,
                    "errors": 0, "skipped": 0, "unreadable_files": 0}, require_work=False)
        self.assertEqual(summary["runs"], 5)
        self.assertFalse(summary["p95_reliable"])
        self.assertEqual(summary["wall_s"]["p50"], 3.0)
        self.assertIsNotNone(summary["wall_s"]["mean"])
        self.assertIsNotNone(summary["wall_s"]["stdev"])
        report = mod.format_report(":m:detekt", samples, summary)
        self.assertIn("p50=3.0s", report)
        self.assertIn("p95 unreliable below 20 runs", report)

    def test_twenty_runs_make_p95_reliable(self):
        mod = load_module()
        samples = [{"wall_s": 1.0, "reported_s": 1.0, "executed": 0,
                    "from_cache": 0, "up_to_date": 1, "exit_code": 0,
                    "result": "SUCCESS"} for _ in range(20)]
        summary = mod.summarize(":m:detekt", samples, {"files": 0, "rewritten_files": 0, "tests": 0, "failures": 0,
                    "errors": 0, "skipped": 0, "unreadable_files": 0}, require_work=False)
        self.assertTrue(summary["p95_reliable"])
        self.assertNotIn("unreliable", mod.format_report(":m:detekt", samples, summary))

    def test_rerun_mode_forces_the_task_and_says_so(self):
        """Mutation: skip --rerun-tasks -> the sample reports up-to-date counts
        and the XML it reads belongs to an earlier run (the instrument then calls
        that a baseline of execution, which it is not)."""
        mod = load_module()
        seen = {}

        def runner(argv):
            seen["argv"] = list(argv)
            return 0, "BUILD SUCCESSFUL in 1s\n8 actionable tasks: 8 executed"

        sample = mod.run_once(":m:test", pathlib.Path("."), profile=False,
                              timeout_s=5, runner=runner, rerun=True)
        self.assertIn("--rerun-tasks", seen["argv"])
        self.assertEqual(sample["executed"], 8)
        sample = mod.run_once(":m:test", pathlib.Path("."), profile=False,
                              timeout_s=5, runner=runner, rerun=False)
        self.assertNotIn("--rerun-tasks", seen["argv"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
