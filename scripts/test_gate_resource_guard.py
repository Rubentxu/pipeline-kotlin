#!/usr/bin/env python3
"""Behavioural tests for gate-resource-guard.py.

The guard decides whether a heavy gate may start while another one is running.
Its detection is over `/proc`, so these tests build a fixture process table
(numeric directories with `cmdline` and `status` files) and pass it in — the
suite never depends on what this host happens to be running.

Claims pinned here:
  - a gate command line is an offender, an unrelated process is not;
  - the caller's own process tree is never an offender (the guard is spawned by
    `just`, whose command line contains the recipe);
  - a warm Gradle daemon is not a gate;
  - the refusal is typed (exit 2) and names the offender;
  - `--force` and the environment override allow the run deliberately;
  - an unreadable process table refuses (exit 3) instead of starting blind.
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "gate-resource-guard.py"


def load_module():
    spec = importlib.util.spec_from_file_location("gate_guard", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ProcFixture:
    """A fake /proc with numeric pid directories."""

    def __init__(self, tmp: pathlib.Path):
        self.root = tmp / "proc"
        self.root.mkdir(parents=True, exist_ok=True)

    def add(self, pid: int, cmdline: str, *, ppid: int = 1) -> None:
        d = self.root / str(pid)
        d.mkdir(exist_ok=True)
        (d / "cmdline").write_bytes(cmdline.replace(" ", "\x00").encode())
        (d / "status").write_text(f"Name:\tt\nPPid:\t{ppid}\n")


class DetectionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.proc = ProcFixture(pathlib.Path(self.tmp.name))
        self.mod = load_module()

    def test_another_gate_is_detected(self):
        self.proc.add(101, "java -jar gradle-wrapper.jar --no-daemon v2/gradlew check")
        self.proc.add(102, "/usr/bin/python3 -c sleep")
        found = self.mod.gate_processes(self.proc.root, self_pid=999)
        self.assertEqual([pid for pid, _ in found], [101])

    def test_own_process_tree_is_ignored(self):
        """Mutation: drop the ancestor walk -> the guard always refuses, because
        `just` (its parent) mentions the gate recipe on its own command line."""
        self.proc.add(200, "sh -c just gate", ppid=1)
        self.proc.add(201, "v2/gradlew -p v2 check", ppid=200)
        self.proc.add(202, "python3 scripts/gate-resource-guard.py --gate L-ROUND",
                      ppid=201)
        found = self.mod.gate_processes(self.proc.root, self_pid=202)
        self.assertEqual(found, [], "nothing outside the caller's tree should match")

    def test_other_worktree_gate_is_detected_even_from_the_same_user(self):
        self.proc.add(300, "sh -c cd /other/worktree && just gate", ppid=1)
        self.proc.add(301, "python3 scripts/gate-resource-guard.py --gate L-DEV",
                      ppid=400)
        self.proc.add(302, "/home/x/java ... gradle-wrapper.jar v2/gradlew -p v2 check",
                      ppid=301)
        found = self.mod.gate_processes(self.proc.root, self_pid=301)
        self.assertEqual([pid for pid, _ in found], [302])

    def test_gradle_daemon_is_not_a_gate(self):
        """Mutation: match on the word `gradle` -> every warm daemon blocks every
        gate, and the guard becomes a permanent refusal."""
        self.proc.add(400, "java ... org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.5")
        found = self.mod.gate_processes(self.proc.root, self_pid=999)
        self.assertEqual(found, [])

    def test_release_gate_and_integration_gate_are_offenders(self):
        self.proc.add(500, "sh -c v2/gradlew -p v2 :pipeline-release:releaseGate")
        self.proc.add(501, "python3 scripts/run-integration-gate.py --base HEAD")
        found = self.mod.gate_processes(self.proc.root, self_pid=999)
        self.assertEqual(sorted(pid for pid, _ in found), [500, 501])


class MainTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.proc = ProcFixture(pathlib.Path(self.tmp.name))
        self.mod = load_module()

    def _run(self, argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = self.mod.main(argv)
        return rc, out.getvalue(), err.getvalue()

    def test_refuses_with_exit_2_and_names_the_offender(self):
        self.proc.add(600, "sh -c just gate")
        self.proc.add(601, "java gradle-wrapper.jar v2/gradlew -p v2 check", ppid=600)
        rc, out, err = self._run(["--gate", "L-MERGE", "--proc-root", str(self.proc.root)])
        self.assertEqual(rc, 2)
        self.assertIn("REFUSED", err)
        self.assertIn("pid=601", err)

    def test_allows_when_nothing_else_runs(self):
        rc, out, err = self._run(["--gate", "L-ROUND", "--proc-root", str(self.proc.root)])
        self.assertEqual(rc, 0)
        self.assertIn("allowed", out)

    def test_force_allows_and_says_so(self):
        self.proc.add(700, "sh -c just gate")
        self.proc.add(701, "java gradle-wrapper.jar v2/gradlew -p v2 check", ppid=700)
        rc, out, err = self._run(["--gate", "L-ROUND", "--force",
                                  "--proc-root", str(self.proc.root)])
        self.assertEqual(rc, 0)
        self.assertIn("concurrency allowed", out)

    def test_unreadable_process_table_refuses(self):
        """Mutation: treat an unreadable /proc as 'no offenders' -> the guard
        reports an allowed run it never verified."""
        missing = pathlib.Path(self.tmp.name) / "no-such-proc"
        rc, out, err = self._run(["--gate", "L-ROUND", "--proc-root", str(missing)])
        self.assertEqual(rc, 3)
        self.assertIn("cannot inspect", err)


if __name__ == "__main__":
    unittest.main(verbosity=2)
