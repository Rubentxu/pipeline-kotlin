package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * RP034-C — workspace resolution fitness.
 *
 * Guards the property the new execution-location seam exists to establish: a
 * Step resolves user paths through the declared authority, never by
 * reconstructing its own base from ambient state.
 *
 * These checks are source-level and deliberately narrow. They forbid the
 * specific *regressions* WU-RP-034 removes — a handler reaching process-global
 * state, or rebuilding a workspace from control-plane paths — rather than
 * asserting a whole-codebase property that would be brittle.
 */
@DisplayName("RP034-C workspace resolution fitness")
class FArchWorkspaceResolutionFitnessTest {

    private companion object {
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val STRING_LITERAL = Regex(""""(?:[^"\\\n]|\\.)*"""")
    }

    private fun v2Root(): Path =        generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .map { it.resolve("v2") }
            .firstOrNull { Files.isDirectory(it) }
            ?: error("cannot locate v2/ from ${System.getProperty("user.dir")}")

    private fun productionSources(): List<Path> {
        val root = v2Root()

        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".kt") }
                .filter { path ->
                    val p = path.toString()
                    p.contains("/src/main/kotlin/") && !p.contains("/build/")
                }
                .toList()
        }
    }    private fun violations(predicate: (String) -> Boolean): List<String> {
        val root = v2Root()
        return productionSources()
            .filter { predicate(executableCodeOf(it)) }
            .map { root.relativize(it).toString() }
    }

    /**
     * The file's content with comments and string literals removed.
     *
     * A raw text scan would flag the KDoc that *documents* the discipline — for
     * example CorePwdStep stating that its handler "never reaches user.dir".
     * Enforcing a rule that its own documentation trips is noise, and would
     * pressure a future author into deleting the explanation instead of fixing
     * code. Only executable occurrences count.
     */
    private fun executableCodeOf(path: Path): String {
        val withoutBlockComments = BLOCK_COMMENT.replace(Files.readString(path), " ")
        val withoutLineComments = LINE_COMMENT.replace(withoutBlockComments, " ")
        return STRING_LITERAL.replace(withoutLineComments, "\"\"")
    }

    @Test
    @DisplayName("no production source resolves a user path from user.dir")
    fun `user dir is not a workspace authority`() {
        // Scoped to executable code only (see executableCodeOf). Two categories
        // are legitimately exempt and are named explicitly rather than matched
        // loosely:
        //
        //  - platform observation ports (SystemRuntimeConfig, MapRuntimeConfig)
        //    expose `userDir()` as a runtime fact; they do not resolve user paths;
        //  - constructor-default resolvers in StepDefinition classes exist only
        //    so a test can build a definition directly. Production threads the
        //    typed capability and never consults them, as their own KDoc states.
        val allowed = setOf(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/SystemRuntimeConfig.kt",
            "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/MapRuntimeConfig.kt",
            "pipeline-step-sdk/junit/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/junit/step/JUnitResultsStepDefinition.kt",
            "pipeline-step-sdk/scm-git/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/scm/git/step/GitCheckoutStepDefinition.kt",
        )
        val offenders = violations { content -> content.contains("user.dir") }
            .filterNot { it in allowed }

        assertTrue(
            offenders.isEmpty(),
            "user.dir must not be a workspace authority (INV-WS-010). New offenders: $offenders. " +
                "Resolve paths through EXECUTION_LOCATION_CAPABILITY rather than widening this allowlist.",
        )
    }

    @Test
    @DisplayName("no production source mutates the JVM current directory")
    fun `no global chdir in production code`() {
        val offenders = violations { content ->
            content.contains("user.dir=") ||
                content.contains("setProperty(\"user.dir\"")
        }
        assertTrue(
            offenders.isEmpty(),
            "a global chdir would break concurrent scopes (INV-WS-006). Offenders: $offenders",
        )
    }

    @Test
    @DisplayName("no production source calls chdir on the filesystem")
    fun `no Runtime chdir in production code`() {
        val offenders = violations { content ->
            content.contains(".chdir(")
        }
        assertTrue(
            offenders.isEmpty(),
            "process chdir is forbidden; cwd is a projected value. Offenders: $offenders",
        )
    }

    @Test
    @DisplayName("the new execution-location seam resolves through the domain authority")
    fun `capability delegates resolution to the pure resolver`() {
        val root = v2Root()

        val capability = root.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ExecutionLocationCapability.kt",
        )
        assertTrue(Files.isRegularFile(capability), "the RP034-C seam must exist")

        val content = Files.readString(capability)
        assertTrue(
            content.contains("WorkspacePathResolver.resolve"),
            "the capability must delegate to the domain authority rather than reimplementing resolution",
        )
        assertTrue(
            content.contains("PathAnchor"),
            "the capability must expose the anchor vocabulary to handlers",
        )
    }

    @Test
    @DisplayName("no Step contract declares a capability wider than the seam it uses")
    fun `execution location capability is declared in the domain`() {
        val root = v2Root()

        val declaration = root.resolve(
            "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/step/WorkspaceIdentity.kt",
        )
        val content = Files.readString(declaration)
        assertTrue(
            content.contains("EXECUTION_LOCATION_CAPABILITY"),
            "the capability key must be hoisted to the domain so plugins can declare it",
        )
    }
}
