#!/usr/bin/env python3
"""S2-B10 / G7 — verifies that every sha256 the receipt cites is a REAL file hash.

Motivation: during this gate the receipt twice carried a hash that no longer (or never
did) describe the file it named — once because the assembler was edited after its hash
was copied into the receipt, once because a placeholder was written instead of a computed
value. Presence-checking a hash string is not verification. This script resolves every
cited hash against the filesystem and fails closed on any citation it cannot resolve.

Citations are recognised in either form used by the receipt:
    sha256 <12-hex>…
    `<64-hex>`
    <64-hex>            (bare, inside tables)
"""
import hashlib
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RECEIPT = os.path.join(HERE, os.pardir, os.pardir, "S2_B10_ARCHIVEARTIFACTS_G7_INSTALLED_ACCEPTANCE_RECEIPT.md")
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", ".."))
DIST_JAR = os.path.join(
    ROOT, "v2", "pipeline-application", "build", "install",
    "pipeline-application", "lib", "pipeline-application-0.1.0-SNAPSHOT.jar",
)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


digest_of_label = {}
all_digests = []


def build_hash_index():
    """prefix -> [labels] for every file the receipt may legitimately cite."""
    index = {}

    def add(label, digest):
        index.setdefault(digest[:12], []).append(label)
        digest_of_label[label] = digest
        all_digests.append((label, digest))

    for dirpath, _dirs, files in os.walk(HERE):
        for name in files:
            p = os.path.join(dirpath, name)
            # No self-exclusion: the verifier's own hash is a citable artefact and
            # must resolve like any other (an exclusion here would be a blind spot).
            rel = os.path.relpath(p, HERE)
            try:
                add(rel, sha256_file(p))
            except OSError:
                pass
    if os.path.isfile(DIST_JAR):
        add("distribution jar", sha256_file(DIST_JAR))

    # Domain PAYLOAD digests are not repo-file hashes: they are the sha256 of the
    # archived artifact content, recorded inside the raw `*-archived-files.sha256`
    # manifests. Indexing those makes the check STRICTER, not looser: a cited
    # payload hash must appear verbatim in the archived raw evidence.
    for dirpath, _dirs, files in os.walk(HERE):
        for name in files:
            if not name.endswith("-archived-files.sha256"):
                continue
            rel = os.path.relpath(os.path.join(dirpath, name), HERE)
            for line in open(os.path.join(dirpath, name), encoding="utf-8"):
                parts = line.split(None, 1)
                if len(parts) == 2 and re.fullmatch(r"[0-9a-f]{64}", parts[0]):
                    add(f"payload digest recorded in {rel}", parts[0])
    return index


def main():
    text = open(RECEIPT, encoding="utf-8").read()
    index = build_hash_index()

    # citations: (prefix, suffix_or_None, literal)
    citations = set()
    # form 1/2: <prefix>…[<suffix>]   (optionally preceded by the word sha256).
    # The suffix is OPTIONAL: the shorthand `sha256 c026979f…` is the form that
    # actually drifted earlier in this gate, so it must be verified, not skipped.
    for m in re.finditer(r"(?:sha256 )?\b([0-9a-f]{8,64})…([0-9a-f]{4,12})?", text):
        citations.add((m.group(1), m.group(2), m.group(0)))
    # form 3: a full 64-hex digest
    for m in re.finditer(r"\b([0-9a-f]{64})\b", text):
        citations.add((m.group(1)[:12], None, m.group(1)))

    # The empty-file digest is cited as the PROOF of zero archived files; it is a
    # legitimate constant, not a file hash.
    EMPTY = hashlib.sha256(b"").hexdigest()
    # `seq 1 2000` payload: computed outside the engine, by construction not a repo file.
    payload_ok = set()
    import subprocess
    expected = subprocess.run(
        ["bash", "-c", "seq 1 2000 | sha256sum | cut -d' ' -f1"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    payload_ok.add(expected[:12])

    def resolve(prefix, suffix):
        """Label of a live digest starting with `prefix` and ending with `suffix`."""
        for label, digest in all_digests:
            if digest.startswith(prefix) and (suffix is None or digest.endswith(suffix)):
                return label
        if EMPTY.startswith(prefix) and (suffix is None or EMPTY.endswith(suffix)):
            return "empty-file digest (constant)"
        if expected.startswith(prefix) and (suffix is None or expected.endswith(suffix)):
            return "seq-1-2000 payload (external)"
        return None

    unresolved = []
    for prefix, suffix, literal in sorted(citations):
        if resolve(prefix, suffix) is None:
            unresolved.append(literal)

    print(f"hash index: {len(index)} distinct prefixes")
    print(f"citations : {len(citations)}")
    for prefix, suffix, literal in sorted(citations):
        label = resolve(prefix, suffix) or "UNRESOLVED"
        print(f"  {literal[:72]:<44} -> {label}")

    # Count claims ("N entries", "verified N/N OK") are the same class of drift as a
    # stale hash: assert them against the manifest instead of trusting the prose.
    manifest = os.path.join(HERE, "g7-raw-sha256.txt")
    actual = len([l for l in open(manifest, encoding="utf-8") if l.strip()])
    count_claims = [int(m.group(1)) for m in re.finditer(r"(\d+) entries", text)]
    verified_claims = [
        (int(m.group(1)), int(m.group(2)))
        for m in re.finditer(r"verified (\d+)/(\d+) OK", text)
    ]
    count_bad = [c for c in count_claims if c != actual]
    verified_bad = [(a, b) for a, b in verified_claims if a != actual or b != actual]
    for c in count_claims:
        print(f"  count claim {c} entries -> {'OK' if c == actual else 'MISMATCH (actual ' + str(actual) + ')'}")
    for a, b in verified_claims:
        print(f"  verified claim {a}/{b} -> {'OK' if (a, b) == (actual, actual) else 'MISMATCH'}")

    if unresolved or count_bad or verified_bad:
        if unresolved:
            print("\nFAIL: citations that resolve to no known file hash:")
            for lit in unresolved:
                print("  " + lit)
        if count_bad or verified_bad:
            print(f"\nFAIL: manifest count claims disagree with the real manifest ({actual} entries)")
        return 1
    print("\nOK: every cited sha256 resolves to a real, current file hash")
    print(f"OK: manifest count claims agree with the real manifest ({actual} entries)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
