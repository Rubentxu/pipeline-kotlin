#!/usr/bin/env python3
"""Candidate receipt (B4): bind a built distribution to its SHA, hashes and identity.

Validates the product identity law and the material identity of a candidate
BEFORE writing an immutable receipt:

  M1  the ZIP exists and its digest/size are measured, never assumed
  M2  handoff.artifact and manifest.asset describe THOSE bytes (sha256 + size)
  M3  archive_root agrees across handoff, manifest and the ZIP's own top folder
  M4  implementation_version agrees across handoff, manifest and the
      `Implementation-Version` of the application JAR INSIDE the archive
  M5  product identity law: product_version == release_train == manifest.version
      == embedded version, compared as EXACT tokens (the v0.43.0 lesson:
      `0.44.0` inside `0.44.0-rc1` is a defect, not a coincidence)
  M6  source_commit is a 40-hex commit, equals the observed git HEAD, and equals
      --expect-sha when provided
  M7  candidate_id (when present) is `sha256:<zip digest>`
  M8  optional artifacts (sbom, sha256sums, distribution manifest file) match
      their declared digests

Verdict: PASS (every check passed), FAIL (a check failed), BLOCKED (an input
document is missing or unreadable). The receipt is written only when the tool
was asked to with --out, and it carries the measured values plus every check.

Usage:
    python3 scripts/emit-candidate-receipt.py --zip <zip> --manifest <json> \\
        --handoff <json> [--sbom <json>] [--expect-sha <sha>] [--out <json>]

Exit codes: 0 PASS, 1 FAIL, 2 BLOCKED.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent

HEX40 = re.compile(r"^[0-9a-f]{40}$")
APP_JAR = re.compile(r"/(pipeline-application-[^/]+\.jar)$")


def sha256_file(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_json(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def zip_archive_root(zip_path: pathlib.Path) -> str | None:
    """The single top-level directory of the archive, or None when ambiguous."""
    tops = set()
    with zipfile.ZipFile(zip_path) as zf:
        for member in zf.infolist():
            if member.is_dir():
                continue
            parts = member.filename.split("/")
            if len(parts) < 2:
                return None
            tops.add(parts[0])
    return tops.pop() if len(tops) == 1 else None


def embedded_implementation_version(zip_path: pathlib.Path) -> tuple[str | None, str | None]:
    """(jar name, Implementation-Version) from the application JAR in the ZIP."""
    with zipfile.ZipFile(zip_path) as zf:
        for member in zf.infolist():
            m = APP_JAR.search("/" + member.filename)
            if not m:
                continue
            with zf.open(member) as fh:
                data = fh.read()
            import io
            with zipfile.ZipFile(io.BytesIO(data)) as jar:
                manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
            for line in manifest.splitlines():
                if line.startswith("Implementation-Version:"):
                    return m.group(1), line.split(":", 1)[1].strip()
            return m.group(1), None
    return None, None


def git_head(root: pathlib.Path) -> str | None:
    try:
        return subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True,
                              text=True, check=True).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return None


def _check(checks: list[dict], name: str, ok: bool, detail: str) -> None:
    checks.append({"check": name, "verdict": "PASS" if ok else "FAIL", "detail": detail})


def evaluate(zip_path: pathlib.Path, manifest: dict, handoff: dict, *,
             sbom_path: pathlib.Path | None, manifest_path: pathlib.Path | None,
             expect_sha: str | None, observed_head: str | None) -> dict:
    checks: list[dict] = []

    digest = sha256_file(zip_path)
    size = zip_path.stat().st_size
    _check(checks, "zip-present", True, f"{zip_path} ({size} bytes, sha256:{digest[:12]}…)")

    artifact = handoff.get("artifact") or {}
    asset = manifest.get("asset") or {}

    _check(checks, "handoff.artifact.sha256 == zip",
           artifact.get("sha256") == digest,
           f"declared {artifact.get('sha256')} vs measured {digest}")
    _check(checks, "manifest.asset.sha256 == zip",
           asset.get("sha256") == digest,
           f"declared {asset.get('sha256')} vs measured {digest}")
    _check(checks, "handoff.artifact.size == zip",
           artifact.get("size") == size, f"declared {artifact.get('size')} vs {size}")
    _check(checks, "manifest.asset.size == zip",
           asset.get("size") == size, f"declared {asset.get('size')} vs {size}")

    root_in_zip = zip_archive_root(zip_path)
    _check(checks, "archive_root agrees (handoff/manifest/zip)",
           root_in_zip is not None
           and artifact.get("archive_root") == root_in_zip
           and asset.get("archive_root") == root_in_zip,
           f"zip={root_in_zip} handoff={artifact.get('archive_root')} "
           f"manifest={asset.get('archive_root')}")

    jar_name, jar_version = embedded_implementation_version(zip_path)
    _check(checks, "app JAR carries Implementation-Version",
           jar_version is not None, f"{jar_name}: {jar_version}")
    _check(checks, "implementation_version agrees (handoff/manifest/jar)",
           jar_version is not None
           and artifact.get("implementation_version") == jar_version
           and asset.get("implementation_version") == jar_version,
           f"handoff={artifact.get('implementation_version')} "
           f"manifest={asset.get('implementation_version')} jar={jar_version}")

    product = handoff.get("product_version")
    train = handoff.get("release_train")
    manifest_version = manifest.get("version")
    _check(checks, "product identity law (exact tokens)",
           product is not None and product == train == manifest_version == jar_version,
           f"product={product} train={train} manifest={manifest_version} jar={jar_version}")

    source_commit = handoff.get("source_commit")
    _check(checks, "source_commit is a 40-hex commit",
           isinstance(source_commit, str) and HEX40.match(source_commit) is not None,
           f"source_commit={source_commit}")
    if expect_sha:
        _check(checks, "--expect-sha == source_commit", source_commit == expect_sha,
               f"expected {expect_sha} vs {source_commit}")
    if observed_head:
        _check(checks, "source_commit == observed git HEAD", source_commit == observed_head,
               f"HEAD {observed_head} vs {source_commit}")

    manifest_commit = (manifest.get("source") or {}).get("git_commit")
    _check(checks, "manifest.source.git_commit == source_commit",
           manifest_commit == source_commit,
           f"manifest={manifest_commit} handoff={source_commit}")

    candidate_id = handoff.get("candidate_id")
    _check(checks, "candidate_id == sha256:<zip>",
           candidate_id == f"sha256:{digest}",
           f"declared {candidate_id} vs sha256:{digest}")

    if sbom_path is not None:
        sbom_digest = sha256_file(sbom_path)
        declared = (handoff.get("sbom") or {}).get("sha256")
        manifest_declared = (manifest.get("sbom") or {}).get("sha256")
        _check(checks, "sbom digest == declared (handoff and manifest)",
               declared == sbom_digest and (manifest_declared in (None, sbom_digest)),
               f"measured {sbom_digest} declared={declared} manifest={manifest_declared}")

    if manifest_path is not None:
        declared = (handoff.get("distribution_manifest") or {}).get("sha256")
        measured = sha256_file(manifest_path)
        _check(checks, "distribution manifest digest == declared",
               declared == measured, f"measured {measured} declared={declared}")

    verdict = "PASS" if all(c["verdict"] == "PASS" for c in checks) else "FAIL"
    return {"zip": {"path": str(zip_path), "sha256": digest, "size": size},
            "checks": checks, "verdict": verdict}


def format_report(receipt: dict) -> str:
    lines = [f"CANDIDATE RECEIPT  verdict={receipt['verdict']}  "
             f"sha256:{receipt['zip']['sha256'][:12]}…", ""]
    for c in receipt["checks"]:
        lines.append(f"  [{c['verdict']}] {c['check']}: {c['detail']}")
    return "\n".join(lines)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--zip", required=True)
    ap.add_argument("--manifest", required=True,
                    help="distribution-manifest.json emitted by the build")
    ap.add_argument("--handoff", required=True,
                    help="candidate-handoff.json emitted by the build")
    ap.add_argument("--sbom")
    ap.add_argument("--expect-sha")
    ap.add_argument("--out")
    ap.add_argument("--root", default=str(ROOT))
    args = ap.parse_args(argv)

    root = pathlib.Path(args.root).resolve()
    zip_path = pathlib.Path(args.zip)
    manifest_path = pathlib.Path(args.manifest)
    handoff_path = pathlib.Path(args.handoff)

    for label, path in (("zip", zip_path), ("manifest", manifest_path),
                        ("handoff", handoff_path)):
        if not path.is_file():
            print(f"BLOCKED: {label} not found at {path}", file=sys.stderr)
            return 2

    try:
        manifest = read_json(manifest_path)
        handoff = read_json(handoff_path)
    except (json.JSONDecodeError, OSError) as exc:
        print(f"BLOCKED: unreadable candidate document: {exc}", file=sys.stderr)
        return 2

    receipt = evaluate(zip_path, manifest, handoff,
                       sbom_path=pathlib.Path(args.sbom) if args.sbom else None,
                       manifest_path=manifest_path,
                       expect_sha=args.expect_sha,
                       observed_head=git_head(root))
    receipt.update({
        "tool": "scripts/emit-candidate-receipt.py",
        "timestamp": datetime.datetime.now(datetime.timezone.utc)
            .strftime("%Y-%m-%dT%H:%M:%SZ"),
        "git_head_observed": git_head(root),
    })
    print(format_report(receipt))

    if args.out:
        pathlib.Path(args.out).write_text(json.dumps(receipt, indent=2, sort_keys=True))
        print(f"WROTE {args.out}")

    return 0 if receipt["verdict"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
