# G4 architecture fitness receipt — `core.echo`

## Law

The LB-01 ordering rule:

```text
CERTIFIED requires LEGACY_REMOVED
```

`LEGACY_REMOVED` is a **source property**, not merely a runtime
property (`LEGACY_UNREACHABLE`). It requires:

```text
decoder absent
  AND dispatcher absent
  AND registration absent
```

## Mechanical proof

`S3EchoLegacyRemovedFitnessTest` (pipeline-architecture-tests):

```text
File: v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/S3EchoLegacyRemovedFitnessTest.kt
Size: 206 lines
@Disabled/@Ignore/@Quarantined: NONE
Source-scanning assertions (not runtime, not grep-fragile)
```

### Test methods (7/7)

| # | Method | What it proves |
|---|---|---|
| 1 | `core echo is registered in the production StepRegistry` | `CoreStepRegistryFactory.registry()` contains `core.echo` |
| 2 | `core echo is NOT in the closed legacy authority LEGACY_PLUGIN_IDS` | `CanonicalCoreStepDecoder.LEGACY_PLUGIN_IDS` does not contain `core.echo` |
| 3 | `core echo is NOT decodable by CanonicalCoreStepDecoder` | No decoder case for `core.echo` |
| 4 | `core echo is NOT dispatched by CanonicalNodeDispatcher` | No dispatcher case for `core.echo` |
| 5 | `core echo is NOT in the legacy metadata table` | `CanonicalCoreStepMetadata.table` has no `core.echo` entry |
| 6 | `certified registry-routed plugins are disjoint from LEGACY_PLUGIN_IDS` | Global policy: no Step is both certified and legacy-executable |
| 7 | `core echo satisfies the LEGACY_REMOVED rule (decoder absent AND dispatcher absent AND registration absent)` | The combined source-level rule |

### Fresh execution result

```text
:pipeline-architecture-tests:test \
  --tests 'S3EchoLegacyRemovedFitnessTest' \
  --rerun-tasks

exit: 0
XML: TEST-dev.rubentxu.pipeline.v2.architecture.S3EchoLegacyRemovedFitnessTest.xml
     tests="7" skipped="0" failures="0" errors="0"

log sha256: 78b4650b141c2d2985eed9f69f659760eeb76975a6b62497e434db9d28bb298b
            /tmp/lfc2e1-s1-echo-s3.log
```

### Reference implementation

The S3 burn-down cycle (prior) removed `core.echo` from every legacy
authority:

```text
removed (S3.1 + S3.2 + S3.3):
  - typed-command sealed hierarchy: CanonicalCoreStepCommand.Echo data class
  - legacy decoder: CanonicalCoreStepDecoder no longer handles ECHO_PLUGIN_ID
  - legacy dispatcher: CanonicalEchoNodeDispatcher deleted
                       CanonicalNodeDispatcher has no Echo case
  - legacy metadata: CanonicalCoreStepMetadata.table no longer carries core.echo
  - closed legacy authority: LEGACY_PLUGIN_IDS excludes core.echo

Echo now lives ONLY as an open StepDefinition (CoreEchoStep) registered
through CoreStepRegistryFactory.
```

## Conclusion

`core.echo` satisfies the **G4 architecture fitness layer** with
7/7 GREEN mechanical proofs. Source-level absence of legacy paths is
established, not merely runtime-unreachable.

Combined with the G7 StepContractSuite (17/17 GREEN) and the real
execution parity (`examples/01-hello.pipeline.kts` SUCCESS), `core.echo`
satisfies `CERTIFIED + LEGACY_REMOVED` for the LB-01 burn-down ledger.

## Cycle context

- Cycle: `lfc2-e1-s1-echo-legacy-removed`
- Branch: `cycle/lfc2-e1-s1-echo-legacy-removed`
- Baseline: `main == origin/main == ca550da0` (after pre-S1 reconciliation)
- Parent cycle: `lfc2-step-ecosystem-expansion` (LFC-2E0) — closed
- Pre-S1 audit: `docs/v2/07-uat/LFC2E0_PRE_S1_EVIDENCE_AUDIT.md`
- This receipt + `CORE_ECHO_CERTIFICATION.md` together formalize the
  S1 closure.
