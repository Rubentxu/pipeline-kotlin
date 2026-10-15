#!/usr/bin/env python3
"""Behavioural tests for regenerate-step-inventory.py (first suite for this generator).

Hermetic: the pure parsers, classifiers, renderer and drift check are exercised
against temp fixtures; the repository-wide walk is not needed for those contracts.
"""
from __future__ import annotations

import importlib.util
import io
import pathlib
import shutil
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "regenerate-step-inventory.py"


def load_module():
    spec = importlib.util.spec_from_file_location("regenerate_step_inventory", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Fixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="inventory-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def write(self, name: str, body: str) -> pathlib.Path:
        p = self.tmp / name
        p.write_text(body)
        return p


class Parsers(Fixture):
    def test_registered_core_steps_are_extracted_in_order(self) -> None:
        p = self.write("CoreStepRegistryFactory.kt",
                       "CoreEchoStep.registerInto(this)\nCoreShStep.registerInto(this)\n")
        self.assertEqual(self.mod.parse_registered_core_steps(p), ["Echo", "Sh"])

    def test_legacy_plugin_ids_strip_comments_and_dedup(self) -> None:
        """Claim: a commented entry is not a registered legacy id.

        Mutation: drop the comment stripping; a commented-out key would count as
        live legacy debt and the inventory would over-report it forever.
        """
        p = self.write("Legacy.kt", '''val LEGACY_PLUGIN_IDS: Set<String> = setOf(
    "core.pwd", // retired in WU-RP-088
    "core.pwd.tmp",
    "core.pwd",
)
''')
        self.assertEqual(self.mod.parse_legacy_plugin_ids(p), ["core.pwd", "core.pwd.tmp"])

    def test_absent_legacy_block_is_empty_not_an_error(self) -> None:
        p = self.write("Legacy.kt", "val OTHER = 1\n")
        self.assertEqual(self.mod.parse_legacy_plugin_ids(p), [])

    def test_legacy_metadata_rows_strip_comments(self) -> None:
        p = self.write("Metadata.kt", '''private val table: Map<String, StepMetadata> = mapOf(
    "core.echo" to EchoSpec, // legacy
    "core.sh" to ShSpec,
)
''')
        self.assertEqual(self.mod.parse_legacy_metadata_rows(p), ["core.echo", "core.sh"])

    def test_legacy_dispatcher_keys_are_found(self) -> None:
        p = self.write("Dispatcher.kt",
                       'when (k) { PluginStepId("core.echo") -> a; PluginStepId("core.sh") -> b }\n')
        self.assertEqual(self.mod.parse_legacy_dispatcher_keys(p),
                         ["core.echo", "core.sh"])

    def test_external_step_keys_dedup(self) -> None:
        p = self.write("Ext.kt",
                       'val A = PluginStepId("example.uppercase")\n'
                       'val B = PluginStepId("example.uppercase")\n'
                       'val C = PluginStepId("example.other")\n')
        self.assertEqual(sorted(self.mod.parse_external_step_keys(p)),
                         ["example.other", "example.uppercase"])


class Classification(Fixture):
    def test_precedence_blocked_deferred_certified_registered(self) -> None:
        with mock.patch.dict(self.mod.BLOCKED_STEPS, {"k.blocked": ("reason", "note")}), \
             mock.patch.dict(self.mod.DEFERRED_STEPS, {"k.deferred": ("scope", "note")}), \
             mock.patch.dict(self.mod.CERTIFIED_RECEIPTS, {"k.cert": "RECEIPT.md"}):
            self.assertTrue(self.mod.classify_core_step("k.blocked").startswith("BLOCKED"))
            self.assertTrue(self.mod.classify_core_step("k.deferred").startswith("DEFERRED"))
            self.assertEqual(self.mod.classify_core_step("k.cert"), "CERTIFIED_AT_SHA")
            self.assertEqual(self.mod.classify_core_step("k.plain"), "REGISTERED")
            self.assertEqual(self.mod.receipt_for("k.cert"), "RECEIPT.md")
            self.assertEqual(self.mod.receipt_for("k.plain"), "—")

    def test_notes_are_scoped_to_the_registry_tables(self) -> None:
        with mock.patch.dict(self.mod.BLOCKED_STEPS, {"k.b": ("r", "blocked note")}), \
             mock.patch.dict(self.mod.DEFERRED_STEPS, {"k.d": ("s", "deferred note")}), \
             mock.patch.dict(self.mod.TIER_B_TBD, {"k.t": "tier b note"}):
            self.assertEqual(self.mod.notes_for("k.b"), "blocked note")
            self.assertEqual(self.mod.notes_for("k.d"), "deferred note")
            self.assertEqual(self.mod.notes_for("k.t"), "tier b note")
            self.assertEqual(self.mod.notes_for("k.x"), "")


class DriftCheck(Fixture):
    def _inv(self, **overrides) -> dict:
        inv = {"head_sha": "0" * 40, "core_keys": ["core.echo"],
               "legacy_plugin_ids": [], "legacy_metadata_rows": [],
               "legacy_dispatcher_keys": [], "sdk_keys": [], "external_keys": []}
        inv.update(overrides)
        return inv

    def test_clean_inventory_has_no_drift(self) -> None:
        with mock.patch.dict(self.mod.CERTIFIED_RECEIPTS, {}):
            self.assertEqual(self.mod.main_check(self._inv()), 0)

    def test_each_legacy_source_reports_drift(self) -> None:
        """Claim: any remaining legacy path is drift, one error per class."""
        err = io.StringIO()
        with mock.patch.dict(self.mod.CERTIFIED_RECEIPTS, {}), \
             mock.patch("sys.stderr", err):
            errors = self.mod.main_check(self._inv(
                legacy_plugin_ids=["core.pwd"],
                legacy_metadata_rows=["core.echo"],
                legacy_dispatcher_keys=["core.echo"]))
        self.assertEqual(errors, 3)
        self.assertIn("LEGACY_PLUGIN_IDS not empty", err.getvalue())
        self.assertIn("table not empty", err.getvalue())
        self.assertIn("when-cases not empty", err.getvalue())

    def test_empty_core_inventory_is_drift(self) -> None:
        """Claim: an inventory with no CORE keys cannot be a green inventory."""
        err = io.StringIO()
        with mock.patch.dict(self.mod.CERTIFIED_RECEIPTS, {}), \
             mock.patch("sys.stderr", err):
            errors = self.mod.main_check(self._inv(core_keys=[]))
        self.assertEqual(errors, 1)
        self.assertIn("CORE step inventory is empty", err.getvalue())


class Rendering(Fixture):
    def test_render_lists_core_keys_with_their_classification(self) -> None:
        inv = {"head_sha": "abc1234", "generated_at": "2026-10-15T00:00:00+00:00",
               "core_keys": ["core.echo", "core.sh"], "legacy_plugin_ids": [],
               "legacy_metadata_rows": [], "legacy_dispatcher_keys": [],
               "sdk_keys": [("http.request", "v2/.../HttpRequestStep.kt")],
               "external_keys": ["example.uppercase"]}
        with mock.patch.dict(self.mod.CERTIFIED_RECEIPTS, {"core.echo": "R.md"}), \
             mock.patch.dict(self.mod.BLOCKED_STEPS, {}), \
             mock.patch.dict(self.mod.DEFERRED_STEPS, {}):
            md = self.mod.render_markdown(inv)
        self.assertIn("core.echo", md)
        self.assertIn("CERTIFIED_AT_SHA", md)
        self.assertIn("core.sh", md)
        self.assertIn("REGISTERED", md)
        self.assertIn("http.request", md)
        self.assertIn("example.uppercase", md)


if __name__ == "__main__":
    unittest.main(verbosity=2)
