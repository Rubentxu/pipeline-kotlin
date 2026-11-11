package dev.rubentxu.pipeline.v2.release

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import java.io.IOException
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
    private val client: HttpClient = defaultClient(),
) : MavenRepositoryReader {

    override fun read(path: String): MavenServedFile {
        val request = try {
            requestFor(path)
        } catch (e: IllegalArgumentException) {
            return MavenServedFile.Unreadable(
                "the address of $path is not an address: ${e.message ?: e::class.java.simpleName}",
            )
        }

        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: IOException) {
            return MavenServedFile.Unreadable(
                "the repository did not answer for $path: ${e.message ?: e::class.java.simpleName}",
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return MavenServedFile.Unreadable("the request for $path was interrupted")
        }

        return when (response.statusCode()) {
            200 -> MavenServedFile.Present(Sha256.ofBytes(response.body()))
            404 -> MavenServedFile.Absent
            else -> MavenServedFile.Unreadable(
                "the repository answered ${response.statusCode()} for $path",
            )
        }
    }

    /** The request that asks the repository for [path]. */
    private fun requestFor(path: String): HttpRequest {
        val builder = HttpRequest
            .newBuilder(URI.create("${baseUrl.trimEnd('/')}/${path.trimStart('/')}"))
            .timeout(timeout)
            .GET()
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

    companion object {

        /** How long a request may take before the answer is not an answer. */
        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** The client every reader starts from. */
        private fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}
