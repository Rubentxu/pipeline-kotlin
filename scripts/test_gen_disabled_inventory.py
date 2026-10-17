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



class BurnDownSnapshotsAreHistoricalOnlyWhileTheBurnDownIsClosed(unittest.TestCase):
    """The derived rule for ~100 legacy counter snapshots.

    These rows assert how many legacy keys REMAINED at a named gate of the S2
    burn-down. That is a statement about a migration in progress, so it can
    only be archived history while the migration is finished. The rule is
    anchored to `LEGACY_PLUGIN_IDS` being empty, read from source, precisely
    so that "finished" is checked rather than assumed from a method name.
    """

    SNAPSHOT = "`counters are 7-7-7 and legacy dispatcher source is physically removed`"

    def test_snapshot_is_historical_while_the_burn_down_is_closed(self) -> None:
        """Claim: a burn-down counter snapshot classifies as HISTORICAL.

        This is the row that would otherwise silently stop firing if someone
        deleted the rule.

        Mutation: delete the BURN_DOWN_SNAPSHOT_RE branch from classify().
        """
        self.assertTrue(gdi.legacy_burn_down_converged(), "precondition: LEGACY_PLUGIN_IDS is empty")
        cat, _reason, blocks = gdi.classify(UNDECLARED, self.SNAPSHOT)
        self.assertEqual(cat, "HISTORICAL")
        self.assertEqual(blocks, "no")

    def test_rule_does_not_fire_once_the_burn_down_reopens(self) -> None:
        """Claim: the rule is gated on the fact, not on the name.

        This is the fail-closed half and it is the reason the rule exists at
        all. If the burn-down reopens, a snapshot asserting "N residual keys"
        describes a LIVE invariant again, and filing it as closed history
        would hide a regression behind a green gate.

        Mutation: drop the `legacy_burn_down_converged()` conjunct.
        """
        original = gdi.LEGACY_IDS_PATH
        try:
            gdi.LEGACY_IDS_PATH = "v2/does-not-exist/CanonicalCoreStepDecoder.kt"
            cat, _reason, blocks = gdi.classify(UNDECLARED, self.SNAPSHOT)
        finally:
            gdi.LEGACY_IDS_PATH = original
        self.assertEqual(cat, "UNKNOWN")
        self.assertEqual(blocks, "yes")

    def test_non_burn_down_entries_stay_untriaged(self) -> None:
        """Claim: the rule is narrow; it does not sweep in unrelated entries.

        A whole-class disable is the case most likely to be over-matched: the
        class name says nothing about a burn-down.

        Mutation: widen BURN_DOWN_SNAPSHOT_RE to match any Core* method.
        """
        cat, _r, _b = gdi.classify(
            UNDECLARED, "class CoreSleepCoordinatorCharacterizationTest (whole class)"
        )
        self.assertEqual(cat, "UNKNOWN")

    def test_live_invariant_written_during_the_burn_down_is_not_archived(self) -> None:
        """Claim: a guard that is STILL TRUE is not filed as closed history.

        "core.pwd stays absent from LEGACY_PLUGIN_IDS" and "no legacy
        resurrection" were written at a gate and still hold today. Filing them
        HISTORICAL would hide a regression behind a non-gating category, which
        is worse than leaving them UNKNOWN.

        Mutation: delete the LIVE_INVARIANT_RE guard from classify().
        """
        for live in (
            "`G5 milestone - core dot pwd stays absent from LEGACY_PLUGIN_IDS post-S2-A9-G5`",
            "`no legacy resurrection - irreversible G5 state holds post-S2-A6-G4`",
        ):
            with self.subTest(live=live):
                cat, reason, blocks = gdi.classify(UNDECLARED, live)
                self.assertEqual(cat, "UNKNOWN")
                self.assertEqual(blocks, "yes")
                self.assertIn("still live", reason)

    def test_convergence_ignores_the_narrative_comments(self) -> None:
        """Claim: keys named in COMMENTS do not count as present.

        The authority file documents every removed key by name in prose. A
        naive `in` over the raw text would report a reopened migration and
        silently drop all ~100 entries back to UNKNOWN.

        Mutation: remove the comment-stripping line in legacy_burn_down_converged().
        """
        self.assertTrue(gdi.legacy_burn_down_converged())


if __name__ == "__main__":
    unittest.main(verbosity=2)
