#!/usr/bin/env python3
"""Behavioural tests for gen-disabled-inventory.py.

The generator has no tests until now, and its own documented law says an
unlisted non-historical @Disabled entry must make the run fail. These tests
pin exactly that, plus the zero-scan and declared-classification-observed
invariants, using hermetic temp trees and the real module (imported, not
reimplemented).
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import pathlib
import re
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "scripts" / "gen-disabled-inventory.py"

KOTLIN = """
package fixture

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class FixtureTest {{
    @Disabled("{reason}")
    fun `{method}`() {{
    }}
}}
"""


def load_module():
    """Import the hyphenated script fresh, so each test owns its globals."""
    spec = importlib.util.spec_from_file_location("gdi", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Harness(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)

    def git_repo(self) -> pathlib.Path:
        subprocess.run(["git", "init", "-q", "--initial-branch=main"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.email", "t@t"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.name", "t"], cwd=self.root, check=True)
        subprocess.run(["git", "commit", "--allow-empty", "-q", "-m", "init"], cwd=self.root, check=True)
        return self.root

    def write_test(self, rel: str, *, reason: str, method: str = "does something") -> pathlib.Path:
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(KOTLIN.format(reason=reason, method=method), encoding="utf-8")
        return p

    def run_generator(self, module, *, test_root: pathlib.Path | None = None):
        module.ROOT = str(self.root)
        module.TEST_ROOT = str(test_root if test_root is not None else self.root / "v2")
        module.OUT = str(self.root / "out.md")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            code = module.main()
        return code, pathlib.Path(module.OUT), err.getvalue()


class UnclassifiedEntries(Harness):
    def test_unclassified_non_archived_entry_fails_and_writes_nothing(self) -> None:
        """Claim: an entry the classic pattern does not explain cannot default to HISTORICAL.

        Mutation: restore `return "HISTORICAL", ...` as the fallback. The run
        then green-passes while a MANDATORY_SUPPORTED entry is miscounted as
        archived evidence — the defect the docstring says was fixed.
        """
        self.git_repo()
        self.write_test(
            "v2/mod-a/src/test/kotlin/FixtureTest.kt",
            reason="MANDATORY_SUPPORTED: gate-critical, must converge to zero",
        )
        module = load_module()
        module.CLASSIFICATION = {}
        code, out, err = self.run_generator(module)
        self.assertEqual(code, 2, "an unclassified entry must fail the run")
        self.assertFalse(out.exists(), "a failed run must not publish an inventory")
        self.assertIn("unclassified", err)
        self.assertIn("FixtureTest.kt:8", err, "the diagnostic must name file:line")

    def test_archived_reason_is_historical_and_passes(self) -> None:
        """Claim: the documented archive pattern is the only benign default.

        Mutation: drop the archive regex so every unlisted entry fails,
        making the generator unusable on 64 archived entries.
        """
        self.git_repo()
        self.write_test(
            "v2/mod-a/src/test/kotlin/FixtureTest.kt",
            reason="Historical migration snapshot evidence",
        )
        module = load_module()
        module.CLASSIFICATION = {}
        code, out, err = self.run_generator(module)
        self.assertEqual(code, 0, err)
        text = out.read_text(encoding="utf-8")
        self.assertRegex(text, r"HISTORICAL\s+= 1")
        self.assertIn("Gate-1 @Disabled state         = not blocked", text)


class ZeroEvidence(Harness):
    def test_missing_scan_root_fails(self) -> None:
        """Claim: scanning nothing is not '0 disabled entries'.

        Mutation: rely on os.walk's silent empty result. A wrong TEST_ROOT
        then reports `@Disabled total = 0` and 'not blocked', exit 0.
        """
        self.git_repo()
        module = load_module()
        module.CLASSIFICATION = {}
        code, out, err = self.run_generator(module, test_root=self.root / "v2-DOES-NOT-EXIST")
        self.assertEqual(code, 2)
        self.assertFalse(out.exists())
        self.assertIn("scan root does not exist", err)

    def test_empty_scan_root_fails(self) -> None:
        """Claim: a tree with no Kotlin test sources is a scan failure.

        Mutation: accept scanned_files == 0.
        """
        self.git_repo()
        (self.root / "v2").mkdir()
        module = load_module()
        module.CLASSIFICATION = {}
        code, out, err = self.run_generator(module)
        self.assertEqual(code, 2)
        self.assertFalse(out.exists())
        self.assertIn("no Kotlin test sources", err)


class DeclaredClassifications(Harness):
    def test_declared_classification_must_be_observed(self) -> None:
        """Claim: CLASSIFICATION cannot silently outlive its target.

        Mutation: drop the observed check. A renamed test or edited reason
        makes the declared COMPATIBILITY expected 2 -> observed 0 without error.
        """
        self.git_repo()
        self.write_test(
            "v2/mod-a/src/test/kotlin/FixtureTest.kt",
            reason="Historical snapshot",
            method="renamed method",
        )
        module = load_module()
        module.CLASSIFICATION = {
            "v2/mod-a/src/test/kotlin/FixtureTest.kt": [
                ("original method", "COMPATIBILITY", "declared reason", "no"),
            ],
        }
        code, _out, err = self.run_generator(module)
        self.assertEqual(code, 2, "an unobserved declaration must fail the run")
        self.assertIn("not observed", err)
        self.assertIn("original method", err)

    def test_observed_declared_classification_is_counted(self) -> None:
        """Claim: a matching declared entry is classified as declared.

        Mutation: stop consulting CLASSIFICATION, so the declared
        COMPATIBILITY entry falls into the archive branch.
        """
        self.git_repo()
        self.write_test(
            "v2/mod-a/src/test/kotlin/FixtureTest.kt",
            reason="DSL classpath: CredentialsId not accessible",
            method="declared method",
        )
        module = load_module()
        module.CLASSIFICATION = {
            "v2/mod-a/src/test/kotlin/FixtureTest.kt": [
                ("declared method", "COMPATIBILITY", "declared reason", "no"),
            ],
        }
        code, out, err = self.run_generator(module)
        self.assertEqual(code, 0, err)
        text = out.read_text(encoding="utf-8")
        self.assertRegex(text, r"COMPATIBILITY\s+= 1")
        self.assertIn("declared reason", text)


class GitProvenance(Harness):
    def test_git_unavailable_fails(self) -> None:
        """Claim: a generated inventory always names the tree it scanned.

        Mutation: keep the unchecked subprocess call; an empty SHA line is
        written and the document claims provenance it does not have.
        """
        self.write_test(
            "v2/mod-a/src/test/kotlin/FixtureTest.kt",
            reason="Historical snapshot",
        )
        module = load_module()
        module.CLASSIFICATION = {}
        code, out, err = self.run_generator(module)  # no git repo in self.root
        self.assertEqual(code, 2)
        self.assertFalse(out.exists())
        self.assertIn("git rev-parse", err)


if __name__ == "__main__":
    unittest.main(verbosity=2)
