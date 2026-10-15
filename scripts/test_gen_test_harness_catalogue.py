#!/usr/bin/env python3
"""Behavioural tests for gen-test-harness-catalogue.py.

The generator is a gate: it refuses to write the catalogue when a module has no
impact criterion, when a role is claimed twice, when a gate's budget drifted from
its recipe, when the UAT frontier is hardcoded instead of bound to the justfile
variable, when a recipe nests Gradle, or when a fork policy has no declared
reason. These tests pin each rule with the mutation that would silence it, using
a hermetic temp repository and the real module (imported, not reimplemented).

The pure helpers (`count_gradle_invocations`, `fork_policy`) are tested directly
because a wrong counter silently blesses every gate as "0 calls" — which is
exactly the bug this file was written after.
"""
from __future__ import annotations

import importlib.util
import io
import pathlib
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "gen-test-harness-catalogue.py"


def load_module():
    spec = importlib.util.spec_from_file_location("gthc", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class GradleInvocationCounterTests(unittest.TestCase):
    """V6 has to count executed invocations, not mentions."""

    def test_interpolated_timeout_is_counted(self):
        """Mutation: require a numeric timeout (the original bug) -> every gate
        reports 0 calls and V6 can never fire again."""
        mod = load_module()
        body = ("@gate budget=\"1270\":\n"
                "    timeout {{ budget }} v2/gradlew -p v2 check\n")
        self.assertEqual(mod.count_gradle_invocations(body), 1)

    def test_literal_timeout_is_counted(self):
        mod = load_module()
        body = "    timeout 600 v2/gradlew -p v2 :mod:test\n"
        self.assertEqual(mod.count_gradle_invocations(body), 1)

    def test_echo_mention_is_not_counted(self):
        """Mutation: count any occurrence of `gradlew` -> the dev recipe fails
        while it is correct (it prints its plan before running it)."""
        mod = load_module()
        body = ("        echo \"dev> v2/gradlew -p v2 $g\"\n"
                "        timeout {{ budget }} v2/gradlew -p v2 $g\n")
        self.assertEqual(mod.count_gradle_invocations(body), 1)

    def test_two_executed_invocations_are_counted(self):
        """Mutation: stop counting after the first match -> a nested graph (two
        Gradle invocations in one phase) passes silently."""
        mod = load_module()
        body = ("    timeout 600 v2/gradlew -p v2 :a:test\n"
                "    ./gradlew -p v2 :b:test\n")
        self.assertEqual(mod.count_gradle_invocations(body), 2)

    def test_comment_and_shebang_are_not_counted(self):
        mod = load_module()
        body = ("#!/usr/bin/env bash\n"
                "    # historical: used to call v2/gradlew -p v2 check\n"
                "    python3 scripts/whatever.py\n")
        self.assertEqual(mod.count_gradle_invocations(body), 0)


class ForkPolicyTests(unittest.TestCase):
    def test_fork_policy_reads_fork_every_and_max_parallel_forks(self):
        mod = load_module()
        with tempfile.TemporaryDirectory() as tmp:
            mod.ROOT = pathlib.Path(tmp)
            module_dir = pathlib.Path(tmp) / "v2" / "mod-a"
            module_dir.mkdir(parents=True)
            (module_dir / "build.gradle.kts").write_text(
                'tasks.test {\n  maxParallelForks = 1\n  forkEvery = 1\n}\n')
            policy = mod.fork_policy("v2/mod-a")
        self.assertIn("forkEvery=1", policy)
        self.assertIn("maxParallelForks=1", policy)

    def test_default_policy_is_none(self):
        mod = load_module()
        with tempfile.TemporaryDirectory() as tmp:
            mod.ROOT = pathlib.Path(tmp)
            module_dir = pathlib.Path(tmp) / "v2" / "mod-b"
            module_dir.mkdir(parents=True)
            (module_dir / "build.gradle.kts").write_text("tasks.test { }\n")
            self.assertIsNone(mod.fork_policy("v2/mod-b"))


class RuleTests(unittest.TestCase):
    """Each rule is exercised through `collect()` on a hermetic tree."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.mod = load_module()
        self._real_just = self.mod._just

    def _repo(self, *, modules=("mod-a",), gate_commands=None, gate_budget=1270,
              uat_excludes="*UatLocal*", suites=()):
        (self.root / "scripts").mkdir(parents=True, exist_ok=True)
        (self.root / "justfile").write_text("# fixture\n")
        # The declared role authorities must exist in the fixture tree, or every
        # case would fail V2 for the same reason and the rule under test would be
        # unobservable.
        for _name, rel, _owns in self.mod.ROLES:
            path = self.root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            if not path.exists():
                path.write_text("# fixture authority\n")
        for name in modules:
            module_dir = self.root / "v2" / name
            (module_dir / "src" / "main" / "kotlin").mkdir(parents=True)
            (module_dir / "build.gradle.kts").write_text("tasks.test { }\n")
        for name in suites:
            path = self.root / "scripts" / name
            path.write_text("def test_x():\n    pass\n")
        self.gate_commands = gate_commands
        self.gate_budget = int(gate_budget)
        self.uat_excludes = uat_excludes

    def _patch_introspection(self):
        """Replace `just`/planner subprocesses with deterministic fixtures.

        Each recipe's fixture body carries its DECLARED budget, so only the case
        under test can fail V3; a shared body would fail every case for the same
        unrelated reason.
        """
        mod = self.mod
        default_command = "    timeout {{ budget }} v2/gradlew -p v2 check"

        def body_for(recipe, budget, commands=None):
            lines = [f'@{recipe} budget="{budget}":']
            lines.extend(commands or [default_command])
            return "\n".join(lines) + "\n"

        bodies = {recipe: body_for(recipe, budget)
                  for _level, recipe, budget, _runs in mod.GATES}
        # The gate under test always carries the fixture's budget/commands, so a
        # drift is observable and no unrelated gate fails for the wrong reason.
        bodies["gate"] = body_for("gate", self.gate_budget, self.gate_commands)

        def fake_just(*args):
            if args and args[0] == "--show":
                body = bodies.get(args[1])
                if body is None:
                    raise RuntimeError(f"unknown recipe {args[1]}")
                return body
            if args[:2] == ("--evaluate", "uat_excludes"):
                return self.uat_excludes + "\n"
            raise RuntimeError(f"unexpected just call {args}")

        def fake_plan(module):
            return {"gradle_tasks": [f":{module.split('/')[-1]}:test"],
                    "python_suites": [], "unclassified": [], "docs_only": False}

        mod._just = fake_just
        mod.impact_plan = fake_plan
        # The fixture tree has no planner script; production_modules only needs
        # build files, which _repo writes.
        return mod

    def _collect(self):
        mod = self._patch_introspection()
        mod.CATALOGUE_DIR = self.root / "v2" / "test-harness"
        mod.JSON_OUT = mod.CATALOGUE_DIR / "catalogue.json"
        mod.MD_OUT = mod.CATALOGUE_DIR / "CATALOGUE.md"
        mod.ROOT = self.root
        return mod.collect()

    def test_clean_fixture_passes(self):
        self._repo(modules=("mod-a", "mod-b"))
        catalogue, violations = self._collect()
        self.assertEqual(violations, [])
        self.assertEqual(len(catalogue["modules"]), 2)
        self.assertEqual(catalogue["uat_frontier"]["excludes"], ["*UatLocal*"])

    def test_role_authority_missing_fails(self):
        """Mutation: skip V2 -> a catalogue can name an authority that does not
        exist and every reader trusts a broken role table."""
        self._repo()
        (self.root / self.mod.ROLES[0][1]).unlink()
        catalogue, violations = self._collect()
        self.assertTrue(any("V2 role" in v and "does not exist" in v
                            for v in violations), violations)

    def test_role_name_outside_authority_fails(self):
        """Mutation: skip the name scan -> a second ImpactResolver arrives and the
        catalogue still claims one authority per role."""
        self._repo()
        (self.root / "scripts" / "impact-resolver-v2.py").write_text("# second engine\n")
        catalogue, violations = self._collect()
        self.assertTrue(any("second engine" in v for v in violations), violations)

    def test_gate_budget_drift_fails(self):
        """Mutation: compare nothing -> the catalogue's budget silently disagrees
        with the recipe every operator actually runs."""
        self._repo(gate_budget=999)  # catalogue wants 1270, recipe declares 999
        catalogue, violations = self._collect()
        self.assertTrue(any("V3 gate L-ROUND" in v for v in violations), violations)

    def test_hardcoded_uat_frontier_fails(self):
        """Mutation: accept any shard.excludes -> a gate stops inheriting the UAT
        frontier and starts certifying the battery it was meant to exclude."""
        self._repo(gate_commands=[
            "    timeout {{ budget }} v2/gradlew -p v2 check "
            "-Pshard.excludes='*UatLocal*'"])
        catalogue, violations = self._collect()
        self.assertTrue(any("V4 gate L-ROUND" in v for v in violations), violations)

    def test_nested_gradle_in_a_recipe_fails(self):
        """Mutation: count mentions instead of executions (or drop V6) -> two
        graphs per phase run while the catalogue reports one."""
        self._repo(gate_commands=[
            "    timeout {{ budget }} v2/gradlew -p v2 check",
            "    timeout {{ budget }} v2/gradlew -p v2 :a:test",
        ])
        catalogue, violations = self._collect()
        self.assertTrue(any("V6 gate L-ROUND" in v for v in violations), violations)

    def test_unjustified_fork_policy_fails(self):
        """Mutation: drop V7 -> forkEvery=1 spreads by copy-paste and nobody can
        tell a deliberate fork from an inherited one."""
        self._repo()
        path = self.root / "v2" / "mod-a" / "build.gradle.kts"
        path.write_text("tasks.test {\n  forkEvery = 1\n}\n")
        catalogue, violations = self._collect()
        self.assertTrue(any("V7" in v and "forkEvery=1" in v for v in violations),
                        violations)

    def test_justified_fork_policy_is_recorded(self):
        self._repo()
        path = self.root / "v2" / "mod-a" / "build.gradle.kts"
        path.write_text("tasks.test {\n  forkEvery = 1\n}\n")
        mod = self.mod
        original = dict(mod.JUSTIFIED_FORK_POLICY)
        self.addCleanup(mod.JUSTIFIED_FORK_POLICY.clear)
        self.addCleanup(mod.JUSTIFIED_FORK_POLICY.update, original)
        mod.JUSTIFIED_FORK_POLICY.clear()
        mod.JUSTIFIED_FORK_POLICY["mod-a"] = "declared reason"
        catalogue, violations = self._collect()
        self.assertEqual(violations, [], violations)
        row = next(m for m in catalogue["modules"] if m["module"] == "v2/mod-a")
        self.assertEqual(row["fork_justification"], "declared reason")

    def test_empty_suite_fails(self):
        """Mutation: skip V5 -> a suite file with no tests counts as coverage."""
        self._repo(suites=("test_empty.py",))
        (self.root / "scripts" / "test_empty.py").write_text("# no tests here\n")
        catalogue, violations = self._collect()
        self.assertTrue(any("V5" in v for v in violations), violations)


class CheckModeTests(unittest.TestCase):
    """`--check` must refuse a stale catalogue and accept a fresh one."""

    def test_stale_catalogue_is_reported(self):
        """Mutation: make --check return 0 -> the committed catalogue can describe
        a tree that no longer exists."""
        mod = load_module()
        catalogue = self._catalogue(mod)
        import json
        payload = json.dumps(catalogue, indent=2, sort_keys=True) + "\n"
        # The committed markdown is NOT what the current tree would render.
        self._stub(mod, payload=payload, markdown="# stale\n", catalogue=catalogue)
        rc = self._run(mod, ["--check"])
        self.assertEqual(rc, 1)

    def test_fresh_catalogue_passes(self):
        mod = load_module()
        catalogue = self._catalogue(mod)
        import json
        payload = json.dumps(catalogue, indent=2, sort_keys=True) + "\n"
        self._stub(mod, payload=payload, markdown=mod.render_markdown(catalogue),
                   catalogue=catalogue)
        rc = self._run(mod, ["--check"])
        self.assertEqual(rc, 0)

    # --- helpers ---

    @staticmethod
    def _catalogue(mod):
        return {"schema": mod.SCHEMA, "home": mod.CATALOGUE_DIR.as_posix(),
                "roles": [], "gates": [], "modules": [], "suites": {
                    "python": [], "python_total": 0, "python_tests": 0,
                    "kotlin_modules_with_tests": []},
                "uat_frontier": {"excludes": [], "declared_in": "justfile"},
                "policies": [], "receipts": {"root": "x", "candidate_evidence": "y"}}

    def _stub(self, mod, *, payload, markdown, catalogue=None):
        import json
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = pathlib.Path(self.tmp.name)
        mod.JSON_OUT = base / "catalogue.json"
        mod.MD_OUT = base / "CATALOGUE.md"
        mod.JSON_OUT.write_text(payload, encoding="utf-8")
        mod.MD_OUT.write_text(markdown, encoding="utf-8")
        mod.ROOT = base
        if catalogue is None:
            catalogue = json.loads(payload)
        mod.collect = lambda: (catalogue, [])

    def _run(self, mod, argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = mod.main(argv)
        self.assertIn(rc, (0, 1, 2))
        return rc


if __name__ == "__main__":
    unittest.main(verbosity=2)
