#!/usr/bin/env python3
"""Behavioural tests for audit-gradle-test-graph.py.

Hermetic: the dry-run runner is injected, so no Gradle build is configured here.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import pathlib
import shutil
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "audit-gradle-test-graph.py"

DRY_RUN_DOMAIN = """
> Task :buildSrc:jar UP-TO-DATE
:pipeline-domain:compileKotlin SKIPPED
:pipeline-domain:testClasses SKIPPED
:pipeline-domain:test SKIPPED

BUILD SUCCESSFUL in 1s
"""

DRY_RUN_APP_HEAVY = """
:pipeline-application:installDist SKIPPED
:pipeline-application:jar SKIPPED
:pipeline-application:compileTestKotlin SKIPPED
:pipeline-application:test SKIPPED

BUILD SUCCESSFUL in 1s
"""

DRY_RUN_RELEASE_WITH_TESTS = """
:pipeline-release:compileTestKotlin SKIPPED
:pipeline-application:test SKIPPED
:pipeline-release:test SKIPPED

BUILD SUCCESSFUL in 1s
"""


def load_module():
    spec = importlib.util.spec_from_file_location("audit_gradle_test_graph", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Parsing(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def test_only_skipped_tasks_are_the_graph(self) -> None:
        tasks = self.mod.parse_dry_run(DRY_RUN_DOMAIN)
        self.assertIn(":pipeline-domain:test", tasks)
        self.assertNotIn(":buildSrc:jar", tasks,
                         "a buildSrc task with a real state is not part of the graph")

    def test_heavy_detection(self) -> None:
        tasks = self.mod.parse_dry_run(DRY_RUN_APP_HEAVY)
        self.assertEqual(self.mod.heavy_tasks(tasks),
                         [":pipeline-application:installDist"])

    def test_test_on_test_detection(self) -> None:
        tasks = self.mod.parse_dry_run(DRY_RUN_RELEASE_WITH_TESTS)
        self.assertEqual(self.mod.test_on_test(tasks, ":pipeline-release:test"),
                         [":pipeline-application:test"])


class Findings(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def test_heavy_in_test_and_duplicate_heavy(self) -> None:
        """Claim: rebuilding a distribution inside two test graphs is visible.

        Mutation: drop the duplication pass; the same installDist reachable from
        two test tasks stops being reported.
        """
        graphs = {
            ":a:test": [":a:installDist", ":a:test"],
            ":b:test": [":a:installDist", ":b:test"],
        }
        findings = self.mod.findings_for_graphs(graphs)
        classes = {f["class"] for f in findings}
        self.assertIn("HEAVY_IN_TEST", classes)
        self.assertIn("DUPLICATE_HEAVY", classes)
        dup = [f for f in findings if f["class"] == "DUPLICATE_HEAVY"][0]
        self.assertEqual(dup["task"], ":a:installDist")
        self.assertEqual(dup["detail"], [":a:test", ":b:test"])

    def test_nested_test_task_is_reported(self) -> None:
        graphs = {":release:test": [":app:test", ":release:test"]}
        findings = self.mod.findings_for_graphs(graphs)
        self.assertEqual([f["class"] for f in findings], ["TEST_ON_TEST"])
        self.assertEqual(findings[0]["detail"], [":app:test"])

    def test_clean_graphs_have_no_findings(self) -> None:
        graphs = {":a:test": [":a:compileKotlin", ":a:test"]}
        self.assertEqual(self.mod.findings_for_graphs(graphs), [])


class ForkPolicy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="graph-audit-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def _write(self, rel: str, body: str) -> None:
        p = self.tmp / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body)

    def test_policy_is_parsed_per_module(self) -> None:
        self._write("v2/mod-a/build.gradle.kts",
                    'tasks.test { useJUnitPlatform()\nmaxParallelForks = 1\nforkEvery = 1\n }')
        self._write("v2/mod-b/build.gradle.kts",
                    'tasks.test { useJUnitPlatform() }')
        self._write("v2/mod-c/build.gradle.kts", "plugins { }")
        rows = {r["module"]: r for r in self.mod.scan_fork_policy(self.tmp)}
        self.assertEqual(rows["v2/mod-a/build.gradle.kts"]["maxParallelForks"], 1)
        self.assertEqual(rows["v2/mod-a/build.gradle.kts"]["forkEvery"], 1)
        self.assertTrue(rows["v2/mod-a/build.gradle.kts"]["useJUnitPlatform"])
        self.assertIsNone(rows["v2/mod-b/build.gradle.kts"]["forkEvery"])
        self.assertNotIn("v2/mod-c/build.gradle.kts", rows,
                         "a module without a test task is out of scope")


class MainFlow(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="graph-main-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def _runner(self, table):
        def run(argv):
            task = argv[3]
            return 0, table[task]
        return run

    def _run(self, argv, runner):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = self.mod.main(argv, runner=runner)
        return rc, out.getvalue()

    def test_audit_reports_and_stays_green_by_default(self) -> None:
        table = {":a:test": DRY_RUN_APP_HEAVY, ":b:test": DRY_RUN_DOMAIN}
        rc, out = self._run(["--root", str(self.tmp), "--task", ":a:test",
                             "--task", ":b:test"],
                            self._runner(table))
        self.assertEqual(rc, 0)
        self.assertIn("[HEAVY_IN_TEST] :a:test", out)

    def test_fail_on_turns_a_class_into_a_gate(self) -> None:
        """Claim: findings can be ratcheted without editing the tool.

        Mutation: ignore --fail-on; a gate built on this audit would always pass.
        """
        table = {":a:test": DRY_RUN_APP_HEAVY}
        rc, out = self._run(["--root", str(self.tmp), "--task", ":a:test",
                             "--fail-on", "HEAVY_IN_TEST"],
                            self._runner(table))
        self.assertEqual(rc, 2)

    def test_json_output_carries_graphs_and_findings(self) -> None:
        table = {":a:test": DRY_RUN_RELEASE_WITH_TESTS}
        out_path = self.tmp / "audit.json"
        rc, _ = self._run(["--root", str(self.tmp), "--task", ":a:test",
                           "--json", str(out_path)],
                          self._runner(table))
        self.assertEqual(rc, 0)
        data = json.loads(out_path.read_text())
        self.assertEqual(data["findings"][0]["class"], "TEST_ON_TEST")
        self.assertIn(":a:test", data["graphs"])

    def test_dry_run_failure_is_an_error_not_a_silent_pass(self) -> None:
        def failing(_argv):
            return 1, "boom"
        with self.assertRaises(RuntimeError):
            self.mod.dry_run(":a:test", self.tmp, timeout_s=1, runner=failing)


if __name__ == "__main__":
    unittest.main(verbosity=2)
