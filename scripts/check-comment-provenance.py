#!/usr/bin/env python3
"""Comments say what the code does; they do not say who asked for it.

A comment that opens with a ticket tag, or that points at a decision record by
its section number, or that pins a commit hash, tells a reader nothing that the
history of the repository cannot tell them better — and the document it names
does not travel with the repository.

WHAT COUNTS AS PROVENANCE
  A ticket: an uppercase tag joined to something that itself looks like an
  identifier. `ADR-0103`, `WU-LPR-402`, `S2-A9`. Ordinary English does not
  match: `OS-level`, `URL-encoded` and `USER-class` continue in lower case, and
  the rule below says so on purpose.

  A section: `§5.3`, `§D2`.

  A commit: a bare hexadecimal word of seven characters or more, which in a
  comment is only ever a hash someone pasted.

WHAT IS DELIBERATELY NOT PROVENANCE
  Published vulnerability identifiers such as CVE-2023-32981. They name a real
  defect in the world and they are the reason the code they sit next to
  exists; removing them would remove the justification.

  Ordinary engineering tokens: SHA-256, UTF-8, HTTP-1.1, META-INF and the rest
  of the vocabulary below. Those describe the world, not the project.

  Directives. A line that starts with `//@` is read by the scenario launcher;
  it is configuration, not commentary, and this check never looks inside one.

HOW THE COMMENTS ARE FOUND
  By tokenising the file, not by guessing from the shape of a line: a `//`
  inside a string literal is a string, and a block of text inside a raw string
  is a payload. Reading comments out of the token stream is what keeps this
  check from failing on a shell script that happens to be embedded in Kotlin.

Exit status is 0 when nothing is found and 1 with a list when something is.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

# The shape of a project ticket: a short uppercase tag, a dash, and a
# continuation that begins like an identifier. `OS-level` fails on the last
# part and is left alone.
TICKET = re.compile(r"\b[A-Z][A-Z0-9]{0,5}-(?=[A-Z0-9])[A-Za-z0-9][A-Za-z0-9.]*\b")
SECTION = re.compile(r"§\s*[0-9][0-9A-Za-z.]*")
HASH = re.compile(r"(?<![A-Za-z0-9])[0-9a-f]{7,40}(?![A-Za-z0-9])")

ALLOWED = {
    "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512",
    "UTF-8", "UTF-16", "UTF-32", "META-INF", "ISO-8601",
    "HTTP-1.0", "HTTP-1.1", "HTTP-2", "NOT_RUN",
}

# Tokens whose `TAG-TOKEN` shape matches the rule but reads as ordinary
# English in the surrounding prose. Listed explicitly so the regex stays
# permissive for real tickets (which carry a digit in the second part).
FALSE_POSITIVES = {
    "UP-TO-DATE", "NON-EMPTY", "BUILD-TIME", "READ-ONLY",
    "WRITE-ONLY", "SECONDS-LEFT", "AUTO-COMMIT", "AUTO-CONFIRM",
    "ZERO-LENGTH", "ZERO-DOWN", "USER-OWNED", "USER-CLASS",
    "OS-LEVEL", "URL-ENCODED", "ENV-LEVEL", "PATH-LIKE",
    "ITERATION-LEVEL", "RUN-TIME", "RUN-LEVEL",
    # regex character classes that the TICKET rule picks up spuriously
    "A-Z0", "A-Z", "A-Z9", "Z0-9", "0-9",
}
EXEMPT_LINE = re.compile(r"^\s*//@")


def comment_spans(source: str):
    """Yield (start, end) of every comment, with string contents left alone."""
    i, n, out = 0, len(source), []
    while i < n:
        if source[i:i + 3] == '"""':
            j = source.find('"""', i + 3)
            i = n if j < 0 else j + 3
        elif source[i] == '"':
            j = i + 1
            while j < n and source[j] != '"':
                j += 2 if source[j] == "\\" else 1
            i = min(j + 1, n)
        elif source[i] == "'":
            j = i + 1
            while j < n and source[j] != "'":
                j += 2 if source[j] == "\\" else 1
            i = min(j + 1, n)
        elif source[i:i + 2] == "//":
            j = source.find("\n", i)
            j = n if j < 0 else j
            if not EXEMPT_LINE.match(source[i:j]):
                out.append((i, j))
            i = j
        elif source[i:i + 2] == "/*":
            depth, j = 1, i + 2
            while j < n and depth:
                if source[j:j + 2] == "/*":
                    depth, j = depth + 1, j + 2
                elif source[j:j + 2] == "*/":
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            out.append((i, j))
            i = j
        else:
            i += 1
    return out


def scan(path: Path, text: str):
    found = []
    for start, end in comment_spans(text):
        block = text[start:end]
        line_no = text.count("\n", 0, start) + 1
        reasons = []
        tickets = [t for t in TICKET.findall(block)
                   if t not in ALLOWED and t not in FALSE_POSITIVES
                   and not t.startswith("CVE-")]
        if tickets:
            reasons.append("ticket " + ",".join(sorted(set(tickets))[:4]))
        if SECTION.search(block):
            reasons.append("seccion " + SECTION.search(block).group(0))
        if HASH.search(block):
            reasons.append("commit " + HASH.search(block).group(0)[:12])
        if reasons:
            found.append((line_no, "; ".join(reasons), block.strip().splitlines()[0][:96]))
    return found


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    files = subprocess.run(
        ["git", "ls-files", "v2"], cwd=repo, capture_output=True, text=True, check=True
    ).stdout.split()
    total, offenders = 0, []
    for rel in files:
        if not rel.endswith((".kt", ".kts", ".sh")):
            continue
        path = repo / rel
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for line_no, why, snippet in scan(path, text):
            offenders.append((rel, line_no, why, snippet))
    total = len(offenders)
    for rel, line_no, why, snippet in offenders[:80]:
        print(f"  {rel}:{line_no}  {why}\n      {snippet}")
    if total > 80:
        print(f"  … y {total - 80} mas")
    if total:
        print(f"\nCOMENTARIOS CON PROCEDENCIA: {total}")
        print("Un comentario explica que hace el codigo. Quien lo pidio lo dice la historia.")
        return 1
    print("OK: ningun comentario nombra un encargo, una seccion de documento o un commit")
    return 0


if __name__ == "__main__":
    sys.exit(main())