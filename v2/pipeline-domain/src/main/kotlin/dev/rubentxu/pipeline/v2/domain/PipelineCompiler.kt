package dev.rubentxu.pipeline.v2.domain

/**
 * Diagnostic emitted by a [PipelineCompiler] when compilation cannot
 * complete. Carries enough information for the calling CLI to render a
 * useful error message and a future LSP / IDE to highlight the source.
 *
 * The diagnostic is **stateless and immutable**. Compilers MUST emit a
 * non-empty list when returning [CompileResult.Failure] and MUST emit an
 * empty list when returning [CompileResult.Success] (failure with no
 * diagnostics is reserved for catastrophic I/O errors, not user errors).
 *
 * @property line 1-based source line, or `null` when the diagnostic is not
 *                tied to a specific line (e.g. global resolution failure).
 * @property column 1-based source column, or `null` when not applicable.
 * @property message human-readable message. MUST be safe to print to a
 *                 terminal without further escaping.
 * @property severity the diagnostic class. Defaults to [Severity.ERROR]
 *                    because that is what blocks compilation.
 */
data class PipelineDiagnostic(
    val line: Int?,
    val column: Int?,
    val message: String,
    val severity: Severity = Severity.ERROR,
) {
    init {
        if (line != null) require(line >= 1) { "PipelineDiagnostic.line must be >= 1, got $line" }
        if (column != null) require(column >= 1) { "PipelineDiagnostic.column must be >= 1, got $column" }
        require(message.isNotBlank()) { "PipelineDiagnostic.message must not be blank" }
    }

    enum class Severity { ERROR, WARNING, INFO }
}

/**
 * Outcome of a [PipelineCompiler.compile] call.
 *
 * The compiler NEVER throws on user errors; user errors are encoded as
 * [CompileResult.Failure] with one or more [PipelineDiagnostic]. The
 * compiler MAY throw on internal errors (I/O, OOM, security violations);
 * those are bugs and must be surfaced as exceptions, not diagnostics.
 */
sealed interface CompileResult {
    data class Success(val definition: PipelineDefinition) : CompileResult
    data class Failure(val diagnostics: List<PipelineDiagnostic>) : CompileResult {
        init {
            require(diagnostics.isNotEmpty()) { "CompileResult.Failure must carry at least one diagnostic" }
        }
    }
}

/**
 * Port for compiling a pipeline source string into a [PipelineDefinition].
 *
 * This is the LF-0201 contract. The M2 compiler takes a textual source
 * (`.kts`, `.groovy`, plain text, anything serialisable to a `String`) and
 * returns either a fully resolved [PipelineDefinition] or a list of
 * diagnostics that describe why it could not.
 *
 * ## Two adapters
 *
 * - [MapPipelineCompiler] (in domain) — test-friendly, deterministic,
 *   resolves a [PipelineDefinition] by id from a frozen map. Useful for
 *   characterisation, fixtures, and any site that wants to skip parsing.
 *
 * - The application-side `SimplePipelineCompiler` (LF-0201, lands in a
 *   follow-up slice) parses a trivial line-based syntax. Real parsing
 *   (Kotlin Script DSL, Groovy Script DSL) lands in LF-0205 along with
 *   the [RunCoordinator] migration.
 *
 * ## Why the port lives in domain
 *
 * Per [CANONICAL_CONTRACTS_SPEC.md](docs/v2/03-specifications/CANONICAL_CONTRACTS_SPEC.md)
 * §Platform services, the runtime is the only entity that knows what a
 * "pipeline" *means* in execution. The compiler is the only entity that
 * knows what a pipeline source *means* in code. Both are domain concerns.
 * Adapters (the actual parsers) live in [application][dev.rubentxu.pipeline.v2.application]
 * or in [sdk][dev.rubentxu.pipeline.v2.sdk], never in domain itself.
 *
 * @see PipelineDefinition
 * @see PipelineDiagnostic
 */
fun interface PipelineCompiler {
    fun compile(source: String): CompileResult
}
