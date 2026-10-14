#!/usr/bin/env python3
"""Contract tests for `scripts/release/build-release-manifest.sh`.

Why these exist
---------------
`v0.61.0-rc1` published a release whose only assets were the ZIP and the SBOM:
no `SHA256SUMS`. `DISTRIBUTION_RELEASE_SPEC.md` §4 requires the digest manifest
alongside them, and calls it "the single digest authority for the whole release".
The failure was silent — nothing refused, nothing was red, the release simply
had no digest authority a consumer could resolve, and
`scripts/install-pipelinek.sh` fails closed on that.

A test for this therefore cannot be "does the script run". It has to kill the
specific ways a manifest can be quietly wrong:

  M1  every requested asset gets exactly one entry        (the coverage claim)
  M2  the manifest verifies with stock `sha256sum -c`     (not a private format)
  M3  a missing input is refused, and nothing is written
  M4  SHA256SUMS cannot list itself                       (self-reference is a lie)
  M5  a duplicate basename is refused                     (two files, one name)
  M6  entry order does not depend on argument order       (reproducible bytes)
  M7  the producer/consumer pair agrees                   (no format drift)

M7 is the one that would have caught the real defect from the consumer side: if
the two scripts ever disagree about the format, a release publishes fine and
every install fails.

These tests are hermetic. They create their own temp directory, run the script
as a subprocess, and touch nothing outside it. No network, no git, no release.

Usage:
    python3 scripts/test_build_release_manifest.py
"""

from __future__ import annotations

import hashlib
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
BUILDER = ROOT / "scripts" / "release" / "build-release-manifest.sh"
CONSUMER = ROOT / "scripts" / "release" / "resolve-release-digest.sh"

FAILURES: list[str] = []
PASSES: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        PASSES.append(name)
        print(f"  PASS  {name}")
    else:
        FAILURES.append(f"{name}: {detail}")
        print(f"  FAIL  {name}  {detail}")


def run_builder(out: pathlib.Path, files: list[pathlib.Path], cwd: pathlib.Path):
    argv = [str(BUILDER), "--out", str(out)] + [str(f) for f in files]
    return subprocess.run(argv, capture_output=True, text=True, cwd=str(cwd), check=False)


def make_assets(directory: pathlib.Path) -> dict[str, pathlib.Path]:
    """Four assets spanning the shapes a real release carries."""
    assets = {}
    for name, content in (
        ("pipelinek-0.61.0.zip", b"PK\x03\x04 pretend-zip bytes"),
        ("pipelinek-0.61.0.sbom.json", b'{"bomFormat":"CycloneDX"}'),
        ("HANDOFF.md", b"# Release handoff\n"),
        ("git-head.txt", b"0123456789abcdef0123456789abcdef01234567\n"),
    ):
        p = directory / name
        p.write_bytes(content)
        assets[name] = p
    return assets


def parse_manifest(path: pathlib.Path) -> list[tuple[str, str]]:
    entries = []
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        digest, _, name = line.partition("  ")
        entries.append((digest, name.lstrip("*")))
    return entries


def test_m1_covers_every_asset(tmp: pathlib.Path) -> None:
    print("\nM1 — every requested asset gets exactly one entry")
    work = tmp / "m1"
    work.mkdir()
    assets = make_assets(work)
    out = work / "SHA256SUMS"

    result = run_builder(out, list(assets.values()), work)
    check("m1/exit-zero", result.returncode == 0, result.stderr)

    entries = parse_manifest(out)
    names = [name for _, name in entries]
    check("m1/entry-count", len(entries) == 4, f"got {len(entries)} entries: {names}")
    for expected in assets:
        check(f"m1/covers-{expected}", expected in names, f"manifest names {names}")

    # M2: the digests are the digests of the real bytes.
    for name, path in assets.items():
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        recorded = dict((n, d) for d, n in entries).get(name)
        check(f"m2/digest-correct-{name}", recorded == actual, f"{recorded} != {actual}")


def test_m2_verifies_with_stock_tool(tmp: pathlib.Path) -> None:
    print("\nM2 — verifies with stock `sha256sum -c` (no private format)")
    work = tmp / "m2"
    work.mkdir()
    assets = make_assets(work)
    out = work / "SHA256SUMS"
    run_builder(out, list(assets.values()), work)

    result = subprocess.run(
        ["sha256sum", "-c", out.name], capture_output=True, text=True, cwd=str(work), check=False
    )
    check("m2/sha256sum-c-exit-zero", result.returncode == 0, result.stdout + result.stderr)
    # Count reported lines rather than matching ": OK" — `sha256sum` localises
    # that string (this host prints "La suma coincide"), and a locale-dependent
    # assertion is a false red on the next machine.
    verified = [ln for ln in result.stdout.splitlines() if ln.strip()]
    check("m2/sha256sum-c-4-lines", len(verified) == 4, result.stdout)

    # The format is the stock one: no header, no JSON, no extra columns.
    body = out.read_text()
    check("m2/no-json-wrapper", not body.lstrip().startswith(("{", "[")), body[:80])
    check("m2/stock-line-format",
          all(re.fullmatch(r"[0-9a-f]{64} [ *].+", line) for line in body.splitlines() if line.strip()),
          body)

    # Entries must be portable: a manifest that records the build machine's
    # absolute paths cannot be verified by a consumer who downloaded the
    # assets, which is the whole reason the manifest is published.
    names = [name for _, name in parse_manifest(out)]
    check("m2/entries-are-relative", all(not n.startswith("/") for n in names), str(names))


def test_m3_missing_input_is_refused(tmp: pathlib.Path) -> None:
    print("\nM3 — a missing input is refused, and nothing is written")
    work = tmp / "m3"
    work.mkdir()
    assets = make_assets(work)
    out = work / "SHA256SUMS"
    absent = work / "pipelinek-9.9.9.zip"

    result = run_builder(out, [assets["HANDOFF.md"], absent], work)
    check("m3/refused", result.returncode != 0, f"exit={result.returncode}")
    check("m3/names-the-absent-file", absent.name in (result.stderr + result.stdout),
          result.stderr)
    check("m3/wrote-nothing", not out.exists(), "a manifest was left on disk after a refusal")


def test_m4_cannot_list_itself(tmp: pathlib.Path) -> None:
    print("\nM4 — SHA256SUMS cannot list itself")
    work = tmp / "m4"
    work.mkdir()
    assets = make_assets(work)
    out = work / "SHA256SUMS"
    out.write_text("stale\n")

    result = run_builder(out, [assets["HANDOFF.md"], out], work)
    check("m4/refused", result.returncode != 0, f"exit={result.returncode}")
    check("m4/explains-why", "cannot carry its own digest" in result.stderr, result.stderr)
    check("m4/did-not-overwrite", out.read_text() == "stale\n",
          "the refusal still rewrote the manifest")


def test_m5_duplicate_basename_is_refused(tmp: pathlib.Path) -> None:
    print("\nM5 — two files with the same basename are refused")
    work = tmp / "m5"
    work.mkdir()
    a_dir = work / "a"
    b_dir = work / "b"
    a_dir.mkdir()
    b_dir.mkdir()
    (a_dir / "HANDOFF.md").write_bytes(b"a")
    (b_dir / "HANDOFF.md").write_bytes(b"b")

    result = run_builder(work / "SHA256SUMS", [a_dir / "HANDOFF.md", b_dir / "HANDOFF.md"], work)
    check("m5/refused", result.returncode != 0, f"exit={result.returncode}")
    check("m5/names-the-duplicate", "duplicate" in result.stderr, result.stderr)


def test_m6_order_is_deterministic(tmp: pathlib.Path) -> None:
    print("\nM6 — entry order does not depend on argument order")
    work = tmp / "m6"
    work.mkdir()
    assets = make_assets(work)

    first = work / "SHA256SUMS.a"
    second = work / "SHA256SUMS.b"
    run_builder(first, list(assets.values()), work)
    run_builder(second, list(reversed(list(assets.values()))), work)

    check("m6/identical-bytes", first.read_bytes() == second.read_bytes(),
          "two identical releases would produce two different manifest digests")


def test_m7_producer_consumer_agree(tmp: pathlib.Path) -> None:
    """The defect that actually shipped was a format the consumer could not read.

    `resolve-release-digest.sh` is what `install-pipelinek.sh` and the SDKMAN
    publisher call. If the producer writes something that consumer cannot parse,
    the release publishes successfully and every install fails closed. So this
    row runs BOTH scripts over the same manifest.
    """
    print("\nM7 — producer and consumer agree on the format")
    work = tmp / "m7"
    work.mkdir()
    assets = make_assets(work)
    out = work / "SHA256SUMS"
    run_builder(out, list(assets.values()), work)

    for name in assets:
        result = subprocess.run(
            [str(CONSUMER), name],
            input=out.read_text(),
            capture_output=True,
            text=True,
            cwd=str(work),
            check=False,
        )
        expected = hashlib.sha256(assets[name].read_bytes()).hexdigest()
        check(
            f"m7/consumer-resolves-{name}",
            result.returncode == 0 and result.stdout.strip() == expected,
            f"exit={result.returncode} out={result.stdout!r} err={result.stderr!r}",
        )

    # And a manifest covering the WHOLE set is what §4 asks for. This is the
    # assertion that would have failed for v0.61.0-rc1, whose published set was
    # {zip, sbom} with no manifest at all.
    names = {n for _, n in parse_manifest(out)}
    check("m7/whole-release-covered", names == set(assets), f"manifest covers {names}")


def main() -> int:
    if not BUILDER.is_file():
        print(f"build-release-manifest.sh not found at {BUILDER}", file=sys.stderr)
        return 2
    if not CONSUMER.is_file():
        print(f"resolve-release-digest.sh not found at {CONSUMER}", file=sys.stderr)
        return 2

    tmp = pathlib.Path(tempfile.mkdtemp(prefix="pk-release-manifest-"))
    try:
        test_m1_covers_every_asset(tmp)
        test_m2_verifies_with_stock_tool(tmp)
        test_m3_missing_input_is_refused(tmp)
        test_m4_cannot_list_itself(tmp)
        test_m5_duplicate_basename_is_refused(tmp)
        test_m6_order_is_deterministic(tmp)
        test_m7_producer_consumer_agree(tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print(f"\nsummary: pass={len(PASSES)} fail={len(FAILURES)}")
    for failure in FAILURES:
        print(f"  - {failure}")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    sys.exit(main())