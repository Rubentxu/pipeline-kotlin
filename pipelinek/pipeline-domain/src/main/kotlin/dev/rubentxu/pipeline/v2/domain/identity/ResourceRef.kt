package dev.rubentxu.pipeline.v2.domain.identity

/**
 * Typed, hierarchical, deterministic reference to an addressable pipeline entity
 * (EVT-1 / RESOURCE_REF_MODEL.md).
 *
 * Laws (frozen, EVT-1):
 * - identifies; never replaces RunId/OpId/StageId/StepId, which remain the local
 *   durable authorities — ResourceRef is a cross-system identity PROJECTION;
 * - deterministic: the same logical entity always yields an equal ResourceRef;
 *   occurrence timestamps, random ids and scheduler-visible ordering are never
 *   identity segments;
 * - no secrets in segments; no mutable attributes (branch name, environment
 *   labels) unless they are true identity segments;
 * - construction is typed via [ResourceRefs] builders only; ad-hoc construction
 *   from raw strings outside the identity package is rejected by architecture fitness;
 * - does not participate in execution fingerprints or journal keys.
 *
 * Canonical serialization ([canonicalText]) is an internal, versioned
 * representation for codecs. It is deliberately NOT a public wire URI scheme;
 * CloudEvents URI mapping stays characterization-only (no frozen transport).
 */
data class ResourceRef(
    val kind: ResourceKind,
    val segments: List<String>,
) {
    init {
        require(segments.isNotEmpty()) {
            "ResourceRef requires at least one segment"
        }
        segments.forEachIndexed { i, s -> validateSegment(s, i) }
    }

    val namespace: String get() = segments.first()
    val path: List<String> get() = segments.drop(1)

    /**
     * Internal canonical text form, versioned by [SERIALIZATION_VERSION].
     * Not a public wire scheme (CloudEvents mapping is characterization-only).
     */
    fun canonicalText(): String =
        "v$SERIALIZATION_VERSION:${kind.name.lowercase()}:" + segments.joinToString("/")

    companion object {
        /**
         * Version of the internal canonical serialization. Bumping it is a
         * breaking codec change and requires an explicit migration note.
         */
        const val SERIALIZATION_VERSION: Int = 1

        internal fun validateSegment(segment: String, index: Int) {
            when {
                segment.isEmpty() ->
                    throw InvalidResourceRefException(
                        "ResourceRef segment $index must not be empty"
                    )
                segment.length > MAX_SEGMENT_LENGTH ->
                    throw InvalidResourceRefException(
                        "ResourceRef segment $index exceeds $MAX_SEGMENT_LENGTH characters"
                    )
                segment.any { it in FORBIDDEN_CHARS || it.isISOControl() } ->
                    throw InvalidResourceRefException(
                        "ResourceRef segment $index contains forbidden characters: '$segment'"
                    )
            }
        }

        /** Characters that would break hierarchical path semantics or are unsafe cross-system. */
        private val FORBIDDEN_CHARS: Set<Char> =
            setOf('/', '\\', ':', '#', '?', '%', ' ', '\t', '\n', '\r', '{', '}', '[', ']')

        private const val MAX_SEGMENT_LENGTH = 256
    }
}

/**
 * Typed failure for invalid identity construction. Fail-closed: no silent
 * sanitization of identity segments.
 */
class InvalidResourceRefException(message: String) : IllegalArgumentException(message)
