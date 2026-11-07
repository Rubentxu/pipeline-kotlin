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

WHAT ELSE COUNTS AS PROVENANCE
  The shapes above are the ones a comment can carry without saying anything
  useful. Five more were added once the tree was MEASURED instead of guessed
  at, and each one is a closed vocabulary with its exceptions written down:

  A working document that does not travel: the names this project's own
  governance carried -- the several `Constitution` documents, the Semantic
  Conservation Law, the Semantic Honesty Gate, the Runtime Observation
  Contract Closure. A reader of the tree cannot open any of them, so the rule
  they state has to be stated in the comment itself. Exceptions: none. The
  measured false positives over the tree are zero, so there is nothing to
  excuse and nothing to narrow.

  A plan coordinate in the `R<n>` alphabet: that is how the audit findings are
  numbered, and a reader cannot walk to them. ONE file is exempt: the
  git-transcript test numbers its own four rows `R1`..`R4`, and that
  vocabulary is the test's own, not the plan's.

  A reference to something outside the tree: an issue, a pull request, a merge
  request, a `gh-` number. The reader has no way to open it, so the fact has
  to be in the comment. A bare `#12` is NOT a reference: this tree numbers its
  own steps and cases that way, and those numbers are part of the contract.
  Exceptions: none; the measured false positives are zero.

  A narration of a past implementation: a comment that says "this used to be"
  AND names, in backticks, a symbol of this codebase the tree no longer
  contains. BOTH halves are required, and that is what makes the rule
  discriminate. The narration alone has no stable shape: the tree carries
  hundreds of past-tense sentences that are not provenance at all, and a rule
  that fired on those would flag ordinary prose. The dead name is the
  discriminator -- a reader who follows `SomeType` finds nothing there, so
  the comment points at nothing. A symbol of this codebase is a type-like name
  (an upper-case letter followed by a lower-case one) or a function-like name
  (a lower-case letter with a capital inside it, as in
  `emitStageFinished`); that leaves SCREAMING_CASE constants and ordinary
  words out of the rule. The marks are verbs that cannot also be present
  tense, so the sentence they sit in is about something that stopped being
  true.
  Exceptions: the names of the Gradle and Reactor vocabularies, which this
  tree's comments use but its code never declares. Their absence proves
  nothing, because the reader can open them in the library they belong to.
  The measured false positives over the tree are zero.

  An attribution to a person: an explicit credit (`thanks to X`, `authored by
  X`) or the first person in the past (`I decided`, `we chose`). A first
  person inside a quotation is a quoted sentence rather than an attribution,
  and a negated first person ("I did not touch them") is the subject of the
  code talking, not the author talking.
  Exceptions: none; the measured false positives are zero.

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
    r"|\bR\d{1,3}\b"
)

# The one file whose `R1`..`R4` are its own vocabulary and not plan
# coordinates: the git-transcript test names the four distinguishable ways its
# guarantee can break. Excused by name, with this reason.
PLAN_EXEMPT_FILES = ("GitTranscriptTest.kt",)

# The name of a working document that decides the work and does not travel with
# the tree: the several `Constitution` documents, the Semantic Conservation Law,
# the Semantic Honesty Gate and the Runtime Observation Contract Closure. A
# reader of the tree cannot open any of them, so the rule they state has to be
# stated in the comment itself. `semantic constitution` is matched in any case,
# because the tree writes it both as a title and inside running prose.
WORKING_DOCUMENT = re.compile(
    r"\bConstitution\b"
    r"|(?i:\bsemantic\s+constitution\b)"
    r"|\bSemantic\s+Conservation\s+Law\b"
    r"|\bSemantic\s+Honesty\s+Gate\b"
    r"|\bRuntime\s+Observation\s+Contract\s+Closure\b",
)

# A reference to something outside the tree: an issue, a pull request, a merge
# request, a `gh-` number. A bare `#12` is NOT one of these -- this tree
# numbers its own steps and cases that way, and those numbers are contract.
EXTERNAL_REFERENCE = re.compile(
    r"\b(?:pull[ -]request|merge[ -]request|issues?|PR|MR)\s*[#!]?\s*\d{1,6}\b"
    r"|\bgh-\d{1,6}\b"
    r"|\b(?:fixes|closes|resolves)\s+#\d{1,6}\b",
)

# The words that mark a narration of a past implementation. The mark alone is
# NOT the rule: the rule needs the mark AND a name that is gone.
#
# Every mark here is a verb in the past tense that CANNOT also be a present
# tense verb, so a sentence that carries one is a sentence about something
# that stopped being true. The vocabulary is closed on purpose, and two
# candidates were measured and REJECTED because they break that property:
# "read" is a present tense word as well ("pins the read to the plugin's
# directory") and "emitted" appears in the present passive ("is emitted as a
# public static final field"). The comparison ignores case, because a
# sentence may open with the verb.
NARRATION_MARK = re.compile(
    r"\bused to\b|\bno longer\b|\bformerly\b|\bpreviously\b|\bhistorically\b"
    r"|\bbefore the fix\b|\bRETIRED\b|\bwas removed\b|\bwere removed\b"
    r"|\bhas been removed\b|\bdefaulted\b|\bcomposed\b|\bcarried\b",
    re.IGNORECASE,
)

# A name written between backticks that names a member of a codebase: a
# type-like name (an upper-case letter followed by a lower-case one) or a
# function-like name (a lower-case letter with a capital inside it, as in
# `emitStageFinished`). SCREAMING_CASE constants and all-lower-case words
# stay out of the rule.
TYPE_NAME = re.compile(r"`([A-Z][a-z][A-Za-z0-9]*)`")
FUNCTION_NAME = re.compile(r"`([a-z][A-Za-z0-9]*[A-Z][A-Za-z0-9]*)`")

# The same shapes, matched in the CODE, to know which names still exist.
IDENTIFIER_IN_CODE = re.compile(r"\b[a-zA-Z][A-Za-z0-9]*\b")

# Names that come from OUTSIDE this tree. The tree's comments use them, the
# tree's code never declares them, and their absence therefore proves
# nothing: a reader CAN open `mustRunAfter` in the Gradle documentation.
# Each entry says where the name comes from, so that adding one is a
# statement about the world and not a way of hiding a finding.
FOREIGN_NAMES = {
    # Gradle's own task and configuration vocabulary.
    "failOnNoDiscoveredTests", "forkEveryPerClass", "skipWhenEmpty",
    "inputDependencies", "artifactId", "mustRunAfter",
    # Reactor's own publisher vocabulary.
    "ofSubscriber",
}

# An attribution to a person: an explicit credit, or the first person in the
# past. A quotation and a negation are excluded when scanning, not here.
PERSON_ATTRIBUTION = re.compile(
    r"\b(?:thanks to|thank you to|credits? to|co-authored by|authored by"
    r"|written by|reviewed by|suggested by|reported by)\s+[A-Z][a-z]+\b"
)
FIRST_PERSON_PAST = re.compile(
    r"\b(?:I|we)\s+(?:did|made|chose|wrote|built|added|removed|replaced|fixed"
    r"|introduced|decided|implemented|refactored)\b"
)
NEGATION = re.compile(r"\b(?:not|never)\b|n't\b", re.IGNORECASE)

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


def heredoc_delimiter(source: str, start: int):
    """The delimiter of the heredoc whose operator starts at `start`.

    `start` points at the first character after the `<<`. The delimiter may be
    quoted, and the word that follows the operator is the delimiter itself.
    Returns (delimiter, where the operator ends, whether tabs are stripped) or
    (None, start, False) when there is no delimiter to read.
    """
    i, n = start, len(source)
    while i < n and source[i] in " \t":
        i += 1
    strip_tabs = False
    if i < n and source[i] == "-":
        strip_tabs = True
        i += 1
    if i >= n:
        return None, start, False
    quote = source[i]
    if quote in ("'", '"', "`"):
        j = source.find(quote, i + 1)
        if j < 0:
            return None, start, False
        return source[i + 1:j], j + 1, strip_tabs
    j = i
    while j < n and source[j] not in " \t\n;|&<>()":
        j += 1
    if j == i:
        return None, start, False
    return source[i:j], j, strip_tabs


def skip_heredoc_body(source: str, start: int, delimiter: str, strip_tabs: bool):
    """Where the body of a heredoc ends, and whether it ended at all.

    The body runs from `start` to the line that names the delimiter, and the
    index returned is the one right after that line. A delimiter that never
    appears is a body that runs to the end of the file: the scan did not read
    the file, and the caller has to know it.
    """
    n = len(source)
    i = start
    while i < n:
        end = source.find("\n", i)
        if end < 0:
            end = n
        line = source[i:end]
        candidate = line.lstrip("\t") if strip_tabs else line
        if candidate == delimiter:
            return (end + 1 if end < n else n), True
        i = end + 1
    return n, False


def shell_scan(source: str):
    """Every shell comment in the source, and whether the scan read it whole.

    The second value is False when the scan ended inside a string, or inside a
    heredoc body that never closed. A scan that did not read the whole file
    cannot say what the rest of the file does not say, so the caller has to be
    able to see that it did not.

    The contexts are kept in a stack, because the quoting of shell nests: a
    double-quoted string may hold a command substitution, and inside that
    substitution the code is code again. A heredoc is a redirect of the CODE,
    so it is recognised there and nowhere else: `<<` inside a quoted string is
    text, and `<<<` is a herestring whose words are its body.

    The body of a heredoc is not code: it is not a string either, and the
    apostrophes in it open nothing. Read as code, one apostrophe opens a quote
    that never closes, and the scan goes on inside a string until the end of
    the file, where every comment is invisible. The body is therefore skipped
    whole, from the line after the redirect to the line that names the
    delimiter.

    Shell has no block comment, so `/*` here is a path glob and reading it as a
    comment swallows the rest of the file. Its comment character is `#`, and it
    has no line the original tokeniser could see at all. A `#` opens a comment
    only at the start of a word, which is what keeps `${#items}` and `foo#bar`
    out of it; `//` opens one when it is not preceded by a colon, which keeps
    `https://` out of it. Quote state is carried across lines, because a
    quoted string in shell can span them.
    """
    i, n, out = 0, len(source), []
    contexts = []
    line_start = True
    bodies = []

    def context():
        return contexts[-1] if contexts else None

    while i < n:
        ch = source[i]
        if ch == "\n":
            line_start = True
            i += 1
            for delimiter, strip_tabs in bodies:
                i, closed = skip_heredoc_body(source, i, delimiter, strip_tabs)
                if not closed:
                    return out, False
            bodies = []
            continue

        current = context()

        if current == "single":
            if ch == "'":
                contexts.pop()
            i += 1
            continue

        if current == "double":
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                contexts.pop()
                i += 1
                continue
            if source[i:i + 2] == "$(":
                contexts.append(("code", ")"))
                i += 2
                line_start = False
                continue
            if ch == "`":
                contexts.append(("code", "`"))
                i += 1
                line_start = False
                continue
            i += 1
            continue

        if current is not None and current[1] == "`" and ch == "`":
            contexts.pop()
            i += 1
            line_start = False
            continue

        if ch == "'":
            contexts.append("single")
            line_start = False
            i += 1
            continue
        if ch == '"':
            contexts.append("double")
            line_start = False
            i += 1
            continue
        if source[i:i + 2] == "$(":
            contexts.append(("code", ")"))
            line_start = False
            i += 2
            continue
        if ch == "`":
            contexts.append(("code", "`"))
            line_start = False
            i += 1
            continue
        if ch == "\\":
            i += 2
            continue
        if source[i:i + 3] == "<<<":
            i += 3
            line_start = False
            continue
        if source[i:i + 2] == "<<" and (i == 0 or source[i - 1] != "<"):
            delimiter, after, strip_tabs = heredoc_delimiter(source, i + 2)
            if delimiter is not None:
                bodies.append((delimiter, strip_tabs))
                line_start = False
                i = after
                continue
        if ch == ")" and current is not None and current[1] == ")":
            contexts.pop()
            i += 1
            line_start = False
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
    return out, not contexts


def looks_like_hash(token: str) -> bool:
    """A pasted commit hash carries a letter and a digit; `decades` does not."""
    return any(c in "abcdef" for c in token) and any(c.isdigit() for c in token)


def narrated_dead_names(block: str, index) -> list:
    """The names a narration points at and the tree no longer holds.

    Both halves are required: the sentence carries a narration mark AND a
    backticked name. A name the code still declares is not provenance -- the
    reader can open it. A name that comes from outside the tree is not
    provenance either -- the reader can open it in the library it belongs to.
    """
    dead = []
    for sentence in re.split(r"(?<=[.;:!?])\s+|\n", block):
        if not NARRATION_MARK.search(sentence):
            continue
        names = TYPE_NAME.findall(sentence) + FUNCTION_NAME.findall(sentence)
        for name in names:
            if name in index or name in FOREIGN_NAMES or name in dead:
                continue
            dead.append(name)
    return dead


def inside_a_quotation(block: str, at: int) -> bool:
    """True when the offset falls inside a double-quoted span of the block.

    A first person inside a quotation belongs to whoever is being quoted; it is
    not the author of the comment attributing the work to themselves.
    """
    return block.count('"', 0, at) % 2 == 1


def scan(rel: str, text: str, spans, index):
    found = []
    plan_exempt = rel.endswith(PLAN_EXEMPT_FILES)
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
        if not plan_exempt:
            plans = sorted({plan for plan in PLAN.findall(block)})
            if plans:
                reasons.append("plan " + ",".join(plans[:4]))
        working = sorted(set(WORKING_DOCUMENT.findall(block)))
        if working:
            reasons.append("documento-que-no-viaja " + ",".join(working[:4]))
        references = sorted({m.group(0) for m in EXTERNAL_REFERENCE.finditer(block)})
        if references:
            reasons.append("referencia-externa " + ",".join(references[:4]))
        dead = narrated_dead_names(block, index)
        if dead:
            reasons.append("narracion " + ",".join(dead[:4]))
        people = [m.group(0) for m in PERSON_ATTRIBUTION.finditer(block)]
        for match in FIRST_PERSON_PAST.finditer(block):
            if inside_a_quotation(block, match.start()):
                continue
            if NEGATION.match(block[match.end():].lstrip()):
                continue
            people.append(match.group(0))
        if people:
            reasons.append("persona " + ",".join(sorted(set(people))[:4]))
        if reasons:
            found.append((line_no, "; ".join(reasons), block.strip().splitlines()[0][:96]))
    return found


def tracked_files(repo: Path):
    listed = subprocess.run(
        ["git", "ls-files", *TRACKED], cwd=repo, capture_output=True, text=True, check=True
    ).stdout.split()
    return [rel for rel in listed if rel.endswith(SUFFIXES)]


def read_source(repo: Path, rel: str):
    path = repo / rel
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return None


def scan_source(rel: str, text: str):
    """Every comment in the file, and whether the read reached its end.

    The reach is part of the reading. A scan that stopped inside a string has
    not seen the rest of the file, and saying nothing about that rest is how a
    partial scan comes to look like a clean one.
    """
    if rel.endswith(".sh"):
        return shell_scan(text)
    return kotlin_comment_spans(text), True


def code_identifiers(text: str, spans) -> set:
    """The identifiers the CODE declares. The comments do not count.

    A name that only a comment mentions is not a name the tree holds, which is
    exactly what makes a narration that points at one a pointer to nothing.
    Every shape of identifier is collected, so that a type-like name and a
    function-like name are judged by the same evidence.
    """
    code = text
    for start, end in sorted(spans, reverse=True):
        code = code[:start] + " " * (end - start) + code[end:]
    return set(IDENTIFIER_IN_CODE.findall(code))


# ---------------------------------------------------------------------------
# THE GUARD'S OWN REACH
#
# A guard that watches part of a file and says nothing about the rest is a
# green over a partial scan, and a partial scan is indistinguishable from a
# clean one. Two checks live here, and both of them are about the guard
# itself.
#
# THE REACH. When the tokenising of a script ends inside a string, the guard
# does not pass in green: it names the file it could not read to the end, and
# it fails. A file the guard could not finish is not a file without findings:
# it is a file without a verdict.
#
# THE OWN PROBE. The guard carries a fixture in memory: a shell text whose
# heredoc holds an apostrophe, with a comment of provenance at the head and
# another at the tail. Its own rule has to find BOTH. The fixture is red with
# a tokeniser that reads a heredoc body as code -- the apostrophe opens a
# quote that never closes and the tail goes invisible -- and green with this
# one.

SELF_CHECK_SOURCE = """# WU-LPR-402
cat <<EOF
the operator's own note
EOF
# ADR-0103
"""

# The lines the probe expects to carry a finding: the head and the tail.
SELF_CHECK_LINES = (1, 5)


def self_check() -> list:
    """What the guard finds in the fixture it carries.

    The fixture is the defect itself, in miniature: a heredoc whose body holds
    an apostrophe, with a comment of provenance on each side of it. Read as
    code, the apostrophe opens a quote that never closes, and everything after
    it belongs to that quote: the tail comment is not a clean comment, it is
    an invisible one. The probe therefore asks for both lines, and a
    tokeniser that cannot see the tail fails here before it ever reaches the
    tree.
    """
    spans, complete = shell_scan(SELF_CHECK_SOURCE)
    problems = []
    if not complete:
        problems.append("el tokenizador no leyo la sonda hasta el final")
    index = code_identifiers(SELF_CHECK_SOURCE, spans)
    found = {line_no for line_no, _why, _snippet
             in scan("self-check.sh", SELF_CHECK_SOURCE, spans, index)}
    for line_no in SELF_CHECK_LINES:
        if line_no not in found:
            problems.append(f"la sonda no vio el comentario de la linea {line_no}")
    return problems


def main() -> int:
    repo = Path(__file__).resolve().parent.parent

    # The guard checks its own reach before it checks anybody else's code.
    problems = self_check()
    if problems:
        for problem in problems:
            print(f"  {problem}")
        print("\nLA GUARD NO LEE SU ALCANCE")
        print("Sin alcance, su veredicto no dice nada del fichero entero.")
        return 1

    files = tracked_files(repo)

    # First pass: which names does the code still hold?
    index = set()
    for rel in files:
        text = read_source(repo, rel)
        if text is None:
            continue
        spans, _complete = scan_source(rel, text)
        index |= code_identifiers(text, spans)

    # Second pass: what do the comments point at?
    offenders = []
    unread = []
    for rel in files:
        text = read_source(repo, rel)
        if text is None:
            continue
        spans, complete = scan_source(rel, text)
        if not complete:
            unread.append(rel)
        for line_no, why, snippet in scan(rel, text, spans, index):
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
    if unread:
        print("\nFICHEROS QUE LA GUARD NO LEYO HASTA EL FINAL:")
        for rel in unread:
            print(f"  {rel}")
        print("Un fichero a medias no es un fichero sin hallazgos: es un fichero sin ver.")
        return 1
    print("OK: ningun comentario nombra un encargo, un documento de trabajo, una etiqueta "
          "administrativa, un commit, una referencia externa, una persona ni una narracion del pasado")
    return 0


if __name__ == "__main__":
    sys.exit(main())