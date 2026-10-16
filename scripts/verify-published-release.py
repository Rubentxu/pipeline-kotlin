#!/usr/bin/env python3
"""Consumer-side verification of a PUBLISHED release (mandate B4.6).

The repository builds immutable candidates; this tool verifies one the way a
CONSUMER does, over the network, and it never reconstructs the artefact:

  1. download the asset and its digest sidecar from GitHub Releases (read-only);
  2. check the download against an INDEPENDENT published file: a per-asset
     sidecar (`<asset>.sha256`, or the repo's `zip.sha256`) or an inventory
     (`SHA256SUMS`) that lists the asset. What is never accepted is no
     independent file at all: a digest the download API declares about itself is
     not a check;
  3. if an inventory (`SHA256SUMS`) is published, require it to agree;
  4. read the archive root and the embedded `Implementation-Version` with the
     EXISTING identity reader (`emit-candidate-receipt.py`), so this tool owns no
     second implementation of "what the archive says it is";
  5. require exact-token agreement with the intended product version.

Why an independent digest is required: a release whose only digest is the one the
download API declares gives the consumer nothing to compare against, and "it
downloaded" is not identity.

Verdicts (typed, never inferred):
  VERIFIED  every identity check passed (exit 0)
  MISMATCH  an identity disagreement: digest, archive root, embedded version,
            inventory, or a sidecar that describes different bytes (exit 1)
  BLOCKED   the download could not be attempted/completed (network, IO) (exit 3)
  MISSING   the asset, the sidecar or the inventory does not exist (exit 4)

Usage:
    python3 scripts/verify-published-release.py --tag v0.61.0-rc1 --version 0.61.0
    python3 scripts/verify-published-release.py --tag v0.61.0-rc1 --version 0.61.0 \
        --base-url http://127.0.0.1:8099/mirror        # hermetic mirror (tests)
    python3 scripts/verify-published-release.py --tag ... --version ... --json
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import pathlib
import re
import sys
import tempfile
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_REPO = "Rubentxu/pipeline-kotlin"
SIDECAR_NAMES = ("{asset}.sha256", "zip.sha256")
INVENTORY_NAME = "SHA256SUMS"
HEX64 = re.compile(r"^[0-9a-fA-F]{64}$")


def _identity_reader():
    """Load the existing identity reader (no second implementation of it)."""
    spec = importlib.util.spec_from_file_location(
        "emit_candidate_receipt", ROOT / "scripts" / "emit-candidate-receipt.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def digest_from_sidecar(text: str) -> str | None:
    """First 64-hex token of a sidecar/inventory line, or None."""
    for token in text.split():
        if HEX64.match(token):
            return token.lower()
    return None


def inventory_entry(text: str, asset_name: str) -> str | None:
    """Digest the inventory declares for `asset_name`, or None."""
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 2 or not HEX64.match(parts[0]):
            continue
        if pathlib.Path(parts[-1].lstrip("*")).name == asset_name:
            return parts[0].lower()
    return None


def fetch(url: str, dest: pathlib.Path, opener=None) -> tuple[bool, str]:
    """Download `url` to `dest`. Returns (ok, detail)."""
    try:
        if opener is not None:
            payload = opener(url)
            dest.write_bytes(payload)
            return True, f"{len(payload)} bytes"
        with urllib.request.urlopen(url, timeout=60) as resp:
            data = resp.read()
        dest.write_bytes(data)
        return True, f"{len(data)} bytes"
    except urllib.error.HTTPError as exc:
        return False, f"HTTP {exc.code}"
    except (urllib.error.URLError, OSError, TimeoutError) as exc:
        return False, str(exc)


def classify_download(detail: str) -> str:
    """HTTP 404 means the artefact is not published; anything else is blocked."""
    return "MISSING" if detail.startswith("HTTP 404") else "BLOCKED"


def verify(repo: str, tag: str, version: str, asset_name: str, base_url: str,
           workdir: pathlib.Path, opener=None) -> dict:
    checks: list[dict] = []

    def check(name: str, ok: bool, detail: str) -> None:
        checks.append({"check": name, "verdict": "PASS" if ok else "FAIL",
                       "detail": detail})

    asset_url = f"{base_url.rstrip('/')}/{asset_name}"
    asset_path = workdir / asset_name
    ok, detail = fetch(asset_url, asset_path, opener)
    if not ok:
        return {"verdict": classify_download(detail),
                "reason": f"asset not available: {asset_name}: {detail}",
                "checks": checks, "asset_url": asset_url}
    check("asset downloaded", True, f"{asset_name}: {detail}")

    # The digest must come from an INDEPENDENT published file: a per-asset
    # sidecar, or an inventory that lists the asset. What is never acceptable is
    # no independent file at all (the download API's own word is not a check).
    sidecar_text = None
    sidecar_used = None
    for template in SIDECAR_NAMES:
        name = template.format(asset=asset_name)
        candidate = workdir / name
        ok, detail = fetch(f"{base_url.rstrip('/')}/{name}", candidate, opener)
        if ok:
            sidecar_text = candidate.read_text(errors="replace")
            sidecar_used = name
            break

    inventory_path = workdir / INVENTORY_NAME
    inventory_ok, _inventory_detail = fetch(
        f"{base_url.rstrip('/')}/{INVENTORY_NAME}", inventory_path, opener)
    inventory_text = (inventory_path.read_text(errors="replace")
                      if inventory_ok else None)

    if sidecar_text is None and inventory_text is None:
        return {"verdict": "MISSING",
                "reason": ("no independent digest published (tried "
                           + ", ".join(t.format(asset=asset_name)
                                       for t in SIDECAR_NAMES)
                           + f", {INVENTORY_NAME}): a digest the download API "
                             "declares about itself is not an independent check"),
                "checks": checks, "asset_url": asset_url}

    declared = None
    if sidecar_text is not None:
        declared = digest_from_sidecar(sidecar_text)
    if declared is None and inventory_text is not None:
        declared = inventory_entry(inventory_text, asset_name)
        sidecar_used = INVENTORY_NAME
    check("an independent digest is published", declared is not None,
          f"digest source={sidecar_used}")

    actual = sha256_file(asset_path)
    check("download matches the published digest", declared == actual,
          f"declared={declared} downloaded={actual}")
    if declared is None or declared != actual:
        return {"verdict": "MISMATCH", "reason": "digest disagreement",
                "checks": checks, "asset_url": asset_url, "sidecar": sidecar_used}

    # Inventory, when published alongside a sidecar: a third source must agree.
    if inventory_text is not None and sidecar_used != INVENTORY_NAME:
        entry = inventory_entry(inventory_text, asset_name)
        check("inventory lists the asset", entry is not None,
              f"{INVENTORY_NAME}: {'entry found' if entry else 'no entry for ' + asset_name}")
        check("inventory agrees with the sidecar", entry == actual,
              f"inventory={entry} downloaded={actual}")
        if entry != actual:
            return {"verdict": "MISMATCH", "reason": "inventory disagreement",
                    "checks": checks, "asset_url": asset_url, "sidecar": sidecar_used}

    # Identity, read by the existing reader: archive root + embedded version.
    reader = _identity_reader()
    root_in_zip = reader.zip_archive_root(asset_path)
    expected_root = f"pipelinek-{version}"
    check("archive root is an exact token",
          root_in_zip == expected_root, f"zip={root_in_zip} expected={expected_root}")
    jar, embedded = reader.embedded_implementation_version(asset_path)
    check("embedded Implementation-Version is an exact token",
          embedded == version, f"jar={jar} version={embedded} expected={version}")
    if root_in_zip != expected_root or embedded != version:
        return {"verdict": "MISMATCH", "reason": "identity disagreement",
                "checks": checks, "asset_url": asset_url, "sidecar": sidecar_used}

    return {"verdict": "VERIFIED",
            "reason": "digest, inventory, archive root and embedded version agree",
            "checks": checks, "asset_url": asset_url, "sidecar": sidecar_used,
            "sha256": actual}


def format_report(result: dict) -> str:
    lines = [f"PUBLISHED RELEASE  verdict={result['verdict']}"]
    for entry in result["checks"]:
        lines.append(f"  [{entry['verdict']}] {entry['check']}: {entry['detail']}")
    lines.append(f"  reason: {result['reason']}")
    if result.get("sidecar"):
        lines.append(f"  digest source: {result['sidecar']}")
    return "\n".join(lines)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--tag", required=True, help="release tag, e.g. v0.61.0-rc1")
    ap.add_argument("--version", required=True,
                    help="intended product version, e.g. 0.61.0")
    ap.add_argument("--repo", default=DEFAULT_REPO)
    ap.add_argument("--asset", default=None,
                    help="asset name (default: pipelinek-<version>.zip)")
    ap.add_argument("--base-url", default=None,
                    help="override the download root (tests use a loopback mirror)")
    ap.add_argument("--json", action="store_true", help="print the receipt as JSON")
    ap.add_argument("--keep", default=None,
                    help="keep the downloaded files in this directory")
    args = ap.parse_args(argv)

    asset_name = args.asset or f"pipelinek-{args.version}.zip"
    base_url = args.base_url or (
        f"https://github.com/{args.repo}/releases/download/{args.tag}")

    tmp = pathlib.Path(args.keep) if args.keep else pathlib.Path(
        tempfile.mkdtemp(prefix="verify-release-"))
    tmp.mkdir(parents=True, exist_ok=True)
    result = verify(args.repo, args.tag, args.version, asset_name, base_url, tmp)
    result["tag"] = args.tag
    result["version"] = args.version
    result["base_url"] = base_url

    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    else:
        print(format_report(result))
    return {"VERIFIED": 0, "MISMATCH": 1, "BLOCKED": 3, "MISSING": 4}[result["verdict"]]


if __name__ == "__main__":
    sys.exit(main())
