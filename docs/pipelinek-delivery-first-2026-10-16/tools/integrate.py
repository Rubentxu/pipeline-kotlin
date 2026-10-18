#!/usr/bin/env python3
"""Safe documentation-only application of the PipelineK delivery-first overlay.

This does not run tests or mutate productive Kotlin/Gradle/release scripts.
Dry-run is the default. No silent overwrites or guesses about repository layout.
"""
from __future__ import annotations

import argparse
import difflib
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / "overlay"
ROADMAP = Path("docs/v2/05-roadmap/ROADMAP.md")
ADDENDUM = Path("docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md")
ROADMAP_MARKER = "[DELIVERY-FIRST-2026-10-16]"
REFERENCE_LINE = (
    "**Extensión ejecutable de BQ1–BQ5 " + ROADMAP_MARKER + ":** "
    "[DELIVERY_FIRST_IMPLEMENTATION_2026-10-16.md]"
    "(DELIVERY_FIRST_IMPLEMENTATION_2026-10-16.md) integra hitos, "
    "ADR propuesta y aceptación efímera; no altera las precedencias RP, "
    "la autoridad de este roadmap ni el certifier externo."
)
ADDENDUM_SECTION = """

### Enlace al paquete de implementación delivery-first [DELIVERY-FIRST-2026-10-16]

Para ejecutar los BQ1–BQ5 **sin reemplazar** el roadmap existente, usar
[`DELIVERY_FIRST_IMPLEMENTATION_2026-10-16.md`](DELIVERY_FIRST_IMPLEMENTATION_2026-10-16.md)
y sus detalles en `DELIVERY_FIRST_IMPLEMENTATION_DELTA.md`,
`DELIVERY_FIRST_MINIMAL_ACCEPTANCE.md`, `DELIVERY_FIRST_AGENT_TEAMS.md`.
Reutilizar ADR-PK-QV-001..004 aceptados. ADR-PK-DF-001 es **PROPOSED**;
mientras no se apruebe, sigue siendo obligatorio `AGENTS.md` §8 (5 assets).
La limpieza de UAT/E2E/AAT se limita a los probes de este ciclo; los tests
heredados requieren equivalencia externa exacta y autorización.
"""


def anchored(repo: Path, rel: Path) -> Path:
    raw = repo / rel
    if raw.is_symlink():
        raise ValueError(f"symlink destination refused: {raw}")
    resolved = raw.resolve(strict=False)
    if not resolved.is_relative_to(repo.resolve()):
        raise ValueError(f"path escapes repository: {raw}")
    return raw


def replace_once(text: str, token: str, value: str) -> str:
    if text.count(token) != 1:
        raise ValueError(f"roadmap anchor must appear exactly once: {token!r}, got {text.count(token)}")
    return text.replace(token, token + "\n" + value, 1)


def prepare(repo: Path) -> dict[Path, str]:
    if not (repo / "AGENTS.md").is_file() or not (repo / ROADMAP).is_file() or not (repo / ADDENDUM).is_file():
        raise ValueError("not the pipeline-kotlin repo root (missing AGENTS.md or canonical roadmap/addendum)")
    changed: dict[Path, str] = {}
    for src in sorted(OVERLAY.rglob("*")):
        if not src.is_file():
            continue
        rel = src.relative_to(OVERLAY)
        dst = anchored(repo, rel)
        new = src.read_text(encoding="utf-8")
        if dst.exists() and dst.read_text(encoding="utf-8") != new:
            raise ValueError(f"existing overlay differs: {rel}; integrate manually, no overwrites")
        if not dst.exists():
            changed[dst] = new

    rm = anchored(repo, ROADMAP)
    orig = rm.read_text(encoding="utf-8")
    if ROADMAP_MARKER not in orig:
        anchor = next((line for line in orig.splitlines() if line.startswith("**Continuación auditada (2026-10-16):**")), None)
        if not anchor:
            raise ValueError("canonical ROADMAP changed: reference anchor absent; integrate manually")
        changed[rm] = replace_once(orig, anchor, REFERENCE_LINE)
    elif REFERENCE_LINE not in orig:
        raise ValueError("existing roadmap marker differs; resolve manually")

    add = anchored(repo, ADDENDUM)
    original = add.read_text(encoding="utf-8")
    if ROADMAP_MARKER not in original:
        changed[add] = original.rstrip("\n") + "\n" + ADDENDUM_SECTION.lstrip("\n")
    elif ADDENDUM_SECTION.strip() not in original:
        raise ValueError("existing addendum marker differs; resolve manually")
    return changed


def atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tempname = tempfile.mkstemp(prefix=".delivery-first-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(content)
        os.replace(tempname, path)
    finally:
        if os.path.exists(tempname):
            os.unlink(tempname)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--dry-run", action="store_true", help="show planned changes (default)")
    group.add_argument("--apply", action="store_true", help="apply safe documentation additions")
    args = parser.parse_args()
    repo = args.repo.expanduser().resolve()
    if not repo.is_dir():
        parser.error(f"repository directory not found: {repo}")
    try:
        changes = prepare(repo)
    except (ValueError, OSError) as exc:
        print(f"REFUSED: {exc}", file=sys.stderr)
        return 2
    if not changes:
        print("ALREADY_APPLIED: overlay and links match; no changes")
        return 0
    for path, content in changes.items():
        rel = path.relative_to(repo)
        print(("APPLY " if args.apply else "WOULD_ADD/UPDATE ") + str(rel))
        if not args.apply and path.exists():
            diff = difflib.unified_diff(path.read_text(encoding="utf-8").splitlines(),content.splitlines(),fromfile=str(rel),tofile=str(rel)+" (overlay)",lineterm="")
            print("\n".join(diff)[:5500])
    if args.apply:
        for path, content in changes.items():
            atomic_write(path, content)
        print(f"APPLIED: {len(changes)} documentation files; review git diff before commit")
    else:
        print("DRY_RUN: nothing modified. Pass --apply to apply after review.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
