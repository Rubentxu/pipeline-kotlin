package dev.rubentxu.pipeline.v2.sdk.http

import dev.rubentxu.pipeline.v2.domain.step.EgressDestination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The plugin's half of egress admission: reducing an author-supplied URL to the destination the
 * runtime is asked about.
 *
 * This file holds no policy. Its whole contract is that the reduction either yields exactly the
 * destination the author named, or yields nothing at all. A reduction that silently lands on a
 * different destination is worse than a refusal, because the refusal is visible in the journal
 * and the substitution is not: the run opens a socket to somewhere the author did not write down,
 * and an allowlist written for the real destination does not govern it.
 *
 * The URL is external input, so every case here is a string a pipeline author can actually type.
 */
class EgressDestinationOfTest {

    // --- the destination is the one that was written ------------------------------

    @Test
    fun `an explicit port is the destination port`() {
        assertEquals(
            EgressDestination("https", "api.example.test", 8443),
            egressDestinationOf("https://api.example.test:8443/"),
        )
    }

    @Test
    fun `an omitted port takes the scheme default`() {
        assertEquals(EgressDestination("https", "api.example.test", 443), egressDestinationOf("https://api.example.test"))
        assertEquals(EgressDestination("http", "api.example.test", 80), egressDestinationOf("http://api.example.test"))
    }

    @Test
    fun `an unrecognised scheme with an explicit port is still a destination`() {
        // Nothing here decides which schemes may be reached. A scheme this product has no default
        // port for is not undeterminable when the author named the port themselves.
        assertEquals(EgressDestination("gopher", "h.example.test", 70), egressDestinationOf("gopher://h.example.test:70"))
    }

    @Test
    fun `an unrecognised scheme without a port is undeterminable`() {
        // There is no default to fall back to, and inventing one would be the guess this type
        // exists to avoid.
        assertNull(egressDestinationOf("gopher://h.example.test"))
    }

    @Test
    fun `host and scheme are lowercased, because both are case-insensitive`() {
        // Two spellings of one destination would produce two different strings in a refusal, and
        // an operator reading the journal has to be able to match one to the other.
        assertEquals(
            egressDestinationOf("https://api.example.test"),
            egressDestinationOf("HTTPS://API.EXAMPLE.TEST"),
        )
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(EgressDestination("https", "api.example.test", 443), egressDestinationOf("  https://api.example.test  "))
    }

    @Test
    fun `userinfo is not carried into the destination`() {
        // `https://user:secret@host/` is a real URL shape. Copying any part of the authority other
        // than the host is how a credential ends up in a refusal that goes to the journal, so the
        // reduction must drop it rather than merely avoid reading it.
        assertEquals(EgressDestination("https", "api.example.test", 443), egressDestinationOf("https://user:secret@api.example.test/"))
    }

    @Test
    fun `path, query and fragment do not change the destination`() {
        // Named in the domain type: a query string or a path has no bearing on whether a socket may
        // be opened, and a rule written against one would turn into a rule about the host the
        // first time a redirect rewrites the path.
        val base = "https://api.example.test"
        assertEquals(egressDestinationOf(base), egressDestinationOf("$base/deep/path?q=1#frag"))
    }

    // --- an explicit port is still a port -----------------------------------------

    @Test
    fun `an explicit port outside the legal range is undeterminable, not defaulted`() {
        // The case this file exists to pin. A port the author wrote that cannot exist must not be
        // quietly exchanged for the scheme's default: the request would then open a socket to a
        // destination nobody wrote down, under an allowlist nobody wrote for it. It was found by
        // reading the branch -- `uri.port in 1..65535` falls through to the default for a port
        // that is present and invalid, so `:0` and `:99999` both became `:443`.
        assertNull(egressDestinationOf("https://api.example.test:0"))
        assertNull(egressDestinationOf("https://api.example.test:99999"))
        assertNull(egressDestinationOf("https://api.example.test:65536"))
        assertNull(egressDestinationOf("http://api.example.test:0"))
    }

    @Test
    fun `the legal range edges are accepted`() {
        // A check that rejected only the obviously-wrong values would pass the test above. The two
        // edges are where an off-by-one would live.
        assertEquals(EgressDestination("https", "h.test", 1), egressDestinationOf("https://h.test:1"))
        assertEquals(EgressDestination("https", "h.test", 65535), egressDestinationOf("https://h.test:65535"))
    }

    // --- undeterminable is a value, not an exception -------------------------------

    @Test
    fun `no url at all makes the reduction throw`() {
        val hostile = listOf(
            "",
            "   ",
            "not a url",
            "://missing-scheme",
            "https://",
            "//no-scheme",
            "http://[unclosed",
            "https://h.test:notaport",
            "ht tp://h.test",
        )

        for (url in hostile) {
            val outcome = try {
                egressDestinationOf(url)
            } catch (e: Throwable) {
                throw AssertionError("egressDestinationOf(\"$url\") threw ${e::class.simpleName}", e)
            }
            assertNull(outcome, "egressDestinationOf(\"$url\") produced $outcome instead of refusing")
        }
    }
}
