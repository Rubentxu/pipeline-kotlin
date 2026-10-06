package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.FailureKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * P3-E E6 — the STABLE authoring boundary and the EXPERIMENTAL representation are two
 * different surfaces, and the difference is enforced rather than described.
 *
 * ## Why this is a fitness law and not a note
 *
 * The repository has two authorities that use the same word for different things:
 *
 * - `DSL_SURFACE_MANIFEST.md` classifies the **authoring constructs** — what a user writes
 *   inside a `.pipeline.kts`. It declares `error` STABLE and `catchError` DEPRECATED
 *   (LFC1-007).
 * - `published-contract-maturity.json` classifies the **library ABI** an external consumer
 *   compiles against. All four published modules are EXPERIMENTAL, and the file says in terms
 *   that it is "deliberately NOT DSL_SURFACE_MANIFEST ... Reusing it would conflate a DSL
 *   function with a library ABI".
 *
 * E6b and D3 typed the REPRESENTATION and, in doing so, also broke the AUTHORING signature.
 * Ten UAT scenarios that legitimately wrote `catchError(buildResult = "FAILURE")` stopped
 * compiling, and that breakage was found by tests rather than by policy.
 *
 * So the architecture to protect is:
 *
 * ```text
 * STABLE authoring surface      StageScope.catchError(buildResult: String?, ...)
 *        ↓  LegacyResultVocabulary, at construction
 * EXPERIMENTAL representation   StepSpec.CatchError(buildResult: CatchErrorBuildResult?, ...)
 *        ↓
 * typed runtime + wire          wireToken
 * ```
 *
 * Deleting the bridge is a legal-looking refactor that silently converts a STABLE surface into
 * a breaking one. Nothing in the behavioural suite would object: every migrated test would
 * still pass. These laws are what object.
 */
@DisplayName("P3-E E6 — the authoring boundary is STABLE and the representation is typed")
class FArchE6AuthoringBoundaryFitnessTest {

    private val stageScope = Class.forName("dev.rubentxu.pipeline.v2.dsl.StageScope")
    private val stepSpecCatchError = Class.forName("dev.rubentxu.pipeline.v2.dsl.StepSpec\$CatchError")
    private val stepSpecError = Class.forName("dev.rubentxu.pipeline.v2.dsl.StepSpec\$Error")

    private fun declaredOverloads(clazz: Class<*>, name: String) =
        clazz.methods.filter { it.name == name && !it.isSynthetic }

    /**
     * Whether some overload takes the RESULT as a String — that is, at the result position.
     *
     * The first version of this helper asked whether the method accepted *any* String, which
     * is true of the TYPED overload too because it still carries `message: String?`. Mutation
     * D3-M4 deleted the legacy adapter and the law still passed: it was vacuous, and a law
     * that cannot fail is worse than no law because it looks like coverage.
     */
    private fun takesStringResult(clazz: Class<*>, methodName: String) =
        declaredOverloads(clazz, methodName).any { m ->
            m.parameterCount >= 2 && m.parameterTypes[1] == String::class.java
        }

    // ---- the STABLE half must keep accepting the tokens the manifest documents -------

    @Test
    fun `catchError still accepts the documented String spelling`() {
        assertTrue(
            takesStringResult(stageScope, "catchError"),
            "StageScope.catchError no longer accepts a String. The manifest documents " +
                "catchError(buildResult?, stageResult?, message?) and it is a DEPRECATED " +
                "surface, which means its signature survives until the declared LFC1-007 " +
                "removal boundary — not until somebody found it inconvenient. Removing the " +
                "adapter is a breaking change to a published authoring surface.",
        )
    }

    @Test
    fun `error still accepts the documented String spelling`() {
        assertTrue(
            takesStringResult(stageScope, "error"),
            "StageScope.error no longer accepts a String. `error` is STABLE in the manifest, " +
                "so its signature cannot change without a major boundary.",
        )
    }

    // ---- the EXPERIMENTAL half must be typed, or the bridge would be decoration -------

    @Test
    fun `the representation is typed, so the bridge is not merely validating a String`() {
        // The inverse law, and the reason the first two are safe. If the representation went
        // back to String, the adapter would be parsing into nothing and the interior would
        // still be the old fail-open pipeline one layer down.
        val catchFields = stepSpecCatchError.declaredFields
            .filter { it.name == "buildResult" || it.name == "stageResult" }
            .associate { it.name to it.type }

        assertEquals(
            mapOf(
                "buildResult" to CatchErrorBuildResult::class.java,
                "stageResult" to CatchErrorBuildResult::class.java,
            ),
            catchFields,
            "StepSpec.CatchError must carry CatchErrorBuildResult in both result fields, " +
                "not String — otherwise the adapter validates into nothing",
        )
        assertEquals(
            FailureKind::class.java,
            stepSpecError.declaredFields.first { it.name == "failureKind" }.type,
            "StepSpec.Error.failureKind must carry FailureKind, not String",
        )
    }

    // ---- and the bridge must be the only way in -------------------------------------

    @Test
    fun `no DSL builder passes a raw String into a catchError or error spec`() {
        // The invariant the whole split exists to protect: a String may be WRITTEN by an author,
        // and it dies in the adapter. It never reaches the IR, the overlay or the runtime.
        val sources = listOf(
            "v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/StageScope.kt",
            "v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/StageScopeBuilders.kt",
            "v2/pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt",
        )
        val offenders = sources.flatMap { path ->
            val file = java.io.File(path)
            if (!file.exists()) return@flatMap emptyList()
            file.readText().lineSequence().withIndex()
                .filter { (_, line) ->
                    val t = line.trim()
                    // a StepSpec construction that hands a String straight into a result field
                    (t.startsWith("buildResult = ") || t.startsWith("stageResult = ")) &&
                        !t.contains("LegacyResultVocabulary") && !t.contains("wireToken") &&
                        !t.contains("?") && t.contains("String") || (
                        t.startsWith("buildResult = ") && t.contains("\"") && !t.contains("LegacyResultVocabulary")
                        )
                }
                .map { (i, _) -> "$path:${i + 1}" }
                .toList()
        }

        assertTrue(
            offenders.isEmpty(),
            "these lines hand a String into a typed result field, bypassing the adapter: $offenders",
        )
    }

    // ---- and the overloads must not have become ambiguous ----------------------------

    @Test
    fun `no authoring function carries two overloads that would make the bare call ambiguous`() {
        // Measured, not theorised: mutation D3-M1 added a String overload with all parameters
        // defaulted and the build failed to COMPILE with "Overload resolution ambiguity" on
        // every `catchError { }` call. A compile failure is not a RED, so the shape was
        // re-derived: the typed overload REQUIRES buildResult, which leaves each spelling with
        // exactly one candidate. This law keeps that property from being undone.
        //
        // The property, stated once: at most ONE overload may have every parameter optional.
        // Two such overloads make the zero-argument call ambiguous regardless of their types.
        for (name in listOf("catchError", "error")) {
            val overloads = declaredOverloads(stageScope, name)
            assertTrue(
                overloads.size == 2,
                "$name must expose exactly two spellings on StageScope, got ${overloads.size}: " +
                    overloads.joinToString { m -> m.parameterTypes.joinToString(prefix = "(", postfix = ")") },
            )

            // Count by the RESULT parameter, not by "has no String anywhere": the typed
            // spelling still carries `message: String?`, so a naive no-String count returns
            // zero and the law measures nothing. What must hold is that the two overloads
            // differ in the type of the result they take.
            val resultTypes = overloads.map { m -> m.parameterTypes[1] }
            assertEquals(
                1,
                resultTypes.count { it != String::class.java },
                "$name must expose exactly one spelling whose result is not a String, got " +
                    resultTypes.joinToString { it.simpleName },
            )
        }
    }

    // ---- and the deprecation claim must match the code ------------------------------

    @Test
    fun `the manifest does not claim STABLE for a construct the code deprecates`() {
        // The divergence this law exists to prevent: the manifest said STABLE while the code
        // had carried @Deprecated(LFC1-007) for a long time, and no test looked. It is a
        // governance fact that must be mechanically consistent, because a STABLE row is what
        // makes a bridge mandatory and a wrong one makes it look optional.
        val manifest = FitnessPaths.v2Root().resolve("../docs/v2/surface/DSL_SURFACE_MANIFEST.md").toFile().readText()
        val claims = Regex("""^\| (\w+) \|.*\| (STABLE|PARTIAL|EXPERIMENTAL|DEPRECATED) \|""", RegexOption.MULTILINE)
            .findAll(manifest)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toMap()

        val deprecationLaw = manifest.contains("LFC1-007")
        assertTrue(
            deprecationLaw,
            "sanity: the manifest is expected to mention LFC1-007 for catchError",
        )
        assertEquals(
            "DEPRECATED",
            claims["catchError"],
            "the manifest still claims ${claims["catchError"]} for catchError while the code " +
                "carries @Deprecated(LFC1-007). A STABLE claim here is what would authorise a " +
                "bridge removal by accident.",
        )
    }

    @Test
    fun `declaredOverloads only sees public methods, so the law is not inspecting bridges`() {
        // If reflection stopped seeing the adapter for any reason (an internal modifier, a
        // synthetic bridge from a default argument), the two laws above would pass vacuously.
        // This asserts the instrument still works.
        val overloads = declaredOverloads(stageScope, "catchError")
        assertTrue(overloads.isNotEmpty(), "reflection found no catchError overload at all")
        assertTrue(
            overloads.all { Modifier.isPublic(it.modifiers) },
            "the adapter must be PUBLIC — a private one would be unreachable from a .pipeline.kts",
        )
        assertEquals(
            2,
            overloads.size,
            "catchError must expose exactly the legacy and the typed spelling, got ${overloads.size}",
        )
    }
}
