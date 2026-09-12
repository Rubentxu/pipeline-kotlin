# S2-A7 / G8 — core.deleteDir Final Certification Receipt

> Cycle: `cycle/lfc2-e1-deletedir-g8`
> Slice: S2-A7 (`core.deleteDir`)
> Gate: **G8 — Final Certification (read-only verification of all prior gates)**
> Base: `main @ 6ad8d5df` (G7 merged); branch HEAD at verification: `6ad8d5df`
> Date: 2026-09-13T00:26–00:40Z (local +02:00)
> Production code changes: **0** (docs/receipt only)
>
> G8 closes S2-A7 and **proposes `CERTIFIED = true`** for `core.deleteDir`
> (ADR-0074). This receipt is a read-only re-verification of every prior gate
> on current main with fresh evidence; the installed-CLI acceptance burden was
> already discharged at G7 (`S2_A7_CORE_DELETEDIR_G7_INSTALLED_ACCEPTANCE_RECEIPT.md`,
> 4/4 PASS, integrity re-verified below — no scenarios re-run at G8).

## 1. Per-gate verification table (re-verification of CLOSED gates)

G4..G7 are already-closed facts (recorded in their merged receipts and in the
gate ledger below). This G8 slice does NOT re-litigate or reinterpret them; it
re-verifies on current main (`6ad8d5df`) that the recorded evidence is intact
and fresh (relevant inputs unchanged since each gate's green run). Where a
claim below cites a test, the test is the gate's OWN recorded proof, re-run
fresh here solely for the freshness canary.

| Gate | Closed at (authority) | Freshness re-verification on `6ad8d5df` (2026-09-13) | Verdict |
|---|---|---|---|
| G4 REGISTRY_PRIMARY | `S2_A7_CORE_DELETEDIR_G4_REGISTRY_PRIMARY_RECEIPT.md` | `CoreDeleteDirStepUnitTest` (own recorded proof) re-run fresh: `"core.deleteDir" !in CanonicalCoreStepCommand.LEGACY_PLUGIN_IDS` ∧ `StructuralFamilyResolver.classify(CoreDeleteDirStep.KEY, registry) == Registry` | **CONFIRMED** |
| G5 LEGACY_REMOVED | `S2_A7_CORE_DELETEDIR_G5_LEGACY_REMOVED_RECEIPT.md` (counters 5/5/5) | `LegacyResidualSnapshot.assertConverged` (inside all 7 `S3*LegacyRemovedFitnessTest`, re-run fresh) confirms the three legacy forms (command subtype/decoder branch, dispatcher file, metadata registration) remain absent; `CoreDeleteDirStepUnitTest.counters - G5 converged 5 5 5` and `CanonicalCoreStepCommandRegistryTest` 7/0 fresh | **CONFIRMED** |
| G6 CONTRACT_SUITE | `CoreDeleteDirStepContractSuiteTest` (merged at G6/G6-prep, `6f4f827e`) | re-run fresh, canary-verified (`cleanTest` + regenerated XML): 22/0/0, sha256 `fa85bfe4e7c61f9421dbfc7d04b7246e1fa96ba96ea9c37101fb9344fd2147b8` | **CONFIRMED** |
| G7 INSTALLED_ACCEPTANCE | `S2_A7_CORE_DELETEDIR_G7_INSTALLED_ACCEPTANCE_RECEIPT.md` (4/4 PASS, INSTALLED_ACCEPTANCE=true) | receipt present; sha256 `97e1255a01aff2160ceea5a398f74c42b1aeec3f41f2c73e16b9a3620f033ab0`; all 8 archived raw evidence files under `docs/v2/07-uat/evidence/s2-a7-g7/` byte-verified against the receipt's recorded sha256 (8/8 match). Scenarios NOT re-run at G8 (per slice mandate; no relevant input changed since G7's green run). | **CONFIRMED** |
| Counters 5/5/5 | G5 closure authority (`LegacyResidualSnapshot`) | `assertConverged` fresh (residual = {milestone, cleanWs, load, waitUntil, archiveArtifacts} × {ids, metadata rows, dispatcher files}); `Lfc2RegistryFamilyFitnessTest` 3/0 fresh | **CONFIRMED** |
| S3 + Lfc2 fitness green | per-gate receipts | S3 ×7 suites: 7+8+12+9+8+4+4 = **52/0** fresh; `Lfc2RegistryFamilyFitnessTest` 3/0 fresh | **CONFIRMED** |

Regression canaries (fresh): `CanonicalCoreStepCommandRegistryTest` 7/0/0
(sha256 `3340bc661e668b35310138dcb9b67e2b24cb0a962b883c6aa2deb62926b9290c`),
`UatLocal011WorkflowControlTest` 13 run / 1 skipped / 0 fail (workflow-control
semantics untouched; sha256 `267d0383da4773e4da955bd3eb646eb36fa37fe1e9bb69ea9f163cd1ab4c1c36`).

## 2. Fresh JUnit XML evidence (canary discipline)

All XMLs regenerated 2026-09-13T00:30–00:35 local (+02:00) via
`./gradlew -p v2 :<module>:cleanTest :<module>:test --tests '<class>'` (exit 0 each;
`cleanTest` deletes prior XMLs so regeneration is the freshness canary, rule 25).

```text
:pipeline-application
  CoreDeleteDirStepUnitTest            tests=18 skipped=0 failures=0 errors=0
    sha256 = 36eb016bb6b3445b3d6226d8c18fd5a52810861b3f5603ef1185a4eef1e47d34
  CoreDeleteDirStepContractSuiteTest   tests=22 skipped=0 failures=0 errors=0
    sha256 = fa85bfe4e7c61f9421dbfc7d04b7246e1fa96ba96ea9c37101fb9344fd2147b8
  CanonicalCoreStepCommandRegistryTest tests=7  skipped=0 failures=0 errors=0
    sha256 = 3340bc661e668b35310138dcb9b67e2b24cb0a962b883c6aa2deb62926b9290c
  UatLocal011WorkflowControlTest       tests=13 skipped=1 failures=0 errors=0
    sha256 = 267d0383da4773e4da955bd3eb646eb36fa37fe1e9bb69ea9f163cd1ab4c1c36

:pipeline-architecture-tests  (single cleanTest run; 8 classes)
  S3EchoLegacyRemovedFitnessTest       tests=7  f=0 e=0
    sha256 = 057b3006d4c2dc5d51a605dc50a1820bb79b4d6c452b45f1a74f726becdbbcaa
  S3EmitEventLegacyRemovedFitnessTest  tests=8  f=0 e=0
    sha256 = 91aa0b71cfb538ac0377547b557dc3b3cb8ad0027c0ade1eb0db491b8fd361c0
  S3ErrorLegacyRemovedFitnessTest      tests=12 f=0 e=0
    sha256 = f41ba0fa6ae41e7c5b3e81f571b1ad2079c994330b1fa5dfec30f11049ac6340
  S3IsUnixLegacyRemovedFitnessTest     tests=9  f=0 e=0
    sha256 = 9af27050220a811c28644ecd0e2088c6a3ae59fc8086e22687dd60189721cb70
  S3PwdLegacyRemovedFitnessTest        tests=8  f=0 e=0
    sha256 = 834665ff717f771e20e406c1abb81a5f0562966a17749df7eeef765c36504d6f
  S3SleepLegacyRemovedFitnessTest      tests=4  f=0 e=0
    sha256 = 120cf218cb8910d381ef5f782f0174e19b1f5464f584da1d4e7b0e893ede8200
  S3WriteFileLegacyRemovedFitnessTest  tests=4  f=0 e=0
    sha256 = f63ecaf47919e8a3cdedce227c47a06617a6b5adfb6034f776217cfb9f90a7bb
  Lfc2RegistryFamilyFitnessTest        tests=3  f=0 e=0
    sha256 = 39f8bc8908b42fb8bd2f2a19e9fd240989add8070b7f32c431457507af11b097
```

Source-of-truth hashes at verification time:

```text
CanonicalCoreStepDecoder.kt sha256 = 1d9d2836c4f481b184ccf0d04efd695df705df17d150cb4e1da5784c55665f4f
CoreDeleteDirStep.kt        sha256 = 15bbccc0c1ea3f72064a0af1f90dc3bb16a8384b0f12d81a35acc1f9ef75207f
G7 receipt                  sha256 = 97e1255a01aff2160ceea5a398f74c42b1aeec3f41f2c73e16b9a3620f033ab0
```

## 3. Why G8 re-runs no installed scenarios

Per ADR-0074 the certification proof of an effectful/recoverable Step rests on
the conjunction: registry-only authority (G4/G5) ∧ typed contract suite (G6)
∧ real installed-distribution acceptance (G7). The G7 slice already executed
the installed CLI against the real binary at `main @ 53b8fca0` — content
identical to `6ad8d5df` for the deleteDir seam (G7 merge touched only docs,
`bbe1e181`/`b38b41ad`) — and observed: real filesystem deletion with
`DirDeleted(deletedCount=5)`, exact domain-event payload, RERUN-class replay
with `deletedCount=0` and stable operation identity, and physical legacy
absence in source and in all 37 installed jars. This slice therefore
re-verifies gate *evidence integrity* (receipt hash + archived-raw hash
cross-check) rather than re-running scenarios; per rule 5/7 the G7 evidence
remains fresh because no relevant input changed.

## 4. Gate ledger close

```text
core.deleteDir:
  REGISTERED          = true   (G1, S2_A7_CORE_DELETEDIR_G1_REGISTRY_SEAM_PROOF.md)
  REGISTRY_PRIMARY    = true   (G4, S2_A7_CORE_DELETEDIR_G4_REGISTRY_PRIMARY_RECEIPT.md)
  LEGACY_REMOVED      = true   (G5, S2_A7_CORE_DELETEDIR_G5_LEGACY_REMOVED_RECEIPT.md, counters 5/5/5)
  CONTRACT_SUITE      = true   (G6, 22/0/0/0)
  INSTALLED_ACCEPTANCE= true   (G7, S2_A7_CORE_DELETEDIR_G7_INSTALLED_ACCEPTANCE_RECEIPT.md, 4/4 PASS)
  CERTIFIED           = true   ← PROPOSED by this G8 receipt
```

## 5. Burn-down counters at close (REAL numbers)

Legacy residual counters (G4/G5 authorities): **5 / 5 / 5**
(`LEGACY_PLUGIN_IDS` / metadata rows / per-Step dispatcher files —
residual = milestone, cleanWs, load, waitUntil, archiveArtifacts). UNCHANGED
by this slice: G8 mutates no authority.

Certified-Steps set (each entry backed by a MERGED G8/final certification
receipt on main; this slice adds ONLY `core.deleteDir`):

```text
CERTIFIED on main (before this proposal): 7
  core.echo            (S3 burn-down; CORE_ECHO_CERTIFICATION.md)
  core.sh              (LB-02 S6 burn-down; LB02_S6_BURN_DOWN_AND_CERTIFICATION.md)
  core.error           (S2-A1/G8, d7e6580b)
  core.sleep           (S2-A2/G8, f6fbde11)
  core.file.writeFile  (S2-A3/G8, 4154acc1)
  core.emit.event      (S2-A4/G8, 902f3b21)
  core.isUnix          (S2-A5/G8, dc0da54f)
PROPOSED by this receipt:               core.deleteDir  → 8 total
  + example.uppercase  (external reference plugin, EP burn-down — counted
                        separately from the 12 core LEGACY_PLUGIN_IDS keys;
                        certified, not affected by this slice)
```

Explicitly NOT in the certified set (state preserved, not touched by this
slice):

```text
core.pwd         : G7 STOP/BLOCKED — STRUCTURED_DSL_RUNTIME_RETURN_GAP;
                   G8 never attempted (S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md).
                   IMPLEMENTED_UNCERTIFIED. NOT certified here.
core.waitUntil   : S2-A8 G3 evidence-corrected to IMPLEMENTED_UNCERTIFIED
                   (BodyInvoker blocker).
core.milestone   : S2-A9 in progress.
core.cleanWs     : contract suite exists on an unmerged branch only.
core.load, core.archiveArtifacts : earlier burn-down stages.
```

Burn-down counters statement per AGENTS.md (the three numbers track the 12
LEGACY_PLUGIN_IDS core keys; the external reference plugin is additional):

```text
Certified core Steps on main BEFORE this slice:        7
  (core.echo, core.sh, core.error, core.sleep, core.file.writeFile,
   core.emit.event, core.isUnix — each backed by a merged certification
   receipt; the count includes core.sh, which IS CERTIFIED)
Added by THIS receipt (proposal):                      core.deleteDir  → 8
Legacy executable Steps:                               5  (residual 5/5/5)
Registry-primary Steps:                                8 (certified) + core.pwd
  (registry-routed but IMPLEMENTED_UNCERTIFIED pending LFC-2R2)
Convergence: M -> 0 as cleanWs/load/milestone/waitUntil/archiveArtifacts close.
```

Note: the stale E0 snapshot in `STEP_ECOSYSTEM_MATRIX.md` ("CERTIFIED: 3")
and the E0 inventory header ("CERTIFIED Steps: 4") predate the S2-A2..S2-A5
G8 closures; the authoritative per-Step states are the merged G8 receipts
cited above. This slice updates ONLY the `core.deleteDir` row/section in
STEP_INVENTORY_LFC2E0.md to CERTIFIED.
STEP_ECOSYSTEM_MATRIX.md remains intentionally unchanged in this slice.

## 6. Side effects

None. No production source under `v2/**/main/**` was modified. Deliverables:
this receipt + per-Step ledger state update
(STEP_INVENTORY_LFC2E0.md) recording `core.deleteDir` = CERTIFIED. Legacy counters frozen at
5/5/5.
