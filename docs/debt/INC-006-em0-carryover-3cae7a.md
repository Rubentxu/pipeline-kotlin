# INC-006 — em-0 carry-over CanonicalCoreStepCommand.Shell deprecated secondary constructor

| Field | Value |
|---|---|
| destination | `docs/debt/INC-006-em0-carryover-3cae7a.md` |
| derived_from | cycle `p-733fb505b5a6bd2d/em-3-jenkins-sh-contract` / `debt-report.json` (round 3) |
| original_source | cycle `p-733fb505b5a6bd2d/em-0-execution-model-contract-freeze` / `debt-report.json` (round 2) |
| fingerprint | `18facd2bed61211f09e5960891d4d62715f2f316d18a288d19421544c1ec109f` |
| cluster_id | `CL-05` |
| severity | `low` |
| priority | `P3` |
| attribution | `pre_existing` |
| owner | `unassigned` |
| followon_cycle | `unassigned` |
| rationale | "CanonicalCoreStepCommand.Shell deprecated secondary constructor — pre-existing carry-forward from em-0 round 2, unchanged in em-3." |

## Description

CanonicalCoreStepCommand.Shell deprecated secondary constructor. The data class
declares a deprecated secondary constructor
(command: String, isScriptBlock: Boolean, returnStdout: Boolean) that delegates
to the primary ShellCommand-based constructor. Marked
`@Deprecated('Use ShellCommand.returnMode')`. It is migration-only scaffolding:
the test file `CanonicalCoreStepCommandRegistryTest.kt:44` invokes it to
instantiate a Shell (line 44), which proves it is still callable from tests.
Locations: `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt:55-62`.

Impact: Migration scaffolding only; does not affect runtime behavior. Test
sites should migrate to the primary constructor before the deprecation window
closes.

## Evidence

| Kind | Path | Observation | sha256 |
|---|---|---|---|
| command | n/a | em-3 (round 3) audit: zero production diff confirms finding unchanged. | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| source | `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CanonicalCoreStepDecoder.kt:55-62` | Lines 55-62 declare the deprecated secondary constructor. Annotation correctly placed. Only known call site is `CanonicalCoreStepCommandRegistryTest.kt:44` (deprecated form). | n/a |

## Remediation

target=backlog -- Migrate `CanonicalCoreStepCommandRegistryTest.kt:44` to the
primary `Shell(shell = ShellCommand(script = "echo test", returnMode = ShellReturnMode.NONE), isScriptBlock = false)`
form; remove the deprecated secondary constructor in EM-10 once the test
migrates.

## Owner

`unassigned`. Suggested path: future cycle (apply) addressing the EM-3 backlog.
Not introduced by em-3.

## Status

`open` — pre-existing carry-forward. Not introduced by em-3.

## Cross-references

- debt-report: `FIND-3CAE7A`
- em-3 round-3 debt-report: `debt-report.json` sha256 `bd58cf5b93b0fd9ae89ef0dbe4f509f1bab3f760e9cb0c3d1ac527a58c5090f0`
- gate receipt: `gate-debt-severity-assigned-5d8b1d2bbff7b052-1` (passed), `gate-debt-priority-assigned-5d8b1d2bbff7b052-1` (passed)
- related INC: INC-004, INC-005
