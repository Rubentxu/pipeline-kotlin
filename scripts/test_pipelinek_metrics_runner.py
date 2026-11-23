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
mode = sys.argv[1]
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
                          startedAtMs=0)))
    sys.exit(int(os.environ.get('FAKE_RUN_EXIT','0')))
if mode == 'events':
    print(obj('RunStarted',1,0,scriptPath='SECRET_EVENT_CANARY'))
    print(obj('StageStarted',2,1,stageIndex=0,stageName='verify'))
    print(obj('StepStarted',3,2,stageIndex=0,stepIndex=0,stepName='check',stepType='sh'))
    print(obj('StepFinished',4,3,stageIndex=0,stepIndex=0,stepName='check',stepType='sh',outcome='success'))
    print(obj('StageFinished',5,4,stageIndex=0,stageName='verify',outcome='success'))
    print(obj('RunFinished',6,5,outcome='success',diagnostics=[]))
    sys.exit(0)
sys.exit(93)
"""
        ), encoding="utf-8")
        self.stub.chmod(0o700)

    def invoke(self, env=None):
        environment = os.environ.copy()
        environment.pop("PIPELINE_CREDENTIALS_MASKING", None)
        environment["XDG_STATE_HOME"] = str(self.state)
        environment.update(env or {})
        return subprocess.run([
            sys.executable, str(TOOL), "--project", str(self.project),
            "--script", "pipelines/ci.pipeline.kts", "--workflow", "ci",
            "--pipelinek", str(self.stub),
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
