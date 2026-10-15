#!/usr/bin/env python3
"""Behavioural tests for release/sbom-cyclonedx.py (first suite for this tool).

Hermetic: a real ZIP is built in a temp directory, so member classification,
digests and the CycloneDX shape are measured against actual bytes.
"""
from __future__ import annotations

import hashlib
import importlib.util
import pathlib
import re
import shutil
import tempfile
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SCRIPT = ROOT / "scripts" / "release" / "sbom-cyclonedx.py"


def load_module():
    spec = importlib.util.spec_from_file_location("sbom_cyclonedx", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Fixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = load_module()

    def setUp(self) -> None:
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="sbom-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.root = "pipelinek-0.61.0"
        self.zip_path = self.tmp / "pipelinek-0.61.0.zip"
        self.members = {
            f"{self.root}/lib/pipeline-domain-0.61.0.jar": b"jar-bytes-domain",
            f"{self.root}/lib/pipeline-step-sdk-runtime-0.61.0.jar": b"jar-bytes-sdk",
            f"{self.root}/bin/pipelinek": b"#!/bin/sh\n",
            f"{self.root}/META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n",
            f"{self.root}/README.md": b"# pipelinek\n",
        }
        with zipfile.ZipFile(self.zip_path, "w") as zf:
            for name, body in self.members.items():
                zf.writestr(name, body)


class Classification(Fixture):
    def test_lib_jar_is_a_library_with_maven_purl(self) -> None:
        kind, purl = self.mod.classify_member(
            f"{self.root}/lib/pipeline-domain-0.61.0.jar")
        self.assertEqual(kind, "library")
        self.assertEqual(purl, "pkg:maven/pipeline-domain/0.61.0")

    def test_unversioned_lib_jar_is_a_library_without_purl(self) -> None:
        kind, purl = self.mod.classify_member(f"{self.root}/lib/weird.jar")
        self.assertEqual(kind, "library")
        self.assertEqual(purl, "")

    def test_bin_and_meta_inf_classes(self) -> None:
        self.assertEqual(self.mod.classify_member(f"{self.root}/bin/pipelinek"), ("file", ""))
        self.assertEqual(self.mod.classify_member(f"{self.root}/META-INF/MANIFEST.MF"),
                         ("data", ""))
        self.assertEqual(self.mod.classify_member(f"{self.root}/README.md"), ("data", ""))

    def test_maven_split(self) -> None:
        self.assertEqual(self.mod.maven_name_from_jar_name("pipeline-domain-0.61.0.jar"),
                         ("pipeline-domain", "0.61.0"))
        self.assertEqual(self.mod.maven_name_from_jar_name("weird.jar"), ("weird", ""))


class SbomShape(Fixture):
    def build(self) -> dict:
        return self.mod.build_sbom(str(self.zip_path), "0.61.0", "deadbeef", "v0.61.0-rc2")

    def test_components_cover_libs_and_bin_and_exclude_data(self) -> None:
        """Claim: the SBOM describes executables and libraries, not every file.

        Mutation: include `data` members; the component list would be padded
        with manifests and README files that are not deployable units.
        """
        bom = self.build()
        refs = {c["bom-ref"] for c in bom["components"]}
        self.assertEqual(refs, {
            "lib:pipeline-domain@0.61.0",
            "lib:pipeline-step-sdk-runtime@0.61.0",
            "file:pipelinek",
        })

    def test_hashes_match_independently_computed_digests(self) -> None:
        bom = self.build()
        by_ref = {c["bom-ref"]: c for c in bom["components"]}
        self.assertEqual(
            by_ref["lib:pipeline-domain@0.61.0"]["hashes"][0]["content"]["value"],
            hashlib.sha256(b"jar-bytes-domain").hexdigest())
        self.assertEqual(
            by_ref["file:pipelinek"]["hashes"][0]["content"]["value"],
            hashlib.sha256(b"#!/bin/sh\n").hexdigest())
        self.assertEqual(bom["metadata"]["component"]["hashes"][0]["content"]["value"],
                         hashlib.sha256(self.zip_path.read_bytes()).hexdigest())

    def test_identity_fields_carry_the_product_version_and_git_provenance(self) -> None:
        """Claim: the SBOM's subject is the distribution it was built from."""
        bom = self.build()
        component = bom["metadata"]["component"]
        self.assertEqual(bom["bomFormat"], "CycloneDX")
        self.assertEqual(bom["specVersion"], "1.5")
        self.assertEqual(component["version"], "0.61.0")
        self.assertEqual(component["bom-ref"], "pipelinek@0.61.0")
        self.assertEqual(component["purl"], "pkg:generic/pipelinek/0.61.0")
        props = {p["name"]: p["value"] for p in component["properties"]}
        self.assertEqual(props["pipelinek:gitCommit"], "deadbeef")
        self.assertEqual(props["pipelinek:gitTag"], "v0.61.0-rc2")
        self.assertRegex(bom["serialNumber"],
                         r"^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    def test_components_are_stable_across_builds(self) -> None:
        """Claim: two SBOMs over the same bytes agree on components exactly.

        The serial number and timestamp are volatile by design; the component
        list is not, and a mismatch there would mean the digest or ordering
        depends on something other than the artifact.
        """
        first = self.build()
        second = self.build()
        self.assertEqual(first["components"], second["components"])


class Digests(Fixture):
    def test_member_digest_matches_raw_bytes(self) -> None:
        with zipfile.ZipFile(self.zip_path) as zf:
            member = zf.getinfo(f"{self.root}/lib/pipeline-domain-0.61.0.jar")
            self.assertEqual(self.mod.sha256_of_zip_member(zf, member),
                             hashlib.sha256(b"jar-bytes-domain").hexdigest())

    def test_file_digest_matches_raw_bytes(self) -> None:
        self.assertEqual(self.mod.sha256_of_file(str(self.zip_path)),
                         hashlib.sha256(self.zip_path.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main(verbosity=2)
