package dev.rubentxu.pipeline.v2.sdk.runtime.durable

/**
 * Environment variable model for shell step execution.
 *
 * Handles the PATH/JAVA_HOME/M2_HOME legacy semantics from V1:
 * - JAVA_HOME/bin is prepended to PATH if JAVA_HOME is set
 * - M2_HOME/bin is prepended to PATH if M2_HOME is set
 *
 * This preserves V1 semantics where Maven/Gradle wrappers would work
 * with the expected PATH ordering.
 *
 * @see <a href="ADR-0046">ADR-0046 — Durable sh Pattern</a>
 */
object EnvModel {

    /**
     * Applies environment variable transformations.
     *
     * Given the user-provided env map, this function:
     * 1. Copies all entries to the output map
     * 2. If JAVA_HOME is set, prepends `${JAVA_HOME}/bin` to PATH
     * 3. If M2_HOME is set, prepends `${M2_HOME}/bin` to PATH
     *
     * The PATH prepend order is: JAVA_HOME/bin first, then M2_HOME/bin,
     * matching V1 legacy semantics from `core/.../Shell.kt:80-95`.
     *
     * @param env The user-provided environment variables.
     * @return The transformed environment with PATH adjustments.
     */
    fun apply(env: Map<String, String>): Map<String, String> {
        val out = env.toMutableMap()

        // JAVA_HOME/bin prepend to PATH
        env["JAVA_HOME"]?.let { javaHome ->
            val currentPath = out["PATH"] ?: ""
            out["PATH"] = "${javaHome}/bin${if (currentPath.isNotEmpty()) ":$currentPath" else ""}"
        }

        // M2_HOME/bin prepend to PATH
        env["M2_HOME"]?.let { m2Home ->
            val currentPath = out["PATH"] ?: ""
            out["PATH"] = "${m2Home}/bin${if (currentPath.isNotEmpty()) ":$currentPath" else ""}"
        }

        return out
    }

    /**
     * Checks if an environment variable value is safe for pb.environment().
     *
     * JDK's ProcessBuilder.environment() rejects NUL characters (\u0000).
     * Values containing =, $, newlines, quotes, backticks, unicode, etc.
     * are all safe and will be passed verbatim.
     *
     * @param value The environment variable value to check.
     * @return true if the value is safe for ProcessBuilder.
     */
    fun isValueSafeForProcessBuilder(value: String): Boolean {
        // ProcessBuilder.environment() rejects NUL
        return !value.contains('\u0000')
    }
}
