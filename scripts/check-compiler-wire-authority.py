#!/usr/bin/env python3
"""The compiler must not write a wire that a codec already owns.

THE GUARANTEE
  Every wire format that a Step owns is written by ONE object: the codec. The DSL
  compiler projects the declared step onto the typed input and delegates the
  encoding to that codec. A compiler that hand-writes the envelope is a SECOND
  producer of that format, and two producers of one format drift: the reader ends
  up carrying aliases nobody declared, or a field is silently defaulted, and the
  run does something the author never asked for.

  The drift is not theoretical. The `writeFile` format had three producers, one of
  them a branch the compiler had stopped reaching: a writer that never writes can
  disagree with the live one for ever without anything noticing. The
  `archiveArtifacts` format hides three defaults on the read side, so a producer
  that forgets one of them does not fail -- it archives something else.

WHY A SCRIPT AND NOT A LINTER RULE
  The guarantee is about the SHAPE of the compiler, not about the shape of a piece
  of Kotlin: a second producer of one format is a fact about two places in the
  tree, and no per-file rule can see it.

WHAT IS SCANNED
  The compiler's source, read from the tree. No build, no subprocess, no network.

THE TABLE
  The table below is the INVENTORY of the migration: for each family whose wire a
  codec owns, it names that codec. A family that is not in the table has no shared
  codec to delegate to (or has no consumer at all), and this guard says nothing
  about it -- a rule that names a format nobody owns would be noise.

  Every codec named here is also CHECKED: if the table names a codec that the tree
  does not hold, the table has rotted and the rule it states is no longer the rule
  that runs. A list that can rot into a list of names is not an authority.

THE TWO DIRECTIONS
  The rule is stated in both directions, because a one-sided rule can be satisfied
  by doing nothing:
    - NEGATIVE: the compiler must not NAME a kind it does not own, i.e. it must
      not write an envelope by hand for a family a codec owns.
    - POSITIVE: the compiler must NAME each codec the table claims it delegates
      to. A compiler that stopped delegating -- falling back to the generic
      `declarativeValue` arm -- writes no hand-made envelope either, so only the
      negative direction would stay green while the family silently degraded.

Exit status is 0 when both directions hold, and 1 with the list of the offenders
when any of them does not.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# The compiler: the one place that lowers a declared step into a wire payload.
COMPILER = (
    ROOT
    / "pipelinek"
    / "pipeline-application"
    / "src"
    / "main"
    / "kotlin"
    / "dev"
    / "rubentxu"
    / "pipeline"
    / "v2"
    / "application"
    / "DslCompiledPipelineCompiler.kt"
)

# The families whose wire a codec owns, and the codec that owns it. The key is
# the `kind` the wire carries: that is the token the compiler would have to name
# to write the envelope by hand.
OWNERS: dict[str, str] = {
    "writeFile": "CoreWriteFileWireCodec",
    "readFile": "CoreReadFileWireCodec",
    "fileExists": "CoreFileExistsWireCodec",
    "deleteDir": "CoreDeleteDirWireCodec",
    "archiveArtifacts": "CoreArchiveArtifactsWireCodec",
}

# The compiler names a kind when it writes it into a JSON object.
KIND = re.compile(r'put\(\s*"kind"\s*,\s*"(?P<kind>[A-Za-z][A-Za-z0-9_]*)"\s*\)')


def blank_comments(text: str) -> str:
    """Blank the comments of a Kotlin source, keeping everything else in place.

    A comment that mentions a kind does not write anything, so it must not be
    read as a violation. The comment regions are replaced by spaces, so the
    offsets of what is left keep pointing at the original line.
    """
    out = []
    index = 0
    length = len(text)
    while index < length:
        if text.startswith('"""', index):
            end = text.find('"""', index + 3)
            end = length if end < 0 else end + 3
            out.append(text[index:end])
            index = end
        elif text[index] == '"':
            end = index + 1
            while end < length:
                if text[end] == "\\":
                    end += 2
                    continue
                if text[end] == '"':
                    end += 1
                    break
                end += 1
            out.append(text[index:end])
            index = end
        elif text.startswith("//", index):
            end = text.find("\n", index)
            end = length if end < 0 else end
            out.append(" " * (end - index))
            index = end
        elif text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = length if end < 0 else end + 2
            out.append("".join("\n" if c == "\n" else " " for c in text[index:end]))
            index = end
        else:
            out.append(text[index])
            index += 1
    return "".join(out)


def codec_exists(name: str) -> bool:
    """Whether the tree holds the codec the table names."""
    pattern = re.compile(r"\bobject\s+" + re.escape(name) + r"\b")
    for path in (ROOT / "pipelinek").rglob("*.kt"):
        if "/build/" in str(path):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        if pattern.search(text):
            return True
    return False


def main() -> int:
    if not COMPILER.is_file():
        print(f"the compiler is not where this rule expects it: {COMPILER}", file=sys.stderr)
        return 1

    try:
        source = COMPILER.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        print(f"could not read the compiler: {error}", file=sys.stderr)
        return 1

    rows: list[str] = []
    missing: list[str] = []

    for kind, codec in OWNERS.items():
        if not codec_exists(codec):
            missing.append(codec)

    code = blank_comments(source)
    for number, line in enumerate(code.splitlines(), start=1):
        for match in KIND.finditer(line):
            kind = match.group("kind")
            if kind not in OWNERS:
                continue
            rows.append(
                f"  {COMPILER.relative_to(ROOT)}:{number}  el compilador escribe a mano el "
                f"envelope de '{kind}': ese formato lo dueno {OWNERS[kind]}"
            )

    # The positive direction: every codec the table claims the compiler delegates
    # to must be NAMED by the compiler's code. A compiler that stopped delegating
    # writes no hand-made envelope either, so without this the negative direction
    # alone would stay green while the family degraded into `declarativeValue`.
    for kind, codec in OWNERS.items():
        if not re.search(r"\b" + re.escape(codec) + r"\b", code):
            rows.append(
                f"  {COMPILER.relative_to(ROOT)}  el compilador no nombra {codec}: "
                f"la familia '{kind}' no delega su ENCODING en el codec que la dueno"
            )

    for line in rows:
        print(line)
    for codec in missing:
        print(f"  {codec}: la tabla nombra un codec que el arbol no tiene")

    if rows or missing:
        print()
        print(f"UNA AUTORIDAD POR FORMATO: FAIL ({len(rows)} infraccion(es))")
        print(
            "  un formato que un codec dueno no lo escribe el compilador: el compilador "
            "proyecta el"
        )
        print(
            "  paso declarado sobre la entrada tipada y delega el ENCODING en el codec."
        )
        return 1

    print(
        f"UNA AUTORIDAD POR FORMATO: OK ({len(OWNERS)} familia(s) con codec dueno, "
        "0 ramas escritas a mano)"
    )
    print("  el compilador no escribe a mano ningun envelope que un codec ya dueno")
    print("  y delega en el codec las 5 familias que la tabla declara")
    return 0


if __name__ == "__main__":
    sys.exit(main())
