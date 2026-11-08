#!/usr/bin/env python3
"""The shape of the catalogue of examples that this tree carries.

The catalogue is a VIEW: the release harness writes it from the catalogue of
scenarios, and the harness is what knows what each example expects and what
its outcome is. What this tree can know about itself is the SHAPE, and the
shape is what this guard checks:

  1. every example directory holds exactly three things: its pipeline, its
     README in English and its README in Spanish -- one more or one less is
     a failure, because a README without its pipeline documents nothing and
     a pipeline without its README documents nothing either;
  2. the name of the directory is autodescriptive: it does not start with a
     number, nor with a ticket prefix. The historical name lives in the
     README, which is where the lineage belongs;
  3. the pipeline of an example is named after the directory that holds it,
     so a directory that says one thing and contains another is a failure;
  4. every index declares the examples there are: the number of rows of an
     index, and the number a group index declares for each of its groups,
     are the number of example directories on disk;
  5. the two languages of an index reach the same examples.

This guard does NOT reimplement the catalogue. It does not know what an
example expects, and a guard that guesses reports a defect where there is
none. What it checks is the shape, and the shape is what the tree can know
about itself.

Exit 0 when the shape is right, 1 otherwise.

Usage: check-catalogue-shape.py [--repo ROOT]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

CATALOGUE_ROOT = Path("examples", "catalogue")

#: The two languages of every document of the catalogue.
LANGUAGES = ("README.md", "README.es.md")

#: The pipeline of an example.
PIPELINE_SUFFIX = ".pipeline.kts"

#: The names a directory may not start with: a number says WHEN it was
#: written, and a ticket says WHO asked for it. Neither says what the
#: example is about.
#:
#: A ticket prefix is a leading segment that carries a digit, like the
#: `har006` or the `wu-rp-053` of the historical names. The rule looks at
#: the LEADING segments only: a digit in the middle of a name says nothing
#: about who asked for the example.
NUMERIC_PREFIX = re.compile(r"^\d")
TICKET_PREFIX = re.compile(r"^(?:[a-z0-9]+-)*[a-z0-9]*[0-9][a-z0-9]*-")

#: The link of a row of an index: the path it points at, and the text of
#: the last cell, which is the number of examples of a group.
ROW_LINK = re.compile(r"\]\(([^)]+)\)")


def example_directories(root: Path) -> dict[Path, list[str]]:
    """Every directory that holds a pipeline, and the files it holds.

    The key is the directory; the value is the names of the files in it.
    A directory that holds no pipeline is an index, not an example.
    """
    found: dict[Path, list[str]] = {}
    for path in sorted(root.rglob("*")):
        if not path.is_dir():
            continue
        if any(child.name.endswith(PIPELINE_SUFFIX) for child in path.iterdir()):
            found[path] = sorted(child.name for child in path.iterdir())
    return found


def index_directories(root: Path) -> list[Path]:
    """Every directory that holds an index, in both languages.

    The root of the catalogue is one of them: `rglob` does not yield the
    directory it starts from, and an index that nobody checks is an index
    that can drift.
    """
    indexes: list[Path] = []
    for path in [root, *sorted(root.rglob("*"))]:
        if not path.is_dir():
            continue
        if any(child.name.endswith(PIPELINE_SUFFIX) for child in path.iterdir()):
            continue
        if any((path / name).is_file() for name in LANGUAGES):
            indexes.append(path)
    return indexes


def rows_of(text: str) -> list[tuple[str, str]]:
    """The rows of the table of an index: the link and the last cell."""
    rows: list[tuple[str, str]] = []
    for line in text.splitlines():
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) < 2:
            continue
        match = ROW_LINK.search(cells[0])
        if match is None:
            continue
        rows.append((match.group(1), cells[-1]))
    return rows


def resolve(root: Path, index: Path, link: str) -> Path | None:
    """The directory a link of an index points at, if it points at one.

    The links of an index are not all relative to the same place: an index
    of examples links from the root of the catalogue, and an index of groups
    links from its own directory. Both are tried, and the one that exists
    is the one the link means.

    A link points at a README; the directory that holds it is the one the
    row is about.
    """
    for base in (root, index):
        candidate = (base / link).resolve()
        if candidate.is_file() and candidate.parent.is_dir():
            return candidate.parent
    return None


def scope(directory: Path, examples: dict[Path, list[str]]) -> set[Path]:
    """The example directories under a directory, at any depth."""
    return {path for path in examples if path.is_relative_to(directory)}


def reachable(root: Path, index: Path, examples: dict[Path, list[str]],
              findings: list[str], language: str) -> set[Path]:
    """The examples an index reaches, following the links it declares.

    A row that points at an example reaches that example. A row that points
    at a group reaches the examples of that group, and the number it
    declares has to be the number there are.

    The language is an argument and not a constant: an index is written in
    two languages, and the two have to be compared. Reading the same file
    twice would compare the English index with itself, and the comparison
    would pass without ever looking at the Spanish one.
    """
    reached: set[Path] = set()
    for link, declared in rows_of(
            (index / language).read_text(encoding="utf-8")):
        target = resolve(root, index, link)
        if target is None:
            findings.append(
                f"{label(root, index)}: a row points at {link}, which is "
                "not a directory of the catalogue")
            continue
        if target in examples:
            reached.add(target)
            continue
        # A row that points at a group declares how many examples it has.
        inside = scope(target, examples)
        if not declared.isdigit():
            findings.append(
                f"{label(root, index)}: the group {link} declares "
                f"{declared!r} examples, and a number is what it declares")
            continue
        if int(declared) != len(inside):
            findings.append(
                f"{label(root, index)}: the group {link} declares "
                f"{declared} examples and there are {len(inside)}")
        reached |= inside
    return reached


def label(root: Path, path: Path) -> str:
    """The name a finding carries: where it is, in the words of the tree."""
    relative = path.relative_to(root)
    return 'the root of the catalogue' if relative == Path('.') \
        else relative.as_posix()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parent.parent))
    args = parser.parse_args()
    repo = Path(args.repo).resolve()
    root = repo / CATALOGUE_ROOT

    findings: list[str] = []

    if not root.is_dir():
        print(f"ERROR: {CATALOGUE_ROOT.as_posix()} does not exist: there is "
              "no catalogue to check")
        return 1
    # The paths of the tree are resolved once, so that a directory and the
    # link that points at it are the same path even when the tree is
    # reached through a symbolic link.
    root = root.resolve()

    examples = example_directories(root)
    indexes = index_directories(root)

    # 1, 2 and 3: what every example directory holds, and how it is named.
    for directory, names in examples.items():
        expected = sorted([*LANGUAGES, f"{directory.name}{PIPELINE_SUFFIX}"])
        if names != expected:
            findings.append(
                f"{label(root, directory)}: holds {names} and the example "
                f"needs exactly {expected}")
        if NUMERIC_PREFIX.match(directory.name):
            findings.append(
                f"{label(root, directory)}: the name of the directory starts "
                "with a number")
        if TICKET_PREFIX.match(directory.name):
            findings.append(
                f"{label(root, directory)}: the name of the directory starts "
                "with a ticket prefix")
        pipelines = [name for name in names if name.endswith(PIPELINE_SUFFIX)]
        if pipelines and pipelines != [f"{directory.name}{PIPELINE_SUFFIX}"]:
            findings.append(
                f"{label(root, directory)}: the pipeline is called "
                f"{pipelines[0]} and the directory that holds it is called "
                f"{directory.name}")

    # 4 and 5: what every index declares, and that both languages agree.
    for index in indexes:
        for name in LANGUAGES:
            if not (index / name).is_file():
                findings.append(
                    f"{label(root, index)}: has no {name}: an index is written "
                    "in the two languages")
        if not all((index / name).is_file() for name in LANGUAGES):
            continue
        english = reachable(root, index, examples, findings, LANGUAGES[0])
        spanish = reachable(root, index, examples, findings, LANGUAGES[1])
        if english != spanish:
            findings.append(
                f"{label(root, index)}: the two languages reach different "
                f"examples: {len(english)} in English and {len(spanish)} in "
                "Spanish")
        inside = scope(index, examples)
        if english != inside:
            missing = sorted(str(path.relative_to(root))
                             for path in inside - english)
            extra = sorted(str(path.relative_to(root))
                           for path in english - inside)
            detail = []
            if missing:
                detail.append(f"not in the index: {missing}")
            if extra:
                detail.append(f"in the index but not on disk: {extra}")
            findings.append(
                f"{label(root, index)}: the index reaches {len(english)} "
                f"examples and there are {len(inside)}"
                + ("; " + "; ".join(detail) if detail else ""))

    print("the shape of the catalogue of examples")
    print(f"  examples:   {len(examples)}")
    print(f"  indexes:    {len(indexes)}")

    if findings:
        print(f"  FAILURES:   {len(findings)}")
        for finding in findings:
            print(f"    - {finding}")
        return 1

    print("  every example has its pipeline and its two READMEs, every index "
          "declares the examples there are")
    return 0


if __name__ == "__main__":
    sys.exit(main())
