# Tasks: lfc2-step-ecosystem-expansion

## LFC-2E0 (this cycle)
- [x] enumerate every production Step key from LEGACY_PLUGIN_IDS + registry + ServiceLoader (`CanonicalCoreStepDecoder.kt`, `CoreStepRegistryFactory.kt`, `examples/example-uppercase-plugin`)
- [x] for each Step key, gather the 12 fields (delivery, path, dsl, def, canonical, legacy, typed I/O, capabilities, replay, example, EH contract, certification) with file:line citations
- [x] write `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` (machine-derived table, 14 production keys + 1 external)
- [x] correct `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md`:
  - add `LEGACY_IMPLEMENTED_UNCERTIFIED` state legend
  - add certification snapshot at top (3 CERTIFIED, 12 LEGACY, ...)
  - mark 12 legacy keys as LEGACY_IMPLEMENTED_UNCERTIFIED (not IMPLEMENTED_UNCERTIFIED)
  - separate block / orchestration DSL from Step keys
  - correct SCM family rows (`git`, `checkout`: NOT_STARTED — no `StepDefinition` exists)
  - rewrite family certification progression to E0..E10 ladder anchored to inventory
- [x] write `openspec/changes/lfc2-step-ecosystem-expansion/proposal.md`
- [x] write `openspec/changes/lfc2-step-ecosystem-expansion/design.md`
- [x] write this tasks.md
- [ ] open PR (do NOT merge to main yet — LFC-2E0 is a cycle handoff, not a merge event)

## LFC-2E1 (next cycle, NOT in this PR)

> **LB-01 ordering:** `CERTIFIED` requires `LEGACY_REMOVED` (per
> `openspec/changes/lfc2-step-constitution-plugin-seam/LEGACY_BURNDOWN_POLICY.md`).
> The first E1 slice addresses LB-02's remaining reachable legacy fixtures,
> NOT a fresh burn-down. Then E1 starts the burn-down of the 12 legacy keys.

### LFC-2E1-S1 — LB-02 LEGACY_REMOVED slice (FIRST)

- [ ] inventory legacy `core.echo` machinery still reachable in production:
  `LegacyEchoUnreachableProofTest`, `EchoDurableSpineTest`, `UatStep002EchoCaptureTest`,
  `CoreEchoSeamTest`, `EchoStepContractSuiteTest` (live in
  `v2/pipeline-application/src/test/kotlin/.../`)
- [ ] refixture each legacy test to drive `core.echo` exclusively through the
  registry seam (no legacy decoder, no legacy metadata row)
- [ ] activate the `S3EchoLegacyRemovedFitnessTest` fitness guard with strict
  forbidden-pattern assertions
- [ ] run `S3EchoLegacyRemovedFitnessTest` and confirm it fails closed on any
  reintroduction of legacy Echo path
- [ ] record `LEGACY_REMOVED` in the LB-01 burn-down ledger (do NOT re-record
  `CERTIFIED`; that was the prior cycle's outcome)
- [ ] capture a small LB-02 LEGACY_REMOVED receipt under `docs/v2/07-uat/`
  (or amend `S3_ECHO_BURNDOWN_CERTIFICATION.md` to note the fitness guard is now
  active)

### LFC-2E1-S2 — universal core freeze (after LB-02 slice)

Burn down 12 legacy keys onto the registry seam following AGENTS.md G0..G8.
Per the inventory priority:

P0 first:
- [ ] `core.error` — burn-down to registry (G0 baseline → G8 CERTIFIED)
- [ ] `core.sleep` — burn-down to registry
- [ ] `core.pwd` — burn-down to registry (must produce typed String value)
- [ ] `core.isUnix` — burn-down to registry (must produce typed Boolean)

P1 second:
- [ ] `core.deleteDir` — burn-down to registry
- [ ] `core.cleanWs` — burn-down to registry
- [ ] `core.waitUntil` — burn-down to registry

P2 third:
- [ ] `core.milestone` — burn-down to registry
- [ ] `core.load` — burn-down to registry
- [ ] `core.archiveArtifacts` — burn-down to registry
- [ ] `core.emit.event` — burn-down to registry
- [ ] `core.file.writeFile` — burn-down to registry

Each burn-down must follow the AGENTS.md template:
```text
G0 baseline / pre-existing failures:
  Fresh canary on pre-apply SHA; reproduce every pre-existing failure with base SHA + SHA-256
  logs. Persist evidence under docs/v2/07-uat/.
G1 registry seam proof:
  Implement Core<Name>Step behind the registry, keeping the legacy decode/dispatch path
  intact. PROVE the registry path is correct (handler + contract + codecs + capabilities).
G2 corpus migration:
  Migrate the durable characterisation/characterization corpus to drive the Step through
  the registry so the same fingerprint/op-journal is exercised by both paths.
G3 REGISTRY_PRIMARY:
  Flip the production wiring to the registry (CoreStepRegistryFactory contains the Step;
  coordinator's stepRegistry is the production factory). Both paths still exist on paper.
G4 LEGACY_UNREACHABLE:
  Remove the legacy execution path source-of-truth (canonical command data class, decoder
  branch, dispatcher case, metadata table row). Prove via fitness that classify() routes
  the key as Registry on every production wiring.
G5 LEGACY_REMOVED:
  Mechanical fitness: source-level absence of all THREE legacy forms (decoder, dispatcher,
  registration). Distinct from LEGACY_UNREACHABLE (runtime property); LEGACY_REMOVED is a
  static source property and is what we mean by "removed".
G6 architecture fitness:
  Run the L4/L5 architecture fitness against the new path; the Lfc2RegistryFamilyFitness
  suite must remain green and now reference the renamed LEGACY_PLUGIN_IDS.
G7 StepContractSuite:
  16/17 coverage: identity, contract completeness, codec input, codec output, canonical
  envelope, registry resolution, capability admission, success, typed failure, fresh
  durable, replay, divergence, observability, missing capability, architecture fitness,
  real DSL scenario (pipeline { stages { stage("...") { steps { <step>(...) } } } }).
G8 CERTIFIED:
  Update the per-Step state in the burn-down ledger. Anything not yet CERTIFIED must be
  reported as IMPLEMENTED_UNCERTIFIED with an exact gap description. Never record DONE/PASS
  for an uncertified Step (ADR-0074).
```

## LFC-2E2..E10 (further cycles, sequenced later)

- E2 utilities plugin (filesystem + typed deterministic values)
- E3 testing/reports plugin (junit, publishHTML, coverage)
- E4 artifacts/stash (cleanWs promoted, archiveArtifacts review)
- E5 toolchains/config (typed JDK/Toolchain matrix)
- E6 HTTP/SSH/notifications (network + credentials + typed response)
- E7 lock/input (fileLock, input step)
- E8 Docker/Podman (process wrapper + image registry credentials)
- E9 advanced Git (LFS, sparse, submodules)
- E10 complex external reference (Artifactory / SonarQube / Vault)

## Closure

- [ ] architecture fitness on final LFC-2E10 inventory (post-E10)
- [ ] real installDist examples acceptance (1..10 still GREEN)
- [ ] full round gate + Rule-16 exact baseline
- [ ] final receipt with argv/exit/digest
- [ ] update AGENTS only for laws proven by the completed cycles

## Constraint reminder

- EVT-4 must remain PENDING-DEFERRED-BY-LOCAL-FIRST-PRIORITY throughout E0..E10.
- M4 (controller/remote) stays DEFERRED until local-first feature freeze.
- Per AGENTS.md user law #7: NO `when(stepName)` cases; NO step-specific
  `CanonicalDurableRunCoordinator` logic; each new Step must use the registry seam
  with at least 2 consumer/regression proofs (one is the contract suite; another
  must be a real `.pipeline.kts` example).
