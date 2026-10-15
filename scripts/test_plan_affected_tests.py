#!/usr/bin/env python3
"""Behavioural tests for plan-affected-tests.py.

Pure planner tested against a hermetic fixture tree; the git path is exercised
with an injected file list so nothing here depends on repository state.
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
SCRIPT = ROOT / "scripts" / "plan-affected-tests.py"


def load_module():
    spec = importlib.util.spec_from_file_location("plan_affected_tests", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class PlannerHarness(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="planner-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        for module in ("pipeline-domain", "pipeline-application", "pipeline-events",
                       "pipeline-architecture-tests", "pipeline-step-sdk/runtime"):
            p = self.tmp / "v2" / module / "build.gradle.kts"
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("plugins { }\n")
        self.tmp.joinpath("scripts").mkdir()
        self.tmp.joinpath("scripts", "tool-x.py").write_text("print(1)\n")
        self.tmp.joinpath("scripts", "test_tool_x.py").write_text("def test_ok():\n    pass\n")
        self.tmp.joinpath("scripts", "no-suite.py").write_text("print(2)\n")

    def plan(self, files):
        return self.mod.plan(files, self.tmp)


class Mapping(PlannerHarness):
    def test_docs_only(self) -> None:
        r = self.plan(["docs/v2/05-roadmap/ROADMAP.md", "README.md"])
        self.assertTrue(r["docs_only"])
        self.assertEqual(r["gradle_tasks"], [])

    def test_module_maps_to_its_test_task(self) -> None:
        r = self.plan(["v2/pipeline-domain/src/main/kotlin/X.kt"])
        self.assertEqual(r["gradle_tasks"], [":pipeline-domain:test"])

    def test_step_sdk_submodule_adds_the_consumer_suite(self) -> None:
        r = self.plan(["v2/pipeline-step-sdk/runtime/src/test/A.kt"])
        self.assertEqual(r["gradle_tasks"],
                         [":pipeline-application:test", ":pipeline-step-sdk:runtime:test"])

    def test_events_pulls_architecture_fitness(self) -> None:
        r = self.plan(["v2/pipeline-events/src/main/kotlin/E.kt"])
        self.assertEqual(r["gradle_tasks"],
                         [":pipeline-architecture-tests:test", ":pipeline-events:test"])

    def test_corpus_fixture_uses_the_application_suite(self) -> None:
        r = self.plan(["v2/compatibility/11-workflow-control.pipeline.kts"])
        self.assertEqual(r["gradle_tasks"], [":pipeline-application:test"])

    def test_build_system_means_full_gate(self) -> None:
        """Claim: a build-system edit escalates instead of picking a module.

        Mutation: map v2/settings.gradle.kts through the module fallback; it
        would plan a bogus :settings.gradle.kts:test (the defect fixed earlier
        in the bash mapping).
        """
        r = self.plan(["v2/settings.gradle.kts", "v2/pipeline-domain/X.kt"])
        self.assertTrue(r["full_gate"])
        self.assertEqual(r["gradle_tasks"], ["check"])

    def test_tooling_only_paths_plan_nothing(self) -> None:
        r = self.plan(["justfile", ".agent/TESTING-STATE.md"])
        self.assertFalse(r["docs_only"])
        self.assertEqual(r["gradle_tasks"], [])
        self.assertEqual(r["python_suites"], [])


class Scripts(PlannerHarness):
    def test_tool_maps_to_its_suite(self) -> None:
        r = self.plan(["scripts/tool-x.py"])
        self.assertEqual(r["python_suites"], ["scripts/test_tool_x.py"])
        self.assertEqual(r["unclassified"], [])

    def test_suite_change_runs_itself(self) -> None:
        r = self.plan(["scripts/test_tool_x.py"])
        self.assertEqual(r["python_suites"], ["scripts/test_tool_x.py"])

    def test_tool_without_suite_is_unclassified(self) -> None:
        """Claim: touching an untested tool is flagged, not silently skipped."""
        r = self.plan(["scripts/no-suite.py"])
        self.assertEqual(r["python_suites"], [])
        self.assertEqual(r["unclassified"], ["scripts/no-suite.py"])


class Unclassified(PlannerHarness):
    def test_v2_path_without_a_module_is_unclassified(self) -> None:
        r = self.plan(["v2/config/detekt/detekt.yml"])
        self.assertEqual(r["unclassified"], ["v2/config/detekt/detekt.yml"])

    def test_unknown_step_sdk_submodule_is_unclassified(self) -> None:
        r = self.plan(["v2/pipeline-step-sdk/nope/X.kt"])
        self.assertEqual(r["unclassified"], ["v2/pipeline-step-sdk/nope/X.kt"])

    def test_main_exits_2_on_unclassified(self) -> None:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = self.mod.main(["--root", str(self.tmp), "--files", "v2/config/x.yml"])
        self.assertEqual(rc, 2)
        self.assertIn("unclassified productive path", err.getvalue())

    def test_main_json_and_args_formats(self) -> None:
        argv = ["--root", str(self.tmp), "--files", "v2/pipeline-domain/A.kt"]
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(self.mod.main(argv + ["--format", "json"]), 0)
        data = json.loads(out.getvalue())
        self.assertEqual(data["gradle_tasks"], [":pipeline-domain:test"])
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(self.mod.main(argv + ["--format", "args"]), 0)
        self.assertEqual(out.getvalue().strip(), ":pipeline-domain:test")

    def test_union_of_modules_is_deduplicated_and_sorted(self) -> None:
        r = self.plan(["v2/pipeline-domain/A.kt", "v2/pipeline-domain/B.kt",
                       "v2/pipeline-application/C.kt"])
        self.assertEqual(r["gradle_tasks"],
                         [":pipeline-application:test", ":pipeline-domain:test"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
