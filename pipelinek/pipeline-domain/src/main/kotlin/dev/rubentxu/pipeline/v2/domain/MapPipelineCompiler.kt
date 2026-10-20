package dev.rubentxu.pipeline.v2.domain

/**
 * Deterministic, test-friendly [PipelineCompiler] backed by a frozen map
 * of pre-built [PipelineDefinition]s.
 *
 * ## Resolution rules
 *
 * - The compiler's "source string" is the [DefinitionId.value] of the
 *   target definition. Any string that matches a key in the backing map
 *   resolves to that definition.
 * - A source that does not match any key produces a [CompileResult.Failure]
 *   with a single diagnostic on line 1, column 1.
 * - An empty source produces a [CompileResult.Failure] with a single
 *   diagnostic on line 1, column 1.
 * - The source is not echoed back in the diagnostic message to avoid
 *   leaking potentially-sensitive content (a legacy Pipelinefile may carry
 *   credential references).
 *
 * ## Why this exists
 *
 * The M2 characterise-parity gate needs a [PipelineCompiler] that behaves
 * deterministically and never touches the file system, environment, or
 * any I/O. [MapPipelineCompiler] is that adapter. It is the moral
 * equivalent of [MapRuntimeConfig] for the [RuntimeConfig] seam.
 *
 * ## Migration status
 *
 * This adapter is intentionally trivial. It does NOT parse the source; it
 * only resolves an id. Real parsing (Kotlin Script DSL, Groovy Script DSL,
 * plain text) lands with `SimplePipelineCompiler` in LF-0201 and with the
 * full DSL migration in LF-0205.
 */
class MapPipelineCompiler(
    definitions: Map<String, PipelineDefinition>,
) : PipelineCompiler {

    private val definitionsView: Map<String, PipelineDefinition> = definitions.toMap()

    override fun compile(source: String): CompileResult {
        if (source.isBlank()) {
            return CompileResult.Failure(
                listOf(
                    PipelineDiagnostic(
                        line = 1,
                        column = 1,
                        message = "Pipeline source must not be blank",
                    )
                )
            )
        }
        val resolved = definitionsView[source]
            ?: return CompileResult.Failure(
                listOf(
                    PipelineDiagnostic(
                        line = 1,
                        column = 1,
                        message = "No pipeline registered for the supplied source identifier",
                    )
                )
            )
        return CompileResult.Success(resolved)
    }

    /** Returns an empty [MapPipelineCompiler] for tests that need a stub. */
    companion object {
        fun empty(): MapPipelineCompiler = MapPipelineCompiler(emptyMap())
    }
}
