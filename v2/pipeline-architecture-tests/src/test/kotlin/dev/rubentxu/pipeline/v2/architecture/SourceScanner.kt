package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

private const val IMPORT_ANCHOR_TEMPLATE = "^import\\s+([\\w.]+\\.)?<TOKEN>(\\..*)?\\s*$"

private fun importAnchorFor(token: String): Pattern =
    Pattern.compile("^import\\s+([\\w.]+\\.)?${Pattern.quote(token)}(\\..*)?\\s*$")

object SourceScanner {
    fun findImports(root: Path, tokens: Collection<String>,
                    allowedPathPrefixes: List<String> = emptyList()): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (file in FitnessPaths.walkKotlinFiles(root)) {
            val relPath = file.toString()
            if (allowedPathPrefixes.isNotEmpty() && allowedPathPrefixes.any { relPath.contains(it) }) {
                continue
            }
            for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                for (token in tokens) {
                    val pattern = importAnchorFor(token)
                    if (pattern.matcher(line).matches()) {
                        findings.add(Finding(file, lineIdx + 1, token, line))
                    }
                }
            }
        }
        return findings
    }

    fun findBuildSubstring(root: Path, substring: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val escaped = Pattern.quote(substring)
        val trailingWordBoundary = substring.last().isJavaIdentifierPart()
        val regex = if (trailingWordBoundary) "\\b${escaped}\\b" else "\\b${escaped}"
        val pattern = Pattern.compile(regex)
        for (file in FitnessPaths.walkBuildFiles(root)) {
            for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//")) continue
                if (pattern.matcher(line).find()) {
                    findings.add(Finding(file, lineIdx + 1, substring, line))
                }
            }
        }
        return findings
    }

    fun findExcludeCalls(root: Path): List<Finding> {
        return findBuildSubstring(root, "exclude(")
    }

    fun findUnallowedImplementation(buildFile: Path, allowed: Set<String>): List<Finding> {
        val findings = mutableListOf<Finding>()
        if (!Files.exists(buildFile)) return findings
        val implPattern = Pattern.compile("""implementation\s*\(\s*["']([^"']+)["']\s*\)""")
        for ((lineIdx, line) in Files.readAllLines(buildFile).withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//")) continue
            val matcher = implPattern.matcher(line)
            if (matcher.find()) {
                val coords = matcher.group(1)!!
                if (coords.startsWith("project(")) continue
                val parts = coords.split(":")
                if (parts.size >= 2) {
                    val groupArtifact = "${parts[0]}:${parts[1]}"
                    if (groupArtifact !in allowed) {
                        findings.add(Finding(buildFile, lineIdx + 1, groupArtifact, line))
                    }
                }
            }
        }
        return findings
    }

    /**
     * Scans Kotlin source files for imports matching the given FQCN prefixes.
     * Matches an import line whose fully-qualified name equals the prefix OR starts with prefix + ".".
     * Handles alias imports by matching the original FQCN before the `as` keyword.
     * Skips comment lines (// and block /* */) as existing code does.
     *
     * Example: prefix "okhttp3" matches "import okhttp3.OkHttpClient" and "import okhttp3.ws.WebSocket"
     * Example: prefix "java.net.http" matches "import java.net.http.WebSocket"
     * Example: prefix "io.ktor.client" matches "import io.ktor.client.HttpClient"
     */
    fun findForbiddenImportPrefixes(root: Path, prefixes: Collection<String>): List<Finding> {
        val findings = mutableListOf<Finding>()
        // FQCN import pattern: captures everything between "import " and end-of-statement
        val importLinePattern = Pattern.compile("^import\\s+(.+?)(?:\\s+as\\s+\\w+)?\\s*;?\\s*$")
        for (file in FitnessPaths.walkKotlinFiles(root)) {
            for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                // Skip block comments
                if (trimmed.startsWith("/*")) continue

                val importMatcher = importLinePattern.matcher(trimmed)
                if (!importMatcher.matches()) continue

                val fqcn = importMatcher.group(1) ?: continue

                for (prefix in prefixes) {
                    // Match if FQCN equals the prefix OR FQCN starts with prefix + "."
                    // For versioned roots like "okhttp" catching "okhttp3.*", the prefix
                    // "okhttp3" is listed explicitly so "okhttp3.OkHttpClient" matches "okhttp3"
                    // exactly; "okhttp" as prefix catches unversioned "okhttp.*" imports.
                    if (fqcn == prefix || fqcn.startsWith("$prefix.")) {
                        findings.add(Finding(file, lineIdx + 1, prefix, line))
                        break // one finding per line per prefix
                    }
                }
            }
        }
        return findings
    }

    /**
     * Finds `when` subjects that branch on a directive key (open-world identity).
     *
     * The directive kernel resolves keys through the registry; a `when` over a
     * key value re-closes the world the registry opened (S1 exit criterion).
     * Deliberately narrow: it matches key-shaped subjects, not every `when`, so
     * legitimate exhaustive matches over closed ADTs (e.g.
     * DirectiveExecutionPolicy) are not flagged.
     *
     * Example flagged:   `when (key.value) { "acme.guard" -> ... }`
     * Example allowed:   `when (policy) { is Gate -> ... }`
     */
    fun findConcreteKeyBranches(root: Path): List<Finding> {
        val findings = mutableListOf<Finding>()
        val keyWhenPattern = Pattern.compile("when\\s*\\(\\s*[^)]*\\bkey(\\.value)?\\s*\\)")
        for (file in FitnessPaths.walkKotlinFiles(root)) {
            for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue
                if (keyWhenPattern.matcher(trimmed).find()) {
                    findings.add(Finding(file, lineIdx + 1, "when(key)", line))
                }
            }
        }
        return findings
    }

    /**
     * WU-RP-020: find PRODUCTION emitters that assign their OWN event sequence.
     *
     * The durable sequence authority is the store: `appendAssigned` assigns the
     * sequence when (and only when) the incoming one is `0`. An emitter that
     * passes any other value BYPASSES that authority, and with
     * `UNIQUE(run_id, sequence)` in place it either corrupts the log or fails
     * the run. Two real instances were found this way and fixed:
     * `WithCredentialsExecutor` (`var sequence = 1L`, restarting per call) and
     * `GitCheckoutExecutor` (`req.stepIndex`, a step ordinal used as a run
     * sequence).
     *
     * Only `/src/main/` is scanned. Tests legitimately pin an explicit sequence
     * because they assert on it; production code has no such business.
     *
     * Flagged: `sequence = sequence++`, `sequence = 1L`, `sequence = counter`
     * Allowed: `sequence = 0L`, `copy(sequence = assignedSequence)` (the store's
     * own projection), `sequence = sequence` on a DECODED event (read model,
     * never an emit).
     *
     * Comments are skipped so the KDoc explaining the rule cannot trip it.
     */
    fun findExplicitSequenceAssignment(root: Path): List<Finding> {
        val findings = mutableListOf<Finding>()
        // `sequence = <expr>` where expr is NOT 0L, a projection of an already
        // assigned value, or a bare decode pass-through.
        val pattern = Pattern.compile("""\bsequence\s*=\s*([A-Za-z0-9_.()+\-]+)""")
        val allowed = setOf("0L", "0")
        for (file in FitnessPaths.walkKotlinFiles(root).filter { it.toString().contains("/src/main/") }) {
            for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue
                val m = pattern.matcher(trimmed)
                if (!m.find()) continue
                // The capture can swallow the closing paren of a call
                // (`emit(sequence = 0L)` captures `0L)`), so normalise before
                // comparing against the allow-list.
                var value = m.group(1)
                while (value.endsWith(")")) value = value.dropLast(1)
                if (value in allowed) continue
                // The store's own projection and read-model decode are not emits.
                if (value.contains("assigned") || value == "sequence" || value.endsWith(".sequence")) continue
                // Reading the sequence back OUT of persisted data is a decode, not
                // an emit: `val sequence = longField(s, "sequence")`. The quoted
                // field name is not inside the captured token, so key off the
                // field-reading call itself.
                if (value.contains("longField(") || value.contains("stringField(") || value.contains("intField(")) continue
                findings.add(Finding(file, lineIdx + 1, "sequence=$value", line))
            }
        }
        return findings
    }
}
