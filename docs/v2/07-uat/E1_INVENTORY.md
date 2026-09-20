# E1.ecosystem-local-first — Inventory (pre-cycle)

Cycle: `cycle/e1-ecosystem-local-first`
Anchor: pre-cycle state of the catalog, contracts, and corpus.
Captured at commit `f0c101fe` (after E1.proposal).

This file is the **before** half of the inventory delta; the
**after** half lands in the cycle closure receipt.

## Catalog of core steps (pre-cycle)

Source of truth: `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreStepRegistryFactory.kt`
sha256: `bcd9d725a7207da35d2eef001d1b566982df36ed61ae0593cbee9e9e425d5023`

16 Step registrations:

| Step | Status (per LB-02 ledger) | Notes |
|---|---|---|
| `core.echo` | **CERTIFIED** | First registry-driven Step; S3 burn-down done. |
| `core.sh` (CoreShellStep) | **IMPLEMENTED_UNCERTIFIED** | F1 contract closed (this cycle's predecessor); formal certification in LB-02 S6.8. |
| `core.error` | Legacy (candidate G1) | Routing still LegacyCore; not flipped. |
| `core.sleep` | Legacy (candidate G1) | Same shape as `core.error`. |
| `core.file.writeFile` | Legacy (candidate G1) | Same. |
| `core.readFile` | Legacy (candidate) | Per WU-LPR-104 (blocker closure). |
| `core.fileExists` | Legacy (candidate) | Companion to `core.readFile`. |
| `core.archiveArtifacts` | **REGISTRY_PRIMARY (LEGACY_UNREACHABLE)** | LB-02 / G4. E1.2 will bridge this without re-implementing it. |
| `core.emit.event` | Legacy (candidate G1) | Same shape. |
| `core.isUnix` | Legacy (candidate G1) | TYPED_RUNTIME_OUTPUT pending. |
| `core.pwd` | Legacy (candidate G1, scoped to `tmp=false`) | D3 frozen at G2. |
| `core.pwd.tmp` | **Registry from registration** | New deterministic tmp-workspace Step. |
| `core.deleteDir` | Legacy (candidate G1) | Conditional capability. |
| `core.cleanWs` | Legacy (candidate G1) | Conditional capability. |
| `core.waitUntil` | (not enumerated above; verify in registry) | — |
| `core.milestone` | (not enumerated above; verify in registry) | — |

The grep above lists 16 registrations; the explicit pair `Milestone`
+ `WaitUntil` are the two not annotated in the table (the codebase
already has fixtures for `core.milestone` and `core.waitUntil` per
the compatibility corpus fixtures 21-milestone.pipeline.kts and
22-wait-until.pipeline.kts, so these steps are present even if not
flagged in the table above).

## Gap that E1 closes

**No `core.junit` step exists in the catalog.** Verified via:

```bash
$ grep -rn 'core\.junit\|junitReport\|readJUnit' v2 --include='*.kt' | wc -l
0
```

A pipeline can build with `core.sh`, archive with
`core.archiveArtifacts`, but cannot read the JUnit XML report back
into a typed Kotlin value. This breaks the local CI/CD loop at
exactly one point.

`E1.1` adds `core.junit` through the public SDK (registry +
contract + codec + handler + capability + events + contract suite
+ DSL façade + corpus fixtures), without:

- modifying F1 (the `sh` variable-scope contract).
- opening F2.
- introducing engine or coordinator special-cases.
- adding remote storage or new protocols.

## Test and corpus inventory (pre-cycle)

- Contract suite tests in `pipeline-application`: 14 (from `ls .../test/.../*ContractSuite*`).
- Compatibility corpus fixtures: 30 (from `ls v2/compatibility/`).
- Examples: 10 numbered `0N-*.pipeline.kts` + 1 contracts folder +
  1 example-uppercase-plugin folder + 1 README + 1 run.sh.

F1 cycle additions (kept here as anchor; not modified by E1):

- 5 contract test classes: `S2ThreePhaseProbeTest`,
  `ShVarScopeGap02Test`, `ShVarScopeGap03Test`,
  `ShVarScopeGap04FormFProbeTest`, `ShVarScopeGap05Test`.
- 5 evidence files in `docs/v2/07-uat/evidence/sh-var-scope-contract/`.
- 1 contract document: `docs/v2/03-specifications/SH_VAR_SCOPE_CONTRACT.md`
  (sha256 `abc8f5bed09f109205a4b7451a801eee272685f544ed973b19b6e65d6d076f7b`).

## Adjacent work that E1 re-uses (does not modify)

- **WU-LPR-062** — Gradle installed-distribution fixture
  (`pipelinek` runs a real Gradle build end-to-end through the
  installed distribution). E1.3.T3 re-uses this as the default
  build driver for the demo pipeline.
- **LB-02 / S6.8** — `core.sh` formal certification. E1 does not
  block on it; F1 contract closure is sufficient for E1.1 to read
  JUnit reports produced by `core.sh` invocations of `./gradlew test`.
- **`core.archiveArtifacts` REGISTRY_PRIMARY** — E1.2 bridges it
  to a derived index without re-implementing the step.

## Inventory delta (after E1 closes)

To be filled by the cycle closure receipt. Expected additions:

| After | Element |
|---|---|
| +1 step | `core.junit` (CERTIFIED through E1.1 burn-down) |
| +1 step | `core.artifact.query` (CERTIFIED through E1.2 burn-down) |
| +1 capability | `JUNIT_REPORT_CAPABILITY` |
| +1 capability | `ARTIFACT_INDEX_CAPABILITY` |
| +1 contract suite | `CoreJunitStepContractSuiteTest` |
| +1 contract suite | `CoreArtifactQueryStepContractSuiteTest` |
| +3 corpus fixtures | `27-junit-read.pipeline.kts`, `28-junit-missing.pipeline.kts`, `29-junit-malformed.pipeline.kts` |
| +2 corpus fixtures | `30-artifact-query-happy.pipeline.kts`, `31-artifact-query-missing.pipeline.kts` |
| +1 example project | `examples/e1-ecosystem-demo/` |
| +1 contract doc | none new (F1 contract unchanged; E1 has no new spec contract document, only the UAT plan) |

F1 contract sha256 must remain `abc8f5bed09f...` after E1 closes;
the closure receipt will assert this.

## Operators on this inventory

- `inventory snapshot captured at: 2026-09-20T10:47Z`
- `commit anchor: f0c101fe`
- `next: E1.0.T3 decision receipt`
