#!/usr/bin/env python3
"""A candidate handoff MUST describe the candidate it is used to promote.

The defect
----------
`v2/pipeline-release/build/candidate/candidate-handoff.json` is build output
under `build/`, so it survives across commits and across branch switches. The
one on disk at the time of writing declares:

    "source_commit":         "6f97d9eda43a47aef448e0b59cd7ead68ca151f3"
    "distribution_manifest": { "built_at": "6f97d9ed..." }
    "sbom":                  { "built_at": "6f97d9ed..." }

and its ZIP (`sha256:2d2df18d…`) verifiably contains six Step SDK jars at
`0.36.0` beside `pipeline-application-0.48.0.jar`. That ZIP is superseded and
must never be promoted.

Why this is a real hole rather than a hypothetical
--------------------------------------------------
`scripts/admission-check.py` validates UAT receipts by Git ancestry (R5,
"the receipt's provenance commit is an ancestor of the candidate"), but it
never reads the handoff. `CandidateAdmissionMain` re-derives provenance from
`SourceProvenanceProbe`, which is correct, and its Gradle task `dependsOn
distZip`, so the *task* cannot admit a stale ZIP.

The unguarded path is the one outside the build: a promotion step that reads
`candidate-handoff.json` from disk, or a reviewer who treats its presence as
evidence. `build/` output is not versioned, is not removed by a checkout, and
carries no marker saying which tree produced it beyond a string in its own
JSON — a document that cannot lie about itself is needed.

What this enforces
------------------
Fail closed unless the on-disk handoff names the candidate exactly:

  R-C1  the handoff exists and parses
  R-C2  `source_commit` equals the SHA being promoted (not merely an
        ancestor — an ancestor handoff describes a DIFFERENT tree)
  R-C3  `distribution_manifest.built_at` equals it, and `sbom.built_at`
        does too WHEN the section is present — a manifest cannot attest to
        bytes from another commit. `distribution_manifest` is non-null in
        the schema, so an absent or attestation-less section is refused;
        `sbom` is optional (`CandidateFileRef?`), so an absent one is
        lawful. A present-but-malformed section is refused either way.
  R-C4  the artifact sha256 the handoff claims is the sha256 of the ZIP that
        actually exists, so a rewritten handoff cannot claim bytes that were
        never built

Ancestor is explicitly NOT accepted. "The handoff comes from history" is
exactly the inheritance AGENTS.md forbids for receipts.

Usage:
    python3 scripts/check-candidate-handoff.py --head <sha>
    python3 scripts/check-candidate-handoff.py --head <sha> \\
        --handoff <path> --zip <path>
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_HANDOFF = ROOT / "v2/pipeline-release/build/candidate/candidate-handoff.json"
DEFAULT_ZIP = ROOT / "v2/pipeline-application/build/distributions/pipelinek-0.48.0.zip"


class Refusal(Exception):
    pass


def sha256_of(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_handoff(path: pathlib.Path) -> dict:
    if not path.is_file():
        raise Refusal(
            f"R-C1: no candidate handoff at {path}. A promotion MUST NOT invent "
            "one, and MUST NOT proceed without one."
        )
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise Refusal(f"R-C1: handoff at {path} is not valid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise Refusal(f"R-C1: handoff at {path} is not a JSON object")
    return data


def check(head: str, handoff_path: pathlib.Path, zip_path: pathlib.Path) -> None:
    data = load_handoff(handoff_path)

    # R-C2
    src = data.get("source_commit")
    if not src:
        raise Refusal(f"R-C2: handoff declares no source_commit; refusing to promote {head}")
    if src != head:
        kind = (
            "an ancestor of"
            if _is_ancestor(src, head)
            else "unrelated to"
        )
        raise Refusal(
            f"R-C2 REFUSED: handoff describes {src[:12]}, which is {kind} the "
            f"candidate {head[:12]}. A handoff for another tree is not evidence "
            "for this one. Rebuild the candidate; do not promote this handoff."
        )

    # R-C3
    #
    # `distribution_manifest` is non-null in CandidateHandoff (schema
    # authority): a handoff without it, or with a section that carries no
    # `built_at`, is partial truth and is refused. `sbom` IS optional
    # (`val sbom: CandidateFileRef?`, emitted only when an SBOM was
    # produced), so an absent one is legitimate; a malformed one is not.
    for key, required in (("distribution_manifest", True), ("sbom", False)):
        section = data.get(key)
        if section is None:
            if required:
                raise Refusal(
                    f"R-C3: handoff carries no {key} section. The document "
                    "attests the candidate's bytes; without it the handoff is "
                    "partial truth and is refused."
                )
            continue
        if not isinstance(section, dict):
            raise Refusal(f"R-C3: handoff.{key} is not a JSON object")
        built_at = section.get("built_at")
        if not isinstance(built_at, str) or not built_at:
            raise Refusal(
                f"R-C3: handoff.{key} carries no built_at. A section that "
                "attests no commit cannot vouch for these bytes."
            )
        if built_at != head:
            raise Refusal(
                f"R-C3 REFUSED: handoff.{key}.built_at is {built_at[:12]}, not the "
                f"candidate {head[:12]}. That document attests to bytes from a "
                "different commit."
            )

    # R-C4
    artifact = data.get("artifact")
    if not isinstance(artifact, dict):
        raise Refusal("R-C4: handoff carries no artifact section")
    claimed = artifact.get("sha256")
    if not claimed:
        raise Refusal("R-C4: handoff artifact carries no sha256")
    if not zip_path.is_file():
        raise Refusal(
            f"R-C4: handoff claims artifact {claimed[:12]} but no ZIP exists at "
            f"{zip_path}. A claimed artifact that was never built is refused."
        )
    actual = sha256_of(zip_path)
    if actual != claimed:
        raise Refusal(
            f"R-C4 REFUSED: handoff claims {claimed[:12]} but {zip_path.name} is "
            f"{actual[:12]}. The handoff does not describe the bytes on disk."
        )


def _is_ancestor(candidate: str, descendant: str) -> bool:
    try:
        return (
            subprocess.run(
                ["git", "merge-base", "--is-ancestor", candidate, descendant],
                cwd=ROOT,
                capture_output=True,
            ).returncode
            == 0
        )
    except FileNotFoundError:
        return False


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--head", required=True, help="the exact candidate SHA being promoted")
    p.add_argument("--handoff", default=str(DEFAULT_HANDOFF))
    p.add_argument("--zip", dest="zip_path", default=str(DEFAULT_ZIP))
    args = p.parse_args()

    try:
        check(args.head, pathlib.Path(args.handoff), pathlib.Path(args.zip_path))
    except Refusal as exc:
        print(f"[handoff] {exc}")
        return 1

    print(f"[handoff] OK: handoff describes {args.head[:12]} and its ZIP matches the claimed digest")
    return 0


if __name__ == "__main__":
    sys.exit(main())
