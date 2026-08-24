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
}
