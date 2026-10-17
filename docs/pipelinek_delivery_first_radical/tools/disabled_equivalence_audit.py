#!/usr/bin/env python3
"""H0.2 -- does a `@Disabled` that claims to be superseded name a LIVE successor?

`test_portfolio.py` classifies a file as `R0_PRUNE_DISABLED_METHODS` from the words
`historical|superseded|snapshot` appearing in its `@Disabled` reason. Those words are
exactly the vocabulary a DELIBERATE traceability archive uses -- the burn-down snapshots
in `CoreDeleteDirStepUnitTest` and friends each say "Preserved verbatim for
traceability". So the classifier can authorise deleting records the authors chose to
keep, on the strength of the same sentence that says why they are kept.

This script measures the thing the classification assumes, instead of assuming it.
For every `@Disabled` that says "superseded by X", it resolves X against every
declaration in the tree and reports whether X is live, itself disabled (a chain inside
the same archive), or absent entirely.

READ-ONLY. Writes nothing inside the repository. Exit status is 0 on completion: this is
a report, not a gate. A non-zero exit means the audit did not run.

    python3 tools/disabled_equivalence_audit.py --repo . --portfolio /tmp/portfolio.tsv

Measuring "equivalent coverage" is a judgement; this script only measures whether the
successor a reason NAMES exists and runs. Equivalence itself still needs per-test consent.
"""
import argparse, collections, os, re, sys

DECL = re.compile(
    r'(?m)^\s*(?:@\w+(?:\s*\([^)]*\))?\s*)*'
    r'(?:(?:internal|private|public|open|abstract|data|sealed)\s+)*'
    r'(?:class|object|fun)\s+(`[^`]+`|\w+)'
)
DISABLED = re.compile(r'@Disabled\s*\(')
SUPERSEDED = re.compile(r'superseded by\s+(?:`([^`]+)`|([A-Za-z][\w.]*\(\)?))', re.I)
TRACEABILITY = re.compile(
    r'preserved verbatim|verbatim for traceability|for traceability', re.I,
)
SKIP = ('/build/', '/.git/', '/.worktrees/')


def strip_comments(text):
    """Blank out comments, preserving newlines so line numbers stay meaningful."""
    text = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), text, flags=re.S)
    return re.sub(r'//[^\n]*', '', text)


def normalise(name):
    return re.sub(r'[^a-z0-9]+', '', name.lower())


def kotlin_files(root):
    for dirpath, dirnames, filenames in os.walk(root):
        normalised = dirpath.replace(os.sep, '/')
        if any(marker in normalised for marker in SKIP):
            dirnames[:] = []
            continue
        for name in filenames:
            if name.endswith('.kt'):
                yield os.path.join(dirpath, name)


def index_declarations(root):
    """Map every declaration name to (is_disabled, [paths])."""
    live, disabled = {}, {}
    for path in kotlin_files(root):
        code = strip_comments(open(path, encoding='utf-8', errors='replace').read())
        spans, at = [], 0
        while True:
            marker = DISABLED.search(code, at)
            if marker is None:
                break
            following = re.search(r'\b(fun|class|object)\b', code[marker.end():])
            end = (marker.end() + following.start()) if following \
                else min(len(code), marker.end() + 400)
            spans.append((marker.start(), end))
            at = marker.end()
        for declaration in DECL.finditer(code):
            key = normalise(declaration.group(1).strip('`'))
            inside = any(a <= declaration.start() < b for a, b in spans)
            (disabled if inside else live).setdefault(key, []).append(path)
    return live, disabled


def reason_of(lines, start):
    """The @Disabled reason, accumulating a concatenation that spans lines."""
    chunk, index = lines[start], start
    while '(' in chunk.split('@Disabled', 1)[-1] and ')' not in chunk and index + 1 < len(lines):
        index += 1
        chunk += ' ' + lines[index].strip()
    return chunk, index


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True)
    parser.add_argument('--portfolio', required=True,
                        help='TSV from test_portfolio.py (status/path/reason columns)')
    args = parser.parse_args()

    live, disabled = index_declarations(args.repo)

    targets = [line.split('\t')[1].strip()
               for line in open(args.portfolio, encoding='utf-8')
               if line.startswith('R0_PRUNE_DISABLED_METHODS\t')]

    tally = collections.Counter()
    findings = []
    for relative in targets:
        code = strip_comments(
            open(os.path.join(args.repo, relative), encoding='utf-8', errors='replace').read()
        )
        lines, index = code.split('\n'), 0
        while index < len(lines):
            if '@Disabled' not in lines[index]:
                index += 1
                continue
            chunk, end = reason_of(lines, index)
            following = lines[end + 1] if end + 1 < len(lines) else ''
            match = SUPERSEDED.search(chunk)
            status, named = 'no-successor', None
            if match:
                named = (match.group(1) or match.group(2)).strip()
                # The gate marker `(... G6)` is sometimes part of the method NAME, so
                # every plausible reading is tried before declaring it absent --
                # otherwise this audit manufactures false "missing" claims.
                candidates = {
                    normalise(named),
                    normalise(named.split(' (')[0].strip('`')),
                    normalise(named.split(' (')[0].strip('`').rstrip('()')),
                }
                if any(c in live for c in candidates):
                    status = 'live'
                elif any(c in disabled for c in candidates):
                    status = 'disabled-successor'
                else:
                    status = 'missing-successor'
            traceable = bool(TRACEABILITY.search(chunk))
            tally[(status, traceable)] += 1
            findings.append((relative, index + 1, status, traceable, named,
                             bool(re.search(r'\bclass\b', following))))
            index = end + 1

    total = sum(tally.values())
    traceable_total = sum(n for (_, traceable), n in tally.items() if traceable)
    print(f'@Disabled audited in the R0 population: {total}')
    print(f'  declaring an explicit preservation-for-traceability intent: {traceable_total}')
    print()
    print(f'{"successor status":22} {"declares traceability":22} count')
    for (status, traceable), n in sorted(tally.items()):
        print(f'  {status:20} {str(traceable):22} {n}')
    print()
    for relative, line, status, traceable, named, is_class in findings:
        if status != 'live':
            print(f'  [{status:20}] {os.path.basename(relative)}:{line}'
                  f'{"  (class)" if is_class else ""} names -> {(named or "(none)")[:78]}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
