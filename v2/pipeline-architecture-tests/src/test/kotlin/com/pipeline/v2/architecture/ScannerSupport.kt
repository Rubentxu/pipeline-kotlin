package com.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * Fail-closed scanning utility for architecture-fitness tests.
 * All operations return an empty list on no match; never throw on missing files.
 */
data class Finding(
    val file: Path,
    val line: Int,
    val token: String,
    val excerpt: String,
)

object ScannerSupport {

    /** Root of the V2 subtree under test.
     *  Defaults to "v2"; overridable via system property `fitness.v2.root`. */
    fun v2Root(): Path {
        val override = System.getProperty("fitness.v2.root", "v2")
        return Path.of(override).toAbsolutePath().normalize()
    }

    /** Recursively walk `root` and yield every regular file whose name ends with `.kt` or `.kts`. */
    fun walkKotlinFiles(root: Path): List<Path> {
        return Files.walk(root).toList()
            .filter { it.toFile().isFile && (it.fileName.toString().endsWith(".kt") || it.fileName.toString().endsWith(".kts")) }
    }

    /** Recursively walk `root` and yield every `build.gradle.kts` and `*.toml` file. */
    fun walkBuildFiles(root: Path): List<Path> {
        return Files.walk(root).toList()
            .filter { it.toFile().isFile &&
                (it.fileName.toString() == "build.gradle.kts" || it.fileName.toString().endsWith(".toml")) }
    }

    /**
     * Returns every `import <token>` line in `*.kt` / `*.kts` under `root` matching `tokens`.
     * Anchored: `^import\s+([\w.]+\.)?<token>(\..*)?\s*$`
     * Note: the design spec shows `(\.\*)?` but that is a typo — a literal asterisk can never
     * match the dot-separated suffixes in real package names (e.g. .client...). The intended
     * meaning is `(\..*)?` (dot + any chars) which is what is implemented here.
     * Comment lines (first non-whitespace is `//` or `*`) are skipped.
     */
    fun findImports(root: Path, tokens: Collection<String>): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (file in walkKotlinFiles(root)) {
            for ((lineIdx, line) in Files.readAllLines(file).withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                for (token in tokens) {
                    // Use (\..*)? — dot + any chars — instead of the design's erroneous (\.\*)?
                    val pattern = Pattern.compile("^import\\s+([\\w.]+\\.)?${Pattern.quote(token)}(\\..*)?\\s*$")
                    if (pattern.matcher(line).matches()) {
                        findings.add(Finding(file, lineIdx + 1, token, line))
                    }
                }
            }
        }
        return findings
    }

    /**
     * Returns every line in `*.gradle.kts` and `*.toml` under `root` whose content contains
     * `substring` as a whole token (word-boundary anchored, per-line).
     * The trailing \b is omitted when substring ends with a non-word char (e.g. "exclude(")
     * because \( \b is always false (non-word char followed by non-word char).
     */
    fun findBuildSubstring(root: Path, substring: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val escaped = Pattern.quote(substring)
        // Only add trailing \b when last char of substring is a word char.
        // For "exclude(" the trailing \b would be between ( and " — always false.
        val trailingWordBoundary = substring.last().isJavaIdentifierPart()
        val regex = if (trailingWordBoundary) "\\b${escaped}\\b" else "\\b${escaped}"
        val pattern = Pattern.compile(regex)
        for (file in walkBuildFiles(root)) {
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
     * Returns every line in `*.gradle.kts` under `root` containing the literal `exclude(` substring.
     */
    fun findExcludeCalls(root: Path): List<Finding> {
        return findBuildSubstring(root, "exclude(")
    }

    /**
     * Returns every `implementation(<coords>)` / `implementation project(...)` line in a single
     * build.gradle.kts whose group:artifact is NOT in `allowed`.
     */
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
                // Skip project(...) declarations
                if (coords.startsWith("project(")) continue
                // Check if the group:artifact is allowed
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
     * Resolved runtime-classpath jar names for the four M0-R2 modules, as captured by
     * the cross-project `runtimeClasspathCapture` task.
     * Reads `v2/<module>/build/fitness/<module>-runtime-classpath.txt` for each module.
     */
    fun loadRuntimeClasspathSnapshots(root: Path): Map<String, List<String>> {
        val modules = listOf("pipeline-domain", "pipeline-application", "pipeline-scripting-api", "pipeline-testkit")
        val result = mutableMapOf<String, List<String>>()
        for (module in modules) {
            val snapshotFile = root.resolve("$module/build/fitness/${module}-runtime-classpath.txt")
            if (Files.exists(snapshotFile)) {
                result[module] = Files.readAllLines(snapshotFile)
            } else {
                result[module] = emptyList()
            }
        }
        return result
    }
}
