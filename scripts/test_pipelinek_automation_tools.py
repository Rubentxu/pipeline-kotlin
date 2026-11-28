#!/usr/bin/env python3
"""Regression tests for offline DSL discovery and local PipelineK metrics."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest


def load(name):
    filename = Path(__file__).with_name(name)
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


dsl = load("pipelinek-dsl-index.py")
metrics = load("pipelinek-local-report.py")


def event(kind, n, seconds, **fields):
    return json.dumps({
        "kind": kind, "sequence": n, "runId": "synthetic-run",
        "eventId": "id-" + str(n), "occurredAt": "2026-01-01T00:00:%02dZ" % seconds,
        **fields
    })


class MetricsTest(unittest.TestCase):
    def events(self):
        return [
            event("RunStarted", 1, 0, scriptPath="secret-is-not-stored"),
            event("StageStarted", 2, 1, stageIndex=0, stageName="build"),
            event("StepStarted", 3, 2, stageIndex=0, stepIndex=0, stepName="compile", stepType="sh"),
            event("StepFinished", 4, 4, stageIndex=0, stepIndex=0, stepName="compile", stepType="sh", outcome="success"),
            event("StageFinished", 5, 5, stageIndex=0, stageName="build", outcome="success"),
            event("RunFinished", 6, 7, outcome="success", diagnostics=[]),
        ]

    def test_exact_durations_and_no_payloads(self):
        report = metrics.project(self.events())
        self.assertEqual(report["coverage"], "COMPLETE")
        self.assertEqual(report["durationMs"], 7000)
        self.assertEqual(report["stages"][0]["durationMs"], 4000)
        self.assertEqual(report["steps"][0]["durationMs"], 2000)
        self.assertNotIn("secret-is-not-stored", json.dumps(report))

    def test_compile_time_and_total_end_to_end_are_distinct(self):
        rows = [
            event("CompilationStarted", 1, 0),
            event("CompilationFinished", 2, 2),
            event("RunStarted", 3, 3, scriptPath="ignored"),
            event("StageStarted", 4, 4, stageIndex=0, stageName="verify"),
            event("StageFinished", 5, 8, stageIndex=0, stageName="verify", outcome="success"),
            event("RunFinished", 6, 9, outcome="success", diagnostics=[]),
        ]
        report = metrics.project(rows)
        self.assertEqual(report["compileMs"], 2000)
        self.assertEqual(report["durationMs"], 6000)
        self.assertEqual(report["endToEndMs"], 9000)

    def test_incomplete_run_never_claims_finished(self):
        report = metrics.project(self.events()[:-2])
        self.assertIsNone(report["durationMs"])
        self.assertEqual(report["outcome"], "incomplete")
        self.assertEqual(report["coverage"], "PARTIAL")

    def test_out_of_order_sequence_is_refused(self):
        rows = self.events()
        with self.assertRaises(ValueError):
            metrics.project([rows[1], rows[0], *rows[2:]])

    def test_mixed_runs_are_refused(self):
        rows = self.events()
        rows[-1] = json.dumps({**json.loads(rows[-1]), "runId": "another"})
        with self.assertRaises(ValueError):
            metrics.project(rows)

    def test_stage_name_is_never_html_injection(self):
        rows = self.events()
        payload = json.loads(rows[1])
        payload["stageName"] = "<script>alert(1)</script>"
        rows[1] = json.dumps(payload)
        report = metrics.project(rows)
        self.assertEqual(report["stages"][0]["name"], "stage-0")
        rendered = metrics.dashboard([report])
        self.assertNotIn("<script>", rendered)
        self.assertNotIn("https://", rendered)

    def test_untrusted_snapshot_data_cannot_enter_report(self):
        with tempfile.TemporaryDirectory() as location:
            snap = metrics.resources(".")
            snap["cpu"]["affinityCount"] = "TOKEN-WOULD-LEAK"
            file = Path(location) / "snapshot.json"
            file.write_text(json.dumps(snap), encoding="utf-8")
            with self.assertRaises(ValueError):
                metrics.approved_snapshot(file)
            snap["cpu"]["affinityCount"] = 2
            snap["EXTRA_SECRET"] = "never-copy-this"
            file.write_text(json.dumps(snap), encoding="utf-8")
            safe = metrics.approved_snapshot(file)
            self.assertNotIn("never-copy-this", json.dumps(safe))

    def test_distinct_workflows_do_not_share_stage_trend(self):
        first = metrics.project(self.events())
        first["workflow"] = "ci"
        second = dict(first)
        second["workflow"] = "certify"
        dash = metrics.dashboard([first, second])
        self.assertIn("ci / stage-0", dash)
        self.assertIn("certify / stage-0", dash)

    def test_resumed_stage_interval_is_excluded_from_speed_trend(self):
        report = metrics.project(self.events())
        report["workflow"] = "ci"
        report["resumeAttempts"] = 2
        html = metrics.dashboard([report])
        self.assertIn("Intentos", html)
        self.assertNotIn("ci / stage-0", html)
        self.assertIn("runs reanudados", html)

    def test_snapshot_is_capacity_not_usage(self):
        snapshot = metrics.resources(".")
        self.assertEqual(snapshot["measurement"], "start-capacity-not-process-consumption")
        self.assertIn("cpu", snapshot)
        self.assertIn("memory", snapshot)
        self.assertNotIn("environ", snapshot)

    def test_history_per_run_without_raw_events(self):
        with tempfile.TemporaryDirectory() as location:
            report = metrics.project(self.events())
            path = Path(location) / "runs" / "a.json"
            metrics.json_write(path, report)
            self.assertEqual(metrics.history(location)[0]["runId"], "synthetic-run")
            self.assertNotIn("secret-is-not-stored", path.read_text(encoding="utf-8"))


class DslIndexTest(unittest.TestCase):
    def fixture(self, directory):
        base = directory / dsl.ROOT
        base.mkdir(parents=True)
        for source in dsl.SOURCES:
            (base / source).write_text("\n", encoding="utf-8")
        (base / "StageScope.kt").write_text(
            """class StageScope {
            /** Execute a command safely. */
            fun sh(command: String): Unit {}
            fun repeat(count: Int, action: () -> Unit) {}
            private fun secret(): Unit {}
            val decoy = "fun forged(foo: String) {}"
            }
            """, encoding="utf-8"
        )
        return base

    def test_signature_description_is_derived_from_kdoc(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            self.fixture(root)
            index = dsl.generate(root)
            entry = next(e for e in index["entries"] if e["name"] == "sh")
            self.assertEqual(entry["description"], "Execute a command safely.")
            self.assertEqual(entry["returnType"], "Unit")

    def test_signatures_and_receivers(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            self.fixture(root)
            index = dsl.generate(root)
            names = [entry["name"] for entry in index["entries"]]
            self.assertIn("sh", names)
            self.assertIn("repeat", names)
            self.assertNotIn("secret", names)
            self.assertNotIn("forged", names)
            self.assertEqual(next(e for e in index["entries"] if e["name"] == "sh")["receiver"], "StageScope")

    def test_runtime_mapping_is_explicitly_only_a_candidate(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            self.fixture(root)
            index = dsl.generate(root, {"entries": [
                {"name": "sh", "key": "core.sh"},
                {"name": "sh", "key": "alternative.sh"}
            ]})
            row = next(e for e in index["entries"] if e["name"] == "sh")
            self.assertEqual(row["runtimeMapping"], "name-candidates-only")
            self.assertEqual(row["runtimeKeys"], ["alternative.sh", "core.sh"])

    def test_missing_file_refused(self):
        with tempfile.TemporaryDirectory() as name:
            with self.assertRaises(ValueError):
                dsl.generate(Path(name))


class JenkinsSurfaceMaskerTest(unittest.TestCase):
    """The masker of the Jenkins-surface guard blanks char literals.

    A char literal holding a double quote opened a phantom string, and the
    phantom swallowed the declarations that followed it: the key constant of
    a step disappeared from the index and the guard reported a key the tree
    did declare.
    """

    def surface(self):
        return load("check-jenkins-surface.py")

    def test_char_literal_holding_a_quote_does_not_swallow_the_next_declaration(self):
        source = "\n".join([
            "fun quote(value: String): String {",
            "    val out = StringBuilder()",
            "    out.append('\"')",
            "    for (ch in value) {",
            "        when (ch) {",
            "            '\"' -> out.append(\"\\\\\\\"\")",
            "            else -> out.append(ch)",
            "        }",
            "    }",
            "    out.append('\"')",
            "    return out.toString()",
            "}",
            "",
            "val KEY: PluginStepId = PluginStepId(\"core.junit.publishReports\")",
        ])
        mask = self.surface().masked(source)
        self.assertEqual(len(source), len(mask))
        hits = list(self.surface().KEY_CONSTANT.finditer(mask))
        self.assertEqual(1, len(hits))
        self.assertEqual("KEY", source[hits[0].start(1):hits[0].end(1)])
        self.assertEqual(
            "core.junit.publishReports", source[hits[0].start(2):hits[0].end(2)]
        )

    def test_char_literal_holding_an_escaped_quote_blanks_only_its_body(self):
        source = "val tab = '\\t' + '\\'' + '\\\\'\nval KEY: PluginStepId = PluginStepId(\"a.b\")\n"
        mask = self.surface().masked(source)
        self.assertEqual(len(source), len(mask))
        for body in ("\\t", "\\'", "\\\\"):
            start = source.index(body)
            for position in range(start, start + len(body)):
                self.assertEqual(" ", mask[position])
        self.assertEqual(1, len(list(self.surface().KEY_CONSTANT.finditer(mask))))

    def test_raw_string_holding_quotes_and_braces_blanks_its_whole_body(self):
        source = 'val template = """{ "key": KEY }"""\nval KEY: PluginStepId = PluginStepId("x.y")\n'
        mask = self.surface().masked(source)
        self.assertEqual(len(source), len(mask))
        self.assertNotIn("KEY }", mask)
        self.assertEqual(1, len(list(self.surface().KEY_CONSTANT.finditer(mask))))


if __name__ == "__main__":
    unittest.main()
