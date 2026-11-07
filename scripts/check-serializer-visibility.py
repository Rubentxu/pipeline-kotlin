#!/usr/bin/env python3
"""The static analysis cannot see the serialization plugin, and this guard
keeps that from mattering again.

The `kotlinx.serialization` compiler plugin generates a `serializer()` function
in the companion of every `@Serializable` class. The static analysis (detekt
2.0.0-alpha.6) resolves types in a STANDALONE Kotlin Analysis API session that
never registers the compiler plugin's services, so a generated `serializer()`
is invisible to it WHEN THE TYPE IS DECLARED IN THE MODULE BEING ANALYSED. A
type that comes from another module is seen as a compiled class, where the
generated method exists in the bytecode, and the analysis resolves it.

That blindness has a cost: the analysis cannot tell a real `UnreachableCode`
from an invented one, and the gate cannot tolerate a finding it cannot judge.
So the repository obtains the serializer of a type it declares through the
REIFIED lookup, `kotlinx.serialization.serializer<T>()`, which the analysis
resolves (it is a library function) and which the plugin rewrites at compile
time into the very same generated serializer.

This guard reads the tree and nothing else: no network, no build, no
subprocess. It asks one question of every source set:

  a source set must not call `T.serializer()` on a type that the SAME source
  set declares.

The rows are reported one per line, and the run fails when a row fails.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODULES = ROOT / "pipelinek"

# The declaration of a type. `class`, `interface` and `object` are the three
# words a Kotlin type is introduced with; the modifiers in front of them
# (`data`, `value`, `enum`, `sealed`, `annotation`, `fun`) do not change the
# name that follows.
DECLARATION = re.compile(r"\b(?:class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)")

# A call to the generated serializer: the receiver is a (possibly qualified)
# type name.
CALL = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\.serializer\(\)")


def strip_noise(text: str) -> str:
    """Remove the comments and the string literals of a Kotlin source.

    A guard that reads the words of a file has to read the CODE of that file:
    a comment that mentions `Foo.serializer()` does not call anything, and a
    string that carries it is a payload, not a call. The block comments, the
    line comments and the literals are replaced by spaces, so the offsets of
    what is left keep pointing at the original line.
    """
    out = []
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        if text.startswith('"""', index):
            end = text.find('"""', index + 3)
            end = length if end < 0 else end + 3
            out.append(" " * (end - index))
            index = end
        elif char == '"':
            end = index + 1
            while end < length:
                if text[end] == "\\":
                    end += 2
                    continue
                if text[end] == '"':
                    end += 1
                    break
                end += 1
            out.append(" " * (end - index))
            index = end
        elif text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = length if end < 0 else end + 2
            out.append("".join("\n" if c == "\n" else " " for c in text[index:end]))
            index = end
        elif text.startswith("//", index):
            end = text.find("\n", index)
            end = length if end < 0 else end
            out.append(" " * (end - index))
            index = end
        else:
            out.append(char)
            index += 1
    return "".join(out)


def source_sets() -> list[Path]:
    """Every source set of every module, in a stable order."""
    found = []
    if not MODULES.is_dir():
        return found
    for module in sorted(p for p in MODULES.iterdir() if p.is_dir()):
        source = module / "src"
        if not source.is_dir():
            continue
        for entry in sorted(p for p in source.iterdir() if p.is_dir()):
            kotlin = entry / "kotlin"
            if kotlin.is_dir():
                found.append(kotlin)
    return found


def declared_types(root: Path) -> set[str]:
    """The names of the types the source set declares."""
    names: set[str] = set()
    for path in sorted(root.rglob("*.kt")):
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        names.update(DECLARATION.findall(strip_noise(text)))
    return names


def blind_calls(root: Path, names: set[str]) -> list[tuple[str, int, str]]:
    """The calls to a generated serializer the analysis cannot resolve."""
    found = []
    for path in sorted(root.rglob("*.kt")):
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        code = strip_noise(text)
        for number, line in enumerate(code.splitlines(), start=1):
            for match in CALL.finditer(line):
                receiver = match.group(1)
                # The first segment of the receiver names the type: a nested
                # type is reached through the type that holds it, and a type
                # of another module is not declared here.
                if receiver.split(".")[0] in names:
                    found.append((str(path.relative_to(ROOT)), number, receiver))
    return found


def main() -> int:
    rows = []
    failures = []
    checked = 0
    for root in source_sets():
        names = declared_types(root)
        if not names:
            continue
        checked += 1
        blind = blind_calls(root, names)
        relative = root.relative_to(ROOT)
        if blind:
            for path, number, receiver in blind:
                rows.append(
                    f"  {path}:{number}  el analisis no ve '{receiver}.serializer()': "
                    f"el tipo lo declara este mismo source set"
                )
            failures.append(str(relative))

    for line in rows:
        print(line)
    if failures:
        print(f"\nSERIALIZADOR VISIBLE: FAIL ({len(failures)} source set(s): {', '.join(failures)})")
        print("El analisis estatico no ve el plugin de serializacion: un 'T.serializer()' sobre un tipo que declara el propio modulo queda sin resolver.")
        print("Escriba 'serializer<T>()': el plugin reescribe esa busqueda reificada hacia el MISMO serializer generado.")
        return 1
    print(f"SERIALIZADOR VISIBLE: OK ({checked} source set(s), ninguna llamada ciega)")
    print("El analisis estatico ve el serializer de cada tipo que declara el propio modulo.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
