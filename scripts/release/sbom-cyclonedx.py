#!/usr/bin/env python3
"""
Generate a minimal CycloneDX 1.5 JSON SBOM from a pipelinek distribution ZIP.

We list every component embedded in the canonical artifact, scoped to what the
release actually ships (no build-time classpath leakage). Components are
classified by the directory they live in:
  - lib/*.jar        -> library (purl: pkg:maven/...)
  - bin/*            -> file (application launcher)
  - (other)          -> library or file as appropriate

Inputs (env or argv):
  1: zip path            (e.g. v2/pipeline-application/build/distributions/pipelinek-0.36.0.zip)
  2: version             (e.g. 0.36.0)
  3: git commit          (e.g. 88b26b81...)
  4: git tag             (e.g. v0.36.0)
  5: output path         (e.g. pipelinek-0.36.0.sbom.json)

Produces CycloneDX 1.5 JSON. No external network. Pure Python stdlib.
"""

from __future__ import annotations
import json
import os
import sys
import zipfile
import hashlib
import re
from datetime import datetime, timezone
from pathlib import PurePosixPath


def sha256_of_zip_member(zf: zipfile.ZipFile, member: zipfile.ZipInfo) -> str:
    h = hashlib.sha256()
    with zf.open(member, "r") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def classify_member(name: str) -> tuple[str, str]:
    """Return (kind, purl-or-None) for a zip member path."""
    p = PurePosixPath(name)
    parts = p.parts
    if not parts:
        return "data", ""
    top = parts[1] if len(parts) >= 2 else parts[0]
    if top == "lib" and p.suffix == ".jar":
        m = re.match(r"^(?P<name>[a-zA-Z0-9._-]+)-(?P<version>[0-9][^/]*?)\.jar$", p.name)
        if m:
            artifact = m.group("name")
            ver = m.group("version")
            purl = f"pkg:maven/{artifact}/{ver}"
            return "library", purl
        return "library", ""
    if top == "bin":
        return "file", ""
    if top == "META-INF":
        return "data", ""
    return "data", ""


def maven_name_from_jar_name(jar_name: str) -> tuple[str, str]:
    """Best-effort split of 'foo-bar-1.2.3.jar' into (name, version)."""
    base = jar_name[:-4] if jar_name.endswith(".jar") else jar_name
    m = re.match(r"^(?P<name>.+)-(?P<version>\d[\w.-]*)$", base)
    if m:
        return m.group("name"), m.group("version")
    return base, ""


def build_sbom(zip_path: str, version: str, git_commit: str, git_tag: str) -> dict:
    components: list[dict] = []
    with zipfile.ZipFile(zip_path, "r") as zf:
        # We process in a stable order: sorted by member name.
        members = sorted(zf.infolist(), key=lambda m: m.filename)
        for member in members:
            if member.is_dir():
                continue
            kind, _ = classify_member(member.filename)
            if kind == "data":
                continue
            sha256 = sha256_of_zip_member(zf, member)
            name = PurePosixPath(member.filename).name
            if kind == "library":
                artifact, ver = maven_name_from_jar_name(name)
                comp = {
                    "type": "library",
                    "bom-ref": f"lib:{artifact}@{ver}",
                    "name": artifact,
                    "version": ver,
                    "hashes": [{"alg": "SHA-256", "content": {"value": sha256}}],
                    "purl": f"pkg:maven/{artifact}/{ver}",
                }
            else:
                comp = {
                    "type": "file",
                    "bom-ref": f"file:{name}",
                    "name": name,
                    "hashes": [{"alg": "SHA-256", "content": {"value": sha256}}],
                }
            components.append(comp)
    metadata = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "tools": [
            {
                "vendor": "pipeline-kotlin",
                "name": "in-tree SBOM generator (stdlib)",
                "version": version,
            }
        ],
        "authors": [{"name": "Rubén"}],
        "component": {
            "type": "application",
            "bom-ref": f"pipelinek@{version}",
            "name": "pipelinek",
            "version": version,
            "hashes": [
                {
                    "alg": "SHA-256",
                    "content": {"value": sha256_of_file(zip_path)},
                }
            ],
            "purl": f"pkg:generic/pipelinek/{version}",
            "properties": [
                {"name": "pipelinek:gitCommit", "value": git_commit},
                {"name": "pipelinek:gitTag", "value": git_tag},
            ],
        },
    }
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": f"urn:uuid:{uuid4hex()}",
        "version": 1,
        "metadata": metadata,
        "components": components,
    }


def sha256_of_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def uuid4hex() -> str:
    import uuid
    return str(uuid.uuid4())


def main(argv: list[str]) -> int:
    if len(argv) != 6:
        print(f"usage: {argv[0]} <zip> <version> <git_commit> <git_tag> <out>", file=sys.stderr)
        return 2
    zip_path, version, git_commit, git_tag, out_path = argv[1:6]
    sbom = build_sbom(zip_path, version, git_commit, git_tag)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(sbom, f, indent=2, sort_keys=True)
    print(f"SBOM written: {out_path} ({len(sbom['components'])} components)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
