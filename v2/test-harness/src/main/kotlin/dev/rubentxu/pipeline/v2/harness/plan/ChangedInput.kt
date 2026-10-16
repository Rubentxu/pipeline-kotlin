package dev.rubentxu.pipeline.v2.harness.plan

/**
 * Closed ADT of changed inputs, classifying how a path can be part of a
 * product-relevant change. The classification is total: every path falls into
 * exactly one case. `UnclassifiedProduction` is the safety valve that fails
 * closed (`Refused`) instead of silently falling through.
 *
 * The decider is pure: it does not call `ProcessBuilder`, `Files.exists`, or
 * any I/O. The classification is the input, not the result of inspecting the
 * path on disk.
 */
sealed interface ChangedInput {
    /** A `.kt` source file under `src/main/`. */
    data class KotlinSource(val path: String) : ChangedInput

    /** A `.java` source file under `src/main/`. */
    data class JavaSource(val path: String) : ChangedInput

    /** A `.proto` schema. */
    data class ProtoSchema(val path: String) : ChangedInput

    /**
     * A META-INF/services descriptor (ServiceLoader file). A change here is
     * product-bearing even when the underlying classes do not change: discovery
     * may now resolve a different plugin.
     */
    data class ServiceDescriptor(val path: String) : ChangedInput

    /** A resource under `src/main/resources/` that ships in the JAR. */
    data class Resource(val path: String) : ChangedInput

    /** A `build.gradle.kts` or `settings.gradle.kts` line that affects build. */
    data class BuildLogic(val path: String) : ChangedInput

    /** A script under `scripts/` that ships with the distribution. */
    data class ExecutableScript(val path: String) : ChangedInput

    /**
     * A production-bearing path that does not fit any other case. The decider
     * MUST refuse it (`Refused(UnclassifiedInput)`), not collapse it to an
     * empty plan.
     */
    data class UnclassifiedProduction(val path: String) : ChangedInput

    /**
     * Anything that is not part of the product: tests, harness, docs, fixtures.
     * This case is allowed to produce an empty plan; the planner still records
     * the input for traceability.
     */
    data class NonProduction(val path: String) : ChangedInput
}
