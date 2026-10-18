package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * DEBT-WIRE-AUTHORITY (`bl-bl-01M3YGH3FE000387X13PG607G0`) — `core.waitUntil`
 * block wire-authority guard. Sibling of [Lfc2LockWireAuthorityFitnessTest]
 * (WU-091) and the `core.input` guard (WU-092); same law, third Step.
 *
 * ## Law under test
 *
 * ```
 * DslCompiledPipelineCompiler
 *     may know:    StepSpec.WaitUntilBlock, CoreWaitUntilInput, CoreWaitUntilWireCodec
 *     must NOT know: the waitUntil BLOCK wire vocabulary
 *                    ("kind":"waitUntilBlock", "initialRecurrencePeriod", "quiet")
 *                    or any hand-written waitUntil block wire JSON
 * ```
 *
 * ## Why the Step codec already existing is not enough
 *
 * `CoreWaitUntilStep` declares an (anonymous) `StepCodec<WaitUntilInput>`.
 * That codec owns the **registry invocation** carrier — `encodedInput`, whose
 * discriminator is `kind="waitUntil"`. The `waitUntil { }` DSL construct is a
 * **block**: it lowers to `BlockStepNode`, and its body-carrying IR is a
 * **different carrier** with a different discriminator,
 * `kind="waitUntilBlock"`, written by `blockPayload`. So the Step codec never
 * governed the block wire; the compiler became its second, hand-written
 * writer. Naming the block format is the whole point of this slice.
 *
 * ## Which bytes are durable input, and which are not
 *
 * `Lfc2WaitUntilDslCanonicalProjectionTest` asserts, across the productive
 * authority (`DslCompiledPipelineCompiler.compile`), that the compiled block
 * payload carries `kind="waitUntilBlock"`.
 *
 * This file previously argued that the whole payload string is durable input
 * because `BodyStructureDigest` appends `child.payload.encoded`. That argument
 * was wrong: the digest's payload-appending call is guarded by
 * `hasBodyContinuation`, bound only for the `HANDLER_CONTINUATION` owner, which
 * only `core.lock` and `core.input` declare. `core.waitUntil` is an
 * engine-driven block and never reaches it. Naming the format in a file that
 * only names it cannot create the obligation, so the ownership is uncontested
 * either way — but the stated reason would have been a fiction, and a fiction
 * in a guard is a guard that cannot be trusted.
 *
 * The payload string IS durable input through a different route:
 * `StepDispatchEngine` puts it in `OperationInput.params`, which
 * `OperationInput` documents as fingerprint input, and the ADR-0103 D1 table's
 * first rule is `!hasJournalEntry -> RERUN`.
 *
 * This fitness asserts *authorship*, never a literal value. Byte identity is
 * carried by the behavioural contract test (`CoreWaitUntilWireCodecContractTest`,
 * pipeline-application), which compiles a real block through the productive
 * path — and whose frozen bytes moved once, deliberately, when `maxBackoffMs`
 * was added to the format (ROADMAP correction, 2026-10-18).
 *
 * Family: source-scan architecture fitness. The scan is comment-blind, like the
 * rest of the Lfc2 family: a forbidden literal inside a comment does not trip
 * it, and a permitted symbol named only in a comment does not satisfy the
 * allowed-knowledge assertion.
 */
class Lfc2WaitUntilWireAuthorityFitnessTest {

    private val compilerSource = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt",
    )
    private val wireCodecSource = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreWaitUntilWireCodec.kt",
    )
    private val dispatchEngineSource = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/StepDispatchEngine.kt",
    )
    private val bodyEngineSource = ScannerSupport.v2Root().resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/BodyExecutionEngine.kt",
    )

    /**
     * A site in the default-authority chain: its path, what it is, and — for a site that does
     * not own the value — the token that proves it delegates rather than invents.
     *
     * `delegatesTo == null` means the field is not part of that file's carrier at all. It is not
     * an exemption: the global literal count still scans this file, so a value written here is
     * fatal even though the site is not required to name the constant.
     */
    private data class DefaultSite(
        val rel: String,
        val label: String,
        val delegatesTo: String?,
    )

    private val cadenceChain = listOf(
        DefaultSite(
            "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionPolicy.kt",
            "WaitUntilShape.initialRecurrencePeriodMs (OWNER: declares the constant)",
            null,
        ),
        DefaultSite(
            "pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/StepSpec.kt",
            "StepSpec.WaitUntilBlock.initialRecurrencePeriod",
            "DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS",
        ),
        DefaultSite(
            "pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/StageScope.kt",
            "StageScope.waitUntil(initialRecurrencePeriod)",
            "DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS",
        ),
        DefaultSite(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreWaitUntilInput.kt",
            "CoreWaitUntilInput.initialRecurrencePeriod",
            "DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS",
        ),
        DefaultSite(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreWaitUntilWireCodec.kt",
            "CoreWaitUntilWireCodec absent-key branch",
            "DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS",
        ),
        DefaultSite(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreWaitUntilStep.kt",
            "WaitUntilInput.initialRecurrencePeriod + registry decoder absent-key branch",
            "DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS",
        ),
        DefaultSite(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalStructuralDecisions.kt",
            "projectWaitUntilScope absent-key branch",
            "shape.initialRecurrencePeriodMs",
        ),
    )

    /**
     * The same chain for the ceiling, which is one file shorter and for a real reason.
     *
     * `core.waitUntil` has TWO carriers with two vocabularies: the registry invocation
     * (`kind="waitUntil"`, `CoreWaitUntilStep`) and the block IR (`kind="waitUntilBlock"`,
     * `CoreWaitUntilWireCodec`). The ceiling belongs to the block, so the registry carrier
     * legitimately does not mention it. Naming it there would be as wrong as inventing it
     * there, which is why this list is not the cadence list reused verbatim.
     */
    private val ceilingChain = listOf(
        DefaultSite(
            "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/BodyExecutionPolicy.kt",
            "DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS (OWNER: declares the constant)",
            null,
        ),
        DefaultSite(
            "pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/StepSpec.kt",
            "StepSpec.WaitUntilBlock.maxBackoffMs",
            "DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS",
        ),
        DefaultSite(
            "pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/StageScope.kt",
            "StageScope.waitUntil(maxBackoffMs)",
            "DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS",
        ),
        DefaultSite(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreWaitUntilInput.kt",
            "CoreWaitUntilInput.maxBackoffMs",
            "DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS",
        ),
        DefaultSite(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreWaitUntilWireCodec.kt",
            "CoreWaitUntilWireCodec absent-key branch",
            "DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS",
        ),
        DefaultSite(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreWaitUntilStep.kt",
            "registry carrier: the ceiling is not in this vocabulary",
            null,
        ),
        DefaultSite(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalStructuralDecisions.kt",
            "projectWaitUntilScope absent-key branch",
            "shape.maxBackoffMs",
        ),
    )

    private fun path(rel: String) = ScannerSupport.v2Root().resolve(rel)

    private fun read(path: java.nio.file.Path): String {
        require(Files.exists(path)) { "Expected source not found: $path" }
        return Files.readString(path)
    }

    /** Comment-blind view of a source file. */
    private fun codeOnly(text: String): String =
        text.lineSequence()
            .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
            .joinToString("\n")

    @Test
    fun `compiler knows the waitUntil domain decision and the codec symbol`() {
        val code = codeOnly(read(compilerSource))
        assertTrue(
            code.contains("StepSpec.WaitUntilBlock"),
            "DslCompiledPipelineCompiler must route StepSpec.WaitUntilBlock; if this fails the " +
                "routing arm was removed and waitUntil falls into the generic else branch.",
        )
        assertTrue(
            code.contains("CoreWaitUntilInput"),
            "DslCompiledPipelineCompiler must know CoreWaitUntilInput (the " +
                "StepSpec.WaitUntilBlock -> CoreWaitUntilInput domain transformation). Without a " +
                "typed value there is nothing for a codec to encode.",
        )
        assertTrue(
            code.contains("CoreWaitUntilWireCodec"),
            "DslCompiledPipelineCompiler must encode the waitUntil block payload through " +
                "CoreWaitUntilWireCodec — the single wire authority for that format.",
        )
    }

    @Test
    fun `compiler never writes the waitUntil block wire vocabulary`() {
        val code = codeOnly(read(compilerSource))
        // Allowed knowledge: the domain transformation with NAMED ARGUMENTS. Those are
        // fields of a typed domain value checked by the Kotlin compiler, not wire text.
        // Forbidden knowledge: WIRE-JSON AUTHORSHIP — the JsonObject keys of that
        // format, written by hand in the compiler.
        val forbidden = listOf(
            "put(\"kind\", \"waitUntilBlock\"",
            "put(\"initialRecurrencePeriod\"",
            "put(\"quiet\"",
        )
        val found = forbidden.filter { code.contains(it) }
        assertTrue(
            found.isEmpty(),
            "DslCompiledPipelineCompiler hand-writes the waitUntil block wire JSON ($found). " +
                "It must lower StepSpec.WaitUntilBlock through " +
                "CoreWaitUntilWireCodec.encode, never author the wire format " +
                "(DEBT-WIRE-AUTHORITY; the core.sh dialect split is the frozen counter-example).",
        )
    }

    @Test
    fun `waitUntil block wire vocabulary lives in the wire authority`() {
        val code = codeOnly(read(wireCodecSource))
        val expected = listOf("waitUntilBlock", "initialRecurrencePeriod", "quiet", "maxBackoffMs")
        val missing = expected.filter { !code.contains(it) }
        assertTrue(
            missing.isEmpty(),
            "CoreWaitUntilWireCodec no longer spells $missing; the guard tracks a moved or " +
                "renamed wire authority. Update the path(s) this fitness scans, not the law.",
        )
    }

    /**
     * The wire has exactly ONE reader of a per-block field, and it is the typed projection.
     *
     * The residue this guards against is measured, not hypothetical:
     * `StepDispatchEngine` extracted `maxBackoffMs` from the raw payload with a
     * regex and passed it to `BodyExecutionEngine` as an override parameter. That
     * value reached the INLINE polling branch only, and the inline branch runs
     * iff the waitUntil control journal is null, which `CompositionRoot` never
     * satisfies in production. So a per-block ceiling was settable in a payload
     * and unreachable in the branch production takes — the defining symptom of a
     * second, unregulated reader of a wire field.
     *
     * A projection now decodes it once (`projectWaitUntilScope`) and both branches
     * read `scope.maxBackoffMs`, so the override parameter has no reader left.
     */
    @Test
    fun `no waitUntil block field is read by a side channel beside the typed projection`() {
        val dispatch = codeOnly(read(dispatchEngineSource))
        assertTrue(
            !dispatch.contains("maxBackoffMsOverride"),
            "StepDispatchEngine computes maxBackoffMsOverride, a regex side-channel into one " +
                "polling branch. The typed projection supplies the ceiling to BOTH branches; a " +
                "second reader is a second authority for the same field.",
        )
        val engine = codeOnly(read(bodyEngineSource))
        assertTrue(
            !engine.contains("maxBackoffMsOverride"),
            "BodyExecutionEngine still declares a maxBackoffMsOverride parameter. With the " +
                "projection as the only reader it has no caller that can supply a non-null value.",
        )
    }

    /**
     * An undeclared poll cadence has ONE declared value, written once.
     *
     * Measured 2026-10-18, before this row existed: six sites declared a value for an
     * undeclared cadence — three as `1000L` and three as `1L`. The two values never met,
     * and each was dead for a different reason: the codec's `1000L` was unreachable
     * because the DSL always fills the key at the call site, and the DSL's `1L` was
     * encoded but never read, because the projection took the cadence from the declared
     * shape. That is two authorities for one question, each invisible to the other.
     *
     * About the instrument, because it took three passes to be wider than its subject. This
     * row went through three vocabularies and each reported a wrong number for a right
     * reason: keyed on `initialRecurrencePeriod` it saw 5 of the 6 (the sixth was a private
     * constant named `DEFAULT_INITIAL_RECURRENCE_PERIOD_MS`); after the fix, keyed the same
     * way, it saw 0 of the 1 (the surviving literal had moved into the shared
     * `DEFAULT_WAIT_UNTIL_INITIAL_RECURRENCE_PERIOD_MS`, whose name still does not contain
     * the property name); only when it matched both the camelCase property and the
     * SCREAMING_SNAKE constant did it see 1 of 1. A guard that cannot see a site must not
     * report it as counted, and a count is not a census until its instrument has been read.
     *
     * Making the parameter live is what exposed it: once the projection reads the
     * payload, the DSL's `1L` reaches the poll, and every DSL `waitUntil` that omits
     * the argument would change from a 1000 ms cadence to 1 ms. The value that
     * actually executes today is `1000L` ([WaitUntilShape]'s Jenkins-parity default),
     * so that is the one the whole chain must name.
     *
     * Denominator: [cadenceChain], six delegating sites plus the owner, all named. One literal —
     * the constant's own declaration — and every other site proves delegation by naming the
     * constant or, for the projection, the descriptor it reads.
     */
    @Test
    fun `the poll cadence default is declared once and named everywhere else`() =
        assertDeclaredOnce(
            label = "poll cadence",
            chain = cadenceChain,
            declaration = Regex(
                // Both shapes a site can declare the value with: a camelCase property named
                // `initialRecurrencePeriod…`, or the SCREAMING_SNAKE constant that now owns it.
                // Keying on either one alone is how this row first reported "Found 0" on a tree
                // that still held exactly one literal — an instrument narrower than its subject
                // reports the correct answer for the wrong reason.
                """(?:\w*[iI]nitialRecurrencePeriod\w*|\w*RECURRENCE_PERIOD\w*)\s*(?::\s*Long\s*)?=\s*([0-9][0-9_]*)L?""",
            ),
            owner = "WaitUntilShape.initialRecurrencePeriodMs (OWNER",
        )

    /**
     * The same law for the backoff ceiling, and it earns its own row because exposing the
     * ceiling on the DSL surface created two more declaration sites on the same day.
     *
     * The ceiling was previously declared once, as an `internal` constant in
     * `pipeline-application`. That was fine while the ceiling had no DSL surface; it stopped
     * being usable the moment `StageScope.waitUntil(maxBackoffMs = …)` needed a default, because
     * `pipeline-scripting-api` cannot name an `internal` in `pipeline-application`. The constant
     * moved to the domain authority beside the cadence, and this row holds it there.
     *
     * Writing this row is what paid for itself immediately: it found a second literal nobody
     * had noticed, `private const val MAX_BACKOFF_MS = 60_000L` in `CoreWaitUntilStep`, whose
     * only occurrence in the whole repository was its own declaration. A private constant with
     * no reader is not a style question: it is a value that looks authoritative and disagrees
     * with the authority on any change to either. Deleted.
     */
    @Test
    fun `the backoff ceiling default is declared once and named everywhere else`() =
        assertDeclaredOnce(
            label = "backoff ceiling",
            chain = ceilingChain,
            declaration = Regex(
                """(?:\w*[mM]axBackoff\w*|\w*MAX_BACKOFF\w*)\s*(?::\s*Long\s*)?=\s*([0-9][0-9_]*)L?""",
            ),
            owner = "DEFAULT_WAIT_UNTIL_MAX_BACKOFF_MS (OWNER",
        )

    /**
     * One literal, written once, at the site that owns it — and every other site proves it
     * delegates by naming [DefaultSite.delegatesTo].
     *
     * The delegation token is per-site rather than a single constant for the whole chain
     * because the projection legitimately does not name the constant: it is the READER, and an
     * absent key means `WaitUntilShape`, so the projection asks the descriptor rather than
     * repeating its value. Demanding the constant there would have been the same
     * second-authority mistake this row exists to prevent, in the guard instead of the code.
     *
     * The literal count is global over the chain regardless of `delegatesTo`: a site with no
     * delegation token is still scanned, so a value written there fails the count rather than
     * passing as an exemption.
     */
    private fun assertDeclaredOnce(
        label: String,
        chain: List<DefaultSite>,
        declaration: Regex,
        owner: String,
    ) {
        val literals = chain.flatMap { site ->
            declaration.findAll(codeOnly(read(path(site.rel))))
                .map { "${site.label} = ${it.groupValues[1]}" }
                .toList()
        }

        assertTrue(
            literals.size == 1,
            "The undeclared $label must be written as a numeric literal exactly once, in the " +
                "descriptor that owns it. Found ${literals.size}: $literals. Every additional " +
                "literal is a second authority for a value the projection reads from the payload, " +
                "so the two sites can disagree without anything failing.",
        )
        assertTrue(
            literals.single().startsWith(owner),
            "The one surviving $label literal must be $owner's own declaration, not: ${literals.single()}",
        )

        val notDelegating = chain
            .filterNot { it.label.startsWith(owner) }
            .filter { site -> site.delegatesTo?.let { !codeOnly(read(path(site.rel))).contains(it) } ?: false }
            .map { "${it.label} does not name ${it.delegatesTo}" }
        assertTrue(
            notDelegating.isEmpty(),
            "Every other site must delegate instead of inventing a value. Offenders: $notDelegating. " +
                "A literal here is a value that can drift from the descriptor without a failing test.",
        )
    }
}
