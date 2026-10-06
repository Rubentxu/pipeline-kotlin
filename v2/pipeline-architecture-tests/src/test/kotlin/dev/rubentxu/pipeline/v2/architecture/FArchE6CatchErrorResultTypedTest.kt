package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.domain.CatchErrorBuildResult
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.CompiledPipeline
import dev.rubentxu.pipeline.v2.domain.ContextOverlay
import dev.rubentxu.pipeline.v2.application.StructuralOverlay
import dev.rubentxu.pipeline.v2.application.StructuralOverlayProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * P3-E D3 — the `catchError` result is typed end to end, and the two representations it
 * crosses are pinned separately.
 *
 * ## What D3 changed and what it had to leave alone
 *
 * `stageResult` and `buildResult` used to be `String` in all four layers, and the producer
 * normalised them with `?.uppercase()` on the way to the wire. D3 made them
 * [CatchErrorBuildResult] everywhere. Two things had to stay byte-identical while that
 * happened, and each gets its own law because they fail independently:
 *
 * - the **event wire**, which has always carried `"UNSTABLE"` and not `"Unstable"`;
 * - the **compiled-pipeline IR**, where `ContextOverlay.CatchErrorOverlay` is
 *   `@Serializable` and stores the result as a bare JSON string. kotlinx's default sealed
 *   handling would emit `{"type":"UNSTABLE"}` — a different document — so the type carries
 *   its own [CatchErrorBuildResult.Serializer].
 *
 * ## Why these are fitness laws and not unit tests
 *
 * Both properties are about the SHAPE OF THE CODE, not about one value's behaviour. A
 * well-meaning future change that swaps the serializer for the default one, or reintroduces
 * a `String` parameter, compiles and passes every behavioural test in the repository. These
 * laws are what notice.
 */
@DisplayName("P3-E D3 — catchError result is typed end to end, with the wire and the IR pinned")
class FArchE6CatchErrorResultTypedTest {

    private val domainRoot: File =
        File("../pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain")
    private val eventsRoot: File =
        File("../pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events")
    private val scriptingRoot: File =
        File("../pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl")
    private val applicationRoot: File =
        File("../pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application")

    /**
     * Strips comments before scanning.
     *
     * The first version of the normalisation law matched its OWN documentation: the KDoc
     * explaining that `stageResult?.uppercase()` was removed contains the text
     * `stageResult?.uppercase()`, so the law reported the very line written to document the
     * fix. That is not a one-off — it is what any source-scanning law does unless it is told
     * the difference between a fact and a description of a fact.
     */
    private fun File.code(): String = readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .map { it.substringBefore("//") }
        .joinToString("\n")

    @Test
    fun `no layer declares the catchError result as a String`() {
        // The defect this closes was never one declaration: it was a String at authoring, in
        // the overlay, in the invocation, and on the event, with the runtime's `else` arm as
        // the only reader. Fixing one layer at a time leaves the same hole with fewer letters.
        val offenders = listOf(
            scriptingRoot.resolve("StepSpec.kt"),
            scriptingRoot.resolve("StageScope.kt"),
            domainRoot.resolve("CompiledPipeline.kt"),
            eventsRoot.resolve("DomainEvent.kt"),
            applicationRoot.resolve("CanonicalInvocation.kt"),
        ).filter { file ->
            file.readText().lineSequence().any { line ->
                val t = line.trim()
                (t.startsWith("val stageResult: String") || t.startsWith("val buildResult: String")) &&
                    !t.contains("//")
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "these files still declare a catchError result as a String: ${offenders.map { it.name }} — " +
                "the typed chain is broken at the first of them",
        )
    }

    @Test
    fun `the producer no longer normalises an authoring String`() {
        // `stageResult?.uppercase() ?: effectiveBuildResult` meant the token that reached
        // durable history was one the author never wrote. With the value typed at authoring,
        // normalisation is not merely unnecessary: there is nothing left to normalise.
        val offenders = listOf(
            applicationRoot.resolve("DslCompiledPipelineCompiler.kt"),
        ).filter { file ->
            Regex("""(stageResult|buildResult)\?*\.(uppercase|lowercase)\(""")
                .containsMatchIn(file.code())
        }

        assertTrue(
            offenders.isEmpty(),
            "the compiler still case-normalises a declared result in ${offenders.map { it.name }}",
        )
    }

    @Test
    fun `the overlay keeps travelling as a bare JSON string`() {
        // This is the assertion that would catch the default kotlinx sealed serializer. If the
        // explicit serializer is dropped, the encoded document becomes {"type":"UNSTABLE"},
        // the compiled-pipeline format changes, and no behavioural test in the repository
        // would report it.
        val overlay = ContextOverlay.CatchErrorOverlay(
            buildResult = CatchErrorBuildResult.Unstable,
            stageResult = CatchErrorBuildResult.Failure,
            message = null,
            enteredAt = 1L,
        )

        val encoded = Json.encodeToString(
            ContextOverlay.serializer(),
            overlay,
        )

        assertTrue(
            encoded.contains("\"buildResult\":\"UNSTABLE\""),
            "the overlay must carry a bare string, encoded was $encoded",
        )
        assertTrue(
            encoded.contains("\"stageResult\":\"FAILURE\""),
            "the overlay must carry a bare string, encoded was $encoded",
        )
        // NOT asserted: the absence of a `type` key. `ContextOverlay` is a @Serializable sealed
        // hierarchy and kotlinx has always written a discriminator for it, so its presence is
        // pre-existing and not this serializer's doing. What this law owns is the VALUE shape:
        // a bare string, never a nested object. A first draft asserted `!contains("type")` and
        // failed against a discriminator it did not introduce.
        assertTrue(
            !Regex(""""buildResult"\s*:\s*\{""").containsMatchIn(encoded),
            "buildResult leaked into a nested object instead of a bare string: $encoded",
        )
        assertTrue(
            !Regex(""""stageResult"\s*:\s*\{""").containsMatchIn(encoded),
            "stageResult leaked into a nested object instead of a bare string: $encoded",
        )
    }

    @Test
    fun `a token outside the vocabulary installs no overlay`() {
        // The fail-open this replaces read `?: "UNSTABLE"`, and UNSTABLE is the claim that
        // SUPPRESSES the failure the scope exists to catch. Refusing to install the overlay
        // puts the failure back on its natural path: nothing catches it, so the run fails.
        val envelope = buildJsonObject {
            put("kind", "CatchErrorEntered")
            put("buildResult", "FALURE")
            put("stageResult", "FALURE")
        }

        val overlay = StructuralOverlayProjection.project(
            PluginStepId("core.emit.event"),
            envelope,
        )

        assertEquals(
            StructuralOverlay.None,
            overlay,
            "an unreadable result must install no overlay, got $overlay",
        )
    }

    @Test
    fun `a valid token still installs the overlay it always did`() {
        // The negative row above is worthless if the positive one regressed with it, so both
        // live here: refuse the unreadable, and keep honouring the readable.
        val envelope = buildJsonObject {
            put("kind", "CatchErrorEntered")
            put("buildResult", "SUCCESS")
            put("stageResult", "FAILURE")
        }

        val overlay = StructuralOverlayProjection.project(
            PluginStepId("core.emit.event"),
            envelope,
        )

        assertEquals(
            StructuralOverlay.CatchErrorEntered(
                buildResult = CatchErrorBuildResult.Success,
                stageResult = CatchErrorBuildResult.Failure,
                message = null,
                enteredAt = null,
            ),
            overlay,
        )
    }

    @Test
    fun `the vocabulary is declared on the cases and the map derives from it`() {
        // SUPPORTED used to repeat the three literals a second time. Two lists of the same
        // vocabulary is two authorities, and they drift the first time someone adds a case.
        // The tokens now live on the cases and the map is built from them, so a fourth case
        // cannot be added to one and forgotten in the other.
        // `data object` has no `name` — that is a property of enums — so the expectation is
        // written as literals, which is also the only form that would notice if a case ever
        // shipped a token other than its own upper-case name.
        val expectations = mapOf<CatchErrorBuildResult, String>(
            CatchErrorBuildResult.Success to "SUCCESS",
            CatchErrorBuildResult.Unstable to "UNSTABLE",
            CatchErrorBuildResult.Failure to "FAILURE",
        )

        for ((case, token) in expectations) {
            assertEquals(token, case.wireToken, "$case must travel as $token")
            assertEquals(
                case,
                CatchErrorBuildResult.parse(case.wireToken),
                "the map derived from the cases must accept every case's own token",
            )
        }

        assertEquals(
            expectations.values.toSet(),
            CatchErrorBuildResult.supportedTokens,
            "the declared tokens and the parse map must be the same vocabulary, not two lists",
        )

        assertEquals(
            setOf("SUCCESS", "UNSTABLE", "FAILURE"),
            CatchErrorBuildResult.supportedTokens,
            "widening the vocabulary needs a decision, not a typo",
        )
    }

    @Test
    fun `the serializer refuses an unknown token instead of decoding it`() {
        // Three refusal seams exist for D3 and each is tested somewhere; this is the one that
        // protects persisted IR. A serializer that decoded unknown input to a default would
        // reintroduce the original defect one layer below the type system.
        val payload = "{\"buildResult\":\"FALURE\",\"stageResult\":\"FAILURE\",\"message\":null,\"enteredAt\":1}"
        val decoded = runCatching {
            Json.decodeFromString(
                ContextOverlay.serializer(),
                payload,
            )
        }

        assertTrue(
            decoded.isFailure,
            "an unknown token must not decode into an overlay, got $decoded",
        )
    }
}
