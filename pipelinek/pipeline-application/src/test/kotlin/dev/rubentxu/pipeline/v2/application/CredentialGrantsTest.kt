package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The credentials an operator grants to a client, read from the file of
 * authorizations.
 *
 * ## The guarantee under trial
 *
 * That a grant is what the operator wrote and nothing else: a credential
 * and the kind it may be requested as, attached to the authorization that
 * carries it. A client that holds no grant holds NONE, a grant is never read
 * from a line that merely resembles one, and a grant the broker cannot honour
 * is refused when the file is read, not when a step asks for it.
 *
 * ## The capacity this serves
 *
 * A run asks for a credential only when it enters a scope that needs it, and
 * the broker answers from the grants of the client that STARTED the run. For
 * that, the service has to know, per authorization, which credentials it may
 * hand out — and has to know it from the same file that says who may ask.
 *
 * ## Why this instrument
 *
 * The reading is PURE, like the rest of the authorization: text in, grants
 * out. There is no store and no socket to set up, so the cheapest thing that
 * can fail is to parse a line and read what came out.
 *
 * ## What this does not freeze
 *
 * The words of the refusals, beyond naming the line, and the vocabulary of
 * the kinds: today the broker carries one, and the file refuses every other.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CredentialGrantsTest {

    @Test
    fun `a fourth field grants the credentials it names`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator all credentials=deploy-key:string,npm.token:string")

        val read = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "a line with a fourth field is an authorization with grants",
        )
        assertEquals(
            setOf(
                CredentialGrant("deploy-key", BrokeredCredentialKind.STRING),
                CredentialGrant("npm.token", BrokeredCredentialKind.STRING),
            ),
            read.authorizations.single().credentialGrants,
            "the grants are exactly the ones the operator wrote",
        )
    }

    @Test
    fun `an authorization without the fourth field grants nothing`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator all")

        val read = assertInstanceOf(ServiceAuthorizationsReading.Read::class.java, reading)
        assertEquals(
            emptySet<CredentialGrant>(),
            read.authorizations.single().credentialGrants,
            "no field, no grant: a credential is granted by being named",
        )
    }

    @Test
    fun `a kind the broker does not carry is refused with the line`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator all credentials=deploy-key:file")

        val refused = assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a grant the broker cannot honour is a file with a mistake",
        )
        assertTrue(refused.detail.contains("line 1"), "the refusal names the line: ${refused.detail}")
        assertTrue(refused.detail.contains("string"), "the refusal names the kind the broker does carry: ${refused.detail}")
    }

    @Test
    fun `a credential named twice is refused even with the same kind`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator all credentials=deploy-key:string,deploy-key:string")

        val refused = assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a grant named twice is a file that says something it did not mean",
        )
        assertTrue(refused.detail.contains("line 1"), "the refusal names the line: ${refused.detail}")
    }

    @Test
    fun `a credential id outside the alphabet is refused`() {
        for (text in listOf("a/b:string", ":string", "a b:string", "ключ:string", "a:b:string", "deploy-key", "deploy-key:")) {
            val reading = ServiceAuthorizations.parse("$TOKEN operator all credentials=$text")

            assertInstanceOf(
                ServiceAuthorizationsReading.Refused::class.java,
                reading,
                "'$text' is not a grant the file may carry",
            )
        }
    }

    @Test
    fun `an empty list of grants is refused`() {
        for (text in listOf("credentials=", "credentials=,", "credentials=deploy-key:string,")) {
            val reading = ServiceAuthorizations.parse("$TOKEN operator all $text")

            assertInstanceOf(
                ServiceAuthorizationsReading.Refused::class.java,
                reading,
                "'$text' names a grant that is not there",
            )
        }
    }

    @Test
    fun `a fourth field that is not a list of credentials is refused`() {
        for (text in listOf("secrets=deploy-key:string", "deploy-key:string", "Credentials=deploy-key:string")) {
            val reading = ServiceAuthorizations.parse("$TOKEN operator all $text")

            val refused = assertInstanceOf(
                ServiceAuthorizationsReading.Refused::class.java,
                reading,
                "'$text' is not the field that grants credentials",
            )
            assertTrue(refused.detail.contains("line 1"), "the refusal names the line: ${refused.detail}")
        }
    }

    @Test
    fun `a fifth field is refused`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator all credentials=deploy-key:string extra")

        assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "an authorization has at most four fields",
        )
    }

    @Test
    fun `a credential id longer than the bound is refused`() {
        val reading = ServiceAuthorizations.parse(
            "$TOKEN operator all credentials=${"k".repeat(CredentialGrant.MAX_CREDENTIAL_ID_LENGTH + 1)}:string",
        )

        assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "the bound is part of the grant, so a longer id is not one",
        )
    }

    @Test
    fun `the grants survive the round trip in a stable order`() {
        val grants = linkedSetOf(
            CredentialGrant("zeta", BrokeredCredentialKind.STRING),
            CredentialGrant("alpha", BrokeredCredentialKind.STRING),
        )

        val written = ServiceAuthorizations.format(
            listOf(ServiceAuthorization(TOKEN, "operator", ServiceScope.All, grants)),
        )
        val reading = ServiceAuthorizations.parse(written)

        val read = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the file the service writes is a file it can read",
        )
        assertEquals(grants, read.authorizations.single().credentialGrants, "the grants survive the round trip")
        assertTrue(
            written.endsWith("credentials=alpha:string,zeta:string"),
            "the order the file is written in does not depend on the order the grants were built in: $written",
        )
    }

    @Test
    fun `an authorization that grants nothing is written with three fields`() {
        val written = ServiceAuthorizations.format(listOf(ServiceAuthorization(TOKEN, "operator", ServiceScope.All)))

        assertEquals("$TOKEN operator all", written, "the file an older service wrote is the file this one writes")
    }

    @Test
    fun `the grants are found by the digest of the token and by nothing else`() {
        val grants = setOf(CredentialGrant("deploy-key", BrokeredCredentialKind.STRING))
        val authorizations = ServiceAuthorizations.of(
            listOf(
                ServiceAuthorization(TOKEN, "operator", ServiceScope.All, grants),
                ServiceAuthorization("token-2", "watcher", ServiceScope.All),
            ),
        )

        assertEquals(grants, authorizations.grantsOfDigest(ServiceAuthorizations.digestOf(TOKEN)))
        assertEquals(
            emptySet<CredentialGrant>(),
            authorizations.grantsOfDigest(ServiceAuthorizations.digestOf("token-2")),
            "a client that holds no grant holds none, not the grants of another",
        )
        assertEquals(
            emptySet<CredentialGrant>(),
            authorizations.grantsOfDigest(ServiceAuthorizations.digestOf("token-3")),
            "a token the service does not know holds no grant",
        )
        assertEquals(
            emptySet<CredentialGrant>(),
            authorizations.grantsOfDigest(null),
            "a run no client started holds no grant",
        )
        assertEquals(
            emptySet<CredentialGrant>(),
            authorizations.grantsOfDigest(TOKEN),
            "the token is not the digest: presenting it where the digest goes finds nothing",
        )
    }

    @Test
    fun `whether the service grants any credential is a fact it can state`() {
        val without = ServiceAuthorizations.of(listOf(ServiceAuthorization(TOKEN, "operator", ServiceScope.All)))
        val with = ServiceAuthorizations.of(
            listOf(
                ServiceAuthorization(
                    TOKEN,
                    "operator",
                    ServiceScope.All,
                    setOf(CredentialGrant("deploy-key", BrokeredCredentialKind.STRING)),
                ),
            ),
        )

        assertEquals(false, without.grantsAnyCredential, "no grant, no store to open")
        assertEquals(true, with.grantsAnyCredential, "one grant is reason enough to open the store")
    }

    private companion object {
        /** The token this service knows. */
        private const val TOKEN: String = "token-1"
    }
}
