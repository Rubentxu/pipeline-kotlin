# Change: lfc2-step-ecosystem-depuration-2026-09-20

## Why

`STEP_ECOSYSTEM_MATRIX.md` y `STEP_INVENTORY_LFC2E0.md` están stale respecto al
estado real del código (2026-09-11 vs 2026-09-20). La matriz original proponía
~25 Steps universales, varias familias de "testing/reports", "toolchains",
"containers", "HTTP/SSH/notifications" y "external reference plugins" sin
discriminar **utilidad real** ni **genericidad**.

El usuario (RWAL — 2026-09-20) pidió una auditoría honesta con criterios
estrictos:

1. **Genericidad**: ¿expresa una primitiva universal (filesystem, network,
   process, time, value) o es un wrapper de un tool específico?
2. **Utilidad**: ¿se usa en pipelines no-Jenkins (GitHub Actions, GitLab CI,
   Buildkite, Concourse)? Si solo Jenkins lo usa, es compat-only.
3. **Tool-specific**: nada de Maven/npm/yarn/pip/dotnet/Go/docker como
   primitivas core — son plugins externos o DEFERRED.
4. **Certificación obligatoria**: cada Step propuesta debe pasar el ciclo
   completo G0..G8 (ADR-0074 + STEP_PLUGIN_CERTIFICATION.md §R1..§R4) con
   todas las fitness y validaciones. Nunca `DONE/PASS` para un uncertified
   Step. Nunca `IMPLEMENTED_UNCERTIFIED` ni `LEGACY_IMPLEMENTED_UNCERTIFIED`
   como estado final aceptado.

El objetivo de este change es **depurar** la matriz a una lista cerrada de
Steps universales y genéricos que el equipo core va a certificar, declarar
explícitamente los rechazos con su razón, y dejar a los vendors como
referencias externas — sin que nadie tenga que re-debatir esto en cada
ciclo.

## Outcomes

1. `STEP_ECOSYSTEM_MATRIX.md` corregido con la lista depurada (CORE
   pendientes + CORE próximos + OPCIONALES + RECHAZADOS + EXTERNAL) y una
   sección explícita "**REJECTED**" con razón por Step.
2. `STEP_INVENTORY_LFC2E0.md` regenerado (machine-derived) reflejando el
   estado real 2026-09-20: 17 CoreSteps en registry, LEGACY_PLUGIN_IDS vacío,
   conteo de G8-certificados vs G6-only vs contract-only.
3. `STEP_REGISTRY_PLAN.md` nuevo: hoja de ruta operacional con el orden de
   burn-down G0..G8 de cada Step pendiente + lista de fitness/validaciones
   que debe pasar cada uno.
4. `LFC2_STEP_ECOSYSTEM_EXPANSION.md` actualizado con la lista depurada en
   lugar de la matriz ambigua previa.
5. **Regla constitucional explícita**: ningún Step propuesto puede ser
   registrado ni certificado sin pasar las 19 dimensiones C01..C19 de
   `STEP_PLUGIN_CERTIFICATION.md` + los fitness relevantes +
   `StepContractSuite` + `installDist` con real fixture + replay
   verification.

## Non-goals

- Implementar nuevos Steps (este change es solo spec/planning).
- Modificar `PipelineDsl.kt`, `Core*Step.kt`, `Canonical*NodeDispatcher.kt`.
- Modificar ADRs.
- Tocar el ciclo LPR actual (que ya cerró LPR-074..081 verde).
- Implementar los Steps rechazados (vendor/tool-specific — son
  responsabilidad de terceros).

## Strict Certification Law (re-asserted)

Per `STEP_PLUGIN_CERTIFICATION.md` §R1 and AGENTS.md "Step is done only when
CERTIFIED":

```text
States:    DESIGNED → IMPLEMENTED_UNCERTIFIED → CERTIFIED
Orthogonal: QUARANTINED, RETIRED
NEVER DONE/PASS for an uncertified Step.
NEVER IMPLEMENTED_UNCERTIFIED as final state in this plan.
```

This change **forbids** the following as a final state for any Step
proposed in the depurated list:

- `DONE`, `PASS`, `IMPLEMENTED_UNCERTIFIED`,
  `LEGACY_IMPLEMENTED_UNCERTIFIED`, `WIP`, `TBD`, `partial`.

A Step must reach **`CERTIFIED`** through the full G0..G8 burn-down OR be
**REJECTED** with an explicit reason and moved out of the plan.

## Strict Validation Set (per Step, applied to every candidate)

Every Step in the depurated list, before it can be moved to `CERTIFIED`,
must pass the following in a single commit-and-verify round:

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

The 19 dimensions C01..C19:

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
- `S3<Name>LegacyRemovedFitnessTest` (one per Step; per AGENTS.md
  "S3/S4 are the canonical implementation of MUST NOT enforcement")
- `LegacyResidualConvergenceFitnessTest`

### 4. StepContractSuite (one per Step, 16/17 dimensions minimum)

Already-canonical template at
`EchoStepContractSuiteTest`/`ShStepContractSuiteTest`. Required rows:

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

Not optional. Per STEP_PLUGIN_CERTIFICATION.md §R3 "real distribution":

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
real fixture run output, and a final `Status: CERTIFIED` or
`Status: REJECTED with reason: <one-line>`.

## Depurated list (CORE — implement in upcoming cycles)

These are the Steps the team will burn-down G0..G8 in upcoming cycles.
Order is binding; do not skip ahead without prior WU.

### Tier A — CORE pending G8 (close immediately, 6-7 WUs)

| # | Step key | Current state | Required action | WU estimate |
|---|----------|---------------|-----------------|-------------|
| 1 | `core.emit.event` | registry + G6 + contract | G8: real installDist + replay | 1 WU |
| 2 | `core.pwd` | registry + G6 + contract | G8: real installDist + replay | 1 WU |
| 3 | `core.cleanWs` | registry + G6 + contract | G8: real installDist + replay | 1 WU |
| 4 | `core.archiveArtifacts` | registry + G6 + contract | G8: real installDist + replay | 1 WU |
| 5 | `core.waitUntil` | registry + G5 (no contract) | G6 contract + G8 installDist | 1-2 WUs |
| 6 | `core.pwdTmp` | registry, no G6/G8 | G6 contract + G8 installDist | 1-2 WUs |
| 7 | `core.writeFile` (formal contract test) | registry + G8 | add `StepContractSuiteTest` | 1 WU |

### Tier B — CORE next gate (genéricos universales, 7 Steps)

| # | Step key | Genericidad | WU estimate |
|---|----------|-------------|-------------|
| 8 | `junit.results` (full burn-down to CERTIFIED) | universal XML | 1-2 WUs |
| 9 | `publishHTML` | universal HTML | 1 WU |
| 10 | `stash` | universal mem-between-stages | 1 WU |
| 11 | `unstash` | universal (pair of stash) | 1 WU |
| 12 | `lock` | universal resource lock | 1-2 WUs |
| 13 | `input` | manual approval | 1-2 WUs |
| 14 | `httpRequest` | universal HTTP | 1-2 WUs |

### Tier C — CORE optional (low priority, on demand)

| # | Step key | Reason optional |
|---|----------|-----------------|
| 15 | `readTOML` / `writeTOML` | formato moderno (Pyproject/Cargo); demand-driven |
| 16 | `tar` / `untar` | paridad con zip; demand-driven |

### Tier D — REJECTED (NEVER do in core; vendor/external/derivable)

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

These are vendor plugins; the core SDK is enough. The team will NOT
implement them.

| Family | Vendor responsible |
|--------|--------------------|
| Artifactory / Xray | JFrog |
| SonarQube | SonarSource |
| Vault credential bindings | HashiCorp |
| AWS, Azure, GCP | respective cloud providers |

## Success

- [ ] `STEP_ECOSYSTEM_MATRIX.md` updated with the depurated list and
      explicit REJECTED section with reasons per Step.
- [ ] `STEP_INVENTORY_LFC2E0.md` regenerated machine-derived reflecting
      2026-09-20 real state (registry=17, LEGACY=0, G8=8, G6-only=4,
      contract-only=3, missing=2).
- [ ] `STEP_REGISTRY_PLAN.md` created with per-Step G0..G8 plan +
      validation set per Step.
- [ ] `LFC2_STEP_ECOSYSTEM_EXPANSION.md` updated with the depurated list.
- [ ] No production code change.
- [ ] WU-LPR-082 receipt with argv, exit code, diff summary.
- [ ] PR opened; merge only after L5 round gate green (or L4 if no
      production change).

## Closure

- [ ] WU-LPR-082 receipt with argv/exit/digest committed.
- [ ] Push to `main`.
- [ ] Tag `wu-lpr-082` published.
- [ ] No `IMPLEMENTED_UNCERTIFIED` / `LEGACY_IMPLEMENTED_UNCERTIFIED` /
      `DONE`/`PASS` states appear in the depurated list.

## Constraint reminder

- V2 Prime Directive §3: classify + quarantine, not V2 dependency on
  legacy. The legacy path is empty (`LEGACY_PLUGIN_IDS = {}`); the depurated
  list keeps it that way.
- AGENTS.md user law #7: NO `when(stepName)` cases; NO step-specific
  coordinator logic; each new Step uses the registry seam with at least 2
  consumer proofs (one contract suite; one real `.pipeline.kts`).
- Per AGENTS.md "Burn-down sequence template G0..G8": no slice may invent a
  different shape. If a future Step needs a new gate, propose the addition
  in an ADR first.
