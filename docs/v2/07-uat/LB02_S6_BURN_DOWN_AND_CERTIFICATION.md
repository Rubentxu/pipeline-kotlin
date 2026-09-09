# LB-02 / S6 — legacy `core.sh` burn-down and certification ledger

## S6 burn-down (commits)

- `36a32cc0` — **S6.1-4** removed the legacy `core.sh` sealed subtype, the Sh
  decoder case, the `CanonicalNodeDispatcher` Shell branch,
  `CanonicalShellNodeDispatcher` + `CanonicalShellDispatchContext`, and the
  `CanonicalCoreStepMetadata["core.sh"]` row. Deleted the obsolete legacy-Sh
  tests (`CanonicalShellNodeDispatcherTest`, Sh cases in
  `CanonicalCoreStepDecoderTest`, Shell subtype in
  `CanonicalCoreStepCommandRegistryTest`, and `core.sh` rows in
  `CoreLegacyStepMetadataResolverTest`), and converted the A4 parity tests to
  registry-metadata fitness. Net `-438` lines across 11 files.
- `5d9cbd6d` — **S6.5** irreversible fitness: `CanonicalCoreStepCommand` has no
  Shell subtype; the legacy Sh decoder fails fast on a `core.sh` node;
  structural switch never classifies `core.sh` as `LegacyCore` with the
  production registry.
- `2ce49fe5` — **S6.6** `ShStepContractSuiteTest` (14 tests, 14/0/0).

## Certification coverage matrix

| Row | Coverage | Status |
| --- | --- | --- |
| identity | ShStepContractSuite + CoreShellStepTest | green |
| contract completeness | ShStepContractSuite | green |
| input codec | ShStepContractSuite + G7 | green |
| output codec | G7 codec round-trip + ShStepContractSuite | green |
| canonical envelope | CoreShellStepTest | green |
| registry resolution | A5 proof + ShStepContractSuite | green |
| capability admission | A4_2 + ShStepContractSuite | green |
| missing capability | A4_2 + ShStepContractSuite | green |
| successful process | ShStepContractSuite (real `echo`, exit 0) | green |
| non-zero exit | ShStepContractSuite (`exit 42` → SCRIPT) | green |
| stdout | ShStepContractSuite (captured file) | green |
| **stderr** | **no dedicated public contract row** | **pending** |
| typed failure | ShStepContractSuite + A4_3 | green |
| cancellation/interruption | A4_3 Interrupted→TIMEOUT; coordinator timeout | green |
| ReplayPolicy semantics | ShStepContractSuite (RERUN) | green |
| durable encoded output | G7 + A4_3 | green |
| ExternalSubprocess recovery | ShStepContractSuite + A4_1 | green |
| running-process recovery | ShStepContractSuite + A5 proof (no relaunch) | green |
| observability | ShStepContractSuite + A4_8 | green |
| real installed distribution scenario | delegated to `UatStep001ShExecutionTest` / A4 CLI (Echo precedent) | green (delegated) |
| architecture fitness | delegated to `:pipeline-architecture-tests` + A5 proof (Echo precedent) | green (delegated) |
| legacy absence | ShStepContractSuite + A5 proof | green |

## Certification result

Per the LB-02 certification rule, one mandatory row has no dedicated public
contract assertion:

```text
core.sh = IMPLEMENTED_UNCERTIFIED
LB-02 != REMOVED
```

Concrete gap: a **stderr** contract row — no test asserts that a `core.sh`
process writing to stderr (a) does not fail, and (b) does not pollute the typed
stdout/return value. The process runtime already carries STDERR as a distinct
typed stream; only the public sh contract row is missing.

## Ledger

```text
core.sh:
    REGISTRY_PRIMARY
        ↓ (A4)
    LEGACY_UNREACHABLE
        ↓ (A5)
    LEGACY_REMOVED        (S6.1-3 production, S6.5 fitness)
        ↓
    IMPLEMENTED_UNCERTIFIED  (S6.6 suite; stderr row pending)
```

When the stderr row is closed, `core.sh = CERTIFIED`, `LB-02 = REMOVED`, and the
AGENTS.md reference rule (`core.echo = atomic/simple`, `core.sh =
effectful/recoverable`) may be added.
