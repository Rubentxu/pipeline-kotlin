# Step Certification Matrix — roll-up

**Source of truth:** `docs/v2/status/step-certification.yaml` (machine-readable)
**Last updated:** 2026-09-17 (LFC-2E0 GLOBAL CLOSURE — FASE 3 of the SDDK cycle)

This file is the human-readable rollup of the canonical machine-readable source.
If this file and the YAML disagree, the YAML wins. Any drift must be filed as
a FASE 3 / drift fix and resolved before LFC-2E0 closure.

## Counter roll-up

```text
Production Step keys total:        16
  CERTIFIED (core):                12   (echo, sh, error, sleep, file.writeFile,
                                       emit.event, isUnix, deleteDir, milestone,
                                       cleanWs, archiveArtifacts, waitUntil)
  CERTIFIED (external plugin):      2   (example.uppercase, utilities.readJSON,
                                       utilities.writeJSON, utilities.sha256)
                                       NOTE: utilities family contributes 3 StepKeys
                                       under 1 OFFICIAL_PLUGIN (`pipeline.utilities.json@1.0.0`)
  STOPPED at G7 (CERTIFICATION not reached): 2  (pwd, pwd.tmp — non-deterministic runtime return)
  REJECTED:                         1   (load — CORE-LOAD-REJECTED 2026-09-17)

DSL extension functions declared: ~67 (PipelineDsl.kt L990-1900)
  removed at this slice: load(path)  (CORE-LOAD-REJECTED)

Real .pipeline.kts fixtures: 23 (01..22, gaps 07/99 removed; v0.33.1 added 16-22;
                                       v0.34 added examples/utilities/01-json-roundtrip.pipeline.kts)
Event Harness contracts: 4 (07, 08, 09, 10)
CERTIFIED Steps with real maintained fixture: 12/12 (100%)
CERTIFIED external plugin Steps with real maintained fixture: 2/2 (100%)
STOPPED Steps with real fixture: 2/2 (100%)
REJECTED Steps with no fixture: 1/1 (intentional — load was never functional)

## Expansion slices (LFC-2E2-EXPANSION cycle)

```text
U0  GATE FITNESS         14 → 15 tests (capabilityAccessFactory + plugin lifecycle + isolation)
U1  JSON HARDENING       +5 typed-failure rows in ContractSuite
    UtilitiesJsonError sealed ADT (3 cases): JsonNotFound / JsonParseFailure / JsonIoFailure
    UtilitiesJsonException typed carrier; production core refs: 0
```

## Legacy residual

```text
LEGACY_PLUGIN_IDS:                  0   ← FIRST ZERO LEGACY RESIDUAL
CanonicalCoreStepMetadata rows:     0
Canonical*NodeDispatcher.kt files:  0
CanonicalCoreStepCommand subtypes:  0
```

## Matrix (machine-verifiable per row)

| Step key | Delivery | Execution | Legacy State | Cert State | G8/G7 Receipt | Real Fixture | Capability |
|---|---|---|---|---|---|---|---|
| `core.echo` | CORE | REGISTRY_PRIMARY | CERTIFIED + LEGACY_REMOVED | CERTIFIED | [G8](CORE_ECHO_CERTIFICATION.md) | 01..09 (7 fixtures) | (atomic) |
| `core.sh` | CORE | REGISTRY_PRIMARY | CERTIFIED + LEGACY_REMOVED | CERTIFIED | [G8](LB02_S6_BURN_DOWN_AND_CERTIFICATION.md) | 03, 04, 06, 07, 08, 09, 10 | `SHELL_OPERATIONS_CAPABILITY` |
| `core.error` | CORE | REGISTRY_PRIMARY | CERTIFIED + LEGACY_REMOVED | CERTIFIED | [G8](S2_A1_CORE_ERROR_G8_FINAL_CERTIFICATION_RECEIPT.md) | 05, 15 | (atomic) |
| `core.sleep` | CORE | REGISTRY_PRIMARY | CERTIFIED + LEGACY_REMOVED | CERTIFIED | [G8](S2_A2_CORE_SLEEP_G8_FINAL_CERTIFICATION_RECEIPT.md) | 16 | (atomic) |
| `core.file.writeFile` | CORE | REGISTRY_PRIMARY | CERTIFIED + LEGACY_REMOVED | CERTIFIED | [G8](S2_A3_CORE_WRITEFILE_G8_FINAL_CERTIFICATION_RECEIPT.md) | 17 | `WORKSPACE_OPERATIONS_CAPABILITY` |
| `core.emit.event` | CORE | REGISTRY_PRIMARY | CERTIFIED + LEGACY_REMOVED | CERTIFIED | [G8](S2_A4_CORE_EMITEVENT_G8_FINAL_CERTIFICATION_RECEIPT.md) | 12 | `EVENT_SINK_CAPABILITY` |
| `core.isUnix` | CORE | REGISTRY_PRIMARY | CERTIFIED + LEGACY_REMOVED | CERTIFIED | [G8](S2_A5_CORE_ISUNIX_G8_FINAL_CERTIFICATION_RECEIPT.md) | 13, 19 | `PLATFORM_IDENTITY_CAPABILITY` |
| `core.pwd` | CORE | REGISTRY_PRIMARY | CERTIFIED + LEGACY_REMOVED | STOPPED_G7 | [G7-BLOCKED](S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md) | 20 | `PLATFORM_IDENTITY_CAPABILITY` |
| `core.pwd.tmp` | CORE | REGISTRY_PRIMARY | CERTIFIED + LEGACY_REMOVED | STOPPED_G7 | [G7-BLOCKED](S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md) | 20 | `PLATFORM_IDENTITY_CAPABILITY` |
| `core.deleteDir` | CORE | REGISTRY_PRIMARY | CERTIFIED + LEGACY_REMOVED | CERTIFIED | [G8](S2_A7_CORE_DELETEDIR_G8_CERTIFICATION_RECEIPT.md) | 11 | `DELETE_DIR_OPERATIONS_CAPABILITY` |
| `core.milestone` | CORE | REGISTRY_PRIMARY | CERTIFIED + LEGACY_REMOVED | CERTIFIED | [G8](S2_A9_CORE_MILESTONE_G8_CERTIFICATION_RECEIPT.md) | 12, 21 | `EVENT_SINK_CAPABILITY` + `MILESTONE_OPERATIONS_CAPABILITY` |
| `core.cleanWs` | OFFICIAL_PLUGIN_CANDIDATE | REGISTRY_PRIMARY | CERTIFIED + LEGACY_REMOVED | CERTIFIED | [G8](S2_A10_CORE_CLEANWS_G8_CERTIFICATION_RECEIPT.md) | 18 | `CLEAN_WS_OPERATIONS_CAPABILITY` |
| `core.archiveArtifacts` | CORE | REGISTRY_PRIMARY | CERTIFIED + LEGACY_REMOVED | CERTIFIED | [G8](S2_B10_ARCHIVEARTIFACTS_G8_CERTIFICATION_RECEIPT.md) | 10 | `ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY` |
| `core.waitUntil` | CORE | ORCHESTRATION | CERTIFIED + LEGACY_REMOVED | CERTIFIED | [G8](S2_A8_CORE_WAITUNTIL_WU_G5B_LEGACY_REMOVED_RECEIPT.md) | 13, 22 | `EVENT_SINK_CAPABILITY` |
| `core.load` | **REJECTED** | (removed) | REMOVED | **REJECTED** | [REJECTION](S2_A5_CORE_LOAD_REJECTION_RECEIPT.md) | (none) | n/a |
| `example.uppercase` | EXTERNAL_REFERENCE | REGISTRY_PRIMARY_VIA_SERVICELOADER | NO_LEGACY_PATH | CERTIFIED | [G8](LB02_EP_EXAMPLE_UPPERCASE_CERTIFICATION.md) | (none — external) | (atomic) |

## Invariants (machine-verifiable, must remain PASS)

| Invariant | Status | Notes |
|---|---|---|
| `LEGACY_PLUGIN_IDS` is empty | **PASS** | 0/0/0 — FIRST ZERO LEGACY RESIDUAL |
| `CanonicalCoreStepMetadata.pluginIds` is empty | **PASS** | All legacy rows removed |
| `Canonical*NodeDispatcher.kt` files in `main` are empty | **PASS** | All legacy dispatcher files deleted |
| `CanonicalCoreStepCommand` sealed subtypes are empty | **PASS** | No legacy constructors |
| Every CERTIFIED Step has a G8 receipt | **PASS** | 12/12 core + 1/1 external |
| Every STOPPED_G7 Step has a G7 STOP_BLOCKED receipt | **PASS** | 2/2 (pwd, pwd.tmp) |
| Every REJECTED Step has a rejection receipt | **PASS** | 1/1 (load) |
| No CERTIFIED Step lacks a maintained real fixture | **PASS** | 12/12 |
| No UNCERTAIN surface in the production source | **PASS** | All Step keys classified |

## Mandatory disabled acceptance tests

| Test | Status | Reason |
|---|---|---|
| `UatLocal011WorkflowControlTest::SC-011-11` | `@Disabled` (OBSOLETE-per-REJECTION) | `core.load` is REJECTED; the DSL function was physically deleted. Re-enabling the test would not even compile. Reclassified from INC-024 "deferred" to OBSOLETE per this slice's CORE-LOAD-REJECTED decision. |
| `UatLocal008CredentialsTest::1148` | `@Disabled` (pre-existing) | DSL classpath: `CredentialsId` not accessible in `.pipeline.kts` scripts. Pre-existing on `c88d5c88` v0.32.2; unrelated to legacy burn-down. Open and tracked outside LFC-2E0 scope. |

The remaining `@Disabled` annotations in the test suite are historical
**counter snapshots preserved for traceability** (e.g. `counters drop to 6-6-6
post-S2-A6-G5`) that document intermediate states in the burn-down
progression. They are NOT "mandatory disabled acceptance tests" — they are
frozen historical artifacts superseded by current-state tests, per the
`@Disabled` KDoc conventions.

## Out of scope

- **`retry/timeout` migration** — old worktree, deferred; per cycle directive
  "no tocar ahora".
- **EVT-4+** — deferred events; not blocking LFC-2E0 closure.
- **M4/M5/M6 / Kubernetes workers / Jenkins adapter** — outside LFC-2E0 scope.

## See also

- `docs/v2/status/step-certification.yaml` — machine-readable source of truth
- `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` — full inventory with row-level citations
- `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md` — planning hypothesis, corrected against this table
- `docs/v2/07-uat/LEGACY_RESIDUAL_LEDGER.md` — burn-down counter history
- `docs/v2/07-uat/S2_A5_CORE_LOAD_REJECTION_RECEIPT.md` — `core.load` REJECTED classification
