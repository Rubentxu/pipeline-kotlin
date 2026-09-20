# E1.ecosystem-local-first — Design

Cycle: `cycle/e1-ecosystem-local-first`
Companion to: `proposal.md`, `spec.md`

## Module placement (hexagonal)

```text
+-----------------------------+   depends on   +-------------------------+
| pipeline-application        | ─────────────► | pipeline-domain         |
|  + core.junit               |                |  + StepDefinition        |
|  + core.artifact.query      |                |  + StepContract          |
|                             |                |  + StepCodec             |
|                             |                |  + Capabilities SPI      |
|                             |                |  + CommonExecutionResult |
+-----------------------------+                +-------------------------+

                            ▲
                            │  implements
                            │
+-----------------------------+
| pipeline-scripting-kotlin24 |
|  + registryStep(...) façade |
|  + JunitReadDsl ext         |
|  + ArtifactQueryDsl ext     |
+-----------------------------+
```

Production source under `v2/pipeline-application/src/main/kotlin/
dev/rubentxu/pipeline/v2/application/` follows the existing pattern
of `CoreArchiveArtifactsStep` (REGISTRY_PRIMARY).

## Type model (sealed data)

```kotlin
// In pipeline-domain (typed contract surface)
sealed interface JunitReadOutcome {
    data class Passed(val totals: JunitTotals) : JunitReadOutcome
    data class Failed(val totals: JunitTotals, val failingCases: List<FailingCase>) : JunitReadOutcome
}

data class JunitTotals(
    val tests: Int,
    val failures: Int,
    val errors: Int,
    val skipped: Int,
    val timeSeconds: Double
)

data class FailingCase(
    val suiteName: String,
    val caseName: String,
    val classname: String,
    val message: String,
    val type: String
)

data class JunitReport(
    val reportPath: String,           // resolved absolute path
    val suites: List<JunitSuiteSummary>,
    val totals: JunitTotals,
    val outcome: JunitReadOutcome
)

data class JunitSuiteSummary(
    val name: String,
    val tests: Int,
    val failures: Int,
    val errors: Int,
    val skipped: Int,
    val timeSeconds: Double
)

// Inputs (codec envelopes)
data class JunitInput(val glob: String)
data class ArtifactQueryInput(val name: String)
data class ArtifactHandle(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String
)
```

The decoder rejects malformed input at the codec boundary; the
handler never reads raw XML from a partially-decoded envelope.

## Codec design

- `JunitInputCodec` — trivial (`glob` is a String).
- `JunitReportCodec` — round-trips the typed `JUnitReport` to a
  durable encoded form for the journal. The codec encodes the whole
  structure; the engine never reconstructs it from individual fields.

For the artifact query side:

- `ArtifactQueryInputCodec` — trivial (name is a String).
- `ArtifactHandleCodec` — round-trips `name/path/size/sha256`.

## Capability declarations

```kotlin
// In pipeline-domain (Capabilities SPI)
interface JunitReportCapability {
    fun resolveReport(glob: String): JunitReport?  // returns null on MISSING_FILE
}

interface ArtifactIndexCapability {
    fun query(name: String): ArtifactHandle?
    fun record(handle: ArtifactHandle)             // called by core.archiveArtifacts
}
```

The capability implementations live in `pipeline-application`'s
JUnit adapter (single writer for the index; in-memory +
file-backed for the durable record). Adapter code does NOT live
in `pipeline-domain`.

## Step implementations (registry-driven)

### `core.junit`

```kotlin
object CoreJunitStep {
    const val KEY = "core.junit"

    val CONTRACT = StepContract(...)

    val DESCRIPTOR = StepDescriptor(
        effects = setOf(Effect.READS_WORKSPACE),
        replayPolicy = ReplayPolicy.MEMOIZED,
        recoveryPolicy = RecoveryPolicy.PIPELINE_DETERMINISTIC
    )

    val INPUT_CODEC = JunitInputCodec()
    val OUTPUT_CODEC = JunitReportCodec()

    fun registerInto(registry: StepRegistry) {
        registry.register(
            StepDefinition(
                key = StepKey(KEY),
                descriptor = DESCRIPTOR,
                inputCodec = INPUT_CODEC,
                outputCodec = OUTPUT_CODEC,
                handler = JunitReadHandler(),
                contract = CONTRACT
            )
        )
    }
}
```

`JunitReadHandler` is typed:

```kotlin
class JunitReadHandler : StepHandler {
    override fun execute(
        encodedInput: EncodedValue,
        ctx: StepHandlerContext
    ): CommonExecutionResult {
        val input = JunitInputCodec.decode(encodedInput)
        val cap = ctx.capabilities[JunitReportCapability::class]
            ?: return CommonExecutionResult.MissingCapability(KEY, "JUNIT_REPORT_CAPABILITY")

        // Resolve glob → file
        val file = resolveFirst(input.glob)
            ?: return CommonExecutionResult.TypedFailure(
                failureKind = FailureKind.INPUT_INVALID,
                message = "JUnit XML not found: ${input.glob}",
                events = listOf(JunitReadFailed("MISSING_FILE"))
            )

        // Parse
        val report = try {
            JunitParser.parse(file)   // throws on malformed / unsupported schema
        } catch (e: JunitParseException) {
            return CommonExecutionResult.TypedFailure(
                failureKind = FailureKind.INPUT_INVALID,
                message = e.message ?: "JUnit XML malformed",
                events = listOf(JunitReadFailed(e.kind))
            )
        }

        val resultEvents = buildList {
            add(JunitReadStarted(report.reportPath))
            report.suites.forEach { add(JunitSuiteRead(it)) }
            add(JunitReadCompleted(report.outcome.summary))
        }

        val encoded = OUTPUT_CODEC.encode(report)
        return CommonExecutionResult.Success(
            encodedOutput = encoded,
            events = resultEvents
        )
    }
}
```

Note: the handler NEVER throws; all parse failures become typed
`INPUT_INVALID` results. (Per AGENTS.md rule 11.)

### `core.artifact.query`

Same pattern. The capability writes through to the index when
`core.archiveArtifacts` records a handle, and reads through it for
the query step. **The filesystem remains the durable record; the
index is a derived projection.**

## DSL façade (in pipeline-scripting-kotlin24)

```kotlin
fun StageScope.junit(glob: String): TypedStepValue<JUnitReport> =
    registryStep(
        stepKey = StepKey(CoreJunitStep.KEY),
        encodedInput = JunitInputCodec.encode(JunitInput(glob))
    )

fun StageScope.artifactQuery(name: String): TypedStepValue<ArtifactHandle> =
    registryStep(
        stepKey = StepKey(CoreArtifactQueryStep.KEY),
        encodedInput = ArtifactQueryInputCodec.encode(ArtifactQueryInput(name))
    )
```

## Failure mode mapping

| Failure | failureKind | Events |
|---|---|---|
| File missing | `INPUT_INVALID` | `JunitReadFailed("MISSING_FILE")` |
| XML malformed | `INPUT_INVALID` | `JunitReadFailed("MALFORMED")` |
| Unsupported schema | `INPUT_INVALID` | `JunitReadFailed("UNSUPPORTED_SCHEMA")` |
| Capability missing | (rejected before handler) | `JunitCapabilityMissing` |
| Tests fail (read OK) | `CommonExecutionResult.Success` (FAIL value) | `JunitReadCompleted(FAIL)` |
| Artifact not found | `INPUT_INVALID` | `ArtifactQueryFailed("NOT_FOUND")` |

`failureKind` always belongs to the project's typed algebra; no
generic `INFRASTRUCTURE` for parse/IO failures.

## Test architecture (per AGENTS.md §Coordinator test composition)

- Pure contract unit tests on codecs and parsers (no registry).
- Registry-resolved tests for the handler (typed input → typed
  output, capability admission, replay).
- End-to-end UAT via `.pipeline.kts` against a real Gradle
  fixture (WU-LPR-062 re-use).

## Out-of-design decisions (escalated, NOT taken)

- **Streaming API for huge reports.** Decided: memory-bound read,
  no streaming. Cycles past 100MB XML files are out of scope.
- **JUnit 5 standalone schema.** Decided: fail closed (S5).
- **Multiple-glob in one call.** Decided: caller invokes N times.
- **Remote artifact retrieval.** Decided: out of cycle.
