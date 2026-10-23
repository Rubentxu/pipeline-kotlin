package dev.rubentxu.pipeline.v2.output.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * `parseCommittedOffset` reads the body of a stream's `cur.cmt` and decides between two outcomes:
 * the value parses cleanly or it does not. The store's recovery and read paths rely on this being a
 * *total* decision with a distinguishable failure shape, because a corrupt commit record used to
 * silently become `0L`, which let `reconcile` truncate the live segment to a negative size. The fix
 * at the call site is `?: 0L -> throw IOException`; these tests pin the parser's contract so a
 * future "let's just default to zero" refactor lights RED on the precise rows it would silently
 * break.
 *
 * The function is pure: a `String` in, a `Long` out or an [IOException] out. No disk, no clock, no
 * step, no subprocess. AGENTS.md forbids the heavier setup for a unit test of a domain rule, and
 * this rule has a heavier setup cost far above its value — the on-disk admission is exercised by
 * the higher paths. See [SegmentOutputStore.committedLocked] for the call site.
 *
 * ## Discriminative mutations the test must catch
 *   * `trim().toLongOrNull() ?: 0L` reverted in `committedLocked` — keeps `""` GREEN; this file's
 *     "an empty body refuses with IOException" row is what lights RED for it.
 *   * The lambda used to swallow `IOException` so read paths keep their `OutputReadResult.Refused`
 *     surface — would let every empty-body run quietly return 0; the same row catches it.
 *   * A `require(parsed >= 0)` removed — would let `-5` parse to `-5L`; the "negative body" row is
 *     what catches it.
*/
class CommittedOffsetParseTest {

    // --- the guarantee: a parseable body returns the same number --------------------------

    @Test
    fun `a plain decimal body parses to its long value`() {
        assertEquals(0L, parseCommittedOffset("0"))
        assertEquals(1L, parseCommittedOffset("1"))
        assertEquals(42L, parseCommittedOffset("42"))
        assertEquals(4_096L, parseCommittedOffset("4096"))
    }

    @Test
    fun `a body trimmed at the edges still parses`() {
        // The writer writes the body terminated with `\n`, and a stray whitespace at the start
        // arrives when an external tool splices the file. The row is two halves rather than one
        // because trimming and parsing are two distinct decisions, and a "fix" that strips digits
        // (instead of whitespace) would still satisfy the half above and break this one.
        assertEquals(42L, parseCommittedOffset("  42  "))
        assertEquals(42L, parseCommittedOffset("42\n"))
        assertEquals(42L, parseCommittedOffset("\n42"))
        assertEquals(42L, parseCommittedOffset("\n42\n"))
    }

    // --- the refusal: an unparseable body does not become a default -----------------------

    @Test
    fun `an empty body refuses with IOException`() {
        // THE ROW. The defect was a silent `?: 0L` here; reverting the throw to the silent
        // default makes this row pass with `assertEquals(0L, …)` and the test goes RED on it
        // before the change and GREEN after the change.
        val thrown = assertThrows(IOException::class.java) {
            parseCommittedOffset("")
        }
        assertEquals(
            true,
            thrown.message?.contains("cur.cmt") == true,
            "the message must name the file the operator is looking at: <${thrown.message}>",
        )
    }

    @Test
    fun `a non-numeric body refuses with IOException`() {
        // Stated as a refusal on purpose. An IOException for non-numeric input is what separates
        // this row from "the parse accepted anything as 0L".
        assertThrows(IOException::class.java) { parseCommittedOffset("not-a-number") }
        assertThrows(IOException::class.java) { parseCommittedOffset("42abc") }
        assertThrows(IOException::class.java) { parseCommittedOffset("  abc  ") }
        assertThrows(IOException::class.java) { parseCommittedOffset("42.0") }
    }

    // --- a non-negative offset is structural: a negative body has no reading --------------

    @Test
    fun `a negative body is refused`() {
        // The store has no place to put a negative offset: cursors name positions and the cursor
        // signedness is non-negative. The defect a `require(parsed >= 0)` removal would reintroduce
        // is that a corrupted `-5` reaches a tail reader and reports a negative "next" page —
        // indistinguishable from "real end of stream" minus a sign.
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            parseCommittedOffset("-1")
        }
        assertEquals(
            true,
            thrown.message?.contains("negative") == true,
            "the message must say WHY a negative is refused: <${thrown.message}>",
        )
    }

    // --- the writer and the reader speak ONE format -------------------------------------

    @Test
    fun `the body the writer writes is the body this parser reads back`() {
        // The writer and the reader are the two ends of one format. The format used to be spelled
        // on the way in twice and read on the way out once, with nothing tying the three together:
        // a writer that spelled it differently would commit records its own reader refuses, and the
        // store would answer "how far is this stream committed" with a refusal that looks like
        // corruption and is really a typo.
        assertEquals(0L, parseCommittedOffset(commitRecordBody(0L)))
        assertEquals(1L, parseCommittedOffset(commitRecordBody(1L)))
        assertEquals(4096L, parseCommittedOffset(commitRecordBody(4096L)))
        assertEquals(1_048_576L, parseCommittedOffset(commitRecordBody(1_048_576L)))
    }

    @Test
    fun `the body the writer writes is never empty`() {
        // An empty body is what a reader sees when it looks at the record while it is being
        // written in place, and this store refuses to interpret an empty record: deciding what an
        // unreadable commit record means is a product decision and this store does not make it
        // silently. A writer that could produce one would turn every concurrent read into a
        // refusal, and the live observer of a running pipeline would die on it.
        val body = commitRecordBody(4096L)
        assertEquals(true, body.isNotBlank(), "the commit record body must carry the offset")
        assertEquals(false, body.trim().isEmpty())
    }
}
