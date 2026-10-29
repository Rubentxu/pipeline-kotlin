package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How a published artifact's surface is READ.
 *
 * The publication gate compares two rendered surfaces, and the rendering is
 * the binary-compatibility validator's, not this project's. This file pins
 * what this project claims about that rendering, because a surface read
 * wrongly certifies a compatibility that does not exist: a declaration read
 * under the wrong name is a declaration that cannot be matched, and a member
 * read under the wrong key is a member that no approval can cover.
 *
 * Everything here is text in, text out. No JAR, no filesystem, no build.
 */
class PublishedApiSurfaceReaderTest {

    // --- the name of a declaration -------------------------------------------------------

    @Test
    fun `a class header names the declaration`() {
        // The line is the engine's, copied from the committed baseline of
        // pipeline-domain. The name is the token after the kind keyword.
        val header = "public final class dev/rubentxu/pipeline/v2/domain/AttemptId {"

        assertEquals(
            "dev/rubentxu/pipeline/v2/domain/AttemptId",
            PublishedApiSurfaceReader.declarationNameOf(header),
        )
    }

    @Test
    fun `an interface header names the declaration, not its kind`() {
        // The engine renders an interface as `… interface class Foo {`, so the
        // LAST kind keyword is the one that introduces the name. A reader that
        // took the FIRST kind keyword would name every interface "interface".
        val header = "public abstract interface class dev/rubentxu/pipeline/v2/domain/Capability {"

        assertEquals(
            "dev/rubentxu/pipeline/v2/domain/Capability",
            PublishedApiSurfaceReader.declarationNameOf(header),
        )
    }

    @Test
    fun `a class with supertypes names the declaration and not its last supertype`() {
        // `… class Foo : A, B {` ends in a supertype, not in the name. A reader
        // that took the last token would call this declaration "B".
        val header = "public final class dev/rubentxu/pipeline/v2/domain/Node : A, B {"

        assertEquals(
            "dev/rubentxu/pipeline/v2/domain/Node",
            PublishedApiSurfaceReader.declarationNameOf(header),
        )
    }

    @Test
    fun `an object header names the declaration`() {
        val header = "public final object dev/rubentxu/pipeline/v2/domain/Registry {"

        assertEquals(
            "dev/rubentxu/pipeline/v2/domain/Registry",
            PublishedApiSurfaceReader.declarationNameOf(header),
        )
    }

    @Test
    fun `a header that names no declaration is refused`() {
        // A surface whose shape was not understood cannot certify compatibility,
        // so the reader refuses instead of inventing a name.
        assertThrows(IllegalArgumentException::class.java) {
            PublishedApiSurfaceReader.declarationNameOf("public final class {")
        }
    }

    // --- the key that names a member EXACTLY ---------------------------------------------

    @Test
    fun `a function key carries the erased descriptor`() {
        // The descriptor is what makes the key an identity: a consumer linked
        // this exact signature, so this exact signature is what has to be
        // approved or refused.
        val line = "public final fun component1 ()Ljava/lang/String;"

        assertEquals(
            "component1()Ljava/lang/String;",
            PublishedApiSurfaceReader.memberKeyOf(line),
        )
    }

    @Test
    fun `two constructors of one declaration have different keys`() {
        // The trap this key exists for. Both members are named `<init>`, and a
        // key built from the name alone would let an approval written for one
        // of them authorize the removal of the other — the exact hiding the
        // gate exists to prevent.
        val oneArgument = "public fun <init> (Ljava/lang/String;)V"
        val twoArguments = "public fun <init> (Ljava/lang/String;I)V"

        assertEquals("<init>(Ljava/lang/String;)V", PublishedApiSurfaceReader.memberKeyOf(oneArgument))
        assertEquals("<init>(Ljava/lang/String;I)V", PublishedApiSurfaceReader.memberKeyOf(twoArguments))
        assertTrue(
            PublishedApiSurfaceReader.memberKeyOf(oneArgument) != PublishedApiSurfaceReader.memberKeyOf(twoArguments),
            "the two constructors share a key, so one approval covers both",
        )
    }

    @Test
    fun `a field key says that it is a field and carries its type`() {
        // A field and a function can share a name, so the key says which kind
        // of member it names. The spelling is the measured one: the same
        // spelling the published-contract ledger carries.
        val line = "public static final field Companion Ldev/rubentxu/pipeline/v2/output/OutputRefusal\$Companion;"

        assertEquals(
            "field:Companion:Ldev/rubentxu/pipeline/v2/output/OutputRefusal\$Companion;",
            PublishedApiSurfaceReader.memberKeyOf(line),
        )
    }

    @Test
    fun `a constant field key carries its type`() {
        val line = "public static final field CORRUPT_REASON_MAX_LEN I"

        assertEquals("field:CORRUPT_REASON_MAX_LEN:I", PublishedApiSurfaceReader.memberKeyOf(line))
    }

    // --- the surface as a whole ---------------------------------------------------------

    @Test
    fun `a rendered surface is read into its declarations and members`() {
        val rendered = """
            public final class dev/rubentxu/pipeline/v2/domain/AttemptId {
            	public static final field Companion Ldev/rubentxu/pipeline/v2/domain/AttemptId${'$'}Companion;
            	public fun toString ()Ljava/lang/String;
            }

            public final class dev/rubentxu/pipeline/v2/domain/RunId {
            	public fun hashCode ()I
            }
        """.trimIndent()

        val surface = PublishedApiSurfaceReader.read(rendered)

        assertEquals(2, surface.declarations.size)
        assertTrue("dev/rubentxu/pipeline/v2/domain/AttemptId" in surface)
        assertTrue("dev/rubentxu/pipeline/v2/domain/RunId" in surface)
        assertEquals(
            setOf(
                "public static final field Companion Ldev/rubentxu/pipeline/v2/domain/AttemptId\$Companion;",
                "public fun toString ()Ljava/lang/String;",
            ),
            surface.membersOf("dev/rubentxu/pipeline/v2/domain/AttemptId"),
        )
        assertEquals(
            setOf("public fun hashCode ()I"),
            surface.membersOf("dev/rubentxu/pipeline/v2/domain/RunId"),
        )
    }

    @Test
    fun `a member that appears before any declaration is refused`() {
        // The rendering's shape is measured, not assumed, and a rendering that
        // breaks the shape is a defect of the rendering rather than of the
        // artifact. Reading it anyway would silently drop the member, and a
        // dropped member is a break nobody sees.
        val rendered = "\tpublic fun orphan ()V"

        val failure = assertThrows(IllegalArgumentException::class.java) {
            PublishedApiSurfaceReader.read(rendered)
        }
        assertTrue(
            failure.message.orEmpty().contains("before any declaration"),
            "the refusal did not say what was wrong: ${failure.message}",
        )
    }

    @Test
    fun `a declaration that is never closed is refused`() {
        // An unclosed declaration means the rest of the file was read as its
        // members, or not read at all. Either way the surface is not what the
        // artifact publishes, so it is refused.
        val rendered = "public final class dev/rubentxu/pipeline/v2/domain/AttemptId {\n\tpublic fun toString ()Ljava/lang/String;"

        val failure = assertThrows(IllegalArgumentException::class.java) {
            PublishedApiSurfaceReader.read(rendered)
        }
        assertTrue(
            failure.message.orEmpty().contains("never closed"),
            "the refusal did not say what was wrong: ${failure.message}",
        )
    }

    @Test
    fun `a declaration that is opened inside another is refused`() {
        // Two open declarations at once: the reader would have to guess which
        // one a member belongs to, and a guess here is a misattributed
        // approval.
        val rendered = """
            public final class dev/rubentxu/pipeline/v2/domain/A {
            	public fun toString ()Ljava/lang/String;
            public final class dev/rubentxu/pipeline/v2/domain/B {
            }
        """.trimIndent()

        val failure = assertThrows(IllegalArgumentException::class.java) {
            PublishedApiSurfaceReader.read(rendered)
        }
        assertTrue(
            failure.message.orEmpty().contains("never closed"),
            "the refusal did not say what was wrong: ${failure.message}",
        )
    }

    @Test
    fun `a line that is neither a header nor a member is refused`() {
        // The rendering has three shapes and no fourth. A fourth one means the
        // reader does not understand what it is reading, and a surface it does
        // not understand cannot certify anything.
        val rendered = """
            public final class dev/rubentxu/pipeline/v2/domain/A {
            	public fun toString ()Ljava/lang/String;
            }
            this is not a shape the engine renders
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            PublishedApiSurfaceReader.read(rendered)
        }
    }
}
