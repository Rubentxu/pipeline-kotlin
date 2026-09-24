package dev.rubentxu.pipeline.v2.spike.stagescoped

/**
 * Pure façade for runtime-returning calls captured inside a stage-scoped
 * block. This is the **adapter** boundary the spike defines.
 *
 * Each method returns a typed [SuspendOutcome]; nothing is `Any?` and no
 * method has a stringly-typed mode. This mirrors the BodyExecutionPolicy
 * lesson from B10/W1b.
 *
 * The spike never inspects `CanonicalRuntimeContext` or any coordinator
 * state — it consumes only this narrow port. Production adapter code (in
 * the canonical path, NOT in the spike module) will implement this port
 * by translating calls to existing typed runtime services. The spike
 * module ships only an in-memory implementation for its own tests; the
 * production adapter is a separate work item that, if it ever lands, will
 * live in :pipeline-application or a sibling module — and the spike stays
 * untouched.
 */
interface SuspendRuntimeFacade {

    /** `pwd(tmp = true)` selects a deterministic temp workspace. */
    fun pwd(tmp: Boolean): SuspendOutcome

    /** Read a workspace file. Empty file or missing-file rejection are caller's policy. */
    fun readFile(file: String): SuspendOutcome

    /** Test a workspace file's existence. */
    fun fileExists(file: String): SuspendOutcome

    /** Shell with `returnStdout = true`. [encoding] is null when caller accepts the default. */
    fun shReturnStdout(script: String, encoding: String?): SuspendOutcome

    /** OS-conditional: true on POSIX, false otherwise. */
    fun isUnix(): SuspendOutcome
}
