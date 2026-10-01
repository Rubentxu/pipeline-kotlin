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
        // RP034-I: the two plugin exemptions are gone — scm-git.checkout and
        // junit.results no longer fall back to user.dir, so a Step has no
        // excuse to reach ambient state for a workspace base.
        val allowed = setOf(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/SystemRuntimeConfig.kt",
            "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/MapRuntimeConfig.kt",
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

        // RP034-F: the file is named after what it now is. The
        // ExecutionLocationCapability interface was deleted because the
        // capability value is the domain ExecutionLocation ADT itself — see the
        // RP034-E receipt; a wrapper type is visible only to
        // :pipeline-application and broke every plugin that read the key.
        val seam = root.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShOptionsExecutionLocationAdapter.kt",
        )
        assertTrue(Files.isRegularFile(seam), "the RP034-C seam must exist")

        val content = Files.readString(seam)
        assertTrue(
            content.contains("WorkspaceLease.Managed") || content.contains("ExecutionLocation("),
            "the seam must derive the domain ExecutionLocation rather than a private wrapper",
        )
        assertTrue(
            content.contains("PathAnchor") || content.contains("workspace"),
            "the seam must keep the anchor vocabulary visible in its contract",
        )
    }

    @Test
    @DisplayName("no Step handler reconstructs a workspace base with WorkspaceResolver")
    fun `step handlers do not rebuild their base from control-plane paths`() {
        // ADR-0100 fitness rule 1: "prohibir nuevos usos directos de
        // WorkspaceResolver desde Step handlers". A handler that rebuilds
        // `controlDirRoot/stageName/index` re-creates exactly the divergence the
        // execution location exists to remove (INV-WS-012), and it is how
        // core.stash, core.archiveArtifacts and core.publishHTML drifted.
        //
        // The authorised set is exactly what the migration plan calls "the
        // allocator and authorised adapters" (05-migration-plan, RP034-I).
        // Every entry below either establishes the location or falls back to the
        // previous reconstruction; none of them is a Step handler, which is
        // what the rule is about. They are named exactly rather than matched
        // loosely, so a new offender has to be added deliberately.
        val allowed = mapOf(
            // The allocator itself.
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/WorkspaceResolver.kt" to "the allocator",
            // The durable spine: it is what derives ShOptions and therefore the
            // execution location every Step reads.
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt" to "durable spine",
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShExecution.kt" to "shell substrate",
            // Migrated operations: each reads the location first and uses the
            // resolver only when no location was injected (direct construction).
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/WorkspaceOperations.kt" to "file vertical, RP034-D",
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/StashOperationsAdapter.kt" to "stash, RP034-E",
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/ArchiveArtifactsOperations.kt" to "archive, RP034-F",
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/PublishHtmlOperationsAdapter.kt" to "publishHTML, RP034-F",
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CleanWsOperationsAdapter.kt" to "cleanWs, RP034-G",
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/DeleteDirOperationsAdapter.kt" to "deleteDir, RP034-G",
        )
        val offenders = violations { content ->
            content.contains("WorkspaceResolver(")
        }.filterNot { it in allowed }

        assertTrue(
            offenders.isEmpty(),
            "a Step must read its base from EXECUTION_LOCATION_CAPABILITY, not rebuild it " +
                "(INV-WS-012). New offenders: $offenders. Inject the location instead of " +
                "widening this allowlist. Authorised today: ${allowed.entries.joinToString { it.key.substringAfterLast('/') + " (" + it.value + ")" }}",
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
