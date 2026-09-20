# STEP_REGISTRY_PLAN — Operational roadmap for upcoming cycles

Status: **BINDING** (cycle `lfc2-step-ecosystem-depuration-2026-09-20`).
Authority: [`docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md`](STEP_ECOSYSTEM_MATRIX.md),
[`docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md`](../07-uat/STEP_INVENTORY_LFC2E0.md),
[`openspec/changes/lfc2-step-ecosystem-depuration-2026-09-20/`](../../../openspec/changes/lfc2-step-ecosystem-depuration-2026-09-20/).

This document is the **operational roadmap**: per-Step G0..G8 plan,
validation set, and binding ordering. Read first; then implement.

## How to use this document

1. Pick the next Step from the **Next Step in queue** section below.
2. Open (or create) its WU receipt at
   `docs/v2/07-uat/S2_<step>_G<gate>_*.md` (or
   `LFC2E<XY>_<step>_G<gate>_*.md` for new families).
3. Run every row of the **Strict Validation Set** (next section) before
   merging.
4. The cycle closes only when the Step's final state is one of:
   - `CERTIFIED` (full G0..G8 + receipt), OR
   - `REJECTED` with reason (added to Tier D below).

There is **no third option**. `IMPLEMENTED_UNCERTIFIED`,
`LEGACY_IMPLEMENTED_UNCERTIFIED`, `DONE`, `PASS`, `WIP`, `TBD`, `partial`
are **forbidden** as final states for any Step on this plan
(per user directive 2026-09-20 and ADR-0074).

## Strict Validation Set (per Step, applied to every candidate)

Every Step on this plan, before it can be moved to `CERTIFIED`, must
pass **all** of the following in a single commit-and-verify round.

### 1. ADR / constitutional gates (mechanical)

- [ ] `when(stepName/stepKey)` is **absent** in
      `CanonicalDurableRunCoordinator` and `CanonicalNodeDispatcher`
      (fitness `Lfc2ConcreteBodyRoutingDebtFitnessTest`).
- [ ] No privileged core execution path bypassing the registry seam
      (fitness `Lfc2RegistryFamilyFitnessTest`).
- [ ] `StepSpec.RegistryStepSpec` is the only generic structural
      representation used (no concrete `StepSpec` subtype).
- [ ] Plugin handler declares capabilities == capabilities used
      (fitness `Lfc2DurableCoordinatorScopeFitnessTest`).
- [ ] No global cwd/env mutation (fitness `Lfc0GlobalStateFitnessTest`).

### 2. Step contract gates (STEP_PLUGIN_CERTIFICATION.md R2..R4)

The 19 dimensions C01..C19 must all be green:

```text
C01  contract/descriptor
C02  input codec round-trip
C03  output codec round-trip
C04  positive DSL compile (real .pipeline.kts compiles)
C05  negative DSL compile (invalid input rejected at compile-time)
C06  canonical IR (StepNode/RegistryStepSpec)
C07  registry resolution (key -> StepDefinition)
C08  capability admission (declared == used; missing = fail-closed)
C09  handler success (typed Outcome, no Map<String, Any?>)
C10  typed failure (typed Result, never exception-as-control-flow)
C11  observability (typed DomainEvent per Step family)
C12  cancellation (typed, not CancellationException-as-failure)
C13  replay (ReplayPolicy declared + verified)
C14  body contract (for block steps; BodyExecutionPolicy shape)
C15  credentials/security (no argv secrets, credential capability declared)
C16  real distribution (installDist + binary runs the real fixture)
C17  Jenkins compatibility (per JENKINS_FAMILIARITY_CATALOG.md)
C18  executable scenario (real .pipeline.kts, not unit test)
C19  zero production changes for external plugin path
```

### 3. Fitness test suite (architecture-tests module)

Each candidate Step must, at minimum, leave these green:

- `Lfc2RegistryFamilyFitnessTest`
- `Lfc2DurableCoordinatorScopeFitnessTest`
- `Lfc2ConcreteBodyRoutingDebtFitnessTest`
- `Lfc2BodyExecutionPolicyFitnessTest`
- `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest`
- `Lfc2B11ExternalScopedRoutingDefenseFitnessTest`
- `S3<Name>LegacyRemovedFitnessTest` (one per Step)
- `LegacyResidualConvergenceFitnessTest`

### 4. StepContractSuite (one per Step, 16/17 dimensions minimum)

Already-canonical template at `EchoStepContractSuiteTest` /
`ShStepContractSuiteTest`. Required rows:

```text
identity
contract completeness
codec input round-trip
codec output round-trip
canonical envelope
registry resolution
capability admission (fail-closed if missing)
handler success
typed failure
fresh durable execution
replay reuse
divergence detection
observability (typed event)
missing capability (rejection, fail-closed)
architecture fitness
real DSL scenario (.pipeline.kts in v2/compatibility/)
```

### 5. installDist + real installed binary (G8 evidence)

Not optional:

```text
installDist : ./gradlew -p v2 :pipeline-application:installDist
binary      : v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
scenario    : real fixture in v2/compatibility/NN-<step>.pipeline.kts
db/control  : SAME for fresh and replay runs
sha256      : of the binary + artifact fingerprint in the receipt
```

### 6. Receipt (per WU)

A new file under `docs/v2/07-uat/S2_<name>_G<0..8>_*.md` (or
`LFC2E<XY>_<name>_G<gate>_*.md` for new families) with: argv, exit code,
XML counters (`failures="0" errors="0"`), SHA-256 of install artifacts,
real fixture run output, and a final `Status: CERTIFIED` (never
`DONE/PASS`/`IMPLEMENTED_UNCERTIFIED`).

## Next Step in queue (binding order)

The order below is binding. Do not skip ahead without a prior WU
that explicitly re-orders the queue.

### Tier A — CORE pending (3 WUs + 1 horizontal blocker)

| Order | Step | State on 2026-09-20 | WU action | WU estimate |
|---|---|---|---|---|
| 1 | `core.writeFile` (formal contract test) | CERTIFIED (G8 done); StepContractSuiteTest missing | Author `WriteFileStepContractSuiteTest` covering the 16/17 standard rows; refixture `v2/compatibility/03-write-file.pipeline.kts` if needed; G6 receipt | 1 WU |
| 2 | `core.waitUntil` | G5 done; G6+G8 pending | Author `WaitUntilStepContractSuiteTest`; G6 contract + G8 installDist with real `v2/compatibility/22-wait-until.pipeline.kts` replay | 1-2 WUs |
| 3 | `core.pwdTmp` | no G6/G8 | G6 contract + G8 installDist. **Depends on LFC-2R2** (the structured DSL runtime-return gap also affects `pwdTmp`) — start work in parallel, but the G8 evidence will need the same fix as `pwd` | 1-2 WUs (depends on LFC-2R2) |
| 4 | `core.pwd` | **BLOCKED (STRUCTURED_DSL_RUNTIME_RETURN_GAP)** | Cannot close without LFC-2R2 (horizontal scope). See blocker section. | N/A — separate scope |

#### Tier A.1 — Horizontal blocker (separate scope: LFC-2R2)

```text
STRUCTURED_DSL_RUNTIME_RETURN_GAP

Symptom : A Step executed by the runtime cannot return a typed value into
          the Kotlin frame that built a PipelineSpec.
Affects : pwd, pwd(tmp), readFile, fileExists, plus any future
          runtime-returning Step family.
Source  : docs/v2/07-uat/S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md

Disposition: LFC-2R2 — Structured Runtime-Returning Steps
            (initial consumers: pwd(), pwd(tmp=true), readFile(),
            fileExists(); architectural references: isUnix /
            sh(returnStdout) generator-level seams).
```

LFC-2R2 is a separate change, not part of LPR. Its scope, design and
G0..G8 plan are TBD in that cycle.

### Tier B — CORE next gate (7 Steps)

For each, the burn-down is:
G0 baseline → G1 registry seam → G2 corpus → G3 REGISTRY_PRIMARY →
G4 LEGACY_UNREACHABLE → G5 LEGACY_REMOVED → G6 contract → G7 contract
suite → G8 installDist → receipt → CERTIFIED.

| Order | Step | Genericidad | WU estimate | Notes |
|---|---|---|---|---|
| 5 | `junit.results` | universal XML | 1-2 WUs | in `pipeline-step-sdk/junit`; full burn-down to CERTIFIED |
| 6 | `stash` | universal mem-between-stages | 1 WU | needs RunRef/ResourceRef typed model; durable local storage |
| 7 | `unstash` | universal (pair of stash) | 1 WU | paired with `stash` |
| 8 | `publishHTML` | universal HTML | 1 WU | generic; NOT analyzer-specific |
| 9 | `lock` | universal resource lock | 1-2 WUs | local lock manager (lockable-resources pattern, neutralized) |
| 10 | `input` | manual approval | 1-2 WUs | CLI first; remote UI later |
| 11 | `httpRequest` | universal HTTP | 1-2 WUs | typed response, credential capability, retry/cancel |

### Tier C — CORE optional (only on demand)

| Order | Step | Trigger |
|---|---|---|
| 12 | `readTOML` / `writeTOML` | on demand (Pyproject / Cargo workflows) |
| 13 | `tar` / `untar` | on demand (paridad con zip) |

### Tier D — REJECTED (NEVER in core; vendor / external / derivable)

These Steps are explicitly rejected from core. Implementation, if ever
demanded, goes through (a) `sh "..."` for derivable cases, or (b) a
third-party plugin external to the core SDK.

| Step | Reason for rejection |
|------|----------------------|
| `tool`, `withMaven`, Maven helpers | tool-specific (Maven); users can `sh "mvn ..."` |
| Gradle toolchain/wrapper helpers | tool-specific |
| NodeJS / npm / pnpm / yarn | tool-specific; users can `sh "npm ..."` |
| Python / pip / Poetry | tool-specific; users can `sh "pip ..."` |
| .NET | tool-specific |
| Go | tool-specific |
| Config File Provider | Jenkins admin-only idiom; no portable use |
| `docker.build/push/pull/inside/withRun` | tool-specific (Docker); plugin external |
| `kubernetes.podTemplate/container` | tool-specific + REMOTE-only (M5) |
| `slackSend` | vendor-specific (Slack); plugin external |
| `emailext` | vendor-specific (Jenkins extension); plugin external |
| `mail` (simple) | low value + covered by `emailext` plugin if needed |
| `sshagent` | Jenkins-idiom with credential helper; derivable with `withCredentials` + `sh` |
| SSH command/get/put/remove | derivable with `withCredentials` + `sh`; plugin external if demanded |
| `copyArtifacts` | Jenkins cross-job idiom; not portable; not local-first |
| `build(job:)` | controller/REMOTE-only (M4/M5) |
| `waitForBuild` | controller/REMOTE-only |
| `properties` | controller/job mutation; REMOTE-only |
| `timestamps` | output decorator, cosmetic; plugin external `timestamper` |
| `ansiColor` | output decorator, cosmetic; plugin external |
| `git` (advanced flags: depth, submodules, LFS, sparse) | CLI-git flags; derivable with `git --depth` in `sh` |
| `readScmFile` | derivable with `readFile` |
| SVN / Mercurial | niche SCM; DEFER until demanded |
| `md5` / `sha1` | legacy/weak hash; security risk; sha256 already exists |
| `readManifest` | Java/Maven-only format; niche |
| `compareVersions` | not generic; niche |
| `checksum verify variants` | niche; sha256 + sh covers it |
| `touch` / `prependToFile` / `tee` | derivable with `sh` + `writeFile` |
| Coverage (JaCoCo/Cobertura/LCOV/OpenCover) | tool-specific formats; plugin external |
| `recordIssues` / static-analysis reports | tool-specific; plugin external |
| Maven POM read/write | tool-specific; plugin external |

### Tier E — EXTERNAL (responsibility of third parties / vendors)

The core SDK is enough. The team will NOT implement these.

| Family | Vendor responsible |
|--------|--------------------|
| Artifactory / Xray | JFrog |
| SonarQube | SonarSource |
| Vault credential bindings | HashiCorp |
| AWS, Azure, GCP | respective cloud providers |

## Cycle ledger convention

Each WU writes:

```text
WU-LPR-<NNN>  Step:  <step_key>
              Gate:  G<0..8>
              Args:  <argv>
              Exit:  <code>
              XML:   <failures errors tests>
              SHA:   <binary sha256>
              Status: CERTIFIED | REJECTED with reason: <one-line>
```

The WU number `NNN` is taken from the LPR cycle counter in
`docs/v2/05-roadmap/LPR_WORK_UNITS.md`. Each new WU appends to the
ledger; receipts live under `docs/v2/07-uat/`.

## Out-of-scope reminders

- Plugin marketplace, signing, hot-reload, remote repository — all
  explicitly REJECTED as future scope per AGENTS.md "Explicitly out of
  scope".
- Cedar policy binding — LFC-2E3 future; data shape is already frozen
  per `STEP_ECOSYSTEM_MATRIX.md §Provider / Release / Policy-surface
  columns`.
- M4 (controller/remote) — DEFERRED until local-first freeze.

## Updating this document

This document becomes stale by construction every time a Step reaches
CERTIFIED or REJECTED. Re-generate (or at least re-sync the relevant
Tier) when:

- A Step reaches CERTIFIED → move it out of the "Next Step in queue"
  table to the historical record (and update
  `STEP_INVENTORY_LFC2E0.md`).
- A Step reaches REJECTED → append it to Tier D with the new reason.
- A new Step is added to the plan (Tier B or C) → append it with its
  WU estimate and notes.
- A new Tier D rejection is recorded (e.g. user says "no React" for
  Step XYZ) → append with reason.

The `STEP_INVENTORY_LFC2E0.md` machine-derived table is the canonical
counter; this plan is the canonical roadmap.
