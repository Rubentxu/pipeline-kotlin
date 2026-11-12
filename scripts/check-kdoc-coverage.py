#!/usr/bin/env python3
"""Contract KDoc coverage over the published ABI dumps.

The published `.api` files are the authority of the public surface: they name
every type the product exposes. This script measures, for those types, how many
carry a KDoc block that states their contract.

The unit of measurement is a public TYPE declared in the sources of a module
that publishes a contract. The contract is what makes the type public to the
world, and the sources are where a human writes the KDoc, so the two sides have
to be read and cross-checked:

  * every type the sources declare is reported with whether a KDoc block
    documents it;
  * every type the contract names is looked up in the sources, and the ones
    with no source counterpart are classified. The toolchain generates some of
    them (the file facades `XxxKt`, the `DefaultImpls` holders, the companion
    the serialization plugin adds to a `@Serializable` type); those are listed
    as GENERATED and are measured by nobody, because no hand writes their
    documentation. Anything else is a disagreement between the contract and
    the sources, and it fails.

Rules applied, in order:

  * A declaration is public unless it is marked internal, private or
    protected. Kotlin's default visibility is public.
  * A declaration is documented when a `/** ... */` block stands immediately
    above it, annotations in between being allowed: an annotated declaration is
    still the declaration the annotation decorates.
  * Nested declarations count as their own type, named the way the ABI names
    them: `Outer$Inner`, and `Outer$Companion` for a companion object.

Exit 0 when the coverage is at or above the floor, 1 otherwise.

Usage: check-kdoc-coverage.py [--repo ROOT] [--min PERCENT] [--json]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

# The published contracts and the module sources that produce them.
CONTRACTS: list[tuple[str, str, str]] = [
    (
        "pipeline-credentials-api",
        "pipelinek/pipeline-credentials-api/api/pipeline-credentials-api.api",
        "pipelinek/pipeline-credentials-api/src/main/kotlin",
    ),
    (
        "pipeline-domain",
        "pipelinek/pipeline-domain/api/pipeline-domain.api",
        "pipelinek/pipeline-domain/src/main/kotlin",
    ),
    (
        "pipeline-events",
        "pipelinek/pipeline-events/api/pipeline-events.api",
        "pipelinek/pipeline-events/src/main/kotlin",
    ),
    (
        "pipeline-output",
        "pipelinek/pipeline-output/api/pipeline-output.api",
        "pipelinek/pipeline-output/src/main/kotlin",
    ),
    (
        "pipeline-runtime-api",
        "pipelinek/pipeline-runtime-api/api/pipeline-runtime-api.api",
        "pipelinek/pipeline-runtime-api/src/main/kotlin",
    ),
    (
        "pipeline-scripting-api",
        "pipelinek/pipeline-scripting-api/api/pipeline-scripting-api.api",
        "pipelinek/pipeline-scripting-api/src/main/kotlin",
    ),
    (
        "pipeline-scripting-definition",
        "pipelinek/pipeline-scripting-definition/api/pipeline-scripting-definition.api",
        "pipelinek/pipeline-scripting-definition/src/main/kotlin",
    ),
    (
        "pipeline-service-client",
        "pipelinek/pipeline-service-client/api/pipeline-service-client.api",
        "pipelinek/pipeline-service-client/src/main/kotlin",
    ),
    (
        "pipeline-step-sdk",
        "pipelinek/pipeline-step-sdk/api/api/api.api",
        "pipelinek/pipeline-step-sdk/api/src/main/kotlin",
    ),
]

API_TYPE = re.compile(r"^public ((?:[A-Za-z]+ )*)(class|interface|object) (\S+)")

MODIFIERS = (
    "public",
    "internal",
    "private",
    "protected",
    "expect",
    "actual",
    "inline",
    "external",
    "open",
    "abstract",
    "final",
    "sealed",
    "data",
    "value",
    "enum",
    "annotation",
    "fun",
)

DECLARATION = re.compile(
    r"^(?P<indent>[ ]*)"
    r"(?P<modifiers>(?:(?:" + "|".join(MODIFIERS) + r")[ ]+)*)"
    r"(?P<kind>class|interface|object)[ ]+"
    r"(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
)

COMPANION = re.compile(
    r"^(?P<indent>[ ]+)(?:(?:public|internal|private|protected)[ ]+)?companion[ ]+object"
)

PACKAGE = re.compile(r"^package[ ]+([\w.]+)")


def annotation_lines(lines: list[str]) -> list[bool]:
    """A mask of the lines that belong to an annotation.

    An annotation starts on a line that opens with `@` and ends where its
    parentheses balance, so `@Deprecated(message = "...", replaceWith = ...)`
    spans several lines and every one of them belongs to the annotation.
    """
    mask = [False] * len(lines)
    index = 0
    while index < len(lines):
        stripped = lines[index].strip()
        if not stripped.startswith("@"):
            index += 1
            continue
        depth = 0
        cursor = index
        while cursor < len(lines):
            depth += lines[cursor].count("(") - lines[cursor].count(")")
            mask[cursor] = True
            if depth <= 0:
                break
            cursor += 1
        index = cursor + 1
    return mask


def documented_above(lines: list[str], index: int, annotations: list[bool]) -> bool:
    """True when a KDoc block stands immediately above the line at `index`.

    A block comment is KDoc when it opens with `/**` and closes with `*/`, so
    the search walks up from the declaration to the closer and then to the
    opener. A `/* ... */` comment that is not KDoc does not count.

    Annotations may stand between the KDoc and the declaration, and a KDoc
    placed after the annotations counts too, because that is where the
    compiler reads it from.
    """
    cursor = index - 1
    while cursor >= 0:
        stripped = lines[cursor].strip()
        if stripped == "" or annotations[cursor]:
            cursor -= 1
            continue
        if not stripped.endswith("*/"):
            return False
        while cursor >= 0 and not lines[cursor].strip().startswith("/*"):
            cursor -= 1
        if cursor < 0:
            return False
        return lines[cursor].strip().startswith("/**")
    return False


def kotlin_files(root: str, source_root: str) -> list[str]:
    base = os.path.join(root, source_root)
    found: list[str] = []
    for directory, _, names in os.walk(base):
        for name in names:
            if name.endswith(".kt"):
                found.append(os.path.join(directory, name))
    return sorted(found)


def declarations(lines: list[str]) -> list[tuple[str, str, bool]]:
    """(binary name, kind, documented) for the public types declared here.

    The binary name is the way the ABI names a type: `Outer` for a top-level
    declaration and `Outer$Inner` for a nested one. Nesting is read from the
    indentation, which is what the Kotlin style fixes.
    """
    found: list[tuple[str, str, bool]] = []
    stack: list[tuple[int, str, bool]] = []
    annotations = annotation_lines(lines)
    for index, line in enumerate(lines):
        companion = COMPANION.match(line)
        if companion:
            indent = len(companion.group("indent"))
            while stack and stack[-1][0] >= indent:
                stack.pop()
            own_public = not re.search(
                r"\b(?:internal|private|protected)[ ]+companion[ ]+object", line
            )
            if stack and stack[-1][2] and own_public:
                owner = stack[-1][1]
                binary = f"{owner}$Companion"
                found.append((binary, "object", documented_above(lines, index, annotations)))
                stack.append((indent, binary, True))
            continue
        match = DECLARATION.match(line)
        if not match:
            continue
        modifiers = match.group("modifiers").split()
        own_public = not any(
            modifier in ("internal", "private", "protected") for modifier in modifiers
        )
        indent = len(match.group("indent"))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        # A declaration deeper than one level below its owner is a local class
        # of a function body, not a member of the type: it is not published and
        # nobody documents it.
        if stack and indent - stack[-1][0] > 4:
            continue
        # A nested declaration is public only when its owner is public too: a
        # public class inside a private one is not part of the public surface.
        enclosing_public = stack[-1][2] if stack else True
        if not own_public or not enclosing_public:
            stack.append((indent, match.group("name"), False))
            continue
        name = match.group("name")
        binary = f"{stack[-1][1]}${name}" if stack else name
        found.append((binary, match.group("kind"), documented_above(lines, index, annotations)))
        stack.append((indent, binary, True))
    return found


def index_module(root: str, source_root: str) -> dict[str, bool]:
    """`package.binary name` -> documented, for the public types of one module."""
    index: dict[str, bool] = {}
    for path in kotlin_files(root, source_root):
        with open(path, encoding="utf-8", errors="replace") as handle:
            lines = handle.readlines()
        package = ""
        for line in lines:
            match = PACKAGE.match(line)
            if match:
                package = match.group(1)
                break
        for binary, _kind, documented in declarations(lines):
            index.setdefault(f"{package}.{binary}", documented)
    return index


def published_types(root: str, api_path: str) -> list[str]:
    """`package.binary name` for every type the contract names."""
    names: list[str] = []
    with open(os.path.join(root, api_path), encoding="utf-8") as handle:
        for line in handle:
            match = API_TYPE.match(line)
            if not match:
                continue
            if "synthetic" in match.group(1):
                continue
            binary = match.group(3).replace("/", ".")
            names.append(binary)
    return names


def is_compiler_generated(binary: str, index: dict[str, bool]) -> bool:
    """True for the types the compiler writes and no hand documents.

    The file facades (`XxxKt`), the `DefaultImpls` holders and the companion the
    serialization plugin adds to a serializable class are all written by the
    compiler: the sources declare no such type, so no KDoc can document it.
    """
    simple = binary.rsplit(".", 1)[-1]
    if simple.endswith("Kt"):
        return True
    if "$DefaultImpls" in simple:
        return True
    if simple.endswith("$Companion"):
        owner = binary[: -len("$Companion")]
        return owner in index
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    )
    parser.add_argument("--min", type=float, default=100.0)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    root = args.repo
    contracts = []
    total_types = total_documented = 0

    for name, api_path, source_root in CONTRACTS:
        index = index_module(root, source_root)
        published = published_types(root, api_path)
        published_set = set(published)

        documented = 0
        undocumented: list[str] = []
        for binary in sorted(published_set):
            if binary not in index:
                continue
            if index[binary]:
                documented += 1
            else:
                undocumented.append(binary)

        generated = [
            binary
            for binary in sorted(published_set - set(index))
            if is_compiler_generated(binary, index)
        ]
        unlocated = [
            binary
            for binary in sorted(published_set - set(index))
            if not is_compiler_generated(binary, index)
        ]
        undeclared = sorted(set(index) - published_set)

        total_types += len(published_set) - len(generated)
        total_documented += documented
        contracts.append(
            {
                "contract": name,
                "published": len(published_set),
                "generated": len(generated),
                "measured": len(published_set) - len(generated),
                "documented": documented,
                "undocumented": len(undocumented),
                "undocumented_names": undocumented,
                "unlocated": unlocated,
                "undeclared": undeclared,
            }
        )

    coverage = 100.0 * total_documented / total_types if total_types else 0.0
    failures = []
    if coverage + 1e-9 < args.min:
        failures.append(
            f"KDoc coverage {coverage:.1f}% is below the floor {args.min:g}%"
        )
    for entry in contracts:
        if entry["unlocated"]:
            failures.append(
                f"{entry['contract']}: {len(entry['unlocated'])} published types have no "
                f"source counterpart, so they are measured by nobody"
            )
        if entry["undeclared"]:
            failures.append(
                f"{entry['contract']}: {len(entry['undeclared'])} public types are declared "
                f"in the sources but the contract does not publish them"
            )

    report = {
        "measured": total_types,
        "documented": total_documented,
        "coverage_percent": round(coverage, 1),
        "floor_percent": args.min,
        "contracts": contracts,
        "failures": failures,
    }

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print("Contract KDoc coverage")
        for entry in contracts:
            measured = entry["measured"]
            percent = 100.0 * entry["documented"] / measured if measured else 0.0
            print(
                f"  {entry['contract']:26s} measured={measured:5d} "
                f"documented={entry['documented']:5d} ({percent:5.1f}%) "
                f"undocumented={entry['undocumented']:5d} "
                f"generated={entry['generated']:4d} unlocated={len(entry['unlocated']):4d}"
            )
        print(
            f"  {'TOTAL':26s} measured={total_types:5d} documented={total_documented:5d} "
            f"({coverage:5.1f}%)"
        )
        if failures:
            print("RESULT: FAIL")
            for failure in failures:
                print(f"  - {failure}")
        else:
            print("RESULT: PASS")

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
