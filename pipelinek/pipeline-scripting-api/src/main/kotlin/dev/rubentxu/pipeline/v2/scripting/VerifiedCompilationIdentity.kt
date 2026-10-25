package dev.rubentxu.pipeline.v2.scripting

import dev.rubentxu.pipeline.v2.domain.digest.Sha256

/**
 * One classpath entry as the reader found it. A null [sha256] is not an
 * empty value: it says the entry's content could not be read, and an entry
 * whose content is unknown cannot take part in a verified identity.
 */
data class ClasspathEntry(
    val canonicalPath: String,
    val sha256: String?,
    val sizeBytes: Long?,
)

/**
 * One classpath entry whose content has been verified by digest. The path is
 * deliberately absent: with the bytes hashed, where a jar sits contributes
 * nothing to whether two compilations are interchangeable, and a path that
 * contributed would keep the cache from recognizing the same jar after a move.
 */
data class ClasspathArtifact(
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * The dimensions that decide whether two compilations are interchangeable,
 * each one a value this build can actually observe: the script's content
 * digest, the compiler, the scripting adapter, the compiler options in the
 * order passed, and the classpath as an ordered sequence of verified
 * contents. Distribution-level and plugin-level digests are not separate
 * dimensions here because this build has no authority that reads them; the
 * jars that carry the DSL and the plugins are classpath entries, and their
 * bytes are already part of the identity through [orderedClasspath]. A
 * separate field with no reader would be decoration that looks like coverage.
 */
data class CompilationInputManifest(
    val sourceDigest: String,
    val compilerIdentity: String,
    val adapterIdentity: String,
    val compilerFlags: List<String>,
    val orderedClasspath: List<ClasspathArtifact>,
)

/**
 * The outcome of trying to establish a compilation identity. Both cases
 * carry a [CacheKey]: the verified one is the identity a cache may trust,
 * and the unavailable one is a reporting key that names itself incomplete
 * and must never be used to reuse a compiled artifact. A caller that caches
 * on an unavailable identity is caching against content it could not read.
 */
sealed interface CacheIdentity {
    val key: CacheKey

    data class Verified(
        val manifest: CompilationInputManifest,
        override val key: CacheKey,
    ) : CacheIdentity

    data class Unavailable(
        val reason: String,
        override val key: CacheKey,
    ) : CacheIdentity
}

/**
 * Establish a compilation identity from observed entries. Pure: the caller
 * reads the files and reports what it saw; this decides what that means.
 *
 * The rules, in the order they decide:
 *  - one unreadable required entry makes the whole identity unavailable,
 *    because a single unknown jar is enough to make two compilations
 *    differ where the key would claim they match;
 *  - an unavailable identity still gets a reporting key, derived from the
 *    dimensions that were observable plus an explicit incompleteness mark
 *    and the path that could not be read, so no two different failures
 *    share a key and no incomplete key can collide with a verified one;
 *  - exact duplicate entries (same digest, same size) collapse to their
 *    first occurrence, so a redundant classpath does not fork the identity;
 *  - order is preserved where it stands: classpath order can decide which
 *    of two same-named classes wins, so it is part of the identity.
 */
fun verifyCompilationIdentity(
    sourceDigest: String,
    compilerIdentity: String,
    adapterIdentity: String,
    compilerFlags: List<String>,
    entries: List<ClasspathEntry>,
): CacheIdentity {
    val artifacts = mutableListOf<ClasspathArtifact>()
    for (entry in entries) {
        val sha = entry.sha256
        val size = entry.sizeBytes
        if (sha == null || size == null) {
            // One unreadable required entry is enough: the identity would
            // claim two compilations match where one jar's bytes are unknown.
            return unavailable(sourceDigest, compilerIdentity, adapterIdentity, compilerFlags, entry.canonicalPath)
        }
        artifacts.add(ClasspathArtifact(sha, size))
    }
    val manifest = CompilationInputManifest(
        sourceDigest = sourceDigest,
        compilerIdentity = compilerIdentity,
        adapterIdentity = adapterIdentity,
        compilerFlags = compilerFlags,
        orderedClasspath = artifacts.distinctBy { it.sha256 to it.sizeBytes },
    )
    return CacheIdentity.Verified(manifest, CacheKey.Companion.v3.compute(manifest))
}

/**
 * The reporting key of an identity that could not be verified: framed over
 * the dimensions that were observable, an explicit incompleteness mark, and
 * the path that could not be read. It is unique per input, never collides
 * with a verified key, and names itself incomplete so no consumer mistakes
 * it for a digest it may cache against.
 */
private fun unavailable(
    sourceDigest: String,
    compilerIdentity: String,
    adapterIdentity: String,
    compilerFlags: List<String>,
    unreadablePath: String,
): CacheIdentity.Unavailable {
    val parts = buildList(6) {
        add(IDENTITY_DOMAIN)
        add(INCOMPLETE_MARK)
        add(sourceDigest)
        add(compilerIdentity)
        add(adapterIdentity)
        addAll(compilerFlags)
        add(unreadablePath)
    }
    return CacheIdentity.Unavailable(
        reason = "classpath content unreadable: $unreadablePath",
        key = CacheKey(Sha256.ofText(parts.joinToString("\u0000")), CacheKey.V3),
    )
}

private const val IDENTITY_DOMAIN = "pipelinek-compilation-identity/sha256"
private const val INCOMPLETE_MARK = "identity-incomplete"
