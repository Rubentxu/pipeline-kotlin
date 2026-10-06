package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.FailureKind

/**
 * P3-E E6 — the STABLE authoring boundary for results whose vocabulary is CLOSED.
 *
 * ## Why this object exists at all
 *
 * `error(message, failureKind)` and `catchError(buildResult?, stageResult?, message?)` are
 * declared **STABLE** in `DSL_SURFACE_MANIFEST.md`. `StepSpec.Error`,
 * `StepSpec.CatchError` and `ContextOverlay.CatchErrorOverlay` are the **EXPERIMENTAL
 * representation** an external consumer compiles against
 * (`published-contract-maturity.json` classifies all four published modules EXPERIMENTAL,
 * and says so deliberately: the manifest classifies authoring constructs, that file
 * classifies library ABI).
 *
 * Typing the representation is therefore allowed. Typing the *authoring signature* was not,
 * and it silently turned `error("boom", "USER")` and `catchError(buildResult = "FAILURE")`
 * — spellings the manifest documents — into compile errors. Ten UAT scenarios failed on
 * exactly that, which is how it was found.
 *
 * So the split is:
 *
 * ```text
 * STABLE authoring surface      String token, validated HERE, at script-construction time
 *        ↓
 * EXPERIMENTAL representation   FailureKind / CatchErrorBuildResult
 *        ↓
 * typed runtime + wire          wireToken, the historical spelling
 * ```
 *
 * ## The invariant this protects
 *
 * **A String never crosses into the IR or the runtime.** It dies here, or it never existed.
 *
 * That is the whole point, and it is stronger than "the signature still compiles". Before
 * this, an unrecognised token compiled, ran, and reached a decision whose `else` arm read it
 * as `UNSTABLE` — the reading that SUPPRESSES the failure the scope was installed to catch.
 * Now the same token cannot construct the pipeline at all.
 *
 * ## Why the throw, and why here
 *
 * Thrown from the builder, which runs during script evaluation. The scripting host already
 * maps a fail-closed builder throw into a **compilation** failure rather than a StepFailed
 * (see `Kotlin24ScriptingHost`), so the refusal arrives in the honest category: the pipeline
 * could not be BUILT, nothing was admitted, and no `RunStarted` is emitted.
 *
 * A `default` here would be the original defect one layer up. There is none, on either type.
 */
internal object LegacyResultVocabulary {

    /**
     * Validates a legacy `error` spelling.
     *
     * @throws IllegalArgumentException naming the token AND the vocabulary, because a message
     * that only says "invalid" sends the author looking for the problem in the wrong place.
     */
    fun failureKind(token: String): FailureKind =
        FailureKind.parse(token) ?: refuse(token, FailureKind.supportedTokens, "error(message, failureKind)")

    /**
     * Validates a legacy `catchError` spelling. `null` means "not declared", which is a
     * legitimate absence and is passed through — only a PRESENT token outside the vocabulary
     * is corruption.
     */
    fun catchBuildResult(token: String?, param: String): CatchErrorBuildResult? =
        token?.let { CatchErrorBuildResult.parse(it) ?: refuse(it, CatchErrorBuildResult.supportedTokens, "catchError($param)") }

    /** Validates a legacy `catchError` stage spelling; same absence-is-legal rule as above. */
    fun catchStageResult(token: String?): CatchErrorBuildResult? = catchBuildResult(token, "stageResult")

    private fun refuse(token: String, supported: Set<String>, construct: String): Nothing =
        throw IllegalArgumentException(
            "$construct does not accept '$token'. Supported: ${supported.sorted().joinToString(", ")}. " +
                "The vocabulary is closed, so this pipeline cannot be built; it fails here rather " +
                "than running and mis-classifying the result.",
        )
}
