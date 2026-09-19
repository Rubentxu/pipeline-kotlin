package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import java.nio.file.Files
import java.nio.file.Path

/**
 * LFC1-008 canonical step coverage fitness test.
 *
 * Verifies that the canonical execution path covers all corpus fixtures
 * and that the ALLOWED_EMIT_EVENT_KINDS whitelist contains only canonical step kinds.
 *
 * Covers EC-12 (FArchLfc1CanonicalCoverageTest 2/2).
 */
@TestInstance(Lifecycle.PER_CLASS)
class FArchLfc1CanonicalCoverageTest {

    private val compatibilityDir = FitnessPaths.v2Root().resolve("compatibility")
    // S2-A4 / G5: the legacy CanonicalEmitEventNodeDispatcher was removed. The whitelist
    // authority now lives in CoreEmitEventStep (registry-routed StepDefinition).
    private val dispatcherPath = FitnessPaths.v2Root()
        .resolve("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreEmitEventStep.kt")
    private val baselinePath = compatibilityDir.resolve("baseline.json")

    /** Canonical step kinds that are allowed in the dispatcher whitelist. */
    private val canonicalEventKinds = setOf(
        "CatchErrorEntered",
        "CatchErrorTriggered",
        "StageMarkedUnstable",
        "FileWritten",
    )

    /** Legacy step kinds that must NOT appear in the whitelist. */
    private val forbiddenEventKinds = setOf(
        "Entered",
        "Emitted",
        "Triggered",
    )

    @Test
    fun `ALLOWED_EMIT_EVENT_KINDS contains only canonical event kinds`() {
        val source = Files.readString(dispatcherPath)

        // Extract the ALLOWED_EMIT_EVENT_KINDS set contents
        val match = Regex("""ALLOWED_KINDS\s*=\s*setOf\(\s*(\[[^\]]*])\s*\)""")
            .find(source) ?: Regex("""ALLOWED_KINDS\s*=\s*setOf\(\s*([\s\S]*?)\n\s*\)""").find(source)

        assertTrue(
            match != null,
            "ALLOWED_KINDS must be declared in CoreEmitEventStep",
        )

        val kindsBlock = match!!.groupValues[1]

        // Verify no legacy kind names appear
        forbiddenEventKinds.forEach { legacy ->
            assertFalse(
                kindsBlock.contains("\"$legacy\""),
                "ALLOWED_KINDS must NOT contain legacy kind '$legacy' — use canonical form",
            )
        }

        // Verify canonical kinds are present
        canonicalEventKinds.forEach { canonical ->
            assertTrue(
                kindsBlock.contains("\"$canonical\""),
                "ALLOWED_KINDS must contain canonical kind '$canonical'",
            )
        }
    }

    @Test
    fun `baseline json declares its schema version`() {
        // WU-LPR-071 reconciliation: the corpus baseline declares `schemaVersion: "v1"`.
        // The historical "1.4" expectation predates the LFC-2 event envelope and no
        // producer emits it; the canonical event schema is now guarded by the
        // 45-variant DomainEvent round-trip suite (FArchL7 / DomainEventRoundTripTest).
        val source = Files.readString(baselinePath)
        val versionMatch = Regex("\"schemaVersion\"\\s*:\\s*\"([^\"]+)\"").find(source)

        assertTrue(
            versionMatch != null,
            "baseline.json must declare a schemaVersion field",
        )

        val version = versionMatch!!.groupValues[1]
        assertEquals(
            "v1",
            version,
            "baseline.json schemaVersion must be v1 (the only corpus baseline schema)",
        )
    }

    @Test
    fun `corpus fixtures only use canonical step kinds`() {
        val fixtureFiles = Files.list(compatibilityDir)
            .filter { it.toString().endsWith(".pipeline.kts") }
            .toList()

        assertTrue(
            fixtureFiles.isNotEmpty(),
            "compatibility directory must contain at least one .pipeline.kts fixture",
        )

        // Fixtures 11 and 12 use catchError/warnError intentionally —
        // they test the pre-compiler rewrite to EmitEvent nodes.
        // All other fixtures (01-10, 13-14) must use canonical step kinds in source form.
        val preCompilerRewriteFixtures = setOf("11", "12")
        val findings = mutableListOf<Pair<Path, String>>()

        fixtureFiles.forEach { file ->
            val fileName = file.fileName.toString()
            val idMatch = Regex("""^(\d+)-""").find(fileName)
            val fixtureId = idMatch?.groupValues?.get(1) ?: ""

            // Skip pre-compiler-rewrite test fixtures
            if (fixtureId in preCompilerRewriteFixtures) return@forEach

            val content = Files.readString(file)

            // Legacy step kinds in non-rewrite fixtures
            listOf("catchError", "warnError", "unstable", "archive", "withEnv").forEach { legacy ->
                if (Regex("""\b$legacy\s*\{""").containsMatchIn(content)) {
                    findings.add(file to legacy)
                }
            }
        }

        assertTrue(
            findings.isEmpty(),
            "Non-rewrite corpus fixtures must only use canonical step kinds. Found legacy kinds in: $findings",
        )
    }
}
