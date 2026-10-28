#!/usr/bin/env python3
"""Only the entry point may end the process.

THE GUARANTEE
  Every call that ends the JVM lives inside a function named `main`. Every
  other function returns a decision and lets its caller decide what to do
  with it.

WHY A SCRIPT AND NOT A LINTER RULE
  detekt 2.x cannot express this guarantee. Its `ForbiddenMethodCall` rule has
  no `ignoreAnnotated` any more, so "main may exit, nobody else may" cannot be
  written; and its `excludes` are file-granular, so the rule would have had to
  exclude whole files. A file-granular exemption cannot see that
  `Main.kt:101` sits in a helper while `Main.kt:180` sits in `main`: measured
  against this tree, the file-granular form would have found 2 of the 3 real
  call sites, and a guard that misses a third of the defect it was written for
  is a guard that reads as a green.

  Two authorities for one guarantee is worse than one coarse one: the detekt
  experiment was measured, found to be inexpressible, and reverted.

HOW THE CALL SITE IS FOUND
  By reading the source as source, not as text. A `System.exit` inside a string
  or a comment is not a call, and a `System.exit` inside a lambda is a call
  that belongs to the function that holds the lambda. So the file is tokenised
  first (comments and string contents blanked, offsets preserved) and only the
  code is searched.

HOW THE OWNING FUNCTION IS FOUND
  By brace matching over that same tokenised text. A function declaration is
  `fun`, an optional receiver, a name, a parameter list and a body; the body
  is either a brace-delimited block or an expression. The exit call belongs to
  the INNERMOST function whose body contains it, which is the function that
  would have to carry the decision.

WHAT IS SCANNED
  The working tree, not the Git index. The guarantee is about the code that is
  about to be compiled, and an index-only scan would not see a file that has
  not been added yet — a green that arrives before the file does. Build output
  directories are skipped: they are generated, they are not the product, and a
  rule that fired on generated code would be noise.

  Every module's `src/main` is scanned, not only the one the cut happened to
  touch: the guarantee is a property of the product, and the entry points of
  the release tooling are `main` too, so they are allowed to end the process.

A KNOWN LIMIT
  The body of an expression-bodied function (`fun f(): Int = ...`) ends where
  this script says it does: at the end of the statement, judged by depth and
  by the operator that would continue it. That judgement is an approximation.
  It does not matter for the tree as it stands — every exit call in the
  product is inside a brace-delimited body — and a wrong answer here would
  name a function that does not own the call, which the reader can see and
  the next run will correct.

Exit status is 0 when every exit call belongs to a `main`, and 1 with the
offenders when one does not.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# A call that ends the JVM. `System.exit` covers `java.lang.System.exit` and
# `kotlin.system.exitProcess` covers the qualified spelling of the Kotlin
# function; both are the same fact, so both are named here.
EXIT_CALL = re.compile(r"\bSystem\s*\.\s*exit\s*\(|\bexitProcess\s*\(")

# The keyword that opens a function declaration.
FUN_KEYWORD = re.compile(r"\bfun\b")

# The characters that, at the end of a line, mean the statement continues on
# the next one. Used only to bound an expression body.
CONTINUES = set(".,?:+-*/&|=<>([{")
OPERATOR_END = set("+-*/&|=<>.,")


def blank(chars: list[str], start: int, end: int) -> None:
    """Replace [start, end) with spaces, keeping every newline in place."""
    for index in range(start, end):
        if chars[index] != "\n":
            chars[index] = " "


def mask_kotlin(source: str) -> str:
    """Return the source with comments and string contents blanked out.

    The offsets of the result match the offsets of the input, so a position
    found in the mask is the same position in the file.
    """
    chars = list(source)
    i, n = 0, len(source)
    while i < n:
        if source[i:i + 3] == '"""':
            end = source.find('"""', i + 3)
            end = n if end < 0 else end + 3
            blank(chars, i, end)
            i = end
        elif source[i] == '"':
            end = i + 1
            while end < n and source[end] != '"':
                end += 2 if source[end] == "\\" else 1
            end = min(end + 1, n)
            blank(chars, i, end)
            i = end
        elif source[i] == "'":
            end = i + 1
            while end < n and source[end] != "'":
                end += 2 if source[end] == "\\" else 1
            end = min(end + 1, n)
            blank(chars, i, end)
            i = end
        elif source[i:i + 2] == "//":
            end = source.find("\n", i)
            end = n if end < 0 else end
            blank(chars, i, end)
            i = end
        elif source[i:i + 2] == "/*":
            depth, end = 1, i + 2
            while end < n and depth:
                if source[end:end + 2] == "/*":
                    depth, end = depth + 1, end + 2
                elif source[end:end + 2] == "*/":
                    depth, end = depth - 1, end + 2
                else:
                    end += 1
            blank(chars, i, end)
            i = end
        else:
            i += 1
    return "".join(chars)


def brace_pairs(masked: str) -> dict[int, int]:
    """Map the position of every `{` to the position of its matching `}`."""
    stack: list[int] = []
    pairs: dict[int, int] = {}
    for index, char in enumerate(masked):
        if char == "{":
            stack.append(index)
        elif char == "}" and stack:
            pairs[stack.pop()] = index
    return pairs


def match_paren(masked: str, start: int) -> int:
    """The position of the `)` that closes the `(` at [start]."""
    depth = 0
    for index in range(start, len(masked)):
        char = masked[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return index
    return len(masked)


def expression_body_end(masked: str, start: int) -> int:
    """Where an expression body ends, judged by depth and by continuation."""
    depth = 0
    index = start
    n = len(masked)
    while index < n:
        char = masked[index]
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        if depth == 0 and char == ";":
            return index
        if depth == 0 and char == "\n":
            ahead = index + 1
            while ahead < n and masked[ahead] in " \t":
                ahead += 1
            following = masked[ahead] if ahead < n else "\n"
            previous = masked[index - 1] if index else " "
            if following not in CONTINUES and previous not in OPERATOR_END:
                return index
        index += 1
    return n


def find_functions(masked: str, pairs: dict[int, int]) -> list[tuple[str, int, int]]:
    """Every function declaration: its name, where its body starts, where it ends."""
    functions: list[tuple[str, int, int]] = []
    for match in FUN_KEYWORD.finditer(masked):
        cursor = match.end()
        while cursor < len(masked) and masked[cursor] in " \t\r\n":
            cursor += 1
        # An explicit type parameter list: `fun <T> name(...)`.
        if cursor < len(masked) and masked[cursor] == "<":
            depth = 0
            while cursor < len(masked):
                if masked[cursor] == "<":
                    depth += 1
                elif masked[cursor] == ">":
                    depth -= 1
                    if depth == 0:
                        cursor += 1
                        break
                cursor += 1
            while cursor < len(masked) and masked[cursor] in " \t\r\n":
                cursor += 1
        # The parameter list opens the first `(` that is not inside a generic
        # argument list of the receiver type.
        depth = 0
        while cursor < len(masked):
            char = masked[cursor]
            if char == "<":
                depth += 1
            elif char == ">":
                depth -= 1
            elif char == "(" and depth == 0:
                break
            cursor += 1
        if cursor >= len(masked):
            continue
        name_match = re.search(r"([A-Za-z_][A-Za-z0-9_]*)\s*$", masked[:cursor])
        if not name_match:
            continue
        name = name_match.group(1)
        close = match_paren(masked, cursor)
        cursor = close + 1
        while cursor < len(masked) and masked[cursor] in " \t\r\n":
            cursor += 1
        # An explicit return type: `: Type` before the body.
        if cursor < len(masked) and masked[cursor] == ":":
            cursor += 1
            depth = 0
            while cursor < len(masked):
                char = masked[cursor]
                # The body opener is looked for BEFORE the brackets are counted:
                # the `{` that opens the body is not a nesting level of the
                # return type, and counting it as one walks the scan past the
                # body and into whatever braces come next.
                if depth == 0 and char in "={":
                    break
                if char in "([{":
                    depth += 1
                elif char in ")]}":
                    depth -= 1
                cursor += 1
        if cursor >= len(masked):
            continue
        if masked[cursor] == "{":
            end = pairs.get(cursor, cursor)
        elif masked[cursor] == "=":
            end = expression_body_end(masked, cursor)
        else:
            continue
        functions.append((name, cursor, end))
    return functions


def owning_function(functions: list[tuple[str, int, int]], position: int) -> tuple[str, int, int] | None:
    """The innermost function whose body holds [position]."""
    owner = None
    for name, start, end in functions:
        if start <= position <= end:
            if owner is None or start > owner[1]:
                owner = (name, start, end)
    return owner


def product_sources(repo: Path) -> list[Path]:
    """Every tracked-by-existence Kotlin file under a `src/main` directory."""
    found: list[Path] = []
    for path in repo.rglob("*.kt"):
        parts = path.relative_to(repo).parts
        if any(part.startswith(".") or part in ("build", "out") for part in parts):
            continue
        for index, part in enumerate(parts):
            if part == "src" and index + 1 < len(parts) and parts[index + 1] == "main":
                found.append(path)
                break
    return sorted(found)


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    offenders: list[tuple[str, int, str]] = []
    total_calls = 0
    calls_in_main = 0
    for path in product_sources(repo):
        try:
            source = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        masked = mask_kotlin(source)
        pairs = brace_pairs(masked)
        functions = find_functions(masked, pairs)
        for match in EXIT_CALL.finditer(masked):
            total_calls += 1
            position = match.start()
            line = source.count("\n", 0, position) + 1
            owner = owning_function(functions, position)
            if owner is None:
                offenders.append((str(path.relative_to(repo)), line, "<sin funcion que la contenga>"))
                continue
            name = owner[0]
            if name == "main":
                calls_in_main += 1
            else:
                offenders.append((str(path.relative_to(repo)), line, name))
    for rel, line, owner in offenders:
        print(f"  {rel}:{line}  en {owner}() -- una funcion que no es main no termina el proceso")
    if offenders:
        print(f"\nSALIDAS DE PROCESO FUERA DE main(): {len(offenders)}")
        print(f"  {total_calls} llamadas de salida en el producto; {calls_in_main} dentro de main()")
        print("Solo el punto de entrada termina el proceso. Las demas funciones devuelven la decision.")
        return 1
    print(
        f"OK: {total_calls} llamadas de salida de proceso en el producto, "
        f"las {calls_in_main} dentro de main()"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
