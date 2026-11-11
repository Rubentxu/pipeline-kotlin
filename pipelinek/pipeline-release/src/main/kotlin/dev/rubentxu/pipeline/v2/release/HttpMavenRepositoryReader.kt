package dev.rubentxu.pipeline.v2.release

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * A repository read over HTTP, with the JDK's own client.
 *
 * The client is the one the JDK ships, so the channel adds no dependency to
 * audit and no behaviour to inherit. The adapter's whole job is to turn an
 * answer into one of the three facts of the port: the digest of the bytes
 * that arrived, the absence of the path, or an answer that is neither.
 *
 * The digest is MEASURED from the bytes that arrived, never taken from what
 * the repository said about them. A registry that answers with a checksum
 * beside the file is reporting a fact about its own copy, and the question
 * this reader answers is whether those bytes are the certified ones.
 *
 * The reader answers about the repository that was NAMED. A redirect names
 * another address, so it is not followed: the bytes would come from
 * somewhere else while the verdict spoke about this one. A repository that
 * answers with a redirect has not been measured, and the reader says so
 * instead of measuring another place.
 *
 * The repository is reached over TLS, because the credential that may travel
 * with the request is worth nothing in clear text. The one exception is the
 * loopback, where the "network" is the machine itself: that is where the
 * tests of this adapter run, and a plain address there cannot be read from
 * the wire.
 *
 * The credential goes to the repository that ASKS for it, and to no other.
 * The credential is sent the first time only after the repository has
 * answered that it wants one (401 or 403), and from then on the reader knows
 * what that repository is: a repository that demands a credential gets it,
 * and a repository that does not demand one never sees it. That matters
 * because the credential is read from the environment and the environment is
 * shared: an operator who holds a token for one registry and asks about
 * another must not send the first one to the second.
 *
 * The credential, when the repository asks for one, arrives in the
 * constructor and in nothing else: it is never written to a report, never
 * printed and never part of an address. Two shapes are offered because the
 * registries of this channel differ: a user and a token travel as HTTP Basic
 * authentication, and a token alone travels as a bearer credential.
 */
class HttpMavenRepositoryReader(
    private val baseUrl: String,
    private val user: String? = null,
    private val token: String? = null,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val totalBudget: Duration = DEFAULT_TOTAL_BUDGET,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    private val client: HttpClient = defaultClient(),
) : MavenRepositoryReader {

    init {
        // An address that carries a credential in it is an address that would
        // put that credential in the argument list, in the verdict and in the
        // log of every tool that prints it. The credential of this instrument
        // travels in the environment, and an address that carries one is
        // refused before it is used.
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        require(uri?.userInfo == null) {
            "the repository address carries a credential in it. The credential of this check " +
                "travels in the environment (PIPELINEK_REPOSITORY_USER and " +
                "PIPELINEK_REPOSITORY_TOKEN), and an address that carries one would carry it " +
                "into the argument list, into the verdict and into the log."
        }
        // A credential that is half given is no credential at all, and a
        // reader that sends none of it would send the same request twice and
        // report a repository that refuses what it was never given.
        require(user == null || token != null) {
            "the credential is incomplete: a user without a token is not a credential, and " +
                "the repository would be asked twice with nothing the second time."
        }
        // The credential travels in clear text unless the transport is
        // encrypted. The loopback is the exception, and it is the exception
        // because there is no wire there to read from.
        require(uri != null && uri.scheme != null && uri.host != null) {
            "the repository address is not an address this reader can use."
        }
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host.isLoopback())) {
            "the repository must be reached over TLS, and ${uri.scheme} is not TLS. The " +
                "credential of this check would travel in clear text, and the only address " +
                "this reader accepts without TLS is the loopback."
        }
    }

    /**
     * Whether this repository has already demanded a credential.
     *
     * The reader learns it from the repository's own answers, and it keeps it
     * for the rest of its life: a repository that demanded a credential once
     * will demand it of every path, and asking again to learn it again would
     * be a request the repository has already answered.
     */
    private var credentialDemanded: Boolean = false

    /**
     * When the reader started, so that the whole check has a budget.
     *
     * A repository that answers slowly must not turn the check into a job
     * that never ends: the artifacts of a release are tens of files, and a
     * run that is still asking after minutes is a run that will not measure
     * what it was asked to measure.
     */
    private val startedAt: Long = System.nanoTime()

    override fun read(path: String): MavenServedFile {
        val refusal = refuseToAsk(path)
        if (refusal != null) return MavenServedFile.Unreadable(refusal)

        val remaining = remainingBudget()
        if (remaining == null) return MavenServedFile.Unreadable(
            "the check ran out of time before it asked for ${quoteForReport(path)}, so the state " +
                "of the version was not measured",
        )

        val first = send(path, withCredential = credentialDemanded, remaining = remaining)
        val failure = first.failure
        if (failure != null) return MavenServedFile.Unreadable(failure)

        when (first.status) {
            200 -> return present(first, path)
            404 -> {
                first.close()
                return MavenServedFile.Absent
            }
            401, 403 -> {
                if (!credentialDemanded && hasCredential()) {
                    // The repository has asked for a credential. Now, and only
                    // now, it gets one.
                    credentialDemanded = true
                    first.close()
                    val second = send(path, withCredential = true, remaining = remainingBudget() ?: remaining)
                    val secondFailure = second.failure
                    if (secondFailure != null) return MavenServedFile.Unreadable(secondFailure)
                    return when (second.status) {
                        200 -> present(second, path)
                        404 -> {
                            second.close()
                            MavenServedFile.Absent
                        }
                        else -> {
                            second.close()
                            MavenServedFile.Unreadable(
                                "the repository answered ${second.status} for ${quoteForReport(path)} " +
                                    "after it asked for a credential, so the credential it was " +
                                    "given does not open this path",
                            )
                        }
                    }
                }
                first.close()
                return MavenServedFile.Unreadable(
                    "the repository answered ${first.status} for ${quoteForReport(path)}, and it " +
                        "did not get a credential it accepts",
                )
            }
            else -> {
                first.close()
                return MavenServedFile.Unreadable(
                    "the repository answered ${first.status} for ${quoteForReport(path)}",
                )
            }
        }
    }

    /** The request that asks the repository for [path]. */
    private fun requestFor(path: String, withCredential: Boolean, remaining: Duration): HttpRequest {
        val builder = HttpRequest
            .newBuilder(URI.create("${baseUrl.trimEnd('/')}/${path.trimStart('/')}"))
            .timeout(minOf(timeout, remaining))
            .GET()
        if (!withCredential) return builder.build()
        when {
            token != null && user != null -> builder.header(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString("$user:$token".toByteArray()),
            )

            token != null -> builder.header("Authorization", "Bearer $token")
            else -> Unit
        }
        return builder.build()
    }

    /**
     * Ask the repository for [path] and read the answer.
     *
     * The answer is what the repository said and the bytes it sent, and never
     * an exception: a transport that fails is an answer nobody could read,
     * and the reader says so instead of guessing.
     */
    private fun send(path: String, withCredential: Boolean, remaining: Duration): Answer {
        val request = try {
            requestFor(path, withCredential, remaining)
        } catch (e: IllegalArgumentException) {
            return Answer(failure = "the address of ${quoteForReport(path)} is not an address: " +
                (e.message ?: e::class.java.simpleName))
        }

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            Answer(status = response.statusCode(), body = response.body())
        } catch (e: IOException) {
            Answer(failure = "the repository did not answer for ${quoteForReport(path)}: " +
                (e.message ?: e::class.java.simpleName))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Answer(failure = "the request for ${quoteForReport(path)} was interrupted")
        }
    }

    /**
     * The digest of the bytes the repository sent, when it sent the file.
     *
     * The body is hashed while it arrives, so it never has to be resident,
     * and it is read with a cap: a repository that streams an unbounded
     * answer would take the memory of the machine that runs the check, and a
     * check that dies of memory did not measure anything.
     */
    private fun present(answer: Answer, path: String): MavenServedFile {
        val body = answer.body ?: return MavenServedFile.Unreadable(
            "the repository answered ${answer.status} for ${quoteForReport(path)} without a body",
        )
        val digest = try {
            body.use { Sha256.ofStream(LimitedStream(it, maxBodyBytes)) }
        } catch (e: TooManyBytesException) {
            return MavenServedFile.Unreadable(
                "the repository sent more than $maxBodyBytes bytes for ${quoteForReport(path)}, " +
                    "and an answer that big is not an artifact this check can measure",
            )
        }
        return MavenServedFile.Present(digest)
    }

    /**
     * The reason why [path] is not a path this reader asks for, or null when
     * it is one.
     *
     * A path that carries a query, a fragment, an escape or a relative
     * segment is turned into an address that is not the path the verdict
     * names: the query is dropped, the escape is decoded and the relative
     * segment is resolved by whoever answers. A path that is asked in
     * another form is measured in another form, and the verdict would name
     * the one it wrote down.
     */
    private fun refuseToAsk(path: String): String? = when {
        path.isBlank() ->
            "the path is blank, and there is no path to ask for"

        path.contains('?') || path.contains('#') ->
            "the path ${quoteForReport(path)} carries a query or a fragment, and the address " +
                "that would be asked is not the path the verdict names"

        path.contains('%') ->
            "the path ${quoteForReport(path)} carries an escape, and the address that would be " +
                "asked is not the path the verdict names"

        path.any { it.isISOControl() } ->
            "the path carries a control character, and a path that says one thing on a line and " +
                "another in the report is not a path this check can measure"

        path.split('/').any { it == "." || it == ".." } ->
            "the path ${quoteForReport(path)} carries a relative segment, and the address that " +
                "would be asked is not the path the verdict names"

        else -> null
    }

    /** Whether a credential is held at all. */
    private fun hasCredential(): Boolean = token != null || user != null

    /** What is left of the budget of this check, or null when it is spent. */
    private fun remainingBudget(): Duration? {
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
        val remaining = totalBudget.minus(elapsed)
        return if (remaining.isNegative || remaining.isZero) null else remaining
    }

    /**
     * One answer of the repository: the status it answered, the bytes it sent
     * and the reason why there is no answer at all.
     */
    private class Answer(
        val status: Int = 0,
        val body: InputStream? = null,
        val failure: String? = null,
    ) {
        /** Close the body of an answer nobody is going to read. */
        fun close() {
            try {
                body?.close()
            } catch (e: IOException) {
                // The answer was not read, and a body that cannot be closed
                // does not change what the repository said.
            }
        }
    }

    /**
     * A stream that refuses to carry more than a limit of bytes.
     *
     * The limit is enforced while the bytes arrive, so an answer that is
     * over it is stopped instead of being held.
     */
    private class LimitedStream(
        private val source: InputStream,
        private val limit: Int,
    ) : InputStream() {

        private var carried: Int = 0

        override fun read(): Int {
            val buffer = ByteArray(1)
            val read = read(buffer, 0, 1)
            return if (read < 0) -1 else buffer[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = source.read(buffer, offset, length)
            if (read > 0) {
                carried += read
                if (carried > limit) throw TooManyBytesException()
            }
            return read
        }

        override fun close() = source.close()
    }

    /** The answer carried more bytes than this check is willing to measure. */
    private class TooManyBytesException : RuntimeException("the answer is over the limit")

    companion object {

        /** How long a request may take before the answer is not an answer. */
        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)

        /**
         * How long the whole check may take.
         *
         * The artifacts of a release are tens of files, and a repository that
         * answers in a second each finishes well inside this. A repository
         * that does not is a repository this check cannot measure, and the
         * check says so instead of asking for days.
         */
        private val DEFAULT_TOTAL_BUDGET: Duration = Duration.ofMinutes(10)

        /**
         * How many bytes an answer may carry before it stops being an
         * artifact.
         *
         * The largest file this channel publishes is a JAR of a few megabytes,
         * and a cap of a hundred megabytes leaves room for a growth of the
         * product while refusing the answer that would take the machine down.
         */
        private const val DEFAULT_MAX_BODY_BYTES: Int = 100 * 1024 * 1024

        /** The client every reader starts from. */
        private fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // A redirect is NOT followed. The instrument answers a question
            // about the repository that was NAMED, and a redirect names
            // another address: the bytes would come from somewhere else
            // while the verdict spoke about this one. It also keeps the
            // credential to the address it was sent to, because there is no
            // second hop. A repository that answers with a redirect has not
            // been measured, and the verdict says so.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }
}

/**
 * Whether this host is the machine itself.
 *
 * The loopback is where a test runs its own registry, and it is the only
 * address this check accepts without TLS. The names are compared as they
 * are written: no name is resolved, because a name that resolves is a
 * question with an answer outside this file.
 */
private fun String.isLoopback(): Boolean =
    this == "localhost" ||
        this == "::1" ||
        this == "[::1]" ||
        this == "127.0.0.1" ||
        this.startsWith("127.")
