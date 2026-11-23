#!/usr/bin/env python3
"""Portable local crash-resume state tests; no PipelineK run or network needed."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("pipelinek-resume-state.py")
spec = importlib.util.spec_from_file_location("resume_state", SCRIPT)
resume = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resume)


def git(root, *args):
    subprocess.run(["git", "-C", str(root), *args], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


class RecoveryFingerprintTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "project"
        self.root.mkdir()
        self.script = self.root / "pipeline.kts"
        self.script.write_text('pipeline { }\n', encoding="utf-8")

    def make_git(self):
        git(self.root, "init", "-q")
        git(self.root, "config", "user.email", "test@example.invalid")
        git(self.root, "config", "user.name", "Local Test")
        git(self.root, "add", "pipeline.kts")
        git(self.root, "commit", "-qm", "initial")

    def test_script_only_non_git(self):
        result = resume.source_identity(self.root, self.script)
        self.assertEqual(result["mode"], "script-only")
        self.assertNotIn("workspaceSha256", result)

    def test_git_staged_unstaged_and_untracked_are_observed(self):
        self.make_git()
        baseline = resume.source_identity(self.root, self.script)
        self.assertEqual(baseline["mode"], "git-visible")
        self.script.write_text('pipeline { /* uncommitted */ }\n')
        modified = resume.source_identity(self.root, self.script)
        self.assertNotEqual(baseline["workspaceSha256"], modified["workspaceSha256"])
        git(self.root, "add", "pipeline.kts")
        staged = resume.source_identity(self.root, self.script)
        self.assertEqual(modified["workspaceSha256"], staged["workspaceSha256"])
        extra = self.root / "test.rs"
        extra.write_text("fn test() {}\n")
        untracked = resume.source_identity(self.root, self.script)
        self.assertNotEqual(staged["workspaceSha256"], untracked["workspaceSha256"])
        extra.write_text("fn test() { panic!() }\n")
        self.assertNotEqual(untracked["workspaceSha256"], resume.source_identity(self.root, self.script)["workspaceSha256"])

    def test_plugin_bytes_are_pinned_not_just_paths(self):
        plugin = self.root / "plugin.jar"
        plugin.write_bytes(b"v1")
        a = resume.session_identity(self.root, self.script, "ci", "version", ["--plugin-jar", str(plugin)],
                                    resume.source_identity(self.root, self.script))
        plugin.write_bytes(b"v2")
        b = resume.session_identity(self.root, self.script, "ci", "version", ["--plugin-jar", str(plugin)],
                                    resume.source_identity(self.root, self.script))
        self.assertNotEqual(a["localFilePins"], b["localFilePins"])
        self.assertNotIn(str(plugin), json.dumps(a))

    def test_config_and_credentials_are_not_hashed_as_raw_secret(self):
        secret = self.root / "inputs.json"
        secret.write_text('{"password":"abc"}\n')
        identity = resume.session_identity(self.root, self.script, "ci", "version", ["--run-inputs", str(secret)],
                                           resume.source_identity(self.root, self.script))
        self.assertIn("--run-inputs", identity["unverifiedInputs"])
        self.assertNotIn('"password"', json.dumps(identity))
        self.assertNotIn(str(secret), json.dumps(identity))

    def test_pending_session_requires_same_runtime_and_inputs(self):
        with tempfile.TemporaryDirectory() as location:
            folder = Path(location)
            identity = resume.session_identity(self.root, self.script, "ci", "version-1", [],
                                               resume.source_identity(self.root, self.script))
            session = folder / "executions" / "session-1"
            session.mkdir(parents=True)
            (session / "session.json").write_text(json.dumps(resume.new_manifest(identity, "session-1")))
            selected, _ = resume.pending(folder, identity)
            self.assertEqual(selected, session)
            other = dict(identity, runtime="version-2")
            with self.assertRaisesRegex(ValueError, "resume refused"):
                resume.pending(folder, other)

    def test_prior_run_id_disagreement_is_refused(self):
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            directory = root / "control" / "last-run"
            directory.mkdir(parents=True)
            (directory / "definition").write_text("other-id\n")
            with self.assertRaisesRegex(ValueError, "disagree"):
                resume.prior_run_id(root, {"runId": "known-id"})

    def test_os_lock_rejects_concurrent_same_workflow(self):
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            with resume.exclusive(root, "ci"):
                with self.assertRaisesRegex(ValueError, "already owns"):
                    with resume.exclusive(root, "ci"):
                        pass
            with resume.exclusive(root, "ci"):
                pass


if __name__ == "__main__":
    unittest.main()
