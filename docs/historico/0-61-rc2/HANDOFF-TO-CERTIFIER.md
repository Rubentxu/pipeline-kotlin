# Handoff to certifier — v0.61.0-rc2 candidate

- **Status:** CANDIDATE_PUBLISHED · 2026-10-15
- **Train:** 0.61.0-rc2 · **Source of truth:** this file + `SHA256SUMS` + `git-head.txt`
- **Provenance:** additive merge of PR #130 (WU-A/B), #133 (WU-B), #135 (WU-C), #136 (WU-C polish) into `main`. No squash (ADR-0099).
- **Base:** `4a6b161e` (post #136)

## Producer → certifier boundary

The producer's responsibility ends at `candidateAdmission` PASSED and a complete set of artefacts
catalogued under SHA-256. The certifier independently re-computes SHAs, re-runs the gate, and
re-measures the R4 scenarios before signing anything. **Nothing in this document is a verdict.**

## Scope of this candidate

This candidate is the rc1 follow-up: rc1 was published with `build.gradle.kts` set to `0.60.0`
(the train was advertised as `0.61.0-rc1` but ProductVersion across the JAR, the runtime
`sdk-info`, the asset name, the archive root and the distribution manifest all carried the
trailing train's previous value). R4 / WU-D fixes that:

- `v2/build.gradle.kts` version bumped from `0.60.0` to `0.61.0`.
- `pipeline-application-0.61.0.jar` `Implementation-Version: 0.61.0`.
- `pipelinek sdk-info` reports `"version":"0.61.0"` and the six coordinates at `0.61.0`.
- ZIP asset name is `pipelinek-0.61.0.zip`, archive root is `pipelinek-0.61.0`.
- `SHA256SUMS` covers the ZIP, the SBOM and the git-head; `sha256sum -c` is green.

### R4 closure slices included in this candidate

| Slice | Receipt | What closed |
|---|---|---|
| #130 (WU-A/B) | `R4_WU_AB_REDACTION_DRAIN_RECEIPT.md` | Output Plane is populated again; ring-full spin fixed, EOF flush retained, `****` synthetic pattern removed. `raw_secret_on_disk == 0` preserved. |
| #133 (WU-B) | `R4_WU_B_TRANSCRIPT_DRAIN_RECEIPT.md` | Typed completion for the transcript pump; `ProcessOutputRefusal` is a sealed ADT with `WriteFailed` and `DrainIncomplete`; sink exceptions routed through `GuardedProcessOutputSink`; `join(10_000)` replaced with `drainTranscriptPumps` (waits AND checks `isAlive`); seal withheld only when the terminal is `OUTPUT_PERSISTENCE_REFUSED`. |
| #135 (WU-C) | `R4_WU_C_INSTALL_DRAIN_RECEIPT.md` | `consoleValidate` decomposed (complexity 36→≤8); `launch` extracted to `startTranscriptPumps` (131→≤120 lines); 4 of 5 named detekt closed; `MainConsoleCli` TooManyFunctions suppressed with documented ABI reason; HF2 canary `ConsoleMultiOpInstallCanaryTest` 3/0/0 (two ops, silent step, --follow on a sealed run). |
| #136 (WU-C polish) | same as #135 | The canary's two detekt findings (VariableNaming, NewLineAtEndOfFile) closed; `:pipeline-application:detekt` 0. |

## Source of truth

| Item | Value |
|---|---|
| Train | 0.61.0-rc2 |
| Base | `4a6b161e` (`main` after #136) |
| Tip | `4a6b161e` |
| PRs included | #130, #133, #135, #136 (all merged before this candidate) |
| Tree state at tip | clean |
| Audit | R4 closure complete |

## Artefacts (SHA-256, from `SHA256SUMS`)

| File | Size | SHA-256 |
|---|---|---|
| `pipelinek-0.61.0.zip` | 88.7 MB | `94c852e7724519857ae1226107c10d247053b5d034a98d4290540d04f3b1ff23` |
| `pipelinek-0.61.0.sbom.json` | 19.7 KB | `fa9fee844ba6c726c6478e0ede00d41b527c917fe69f9ae8904d35bee7bff7ae` |
| `git-head.txt` | 41 B | `274a45c94ed0c9670a07dc0afe1cb36a23694be64f1233a9457b5e15e846f422` |

## Producer verification

- `:pipeline-application:detekt` 0 issues
- `:pipeline-step-sdk:runtime:detekt` 0 issues
- L4 incremental (`:pipeline-step-sdk:runtime:test` + `:pipeline-application:test`) → 262 / 3 skipped / 0 failures / 0 errors
- `ConsoleMultiOpInstallCanaryTest` HF2 (the 3 skipped under non-canary L4) → 3 / 0 / 0 against the installed binary
- `install-pipelinek.sh install 0.61.0` against the loopback mirror → `Installed binary reports: pipeline 0.61.0`, `sdk-info` `version: 0.61.0`, all 6 coordinates at `0.61.0`

## Known limitation carried over

**Frame fragmentation**: the JSONL path emits one record per emitted byte for very small
payloads (a 4-byte `f1\n` arrives as three records of length 1). The consumer contract is to
reconstruct text by concatenating `text` fields. The canary `MULTI-3` re-derives the assertion
on the reassembled text rather than on a substring of the raw stdout, so the failure mode is
covered. The throughput fix is documented in the WU-A/B and WU-C receipts; the slice is not
in this candidate.

## Certifier notes

- Re-derive every digest in `SHA256SUMS` from `evidence/0-61-rc2/` before doing anything else;
  a tampered asset must be reported, not silently accepted.
- The product identity is `0.61.0` across all five surfaces (asset name, archive root, JAR
  manifest, runtime `sdk-info`, distribution manifest). A `0.60.0` on any of them is a
  regression of the rc1 product-identity defect and is the most likely point of failure.
- The `MainConsoleCli` TooManyFunctions suppression in WU-C is a documented ABI decision, not
  a relaxed assertion; the certifier SHOULD flag it if a refactor of the public surface is
  considered part of the R4 contract.

## Release train: 0.61.0

This candidate, when accepted, is the rc2 of the `0.61.0` train. A subsequent rc (rc3 etc.) or
the stable `0.61.0` would carry the next additive slice. A defect discovered by the certifier
becomes an issue against this train and is fixed in `main`; the next candidate of the same
train ships the fix, this one is not retroactively modified.
