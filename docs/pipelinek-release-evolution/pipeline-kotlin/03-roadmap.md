# pipeline-kotlin — DIST-PRODUCT Roadmap

## TRAIN P0 — Protocol cutover

Goal: make candidate material target-versioned and consumable by the asynchronous harness without adding heavy certification upstream.

### P0.1 Product identity model

- introduce `ProductVersion` / candidate metadata distinction in release specs;
- remove assumptions that `-rcN` must live inside binary identity;
- update ADR/release docs.

Exit: product version and candidate state are independent concepts.

### P0.2 Distribution manifest v2

- generate immutable machine-readable manifest;
- include asset, archive root, runtime version, source SHA, ZIP SHA, SBOM SHA;
- verify generation reproducibility.

Exit: manifest describes actual bytes and is never promotion-rewritten.

### P0.3 Cheap identity admission

Add deterministic test/tool that verifies exact equality among:

- target version;
- asset name;
- archive root;
- JAR Implementation-Version;
- CLI version;
- manifest version.

Exit: historical 0.43.0 mismatch would fail locally.

### P0.4 Candidate handoff v2

- emit candidate descriptor schema v2;
- use ZIP SHA as candidate ID;
- include release train + sequence.

Exit: harness can ingest candidate without inferring identity from filename/tag.

## TRAIN P1 — Manual distribution repair

### P1.1 SHA authority

Use `SHA256SUMS`; remove dependency on absent `.zip.sha256` sidecar.

### P1.2 Transactional install

Download/extract/verify in mktemp, atomic final move.

### P1.3 Exact runtime identity

Warning -> hard failure and cleanup.

### P1.4 Diagnostics

Improve `doctor`/install docs with binary root/version information.

## TRAIN P2 — 0.43.x corrective release

If still desired:

- checkout certified 0.43 functional baseline;
- set target ProductVersion 0.43.1;
- build new target-versioned candidate;
- hand off to harness;
- continue main independently.

## TRAIN P3 — Resume 0.44 candidate stream

- materialize latest 0.44 train state under protocol v2;
- publish candidate descriptor;
- continue S1/S2 work without waiting.
