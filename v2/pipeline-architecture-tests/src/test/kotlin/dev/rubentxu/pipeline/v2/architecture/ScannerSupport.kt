package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Path

object ScannerSupport {
    fun v2Root() = FitnessPaths.v2Root()
    fun walkKotlinFiles(r: Path) = FitnessPaths.walkKotlinFiles(r)
    fun walkBuildFiles(r: Path) = FitnessPaths.walkBuildFiles(r)
    fun findImports(r: Path, tokens: Collection<String>, allowedPathPrefixes: List<String> = emptyList())
        = SourceScanner.findImports(r, tokens, allowedPathPrefixes)
    fun findBuildSubstring(r: Path, s: String) = SourceScanner.findBuildSubstring(r, s)
    fun findExcludeCalls(r: Path) = SourceScanner.findExcludeCalls(r)
    fun findUnallowedImplementation(b: Path, a: Set<String>) = SourceScanner.findUnallowedImplementation(b, a)
    fun loadRuntimeClasspathSnapshots(r: Path) = RuntimeClasspathSnapshots.load(r)
}
