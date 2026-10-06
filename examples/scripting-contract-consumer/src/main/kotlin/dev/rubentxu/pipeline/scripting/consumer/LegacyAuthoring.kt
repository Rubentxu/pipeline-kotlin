package dev.rubentxu.pipeline.scripting.consumer

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline

/**
 * P3-E E6 — the STABLE authoring surface, seen from the outside.
 *
 * ## Why this file exists next to [ScriptingAuthoring]
 *
 * The typed consumer proves the NEW API. This one proves that the OLD API still works, and those
 * are different claims with different failure modes:
 *
 * - if the typed path regresses, the new surface is broken;
 * - if the legacy path regresses, a **published STABLE surface** is broken, and every pipeline
 *   in the wild that writes `catchError(buildResult = "FAILURE")` stops compiling.
 *
 * The second one was actually broken once. E6b typed `error`'s second parameter and D3 typed
 * `catchError`'s, and ten UAT scenarios that legitimately wrote the Jenkins spelling stopped
 * compiling. Nothing in the behavioural suite objected — the migrated tests passed fine. Only
 * the ones that had NOT been migrated failed, which is the worst possible signature: a test
 * suite that is green precisely because it stopped testing the old spelling.
 *
 * So the legacy spelling gets its own proof, on purpose, kept compiling forever or until the
 * declared removal boundary.
 *
 * ## What is asserted here and what is not
 *
 * Asserted: the legacy spelling compiles, produces the SAME typed state as the typed spelling,
 * and projects the SAME durable token. "Compiles" alone would be weak — two spellings could
 * compile and disagree. The comparison against [ScriptingAuthoring] is the real property.
 *
 * Not asserted: that a misspelled token refuses at runtime. That happens during SCRIPT
 * construction, before a run exists, and this build has no scripting host. The refusal is proven
 * in-process by `LegacyResultVocabularyTest` and across the host by
 * `ErrorHandlingTest`, and repeating it here would prove less, not more.
 */
object LegacyAuthoring {

    /** The Jenkins spelling. Declared STABLE, so it must keep working. */
    fun errorLegacy(message: String, kind: String): StepSpec.Error = pipeline {
        stages {
            stage("Build") {
                error(message, kind)
            }
        }
    }.stages.single().steps.single() as StepSpec.Error

    /** The habitual form: no vocabulary at all. */
    fun errorDefault(message: String): StepSpec.Error = pipeline {
        stages {
            stage("Build") {
                error(message)
            }
        }
    }.stages.single().steps.single() as StepSpec.Error

    /**
     * The Jenkins catchError spelling, with BOTH optional result parameters.
     *
     * This is the exact form the manifest documents and the compatibility corpus still writes.
     */
    fun catchErrorLegacy(
        buildResult: String,
        stageResult: String,
    ): StepSpec.CatchError = pipeline {
        stages {
            stage("Build") {
                catchError(buildResult = buildResult, stageResult = stageResult) {
                    sh("exit 1")
                }
            }
        }
    }.stages.single().steps.single() as StepSpec.CatchError

    /** The same scope with no declared results, which must stay an absence on both fields. */
    fun catchErrorBare(): StepSpec.CatchError = pipeline {
        stages {
            stage("Build") {
                catchError {
                    sh("exit 1")
                }
            }
        }
    }.stages.single().steps.single() as StepSpec.CatchError

    /** The new spelling, for the equivalence assertion below. */
    fun catchErrorTyped(
        buildResult: CatchErrorBuildResult,
        stageResult: CatchErrorBuildResult,
    ): StepSpec.CatchError = pipeline {
        stages {
            stage("Build") {
                catchError(buildResult, stageResult) {
                    sh("exit 1")
                }
            }
        }
    }.stages.single().steps.single() as StepSpec.CatchError

    /** The durable token a catchError result has always carried. */
    fun catchWireToken(result: CatchErrorBuildResult): String = result.wireToken

    /** The durable token an error kind has always carried. */
    fun errorWireToken(kind: FailureKind): String = kind.name
}