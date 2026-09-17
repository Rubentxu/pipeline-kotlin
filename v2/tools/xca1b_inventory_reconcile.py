#!/usr/bin/env python3
"""XCA-1B — runtime discovery vs certification ledger reconciliation.

Principle: do not use the ledger to discover what the ledger should contain.

Two independent authorities are compared:
  DISCOVERY      runtime production StepKeys (PluginStepId declarations in src/main)
  CERTIFICATION  docs/v2/status/step-certification.yaml

A runtime key absent from the ledger is only acceptable if it carries an EXPLICIT
typed exception with a stated reason. There is no heuristic fallback and no magic
list of "expected" keys: anything not in EXCEPTIONS below is a VIOLATION and is
printed as such. Adding an entry is a deliberate, reviewable act.

Exception classes
  BLOCK_ORCHESTRATION  control-flow Step entered via BodyInvoker/BranchInvoker;
                       it has no user-facing atomic DSL surface to certify as a
                       REGISTRY_STEP. Needs the ORCHESTRATION evidence adapter.
  STRUCTURAL_SYNTH     synthesised by compiler rewriting (e.g. catchError ->
                       core.emit.event + core.sh).
  CONTROL_NODE         durable control row / internal node, not a user Step.
  TEMPLATE             not a real key (source-level dynamic construction).
"""
import re, sys, pathlib
from collections import Counter

REPO = pathlib.Path(__file__).resolve().parents[2]
LEDGER = REPO / "docs/v2/status/step-certification.yaml"

# ---------------------------------------------------------------- discovery


def runtime_keys():
    """Production StepKeys only: src/main source sets, never tests or build output."""
    pats = ["examples/*/src/main/kotlin", "v2/*/src/main/kotlin"]
    keys = set()
    for pat in pats:
        for p in REPO.glob(pat):
            for f in p.rglob("*.kt"):
                txt = f.read_text(errors="ignore")
                for m in re.finditer(r'PluginStepId\("([^"]+)"', txt):
                    keys.add(m.group(1))
    return keys


# ----------------------------------------------------- typed exceptions

EXCEPTIONS = {
    # key -> (class, reason)
    "core.catchError": ("BLOCK_ORCHESTRATION",
                        "control-flow; rewrites to core.emit.event + core.sh"),
    "core.warnError": ("BLOCK_ORCHESTRATION",
                       "control-flow; rewrites to core.emit.event + core.sh"),
    "core.dir": ("BLOCK_ORCHESTRATION", "opens a body scope via BodyInvoker"),
    "core.withEnv": ("BLOCK_ORCHESTRATION", "opens a body scope via BodyInvoker"),
    "core.withCredentials": ("BLOCK_ORCHESTRATION", "opens a body scope"),
    "core.parallel": ("BLOCK_ORCHESTRATION", "named bodies via BranchInvoker.invokeAll"),
    "core.retry": ("BLOCK_ORCHESTRATION", "body re-entry + durable retry control rows"),
    "core.timeout": ("BLOCK_ORCHESTRATION", "body re-entry"),
    "core.timestamps": ("BLOCK_ORCHESTRATION",
                        "declares BodyContextProjection.Timestamps; opens a body scope"),
    "parallel-branch": ("CONTROL_NODE", "durable control row for parallel branches"),
    "retry-attempt": ("CONTROL_NODE", "durable retry control row (RETRY-D)"),
    "wait-until-control": ("CONTROL_NODE", "durable waitUntil control row"),
    "wait-until-poll": ("CONTROL_NODE", "durable waitUntil poll row"),
    "core.${step.name}": ("TEMPLATE", "dynamic construction in source, not a real key"),
}

# ledger states that legitimately have no runtime declaration
LEDGER_ONLY_OK = {"REJECTED", "STOPPED_G7", "STOPPED", "DEFERRED", "DESIGNED"}


def ledger_records():
    text = "\n".join(l for l in LEDGER.read_text().split("\n")
                     if not l.lstrip().startswith("#"))
    out = {}
    for m in re.finditer(r'^  - step_key:\s*"?([\w.\-${}]+)"?\s*$', text, re.M):
        start = m.end()
        nxt = re.search(r'^  - step_key:', text[start:], re.M)
        body = text[start:start + nxt.start()] if nxt else text[start:]
        st = re.search(r'^\s{4}certification_state:\s*"?([\w_]+)"?', body, re.M)
        out[m.group(1)] = st.group(1) if st else "?"
    return out


def main():
    rt = runtime_keys()
    led = ledger_records()

    print("=" * 100)
    print("RECONCILIATION — discovery (runtime) vs certification (ledger)")
    print("=" * 100)
    print(f"  runtime production StepKeys : {len(rt)}")
    print(f"  ledger records              : {len(led)}")

    # A. runtime -> ledger
    missing = sorted(rt - set(led))
    print()
    print("A. runtime keys ABSENT from the ledger")
    print("-" * 100)
    viol = []
    cls_count = Counter()
    for k in missing:
        if k in EXCEPTIONS:
            c, why = EXCEPTIONS[k]
            cls_count[c] += 1
            print(f"   [excluded] {c:20} {k:24} {why}")
        else:
            viol.append(k)
            cls_count["VIOLATION"] += 1
            print(f"   [VIOLATION] {'':20} {k:24} no typed exception declared")
    if not missing:
        print("   (none)")
    print()
    print("   excluded by class:", dict(cls_count))

    # B. ledger -> runtime
    print()
    print("B. ledger keys with NO runtime declaration")
    print("-" * 100)
    violb = []
    for k in sorted(set(led) - rt):
        st = led[k]
        if st in LEDGER_ONLY_OK:
            print(f"   [excluded] {st:12} {k:24} ledger state permits absence")
        else:
            violb.append(k)
            print(f"   [VIOLATION] {st:12} {k:24} certified but not declared at runtime")
    if not (set(led) - rt):
        print("   (none)")

    print()
    print("=" * 100)
    print("COUNTERS")
    print("=" * 100)
    print(f"  runtime StepKeys missing from ledger (unclassified) : {len(viol)}")
    print(f"  ledger keys missing from runtime (unexpected)       : {len(violb)}")
    print(f"  typed exceptions applied                            : {sum(cls_count.values()) - len(viol)}")
    return 1 if (viol or violb) else 0


if __name__ == "__main__":
    sys.exit(main())
