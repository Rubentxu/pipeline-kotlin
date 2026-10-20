package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CompileResult
import dev.rubentxu.pipeline.v2.domain.Edge
import dev.rubentxu.pipeline.v2.domain.EdgeKind
import dev.rubentxu.pipeline.v2.domain.PipelineCompiler
import dev.rubentxu.pipeline.v2.domain.PipelineDefinition
import dev.rubentxu.pipeline.v2.domain.PipelineDiagnostic
import dev.rubentxu.pipeline.v2.domain.DefinitionId
import dev.rubentxu.pipeline.v2.domain.StepDescriptor

/**
 * Trivial line-based [PipelineCompiler] used to demonstrate and exercise
 * the M2 contract without committing to a full DSL parser.
 *
 * ## Syntax
 *
 * ```text
 * # comment lines start with #
 * name hello                              # required: pipeline name
 * version 0.0.0                           # required: pipeline version
 * step <type> <id> [configRef]            # one per step; configRef defaults to id
 * edge <fromId> <toId> [SEQUENTIAL|PARALLEL|CONDITIONAL]   # one per edge
 * ```
 *
 * Blank lines and lines starting with `#` are ignored. Whitespace is
 * collapsed but otherwise significant. The source identifier (the `String`
 * passed to [compile]) is the [DefinitionId.value] of the resulting
 * pipeline — callers typically derive it from the source path with
 * [dev.rubentxu.pipeline.v2.domain.DeterministicIdGenerator].
 *
 * ## Why this exists
 *
 * The M2 characterise-parity gate needs an application-side adapter to
 * satisfy the canonical-authority pin in `FArchM2CanonicalPipelineCompilerTest`.
 * This is the trivial placeholder that ships in LF-0201. The real
 * DSL parser (Kotlin Script / Groovy Script) lands in LF-0205 along with
 * the [RunCoordinator] migration; the test-friendly [MapPipelineCompiler]
 * already covers characterisation needs.
 *
 * ## Forward-compatibility note
 *
 * The compiler intentionally does NOT fail on `PARALLEL` or `CONDITIONAL`
 * edges — those are forward declarations accepted by the M2 surface, with
 * runtime semantics flattened to sequential by [RunCoordinator] until
 * LF-0207 / LF-0307 land. See [Edge] KDoc for the migration cadence.
 */
class SimplePipelineCompiler : PipelineCompiler {

    override fun compile(source: String): CompileResult {
        val diagnostics = mutableListOf<PipelineDiagnostic>()
        var name: String? = null
        var version: String? = null
        val steps = mutableListOf<StepDescriptor>()
        val edges = mutableListOf<Edge>()

        source.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val tokens = line.split(WHITESPACE).filter { it.isNotEmpty() }
            when (val head = tokens.firstOrNull()) {
                null -> {
                    // line of pure whitespace — already filtered
                }
                "name" -> {
                    val value = tokens.getOrNull(1)
                    if (value == null) {
                        diagnostics += PipelineDiagnostic(lineNumber, 1, "`name` requires a value")
                    } else if (name != null) {
                        diagnostics += PipelineDiagnostic(lineNumber, 1, "duplicate `name` directive")
                    } else {
                        name = value
                    }
                }
                "version" -> {
                    val value = tokens.getOrNull(1)
                    if (value == null) {
                        diagnostics += PipelineDiagnostic(lineNumber, 1, "`version` requires a value")
                    } else if (version != null) {
                        diagnostics += PipelineDiagnostic(lineNumber, 1, "duplicate `version` directive")
                    } else {
                        version = value
                    }
                }
                "step" -> {
                    val type = tokens.getOrNull(1)
                    val id = tokens.getOrNull(2)
                    val configRef = tokens.getOrNull(3) ?: id
                    when {
                        type == null -> diagnostics += PipelineDiagnostic(
                            lineNumber, 1, "`step` requires a type and an id"
                        )
                        id == null -> diagnostics += PipelineDiagnostic(
                            lineNumber, 1, "`step $type` requires an id"
                        )
                        steps.any { it.id == id } -> diagnostics += PipelineDiagnostic(
                            lineNumber, 1, "duplicate step id '$id'"
                        )
                        else -> steps += StepDescriptor(stepId = id, name = type, configRef = configRef!!)
                    }
                }
                "edge" -> {
                    val from = tokens.getOrNull(1)
                    val to = tokens.getOrNull(2)
                    val kindToken = tokens.getOrNull(3) ?: "SEQUENTIAL"
                    val kind = parseEdgeKind(kindToken)
                    when {
                        from == null -> diagnostics += PipelineDiagnostic(
                            lineNumber, 1, "`edge` requires a source step id"
                        )
                        to == null -> diagnostics += PipelineDiagnostic(
                            lineNumber, 1, "`edge $from` requires a target step id"
                        )
                        kind == null -> diagnostics += PipelineDiagnostic(
                            lineNumber, 1, "unknown edge kind '$kindToken'; valid: SEQUENTIAL, PARALLEL, CONDITIONAL"
                        )
                        else -> edges += Edge(from = from, to = to, kind = kind)
                    }
                }
                else -> diagnostics += PipelineDiagnostic(
                    lineNumber, 1, "unknown directive '$head'; valid: name, version, step, edge"
                )
            }
        }

        if (name == null) diagnostics += PipelineDiagnostic(null, null, "missing `name` directive", PipelineDiagnostic.Severity.ERROR)
        if (version == null) diagnostics += PipelineDiagnostic(null, null, "missing `version` directive", PipelineDiagnostic.Severity.ERROR)

        if (diagnostics.isNotEmpty()) return CompileResult.Failure(diagnostics)

        val definition = PipelineDefinition(
            id = DefinitionId(name!!),
            name = name!!,
            version = version!!,
            steps = steps.toList(),
            edges = edges.toList(),
        )
        return CompileResult.Success(definition)
    }

    private fun parseEdgeKind(token: String): EdgeKind? = when (token.uppercase()) {
        "SEQUENTIAL" -> EdgeKind.SEQUENTIAL
        "PARALLEL" -> EdgeKind.PARALLEL
        "CONDITIONAL" -> EdgeKind.CONDITIONAL
        else -> null
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
