package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.domain.digest.Sha256

/**
 * A versioned cache key for stable script compilation caching.
 *
 * @property value The SHA-256 hex digest.
 * @property version The algorithm version tag.
 */
data class CacheKey(
    val value: String,
    val version: String,
) {
    companion object {
        const val V1 = "v1"
        const val V2 = "v2"

        /**
         * Joins parts with `|`, then SHA-256s UTF-8 bytes.
         *
         * **This is `v1`'s pre-image and it is kept unframed on purpose.** A value already
         * written under the `v1` label is only interpretable if the recipe that produced it
         * still reproduces; framing it "better" would silently redefine every `v1` digest ever
         * emitted. New algorithms use [framedDigest], never this.
         *
         * Routed through [Sha256] (B0), which removes a `private val digest =
         * MessageDigest.getInstance("SHA-256")` held as static mutable state and guarded by
         * `synchronized`. That field was correct only because every caller took the lock and
         * called `reset()` first; the correctness depended on a discipline every future caller
         * would have to remember.
         */
        fun sha256Hex(vararg parts: String): String = Sha256.ofText(parts.joinToString("|"))

        /**
         * Length-framed pre-image: each part contributes `<charCount>:<part>`, in order.
         *
         * ## Why framing is not a detail here
         *
         * A digest over `parts.joinToString("|")` is not injective on the assignment. The
         * assignments `("a", "b", "c|d", "")` and `("a", "b", "c", "d|")` produce the same
         * pre-image and therefore the same digest — a collision reachable by putting a `|`
         * inside a value, and `pipelineSource` is arbitrary text.
         *
         * [Sha256.ofDirectory] already decided the same question for its own inputs and chose
         * NUL termination, which is sound there because a NUL cannot occur in a POSIX path or
         * in a hex digest. It is *not* sound here: `pipelineSource` is arbitrary script text,
         * so a NUL is merely pathological rather than impossible. Framing by length instead
         * removes the assumption entirely — the assignment is recoverable from the pre-image
         * whatever the values contain.
         *
         * The count is in UTF-16 chars while the digest is over UTF-8 bytes. That mismatch is
         * harmless: what has to be unambiguous is the pre-image *string*, and a reader
         * recovering the parts counts chars, the unit in which the lengths were written.
         */
        private fun framedDigest(parts: List<String>): String = Sha256.ofText(
            buildString {
                parts.forEach { append(it.length).append(':').append(it) }
            }
        )

        object v1 {
            fun compute(
                scriptText: String,
                sortedClasspath: String,
                kotlinVersion: String,
                hostVersion: String,
            ): CacheKey = CacheKey(
                sha256Hex(scriptText, sortedClasspath, kotlinVersion, hostVersion),
                V1
            )
        }

        /**
         * The `v2` algorithm: the identity declared by `SCRIPTING_COMPILER_SPEC.md` §7.
         *
         * That section names seven dimensions. Five are supplied here as real values; the
         * remaining two are **absent by measurement, not by oversight**, and naming them here is
         * what keeps them from being forgotten:
         *
         * | spec dimension            | status in `v2`                        |
         * |---------------------------|---------------------------------------|
         * | `pipelineSourceDigest`    | [CompilationIdentity.pipelineSource]  |
         * | `kotlinCompilerVersion`   | [CompilationIdentity.kotlinVersion]   |
         * | `scriptingAdapterVersion` | [CompilationIdentity.hostVersion]     |
         * | `approvedClasspathDigest` | [CompilationIdentity.approvedClasspath]|
         * | `compilerOptionsDigest`   | [CompilationIdentity.compilerOptions] |
         * | `dslApiVersion`           | **missing** — no such version exists to read |
         * | `pluginLockDigest`        | **missing** — the classpath carries the plugins; a separate lock digest needs a lock to read |
         *
         * Adding a dimension changes the digest for every input, which is why it happened under a
         * new version label rather than by editing `v1`. Nothing reads these values yet (measured
         * 2026-10-18: no cache exists), so no durable value is being silently reinterpreted.
         */
        object v2 {
            fun compute(identity: CompilationIdentity): CacheKey = CacheKey(
                framedDigest(
                    buildList {
                        add(identity.pipelineSource)
                        add(identity.kotlinVersion)
                        add(identity.hostVersion)
                        add(identity.approvedClasspath)
                        // Each option is its own framed part, so both the set and the order
                        // are part of the identity. Joining them into one string first would
                        // reintroduce exactly the ambiguity framing was added to remove.
                        addAll(identity.compilerOptions)
                    }
                ),
                V2
            )
        }
    }
}

/**
 * The inputs that decide whether two compilations are interchangeable.
 *
 * Named fields rather than a positional call: four bare `String` parameters in a row can be
 * transposed by a reader and by an IDE without complaint, and a transposed `kotlinVersion` /
 * `hostVersion` produces a plausible key for the wrong compilation. Naming them makes the
 * transposition a compile error.
 *
 * Every field is something the caller actually holds. A field that had to be invented to fill
 * the spec's table would be a fabricated dimension: it would look like coverage and change no
 * behaviour, which is the "dead semantic parameter" the Semantic Constitution forbids.
 */
data class CompilationIdentity(
    /** The script source text. Spec dimension: `pipelineSourceDigest`. */
    val pipelineSource: String,
    /** The Kotlin compiler version. Spec dimension: `kotlinCompilerVersion`. */
    val kotlinVersion: String,
    /** The scripting host's own implementation version. Spec dimension: `scriptingAdapterVersion`. */
    val hostVersion: String,
    /** The resolved classpath, already sorted and joined by the caller. Spec dimension: `approvedClasspathDigest`. */
    val approvedClasspath: String,
    /** The compiler options passed to the compiler, in the order passed. Spec dimension: `compilerOptionsDigest`. */
    val compilerOptions: List<String>,
)