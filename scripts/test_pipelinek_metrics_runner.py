#!/usr/bin/env python3
"""Offline integration tests: arbitrary project, fake PipelineK CLI, no network."""
from datetime import datetime, timezone, timedelta
import importlib.util
import json
import os
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
import os, sys
from pathlib import Path
mode = sys.argv[1]
if mode == 'version':
    print(json.dumps({'schema':'pipelinek.version/test','version':os.environ.get('FAKE_VERSION','test-1')}))
    sys.exit(0)
if mode == 'inspect':
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
