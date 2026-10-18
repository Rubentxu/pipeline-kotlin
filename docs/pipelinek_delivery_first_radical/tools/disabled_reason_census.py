#!/usr/bin/env python3
"""H0.5 -- where does every `@Disabled` reason in v2/ actually point?

`disabled_equivalence_audit.py` answers a narrower question: among the population
`test_portfolio.py` labels `R0_PRUNE_DISABLED_METHODS`, does the successor a reason
NAMES exist and run. That population is 1 row today, so the audit is a correct
measurement of a nearly-empty set -- and it is the measurement the roadmap cited when
it recorded "47 of 51 claims of equivalence do not resolve to a live test".

That citation and this script disagree because they measured different populations.
This one measures all of them, and it classifies the *reason* rather than grepping
for the word `superseded`, because 55 rows use that word to narrate a historical
burn-down count ("the 4-id count is superseded by the 3-id count") and those are a
deliberate traceability archive, not a promise of coverage.

Four shapes, and they are what `AGENTS.md` sections 2/3/7 actually make possible:

    AnchoredToLocal    names an ADR or a file that exists  -- the sanctioned form
    ArchivalSnapshot   narrates a past count; promises nothing about a live test
    SupersededByTest   names a sibling test; that test must exist AND not be disabled
    FreeText           says why it cannot run and makes no substitution claim

Only `SupersededByTest` can fail: it is the one shape that makes a claim a later
reader could try to collect. Everything else is a statement about the past or about
the row itself.

READ-ONLY. Writes nothing inside the repository. Exit 0 means the census ran, NOT
that the corpus is clean; this is a report, not a gate.

    python3 docs/pipelinek_delivery_first_radical/tools/disabled_reason_census.py --repo .

Measuring whether a substitution is *equivalent* is a judgement and stays out of
here, exactly as in `disabled_equivalence_audit.py`. This only reports whether the
named thing is there.
"""
import argparse
import collections
import os
import re
import sys

SKIP = {'build', '.git', '.worktrees', 'buildSrc', 'dist', 'out'}

DECL = re.compile(
    r'(?m)^\s*(?:@\w+(?:\s*\([^)]*\))?\s*)*'
    r'(?:(?:internal|private|public|open|abstract|sealed|data)\s+)*'
    r'(?:class|object|fun)\s+(`[^`]+`|\w+)'
)
DISABLED_REASON = re.compile(r'@Disabled\s*\(\s*"((?:[^"\\]|\\.)*)"')

# `Historical S2-A10/G5 snapshot:` / `counters - post-S2-A9-G5 converged 4-4-4`
SNAPSHOT = re.compile(
    r'(?i)historical\s+[A-Z0-9][A-Z0-9./-]*\s+snapshot|\bpost-S2-[A-Z0-9-]*|\bcounters?\b'
)
# `superseded by CoreErrorRegistryPrimaryFitnessTest`, `replaced by X`, `covered by X`
SUPERSEDES = re.compile(
    r'(?i)\b(?:superseded|replaced|covered)\s+by\s+`?([A-Za-z_][\w.]{5,})`?'
)
# `core.sleep LEGACY_REMOVED at G5` -- claims the subject is GONE, not replaced
RETIRED = re.compile(r'(?i)\b(?:LEGACY_REMOVED|physically\s+(?:deleted|removed|destroyed))\b')
# `ADR-PK-QV-003`, `ADR-0070`
ADR = re.compile(r'\bADR(?:-[A-Z0-9]+)*-\d{3,}\b')
# `docs/v2/07-uat/disabled_uat_review_2026-10-17.md`
DOC = re.compile(r'(?:docs/|v2/)[\w./-]+\.(?:md|kt|kts)')


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), text, flags=re.S)
    return re.sub(r'//[^\n]*', '', text)


def classify(reason, declarations):
    """Return (shape, note). `declarations` maps a simple name -> disabled?."""
    if ADR.search(reason) or DOC.search(reason):
        named = sorted(set(ADR.findall(reason)) | set(DOC.findall(reason)))
        broken = [n for n in named
                  if n.endswith('.md') and not os.path.exists(n)]
        return 'AnchoredToLocal', ('BROKEN ' + ', '.join(broken)) if broken else ''
    if SNAPSHOT.search(reason):
        return 'ArchivalSnapshot', ''
    m = SUPERSEDES.search(reason)
    if m:
        name = m.group(1).split('.')[-1]
        if RETIRED.search(reason) and name not in declarations:
            # "superseded by core.sleep LEGACY_REMOVED" names a Step, not a test.
            return 'ArchivalSnapshot', 'retirado, no sustituido'
        state = declarations.get(name)
        if state is None:
            return 'SupersededByTest', f'AUSENTE {name}'
        if state:
            return 'SupersededByTest', f'DESABILITADO {name}'
        return 'SupersededByTest', ''
    return 'FreeText', ''


def walk(repo):
    for root, dirs, files in os.walk(os.path.join(repo, 'v2')):
        dirs[:] = [d for d in dirs if d not in SKIP]
        for name in sorted(files):
            if name.endswith(('.kt', '.kts')):
                yield os.path.join(root, name)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--repo', default='.', help='repository root (default: .)')
    args = ap.parse_args()

    paths = list(walk(args.repo))
    if not paths:
        print('no Kotlin sources found; refusing to report an empty census as a result',
              file=sys.stderr)
        return 2

    declarations = {}
    rows = []
    for path in paths:
        try:
            text = strip_comments(open(path, encoding='utf-8').read())
        except (OSError, UnicodeDecodeError):
            continue
        for m in DECL.finditer(text):
            declarations[m.group(1).strip('`')] = False
        for m in DISABLED_REASON.finditer(text):
            dm = DECL.search(text[m.end():m.end() + 400])
            rows.append((path, dm.group(1).strip('`') if dm else '<sin declaracion>',
                         m.group(1)))

    for path, who, _ in rows:
        declarations[who] = True

    shapes = collections.Counter()
    faults = []
    for path, who, reason in rows:
        shape, note = classify(reason, declarations)
        shapes[shape] += 1
        if shape == 'SupersededByTest' and note:
            faults.append((path, who, note, reason))

    total = len(rows)
    print(f'Kotlin sources scanned : {len(paths)}')
    print(f'@Disabled with a reason: {total}')
    print()
    for shape, count in shapes.most_common():
        print(f'  {shape:<20} {count:4}')

    if not rows:
        print('\nanti-vacuo FALLO: 0 filas medidas; la poblacion se movio o el parseo fallo')
        return 3

    print()
    if faults:
        print(f'{len(faults)} promesa(s) de cobertura que no se puede cobrar:')
        for path, who, note, reason in faults:
            print(f'  [{note}] {os.path.basename(path)}::{who}')
            print(f'      {reason[:140]}')
    else:
        print('0 promesas de cobertura sin sucesor vivo: '
              'toda fila que nombra un test sucesor nombra uno que existe y corre.')
    return 0


if __name__ == '__main__':
    sys.exit(main())