package dev.rubentxu.pipeline.v2.scripting

import java.io.File
import java.nio.file.Path

/**
 * Defines a Kotlin script to be compiled and evaluated by a [ScriptingHost].
 *
 * @param sourceText Raw Kotlin script text. Set when using inline scripts.
 * @param sourcePath Path to a `.kts` file. Set when loading from disk.
 * @param classpath List of absolute file paths (JARs or directories) added to
 *                  the script's compilation and evaluation classloader.
 * @param properties Arbitrary key-value configuration passed to the script.
 */
data class ScriptDefinition(
    val sourceText: String? = null,
    val sourcePath: Path? = null,
    val classpath: List<String> = emptyList(),
    val properties: Map<String, String> = emptyMap(),
) {
    init {
        require(sourceText != null || sourcePath != null) {
            "ScriptDefinition requires either sourceText or sourcePath"
        }
    }

    companion object {
        @JvmStatic
        fun inline(text: String, classpath: List<String> = emptyList(), properties: Map<String, String> = emptyMap()): ScriptDefinition =
            ScriptDefinition(sourceText = text, classpath = classpath, properties = properties)

        @JvmStatic
        fun file(path: Path, classpath: List<String> = emptyList(), properties: Map<String, String> = emptyMap()): ScriptDefinition =
            ScriptDefinition(sourcePath = path, classpath = classpath, properties = properties)

        /**
         * Locates the `pipeline-scripting-api` JAR from the current runtime classpath
         * and returns its absolute path. This is used to make the DSL functions visible
         * to the Kotlin script compiler — `dependenciesFromCurrentContext(wholeClasspath=false)`
         * only brings the scripting host's compile classpath, not the application runtime
         * classpath where `pipeline-scripting-api` lives.
         *
         * Uses the system property `APP_HOME` (set by the launch script) to locate
         * the lib directory, then scans for the `pipeline-scripting-api` JAR. Falls back
         * to `java.class.path` scanning if `APP_HOME` is not set.
         *
         * @return absolute path to the `pipeline-scripting-api` JAR, or null if not found
         */
        @JvmStatic
        fun dslApiJar(): String? = classpathJar("pipeline-scripting-api")

        /**
         * Locates the `pipeline-domain` JAR from the current runtime classpath and
         * returns its absolute path.
         *
         * The Kotlin scripting host replaces the host classpath, so scripts that
         * reference domain types need the domain JAR named explicitly. Deriving it
         * from the classpath keeps this correct on any machine, in any working
         * directory and under any checkout layout. A literal absolute path silently
         * pins the caller to one developer's working copy.
         *
         * @return absolute path to the `pipeline-domain` JAR, or null if not found
         */
        @JvmStatic
        fun domainJar(): String? = classpathJar("pipeline-domain")

        /**
         * Locates a JAR by artifact name from the current runtime classpath and
         * returns its absolute path, or null when it is not present.
         *
         * Uses the system property `APP_HOME` (set by the launch script) to locate
         * the lib directory, then scans for the artifact. Falls back to
         * `java.class.path` scanning if `APP_HOME` is not set.
         *
         * @param artifact artifact name prefix to match, e.g. `pipeline-domain`
         * @return absolute path to the JAR, or null if not found
         */
        @JvmStatic
        fun classpathJar(artifact: String): String? {
            // Try APP_HOME first (set by the launch script)
            val appHome = System.getProperty("APP_HOME")
            if (appHome != null) {
                val libDir = File(appHome, "lib")
                if (libDir.isDirectory) {
                    val jarFiles = libDir.listFiles { _, name ->
                        name.startsWith(artifact) && name.endsWith(".jar")
                    }
                    if (!jarFiles.isNullOrEmpty()) {
                        return jarFiles.first().absoluteFile.canonicalPath
                    }
                }
            }
            // Fallback: scan java.class.path
            val classPath = System.getProperty("java.class.path", "")
            val separator = System.getProperty("path.separator", ":")
            for (entry in classPath.split(separator)) {
                val path = entry.trim()
                if (path.isEmpty()) continue
                if (path.contains(artifact) && path.endsWith(".jar")) {
                    return File(path).absoluteFile.canonicalPath
                }
            }
            return null
        }
    }
}
