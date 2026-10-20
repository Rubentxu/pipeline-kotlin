package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.CorrelationId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.application.observation.ObservationFormat
import dev.rubentxu.pipeline.v2.application.observation.RunObservationOutput
import dev.rubentxu.pipeline.v2.events.RunCorrelated
import dev.rubentxu.pipeline.v2.events.EventSink
import java.nio.file.Path

/**
 * The pieces both run worlds share.
 *
 * ## Why a shared file and not a "utils" bag
 *
 * These are the facts that must be said ONCE: how the correlation token and the
 * launch receipt reach the operator, how the events become a document, and how
 * a script becomes a compiled pipeline. A copy of any of them inside a world
 * is a copy that can drift, and a drifting copy is how a `--db` run and a
 * volatile run end up answering the same question differently.
 *
 * The helpers only ONE world uses live in that world's file — the failure
 * report belongs to the durable world, the live output observer to the
 * volatile one — and the ones only [main] uses live in [Main].
 */

/**
 * PK-1 / ADR-PK1-IDENTITY — emit the [RunCorrelated] event on the
 * supplied event sink AND the [LaunchReceipt] JSON line on stdout.
 *
 * Called at the moment durable state is established (lease acquired for
 * the durable branch; RunIdDirectory record for the in-memory branch).
 * The event is the durable witness of the correlation; the receipt is
 * the immediate stdout confirmation. Both are emitted in the same
 * order so a consumer reading the event log + stdout can join them
 * without an out-of-order window.
 */
internal fun emitCorrelationAndReceipt(
    eventStore: dev.rubentxu.pipeline.v2.events.EventSink,
    runId: dev.rubentxu.pipeline.v2.domain.RunId,
    correlationId: dev.rubentxu.pipeline.v2.domain.CorrelationId,
    ownerId: dev.rubentxu.pipeline.v2.events.durable.RunOwnerId,
    startedAtMs: Long,
    controlDir: java.nio.file.Path,
    format: ObservationFormat,
) {
    val correlatedEvent = RunCorrelated(
        eventId = "e-correlated-${System.nanoTime()}",
        runId = runId.value,
        sequence = 0L,
        occurredAt = java.time.Instant.ofEpochMilli(startedAtMs),
        correlationId = correlationId,
        ownerId = ownerId.value,
        startedAtMs = startedAtMs,
    )
    eventStore.append(correlatedEvent)
    val receipt = LaunchReceipt.launched(
        runId = runId,
        correlationId = correlationId,
        ownerId = ownerId,
        startedAtMs = startedAtMs,
        controlDir = controlDir,
    )
    val bytes = LaunchReceipt.encode(receipt).toByteArray(Charsets.UTF_8)
    // ADR-PK-NEXT-R0.3: in TEXT view the receipt goes to stderr so the
    // human console is not polluted. In JSON / JSON_LINES view the
    // receipt is the first stdout line so a machine consumer can
    // join it to the event stream by line index. Either way, the
    // receipt is NOT a substitute for the durable RunCorrelated
    // event; the certifier must validate the durable record, not
    // trust the receipt alone.
    when (format) {
        ObservationFormat.TEXT -> {
            System.err.write(bytes)
            System.err.flush()
        }
        ObservationFormat.JSON, ObservationFormat.JSON_LINES -> {
            System.out.write(bytes)
            System.out.flush()
        }
    }
}

// ------------------------------------------------------------------------
// PK-0-57-RC1 / ADR-PK1-OUTPUT-FRAMING — opt-in output protocol helpers.
// ------------------------------------------------------------------------


/**
 * Write the end-of-run event document to the channel the negotiated
 * protocol dictates. The bytes do not change between protocols; the
 * channel does.
 *
 * - [OutputProtocol.Published] (default) → stdout.
 * - [OutputProtocol.SeparatedV2] → stderr (machine events go to
 *   stderr; the receipt is unaffected and stays on stdout via
 *   [emitCorrelationAndReceipt]).
 * - TEXT view is unaffected in both protocols; this helper is
 *   only called for JSON / JSON_LINES.
 */
internal fun writeEventDocument(
    events: List<dev.rubentxu.pipeline.v2.events.DomainEvent>,
    view: dev.rubentxu.pipeline.v2.application.observation.ObservationView,
    format: ObservationFormat,
    query: dev.rubentxu.pipeline.v2.application.observation.CompiledObservationQuery,
    protocol: OutputProtocol,
) {
    val bytes = RunObservationOutput.encode(events, view, format, query).toByteArray(Charsets.UTF_8)
    val sink = when (protocol) {
        OutputProtocol.Published -> System.out
        OutputProtocol.SeparatedV2 -> System.err
    }
    sink.write(bytes)
    sink.flush()
}

/**
 * Compile the DSL into the canonical form, or refuse it.
 *
 * ## Why this wrapper exists
 *
 * `DslCompiledPipelineCompiler` rejects an invalid definition with `error()`, which is the right call
 * from inside a compiler that has no idea how a CLI reports things. What it did NOT have was a caller
 * that knew it was being handed user input rather than a programmer's bug, and the consequence was
 * measured:
 *
 * ```console
 * $ pipelinek run --format json mixed-body-and-sibling.pipeline.kts
 * stdout: {"schema":"pipelinek.launch/1",...,"status":"Launched"}
 * stderr: Exception in thread "main" java.lang.IllegalStateException:
 *             Stage 'ParallelTest' cannot mix a parallel body with sibling steps
 *             at ...DslCompiledPipelineCompiler.stageNode(DslCompiledPipelineCompiler.kt:107)
 *             at ...MainKt.main(Main.kt:498)
 * exit 1
 * ```
 *
 * (The trace is quoted as it was observed, line numbers included. The file has
 * since been split, so the frames it names do not live where they did.)
 *
 * Correct verdict, right exit code, right message — delivered as an uncaught exception. A stack trace
 * tells the author that something broke inside the runtime, when what happened is that their pipeline
 * is wrong and the runtime is working. AGENTS.md reserves exceptions for adapter boundaries and
 * programmer defects (STRICT TYPED FUNCTIONAL DESIGN §11); a stage that mixes a `parallel` body with
 * sibling steps is neither.
 *
 * ## What it does NOT swallow
 *
 * The catch is scoped to this one call and to [IllegalStateException], which is what the guards throw.
 * Nothing else is converted: a `NullPointerException` or a `ClassCastException` inside the compiler is
 * a defect, and it still surfaces as a defect with its stack trace intact. The refusal prints the
 * message verbatim, because the message is the part the author needs.
 *
 * Exit code is 1, matching every other definition-level failure (`CliCompileErrorExitsOneTest`); 2 is
 * reserved for admission refusals, which are a different decision about a different thing.
 */

internal fun compileOrRefuse(
    spec: dev.rubentxu.pipeline.v2.dsl.PipelineSpec,
    sourcePath: String,
    sourceContent: String,
): dev.rubentxu.pipeline.v2.domain.CompiledPipeline =
    try {
        DslCompiledPipelineCompiler.compile(
            spec = spec,
            sourcePath = sourcePath,
            sourceContent = sourceContent,
            pluginLockDigest = dev.rubentxu.pipeline.v2.domain.Digest("builtin"),
        )
    } catch (rejection: IllegalStateException) {
        System.err.println("Error: ${rejection.message}")
        kotlin.system.exitProcess(1)
    }
