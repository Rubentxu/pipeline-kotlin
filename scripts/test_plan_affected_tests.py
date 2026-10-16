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
        # ABI dump for one module only, so "apiCheck appears exactly where the
        # module publishes a binary-compatibility dump" is observable.
        api = self.tmp / "v2" / "pipeline-domain" / "api"
        api.mkdir(parents=True)
        (api / "pipeline-domain.api").write_text("public final class A {}\n")
        # Consumer relation for the merge profile: the application consumes domain.
        (self.tmp / "v2" / "pipeline-application" / "build.gradle.kts").write_text(
            'plugins { }\ndependencies { implementation(project(":pipeline-domain")) }\n')
        self.tmp.joinpath("scripts").mkdir()
        self.tmp.joinpath("scripts", "tool-x.py").write_text("print(1)\n")
        self.tmp.joinpath("scripts", "test_tool_x.py").write_text("def test_ok():\n    pass\n")
        self.tmp.joinpath("scripts", "no-suite.py").write_text("print(2)\n")

    def plan(self, files, *, profile="dev"):
        return self.mod.plan(files, self.tmp, profile=profile)


class Mapping(PlannerHarness):
    def test_docs_only(self) -> None:
        r = self.plan(["docs/v2/05-roadmap/ROADMAP.md", "README.md"])
        self.assertTrue(r["docs_only"])
        self.assertEqual(r["gradle_tasks"], [])

    def test_module_maps_to_its_test_task(self) -> None:
        r = self.plan(["v2/pipeline-domain/src/main/kotlin/X.kt"])
        # test + lint (detekt) + ABI (apiCheck, because the module has a dump).
        self.assertEqual(r["gradle_tasks"], [":pipeline-domain:apiCheck",
                                              ":pipeline-domain:detekt",
                                              ":pipeline-domain:test"])

    def test_apicheck_only_where_a_dump_exists(self) -> None:
        """Mutation: add apiCheck to every module -> the gate fails for modules
        that never declared a binary-compatibility surface."""
        r = self.plan(["v2/pipeline-application/src/main/kotlin/X.kt"])
        self.assertNotIn(":pipeline-application:apiCheck", r["gradle_tasks"])
        self.assertIn(":pipeline-application:detekt", r["gradle_tasks"])

    def test_merge_profile_adds_direct_consumers(self) -> None:
        """Claim: the merge gate re-runs the code that consumes what changed.

        Mutation: hand-maintain a consumer table -> it drifts from the build
        files; here the relation is read from `implementation(project(...))`.
        """
        files = ["v2/pipeline-domain/src/main/kotlin/X.kt"]
        dev = self.plan(files)
        merge = self.plan(files, profile="merge")
        self.assertEqual(dev["consumers"], [], "dev stays bounded")
        self.assertEqual(merge["consumers"], ["pipeline-application"])
        self.assertIn(":pipeline-application:test", merge["gradle_tasks"])
        self.assertNotIn(":pipeline-application:test", dev["gradle_tasks"])
        self.assertEqual(dev["profile"], "dev")
        self.assertEqual(merge["profile"], "merge")

    def test_merge_profile_does_not_recurse_without_bound(self) -> None:
        """Mutation: take the transitive closure -> the merge gate becomes the
        round gate and the ladder loses its middle rung."""
        (self.tmp / "v2" / "pipeline-events" / "build.gradle.kts").write_text(
            'dependencies { implementation(project(":pipeline-application")) }\n')
        merge = self.plan(["v2/pipeline-domain/src/main/kotlin/X.kt"], profile="merge")
        self.assertEqual(merge["consumers"], ["pipeline-application"])
        self.assertNotIn(":pipeline-events:test", merge["gradle_tasks"])

    def test_step_sdk_submodule_adds_the_consumer_suite(self) -> None:
        r = self.plan(["v2/pipeline-step-sdk/runtime/src/test/A.kt"])
        self.assertEqual(r["gradle_tasks"],
                         [":pipeline-application:detekt", ":pipeline-application:test",
                          ":pipeline-step-sdk:runtime:detekt",
                          ":pipeline-step-sdk:runtime:test"])

    def test_events_pulls_architecture_fitness(self) -> None:
        r = self.plan(["v2/pipeline-events/src/main/kotlin/E.kt"])
        # The architecture suite is an extra task for this module, not a touched
        # module, so it gets no lint task of its own.
        self.assertEqual(r["gradle_tasks"],
                         [":pipeline-architecture-tests:test",
                          ":pipeline-events:detekt", ":pipeline-events:test"])

    def test_corpus_fixture_uses_the_application_suite(self) -> None:
        r = self.plan(["v2/compatibility/11-workflow-control.pipeline.kts"])
        self.assertEqual(r["gradle_tasks"],
                         [":pipeline-application:detekt", ":pipeline-application:test"])

    def test_build_system_means_full_gate(self) -> None:
        """Claim: a build-system edit escalates instead of picking a module.

        Mutation: map v2/settings.gradle.kts through the module fallback; it
        would plan a bogus :settings.gradle.kts:test (the defect fixed earlier
        in the bash mapping).
        """
        r = self.plan(["v2/settings.gradle.kts", "v2/pipeline-domain/X.kt"])
        self.assertTrue(r["full_gate"])
        self.assertEqual(r["gradle_tasks"], ["check"])
        self.assertEqual(r["touched_modules"], [],
                         "the full gate already covers lint, ABI and consumers")

    def test_tooling_only_paths_plan_nothing(self) -> None:
        r = self.plan(["justfile", ".agent/TESTING-STATE.md"])
        self.assertFalse(r["docs_only"])
        self.assertEqual(r["gradle_tasks"], [])
        self.assertEqual(r["python_suites"], [])

    def test_harness_governance_maps_to_the_catalogue_suite(self) -> None:
        """Mutation: leave `v2/test-harness/` unclassified -> every catalogue
        change returns BLOCKED, and a listed-but-unmapped path is how the gate
        stops being trusted."""
        suite = self.tmp / "scripts" / "test_gen_test_harness_catalogue.py"
        suite.write_text("def test_ok():\n    pass\n")
        r = self.plan(["v2/test-harness/catalogue.json",
                       "v2/test-harness/CATALOGUE.md",
                       "v2/test-harness/README.md"])
        self.assertEqual(r["unclassified"], [])
        self.assertEqual(r["python_suites"],
                         ["scripts/test_gen_test_harness_catalogue.py"])
        self.assertEqual(r["gradle_tasks"], [])

    def test_build_config_escalates_to_the_round_gate(self) -> None:
        """Mutation: treat v2/config as a module -> a global lint-rule change
        plans one module's tests and the rest of the repository is unchecked."""
        r = self.plan(["v2/config/detekt/detekt.yml"])
        self.assertTrue(r["full_gate"])
        self.assertEqual(r["gradle_tasks"], ["check"])
        self.assertEqual(r["unclassified"], [])


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
        # NB: `v2/config/**` used to be the example here; it is now classified as
        # build configuration (full round gate), which is strictly stronger than
        # refusing it. A path with no classification at all still fails closed.
        r = self.plan(["v2/not-a-module/thing.yml"])
        self.assertEqual(r["unclassified"], ["v2/not-a-module/thing.yml"])

    def test_unknown_step_sdk_submodule_is_unclassified(self) -> None:
        r = self.plan(["v2/pipeline-step-sdk/nope/X.kt"])
        self.assertEqual(r["unclassified"], ["v2/pipeline-step-sdk/nope/X.kt"])

    def test_main_exits_2_on_unclassified(self) -> None:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = self.mod.main(["--root", str(self.tmp), "--files",
                                "v2/not-a-module/x.yml"])
        self.assertEqual(rc, 2)
        self.assertIn("unclassified productive path", err.getvalue())

    def test_main_json_and_args_formats(self) -> None:
        argv = ["--root", str(self.tmp), "--files", "v2/pipeline-domain/A.kt"]
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(self.mod.main(argv + ["--format", "json"]), 0)
        data = json.loads(out.getvalue())
        self.assertEqual(data["gradle_tasks"], [":pipeline-domain:apiCheck",
                                                 ":pipeline-domain:detekt",
                                                 ":pipeline-domain:test"])
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(self.mod.main(argv + ["--format", "args"]), 0)
        self.assertEqual(out.getvalue().strip(),
                         ":pipeline-domain:apiCheck :pipeline-domain:detekt "
                         ":pipeline-domain:test")

    def test_union_of_modules_is_deduplicated_and_sorted(self) -> None:
        r = self.plan(["v2/pipeline-domain/A.kt", "v2/pipeline-domain/B.kt",
                       "v2/pipeline-application/C.kt"])
        self.assertEqual(r["gradle_tasks"],
                         [":pipeline-application:detekt", ":pipeline-application:test",
                          ":pipeline-domain:apiCheck", ":pipeline-domain:detekt",
                          ":pipeline-domain:test"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
