package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BLOCK 1-J — the no-core-change law, stated as something a build can check.
 *
 * ## What is already pinned, and what is not
 *
 * [Block1AuthorityClosureTest] already owns "no dispatcher switches on a concrete Step key". That
 * is a **negative** law: it forbids a shape. This file pins a different one — that the core does not
 * *contain* a plugin's identity — and it is the half that makes a new plugin free rather than
 * merely possible.
 *
 * Neither law can prove the claim that actually matters, which is sufficiency: "a new plugin needs
 * no core change". A scan can only show the absence of a bad shape. Sufficiency is proved by DOING
 * it, and the receipt for BLOCK 1-J records that experiment; this file is what stops the seam from
 * rotting afterwards.
 *
 * ## Why the namespaces are DERIVED rather than listed
 *
 * A hardcoded list of forbidden plugin names is a list that goes stale exactly when it is needed:
 * a new plugin ships, and the list still says "these are all the plugins". So the forbidden set is
 * read from the plugin modules themselves on every run — every `PluginStepId("…")` a plugin
 * declares, reduced to its first dot-segment. Add a plugin and this law covers it without being
 * edited. A law that must be updated when the thing it polices changes is a weaker law than one
 * that reads the thing.
 *
 * ## Why the dot is required
 *
 * `NetworkEgress.defaultEgressPortFor` matches the literal `"http"`, which is a URL scheme. The
 * `http` plugin's namespace is also spelled `http`, so a scan for the bare word would flag a
 * correct piece of core code. Requiring the trailing dot means a literal only counts when it is
 * shaped like a plugin key prefix, which is the thing the law is about.
 *
 * ## Why comments do not count
 *
 * The core's KDoc legitimately NAMES plugins while explaining the seam — `StepRegistry` documents
 * that `example.uppercase` uses the legacy registration shape. A sentence is documentation; a
 * branch is knowledge. Stripping comments keeps the two apart.
 *
 * ## Why `pipeline-step-sdk` is excluded from "core"
 *
 * Those four modules ARE plugins; they ship their own manifests and declare their own families. A
 * plugin naming itself is the normal case, not a violation. The core is what must name none.
 */
class CoreKnowsNoExternalPluginNamespaceFitnessTest {

    private val repoRoot: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { dir -> Files.isDirectory(dir.resolve(".git")) || Files.isRegularFile(dir.resolve(".git")) }
        ?: error(
            "Cannot locate the checkout root: no ancestor of ${Path.of("").toAbsolutePath()} has a .git entry. " +
                "This fitness would otherwise scan nothing and pass.",
        )

    private val v2Root: Path = repoRoot.resolve("v2")

    private fun kotlinFilesUnder(root: Path, sourceSet: String): List<Path> =
        if (!Files.isDirectory(root)) {
            emptyList()
        } else {
            Files.walk(root).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                    .filter { it.toString().contains("/src/$sourceSet/") || it.toString().contains("\\src\\$sourceSet\\") }
                    .toList()
            }
        }

    /** Every module directory under `v2/` that is a plugin rather than the core. */
    private fun pluginModuleDirs(): List<Path> {
        val sdk = v2Root.resolve("pipeline-step-sdk")
        return if (!Files.isDirectory(sdk)) {
            emptyList()
        } else {
            Files.list(sdk).use { s -> s.filter { Files.isDirectory(it) }.toList() }
        }
    }

    /**
     * Core production sources: every `v2` module EXCEPT the plugin SDK, and nothing outside `v2`.
     *
     * `examples/` is deliberately absent even though it lives in this repository: those modules are
     * plugins, and a plugin naming its own Step keys is the normal case rather than the violation.
     * Counting them here flagged fifteen honest self-references on the first run, which is what an
     * over-broad law looks like from the inside — it finds real strings and still answers the wrong
     * question.
     */
    private val coreSources: List<Path> =
        Files.list(v2Root).use { modules ->
            modules
                .filter { Files.isDirectory(it) }
                .filter { it.fileName.toString() != "pipeline-step-sdk" }
                .filter { it.fileName.toString() != "build" }
                .flatMap { module -> kotlinFilesUnder(module.resolve("src"), "main").stream() }
                .toList()
        }

    /** Plugin-declared keys, read from the plugin modules and from `examples/`. */
    private val pluginKeys: List<String> = run {
        val files = mutableListOf<Path>()
        for (dir in pluginModuleDirs()) {
            files.addAll(kotlinFilesUnder(dir.resolve("src"), "main"))
        }
        // `examples/` sits outside v2 and is not a plugin MODULE list, so it is added whole.
        files.addAll(kotlinFilesUnder(repoRoot.resolve("examples"), "main"))

        val keys = mutableListOf<String>()
        for (file in files) {
            for (match in KEY_LITERAL.findAll(file.codeOnly())) {
                keys.add(match.groupValues[1])
            }
        }
        keys
    }

    private val pluginNamespaces: Set<String> =
        pluginKeys.mapNotNull { key -> key.substringBefore('.').takeIf { ns -> ns != key && key.contains('.') } }.toSet()

    /**
     * Core production source with comments removed.
     *
     * Blunt on purpose, and deliberately the same approach as [Block1AuthorityClosureTest]: a `//`
     * inside a string literal is also cut, which could hide a name written after such a literal on
     * the same line — a shape no reviewer would produce, and one that costs nothing to notice.
     */
    private fun Path.codeOnly(): String =
        Files.readString(this)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    private fun relative(path: Path): String = repoRoot.relativize(path).toString()

    // --------------------------------------------------------------------------- rows

    @Test
    fun `the plugin namespace set is derived and not empty`() {
        // Non-vacuity of this very fitness. A scan for plugin namespaces over a set that failed to
        // derive would report zero offenders and be GREEN, which is the most expensive shape a
        // fitness can have: it looks like coverage and is the absence of the scan.
        assertTrue(
            pluginNamespaces.size >= 4,
            "expected the plugins to declare at least four distinct key namespaces, derived from " +
                "${pluginKeys.size} keys; got $pluginNamespaces. If the derivation broke, every other " +
                "row here would pass for the wrong reason.",
        )
        assertTrue(
            pluginNamespaces.contains("scm-git") && pluginNamespaces.contains("http"),
            "the derivation must reach the in-tree SDK plugins, not only examples/; got $pluginNamespaces",
        )
    }

    @Test
    fun `no core production source names an external plugin namespace in code`() {
        val offenders = mutableListOf<String>()

        for (file in coreSources) {
            if (file.fileName.toString() == "CoreKnowsNoExternalPluginNamespaceFitnessTest.kt") continue
            file.codeOnly().lines().forEachIndexed { index, line ->
                for (match in QUOTED.findAll(line)) {
                    val literal = match.groupValues[1]
                    val namespace = pluginNamespaces.firstOrNull { literal.startsWith("$it.") }
                    if (namespace != null) {
                        offenders += "${relative(file)}:${index + 1} names plugin namespace '$namespace.' ($literal)"
                    }
                }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "core production code must not contain a plugin's key namespace. A plugin naming itself is " +
                "the normal case; the core naming one is the defect this law forbids. Found:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `the namespace detector actually fires on a plugin-shaped literal`() {
        // The detector's own non-vacuity. Without this, a regex that silently stopped matching —
        // one edit away, and green forever — would leave the row above as decoration.
        val sample = """val families = if (key.value.startsWith("scm-git.")) 1 else 0"""
        val fired = pluginNamespaces.any { ns -> QUOTED.findAll(sample).any { it.groupValues[1].startsWith("$ns.") } }

        assertTrue(
            fired,
            "the detector must flag a plugin-shaped literal. It derives namespaces $pluginNamespaces " +
                "and found none in $sample, so the pattern above is not matching what it claims to.",
        )
    }

    private companion object {
        /** `PluginStepId("…")` / `DirectiveKey("…")` / `PluginStepId(name = "…")`. */
        val KEY_LITERAL = Regex("""(?:PluginStepId|DirectiveKey)\s*\(\s*(?:name\s*=\s*)?"([^"]+)"""")

        /** Any quoted literal in a line of code. */
        val QUOTED = Regex(""""([^"]+)"""")
    }
}
