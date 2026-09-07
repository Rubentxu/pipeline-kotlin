package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * LFC0-006 / LFC0-007.
 *
 * The local-first runtime must carry workspace state explicitly. Production
 * code may not recover the old global working-directory or debug-write paths.
 */
class Lfc0GlobalStateFitnessTest {

    @Test
    fun `production code does not access the controller user directory property`() {
        assertNoProductionOccurrences(
            "System.setProperty(\"user.dir\"",
            "System.getProperty(\"user.dir\"",
        )
    }

    @Test
    fun `production code does not retain the removed credentials debug directory`() {
        assertNoProductionOccurrences("/tmp/uat008-debug")
    }

    private fun assertNoProductionOccurrences(vararg forbidden: String) {
        val findings = Files.walk(ScannerSupport.v2Root())
            .use { paths ->
                paths
                    .filter { it.isProductionKotlinSource() }
                    .flatMap { path ->
                        Files.readAllLines(path).mapIndexedNotNull { index, line ->
                            forbidden.firstOrNull(line::contains)?.let { token ->
                                Finding(path, index + 1, token, line.trim())
                            }
                        }.stream()
                    }
                    .toList()
            }

        assertTrue(findings.isEmpty(), "Forbidden production global-state access: $findings")
    }

    private fun Path.isProductionKotlinSource(): Boolean =
        toString().contains("/src/main/") && fileName.toString().endsWith(".kt") &&
            // The `SystemRuntimeConfig` adapter is the canonical bridge between
            // the production runtime and JVM/OS global state. It is the ONLY
            // site allowed to call `System.getenv` / `System.getProperty`; that
            // invariant is enforced separately by
            // `FArchM1CanonicalRuntimeConfigTest`. Excluding the adapter from
            // this fitness scan keeps the two constraints coherent: the adapter
            // is the allowlist for direct global-state access, every other
            // production site must go through it.
            !toString().endsWith("/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/SystemRuntimeConfig.kt")
}
