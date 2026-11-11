package dev.rubentxu.pipeline.v2.release

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * Whether a repository that answers over HTTP serves what the publication
 * manifest declares.
 *
 * The seam here is real: the reader talks HTTP to a server that is really
 * there, and the bytes it measures are the bytes that crossed the socket.
 * What is faked is the world OUTSIDE the reader, which is the registry: the
 * server answers with what the test puts in it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class MavenResolutionAdmissionTest {

    private lateinit var server: HttpServer
    private var baseUrl: String = ""
    private val bodies = mutableMapOf<String, ByteArray>()
    private val statuses = mutableMapOf<String, Int>()
    private val challenged = mutableSetOf<String>()
    private val requested = mutableListOf<String>()
    private val authorizationSeen = mutableListOf<String>()
    private var servesAnythingElse = false

    @TempDir
    lateinit var directory: Path

    private val pomBytes = "the pom of the contract".toByteArray()
    private val jarBytes = "the jar of the classes".toByteArray()

    private val pom = MavenArtifactDigest(
        path = "dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.pom",
        kind = MavenArtifactKind.POM,
        sha256 = Sha256.ofBytes(pomBytes),
    )
    private val jar = MavenArtifactDigest(
        path = "dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/pipelinek-domain-0.96.0.jar",
        kind = MavenArtifactKind.JAR,
        sha256 = Sha256.ofBytes(jarBytes),
    )

    @BeforeEach
    fun startTheRepository() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> answer(exchange) }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stopTheRepository() {
        server.stop(0)
    }

    @Test
    fun `a repository that serves the declared bytes is verified`() {
        serveTheDeclaredBytes()
        val outcome = admit()
        assertInstanceOf(MavenResolutionOutcome.Verified::class.java, outcome)
    }

    @Test
    fun `an artifact served with other bytes is refused by name`() {
        serveTheDeclaredBytes()
        bodies[jar.path] = "the jar of other classes".toByteArray()

        val outcome = assertInstanceOf(
            MavenResolutionOutcome.Rejected::class.java,
            admit(),
        )
        assertTrue(outcome.summary.contains("ARTIFACT_DISAGREES"), outcome.summary)
        assertTrue(outcome.summary.contains(jar.path), outcome.summary)
    }

    @Test
    fun `an artifact the repository does not serve is refused by name`() {
        serveTheDeclaredBytes()
        bodies.remove(jar.path)

        val outcome = assertInstanceOf(
            MavenResolutionOutcome.Rejected::class.java,
            admit(),
        )
        assertTrue(outcome.summary.contains("ARTIFACT_ABSENT"), outcome.summary)
        assertTrue(outcome.summary.contains(jar.path), outcome.summary)
    }

    @Test
    fun `a repository that serves none of the version says the version is absent`() {
        val outcome = assertInstanceOf(
            MavenResolutionOutcome.Rejected::class.java,
            admit(),
        )
        assertTrue(outcome.summary.contains("VERSION_ABSENT"), outcome.summary)
    }

    @Test
    fun `a repository that answers something else is not measured`() {
        serveTheDeclaredBytes()
        statuses[jar.path] = 500

        val outcome = assertInstanceOf(
            MavenResolutionOutcome.Refused::class.java,
            admit(),
        )
        assertTrue(outcome.reason.contains(jar.path), outcome.reason)
    }

    @Test
    fun `a repository that answers with a redirect has not been measured`() {
        serveTheDeclaredBytes()
        statuses[jar.path] = 302

        val outcome = assertInstanceOf(
            MavenResolutionOutcome.Refused::class.java,
            admit(),
        )
        assertTrue(outcome.reason.contains(jar.path), outcome.reason)
        assertTrue(outcome.reason.contains("302"), outcome.reason)
    }

    @Test
    fun `a manifest nobody can read is not a verdict`() {
        val outcome = MavenResolutionAdmission.admit(
            Path.of("/nonexistent/maven-publication-manifest.json"),
            HttpMavenRepositoryReader(baseUrl),
        )
        assertInstanceOf(MavenResolutionOutcome.Refused::class.java, outcome)
    }

    @Test
    fun `a repository that demands a credential is not measured without one`() {
        serveTheDeclaredBytes()
        challenged += pom.path
        challenged += jar.path

        val outcome = assertInstanceOf(
            MavenResolutionOutcome.Refused::class.java,
            admit(),
        )
        assertTrue(outcome.reason.contains(pom.path), outcome.reason)
    }

    @Test
    fun `the credential reaches the repository that demanded it`() {
        serveTheDeclaredBytes()
        challenged += pom.path
        challenged += jar.path

        assertInstanceOf(
            MavenResolutionOutcome.Verified::class.java,
            admit(user = "the-user", token = "the-token"),
        )
        assertTrue(authorizationSeen.contains(pom.path), "the credential must reach the repository that asked for it")
    }

    @Test
    fun `a credential is not sent to a repository that does not demand it`() {
        serveTheDeclaredBytes()

        assertInstanceOf(
            MavenResolutionOutcome.Verified::class.java,
            admit(user = "the-user", token = "the-token"),
        )
        assertTrue(
            authorizationSeen.isEmpty(),
            "the credential must not reach a repository that never asked for it: $authorizationSeen",
        )
    }

    @Test
    fun `the digest is measured and not taken from the repository's own checksum`() {
        serveTheDeclaredBytes()
        bodies[jar.path] = "the jar of other classes".toByteArray()
        // The repository's own claim about the file it holds, right beside it.
        bodies["${jar.path}.sha1"] = jar.sha256.toByteArray()

        val outcome = assertInstanceOf(
            MavenResolutionOutcome.Rejected::class.java,
            admit(),
        )
        assertTrue(outcome.summary.contains("ARTIFACT_DISAGREES"), outcome.summary)
        assertTrue(
            !requested.contains("${jar.path}.sha1"),
            "the reader must not ask the repository what it thinks of itself: $requested",
        )
    }

    @Test
    fun `a repository that serves more than it declares does not change the verdict`() {
        serveTheDeclaredBytes()
        servesAnythingElse = true

        val outcome = assertInstanceOf(
            MavenResolutionOutcome.Verified::class.java,
            admit(),
        )
        assertInstanceOf(MavenResolutionOutcome.Verified::class.java, outcome)
    }

    @Test
    fun `a repository that is not reached over TLS is refused`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            HttpMavenRepositoryReader("http://maven.example.com/repository")
        }
        assertTrue(refusal.message?.contains("TLS") == true, refusal.message)
    }

    @Test
    fun `an address that carries a credential is refused`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            HttpMavenRepositoryReader("https://the-user:the-token@maven.example.com/repository")
        }
        assertTrue(refusal.message?.contains("credential") == true, refusal.message)
    }

    @Test
    fun `a credential that is half given is refused`() {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            HttpMavenRepositoryReader(baseUrl, user = "the-user")
        }
        assertTrue(refusal.message?.contains("incomplete") == true, refusal.message)
    }

    @Test
    fun `a path that carries an escape is not asked for`() {
        serveTheDeclaredBytes()
        val escaped = jar.copy(
            path = "dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/..%2f..%2fother/" +
                "pipelinek-domain-0.96.0.jar",
        )

        assertInstanceOf(
            MavenResolutionOutcome.Refused::class.java,
            MavenResolutionAdmission.admit(
                manifestPath(listOf(pom, escaped)),
                HttpMavenRepositoryReader(baseUrl),
            ),
        )
        assertTrue(!requested.contains(escaped.path), "the escaped path must not be asked: $requested")
    }

    @Test
    fun `a manifest with more artifacts than a release has is not asked for`() {
        val many = (1..600).map { index ->
            MavenArtifactDigest(
                path = "dev/rubentxu/pipelinek/pipelinek-domain/0.96.0/file-$index.jar",
                kind = MavenArtifactKind.JAR,
                sha256 = "a".repeat(64),
            )
        }

        val outcome = assertInstanceOf(
            MavenResolutionOutcome.Refused::class.java,
            MavenResolutionAdmission.admit(
                manifestPath(many),
                HttpMavenRepositoryReader(baseUrl),
            ),
        )
        assertTrue(outcome.reason.contains("600"), outcome.reason)
        assertTrue(requested.isEmpty(), "nothing must be asked of a manifest that is not a release")
    }

    /** Put the declared bytes in the repository, under the paths of the manifest. */
    private fun serveTheDeclaredBytes() {
        bodies[pom.path] = pomBytes
        bodies[jar.path] = jarBytes
    }

    /** The manifest of the artifacts, written where the admission reads it. */
    private fun manifestPath(
        artifacts: List<MavenArtifactDigest> = listOf(pom, jar),
    ): Path {
        val manifest = MavenPublicationManifest(
            candidateId = CandidateId.fromDigest("a".repeat(64)),
            productVersion = ProductVersion.parseOrThrow("0.96.0"),
            sdkZip = MavenFileDigest("pipelinek-0.96.0.sdk.zip", "e".repeat(64)),
            artifacts = artifacts,
            bundleSha256 = "d".repeat(64),
        )
        val path = directory.resolve("maven-publication-manifest.json")
        Files.writeString(path, MavenPublicationCodec.encode(manifest))
        return path
    }

    /** The outcome of the check against the repository that is running. */
    private fun admit(user: String? = null, token: String? = null): MavenResolutionOutcome =
        MavenResolutionAdmission.admit(
            manifestPath(),
            HttpMavenRepositoryReader(baseUrl, user = user, token = token),
        )

    /** Answer with what the test put in the repository, or with the status it declares. */
    private fun answer(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix("/")
        requested += path
        val authorization = exchange.requestHeaders.getFirst("Authorization")
        if (authorization != null) authorizationSeen += path

        if (challenged.contains(path) && authorization == null) {
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
            return
        }

        val declared = statuses[path]
        val body = bodies[path]
        if (declared != null) {
            exchange.sendResponseHeaders(declared, -1)
            exchange.close()
            return
        }
        if (body == null && !servesAnythingElse) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        val served = body ?: "the registry's own file beside the artifact".toByteArray()
        exchange.sendResponseHeaders(200, served.size.toLong())
        exchange.responseBody.use { it.write(served) }
    }
}
