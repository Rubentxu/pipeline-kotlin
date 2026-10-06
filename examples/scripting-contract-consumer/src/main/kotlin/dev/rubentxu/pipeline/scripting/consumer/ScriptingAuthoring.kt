package dev.rubentxu.pipeline.scripting.consumer

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.dsl.pipeline

/**
 * P3-E E6 — the authoring surface, seen from a consumer that declares ONE coordinate.
 *
 * This file names `FailureKind`, a type that lives in `pipeline-domain`. This build does NOT
 * declare `pipeline-domain`: it resolves `pipeline-scripting-api` and nothing else from PipelineK.
 * So the only route by which that name can resolve is the publisher's Gradle Module Metadata —
 * `api(project(":pipeline-domain"))` rather than `implementation(...)`.
 *
 * That is the property. It is invisible from inside the publishing build, and invisible from the
 * four-coordinate consumer, which declares `pipeline-domain` itself and would therefore compile
 * either way. Here, reverting the publisher's scope makes this file fail to compile.
 *
 * What it also fixes, at runtime, is the ergonomic claim: `error("msg")` — the form the vast
 * majority of authors write — still resolves with no vocabulary written into the pipeline and no
 * ceremony, because the default carries it. Only the explicit non-default case names
 * `FailureKind.X`, and that is exactly the case where compile-time checking is worth an import.
 */
object ScriptingAuthoring {

    /** The habitual form. Nothing about `FailureKind` is written by the author. */
    fun defaultForm(): StepSpec.Error = pipeline {
        stages {
            stage("Build") {
                error("boom")
            }
        }
    }.stages.single().steps.single() as StepSpec.Error

    /** The explicit form: the source-compatibility break, written in its new shape. */
    fun explicitForm(kind: FailureKind): StepSpec.Error = pipeline {
        stages {
            stage("Build") {
                error("boom", kind)
            }
        }
    }.stages.single().steps.single() as StepSpec.Error

    /**
     * The durable token. `FailureKind.name` IS the historical token, so an observer reading
     * `"USER"` from the stream cannot tell this record from one written before the change.
     */
    fun wireToken(kind: FailureKind): String = kind.name
}