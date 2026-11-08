#!/usr/bin/env python3
"""The external-compatibility gate picks the releases it compares against.

The gate answers a question with published artifacts on both sides: can a
consumer OUTSIDE this repository build a plugin against the SDK a release
publishes and load it into the distribution that release publishes? Three of
its rows compare the candidate against a PUBLISHED release, and each of them
needs a different one:

  B  the previously published SDK of the SAME LINE  -> the plugin is ADMITTED
  C  a published SDK of an OLDER LINE               -> the plugin is REFUSED
  E  the previously PUBLISHED artifact              -> the surface is carried

WHICH release fills each of those roles is a rule of its own, and it is a rule
in LINES: a plugin declares [sdk, nextMinor), so the end of a line is the
first version of the next one. The gate used to compare TRAINS there -- the
whole version -- and the two are the same thing only while every release
opens a new minor. The first PATCH train broke it: with the candidate train
0.94.1 the release v0.94.0 was taken for an older LINE (its train differs)
when it is the previous release OF THE SAME LINE, whose SDK still loads. Row C
then measured a plugin the candidate ADMITS and reported it as a refusal that
never happened. Measured 2026-11-08: row C exited 0 where the contract
requires 2, and the publication of v0.94.1-rc1 was refused for it.

This guard measures the SELECTION and nothing else. It does not re-implement
the rule: it runs the gate's own `--select-only` mode against a FIXED
published history, so the authority stays the script that publishes. The
history is a fixture, not a query: the guard never reaches the network, and a
release published tomorrow changes nothing here.

The history carries the cases the rule has to tell apart:

  - a line with a stable AND a patch train (0.94.0 and 0.94.1-rc1), so the
    baseline and the previous side cannot be the same release by accident;
  - a release created but never published, and a draft: neither is a published
    release, and neither may be picked as a baseline;
  - lines with no published release at all, where the answer is "nothing to
    compare against" and the row is SKIPPED with its reason stated.

Run it against another copy of the gate to see whether the rule discriminates:

    python3 scripts/check-compat-comparison-selection.py /path/to/gate.sh
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GATE = ROOT / "scripts" / "release" / "verify-external-compatibility.sh"

# The published history this guard measures against.
#
# The order is the publication order, newest first, which is the order the
# gate's own query produces. `publishedAt` is null for the two entries that
# are NOT published releases, and the gate's query filters them out; they are
# here so that a filter that stopped filtering would be visible.
PUBLISHED_HISTORY = [
    {"tagName": "v0.94.1-rc1", "publishedAt": "2026-11-08T12:00:00Z", "isDraft": False},
    {"tagName": "v0.94.0", "publishedAt": "2026-11-08T08:59:22Z", "isDraft": False},
    {"tagName": "v0.94.0-rc2", "publishedAt": "2026-11-08T00:26:30Z", "isDraft": False},
    {"tagName": "v0.94.0-rc1", "publishedAt": "2026-11-08T00:04:43Z", "isDraft": False},
    {"tagName": "v0.93.0-rc1", "publishedAt": "2026-11-06T22:46:17Z", "isDraft": False},
    {"tagName": "v0.92.0-rc1", "publishedAt": "2026-10-30T07:32:03Z", "isDraft": False},
    {"tagName": "v0.91.0", "publishedAt": "2026-10-29T12:42:47Z", "isDraft": False},
    {"tagName": "v0.90.0", "publishedAt": "2026-10-25T11:26:35Z", "isDraft": False},
    {"tagName": "v0.89.0-rc1", "publishedAt": None, "isDraft": False},
    {"tagName": "v0.88.0-rc1", "publishedAt": None, "isDraft": True},
]

# What each candidate train must select against that history.
#
# 0.94.1  a PATCH train: the baseline is the stable of the SAME line
#         (0.94.0), and the older line is the one below it.
# 0.95.0  a MINOR train: nothing was published on its line yet, so the
#         baseline does not exist, and the older line is 0.94.
# 0.91.1  a patch train on a line whose published release is a stable.
# 0.90.0  a stable on a line that published only itself: there is no older
#         line to compare against, and the row says so.
# 0.89.1  the line published a release that was NEVER published: it is not a
#         baseline.
# 0.88.1  the line published only a DRAFT: it is not a baseline either.
EXPECTED = {
    "0.94.1": {"baseline": "v0.94.0", "older": "v0.93.0-rc1", "previous": "v0.94.0"},
    "0.95.0": {"baseline": "<none>", "older": "v0.94.1-rc1", "previous": "v0.94.1-rc1"},
    "0.91.1": {"baseline": "v0.91.0", "older": "v0.90.0", "previous": "v0.94.1-rc1"},
    "0.90.0": {"baseline": "<none>", "older": "<none>", "previous": "v0.94.1-rc1"},
    "0.89.1": {"baseline": "<none>", "older": "<none>", "previous": "v0.94.1-rc1"},
    "0.88.1": {"baseline": "<none>", "older": "<none>", "previous": "v0.94.1-rc1"},
}

# The gh the gate will find: it answers with the fixture and applies the
# query the gate asked for, so the gate's OWN filtering and ordering are
# measured and not this file's.
GH_STUB = """#!/usr/bin/env bash
set -euo pipefail
fixture="$FIXTURE"
expr=""
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
    if [[ "${args[$i]}" == "--jq" ]]; then
        expr="${args[$((i + 1))]}"
    fi
done
if [[ -z "$expr" ]]; then
    cat "$fixture"
    exit 0
fi
jq -r "$expr" "$fixture"
"""


def build_mirror(gate: Path, workdir: Path) -> Path:
    """Lay out the tree the gate resolves from the path it was invoked with.

    The gate resolves its own repository from `${BASH_SOURCE[0]}`, so it runs
    inside a MIRROR: the copy of the gate under test, and the example plugin
    sources it looks for. The mirror is what lets the same instrument measure
    this repository's gate and a copy of it that lives somewhere else.
    """
    mirror = workdir / "mirror"
    mirror_scripts = mirror / "scripts" / "release"
    mirror_scripts.mkdir(parents=True)
    shutil.copy2(gate, mirror_scripts / gate.name)
    (mirror / "examples").symlink_to(ROOT / "examples", target_is_directory=True)
    return mirror_scripts / gate.name


def select_releases(mirror_gate: Path, mirror: Path, train: str, workdir: Path) -> tuple[str, str]:
    """Run the gate's selection for `train` and return (line, stderr)."""
    env = dict(os.environ)
    env["PATH"] = f"{workdir / 'bin'}{os.pathsep}{env.get('PATH', '')}"
    env["FIXTURE"] = str(workdir / "published-history.json")
    proc = subprocess.run(
        ["bash", str(mirror_gate), "--candidate-train", train, "--select-only"],
        cwd=str(mirror),
        env=env,
        capture_output=True,
        text=True,
        timeout=120,
    )
    for line in proc.stdout.splitlines():
        if line.startswith("external-compat: select "):
            return line, proc.stderr
    return "", proc.stderr


def parse_selection(line: str) -> dict[str, str]:
    fields = {}
    for token in line.split(" ", 1)[1].split():
        if "=" in token:
            key, value = token.split("=", 1)
            fields[key] = value
    return fields


def main() -> int:
    gate = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else GATE
    if not gate.is_file():
        print(f"the gate under test does not exist: {gate}", file=sys.stderr)
        return 1

    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="pipelinek-compat-selection.") as tmp:
        workdir = Path(tmp)
        (workdir / "bin").mkdir()
        stub = workdir / "bin" / "gh"
        stub.write_text(GH_STUB)
        stub.chmod(0o755)
        (workdir / "published-history.json").write_text(json.dumps(PUBLISHED_HISTORY))
        mirror = workdir / "mirror"
        mirror_gate = build_mirror(gate, workdir)

        for train, expected in sorted(EXPECTED.items()):
            line, stderr = select_releases(mirror_gate, mirror, train, workdir)
            if not line:
                failures.append(
                    f"the gate printed no selection for train {train}"
                    + (f"\n    stderr: {stderr.strip()}" if stderr.strip() else "")
                )
                continue
            selected = parse_selection(line)
            for role, want in expected.items():
                got = selected.get(role, "<missing>")
                if got != want:
                    failures.append(
                        f"train {train}: the {role} is {got}, and it must be {want}"
                    )

    if failures:
        print("COMPATIBILIDAD EXTERNA: FAIL")
        for failure in failures:
            print(f"  {failure}")
        print(
            "The rows compare LINES: the baseline is a published SDK of the same line, "
            "and the older side is a published SDK of an older line."
        )
        return 1

    print(
        f"OK: the external-compatibility gate selects the published releases by LINE "
        f"({len(EXPECTED)} candidate trains measured against a fixed published history)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
