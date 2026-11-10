package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The authorization of the local service, decided.
 *
 * ## The guarantee under trial
 *
 * That the answer to "may this client ask about this run" depends on the
 * token AND on the scope, and on nothing else: not on the label, not on the
 * order of the authorizations, and not on whether the run exists. The scope
 * is checked BEFORE the store is read, so a client that may not ask about a
 * run learns nothing about whether that run is there.
 *
 * ## The capacity this serves
 *
 * An operator who hands a client a narrow token gets the narrow promise, and
 * a client that is refused learns WHICH refusal it was: "you are nobody" and
 * "not about that run" are two different facts and the client acts on them
 * differently — the first with another token, the second with a scope that
 * covers the run.
 *
 * ## Why this instrument
 *
 * The decision is PURE: it reads a token and a run id and answers with a
 * value. There is no socket, no store and no clock, so the cheapest thing
 * that can fail is to call it and read the answer.
 *
 * ## What this does not freeze
 *
 * The words of the refusals and the format of the file: the file's format is
 * read by [ServiceAuthorizationsReading.Refused], whose sentence names the
 * line, and the decision itself is three cases of a sealed type.
 */
class ServiceAuthorizationTest {

    @Test
    fun `a client that presents nothing is refused`() {
        val outcome = authorizations().decide(null, null)

        assertEquals(
            ServiceAuthorizationOutcome.NotAuthorized,
            outcome,
            "no token is no authorization",
        )
    }

    @Test
    fun `a client that presents a token nobody knows is refused`() {
        val outcome = authorizations().decide("token-of-another-client", null)

        assertEquals(
            ServiceAuthorizationOutcome.NotAuthorized,
            outcome,
            "a token the service does not know is the same fact as no token",
        )
    }

    @Test
    fun `a client that presents a known token may ask about the service itself`() {
        val outcome = authorizations().decide(TOKEN, null)

        assertEquals(
            ServiceAuthorizationOutcome.Authorized,
            outcome,
            "a question about the service names no run, so the token alone decides",
        )
    }

    @Test
    fun `a token with the scope all may ask about any run`() {
        val outcome = authorizations(ServiceScope.All).decide(TOKEN, "run-nobody-wrote")

        assertEquals(
            ServiceAuthorizationOutcome.Authorized,
            outcome,
            "the scope all covers every run, including one that does not exist",
        )
    }

    @Test
    fun `a token with the scope none may not ask about a run`() {
        val outcome = authorizations(ServiceScope.None).decide(TOKEN, "run-1")

        assertEquals(
            ServiceAuthorizationOutcome.RunNotAuthorized("run-1"),
            outcome,
            "the scope none covers no run, and the refusal names the run",
        )
    }

    @Test
    fun `a token with the scope of a run may ask about that run and about no other`() {
        val scoped = authorizations(ServiceScope.Runs(setOf("run-1")))

        assertEquals(
            ServiceAuthorizationOutcome.Authorized,
            scoped.decide(TOKEN, "run-1"),
            "the run the scope names is the run the client may ask about",
        )
        assertEquals(
            ServiceAuthorizationOutcome.RunNotAuthorized("run-2"),
            scoped.decide(TOKEN, "run-2"),
            "a run the scope does not name is refused, and the refusal names it",
        )
    }

    @Test
    fun `the label does not decide anything`() {
        val labelled = ServiceAuthorizations.of(
            listOf(ServiceAuthorization(TOKEN, "another-label", ServiceScope.None)),
        )

        assertEquals(
            ServiceAuthorizationOutcome.RunNotAuthorized("run-1"),
            labelled.decide(TOKEN, "run-1"),
            "the label is for the operator, and the scope is what decides",
        )
    }

    @Test
    fun `a line of token, label and scope is read`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator all")

        val authorizations = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the line holds an authorization",
        )
        assertEquals(1, authorizations.authorizations.size)
        assertEquals(TOKEN, authorizations.authorizations.first().token)
        assertEquals("operator", authorizations.authorizations.first().label)
        assertEquals(ServiceScope.All, authorizations.authorizations.first().scope)
    }

    @Test
    fun `a blank line and a comment are not authorizations`() {
        val reading = ServiceAuthorizations.parse("# the operator's file\n\n$TOKEN operator all\n")

        val authorizations = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the file holds one authorization and two lines that hold none",
        )
        assertEquals(1, authorizations.authorizations.size)
    }

    @Test
    fun `a line that is not three fields is refused and the line is named`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator\n")

        val refused = assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a line with two fields is not an authorization",
        )
        assertTrue(refused.detail.contains("line 1"), "the refusal names the line: ${refused.detail}")
    }

    @Test
    fun `a token that is too long is refused`() {
        val reading = ServiceAuthorizations.parse("${"x".repeat(513)} operator all")

        val refused = assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a token longer than the bound is not an authorization",
        )
        assertTrue(
            refused.detail.contains("512"),
            "the refusal names the bound: ${refused.detail}",
        )
    }

    @Test
    fun `a label with letters it may not hold is refused`() {
        val reading = ServiceAuthorizations.parse("$TOKEN oper/ator all")

        val refused = assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a label with a slash in it is not a label",
        )
        assertTrue(refused.detail.contains("line 1"), "the refusal names the line: ${refused.detail}")
    }

    @Test
    fun `a scope that is not a closed word is a list of run ids`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator run-1,run-2")

        val authorizations = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "a scope that is not 'all' and not 'none' names the runs it covers",
        )
        assertEquals(
            ServiceScope.Runs(setOf("run-1", "run-2")),
            authorizations.authorizations.first().scope,
            "the list names the runs and nothing else",
        )
    }

    @Test
    fun `a scope with an empty run id is refused`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator run-1,,run-2")

        assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a run id that is empty names no run",
        )
    }

    @Test
    fun `a scope that names a run id that cannot name a run is refused`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator run/1")

        val refused = assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a scope cannot name a run the service would refuse to answer about",
        )
        assertTrue(refused.detail.contains("line 1"), "the refusal names the line: ${refused.detail}")
    }

    @Test
    fun `the same token twice is refused`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator all\n$TOKEN other all\n")

        val refused = assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "two lines with the same token are two authorizations that are one",
        )
        assertTrue(refused.detail.contains("line 2"), "the refusal names the line: ${refused.detail}")
    }

    @Test
    fun `what the service writes is what the service reads`() {
        val written = ServiceAuthorizations.format(
            listOf(
                ServiceAuthorization(TOKEN, "operator", ServiceScope.All),
                ServiceAuthorization("token-2", "watcher", ServiceScope.Runs(setOf("run-1"))),
            ),
        )

        val reading = ServiceAuthorizations.parse(written)

        val authorizations = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the file the service writes is a file it can read",
        )
        assertEquals(2, authorizations.authorizations.size)
        assertEquals(
            ServiceScope.Runs(setOf("run-1")),
            authorizations.authorizations[1].scope,
            "the scope survives the round trip",
        )
    }

    @Test
    fun `a scope with the suffix start lets the client start runs`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator all+start")

        val authorizations = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the scope may end with the right to start a run",
        )
        assertEquals(
            ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start)),
            authorizations.authorizations.first().scope,
            "the right to start wraps the reading scope it adds to",
        )
    }

    @Test
    fun `a scope that starts nothing still may start runs`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator none+start")

        val authorizations = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the right to start and the right to read are two rights",
        )
        assertEquals(
            ServiceScope.WithRights(ServiceScope.None, setOf(ServiceScope.Right.Start)),
            authorizations.authorizations.first().scope,
            "a client that may start and read nothing is expressible",
        )
    }

    @Test
    fun `a scope with a capability this build does not know is refused`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator all+stop")

        assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a capability nobody serves is refused rather than taken for the default",
        )
    }

    @Test
    fun `a scope with the suffix cancel lets the client cancel the runs it covers`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator all+cancel")

        val authorizations = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the scope may end with the right to cancel a run",
        )
        assertEquals(
            ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Cancel)),
            authorizations.authorizations.first().scope,
            "the right to cancel wraps the reading scope it adds to",
        )
    }

    @Test
    fun `a scope that names no run may not carry the right to cancel`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator none+cancel")

        assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a scope that covers no run cannot exercise a right about a run",
        )
    }

    @Test
    fun `a client that may not cancel runs is refused by name`() {
        val outcome = authorizations(ServiceScope.All).decideCancel(TOKEN, "run-1")

        assertEquals(
            ServiceAuthorizationOutcome.CancelNotAuthorized,
            outcome,
            "the token was accepted and what it lacks is the right to cancel",
        )
    }

    @Test
    fun `a client that may cancel a run it does not cover is refused by name`() {
        val scoped = authorizations(
            ServiceScope.WithRights(ServiceScope.Runs(setOf("run-1")), setOf(ServiceScope.Right.Cancel)),
        )

        assertEquals(
            ServiceAuthorizationOutcome.RunNotAuthorized("run-2"),
            scoped.decideCancel(TOKEN, "run-2"),
            "a run the scope does not cover may not be cancelled, and the refusal names it",
        )
    }

    @Test
    fun `a client that may cancel the runs it covers is authorized`() {
        val scoped = authorizations(
            ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Cancel)),
        )

        assertEquals(
            ServiceAuthorizationOutcome.Authorized,
            scoped.decideCancel(TOKEN, "run-1"),
            "the right to cancel and the scope of the run are both asked",
        )
    }

    @Test
    fun `a client that presents nothing may not cancel runs`() {
        val scoped = authorizations(
            ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Cancel)),
        )

        assertEquals(
            ServiceAuthorizationOutcome.NotAuthorized,
            scoped.decideCancel(null, "run-1"),
            "no token is no authorization, and the cancel is not one of them",
        )
    }

    @Test
    fun `the right to start and the right to cancel are independent`() {
        val scope = ServiceScope.WithRights(
            ServiceScope.All,
            setOf(ServiceScope.Right.Start, ServiceScope.Right.Cancel),
        )
        val authorizations = authorizations(scope)

        assertEquals(
            ServiceAuthorizationOutcome.Authorized,
            authorizations.decideStart(TOKEN),
            "a client that may cancel may also start",
        )
        assertEquals(
            ServiceAuthorizationOutcome.Authorized,
            authorizations.decideCancel(TOKEN, "run-1"),
            "and one that may start may also cancel",
        )
    }

    @Test
    fun `the scope of a cancel survives the round trip`() {
        val written = ServiceAuthorizations.format(
            listOf(
                ServiceAuthorization(
                    TOKEN,
                    "operator",
                    ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Cancel)),
                ),
            ),
        )

        val reading = ServiceAuthorizations.parse(written)

        val authorizations = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the file the service writes is a file it can read",
        )
        assertEquals(
            ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Cancel)),
            authorizations.authorizations.first().scope,
            "the right to cancel survives the round trip",
        )
    }

    @Test
    fun `a client that may not start runs is refused by name`() {
        val outcome = authorizations(ServiceScope.All).decideStart(TOKEN)

        assertEquals(
            ServiceAuthorizationOutcome.StartNotAuthorized,
            outcome,
            "the token was accepted and what it lacks is the right to start",
        )
    }

    @Test
    fun `a client that may start runs is authorized to start them`() {
        val outcome = authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start))).decideStart(TOKEN)

        assertEquals(
            ServiceAuthorizationOutcome.Authorized,
            outcome,
            "the right to start is the scope's own word",
        )
    }

    @Test
    fun `a client that presents nothing may not start runs`() {
        val outcome = authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start))).decideStart(null)

        assertEquals(
            ServiceAuthorizationOutcome.NotAuthorized,
            outcome,
            "no token is no authorization, and the start is not one of them",
        )
    }

    @Test
    fun `the scope of a start keeps the reading rights it wraps`() {
        val scoped = authorizations(
            ServiceScope.WithRights(ServiceScope.Runs(setOf("run-1")), setOf(ServiceScope.Right.Start)),
        )

        assertEquals(
            ServiceAuthorizationOutcome.Authorized,
            scoped.decide(TOKEN, "run-1"),
            "the right to start does not take the reading rights away",
        )
        assertEquals(
            ServiceAuthorizationOutcome.RunNotAuthorized("run-2"),
            scoped.decide(TOKEN, "run-2"),
            "and it does not widen them either",
        )
    }

    @Test
    fun `the scope of a start survives the round trip`() {
        val written = ServiceAuthorizations.format(
            listOf(ServiceAuthorization(TOKEN, "operator", ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start)))),
        )

        val reading = ServiceAuthorizations.parse(written)

        val authorizations = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the file the service writes is a file it can read",
        )
        assertEquals(
            ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start)),
            authorizations.authorizations.first().scope,
            "the right to start survives the round trip",
        )
    }

    @Test
    fun `a scope with the suffix answer lets the client answer the questions of the runs it covers`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator all+answer")

        val authorizations = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the scope may end with the right to answer the questions of a run",
        )
        assertEquals(
            ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Answer)),
            authorizations.authorizations.first().scope,
            "the right to answer wraps the reading scope it adds to",
        )
    }

    @Test
    fun `a scope that names no run may not carry the right to answer`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator none+answer")

        assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a scope that covers no run cannot exercise a right about a run",
        )
    }

    @Test
    fun `the right to answer named twice is refused`() {
        val reading = ServiceAuthorizations.parse("$TOKEN operator all+answer+answer")

        assertInstanceOf(
            ServiceAuthorizationsReading.Refused::class.java,
            reading,
            "a right named twice is a file with a mistake",
        )
    }

    @Test
    fun `a client that may not answer is refused by name whatever run it names`() {
        val readOnly = authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Cancel)))

        assertEquals(
            ServiceAuthorizationOutcome.AnswerNotAuthorized,
            readOnly.decideAnswer(TOKEN, "run-1"),
            "the token was accepted and what it lacks is the right to answer",
        )
        assertEquals(
            ServiceAuthorizationOutcome.AnswerNotAuthorized,
            readOnly.decideAnswer(TOKEN, "a-run-nobody-has"),
            "the right is asked before the run, so the refusal says nothing about runs",
        )
    }

    @Test
    fun `a client that may answer a run it does not cover is refused by name`() {
        val scoped = authorizations(
            ServiceScope.WithRights(ServiceScope.Runs(setOf("run-1")), setOf(ServiceScope.Right.Answer)),
        )

        assertEquals(
            ServiceAuthorizationOutcome.RunNotAuthorized("run-2"),
            scoped.decideAnswer(TOKEN, "run-2"),
            "a run the scope does not cover may not be answered, and the refusal names it",
        )
        assertEquals(
            ServiceAuthorizationOutcome.Authorized,
            scoped.decideAnswer(TOKEN, "run-1"),
            "and a run it covers may",
        )
    }

    @Test
    fun `a client that presents nothing or a token nobody knows may not answer`() {
        val scoped = authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Answer)))

        assertEquals(ServiceAuthorizationOutcome.NotAuthorized, scoped.decideAnswer(null, "run-1"), "no token")
        assertEquals(
            ServiceAuthorizationOutcome.NotAuthorized,
            scoped.decideAnswer("another-token", "run-1"),
            "a token the service does not know",
        )
    }

    @Test
    fun `the right to answer is independent of the rights to start and to cancel`() {
        val onlyStartAndCancel = authorizations(
            ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Start, ServiceScope.Right.Cancel)),
        )
        val onlyAnswer = authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Answer)))

        assertEquals(
            ServiceAuthorizationOutcome.AnswerNotAuthorized,
            onlyStartAndCancel.decideAnswer(TOKEN, "run-1"),
            "a client that may start and cancel does not thereby answer",
        )
        assertEquals(
            ServiceAuthorizationOutcome.CancelNotAuthorized,
            onlyAnswer.decideCancel(TOKEN, "run-1"),
            "and a client that may answer does not thereby cancel",
        )
        assertEquals(
            ServiceAuthorizationOutcome.StartNotAuthorized,
            onlyAnswer.decideStart(TOKEN),
            "nor start",
        )
    }

    @Test
    fun `the service knows whether any authorization may answer`() {
        assertTrue(
            authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Answer))).mayAnswerAnyInput,
            "one authorization with the right is enough",
        )
        assertTrue(
            !authorizations(ServiceScope.WithRights(ServiceScope.All, setOf(ServiceScope.Right.Cancel))).mayAnswerAnyInput,
            "a service nobody granted the right to has no reason to compose the channel",
        )
    }

    @Test
    fun `the label of an authorization is found by the digest of its token and by nothing else`() {
        val authorizations = authorizations()

        assertEquals(
            "operator",
            authorizations.labelOfDigest(ServiceAuthorizations.digestOf(TOKEN)),
            "the name the operator gave the token is the one that comes out",
        )
        assertEquals(null, authorizations.labelOfDigest(null), "a run nobody started has no responder")
        assertEquals(
            null,
            authorizations.labelOfDigest(ServiceAuthorizations.digestOf("another-token")),
            "a digest the service does not know names nobody",
        )
        assertEquals(null, authorizations.labelOfDigest("operator"), "the label itself is not a digest")
    }

    @Test
    fun `the scope of an answer survives the round trip`() {
        val written = ServiceAuthorizations.format(
            listOf(
                ServiceAuthorization(
                    TOKEN,
                    "operator",
                    ServiceScope.WithRights(
                        ServiceScope.Runs(setOf("run-1")),
                        setOf(ServiceScope.Right.Cancel, ServiceScope.Right.Answer),
                    ),
                ),
            ),
        )

        val reading = ServiceAuthorizations.parse(written)

        val authorizations = assertInstanceOf(
            ServiceAuthorizationsReading.Read::class.java,
            reading,
            "the file the service writes is a file it can read",
        )
        assertEquals(
            ServiceScope.WithRights(
                ServiceScope.Runs(setOf("run-1")),
                setOf(ServiceScope.Right.Cancel, ServiceScope.Right.Answer),
            ),
            authorizations.authorizations.first().scope,
            "the right to answer survives the round trip next to the others",
        )
    }

    /**
     * The authorizations this service knows.
     */
    private fun authorizations(scope: ServiceScope = ServiceScope.All): ServiceAuthorizations =
        ServiceAuthorizations.of(listOf(ServiceAuthorization(TOKEN, "operator", scope)))

    private companion object {
        /** The token this service knows. */
        private const val TOKEN: String = "token-1"
    }
}
