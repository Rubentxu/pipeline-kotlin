#!/usr/bin/env python3
"""The KDoc that promises an exception the code does not throw.

A `@throws` on a function is part of its contract: it is what a consumer reads
to know which exception to expect and to catch. A `@throws` that names an
exception the code never produces is a promise the product does not keep — the
catcher waits for a class that never arrives, and the exception that does
arrive goes uncaught.

This script measures the whole product and FAILS when a promise cannot be
kept. It is a guard, so it has to be able to fail: the defect it watches for
is the one where a documented exception and the thrown one are two different
classes.

WHAT IT CAN PROVE, AND HOW
---------------------------

Proving that an exception is NEVER thrown needs the whole call graph, and a
call graph read from the text alone is a guess. This script resolves calls by
RECEIVER instead of by name, because a name alone is not enough:

  * an unqualified call resolves to the members of the enclosing type and the
    top-level functions of the same file;
  * a call on a local — or on a constructor property — whose type is a type
    this script knows resolves to the members of that type;
  * a call on a receiver this script cannot type — a JDBC connection, a
    stream, a value returned by a factory — resolves to NOTHING.

That last rule is the one that matters. A `connection.close()` is not the
project's `close()`, and treating it as such would let any project function
that throws `IllegalStateException` satisfy any promise anywhere: the graph
would answer "yes, it can throw it" for reasons that have nothing to do with
the code under the promise.

THE VERDICT OF A PROMISE
-------------------------

A promise names a type E. The script asks what the function can throw, and it
answers with one of four verdicts:

  KEPT        the function can throw E, or something that inherits from it.
  BROKEN      the PRODUCT knows how to produce E — the script has seen E
              raised by one of the constructions it understands — and this
              function cannot. The promise names a class this code never
              raises here.
  NOT CHECKED the promise names a class the script cannot attribute to any
              producing site it can see, so the script does not claim it is
              false. An exception raised by an external API is the case: the
              script reads this repository, not the JDK's signatures.
  UNREADABLE  the declaration has no body to read — an interface method, a
              property, an expression body. The promise is a contract for the
              implementations, and this script does not judge it.

The third verdict is what keeps the guard honest. A guard that called every
unchecked promise broken would report the exceptions that come out of the
JDK, and a guard with that many false alarms is a guard nobody reads.

A PORT CARRIES ITS PROMISE TO WHOEVER CALLS IT. An interface method has no
body, so the script cannot see what it throws; what it DECLARES is what its
callers may receive. The script therefore lets a declaration without a body
pass its promise to the functions that call it, and judges the promise on the
declaration itself as UNREADABLE.

WHAT IT DOES NOT CLAIM
-----------------------

The script measures the promises of the PRODUCT (`src/main`), because a
promise in a test is read by nobody. It measures `@throws` and the prose that
names an exception as the one the code throws, and it says how many of each it
measured.

Exit 0 when every promise it can check is kept, 1 otherwise.

Usage: check-kdoc-throws.py [--repo ROOT] [--json]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import defaultdict

# --- The vocabulary -------------------------------------------------------------

# The hierarchy that says a promise of a base class is kept by a concrete
# exception that inherits from it. The product's own types are added to it
# when the sources are read.
JDK_SUPER: dict[str, str | None] = {
    "IllegalArgumentException": "RuntimeException",
    "IllegalStateException": "RuntimeException",
    "UnsupportedOperationException": "RuntimeException",
    "SecurityException": "RuntimeException",
    "NullPointerException": "RuntimeException",
    "NumberFormatException": "IllegalArgumentException",
    "ConcurrentModificationException": "RuntimeException",
    "IllegalMonitorStateException": "RuntimeException",
    "IndexOutOfBoundsException": "RuntimeException",
    "ClassCastException": "RuntimeException",
    "ArithmeticException": "RuntimeException",
    "IOException": "Exception",
    "FileNotFoundException": "IOException",
    "UncheckedIOException": "RuntimeException",
    "GeneralSecurityException": "Exception",
    "AEADBadTagException": "GeneralSecurityException",
    "SQLException": "Exception",
    "RuntimeException": "Exception",
    "Exception": "Throwable",
    "Error": "Throwable",
    "Throwable": None,
    "AssertionError": "Error",
    "NotImplementedError": "Error",
}

# The Kotlin standard library calls that throw a known exception. A promise of
# `IllegalArgumentException` on a function whose body holds a `require` is kept
# by that `require`.
STANDARD_CALL: dict[str, str] = {
    "require": "IllegalArgumentException",
    "check": "IllegalStateException",
    "error": "IllegalStateException",
    "TODO": "NotImplementedError",
    "assert": "AssertionError",
}

# The catch clauses whose rethrow adds NO type: `catch (e: Exception)` hands
# back whatever arrived, and whatever arrived is already counted by the
# calibrators of the `try`. Counting `Exception` here would keep EVERY
# promise, and a guard that cannot fail is not a guard.
DYNAMIC_CATCHES = {"Exception", "Throwable", "RuntimeException"}

# The words that are a LANGUAGE CONSTRUCT and not a call, when they are
# followed by an opening parenthesis. The list is short on purpose: a word
# that only a construct can introduce never appears followed by "(" in a call,
# so `open` — a visibility modifier AND the name of a door — is not in it, and
# a call to `open(file)` is a call.
CALL_STOP_WORDS = {
    "if", "for", "while", "when", "catch", "return", "throw", "is", "as", "in",
    "do", "else", "try", "finally", "fun", "class", "object", "interface",
    "val", "var", "typealias", "package", "import", "by", "where", "super",
    "this", "true", "false", "null", "constructor", "init", "get", "set",
    "field", "it",
}

# --- The patterns ---------------------------------------------------------------

KDOC_START = re.compile(r"^\s*/\*\*")
KDOC_END = re.compile(r"^\s*\*/")
THROWS_TAG = re.compile(r"@throws\s+([A-Za-z_][A-Za-z0-9_.]*)")
PROSE_THROW = re.compile(
    r"\b(?:throw(?:s|ing)?|raised?|raises)\s+(?:an?\s+|the\s+)?\[([A-Za-z_][A-Za-z0-9_.]*)\]"
)
FUN_DECL = re.compile(
    r"^(?P<indent>[ ]*)"
    r"(?:(?:public|private|internal|protected|override|open|final|suspend|inline|infix|operator|abstract|external|tailrec)[ ]+)*"
    r"fun\s+(?:<[^>]*>[ ]*)?"
    r"(?:(?P<recv>[A-Za-z_][A-Za-z0-9_]*)[.]::)?"
    r"(?P<name>[A-Za-z_][A-Za-z0-9_]*)[ ]*\("
)
TYPE_DECL = re.compile(
    r"^(?P<indent>[ ]*)"
    r"(?:(?:public|private|internal|protected|open|abstract|sealed|final|data|value|enum|annotation|inline|fun|companion)[ ]+)*"
    r"(?:class|object|interface)[ ]+(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
)
TYPE_SUPER = re.compile(
    r"^(?:class|object|interface)[ ]+[A-Za-z_][A-Za-z0-9_]*(?:[ ]*\([^)]*\))?[ ]*:[ ]*(?P<super>[A-Za-z_][A-Za-z0-9_.]*)"
)
TYPEALIAS = re.compile(r"^typealias\s+([A-Za-z_][A-Za-z0-9_]*)")
LOCAL_TYPED = re.compile(
    r"\b(?:val|var)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?P<type>[A-Za-z_][A-Za-z0-9_.]*)"
)
CONSTRUCTOR_PROPERTY = re.compile(
    r"(?:^|[(,])\s*(?:private\s+|public\s+|internal\s+|protected\s+)?"
    r"(?:val|var)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?P<type>[A-Za-z_][A-Za-z0-9_.]*)"
)
STRING_LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\'')


def simple(name: str) -> str:
    """The simple name of a qualified type."""
    return name.rsplit(".", 1)[-1]


def strip_comments(text: str) -> str:
    """The text without its comments and its string literals."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


def depth_before(lines: list[str]) -> list[int]:
    """The brace depth in front of every line.

    The depth is what says which type holds a declaration, and it is used
    instead of the indentation because a file is free to write its members
    without it — and one that does would hide every member of its type from
    a guard that reads the indentation.
    """
    depths: list[int] = []
    depth = 0
    for line in lines:
        depths.append(depth)
        bare = strip_comments(STRING_LITERAL.sub('""', line))
        depth += bare.count("{") - bare.count("}")
    return depths


# --- The model ------------------------------------------------------------------


class Function:
    """One declared function, with what it throws and what it calls."""

    def __init__(self, name: str, owner: str | None, path: str, line: int) -> None:
        self.name = name
        self.owner = owner
        self.path = path
        self.line = line
        self.direct: set[str] = set()
        self.calls: list[tuple[str | None, str]] = []
        self.locals: dict[str, str] = {}
        self.contract: set[str] = set()
        self.has_body = True


class Type:
    """One declared type, with the properties it hands to its members."""

    def __init__(self, name: str, path: str, line: int) -> None:
        self.name = name
        self.path = path
        self.line = line
        self.super: str | None = None
        self.properties: dict[str, str] = {}


def kdoc_blocks(lines: list[str]):
    """Every KDoc block of the file, with the index of its first and last line."""
    index = 0
    while index < len(lines):
        if KDOC_START.match(lines[index]):
            start = index
            body: list[str] = []
            index += 1
            while index < len(lines) and not KDOC_END.match(lines[index]):
                body.append(lines[index])
                index += 1
            yield start, index, body
        index += 1


def declaration_after(lines: list[str], index: int) -> tuple[int, str] | None:
    """The line that declares what a KDoc documents, and its kind.

    Annotations and comments may stand between the KDoc and the declaration.
    A declaration that the script cannot recognise is None: the script does
    not guess at what it cannot see.
    """
    cursor = index + 1
    while cursor < len(lines) and (
        not lines[cursor].strip()
        or lines[cursor].strip().startswith("@")
        or lines[cursor].strip().startswith("//")
    ):
        cursor += 1
    if cursor >= len(lines):
        return None
    if FUN_DECL.match(lines[cursor]):
        return cursor, "fun"
    if TYPE_DECL.match(lines[cursor]):
        return cursor, "type"
    return None


def body_of(lines: list[str], start: int) -> str:
    """The text of the block that opens at `start`, braces balanced."""
    depth = 0
    end = start
    for offset in range(start, len(lines)):
        depth += lines[offset].count("{") - lines[offset].count("}")
        if depth <= 0:
            end = offset
            break
    return "\n".join(lines[start:end + 1])


def body_after(lines: list[str], index: int) -> tuple[str, int] | None:
    """The block body of the declaration that starts at `index`.

    A declaration with an EXPRESSION body (`fun f() = ...`) has no block to
    read, and neither has an interface method. Both return None: the script
    does not guess at what a body it cannot see throws.
    """
    cursor = index
    while cursor < len(lines) and (
        not lines[cursor].strip()
        or lines[cursor].strip().startswith("@")
        or lines[cursor].strip().startswith("//")
    ):
        cursor += 1
    if cursor >= len(lines):
        return None
    parens = 0
    while cursor < len(lines):
        line = lines[cursor]
        for char in line:
            if char == "(":
                parens += 1
            elif char == ")":
                parens -= 1
        if parens == 0:
            stripped = line.strip()
            if stripped.endswith("=") and not stripped.endswith(("==", "!=", ">=", "<=", "=>")):
                return None
            if "{" in line:
                return body_of(lines, cursor), cursor
            if stripped and not stripped.startswith("@"):
                return None
        cursor += 1
        if cursor - index > 60:
            return None
    return None


def constructor_of(lines: list[str], index: int) -> str:
    """The primary constructor of the type declared at `index`, as text."""
    depth = 0
    seen = False
    text: list[str] = []
    for offset in range(index, min(index + 40, len(lines))):
        line = lines[offset]
        text.append(line)
        for char in line:
            if char == "(":
                depth += 1
                seen = True
            elif char == ")":
                depth -= 1
        if seen and depth <= 0:
            break
        if not seen and offset > index:
            break
    return "\n".join(text)


def enclosing_type(lines: list[str], depths: list[int], index: int) -> str | None:
    """The name of the type that holds the declaration at `index`."""
    mine = depths[index]
    for back in range(index - 1, -1, -1):
        match = TYPE_DECL.match(lines[back])
        if match and depths[back] < mine:
            return match.group("name")
    return None


def direct_thrown(body: str, known: set[str]) -> set[str]:
    """The types the body itself throws."""
    text = strip_comments(body)
    found: set[str] = set()
    for name in re.findall(r"throw\s+([A-Za-z_][A-Za-z0-9_.]*)", text):
        short = simple(name)
        if short in ("e", "it", "ex", "exception", "cause", "t", "error", "divergence"):
            continue
        if short in known:
            found.add(short)
    for call, kind in STANDARD_CALL.items():
        if re.search(r"(?<![\w.])" + call + r"\s*\(", text):
            found.add(kind)
    for caught in re.findall(r"catch\s*\(\s*\w+\s*:\s*([A-Za-z_][A-Za-z0-9_.]*)", text):
        name = simple(caught)
        if name not in DYNAMIC_CATCHES:
            found.add(name)
    return found


def calls_of(body: str) -> tuple[list[tuple[str | None, str]], dict[str, str]]:
    """The calls the body makes, and the locals it declares with a type."""
    text = strip_comments(body)
    locals_: dict[str, str] = {}
    for match in LOCAL_TYPED.finditer(text):
        locals_[match.group("name")] = simple(match.group("type"))
    calls: list[tuple[str | None, str]] = []
    for match in re.finditer(r"(?<![\w.])([A-Za-z_][A-Za-z0-9_]*)\s*\(", text):
        name = match.group(1)
        if name not in CALL_STOP_WORDS:
            calls.append((None, name))
    # A call on a receiver is a call whatever the name is: after a dot there is
    # no construct, so `store.get(id)` is a call and not an accessor.
    for match in re.finditer(
        r"(?P<recv>[A-Za-z_][A-Za-z0-9_]*)\s*[.]\s*(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*\(", text
    ):
        receiver = match.group("recv")
        calls.append(("this" if receiver == "this" else receiver, match.group("name")))
    # And the receiver can be a CONSTRUCTION: `Store(...).read()` names the
    # type of the thing that was built, and the call belongs to that type.
    for match in re.finditer(
        r"(?P<recv>[A-Za-z_][A-Za-z0-9_]*)\s*\((?:[^()]|\([^()]*\))*\)\s*[.]\s*"
        r"(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*\(",
        text,
    ):
        calls.append((match.group("recv"), match.group("name")))
    return calls, locals_


# --- The census -----------------------------------------------------------------


def product_files(root: str) -> list[str]:
    """Every Kotlin file of the PRODUCT."""
    base = os.path.join(root, "pipelinek")
    found: list[str] = []
    for directory, dirs, names in os.walk(base):
        dirs[:] = [d for d in dirs if d not in ("build", ".git", ".gradle")]
        if os.sep + "src" + os.sep + "main" + os.sep not in directory + os.sep:
            continue
        for name in names:
            if name.endswith(".kt"):
                found.append(os.path.join(directory, name))
    return sorted(found)


def parse(root: str):
    """The types, the functions and the promises of the product."""
    types: dict[str, Type] = {}
    functions: list[Function] = []
    promises: list[dict] = []

    for path in product_files(root):
        relative = os.path.relpath(path, root)
        with open(path, encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
        depths = depth_before(lines)

        # The types, with the properties they hand to their members.
        for index, line in enumerate(lines):
            match = TYPE_DECL.match(line)
            if match:
                name = match.group("name")
                if name == "Companion":
                    # A companion is not a type of its own: its members belong
                    # to the class that holds it, and they are reached through
                    # that class's name.
                    continue
                declared = types.setdefault(name, Type(name, relative, index + 1))
                for prop in CONSTRUCTOR_PROPERTY.finditer(constructor_of(lines, index)):
                    declared.properties[prop.group("name")] = simple(prop.group("type"))
                for sup in TYPE_SUPER.finditer(line):
                    declared.super = simple(sup.group("super"))
                    break
            alias = TYPEALIAS.match(line.strip())
            if alias:
                types.setdefault(alias.group(1), Type(alias.group(1), relative, index + 1))

        known = set(types) | set(JDK_SUPER)

        # The functions, with what they throw and what they call.
        for index, line in enumerate(lines):
            match = FUN_DECL.match(line)
            if not match:
                continue
            function = Function(
                match.group("name"),
                enclosing_type(lines, depths, index),
                relative,
                index + 1,
            )
            found = body_after(lines, index)
            if found is None:
                function.has_body = False
            else:
                body, _body_line = found
                function.direct = direct_thrown(body, known)
                function.calls, function.locals = calls_of(body)
            owner = types.get(function.owner or "")
            if owner:
                function.locals = {**owner.properties, **function.locals}
            functions.append(function)

        # The promises, each attached to what its KDoc documents.
        for start, end, block in kdoc_blocks(lines):
            text = "\n".join(block)
            promised = [simple(m.group(1)) for m in THROWS_TAG.finditer(text)]
            prose = [simple(m.group(1)) for m in PROSE_THROW.finditer(text)]
            if not promised and not prose:
                continue
            declared = declaration_after(lines, end)
            if declared is None:
                promises.append({
                    "path": relative,
                    "line": start + 1,
                    "promised": promised,
                    "prose": prose,
                    "target": None,
                    "kind": "unreadable",
                })
                continue
            line_index, kind = declared
            if kind == "fun":
                target = None
                for candidate in functions:
                    if candidate.path == relative and candidate.line == line_index + 1:
                        target = candidate
                        break
                promises.append({
                    "path": relative,
                    "line": start + 1,
                    "promised": promised,
                    "prose": prose,
                    "target": target,
                    "kind": "fun",
                })
            else:
                promises.append({
                    "path": relative,
                    "line": start + 1,
                    "promised": promised,
                    "prose": prose,
                    "target": None,
                    "kind": "type",
                    "type": TYPE_DECL.match(lines[line_index]).group("name"),
                })

    # A declaration without a body carries its promise to whoever calls it.
    for entry in promises:
        target = entry.get("target")
        if target is not None and not target.has_body:
            target.contract |= set(entry["promised"]) | set(entry["prose"])

    return types, functions, promises


def build_index(functions: list[Function]):
    """Where each function lives, by type, by file, and by name.

    The lookup is by NAME inside a scope, and not by name alone: a name with
    no scope is not a call, it is a coincidence.
    """
    by_owner_name: dict[tuple[str, str], list[int]] = defaultdict(list)
    by_file_name: dict[tuple[str, str], list[int]] = defaultdict(list)
    for position, function in enumerate(functions):
        if function.owner:
            by_owner_name[(function.owner, function.name)].append(position)
        by_file_name[(function.path, function.name)].append(position)
    return by_owner_name, by_file_name


def candidates_of(index, function, receiver, name, types):
    """The functions a call from `function` can reach."""
    by_owner_name, by_file_name = index
    if receiver is None:
        pool = list(by_owner_name.get((function.owner or "", name), []))
        pool += list(by_file_name.get((function.path, name), []))
        return pool
    if receiver == "this":
        return list(by_owner_name.get((function.owner or "", name), []))
    owner = function.locals.get(receiver) or (receiver if receiver in types else None)
    if owner is None or owner not in types:
        # A receiver this script cannot type is a receiver it cannot follow:
        # nothing is resolved.
        return []
    return list(by_owner_name.get((owner, name), []))


def resolve(functions: list[Function], types: dict[str, Type]):
    """What every function can throw, by fixpoint over the resolved calls."""
    index = build_index(functions)

    def candidates(receiver: str | None, name: str, function: Function) -> list[int]:
        return candidates_of(index, function, receiver, name, types)

    produced: dict[int, set[str]] = {
        position: set(function.direct) | set(function.contract)
        for position, function in enumerate(functions)
    }
    changed = True
    rounds = 0
    while changed and rounds < 60:
        changed = False
        rounds += 1
        for position, function in enumerate(functions):
            for receiver, name in function.calls:
                for callee in candidates(receiver, name, function):
                    extra = produced[callee] - produced[position]
                    if extra:
                        produced[position] |= extra
                        changed = True
    return produced


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    )
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    root = args.repo
    types, functions, promises = parse(root)
    produced = resolve(functions, types)
    index = build_index(functions)
    index_of = {id(function): position for position, function in enumerate(functions)}

    # The vocabulary: the types the PRODUCT raises by a construction the
    # script understands. A promise of a type OUTSIDE it names a class whose
    # producing site the script cannot see — an exception that comes out of
    # an external API — and the script does not call that a broken promise.
    vocabulary: set[str] = set(STANDARD_CALL.values())
    for function in functions:
        vocabulary |= {name for name in function.direct if name in types}

    hierarchy = dict(JDK_SUPER)
    for name, declared in types.items():
        if declared.super:
            hierarchy.setdefault(name, declared.super)

    def is_subtype(child: str, parent: str) -> bool:
        seen: set[str] = set()
        cursor: str | None = child
        while cursor and cursor not in seen:
            if cursor == parent:
                return True
            seen.add(cursor)
            cursor = hierarchy.get(cursor)
        return False

    kept = 0
    unchecked = 0
    unreadable = 0
    broken: list[dict] = []

    for entry in promises:
        if entry["kind"] == "unreadable":
            unreadable += 1
            continue
        if entry["kind"] == "fun":
            target = entry["target"]
            if target is None or not target.has_body:
                # A declaration without a body is a contract for the
                # implementations, and this script does not judge it.
                unreadable += 1
                continue
            reachable = set(target.direct)
            for receiver, name in target.calls:
                for callee in candidates_of(index, target, receiver, name, types):
                    reachable |= produced[callee]
        else:
            reachable = set()
            name = entry.get("type")
            for position, function in enumerate(functions):
                if function.owner == name:
                    reachable |= produced[position]
        entry["produced"] = sorted(reachable)
        for name in entry["promised"] + entry["prose"]:
            form = "@throws" if name in entry["promised"] else "prose"
            if any(is_subtype(other, name) for other in reachable):
                kept += 1
            elif name in vocabulary:
                broken.append({
                    "path": entry["path"],
                    "line": entry["line"],
                    "promised": name,
                    "produced": sorted(reachable),
                    "form": form,
                })
            else:
                unchecked += 1

    failures = [
        f"{entry['path']}:{entry['line']} promises {entry['promised']} "
        f"({entry['form']}) and this code cannot throw it: it throws "
        f"{entry['produced'] or 'nothing it can name'}"
        for entry in broken
    ]

    report = {
        "files": len(product_files(root)),
        "functions": len(functions),
        "promises_kept": kept,
        "promises_broken": len(broken),
        "promises_unchecked": unchecked,
        "promises_unreadable": unreadable,
        "failures": failures,
    }

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print("The exceptions the KDoc promises and the code does not throw")
        print(f"  files read:            {report['files']}")
        print(f"  functions read:        {report['functions']}")
        print(f"  promises kept:         {report['promises_kept']}")
        print(f"  promises broken:       {report['promises_broken']}")
        print(f"  promises not checked:  {report['promises_unchecked']}")
        print(f"  promises unreadable:   {report['promises_unreadable']}")
        if failures:
            print("RESULT: FAIL")
            for failure in failures:
                print(f"  - {failure}")
        else:
            print("RESULT: PASS")

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
