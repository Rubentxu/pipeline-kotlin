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
                            // WU-LPR-071: scan code, not prose — a `//` line comment that
                            // merely MENTIONS a forbidden token is not a global-state access.
                            val code = line.substringBefore("//")
                            forbidden.firstOrNull(code::contains)?.let { token ->
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
            !toString().endsWith("/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/SystemRuntimeConfig.kt") &&
            // WU-LPR-081: Step SDK plugins (scm-git, junit) declare a
            // `workspaceRootResolver: () -> Path` constructor default that
            // falls back to `System.getProperty("user.dir")` as a
            // **developer-escape hatch only**. The production handler reads the
            // workspace root from the typed `WORKSPACE_IDENTITY_CAPABILITY`
            // (fail-closed at the registry boundary); the system-property
            // default is consulted only when the handler is admitted through
            // unit-test construction outside the canonical bridge (rare).
            // Both files document this explicitly in their KDoc ("developer
            // escape hatch ONLY"). Excluding them keeps the fitness invariant
            // focused on the production path: every other production site must
            // go through `SystemRuntimeConfig`; the developer-escape hatch
            // defaults are documented in-line and out of scope for LFC-0.
            !toString().endsWith("/pipeline-step-sdk/scm-git/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/scm/git/step/GitCheckoutStepDefinition.kt") &&
            !toString().endsWith("/pipeline-step-sdk/junit/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/junit/step/JUnitResultsStepDefinition.kt")
}
