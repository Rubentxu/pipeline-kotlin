#!/usr/bin/env python3
"""Comments say what the code does; they do not say who asked for it.

A comment that opens with a ticket tag, or that points at a decision record by
its section number, or that pins a commit hash, tells a reader nothing that the
history of the repository cannot tell them better -- and the document it names
does not travel with the repository.

Ported from `origin/main`, where it was introduced in
`scripts/check-comment-provenance.py` (commit 759cf5384) and wired into the gate
in `f558bdc5d`. Four things changed on the way over, and each of them is a case
where the original reported a fact that was not true:

  1. The file list. The original asked for `git ls-files v2`. This tree lives in
     `pipelinek/`, so that command returned NOTHING and the guard printed its
     green over a scan that never happened. A guard that watches zero files is
     the most comfortable green there is.
  2. Shell comments were invisible. The original tokeniser knew `//` and `/* */`
     and nothing else, so every one of the ~650 `#` comments in this tree's shell
     scripts was unchecked -- including four that really did pin a commit hash.
  3. Shell `/*` is a glob, not a block comment. The original tokeniser read
     `for f in "$root"/last-run/*; do` as the start of a comment and swallowed
     everything up to the next `*/`, hundreds of lines later. The refusal it then
     printed named a line the reader could not find.
  4. `UP-TO-DATE` was reported as the ticket `UP-TO`. The rule stopped at the
     second hyphen and the exceptions list carried the full token, so the two
     could never meet.

WHAT COUNTS AS PROVENANCE
  A ticket: an uppercase tag joined to something that itself looks like an
  identifier, matched WHOLE so that `UP-TO-DATE` is one token and not two.
  `ADR-0103`, `WU-LPR-402`, `S2-A9`. Ordinary English does not match:
  `OS-level`, `URL-encoded` and `USER-class` continue in lower case, and the
  rule below says so on purpose.

  A section: `§5.3`, `§D2`.

  A commit: a bare hexadecimal word of seven characters or more, which in a
  comment is only ever a hash somebody pasted. It must carry both a letter and
  a digit: `decades` and `999999999` are words and a glob, not hashes.

  A management document: the name of a document that decides the work rather
  than describing the code. `ROADMAP` and `BACKLOG` are matched in any case,
  because no ordinary sentence in a source file uses those words, and glued to
  a neighbouring word (`..._ROADMAP`), because a glued name points at the same
  document.

  An administrative label: `ADR`, `SPEC`, `TRAIN` in capitals only, and only
  standing on their own. Lower case `spec` and lower case `release train` are
  ordinary English and appear in this tree as such, and `PIPELINEK_RELEASE_TRAIN`
  is a variable name rather than a pointer.

  A plan coordinate: the alphabet the plan itself uses, and nothing else.
  `H3` (a milestone), `a2` (a slice), `Slice 2`, `BLOCK 1`, `B.1` (an audit
  finding, also found glued to a word as in `auditB.3`). The vocabulary is
  closed on purpose: a rule shaped like "a letter next to a digit" also
  matches `v2`, which is this product's own version, and a rule that knew
  every token would be the plan wearing the guard's clothes.

WHAT IS DELIBERATELY NOT PROVENANCE
  Published vulnerability identifiers such as CVE-2023-32981. They name a real
  defect in the world and they are the reason the code they sit next to
  exists; removing them would remove the justification.

  Ordinary engineering tokens: SHA-256, UTF-8, HTTP-1.1, META-INF and the rest
  of the vocabulary below. Those describe the world, not the project.

  Directives. A line that starts with `//@` is read by the scenario launcher;
  it is configuration, not commentary, and this check never looks inside one.

WHY THE MANAGEMENT DOCUMENTS ARE A RULE AND NOT A MATTER OF TASTE
  The documents these rules name are not repository content: they live outside
  the Git index, so a reader of this tree cannot open them. A comment that
  points at one therefore points at nothing the reader has, and the fact the
  comment wanted to convey -- what the code does, and why -- has to be said in
  the comment itself. The same is true of a plan coordinate: it locates a fact
  in a document that does not travel, so the fact has to travel in the comment.

HOW THE COMMENTS ARE FOUND
  By tokenising the file for the language it is written in, not by guessing from
  the shape of a line: a `//` inside a string literal is a string, and a block
  of text inside a raw string is a payload. Reading comments out of the token
  stream is what keeps this check from failing on a shell script that happens to
  be embedded in Kotlin.

Exit status is 0 when nothing is found and 1 with a list when something is.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

# The shape of a project ticket: a short uppercase tag, a dash, and a
# continuation that begins like an identifier and runs to the end of the token.
# The continuation may contain hyphens so that `UP-TO-DATE` is matched whole and
# can be excused by name; `OS-level` still fails the lookahead, because after the
# dash it wants a capital or a digit.
TICKET = re.compile(r"\b[A-Z][A-Z0-9]{0,5}-(?=[A-Z0-9])[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\b")
SECTION = re.compile(r"§\s*[0-9][0-9A-Za-z.]*")
HASH = re.compile(r"(?<![A-Za-z0-9])[0-9a-f]{7,40}(?![A-Za-z0-9])")

# The name of a document that decides the work instead of describing the code.
# Matched in any case: no ordinary sentence in a source file says "roadmap".
# The name may be glued to a neighbouring word (`..._ROADMAP`), because a glued
# name points at the same document.
DOCUMENT = re.compile(r"[A-Za-z0-9_]*(?:ROADMAP|BACKLOG)\b", re.IGNORECASE)

# An administrative label. Capitals only, and NOT glued: lower case "spec" and
# lower case "release train" are ordinary English and occur in this tree as
# such, and `PIPELINEK_RELEASE_TRAIN` is a variable name, not a pointer.
LABEL = re.compile(r"\b(?:ADR|SPEC|TRAIN)\b")

# A coordinate of the plan. A closed vocabulary, not a shape: a shape like
# "a letter next to a digit" also matches `v2`, which is the product's own
# version token, and would fire on it 457 times.
#
# The audit-coordinate branch accepts a glued prefix (`auditB.3`) because the
# coordinate is the pointer and the prefix is only the word it was stuck to.
# The other branches do not: `a[0-9]` glued would also match `schema1`.
PLAN = re.compile(
    r"[A-Za-z0-9_]*[A-Z]\.[0-9]\b"
    r"|\bH[0-9]\b"
    r"|\ba[0-9]\b"
    r"|\b[Ss]lice\s+[0-9A-Z]\b"
    r"|\bBLOCK\s+[0-9]\b"
)

ALLOWED = {
    "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512",
    "UTF-8", "UTF-16", "UTF-32", "META-INF", "ISO-8601",
    "HTTP-1.0", "HTTP-1.1", "HTTP-2", "NOT_RUN",
    # crypto algorithm names, which are names and not tickets
    "AES-256", "AES-256-GCM", "AES-KWP", "SHA-3-256", "SHA-256s",
}

# Tokens whose `TAG-TOKEN` shape matches the rule but reads as ordinary
# English, as a protocol name, or as a literal value in this tree's own
# vocabulary. Listed explicitly so the regex stays permissive for real tickets
# (which carry a digit in the second part).
FALSE_POSITIVES = {
    "UP-TO-DATE", "NON-EMPTY", "BUILD-TIME", "READ-ONLY",
    "WRITE-ONLY", "SECONDS-LEFT", "AUTO-COMMIT", "AUTO-CONFIRM",
    "ZERO-LENGTH", "ZERO-DOWN", "USER-OWNED", "USER-CLASS",
    "OS-LEVEL", "URL-ENCODED", "ENV-LEVEL", "PATH-LIKE",
    "ITERATION-LEVEL", "RUN-TIME", "RUN-LEVEL",
    # the terms this codebase uses for its own behaviour
    "OPT-IN", "SIDE-EFFECT", "OFF-BY-ONE", "SHORT-CIRCUIT",
    "FAIL-CLOSED", "HALF-FINISHED", "NON-REPLAYABLE", "REPLAY-BREAKING",
    "EFFECT-AWARE", "SOURCE-TEXT", "SOURCE-ADDITIVE", "NO-SOURCE",
    "LENGTH-PREFIXED", "STORE-ASSIGNED",
    # literal values and names, not references to anything
    "SECRET-AB", "SECRET-ABCDEFGH", "X-Token", "A-Za-z0-9",
    # regex character classes that the TICKET rule picks up spuriously
    "A-Z0", "A-Z", "A-Z9", "Z0-9", "0-9",
}

EXEMPT_LINE = re.compile(r"^\s*//@")

# The tracked source trees. `scripts/` is here as well as the product: the gate
# and the launcher are source too, and a guard that stops at the edge of the
# product tree leaves the part of the repository a reviewer reads most unchecked.
TRACKED = ("pipelinek", "examples", "scripts")
SUFFIXES = (".kt", ".kts", ".sh")


def kotlin_comment_spans(source: str):
    """Yield (start, end) of every Kotlin comment, with string contents left alone."""
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


def shell_comment_spans(source: str):
    """Yield (start, end) of every shell comment.

    Shell has no block comment, so `/*` here is a path glob and reading it as a
    comment swallows the rest of the file. Its comment character is `#`, and it
    has no line the original tokeniser could see at all. Both are handled here.

    A `#` opens a comment only at the start of a word, which is what keeps
    `${#items}` and `foo#bar` out of it; `//` opens one when it is not preceded by
    a colon, which keeps `https://` out of it. Quote state is carried across
    lines, because a quoted string in shell can span them.
    """
    i, n, out = 0, len(source), []
    in_single = in_double = False
    line_start = True
    while i < n:
        ch = source[i]
        if ch == "\n":
            line_start = True
            i += 1
            continue
        if in_single:
            if ch == "'":
                in_single = False
            i += 1
            continue
        if in_double:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_double = False
            i += 1
            continue
        if ch == "'":
            in_single = True
            line_start = False
            i += 1
            continue
        if ch == '"':
            in_double = True
            line_start = False
            i += 1
            continue
        if ch == "\\":
            i += 2
            continue
        previous = source[i - 1] if i else " "
        if ch == "#" and (line_start or previous in " \t;&|()"):
            j = source.find("\n", i)
            j = n if j < 0 else j
            out.append((i, j))
            i = j
            continue
        if source[i:i + 2] == "//" and previous != ":" and source[i + 2:i + 3] != "/":
            j = source.find("\n", i)
            j = n if j < 0 else j
            out.append((i, j))
            i = j
            continue
        if not ch in " \t":
            line_start = False
        i += 1
    return out


def looks_like_hash(token: str) -> bool:
    """A pasted commit hash carries a letter and a digit; `decades` does not."""
    return any(c in "abcdef" for c in token) and any(c.isdigit() for c in token)


def scan(text: str, spans):
    found = []
    for start, end in spans:
        block = text[start:end]
        line_no = text.count("\n", 0, start) + 1
        reasons = []
        tickets = [t for t in TICKET.findall(block)
                   if t not in ALLOWED and t not in FALSE_POSITIVES
                   and not t.startswith("CVE-")]
        if tickets:
            reasons.append("ticket " + ",".join(sorted(set(tickets))[:4]))
        section = SECTION.search(block)
        if section:
            reasons.append("seccion " + section.group(0))
        commits = [h for h in HASH.findall(block) if looks_like_hash(h)]
        if commits:
            reasons.append("commit " + ",".join(sorted(set(commits))[:4]))
        documents = sorted({d.upper() for d in DOCUMENT.findall(block)})
        if documents:
            reasons.append("documento " + ",".join(documents[:4]))
        labels = sorted({label for label in LABEL.findall(block)})
        if labels:
            reasons.append("etiqueta " + ",".join(labels[:4]))
        plans = sorted({plan for plan in PLAN.findall(block)})
        if plans:
            reasons.append("plan " + ",".join(plans[:4]))
        if reasons:
            found.append((line_no, "; ".join(reasons), block.strip().splitlines()[0][:96]))
    return found


def tracked_files(repo: Path):
    listed = subprocess.run(
        ["git", "ls-files", *TRACKED], cwd=repo, capture_output=True, text=True, check=True
    ).stdout.split()
    return [rel for rel in listed if rel.endswith(SUFFIXES)]


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    offenders = []
    for rel in tracked_files(repo):
        path = repo / rel
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        spans = shell_comment_spans(text) if rel.endswith(".sh") else kotlin_comment_spans(text)
        for line_no, why, snippet in scan(text, spans):
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
    print("OK: ningun comentario nombra un encargo, un documento de trabajo, una etiqueta administrativa o un commit")
    return 0


if __name__ == "__main__":
    sys.exit(main())