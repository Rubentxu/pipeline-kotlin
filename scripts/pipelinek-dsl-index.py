#!/usr/bin/env python3
"""Offline, source-derived PipelineK Kotlin DSL signature index.

This index is for discovery, not Kotlin compilation or runtime admission.
A runtime key may map to zero or multiple Kotlin functions.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path("pipelinek/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl")
SOURCES = [
    "PipelineDsl.kt", "StageScope.kt", "StageScopeBuilders.kt",
    "StepBodyScope.kt", "StepBodyScopeOperations.kt", "StepBodyScopeWrappers.kt",
    "StepBodyScopeExtensions.kt", "WhenGateDsl.kt", "StepEmitter.kt", "StepSpec.kt",
]
FUNCTION = re.compile(r"(?m)^[ \t]*(?:(?:override|public|inline|suspend|open|final)\s+)*fun\s+((?:\w+\.)?\w+)\s*\(")
PRIVATE = re.compile(r"(?m)^[ \t]*(?:(?:override|inline|suspend)\s+)*(?:private|internal|protected)\s+fun\s+")
OWNER = re.compile(r"(?m)^[ \t]*(?:(?:open|abstract|internal|public|sealed|data)\s+)*(?:class|interface|object)\s+(\w+)")


def strip_comments_and_strings(source):
    """Blank Kotlin comments/string contents, retaining offsets."""
    chars = list(source)
    state = "code"
    i = 0
    while i < len(source):
        chunk = source[i:i+3]
        two = source[i:i+2]
        char = source[i]
        if state == "code":
            if two == "//":
                state = "line"
            elif two == "/*":
                state = "block"
            elif chunk == '"""':
                chars[i:i+3] = [" ", " ", " "]
                i += 3
                state = "triple"
                continue
            elif char == '"':
                chars[i] = " "
                i += 1
                state = "string"
                continue
            elif char == "'":
                chars[i] = " "
                i += 1
                state = "character"
                continue
            else:
                i += 1
                continue
        if state == "line" and char == "\n":
            state = "code"
            i += 1
            continue
        if state == "block" and two == "*/":
            chars[i:i+2] = [" ", " "]
            i += 2
            state = "code"
            continue
        if state == "triple" and chunk == '"""':
            chars[i:i+3] = [" ", " ", " "]
            i += 3
            state = "code"
            continue
        if state in ("string", "character") and char == "\\":
            chars[i:i+2] = [" ", " "]
            i += 2
            continue
        if state == "string" and char == '"':
            state = "code"
        elif state == "character" and char == "'":
            state = "code"
        if char != "\n":
            chars[i] = " "
        i += 1
    return "".join(chars)


def match_closer(clean, start, opening="(", closing=")"):
    depth = 0
    for offset in range(start, len(clean)):
        if clean[offset] == opening:
            depth += 1
        elif clean[offset] == closing:
            depth -= 1
            if depth == 0:
                return offset
    raise ValueError("unbalanced Kotlin signature or block")


def extract(file, root):
    source = file.read_text(encoding="utf-8")
    clean = strip_comments_and_strings(source)
    contexts = []
    for obj in OWNER.finditer(clean):
        opening = clean.find("{", obj.end())
        if opening < 0:
            continue
        try:
            closing = match_closer(clean, opening, "{", "}")
            contexts.append((opening, closing, obj.group(1)))
        except ValueError:
            continue
    rows = []
    for fn in FUNCTION.finditer(clean):
        line_start = clean.rfind("\n", 0, fn.start()) + 1
        raw_prefix = source[line_start:fn.start()]
        if PRIVATE.search(raw_prefix):
            continue
        begin = clean.find("(", fn.start())
        end = match_closer(clean, begin)
        raw_params = " ".join(source[begin+1:end].strip().split())
        qualified = fn.group(1)
        available = [c for c in contexts if c[0] < fn.start() < c[1]]
        owner = max(available, key=lambda c: c[0])[2] if available else "top-level"
        receiver, _, function_name = qualified.rpartition(".")
        receiver = receiver or owner
        function_name = function_name or qualified
        signature = "fun " + qualified + "(" + raw_params + ")"
        rows.append({
            "id": receiver + "." + function_name + "-" + hashlib.sha256(signature.encode()).hexdigest()[:12],
            "name": function_name,
            "receiver": receiver,
            "signature": signature,
            "owner": owner,
            "source": str(file.relative_to(root)),
            "line": source.count("\n", 0, fn.start()) + 1,
            "runtimeKeys": [],
            "runtimeMapping": "unresolved",
        })
    return rows


def generate(root, catalog=None):
    paths = [root / ROOT / name for name in SOURCES]
    missing = [str(p) for p in paths if not p.is_file()]
    if missing:
        raise ValueError("missing Kotlin DSL source: " + ", ".join(missing))
    rows = [row for path in paths for row in extract(path, root)]
    if not rows:
        raise ValueError("no DSL functions discovered")
    if catalog is not None:
        if not isinstance(catalog.get("entries"), list):
            raise ValueError("invalid PipelineK runtime steps catalog")
        by_name = {}
        for item in catalog["entries"]:
            if isinstance(item, dict) and item.get("name") and item.get("key"):
                by_name.setdefault(item["name"], set()).add(item["key"])
        for row in rows:
            row["runtimeKeys"] = sorted(by_name.get(row["name"], set()))
            row["runtimeMapping"] = "name-candidates-only" if row["runtimeKeys"] else "unmapped"
    return {
        "schema": "pipelinek.dsl-signatures/v1",
        "completeness": "source-derived discovery; validate --compile-only and runtime registry are authoritative",
        "entries": sorted(rows, key=lambda x: (x["name"], x["receiver"], x["source"], x["line"])),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--runtime-json", type=Path)
    parser.add_argument("--name")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    catalog = json.loads(args.runtime_json.read_text(encoding="utf-8")) if args.runtime_json else None
    result = generate(args.root.resolve(), catalog)
    if args.name:
        result["entries"] = [row for row in result["entries"] if row["name"] == args.name]
    result_text = json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(result_text, encoding="utf-8")
    else:
        print(result_text, end="")


if __name__ == "__main__":
    main()
