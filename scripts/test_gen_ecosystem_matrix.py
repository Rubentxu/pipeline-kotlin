#!/usr/bin/env python3
"""Behavioural tests for gen-ecosystem-matrix.py (first suite for this generator).

Hermetic: every source path the generator reads is redirected to a temp tree, so
the real parsing and counting code runs against controlled inputs.
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
SCRIPT = ROOT / "scripts" / "gen-ecosystem-matrix.py"

FACTORY = """
object CoreStepRegistryFactory {
    fun register() {
        CoreEchoStep.registerInto(registry)
        CoreShStep.registerInto(registry)
    }
}
"""

STEP_ECHO = 'val KEY = PluginStepId("core.echo")\n'
STEP_SH = 'val KEY = PluginStepId("core.sh")\n'

RECEIPT = """# WU_LPR_032

| key | descriptor | certification | status | note |
|---|---|---|---|---|
| `core.echo` | Echo | CERTIFIED | **SUPPORTED** | x |
| `core.sh` | Shell | CERTIFIED | **SUPPORTED** | x |
"""

EXTERNAL_STEP = """
object UppercaseStepDefinition : StepDefinition<UppercaseInput> {
    val KEY: PluginStepId = PluginStepId("example.uppercase")
}
"""


def load_module():
    spec = importlib.util.spec_from_file_location("gen_ecosystem_matrix", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Harness(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="ecosystem-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.out = self.tmp / "matrix.md"
        self.factory = self.tmp / "v2/pipeline-application/src/main/kotlin/CoreStepRegistryFactory.kt"
        self.factory.parent.mkdir(parents=True)
        self.factory.write_text(FACTORY)
        (self.factory.parent / "CoreEchoStep.kt").write_text(STEP_ECHO)
        (self.factory.parent / "CoreShStep.kt").write_text(STEP_SH)
        self.receipt = self.tmp / "docs/v2/07-uat/WU_LPR_032_RECEIPT.md"
        self.receipt.parent.mkdir(parents=True)
        self.receipt.write_text(RECEIPT)
        self.ledger = self.tmp / "docs/v2/07-uat/WU_LPR_060_CERTIFICATION_LEDGER.md"
        self.ledger.write_text("SUPPORTED_CERTIFIED: **2**\n")
        self.examples = self.tmp / "examples"
        d = self.examples / "example-uppercase/src/main/kotlin"
        d.mkdir(parents=True)
        (d / "UppercaseStepDefinition.kt").write_text(EXTERNAL_STEP)

    def _patch(self):
        return [
            mock.patch.object(self.mod, "ROOT", self.tmp),
            mock.patch.object(self.mod, "FACTORY", self.factory),
            mock.patch.object(self.mod, "RECEIPT_032", self.receipt),
            mock.patch.object(self.mod, "LEDGER", self.ledger),
            mock.patch.object(self.mod, "EXTERNAL_PLUGINS_DIR", self.examples),
            mock.patch.object(self.mod, "OUT", self.out),
        ]

    def run_generator(self):
        buf = io.StringIO()
        import contextlib as _ctx
        patches = self._patch()
        for p in patches:
            p.start()
        try:
            with _ctx.redirect_stdout(buf):
                self.mod.main()
        finally:
            for p in patches:
                p.stop()
        return buf.getvalue()


class Counting(Harness):
    def test_core_and_external_are_counted_and_never_summed(self) -> None:
        """Claim: the CORE total and the orchestration total are independent.

        Mutation: sum them; the matrix would claim a StepRegistry larger than
        it is (and the file itself says they live in different namespaces).
        """
        out = self.run_generator()
        text = self.out.read_text()
        self.assertIn("## 1. CORE StepDefinitions", text)
        self.assertIn("Count: **2**", text)
        self.assertIn("`core.echo`", text)
        self.assertIn("`example.uppercase`", text)
        self.assertIn("SUPPORTED_CERTIFIED: **2**", text)
        self.assertIn(f"Orchestration blocks: {len(self.mod.ORCHESTRATION_BLOCKS)}", out)
        self.assertIn("**Not summed**", text)

    def test_ledger_absent_is_reported_as_none(self) -> None:
        """Claim: a missing ledger is visible in the output, not silently zero."""
        self.ledger.unlink()
        self.run_generator()
        self.assertIn("SUPPORTED_CERTIFIED = None", self.out.read_text())

    def test_external_step_without_key_is_not_listed(self) -> None:
        """Claim: an external plugin needs BOTH a key and a StepDefinition class."""
        shutil.rmtree(self.examples / "example-uppercase")
        d = self.examples / "example-broken/src/main/kotlin"
        d.mkdir(parents=True)
        (d / "BrokenStepDefinition.kt").write_text("object BrokenStepDefinition : StepDefinition<X>\n")
        self.run_generator()
        text = self.out.read_text()
        self.assertIn("EXTERNAL_REFERENCE StepDefinitions", text)
        self.assertIn("Count: **0**", text)
        self.assertIn("(none)", text)


class FailLoud(Harness):
    def test_missing_step_source_fails_loud(self) -> None:
        """Claim: a factory entry without source cannot produce a matrix.

        Mutation: skip unknown classes; the matrix would silently under-count.
        """
        self.factory.write_text(
            "object CoreStepRegistryFactory { CoreGhostStep.registerInto(registry) }\n")
        with self.assertRaises(SystemExit) as ctx:
            self.run_generator()
        self.assertIn("FAIL-LOUD", str(ctx.exception))
        self.assertFalse(self.out.exists(), "nothing is written on failure")

    def test_core_key_without_admission_row_fails_loud(self) -> None:
        """Claim: every CORE key must appear in the admission receipt."""
        self.receipt.write_text(RECEIPT.replace("| `core.sh` | Shell | CERTIFIED | **SUPPORTED** | x |\n", ""))
        with self.assertRaises(SystemExit) as ctx:
            self.run_generator()
        self.assertIn("missing from WU_LPR_032 admission", str(ctx.exception))

    def test_step_source_without_key_constant_fails_loud(self) -> None:
        (self.factory.parent / "CoreShStep.kt").write_text("val x = 1\n")
        with self.assertRaises(SystemExit) as ctx:
            self.run_generator()
        self.assertIn("no StepKey constant", str(ctx.exception))


if __name__ == "__main__":
    unittest.main(verbosity=2)
