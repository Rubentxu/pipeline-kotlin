#!/usr/bin/env python3
"""Offline integration tests: arbitrary project, fake PipelineK CLI, no network."""
from datetime import datetime, timezone, timedelta
import importlib.util
import json
import os
import signal
import time
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

TOOL = Path(__file__).with_name("pipelinek-run-with-metrics.py")


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.project = base / "external-go-project"
        self.project.mkdir()
        (self.project / "pipelines").mkdir()
        (self.project / "pipelines" / "ci.pipeline.kts").write_text(
            "// any project language: this file need not be a PipelineK source checkout\n"
        )
        self.state = base / "local-state"
        self.stub = base / "fake-pipelinek"
        self.stub.write_text(textwrap.dedent(
            """#!/usr/bin/env python3
import json
from datetime import datetime, timedelta, timezone
import os, sys, time
from pathlib import Path
mode = sys.argv[1]
if mode == 'version':
    print(json.dumps({'schema':'pipelinek.version/test','version':os.environ.get('FAKE_VERSION','test-1')}))
    sys.exit(0)
if mode == 'inspect':
    if os.environ.get('FAKE_INSPECT_STATE') == 'unobservable':
        print('run-state unobservable')
        sys.exit(0)
    if Path('completed.marker').exists():
        print('run-state terminal outcome=succeeded at=0')
    else:
        print('run-state running')
    sys.exit(0)
when = datetime.now(timezone.utc)
def obj(kind, n, offset, **extra):
    return json.dumps(dict(kind=kind, sequence=n, eventId='fake-'+str(n),
                           runId='synthetic-run-id',
                           occurredAt=(when + timedelta(seconds=offset)).isoformat(),
                           **extra))
if mode == 'run':
    if os.environ.get('PIPELINE_CREDENTIALS_MASKING') != 'required':
        sys.exit(88)
    print(json.dumps(dict(schema='pipelinek.launch/1',status='Launched',
                          runId='synthetic-run-id',correlationId='c',ownerId='o',
                          startedAtMs=0)), flush=True)
    if '--resume' in sys.argv:
        Path('resumed.marker').write_text('yes')
    if os.environ.get('FAKE_HANG') == '1':
        time.sleep(60)
    exit_value = int(os.environ.get('FAKE_RUN_EXIT','0'))
    if exit_value == 0:
        Path('completed.marker').write_text('yes')
    sys.exit(exit_value)
if mode == 'events':
    print(obj('RunStarted',1,0,scriptPath='SECRET_EVENT_CANARY'))
    print(obj('StageStarted',2,1,stageIndex=0,stageName='verify'))
    print(obj('StepStarted',3,2,stageIndex=0,stepIndex=0,stepName='check',stepType='sh'))
    if Path('completed.marker').exists():
        print(obj('StepFinished',4,3,stageIndex=0,stepIndex=0,stepName='check',stepType='sh',outcome='success'))
        print(obj('StageFinished',5,4,stageIndex=0,stageName='verify',outcome='success'))
        print(obj('RunFinished',6,5,outcome='success',diagnostics=[]))
    sys.exit(0)
sys.exit(93)
"""
        ), encoding="utf-8")
        self.stub.chmod(0o700)

    def invoke(self, env=None, flags=()):
        environment = os.environ.copy()
        environment.pop("PIPELINE_CREDENTIALS_MASKING", None)
        environment["XDG_STATE_HOME"] = str(self.state)
        environment.update(env or {})
        return subprocess.run([
            sys.executable, str(TOOL), "--project", str(self.project),
            "--script", "pipelines/ci.pipeline.kts", "--workflow", "ci",
            "--pipelinek", str(self.stub), *flags,
        ], cwd=self.project, env=environment, capture_output=True, text=True)

    def test_arbitrary_project_creates_private_history_and_html(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        reports = list(self.state.glob("pipelinek/projects/*/reports/runs/*.json"))
        self.assertEqual(len(reports), 1, result.stderr)
        row = json.loads(reports[0].read_text(encoding="utf-8"))
        self.assertEqual(row["schema"], "pipelinek.local-metrics/v1")
        self.assertEqual(row["runId"], "synthetic-run-id")
        self.assertEqual(row["durationMs"], 5000)
        self.assertEqual(row["stages"][0]["durationMs"], 3000)
        self.assertEqual(row["processUsage"]["exitCode"], 0)
        self.assertIn("cpuUserMs", row["processUsage"])
        self.assertNotIn("SECRET_EVENT_CANARY", reports[0].read_text())
        self.assertTrue((reports[0].parent.parent / "dashboard.html").is_file())
        if os.name == "posix":
            self.assertEqual(reports[0].stat().st_mode & 0o777, 0o600)

    def test_nonzero_pipeline_exit_remains_nonzero(self):
        result = self.invoke({"FAKE_RUN_EXIT": "7"})
        self.assertEqual(result.returncode, 7, result.stderr)

    def test_reject_script_outside_project(self):
        outside = self.project.parent / "other.pipeline.kts"
        outside.write_text("irrelevant")
        result = subprocess.run([
            sys.executable, str(TOOL), "--project", str(self.project),
            "--script", str(outside), "--pipelinek", str(self.stub),
        ], capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("contained within", result.stderr)

    def test_crash_is_resumable_without_starting_a_new_run(self):
        crashed = self.invoke({"FAKE_RUN_EXIT": "7"})
        self.assertEqual(crashed.returncode, 7, crashed.stderr)
        manifests = list(self.state.glob("pipelinek/projects/*/reports/executions/*/session.json"))
        self.assertEqual(len(manifests), 1)
        interrupted = json.loads(manifests[0].read_text(encoding="utf-8"))
        self.assertEqual(interrupted["status"], "INTERRUPTED")
        self.assertEqual(interrupted["attempts"], 1)
        resumed = self.invoke(flags=("--resume", "--accept-unverified-inputs"))
        self.assertEqual(resumed.returncode, 0, resumed.stderr)
        self.assertTrue((self.project / "resumed.marker").exists())
        complete = json.loads(manifests[0].read_text(encoding="utf-8"))
        self.assertEqual(complete["attempts"], 2)
        self.assertEqual(complete["status"], "TERMINAL")
        self.assertEqual(complete["runId"], interrupted["runId"])

    def test_normal_terminal_run_cannot_be_resumed(self):
        first = self.invoke()
        self.assertEqual(first.returncode, 0, first.stderr)
        repeated = self.invoke(flags=("--resume", "--accept-unverified-inputs"))
        self.assertEqual(repeated.returncode, 2, repeated.stderr)
        self.assertFalse((self.project / "resumed.marker").exists())

    def test_resume_with_changed_inputs_is_refused(self):
        self.assertEqual(self.invoke({"FAKE_RUN_EXIT": "7"}).returncode, 7)
        (self.project / "pipelines" / "ci.pipeline.kts").write_text("// changed after crash")
        repeated = self.invoke(flags=("--resume", "--accept-unverified-inputs"))
        self.assertEqual(repeated.returncode, 2)
        self.assertIn("source", repeated.stderr)
        self.assertFalse((self.project / "resumed.marker").exists())

    def test_resume_with_changed_installed_runtime_is_refused(self):
        self.assertEqual(self.invoke({"FAKE_RUN_EXIT": "7"}).returncode, 7)
        result = self.invoke({"FAKE_VERSION": "other-runtime"},
                             flags=("--resume", "--accept-unverified-inputs"))
        self.assertEqual(result.returncode, 2)
        self.assertIn("runtime", result.stderr)

    def test_status_reads_journal_without_running_workflow(self):
        self.assertEqual(self.invoke({"FAKE_RUN_EXIT": "7"}).returncode, 7)
        status = self.invoke(flags=("--status",))
        self.assertEqual(status.returncode, 0, status.stderr)
        report = json.loads(status.stdout)
        self.assertEqual(report["schema"], "pipelinek.local-sessions/v1")
        self.assertEqual(report["sessions"][0]["journalState"], "run-state running")
        self.assertFalse((self.project / "completed.marker").exists())

    def test_resume_refuses_non_git_without_explicit_ack(self):
        self.assertEqual(self.invoke({"FAKE_RUN_EXIT": "7"}).returncode, 7)
        denied = self.invoke(flags=("--resume",))
        self.assertEqual(denied.returncode, 2)
        self.assertIn("non-Git workspace", denied.stderr)

    def test_resume_does_not_duplicate_concurrent_work(self):
        first = self.invoke({"FAKE_RUN_EXIT": "7"})
        self.assertEqual(first.returncode, 7)
        runner = self._load_runner()
        spec = importlib.util.spec_from_file_location(
            "resume_helper", Path(__file__).with_name("pipelinek-resume-state.py"))
        helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(helper)
        root = runner.project_history(self.project)
        with helper.exclusive(root, "ci"):
            denied = self.invoke(flags=("--resume", "--accept-unverified-inputs"))
            self.assertEqual(denied.returncode, 2)
            self.assertIn("already owns", denied.stderr)

    def test_resume_or_new_starts_and_then_resumes_only_when_needed(self):
        crash = self.invoke({"FAKE_RUN_EXIT": "7"}, flags=("--resume-or-new",))
        self.assertEqual(crash.returncode, 7)
        resumed = self.invoke(flags=("--resume-or-new", "--accept-unverified-inputs"))
        self.assertEqual(resumed.returncode, 0, resumed.stderr)
        self.assertTrue((self.project / "resumed.marker").exists())
        manifests = list(self.state.glob("pipelinek/projects/*/reports/executions/*/session.json"))
        self.assertEqual(len(manifests), 1)
        self.assertEqual(json.loads(manifests[0].read_text())["attempts"], 2)

    def test_explicit_new_cannot_duplicate_unfinished_effect(self):
        self.assertEqual(self.invoke({"FAKE_RUN_EXIT": "7"}).returncode, 7)
        other = self.invoke(flags=("--new",))
        self.assertEqual(other.returncode, 2)
        self.assertIn("no verified terminal", other.stderr)
        manifests = list(self.state.glob("pipelinek/projects/*/reports/executions/*/session.json"))
        self.assertEqual(len(manifests), 1)

    def test_unobservable_journal_refuses_replay(self):
        self.assertEqual(self.invoke({"FAKE_RUN_EXIT": "7"}).returncode, 7)
        denied = self.invoke(
            {"FAKE_INSPECT_STATE": "unobservable"},
            flags=("--resume", "--accept-unverified-inputs")
        )
        self.assertEqual(denied.returncode, 2)
        self.assertIn("unobservable", denied.stderr)
        self.assertFalse((self.project / "resumed.marker").exists())

    def test_manifest_does_not_serialize_run_args_or_credentials(self):
        first = self.invoke({"FAKE_RUN_EXIT": "7"})
        self.assertEqual(first.returncode, 7)
        manifest = next(self.state.glob("pipelinek/projects/*/reports/executions/*/session.json"))
        content = manifest.read_text(encoding="utf-8")
        self.assertIn("pipelinek.local-session/v1", content)
        self.assertNotIn("FAKE_RUN_EXIT", content)
        self.assertNotIn("PIPELINE_CREDENTIALS_MASKING", content)

    @unittest.skipUnless(os.name == "posix", "requires POSIX process-group kill")
    def test_sigkill_of_wrapper_and_child_keeps_resume_state(self):
        environment = os.environ.copy()
        environment["XDG_STATE_HOME"] = str(self.state)
        environment["FAKE_HANG"] = "1"
        command = [
            sys.executable, str(TOOL), "--project", str(self.project),
            "--script", "pipelines/ci.pipeline.kts", "--workflow", "ci",
            "--pipelinek", str(self.stub),
        ]
        process = subprocess.Popen(command, cwd=self.project, env=environment,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   start_new_session=True)
        try:
            ready = False
            for _ in range(100):
                manifests = list(self.state.glob("pipelinek/projects/*/reports/executions/*/session.json"))
                if manifests:
                    state = json.loads(manifests[0].read_text(encoding="utf-8"))
                    if state.get("status") == "RUNNING" and state.get("runId"):
                        ready = True
                        break
                time.sleep(0.1)
            self.assertTrue(ready, "launch receipt was not durably saved")
        finally:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)
        resumed = self.invoke(flags=("--resume", "--accept-unverified-inputs"))
        self.assertEqual(resumed.returncode, 0, resumed.stderr)
        self.assertTrue((self.project / "resumed.marker").exists())

    def test_disagreement_between_durable_directory_and_launch_receipt_refuses(self):
        self.assertEqual(self.invoke({"FAKE_RUN_EXIT": "7"}).returncode, 7)
        session = next(self.state.glob("pipelinek/projects/*/reports/executions/*/session.json"))
        mapping = session.parent / "control" / "last-run"
        mapping.mkdir(parents=True)
        (mapping / "definition-1").write_text("different-run-id\\n")
        denied = self.invoke(flags=("--resume", "--accept-unverified-inputs"))
        self.assertEqual(denied.returncode, 2)
        self.assertIn("disagree", denied.stderr)

    def test_new_requires_prior_terminal_even_if_manifest_stale(self):
        self.assertEqual(self.invoke({"FAKE_RUN_EXIT": "7"}).returncode, 7)
        # Simulate the product writing a terminal after the wrapper crashed.
        (self.project / "completed.marker").write_text("terminal recorded")
        fresh = self.invoke(flags=("--new",))
        self.assertEqual(fresh.returncode, 0, fresh.stderr)
        manifests = list(self.state.glob("pipelinek/projects/*/reports/executions/*/session.json"))
        self.assertEqual(len(manifests), 2)
        self.assertTrue(all(json.loads(p.read_text())["status"] == "TERMINAL" for p in manifests))

    def test_multiple_pending_sessions_need_explicit_selection(self):
        self.assertEqual(self.invoke({"FAKE_RUN_EXIT": "7"}).returncode, 7)
        session = next(self.state.glob("pipelinek/projects/*/reports/executions/*/session.json"))
        other = session.parent.parent / "second-session"
        other.mkdir()
        (other / "session.json").write_bytes(session.read_bytes())
        refused = self.invoke(flags=("--resume-or-new", "--accept-unverified-inputs"))
        self.assertEqual(refused.returncode, 2)
        self.assertIn("found 2", refused.stderr)

    def test_conflicting_db_flag_is_refused(self):
        runner = self._load_runner()
        with self.assertRaises(ValueError):
            runner.launch_command(str(self.stub), Path("pipeline.kts"),
                                  Path("a"), Path("b"), ["--db", "unsafe"])

    def _load_runner(self):
        spec = importlib.util.spec_from_file_location("portable_runner", TOOL)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module


if __name__ == "__main__":
    unittest.main()
