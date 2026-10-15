#!/usr/bin/env python3
"""Behavioural tests for fitness-harness-coverage.py.

Hermetic fixture trees drive the real registry logic in-process; the last case
runs against the actual scripts/ tree, which is the state this fitness is meant
to defend.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import pathlib
import shutil
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "fitness-harness-coverage.py"


def load_module():
    spec = importlib.util.spec_from_file_location("fitness_harness_coverage", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Harness(unittest.TestCase):
    def setUp(self) -> None:
        self.mod = load_module()
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="fitness-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def write(self, rel: str, body: str = "") -> pathlib.Path:
        p = self.tmp / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")
        return p

    def run_fitness(self, *, strict: bool = False):
        out, err = io.StringIO(), io.StringIO()
        argv = ["--root", str(self.tmp)] + (["--strict"] if strict else [])
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = self.mod.main(argv)
        return rc, out.getvalue(), err.getvalue()


class ToolNeedsSuite(Harness):
    def test_tool_with_colocated_suite_passes(self) -> None:
        """Claim: a tool plus its suite is the green shape.

        Mutation: require some other naming convention; the honest pair fails.
        """
        self.write("tool-x.py", "print('x')\n")
        self.write("test_tool_x.py", "def test_ok():\n    assert True\n")
        rc, out, err = self.run_fitness()
        self.assertEqual(rc, 0, err + out)

    def test_tool_without_suite_is_a_violation(self) -> None:
        """Claim: a new tool cannot arrive without a suite.

        Mutation: drop R1; the violation disappears and undeclared tooling
        accumulates silently.
        """
        self.write("tool-x.py", "print('x')\n")
        rc, out, err = self.run_fitness()
        self.assertEqual(rc, 2)
        self.assertIn("no colocated suite", err)

    def test_subdirectory_tool_maps_to_same_directory_suite(self) -> None:
        """Claim: the rule is colocated, not root-only."""
        self.write("release/tool-y.py", "print('y')\n")
        self.write("release/test_tool_y.py", "def test_ok():\n    assert True\n")
        rc, out, err = self.run_fitness()
        self.assertEqual(rc, 0, err + out)


class SuiteNeedsSubjectAndTests(Harness):
    def test_orphan_suite_is_a_violation(self) -> None:
        """Claim: a suite whose subject is gone is a defect, not history."""
        self.write("test_ghost.py", "def test_x():\n    assert True\n")
        rc, out, err = self.run_fitness()
        self.assertEqual(rc, 2)
        self.assertIn("names no existing subject", err)

    def test_suite_without_tests_is_a_violation(self) -> None:
        """Claim: an empty suite cannot satisfy R1.

        Mutation: check only that the file exists; a placeholder suite with no
        tests would certify the tool.
        """
        self.write("tool-x.py", "print('x')\n")
        self.write("test_tool_x.py", "# TODO\n")
        rc, out, err = self.run_fitness()
        self.assertEqual(rc, 2)
        self.assertIn("contains no `def test_`", err)

    def test_suite_may_target_a_shell_subject(self) -> None:
        """Claim: a .sh subject in another directory resolves (installer case)."""
        self.write("installer.sh", "#!/bin/sh\n")
        self.write("test_installer.py", "def test_x():\n    assert True\n")
        rc, out, err = self.run_fitness()
        self.assertEqual(rc, 0, err + out)


class Registries(Harness):
    def test_open_gap_is_reported_without_strict_and_fails_with_it(self) -> None:
        """Claim: a declared gap is visible and has a lever.

        Mutation: treat OPEN_GAP as silence; the gap stops being reported at
        all and --strict loses its meaning.
        """
        self.write("tool-x.py", "print('x')\n")
        with mock.patch.dict(self.mod.EXEMPT,
                             {"tool-x.py": ("OPEN_GAP", "fixture gap")}):
            rc, out, err = self.run_fitness()
            self.assertEqual(rc, 0, err)
            self.assertIn("[OPEN_GAP]", out)
            self.assertIn("fixture gap", out)
            rc, out, err = self.run_fitness(strict=True)
            self.assertEqual(rc, 2)
            self.assertIn("OPEN_GAP under --strict", err)

    def test_owner_exemption_is_not_an_open_gap(self) -> None:
        """Claim: an OWNER exemption neither fails --strict nor is listed as a gap."""
        self.write("tool-x.py", "print('x')\n")
        with mock.patch.dict(self.mod.EXEMPT,
                             {"tool-x.py": ("OWNER", "outside authority")}):
            rc, out, err = self.run_fitness(strict=True)
            self.assertEqual(rc, 0, err)
            self.assertNotIn("[OPEN_GAP]", out)

    def test_unmapped_shell_entry_point_is_a_violation(self) -> None:
        """Claim: a shell entry point must be mapped or declared."""
        self.write("mystery.sh", "#!/bin/sh\n")
        rc, out, err = self.run_fitness()
        self.assertEqual(rc, 2)
        self.assertIn("no declared suite", err)

    def test_shell_mapping_must_point_at_an_existing_suite(self) -> None:
        """Claim: a mapping to a deleted suite is itself a violation."""
        self.write("installer.sh", "#!/bin/sh\n")
        with mock.patch.dict(self.mod.SHELL_SUITES,
                             {"installer.sh": "test_gone.py"}):
            rc, out, err = self.run_fitness()
            self.assertEqual(rc, 2)
            self.assertIn("does not exist", err)

    def test_shell_mapping_by_basename_covers_subdirectories(self) -> None:
        """Claim: a suite can be declared for a shell script in a subdir."""
        self.write("release/x.sh", "#!/bin/sh\n")
        self.write("test_x.py", "def test_x():\n    assert True\n")
        with mock.patch.dict(self.mod.SHELL_SUITES, {"x.sh": "test_x.py"}):
            rc, out, err = self.run_fitness()
        self.assertEqual(rc, 0, err + out)

    def test_topic_suite_registry_resolves_declared_subjects(self) -> None:
        """Claim: a topic-named suite covers its declared subjects.

        Mutation: require stem equality everywhere; test_sdkman_digest.py (two
        subjects) would be reported as an orphan suite.
        """
        self.write("a.sh", "#!/bin/sh\n")
        self.write("b.sh", "#!/bin/sh\n")
        self.write("test_topic.py", "def test_x():\n    assert True\n")
        with mock.patch.dict(self.mod.SUITE_SUBJECTS,
                             {"test_topic.py": ["a.sh", "b.sh"]}), \
             mock.patch.dict(self.mod.EXEMPT_SHELL, {"a.sh": "fixture", "b.sh": "fixture"}):
            rc, out, err = self.run_fitness()
        self.assertEqual(rc, 0, err + out)

    def test_topic_suite_with_missing_subject_is_a_violation(self) -> None:
        """Claim: the declared subjects are checked, not just recorded."""
        self.write("a.sh", "#!/bin/sh\n")
        self.write("test_topic.py", "def test_x():\n    assert True\n")
        with mock.patch.dict(self.mod.SUITE_SUBJECTS,
                             {"test_topic.py": ["a.sh", "missing.sh"]}), \
             mock.patch.dict(self.mod.EXEMPT_SHELL, {"a.sh": "fixture"}):
            rc, out, err = self.run_fitness()
        self.assertEqual(rc, 2)
        self.assertIn("names no existing subject", err)


class RealTree(Harness):
    def test_real_scripts_tree_satisfies_the_registry(self) -> None:
        """Claim: today's harness passes its own fitness (default mode).

        This is the state the registry defends; if a tool arrives without a
        suite, this test fails on the same run as `just fitness`.
        """
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = self.mod.main(["--root", str(ROOT / "scripts")])
        self.assertEqual(rc, 0, err.getvalue() + out.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
