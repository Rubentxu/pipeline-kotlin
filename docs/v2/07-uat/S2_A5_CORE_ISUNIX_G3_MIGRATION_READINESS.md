# S2-A5 / G3 — `core.isUnix` Migration Readiness

**Cycle:** `lfc2-e1-s2-a5-g3-core-isunix-readiness`
**Branch:** `cycle/lfc2-e1-s2-a5-g3-core-isunix-readiness`
**Base:** `3cc4f7f3` (R4A → R4B wiring → R4B scope fix → R4B receipt → R4B closure → roadmap sync)
**Status:** READINESS PROVEN — STOP after G3 receipt; no G4 (authority flip) auto-progression.
**Date:** 2026-09-12T06:38Z

## Scope (user GO)

Pre-flip migration readiness only. Production routing unchanged. No
`LEGACY_PLUGIN_IDS` change. No legacy removal. STOP after this receipt; GO G4
requires explicit review of this evidence.

**Forbidden in this slice (asserted, no production change observed):**
- no `LEGACY_PLUGIN_IDS` change
- no `REGISTRY_PRIMARY` flip
- no legacy removal
- no DSL semantic redesign
- no `pwd` / `milestone` / other legacy key
- no G3-A4.2 ShellOperations
- no G4 (this receipt is the gate)

## G3 readiness claim — ten assertions

| # | Assertion | Evidence source | Status |
|---|---|---|---|
| 1 | candidate registration | `CoreStepRegistryFactory.kt:69` `CoreIsUnixStep.registerInto(this)` with G1 explanatory comment (lines 63–68) | ✅ PROVEN |
| 2 | `StructuralFamily = LegacyCore` | `StructuralStepFamily.kt:19` `data object LegacyCore`; resolver line 46 returns `LegacyCore` when registry-resolved key is in `LEGACY_PLUGIN_IDS` (line 30 rule) | ✅ PROVEN |
| 3 | canonical C2 classifier | `UnixPlatformClassifier.kt` (`pipeline-application`, frozen UNIX_LIKE set of 10 normalized names; total pure `Boolean`) | ✅ PROVEN |
| 4 | `PLATFORM_IDENTITY` capability/admission | `CoreIsUnixStep` declares `requiredCapabilities = setOf(PLATFORM_IDENTITY_CAPABILITY)`; `CoreIsUnixStepUnitTest` 18/0 covers admit/deny typed paths | ✅ PROVEN |
| 5 | typed output + durable replay law | `CoreIsUnixStepUnitTest` 18/0 includes fresh/replay/divergence/typed-output rows; `ReplayPolicy.NEVER` for `core.isUnix` is honored (no fabricated re-observation on resume) | ✅ PROVEN |
| 6 | LFC-2R production seam valid | `ScriptedIsUnixRuntimeTest` 13/0 (R4B-installed wiring) + `ScriptedRegistryInvokerTest` 10/0 (registry seam) — both still green post-cycle-base | ✅ PROVEN |
| 7 | legacy dispatcher/decoder still physically present | `CanonicalIsUnixNodeDispatcher.kt` (executable source on main) + `CanonicalCoreStepDecoder.kt` lines 86, 158, 163, 204, 270 (decoder branch + payload kind check for `core.isUnix`); `CanonicalIsUnixNodeDispatcherTest` 1/0 green | ✅ PROVEN |
| 8 | legacy still reachable | routing unchanged; `StructuralFamilyResolver` legacy-membership-wins rule keeps `LegacyCore`; `core.isUnix` still in `LEGACY_PLUGIN_IDS`; pre-existing `CoreLegacyStepMetadataResolverTest` 2/4 failures are on `core.sleep`, NOT `core.isUnix` (verified failure message: "No canonical core metadata registered for plugin 'core.sleep'") | ✅ PROVEN for `core.isUnix` (scope-bounded) |
| 9 | registry execution seam ready for authority flip | candidate registered, classifier proven, capability declared, replay law honored, legacy still reachable; all G4 prerequisites mechanically present | ✅ PROVEN |
| 10 | Rule-16 baseline not widened | fresh G3 execution produced 0 new failures for `core.isUnix`; pre-existing `core.sleep` metadata bug unchanged (proven: same failure message in current branch and in `50ffb299` cycle-base handoff) | ✅ PROVEN |

## Verification (fresh XML, this session, this branch)

| Suite | Module | Tests | Failures | Errors | XML timestamp | sha256 |
|---|---|---|---|---|---|---|
| `CoreIsUnixStepUnitTest` | pipeline-application | 18 | 0 | 0 | 2026-09-12T06:38:07.420Z | `e993d8de6f2da26dfac5c7a6ffff864f75a31efadef48af6c85ebaaca389d3ba` |
| `CanonicalIsUnixNodeDispatcherTest` | pipeline-application | 1 | 0 | 0 | 2026-09-12T06:38:07.661Z | `220b43582ce0f57df65827361e7cdb36827f4af1c8ee0c67be966f70715255f8` |
| `ScriptedIsUnixRuntimeTest` | pipeline-application | 13 | 0 | 0 | 2026-09-12T06:38:07.667Z | `38d24f876e375919eb00b087d114936b0c20ec6ccbfeb162ec298377f648fcfd` |
| `ScriptedRegistryInvokerTest` | pipeline-application | 10 | 0 | 0 | 2026-09-12T06:38:07.796Z | `c1a8df9581f4bd3f187601d74a93e60ff70c5d87321f27a0df59c1ab6d7bcbb7` |

Command (L1+L2 evidence, full rerun, canary discipline per AGENTS.md rule 25):

```bash
timeout 600 ./gradlew -p v2 :pipeline-application:test \
  --tests 'CoreIsUnixStepUnitTest' \
  --tests 'ScriptedIsUnixRuntimeTest' \
  --tests 'CanonicalIsUnixNodeDispatcherTest' \
  --tests 'ScriptedRegistryInvokerTest' \
  --rerun-tasks
```

Result: **BUILD SUCCESSFUL in 1m 2s, 42 actionable tasks: 42 executed** (full
rerun, not cached; fresh XML written; digest captured at the timestamp above).

Architecture fitness proxy (`S3EchoLegacyRemovedFitnessTest`, peer G4
mechanical proof — applies structural fitness to the family):

| Suite | Tests | Failures | XML sha256 |
|---|---|---|---|
| `S3EchoLegacyRemovedFitnessTest` | 7 | 0 | `f0bc36a5b384061658871bfb264ede3027e058dbca5ebf8175e6930afe417cee` |

## Out-of-scope observations (documented, NOT acted on)

1. **No dedicated G4 fitness for `core.isUnix`** (`S3IsUnixLegacyRemovedFitnessTest`
   does not exist). This matches the **E-024 / E-048 finding** from the
   LFC-2E0 audit (`DEDICATED_FITNESS_GAP`, not a certification gap). The
   readiness claim here is mechanical: registration + classifier + capability
   + replay + dispatcher-presence are independently observable from the
   evidence above; no dedicated fitness was needed for readiness.
2. **`CoreLegacyStepMetadataResolverTest` 2 failures on `core.sleep`** — pre-existing
   bug, NOT introduced by this slice. Failure message: "No canonical core metadata
   registered for plugin 'core.sleep'". Identical to the failure set documented at
   cycle-base `50ffb299` (LFC-2R / R4B handoff). G3 does not touch `core.sleep`.
   Rule-16 invariant: `core.isUnix`-related test count unchanged (18 unit + 1
   dispatcher + 13 scripted runtime + 10 invoker = 42 GREEN, same as pre-slice).

## Counters (unchanged by G3 — no flip, no removal)

```text
core.isUnix:
  REGISTERED         = true
  REGISTRY_PRIMARY   = false
  LEGACY_UNREACHABLE = false
  LEGACY_REMOVED     = false
  CONTRACT_SUITE     = false
  CERTIFIED          = false

  MIGRATION_READY    = true
  StructuralFamily   = LegacyCore
  legacy counters    = 8 / 8 / 8
```

The 8/8/8 counters are unchanged from LFC-2R / R4B closure (`c6783f95` snapshot).
No `LEGACY_PLUGIN_IDS` edit, no `CoreStepRegistryFactory` membership change for
`core.isUnix` (it was added at G1; this slice only proves it stays correct).

## STOP

G3 ends here. The next slice (`S2-A5/G4` — authority flip + LEGACY_UNREACHABLE)
requires explicit user GO after review of this receipt. Per the AGENTS.md scope
firewall and the LB-02 burn-down law, the framework will not auto-progress.

Re-entry path for `S2-A5/G4`:
```text
S2-A5/G4 → authority flip + LEGACY_UNREACHABLE
S2-A5/G5 → physical legacy removal
S2-A5/G6 → contract suite (IsUnixStepContractSuite)
installed/final certification
S2-A5 CLOSED
```

Until then, the slice remains:
- `core.isUnix` is REGISTERED and READY to be flipped,
- but the flip has not happened.