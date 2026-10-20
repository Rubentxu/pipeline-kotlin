package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import java.nio.file.Files
import java.nio.file.Path

/**
 * Durable console-transcript file naming authority for the durable shell protocol.
 *
 * The canonical durable console transcript is `console.log`. It is a merged console transcript
 * (plain sh stdout + stderr), NOT specifically stdout or stderr. Pipeline-Kotlin uses neutral
 * vocabulary; the Jenkins-derived legacy name appears only behind the localized read-compatibility
 * boundary below so older durable operations (created before this rename) can still be recovered.
 *
 * New executions MUST write only `console.log`; `jenkins-log.txt` is never written here.
 */
object DurableShellFiles {
    /** Canonical name of the durable merged console transcript. */
    const val CONSOLE_LOG: String = "console.log"

    /**
     * Legacy filename read-compatibility constant ONLY. Isolated here so the Jenkins-derived name
     * is not scattered across the runtime. Never used as a write target.
     */
    private const val LEGACY_JENKINS_LOG: String = "jenkins-log.txt"

    /** Canonical write path for a fresh durable console transcript. */
    fun consoleLog(controlDir: Path): Path = controlDir.resolve(CONSOLE_LOG)

    /**
     * Resolves the durable console transcript to read:
     * 1. `console.log` if present (canonical),
     * 2. else legacy `jenkins-log.txt` if present (older durable operation),
     * 3. else the canonical `console.log` target.
     *
     * Only reads consult the legacy fallback; writers always target [consoleLog].
     */
    fun resolveConsoleLog(controlDir: Path): Path {
        val canonical = controlDir.resolve(CONSOLE_LOG)
        if (Files.exists(canonical)) return canonical
        val legacy = controlDir.resolve(LEGACY_JENKINS_LOG)
        return if (Files.exists(legacy)) legacy else canonical
    }
}
