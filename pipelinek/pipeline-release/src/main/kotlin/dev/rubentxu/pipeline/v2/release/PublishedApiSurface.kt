package dev.rubentxu.pipeline.v2.release

/**
 * The public surface of one published artifact, as the binary-compatibility
 * validator renders it in a `.api` file.
 *
 * The rendering is the engine's, not this project's: the same engine produced
 * the baseline committed under `<module>/api/<module>.api` and the dump of a
 * published jar. Reading that rendering is therefore a matter of naming what
 * the engine wrote, not of interpreting a format of this project's own.
 *
 * A declaration is one column-0 header plus the member lines indented under
 * it. A member is one TAB-indented line, and its text IS its identity: the
 * line carries the erased JVM descriptor, so a member whose parameter list
 * changed is a different line and reads as a removal plus an addition. That is
 * the right reading for a compatibility gate — the old line is what a consumer
 * compiled against, and a consumer that linked it will not find it again.
 */
data class PublishedApiSurface(
    /** The declarations, in the order the artifact renders them. */
    val declarations: Map<String, Set<String>>,
) {
    /** The member lines of [name], or an empty set when the declaration is absent. */
    fun membersOf(name: String): Set<String> = declarations[name] ?: emptySet()

    /** Whether this surface declares [name]. */
    operator fun contains(name: String): Boolean = declarations.containsKey(name)
}

/**
 * Reads the `.api` rendering of a published artifact.
 *
 * The shape of the rendering is measured, not assumed: a header is a column-0
 * line that ends in `{`; a member is a line that starts with a TAB; a `}`
 * closes the declaration that is open.
 *
 * The name of a declaration is the token that follows the LAST kind keyword
 * (`class`, `interface`, `object`), because the engine renders an interface as
 * `public abstract interface class …` and a class with two supertypes as
 * `… class Foo : A, B {`. In both, the last token of the header is not the
 * name.
 */
object PublishedApiSurfaceReader {

    /** The words the engine uses to introduce the kind of a declaration. */
    private val KIND_KEYWORDS = setOf("class", "interface", "object")

    /** The name of a member that is a function: what stands between `fun` and its parameters. */
    private val FUNCTION_NAME = Regex("""\bfun\s+(\S+?)\s*\(""")

    /** The name of a member that is a field: what stands after `field`. */
    private val FIELD_NAME = Regex("""\bfield\s+(\S+)""")

    /**
     * Read a rendered surface.
     *
     * A member line that appears before any declaration, and a header that
     * never closes, are defects of the RENDERING rather than of the artifact;
     * they are reported instead of being silently absorbed, because a surface
     * whose shape was not understood cannot certify compatibility.
     */
    fun read(text: String): PublishedApiSurface {
        val declarations = LinkedHashMap<String, MutableSet<String>>()
        var current: MutableSet<String>? = null
        var currentName: String? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Unit
                line.startsWith("\t") -> {
                    val members = current
                        ?: throw IllegalArgumentException(
                            "a member line appears before any declaration: '$line'",
                        )
                    members.add(line.trim())
                }

                line == "}" -> {
                    current = null
                    currentName = null
                }

                line.endsWith("{") -> {
                    if (current != null) {
                        throw IllegalArgumentException(
                            "the declaration $currentName was never closed before '$line'",
                        )
                    }
                    val name = declarationNameOf(line)
                    val members = LinkedHashSet<String>()
                    declarations[name] = members
                    current = members
                    currentName = name
                }

                else -> throw IllegalArgumentException(
                    "a rendered surface carries only headers, members and closing braces; got '$line'",
                )
            }
        }
        if (current != null) {
            throw IllegalArgumentException("the declaration $currentName was never closed")
        }
        return PublishedApiSurface(declarations)
    }

    /**
     * The name of the declaration a header introduces, in the artifact's own
     * spelling: package segments separated by `/`, nested classes separated
     * by `$`.
     *
     * That spelling is the one the ledger uses to name a removal, and it is
     * used because it is what the artifact carries: a consumer's class file
     * names the class that way, so a break has to be named that way too.
     */
    fun declarationNameOf(header: String): String {
        val body = header.removeSuffix("{").trim()
        val tokens = body.split(Regex("""\s+"""))
        val kindIndex = tokens.indexOfLast { it in KIND_KEYWORDS }
        if (kindIndex < 0 || kindIndex + 1 >= tokens.size) {
            throw IllegalArgumentException("the header does not name a declaration: '$header'")
        }
        return tokens[kindIndex + 1]
    }

    /**
     * The name of a member, used to say in prose which member is gone.
     *
     * The descriptor is what makes two members with the same name different
     * members; the name is what a reader can hold in their head. A member
     * whose line names neither a function nor a field has no such short
     * name, and the whole line is its name.
     */
    fun memberNameOf(line: String): String {
        val trimmed = line.trim()
        FUNCTION_NAME.find(trimmed)?.let { return it.groupValues[1] }
        FIELD_NAME.find(trimmed)?.let { return it.groupValues[1] }
        return trimmed
    }

    /**
     * The key that names a member EXACTLY: its name together with the erased
     * descriptor the engine rendered.
     *
     * The name alone is not an identity. Two constructors of the same
     * declaration are two different members that share the name `<init>`, and
     * a key built from the name alone would let an approval written for one
     * of them authorize the removal of the other — which is exactly the
     * hiding this gate exists to prevent. The descriptor is what tells them
     * apart, so the key carries it.
     *
     * A function's key is the name followed by the descriptor as the engine
     * wrote it (`getDeadlineMs()J`). A field's key says so, because a field
     * and a function can share a name (`field:Companion:…`).
     */
    fun memberKeyOf(line: String): String {
        val trimmed = line.trim()
        FUNCTION_NAME.find(trimmed)?.let { match ->
            val open = match.range.last
            return match.groupValues[1] + trimmed.substring(open)
        }
        FIELD_NAME.find(trimmed)?.let { match ->
            val type = trimmed.substring(match.range.last + 1).trim()
            return "field:${match.groupValues[1]}:$type"
        }
        return trimmed
    }
}
