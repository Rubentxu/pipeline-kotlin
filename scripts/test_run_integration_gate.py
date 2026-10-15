#!/usr/bin/env python3
"""Behavioural tests for run-integration-gate.py.

Hermetic: the phase runner and the evidence collector are injected; the plan is
driven against a temporary git repository so the real planner code runs.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import pathlib
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "run-integration-gate.py"


def load_module():
    spec = importlib.util.spec_from_file_location("run_integration_gate", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Verdicts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def _phase(self, name, rc, ran=True):
        return {"phase": name, "argv": [], "exit_code": rc, "duration_s": 0.0,
                "ran": ran}

    def test_failed_phase_makes_fail(self) -> None:
        phases = [self._phase("fitness", 1)]
        self.assertEqual(self.mod.verdict_of(phases, None, None), "FAIL")

    def test_blocked_reason_wins(self) -> None:
        phases = [self._phase("fitness", 0)]
        self.assertEqual(self.mod.verdict_of(phases, None, "unclassified: x"),
                         "BLOCKED")

    def test_gradle_without_fresh_evidence_is_stale(self) -> None:
        """Claim: a Gradle phase that produced no fresh XML is not a pass.

        Mutation: return PASS when phases exit 0; a cache-warm or misdirected
        run would certify an integration gate with no new evidence.
        """
        phases = [self._phase("fitness", 0), self._phase("gradle", 0)]
        self.assertEqual(self.mod.verdict_of(
            phases, {"fresh_files": 0}, None), "STALE")
        self.assertEqual(self.mod.verdict_of(
            phases, {"fresh_files": 3}, None), "PASS")

    def test_not_run_gradle_is_not_stale(self) -> None:
        phases = [self._phase("fitness", 0), self._phase("gradle", 0, ran=False)]
        self.assertEqual(self.mod.verdict_of(phases, None, None), "PASS")


class Roots(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def test_module_tasks_map_to_their_results_dirs(self) -> None:
        roots = self.mod.results_roots_for([":a:test", ":b:test"], pathlib.Path("/r"))
        self.assertEqual(roots, [pathlib.Path("/r/v2/a/build/test-results/test"),
                                 pathlib.Path("/r/v2/b/build/test-results/test")])

    def test_non_module_task_falls_back_to_v2(self) -> None:
        roots = self.mod.results_roots_for(["check"], pathlib.Path("/r"))
        self.assertEqual(roots, [pathlib.Path("/r/v2")])

    def test_fresh_evidence_counts_only_files_inside_the_boundary(self) -> None:
        """Claim: freshness is a measurement, not an assumption."""
        tmp = pathlib.Path(tempfile.mkdtemp(prefix="intg-evidence-"))
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        d = tmp / "build" / "test-results" / "test"
        d.mkdir(parents=True)
        (d / "TEST-A.xml").write_text(
            '<testsuite name="A" tests="2" failures="0" errors="0" skipped="0"/>')
        past = time.time() - 600
        ev = self.mod.fresh_evidence([tmp], past)
        self.assertEqual(ev["fresh_files"], 1)
        self.assertEqual(ev["fresh"]["tests"], 2)
        future = time.time() + 600
        ev2 = self.mod.fresh_evidence([tmp], future)
        self.assertEqual(ev2["fresh_files"], 0)
        self.assertEqual(ev2["carried_over_files"], 1)


class MainFlow(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def setUp(self) -> None:
        self.repo = pathlib.Path(tempfile.mkdtemp(prefix="intg-repo-"))
        self.addCleanup(shutil.rmtree, self.repo, ignore_errors=True)
        subprocess.run(["git", "-C", str(self.repo), "init", "-q",
                        "--initial-branch=main"], check=True)
        subprocess.run(["git", "-C", str(self.repo), "config", "user.email", "t@t"],
                       check=True)
        subprocess.run(["git", "-C", str(self.repo), "config", "user.name", "t"],
                       check=True)
        mod_dir = self.repo / "v2" / "pipeline-domain"
        mod_dir.mkdir(parents=True)
        (mod_dir / "build.gradle.kts").write_text("plugins { }\n")
        (mod_dir / "src.kt").write_text("val x = 1\n")
        subprocess.run(["git", "-C", str(self.repo), "add", "-A"], check=True)
        subprocess.run(["git", "-C", str(self.repo), "commit", "-q", "-m", "init"],
                       check=True)

    def _main(self, argv, runner, evidence_fn=None):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = self.mod.main(argv, runner=runner, evidence_fn=evidence_fn)
        return rc, out.getvalue()

    def _runner(self, exits):
        calls = []

        def classify(argv):
            if argv[0].endswith("gradlew"):
                return "gradle"
            for arg in argv:
                if arg.endswith("fitness-harness-coverage.py"):
                    return "fitness"
                if arg.endswith(".py") and arg.startswith("scripts/"):
                    return "python"
            return "other"

        def run(argv):
            calls.append(argv)
            return exits.get(classify(argv), 0), ""

        return run, calls

    def test_pass_receipt_binds_the_plan_and_evidence(self) -> None:
        (self.repo / "v2" / "pipeline-domain" / "src.kt").write_text("val x = 2\n")
        runner, calls = self._runner({})
        evidence = {"fresh_files": 4, "carried_over_files": 0,
                    "fresh": {"tests": 9, "failures": 0, "errors": 0, "skipped": 0}}
        out_path = self.repo / "receipt.json"
        rc, out = self._main(["--root", str(self.repo), "--base", "HEAD",
                              "--out", str(out_path)], runner,
                             evidence_fn=lambda roots, b: evidence)
        self.assertEqual(rc, 0, out)
        receipt = json.loads(out_path.read_text())
        self.assertEqual(receipt["verdict"], "PASS")
        self.assertEqual(receipt["plan"]["gradle_tasks"], [":pipeline-domain:test"])
        gradle_argv = [p["argv"] for p in receipt["phases"] if p["phase"] == "gradle"][0]
        self.assertIn(":pipeline-domain:test", gradle_argv)
        self.assertTrue(any(a.startswith("-Pshard.excludes=") for a in gradle_argv))
        self.assertNotIn("--rerun-tasks", gradle_argv)
        self.assertIn("fresh XML: 4 file(s)", out)

    def test_failing_fitness_phase_fails_the_gate(self) -> None:
        (self.repo / "v2" / "pipeline-domain" / "src.kt").write_text("val x = 3\n")
        runner, _ = self._runner({"fitness": 2})
        rc, out = self._main(["--root", str(self.repo), "--base", "HEAD"], runner,
                             evidence_fn=lambda roots, b: {"fresh_files": 1,
                                                           "carried_over_files": 0,
                                                           "fresh": {}})
        self.assertEqual(rc, 1)
        self.assertIn("verdict=FAIL", out)

    def test_gradle_without_fresh_evidence_is_stale(self) -> None:
        (self.repo / "v2" / "pipeline-domain" / "src.kt").write_text("val x = 4\n")
        runner, _ = self._runner({})
        rc, out = self._main(["--root", str(self.repo), "--base", "HEAD"], runner,
                             evidence_fn=lambda roots, b: {"fresh_files": 0,
                                                           "carried_over_files": 2,
                                                           "fresh": {}})
        self.assertEqual(rc, 3)
        self.assertIn("verdict=STALE", out)

    def test_expect_sha_mismatch_is_blocked(self) -> None:
        runner, calls = self._runner({})
        rc, out = self._main(["--root", str(self.repo), "--base", "HEAD",
                              "--expect-sha", "0" * 40], runner)
        self.assertEqual(rc, 2)
        self.assertIn("BLOCKED", out)
        self.assertEqual(calls, [], "a blocked gate must not run any phase")

    def test_unclassified_path_is_blocked(self) -> None:
        cfg = self.repo / "v2" / "config"
        cfg.mkdir()
        (cfg / "x.yml").write_text("a: 1\n")
        runner, calls = self._runner({})
        rc, out = self._main(["--root", str(self.repo), "--base", "HEAD"], runner)
        self.assertEqual(rc, 2)
        self.assertIn("unclassified paths", out)

    def test_docs_only_plan_skips_gradle_as_not_run(self) -> None:
        (self.repo / "NOTES.md").write_text("docs\n")
        subprocess.run(["git", "-C", str(self.repo), "add", "-A"], check=True)
        subprocess.run(["git", "-C", str(self.repo), "commit", "-q", "-m", "docs"],
                       check=True)
        runner, calls = self._runner({})
        out_path = self.repo / "receipt.json"
        rc, out = self._main(["--root", str(self.repo), "--base", "HEAD~1",
                              "--out", str(out_path)], runner)
        self.assertEqual(rc, 0, out)
        receipt = json.loads(out_path.read_text())
        gradle = [p for p in receipt["phases"] if p["phase"] == "gradle"][0]
        self.assertFalse(gradle["ran"])
        self.assertEqual(receipt["verdict"], "PASS")
        self.assertFalse(any(argv[0].endswith("gradlew") for argv in calls))


if __name__ == "__main__":
    unittest.main(verbosity=2)
