#!/usr/bin/env python3
"""S2-B10 / G8 — resolves every sha256 and every count the G8 receipt asserts.

The G8 receipt makes three kinds of claim, and each is checked against a different
oracle:

  1. HASH CITATIONS that name a file in the tree        -> resolved by hashing that
     file now. A stale hash fails.
  2. HASH CITATIONS that name a BUILD OUTPUT
     The JUnit XMLs are committed under `raw/xml/`, so they are ordinary resolvable
     files and are checked like any other. The distribution jar is NOT re-derivable
     (gitignored, and byte-unstable across builds), so it is accepted only in one
     explicitly named class:
        * `build output, NON-REPRODUCIBLE (proven)` — granted only when the gate also
          recorded a REBUILD at the identical commit whose hash DIFFERS, which is
          what makes "non-reproducible" a demonstrated property rather than an
          excuse. If the rebuild happened to match, this class is not available.
     An unclassified unresolvable citation is still a failure.
  3. LIVE CLAIMS (the residual triple) -> re-derived from source right now. This is
     the one substantive claim that IS re-derivable from a fresh checkout, so it is
     not taken from the JSON.

Count claims ("N entries", "verified N/N OK") are asserted against the manifest, and
the test-count claims are asserted against g8-canary.json.
"""
import hashlib
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", ".."))
G7 = os.path.join(HERE, os.pardir, "s2-b10-g7")
RECEIPT = os.path.join(HERE, os.pardir, os.pardir, "S2_B10_ARCHIVEARTIFACTS_G8_CERTIFICATION_RECEIPT.md")
EMPTY = hashlib.sha256(b"").hexdigest()

# Paths the receipt cites by name and hash. Kept explicit: a citation that is not
# resolvable here is a gate failure, not a silent pass.
# Counter states recorded by CLOSED gates that this receipt quotes verbatim.
#   "2/3/3" = G4 REGISTRY_PRIMARY: id removed, metadata row and dispatcher file still present.
HISTORICAL_COUNTER_STATES = {"2/3/3"}

REPO_SOURCES = [
    "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreArchiveArtifactsStep.kt",
    "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/ArchiveArtifactsOperations.kt",
    "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/CoreArchiveArtifactsStepContractSuiteTest.kt",
    "docs/v2/07-uat/S2_B10_ARCHIVEARTIFACTS_G4_REGISTRY_PRIMARY_RECEIPT.md",
    "docs/v2/07-uat/S2_B10_ARCHIVEARTIFACTS_G5_LEGACY_REMOVED_RECEIPT.md",
    "docs/v2/07-uat/S2_B10_ARCHIVEARTIFACTS_G6_CONTRACT_CERTIFICATION_RECEIPT.md",
    "docs/v2/07-uat/S2_B10_ARCHIVEARTIFACTS_G7_INSTALLED_ACCEPTANCE_RECEIPT.md",
    "docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md",
]

digests = []          # (label, digest)


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def add(label, digest):
    digests.append((label, digest))


def build_index():
    for base, tag in ((HERE, "g8 evidence"), (G7, "g7 evidence")):
        for dirpath, _d, files in os.walk(base):
            for name in files:
                p = os.path.join(dirpath, name)
                rel = os.path.relpath(p, base)
                try:
                    add(f"{tag}: {rel}", sha256_file(p))
                except OSError:
                    pass

    # payload digests recorded inside archived-file manifests
    for base, tag in ((HERE, "g8"), (G7, "g7")):
        for dirpath, _d, files in os.walk(base):
            for name in files:
                if not name.endswith("-archived-files.sha256"):
                    continue
                rel = os.path.relpath(os.path.join(dirpath, name), base)
                for line in open(os.path.join(dirpath, name), encoding="utf-8"):
                    parts = line.split(None, 1)
                    if len(parts) == 2 and re.fullmatch(r"[0-9a-f]{64}", parts[0]):
                        add(f"payload digest recorded in {tag}:{rel}", parts[0])

    # Repo files the receipt cites by path (source under test, suite, prior receipts,
    # ledger). Hashed live, so a stale citation fails here.
    for rel in REPO_SOURCES:
        p = os.path.join(ROOT, rel)
        if not os.path.isfile(p):
            continue
        add(f"repo file: {rel}", sha256_file(p))

    # Digests RECORDED inside the gate's own `*-sha256.txt` notes. These are claims the
    # gate wrote down at run time; indexing them checks internal consistency (the receipt
    # cites what the gate recorded), which is strictly weaker than resolving a file and is
    # labelled as such. `g7-cited-jar-sha256.txt` is excluded because that digest is
    # handled by the explicit non-reproducible class below.
    for name in sorted(os.listdir(os.path.join(HERE, "raw"))) if os.path.isdir(os.path.join(HERE, "raw")) else []:
        if not name.endswith("-sha256.txt") or name == "g7-cited-jar-sha256.txt":
            continue
        rel = os.path.join("raw", name)
        for line in open(os.path.join(HERE, rel), encoding="utf-8"):
            line = line.strip()
            if re.fullmatch(r"[0-9a-f]{64}", line):
                add(f"recorded digest in {rel}", line)

    canary_data = json.load(open(os.path.join(HERE, "g8-canary.json"), encoding="utf-8"))

    # the G7 non-reproducible build-output class, PROVEN by a recorded rebuild
    cited = os.path.join(HERE, "raw", "g7-cited-jar-sha256.txt")
    rebuilt = os.path.join(HERE, "raw", "rebuild-jar-sha256.txt")
    class_ok = False
    if os.path.isfile(cited) and os.path.isfile(rebuilt):
        c = open(cited, encoding="utf-8").read().strip()
        r = open(rebuilt, encoding="utf-8").read().strip()
        if re.fullmatch(r"[0-9a-f]{64}", c) and re.fullmatch(r"[0-9a-f]{64}", r) and c != r:
            add("build output, NON-REPRODUCIBLE (proven: rebuild at identical commit differs)", c)
            class_ok = True
    add("empty-file digest (constant)", EMPTY)
    return canary_data, class_ok


def live_residual():
    d = os.path.join(ROOT, "v2", "pipeline-application", "src", "main", "kotlin",
                     "dev", "rubentxu", "pipeline", "v2", "application")
    src = open(os.path.join(d, "CanonicalCoreStepDecoder.kt"), encoding="utf-8").read()
    m = re.search(r"LEGACY_PLUGIN_IDS: Set<String> = setOf\((.*?)\n\s*\)", src, re.S)
    ids = sorted(l.strip().strip('",') for l in m.group(1).splitlines()
                 if l.strip() and not l.strip().startswith("//"))
    meta = open(os.path.join(d, "CanonicalCoreStepMetadata.kt"), encoding="utf-8").read()
    rows = sorted(re.findall(r'^\s*"(core\.[a-zA-Z.]+)" to StepMetadata', meta, re.M))
    disp = sorted(p.name for p in (os.path.join(ROOT, "v2") and
                                   __import__("pathlib").Path(os.path.join(ROOT, "v2")).rglob("Canonical*NodeDispatcher.kt")))
    per_step = [x for x in disp if x != "CanonicalNodeDispatcher.kt"]
    return ids, rows, per_step


def main():
    text = open(RECEIPT, encoding="utf-8").read()
    canary, class_ok = build_index()

    citations = set()
    for m in re.finditer(r"(?:sha256 )?\b([0-9a-f]{8,64})…([0-9a-f]{4,12})?", text):
        citations.add((m.group(1), m.group(2), m.group(0)))
    for m in re.finditer(r"\b([0-9a-f]{64})\b", text):
        citations.add((m.group(1)[:12], None, m.group(1)))

    def resolve(prefix, suffix):
        for label, digest in digests:
            if digest.startswith(prefix) and (suffix is None or digest.endswith(suffix)):
                return label
        return None

    unresolved = []
    for prefix, suffix, literal in sorted(citations):
        if resolve(prefix, suffix) is None:
            unresolved.append(literal)
    for prefix, suffix, literal in sorted(citations):
        print(f"  {literal[:72]:<44} -> {resolve(prefix, suffix) or 'UNRESOLVED'}")

    problems = []
    if unresolved:
        problems.append("citations that resolve to no known hash: " + ", ".join(unresolved))
    if not class_ok:
        problems.append("the G7 build-output class is not PROVEN: raw/g7-cited-jar-sha256.txt and "
                        "raw/rebuild-jar-sha256.txt must both exist and differ")

    # count claims vs the real manifest
    manifest = os.path.join(HERE, "g8-raw-sha256.txt")
    actual = len([l for l in open(manifest, encoding="utf-8") if l.strip()]) if os.path.isfile(manifest) else None
    if actual is None:
        problems.append("manifest g8-raw-sha256.txt is missing")
    else:
        for m in re.finditer(r"(\d+) entries", text):
            if int(m.group(1)) != actual:
                problems.append(f"count claim {m.group(1)} entries != real {actual}")
        for m in re.finditer(r"verified (\d+)/(\d+) OK", text):
            if (int(m.group(1)), int(m.group(2))) != (actual, actual):
                problems.append(f"verified claim {m.group(1)}/{m.group(2)} != real {actual}/{actual}")
        print(f"  count claims vs manifest: {actual} entries")

    # Test/counter claims vs g8-canary.json.
    #
    # This asserts CONSISTENCY, not existence. An earlier draft checked only that the
    # expected literal appeared SOMEWHERE in the receipt, which a mutation survives
    # whenever the same number is also stated elsewhere -- and it is, in the per-gate
    # table and the ledger. The negative controls caught that: editing one occurrence of
    # `27/0/0` or `2/2/2` still passed. So instead, every `a/b/c` triple found anywhere in
    # the receipt must be a value the evidence permits. A triple outside the allowlist is
    # a contradiction wherever it sits, and it fails.
    allowed = {
        f"{v['tests']}/0/0"
        for v in canary["classes"].values()
        if v["failures"] == 0 and v["errors"] == 0
    }
    allowed.add(f"{canary['totals']['s3_legacy_removed_tests']}/0/0")   # S3 aggregate
    allowed.add("2/2/2")                                               # live residual triple
    # Historical counter states the receipt legitimately quotes from closed gates.
    # Declared explicitly and kept minimal: each one is a number a prior gate actually
    # recorded, and adding to this set is a deliberate act, not an accident.
    allowed |= HISTORICAL_COUNTER_STATES
    observed = set(re.findall(r"\b(\d+/\d+/\d+)\b", text))
    stray = sorted(observed - allowed)
    if stray:
        problems.append(f"count triples contradicting the evidence: {stray} (allowed: {sorted(allowed)})")
    print(f"  count triples observed: {sorted(observed)}")

    n = canary["classes"]["CoreArchiveArtifactsStepContractSuiteTest"]["tests"]
    if f"{n}/0/0" not in observed:
        problems.append(f"receipt never states the contract suite as {n}/0/0")
    s3 = canary["totals"]["s3_legacy_removed_tests"]
    if f"{s3}/0/0" not in observed:
        problems.append(f"receipt never states the S3 totals as {s3}/0/0")

    # live claim: the residual triple
    ids, rows, per_step = live_residual()
    triple = f"{len(ids)}/{len(rows)}/{len(per_step)}"
    print(f"  live residual triple = {triple}  (ids={ids}, rows={rows}, dispatchers={per_step})")
    if triple != "2/2/2":
        problems.append(f"live residual triple is {triple}, receipt claims 2/2/2")
    if triple not in observed:
        problems.append(f"receipt never states the residual triple as {triple}")

    if problems:
        print("\nFAIL:")
        for p in problems:
            print("  " + p)
        return 1
    print("\nOK: every cited sha256 resolves, or is in an explicitly proven non-re-derivable class")
    print("OK: count and test-number claims agree with the manifest and g8-canary.json")
    print("OK: the residual triple re-derives from live source as 2/2/2")
    return 0


if __name__ == "__main__":
    sys.exit(main())
