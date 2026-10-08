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

    /**
     * The law: no build file may exclude SOURCES from compilation.
     *
     * ## Why this is not `findBuildSubstring(root, "exclude(")`
     *
     * The guarantee is about hiding CODE from the compiler, not about pattern filters. A
     * `fileTree(...) { exclude(...) }` declares which files are INPUTS to a task and never hides a
     * source from compilation, but the old blanket token scan could not tell the two apart. Measured
     * 2026-10-08: three legitimate `inputs.files(fileTree(...) { exclude(...) })` declarations in the
     * plugin SDK modules failed this law while compiling nothing, which is an over-fire, not a
     * finding.
     *
     * ## The rule, and why it is narrow rather than loose
     *
     *  - `exclude(` / `setExcludes(` on a line that also builds a `fileTree(` -> ALLOWED (an input
     *    filter; the line says what the task reads, not what the compiler ignores).
     *  - anything else carrying either token -> FLAGGED. Both spellings are scanned on purpose: a
     *    scan for `exclude(` alone would miss `sourceSets { setExcludes(...) }`, which is the same
     *    exclusion written with Gradle's setter.
     *
     * A multi-line `fileTree` filter is deliberately still flagged: over-firing forces the author to
     * either keep the declaration on one line or refine this law again, whereas a lookback heuristic
     * wide enough to allow it would also allow a compile exclude sitting under a `fileTree` line.
     * Both directions are pinned by fixtures in [FArch011V2NoCompileExcludesTest].
     */
    fun findExcludeCalls(root: Path): List<Finding> {
        val tokens = listOf("exclude(", "setExcludes(")
        val findings = mutableListOf<Finding>()
        for (file in FitnessPaths.walkBuildFiles(root)) {
            for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//")) continue
                val token = tokens.firstOrNull { line.contains(it) } ?: continue
                if (line.contains("fileTree(")) continue
                findings.add(Finding(file, lineIdx + 1, token, line))
            }
        }
        return findings
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
        //
        // The value class deliberately stops at the FIRST `)` so it can read a call without
        // balancing parens: `emit(sequence = 0L)` captures `0L`. That is also why the exemption
        // below cannot match on an argument: `rs.getLong("sequence")` captures only `rs.getLong(`,
        // with the column name never reaching the comparison. The captured prefix is enough to
        // recognise the READ (`getLong(`, `stringField(`, …), so that is what it keys off.
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
                // Same rule, JDBC spelling: `rs.getLong("sequence")` reads the sequence back OUT
                // of a durable row, which is a decode and never an emit. P3-E E4c introduced this
                // shape in `SqliteEventStore.readRecord`, where the row's sequence is what makes an
                // `Undecodable` reportable — and the store remains the authority precisely because it
                // READS the value it assigned rather than inventing one.
                //
                // The exemption keys off the getter, NOT off the column name: the value
                // class above stops at the first `)`, so `rs.getLong("sequence")` captures only
                // `rs.getLong(` and the quoted column never reaches the comparison. The first
                // attempt keyed off `"sequence"`, looked right, matched nothing, and was caught by
                // the `row read` fixture rather than by review.
                if (value.contains("getLong(") || value.contains("getString(") || value.contains("getInt(")) {
                    continue
                }
                findings.add(Finding(file, lineIdx + 1, "sequence=$value", line))
            }
        }
        return findings
    }
}
