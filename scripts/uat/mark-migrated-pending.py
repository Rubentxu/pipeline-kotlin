#!/usr/bin/env python3
"""
Mark Blocked V2 UAT tests with @Disabled("migrated-pending") per
docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md §5.

Why this exists
---------------

BQ5 classifies 18 V2 UAT tests as `Blocked` because they have an
explicit UAT/E2E name but no release-scale tag and no process API.
The legitimate transformation per AGENTS.md and the migration roadmap
is:

  Marcado de una UAT migrada: @Disabled("migrated to harness; see
  pipelinek-release-harness#<issue>") es la única transformación
  válida. Borrar una UAT antes de la migración es prohibido.

Per AGENTS.md:

  No abrir issues contra el harness desde aquí. PipelineK sólo
  entrega candidatas; el harness abre las issues que necesite a
  partir de evidencia propia.

So the @Disabled reason we emit does NOT reference a fabricated
harness issue. It points at the roadmap document and the QUALITY_VALUE
addendum, which is the only authority PipelineK can claim.

This script is idempotent: re-running it on a file that already has
@Disabled("migrated-pending") is a no-op.
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
INVENTORY_DEFAULT = REPO_ROOT / "tmp" / "pipelinek-bq5-inventory.csv"

DISABLED_IMPORT = "import org.junit.jupiter.api.Disabled"
DISABLED_ANNOTATION = '@Disabled("migrated-pending")'
DISABLED_DOC = (
    "// Migrated-pending per docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md BQ5.\n"
    "// The harness is the owner of the equivalence proof; the source stays in place\n"
    "// until that proof arrives and the deletion guard (retirement_guard.py) authorises it.\n"
)


def blocked_v2_paths(inventory_csv: Path) -> list[Path]:
    paths: list[Path] = []
    with inventory_csv.open() as f:
        for row in csv.DictReader(f):
            if (
                row["kind_hint"] == "EXPLICIT_UAT_E2E"
                and row["release_scale_tags"] == "0"
                and not row["observed_process_api"]
                and row["module"].startswith("v2/")
            ):
                paths.append(Path(row["path"]))
    return paths


def already_migrated(text: str) -> bool:
    return 'migrated-pending' in text and "import org.junit.jupiter.api.Disabled" in text


def mark_file(path: Path) -> str:
    """Add the @Disabled annotation to a UAT file. Returns one of: applied, already, no_class_decl."""
    if not path.exists():
        return f"missing: {path}"
    text = path.read_text(encoding="utf-8")
    if already_migrated(text):
        return f"already: {path}"
    # Insert the import after the last `import` line, or after `package` if no imports.
    if DISABLED_IMPORT not in text:
        if "\nimport " in text:
            text = re.sub(
                r"(\nimport [^\n]+\n)(?!import |\n)",
                lambda m: m.group(0) + DISABLED_IMPORT + "\n",
                text,
                count=1,
            )
        else:
            # No imports at all: insert after the package line.
            text = re.sub(
                r"^(package [^\n]+\n)",
                lambda m: m.group(0) + "\n" + DISABLED_IMPORT + "\n",
                text,
                count=1,
            )
    # Insert the @Disabled annotation before the (last) class declaration in the file.
    # The Blocked UATs in v2/* are all class-based; no top-level object with @Test.
    class_pattern = re.compile(
        r"^(\s*)(?:@\w+(?:\([^)]*\))?\s+)*((?:(?:internal |public |private |abstract |sealed |open |final |data |enum )*\s*)?)class\s+(\w+)",
        re.MULTILINE,
    )
    match = None
    for m in class_pattern.finditer(text):
        match = m
    if match is None:
        return f"no_class_decl: {path}"
    indent = match.group(1)
    pre = text[: match.start()]
    if pre.rstrip().endswith('"migrated-pending"') or pre.rstrip().endswith('@Disabled'):
        return f"already: {path}"
    # Reconstruct the full matched span (indent + modifiers + "class " + name),
    # then prepend @Disabled + a doc comment.
    full_match = match.group(0)
    new_class = (
        pre
        + indent
        + DISABLED_ANNOTATION
        + "\n"
        + indent
        + DISABLED_DOC.rstrip("\n")
        + "\n"
        + full_match
        + text[match.end():]
    )
    path.write_text(new_class, encoding="utf-8")
    return f"applied: {path}"


def main(argv: list[str]) -> int:
    inventory = Path(argv[1]) if len(argv) > 1 else INVENTORY_DEFAULT
    if not inventory.exists():
        print(f"inventory not found: {inventory}", file=sys.stderr)
        print("run tooling/inventory_tests.py first", file=sys.stderr)
        return 2
    paths = blocked_v2_paths(inventory)
    if not paths:
        print("no Blocked V2 UATs found in inventory", file=sys.stderr)
        return 0
    applied = 0
    already = 0
    missing = 0
    no_class = 0
    for p in paths:
        result = mark_file(p)
        if result.startswith("applied:"):
            applied += 1
        elif result.startswith("already:"):
            already += 1
        elif result.startswith("missing:"):
            missing += 1
        else:
            no_class += 1
        print(result)
    print(f"summary: applied={applied} already={already} missing={missing} no_class_decl={no_class} total={len(paths)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
