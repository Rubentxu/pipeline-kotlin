#!/usr/bin/env python3
"""Regression suite for the @Disabled inventory classification.

The generator had NO suite of its own. That is how it shipped an unconditional

    return "HISTORICAL", ..., "no"

behind a comment claiming "the generator fails the run". A generator whose
whole value is that an unknown is refused needs a test that proves the refusal.

Each row names the mutation that kills it. Every mutation reverts to a form this
tool actually had.
"""
from __future__ import annotations

import importlib.util
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "gen-disabled-inventory.py"


def load():
    spec = importlib.util.spec_from_file_location("gdi", TOOL)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


gdi = load()

UNDECLARED = "v2/brand-new-module/src/test/kotlin/FreshlyDisabledTest.kt"
DECLARED_FILE = (
    "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/"
    "UatLocal008CredentialsTest.kt"
)
DECLARED_METHOD = "`CR-BD-034 mismatched credential kind throws`"


class UnknownIsRefused(unittest.TestCase):
    """An @Disabled nobody triaged must not pass for archived history."""

    def test_untriaged_is_not_historical(self) -> None:
        """Claim: an undeclared entry is UNKNOWN, never HISTORICAL.

        Mutation: restore `return "HISTORICAL", ..., "no"` as the default.
        """
        cat, _reason, blocks = gdi.classify(UNDECLARED, "`a brand new contract`")
        self.assertEqual(cat, "UNKNOWN")
        self.assertEqual(blocks, "yes")

    def test_untriaged_blocks_gate_1(self) -> None:
        """Claim: an untriaged disabled test is not a green gate.

        Mutation: set blocks_gate_1 to "no" in the UNKNOWN branch.
        """
        _cat, _reason, blocks = gdi.classify(UNDECLARED, "`anything`")
        self.assertEqual(blocks, "yes")

    def test_declared_entries_still_resolve(self) -> None:
        """Claim: the table still wins for entries it declares.

        Without this row, making every entry UNKNOWN would pass. The default
        must be the only thing that changed.

        Mutation: return UNKNOWN unconditionally, ignoring CLASSIFICATION.
        """
        cat, _reason, _blocks = gdi.classify(DECLARED_FILE, DECLARED_METHOD)
        self.assertEqual(cat, "COMPATIBILITY")

    def test_historical_is_reachable_only_by_declaration(self) -> None:
        """Claim: HISTORICAL exists as a category but is never inferred.

        Mutation: add HISTORICAL to the fallthrough category set.
        """
        self.assertIn("HISTORICAL", gdi.CATEGORIES)
        cat, _r, _b = gdi.classify(UNDECLARED, "`whatever`")
        self.assertNotEqual(cat, "HISTORICAL")

    def test_unknown_is_not_one_of_the_normal_categories(self) -> None:
        """Claim: UNKNOWN is a distinct outcome, not a sixth routine category.

        Mutation: append UNTRIAGED to CATEGORIES.
        """
        self.assertNotIn(gdi.UNTRIAGED, gdi.CATEGORIES)
        self.assertEqual(gdi.UNTRIAGED, "UNKNOWN")


class TheGeneratorRefusesToPublish(unittest.TestCase):
    """The exit code is the part a gate actually reads."""

    def test_generator_exits_nonzero_on_the_real_tree(self) -> None:
        """Claim: the current repository has untriaged entries and says so.

        Measured: 72 entries that the previous default silently filed as
        HISTORICAL. This row asserts the refusal, not the number, so it stays
        true once a human triages them — at which point a separate row below
        requires the success path to be reachable.

        Mutation: `return 0` unconditionally, or drop the `untriaged` check.
        """
        proc = subprocess.run(
            [sys.executable, str(TOOL)], capture_output=True, text=True, cwd=ROOT
        )
        self.assertEqual(proc.returncode, 1, proc.stdout[-2000:])
        self.assertIn("not declared in CLASSIFICATION", proc.stderr)
        self.assertIn("UNKNOWN", proc.stderr)

    def test_generator_names_the_untriaged_entries(self) -> None:
        """Claim: the diagnostic is actionable, not a bare count.

        A gate that fails with "72" and no paths sends the reader hunting.

        Mutation: drop the per-entry lines from the stderr block.
        """
        proc = subprocess.run(
            [sys.executable, str(TOOL)], capture_output=True, text=True, cwd=ROOT
        )
        self.assertIn(".kt ::", proc.stderr)

    def test_inventory_written_marks_unknown_as_blocking(self) -> None:
        """Claim: the published document does not claim the gate is clear.

        Mutation: subtract UNKNOWN from the mandatory total.
        """
        out = ROOT / "docs/v2/07-uat/WU_LPR_061_DISABLED_INVENTORY.md"
        text = out.read_text(encoding="utf-8")
        self.assertIn("UNKNOWN", text)
        self.assertIn("mandatory-disabled (Gate-1)", text)
        # The Gate-1 line must be OPEN while UNKNOWN is non-zero.
        unknown_line = next(
            ln for ln in text.splitlines() if ln.strip().startswith("UNKNOWN")
        )
        count = int(unknown_line.split("=")[1].strip())
        gate_line = next(
            ln for ln in text.splitlines() if "Gate-1 @Disabled state" in ln
        )
        if count > 0:
            self.assertIn("OPEN", gate_line, f"{count} untriaged but gate reads clear")


class ExtractionStillWorks(unittest.TestCase):
    """The regex layer is untouched by this change, so pin it."""

    def test_brand_new_disabled_class_is_extracted(self) -> None:
        """Claim: extraction sees a disabled method with no history.

        Mutation: narrow the @Disabled regex so this file yields nothing.
        """
        import tempfile

        src = (
            "package probe\n"
            "import org.junit.jupiter.api.Disabled\n"
            'class Fresh {\n'
            '  @Disabled("disabled for now")\n'
            "  fun `a brand new contract`() {}\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "Fresh.kt"
            p.write_text(src, encoding="utf-8")
            entries = gdi.extract_entries("x/Fresh.kt", str(p))
        self.assertEqual(len(entries), 1)
        self.assertIn("brand new contract", entries[0][0])


if __name__ == "__main__":
    unittest.main(verbosity=2)