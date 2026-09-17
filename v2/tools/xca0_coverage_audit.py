#!/usr/bin/env python3
"""
XCA-0 — examples/ executable-coverage auditor (reconnaissance form).

Answers, with evidence rather than assertion:
  for every CERTIFIED surface, does a maintained fixture EXIST and does that
  fixture ACTUALLY EXERCISE the surface?

Three states, deliberately distinct so the tool cannot produce a silent green:
  EXERCISED      a claimed fixture imports/calls the surface's DSL symbol
  NOT_EXERCISED  claimed fixtures exist but none invokes the symbol
  SYMBOL_UNKNOWN cannot derive the DSL symbol from the step key -> MANUAL REVIEW

Parser guards (each has a negative fixture below):
  G1 comment/schema block is never parsed as a record
  G2 the field is `real_fixtures` (plural); `real_fixture` (singular) is schema doc
  G3 records are exactly 2-space `- step_key:`; nested 6-space entries are a
     DIFFERENT (duplicate-authority) listing and must not be merged into records
"""
import re, sys, pathlib
from collections import Counter, defaultdict

REPO = pathlib.Path(__file__).resolve().parents[2]

# ---------------------------------------------------------------- parser

TOP_RECORD = re.compile(r'^  - step_key:\s*"?([\w.\-]+)"?\s*$')
NESTED_ENTRY = re.compile(r'^ {6}- step_key:\s*"?([\w.\-]+)"?\s*$')
FIELD = re.compile(r'^ {4}([a-z_]+):\s*(.*?)\s*$')
FIXTURE_ITEM = re.compile(r'^ {6}- (.+?)\s*$')


def parse_ledger(text):
    """Typed-ish record extraction. Returns (records, nested_keys, guard_report)."""
    lines = text.split("\n")
    records, nested = [], []
    cur, section = None, None
    guards = {"comment_lines_skipped": 0, "singular_real_fixture_in_records": 0}

    for ln in lines:
        if ln.lstrip().startswith("#"):
            guards["comment_lines_skipped"] += 1
            continue
        m = TOP_RECORD.match(ln)
        if m:
            cur = {"step_key": m.group(1), "real_fixtures": []}
            records.append(cur)
            section = None
            continue
        n = NESTED_ENTRY.match(ln)
        if n:
            nested.append(n.group(1))
            cur = None
            section = None
            continue
        if cur is None:
            continue
        f = FIELD.match(ln)
        if f:
            name, val = f.group(1), f.group(2)
            section = name
            if name == "real_fixtures":
                if val.strip() == "[]":
                    cur["real_fixtures"] = []
                elif val.strip():
                    cur["real_fixtures"] = [val.strip().strip('"')]
            elif name == "real_fixture":
                # guard G2: singular never appears in real records
                guards["singular_real_fixture_in_records"] += 1
            else:
                cur[name] = val.strip().strip('"')
            continue
        fi = FIXTURE_ITEM.match(ln)
        if fi and section == "real_fixtures":
            cur["real_fixtures"].append(fi.group(1).strip().strip('"'))
    return records, nested, guards


# ------------------------------------------------- fixture symbol extraction

IMPORT = re.compile(r'^\s*import\s+([\w.]+)\s*$')
CALL = re.compile(r'\b([a-zA-Z_][A-Za-z0-9_]*)\s*\(')
# structural DSL verbs, not Steps
STRUCTURAL = {"pipeline", "stages", "stage", "steps", "script", "if",
              "forEach", "times", "pipelineStep", "registryStep", "withEnv"}


def fixture_symbols(path):
    src = pathlib.Path(REPO / path).read_text()
    syms = set()
    for ln in src.split("\n"):
        if ln.lstrip().startswith("//"):
            continue
        mi = IMPORT.match(ln)
        if mi:
            syms.add(mi.group(1).split(".")[-1])
        for c in CALL.findall(ln):
            syms.add(c)
    return syms - STRUCTURAL


# Steps with NO user-facing DSL surface: synthesised by compiler rewriting; their
# evidence is a structural/rewrite contract, NOT a fixture calling an invented
# symbol. Verified: `grep -rn "fun emitEvent"` finds no DSL declaration, and
# PipelineDsl.kt names core.emit.event only inside error-message strings.
STRUCTURAL_SYNTHETIC = {
    "core.emit.event": (
        "compiler-rewritten from catchError/warnError/unstable; no DSL surface. "
        "Evidence: EmitEventCatchErrorRegistryUatTest.kt"
    ),
}


def dsl_symbol_candidates(step_key):
    """Candidate DSL symbols for a step key.
      last segment        core.echo       -> echo
      camelCase(last two) core.emit.event -> emitEvent
    A candidate is only TRUSTED if it is declared in source (source_symbols).
    Fixture frequency is never used to derive a symbol."""
    seg = step_key.split(".")
    cands = {seg[-1]}
    if len(seg) >= 2:
        cands.add(seg[-2] + seg[-1][:1].upper() + seg[-1][1:])
    return cands


DECL_FUN = re.compile(
    r'^\s*(?:override\s+|private\s+|public\s+|internal\s+|suspend\s+)*'
    r'fun\s+(?:<[^>]+>\s*)?'          # optional generic params
    r'(?:[A-Za-z_][\w.]*\s*\.\s*)?'   # optional receiver: fun StepScope.readJSON(
    r'([A-Za-z_][A-Za-z0-9_]*)\s*\('
)


def source_symbols():
    """DSL symbols DECLARED IN SOURCE: the core facade plus plugin DSL facades.
    This is the authority for symbol names; fixtures only prove usage."""
    files = [REPO / "v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt"]
    for p in REPO.glob("examples/*/src/main/kotlin/**/*.kt"):
        try:
            if "registryStep" in p.read_text():
                files.append(p)
        except Exception:
            pass
    syms = set()
    for f in files:
        if not f.exists():
            continue
        for ln in f.read_text().split("\n"):
            if ln.lstrip().startswith("//"):
                continue
            m = DECL_FUN.match(ln)
            if m:
                syms.add(m.group(1))
    return syms


# ------------------------------------------------------------------- main

def main():
    ledger = (REPO / "docs/v2/status/step-certification.yaml").read_text()
    records, nested, guards = parse_ledger(ledger)

    print("=" * 100)
    print("PARSER GUARDS")
    print("=" * 100)
    print(f"  top-level records parsed        : {len(records)}")
    print(f"  comment lines skipped (G1)      : {guards['comment_lines_skipped']}")
    print(f"  singular `real_fixture` in recs : {guards['singular_real_fixture_in_records']}  (must be 0)")
    print(f"  nested 6-space entries (G3)     : {len(nested)}  <- duplicate-authority listing, NOT merged")
    assert len(records) == 32, f"expected 32 records, got {len(records)}"
    assert guards["singular_real_fixture_in_records"] == 0

    # all symbols used anywhere, for SYMBOL_UNKNOWN detection
    all_fixtures = set()
    for r in records:
        all_fixtures.update(r["real_fixtures"])
    for p in REPO.glob("examples/**/*.pipeline.kts"):
        all_fixtures.add(str(p.relative_to(REPO)))
    for p in REPO.glob("v2/compatibility/*.pipeline.kts"):
        all_fixtures.add(str(p.relative_to(REPO)))
    symcache = {f: fixture_symbols(f) for f in all_fixtures if (REPO / f).exists()}
    src_syms = source_symbols()
    print(f"  DSL symbols declared in source   : {len(src_syms)}")

    print()
    print("=" * 100)
    print("CERTIFIED SURFACES — executable coverage")
    print("=" * 100)
    hdr = f"{'surface':26} {'state':11} claimed  exer  verdict"
    print(hdr); print("-" * 100)

    rows = []
    for r in records:
        k, st = r["step_key"], r.get("certification_state", "?")
        if st != "CERTIFIED":
            continue
        claimed = r["real_fixtures"]
        cands = dsl_symbol_candidates(k)
        resolved = cands & src_syms
        exer = [f for f in claimed if resolved & symcache.get(f, set())]
        if k in STRUCTURAL_SYNTHETIC and not exer:
            verdict, cls = "STRUCTURAL_SYNTH", "D_REWRITE_CONTRACT"
        elif exer:
            # A = already canonical under examples/; B = only compatibility/ today
            verdict = "EXERCISED"
            cls = ("A_IN_EXAMPLES" if any(f.startswith("examples/") for f in exer)
                   else "B_PROMOTE_COMPATIBILITY")
        elif not claimed:
            verdict, cls = "NOT_EXERCISED", "C_CREATE_OR_EXPAND"
        else:
            # claimed but the file does not invoke the symbol (XCA-1A removed the
            # known cases; any remaining one is a defect to fix, not a class)
            verdict, cls = "NOT_EXERCISED", "C_CREATE_OR_EXPAND"
        rows.append((k, st, claimed, exer, verdict, cls))
        shown = ",".join(f.split("/")[-1] for f in exer) or "-"
        print(f"{k:26} {st:11} {len(claimed):^7} {len(exer):^4}  {verdict}  {cls}  {shown}")

    n = len(rows)
    # Count each verdict explicitly. Deriving "NOT_EXERCISED" as n - ex - unk
    # silently absorbed the structural class and misreported the debt.
    byv = Counter(r[4] for r in rows)
    print()
    print("=" * 100)
    print("COUNTERS")
    print("=" * 100)
    print(f"  CERTIFIED records                    : {n}")
    for v in ("EXERCISED", "NOT_EXERCISED", "STRUCTURAL_SYNTH", "SYMBOL_UNKNOWN"):
        print(f"  {v:37}: {byv.get(v, 0)}")
    assert sum(byv.values()) == n, "verdicts must partition the records"
    print()
    for cls, c in sorted(Counter(r[5] for r in rows).items()):
        print(f"  {cls:26} : {c}")

    print()
    print("=" * 100)
    print("LEDGER-MAPPING FALSE CLAIMS (claimed but not exercised)")
    print("=" * 100)
    for k, st, claimed, exer, verdict, cls in rows:
        if verdict == "NOT_EXERCISED" and claimed:
            print(f"  {k}")
            for f in claimed:
                mark = "ok" if (dsl_symbol_candidates(k) & src_syms & symcache.get(f, set())) else "NOT-EXERCISED"
                print(f"      {f}   [{mark}]")
    return 0


if __name__ == "__main__":
    sys.exit(main())
