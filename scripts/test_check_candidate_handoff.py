#!/usr/bin/env python3
"""Tests for check-candidate-handoff.py.

Each test states the claim it defends and the mutation that kills it. The
subject is executed as a subprocess against handoffs written into a temporary
directory, so nothing here reimplements the checker.

The SHA used is the repository's real HEAD so that the ancestry message can
be exercised against real Git rather than a fabricated history.
"""

from __future__ import annotations

import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOOL = ROOT / "scripts" / "check-candidate-handoff.py"
HEAD = subprocess.run(
    ["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True
).stdout.strip()

PAYLOAD = b"pretend-zip-bytes"


class Harness(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        d = pathlib.Path(self.tmp.name)
        self.handoff = d / "handoff.json"
        self.zip = d / "candidate.zip"
        self.zip.write_bytes(PAYLOAD)
        self.digest = hashlib.sha256(PAYLOAD).hexdigest()

    def write_handoff(self, **overrides) -> dict:
        data = {
            "schema_version": "pipelinek-candidate-2",
            "candidate_id": f"sha256:{self.digest}",
            "release_train": "0.48.0",
            "candidate_sequence": 1,
            "product_version": "0.48.0",
            "source_commit": HEAD,
            "artifact": {
                "name": "pipelinek-0.48.0.zip",
                "sha256": self.digest,
                "size": len(PAYLOAD),
                "archive_root": "pipelinek-0.48.0",
                "implementation_version": "0.48.0",
            },
            "distribution_manifest": {
                "name": "distribution-manifest.json",
                "sha256": "b" * 64,
                "built_at": HEAD,
            },
            "sbom": {
                "name": "pipelinek-0.48.0.sbom.json",
                "sha256": "c" * 64,
                "built_at": HEAD,
            },
        }
        for k, v in overrides.items():
            if v is None:
                data.pop(k, None)
            elif isinstance(v, dict) and isinstance(data.get(k), dict):
                data[k] = {**data[k], **v}
            else:
                data[k] = v
        self.handoff.write_text(json.dumps(data, indent=2), encoding="utf-8")
        return data

    def run_check(self, head: str | None = None) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                sys.executable,
                str(TOOL),
                "--head",
                head or HEAD,
                "--handoff",
                str(self.handoff),
                "--zip",
                str(self.zip),
            ],
            capture_output=True,
            text=True,
            cwd=ROOT,
        )


class Accepts(Harness):
    def test_matching_handoff_is_accepted(self) -> None:
        """Claim: a handoff for exactly this tree promotes.

        Mutation: require the handoff to be a descendant of HEAD instead of
        equal, so this legitimate case is refused.
        """
        self.write_handoff()
        r = self.run_check()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("OK", r.stdout)


class RefusesStale(Harness):
    def test_ancestor_handoff_is_refused(self) -> None:
        """Claim: a handoff from history is NOT evidence for this tree.

        This is the actual defect: the on-disk handoff names 6f97d9ed, a real
        ancestor of HEAD, and promotion must fail closed.

        Mutation: accept any ancestor (`git merge-base --is-ancestor`), which
        is precisely the inheritance this guard exists to refuse.
        """
        self.write_handoff(source_commit="6f97d9eda43a47aef448e0b59cd7ead68ca151f3")
        r = self.run_check()
        self.assertEqual(r.returncode, 1, "an ancestor handoff must be refused")
        self.assertIn("R-C2", r.stdout)
        self.assertIn("ancestor", r.stdout)

    def test_unrelated_handoff_is_refused(self) -> None:
        """Claim: an unrelated SHA is refused too, with a different wording.

        Mutation: treat any parseable 40-hex as acceptable.
        """
        self.write_handoff(source_commit="f" * 40)
        r = self.run_check()
        self.assertEqual(r.returncode, 1)
        self.assertIn("R-C2", r.stdout)
        self.assertIn("unrelated", r.stdout)


class RefusesPartialTruth(Harness):
    def test_manifest_built_at_from_another_commit_is_refused(self) -> None:
        """Claim: the manifest cannot attest to another commit's bytes.

        Mutation: drop the R-C3 loop, so only source_commit is checked.
        """
        self.write_handoff(distribution_manifest={"built_at": "6f97d9eda43a47aef448e0b59cd7ead68ca151f3"})
        r = self.run_check()
        self.assertEqual(r.returncode, 1)
        self.assertIn("R-C3", r.stdout)

    def test_sbom_built_at_from_another_commit_is_refused(self) -> None:
        """Claim: the SBOM is checked too, not only the manifest.

        Mutation: iterate only distribution_manifest.
        """
        self.write_handoff(sbom={"built_at": "6f97d9eda43a47aef448e0b59cd7ead68ca151f3"})
        r = self.run_check()
        self.assertEqual(r.returncode, 1)
        self.assertIn("R-C3", r.stdout)


class RefusesFabrication(Harness):
    def test_claimed_digest_not_matching_the_zip_is_refused(self) -> None:
        """Claim: a handoff cannot claim bytes that were never built.

        Mutation: trust the handoff's own sha256 field without reading the ZIP.
        """
        self.write_handoff(artifact={"sha256": "d" * 64})
        r = self.run_check()
        self.assertEqual(r.returncode, 1)
        self.assertIn("R-C4", r.stdout)

    def test_missing_zip_is_refused(self) -> None:
        """Claim: a claimed artifact with no bytes is refused.

        Mutation: return OK when the ZIP is absent.
        """
        self.write_handoff()
        self.zip.unlink()
        r = self.run_check()
        self.assertEqual(r.returncode, 1)
        self.assertIn("R-C4", r.stdout)

    def test_missing_handoff_is_refused(self) -> None:
        """Claim: absence of a handoff is a refusal, not a pass.

        Mutation: return OK when the file is missing.
        """
        self.write_handoff()
        self.handoff.unlink()
        r = self.run_check()
        self.assertEqual(r.returncode, 1)
        self.assertIn("R-C1", r.stdout)

    def test_malformed_handoff_is_refused(self) -> None:
        """Claim: unparseable evidence is refused, not skipped.

        Mutation: treat a JSON error as an empty dict and continue.
        """
        self.handoff.write_text("{not json", encoding="utf-8")
        r = self.run_check()
        self.assertEqual(r.returncode, 1)
        self.assertIn("R-C1", r.stdout)

    def test_handoff_without_source_commit_is_refused(self) -> None:
        """Claim: a handoff that names no tree cannot promote anything.

        Mutation: default a missing source_commit to HEAD.
        """
        self.write_handoff(source_commit=None)
        r = self.run_check()
        self.assertEqual(r.returncode, 1)
        self.assertIn("R-C2", r.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
