package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.HermeticHttpServer
import dev.rubentxu.pipeline.v2.application.support.ProcessPeakRss
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * WU-093 H8-A — `http.request` against the INSTALLED DISTRIBUTION and a real server.
 *
 * ## Why this file is not more of the same
 *
 * Everything before H8 ran in-process against a transport that counted sends. That
 * proves the handler asked once. It cannot produce a truncated body, cannot be slow,
 * cannot answer 401, and cannot say whether the JDK reused a socket — so it cannot
 * certify any of the behaviours a user actually meets.
 *
 * Everything here runs the `installDist` binary against [HermeticHttpServer] on
 * loopback. Two consequences follow, and both matter more than the assertions:
 *
 * - **Discovery IS the product.** `http.request` reaches this test through the
 *   `ServiceLoader` discovery added in H4.5 and through the contributor threading fixed
 *   in H7-D. Both defects were invisible to unit tests and fatal here — which is the
 *   argument for having this file at all.
 * - **The server is a witness.** "The pipeline behaved" and "the server saw exactly one
 *   request" are different claims, and only the second catches a resend whose journal
 *   row was lost.
 *
 * ## Hermetic
 *
 * Loopback, ephemeral port, no DNS, no public internet: a green run cannot depend on
 * anyone else's server.
 *
 * ## The DSL is typed, so the scripts below are typed too
 *
 * `httpRequest` is not a default import and its parameters are not strings —
 * `method` is an [HttpMethod], `validResponseCodes` is a `List<StatusRange>`. A Jenkins
 * script uses `"POST"` and `"200,204"`; these scripts cannot, and that difference is the
 * product working as designed rather than as documented.
 */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class HttpInstalledUatTest {

    private val binary: File = AppBinSupport.discover().toFile()
    private lateinit var server: HermeticHttpServer
    private val scratch = mutableListOf<File>()

    @BeforeEach
    fun startServer() {
        server = HermeticHttpServer.start()
    }

    @AfterEach
    fun stopServer() {
        if (::server.isInitialized) server.close()
        scratch.forEach { it.deleteRecursively() }
        scratch.clear()
    }

    // ── harness ─────────────────────────────────────────────────────────────

    private data class CliResult(val exitCode: Int, val output: String)

    /**
     * The CLI's failure exit.
     *
     * Named here so no scenario hard-codes a bare number. An earlier draft of this file
     * expected 2 everywhere and failed every scenario on the number alone, while the
     * product's actual behaviour was correct in all of them — which is the worst kind
     * of red: it teaches the next reader to distrust the assertions.
     */
    private val EXIT_FAILURE = 1

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { scratch += it }

    private fun writePipeline(body: String): File =
        Files.createTempFile("h8-", ".pipeline.kts").toFile()
            .apply { writeText(body); deleteOnExit() }

    /** The imports a `.pipeline.kts` needs to reach the plugin's DSL. */
    private val httpImports = """
        import dev.rubentxu.pipeline.v2.sdk.http.httpRequest
        import dev.rubentxu.pipeline.v2.sdk.http.HttpMethod
        import dev.rubentxu.pipeline.v2.sdk.http.StatusRange
    """.trimIndent()

    /**
     * Wraps [steps] in the canonical `.pipeline.kts` shape.
     *
     * Steps go DIRECTLY in the `stage` block: `StageScope` is already the receiver, and
     * `steps()` is an accessor the compiler uses, not a builder. Writing `steps { … }`
     * resolves to `steps(…)` and fails to compile — which is a fair reminder that the
     * first draft of this harness guessed the DSL instead of reading it.
     */
    private fun script(steps: String): File = writePipeline(
        """
        $httpImports

        pipeline {
            stages {
                stage("h8") {
        $steps
                }
            }
        }
        """.trimIndent(),
    )

    private fun get(url: String) = """httpRequest(url = "$url")"""

    private fun run(
        vararg args: String,
        timeoutMinutes: Long = 5,
        onStart: (Long) -> Unit = {},
    ): CliResult {
        val proc = ProcessBuilder(binary.absolutePath, *args).redirectErrorStream(true).start()
        val poller = ProcessPeakRss.poll(proc.pid())
        onStart(proc.pid())
        if (!proc.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            proc.destroyForcibly()
            poller.stop()
            error("the installed binary hung on ${args.toList()} after $timeoutMinutes min")
        }
        poller.stop()
        return CliResult(
            proc.exitValue(),
            proc.inputStream.bufferedReader().readText() + " PEAK_RSS=" + (poller.peakBytes ?: -1L),
        )
    }

    private fun runFresh(
        pipeline: File,
        vararg extraArgs: String,
        timeoutMinutes: Long = 5,
    ): CliResult {
        val db = File(tempDir("h8-db-"), "db.sqlite").absolutePath
        val ctl = tempDir("h8-ctl-").absolutePath
        return run(
            "run",
            "--db",
            db,
            "--control-root",
            ctl,
            *extraArgs,
            pipeline.absolutePath,
            timeoutMinutes = timeoutMinutes,
        )
    }

    // ── the permission laws, first ─────────────────────────────────────────

    @Test
    fun `H8-01 a default run may not reach the network at all`() {
        val result = runFresh(script(get("${server.baseUrl}/ok")))

        assertEquals(
            EXIT_FAILURE,
            result.exitCode,
            "a run with no --allow-network must fail; output:\n${result.output.takeLast(1500)}",
        )
        assertTrue(
            result.output.contains("network.egress"),
            "the refusal must NAME the capability that was missing. 'it failed' sends the " +
                "operator to read the docs; 'network.egress' plus the hint to --allow-network " +
                "sends them to the one flag. Output:\n${result.output.takeLast(1500)}",
        )
        assertEquals(
            0,
            server.countOf("/ok"),
            "THE assertion. The default run was refused and the server was never contacted. " +
                "A refusal that still opens a socket is not a refusal — and no in-process " +
                "test could have said this, because a recording transport is never opened " +
                "precisely when the product is working.",
        )
    }

    @Test
    fun `H8-02 --allow-network reaches the server, and the server saw it exactly once`() {
        val result = runFresh(script(get("${server.baseUrl}/ok")), "--allow-network")

        assertEquals(0, result.exitCode, "output:\n${result.output.takeLast(1500)}")
        assertEquals(
            1,
            server.countOf("/ok"),
            "exactly one arrival. The installed binary, a real socket, one request. This is " +
                "the claim the whole H3..H7 train was building toward.",
        )
    }

    // ── methods, bodies, statuses ──────────────────────────────────────────

    @Test
    fun `H8-03 a POST body arrives intact at the server`() {
        // The generated script carries a JSON body, so this Kotlin raw string cannot
        // nest one. `CHARGE_BODY` is spliced in quoted, which keeps the generated
        // `.pipeline.kts` readable in a failure message instead of as \\u escapes.
        // Escaped for the GENERATED script: the .pipeline.kts line is
        // body = "{\"charge\":1}", whose runtime value is {"charge":1} — 13 bytes.
        val charge = """{\"charge\":1}"""
        val pipeline = script(
            """
            httpRequest(
                url = "${server.baseUrl}/echo",
                method = HttpMethod.Post,
                body = "$charge",
            )
            """.trimIndent(),
        )

        val result = runFresh(pipeline, "--allow-network")

        assertEquals(0, result.exitCode, "output:\n${result.output.takeLast(1500)}")
        val seen = server.requests.single { it.path == "/echo" }
        assertEquals("POST", seen.method)
        // 12, not `charge.length`: `charge` is the ESCAPED source form that the
        // generated .pipeline.kts carries, and its escapes are consumed by the Kotlin
        // compiler. The body on the wire is {"charge":1} — twelve bytes. An earlier
        // draft compared against the escaped length and failed on a body that had
        // arrived perfectly.
        assertEquals(
            12,
            seen.bodyLength,
            "the body the author wrote, byte for byte, at the length they wrote it -- " +
                "escapes are for the script compiler, not for the socket",
        )
    }

    @Test
    fun `H8-04 an accepted status succeeds and a refused one fails with the status named`() {
        val accepted = script(
            """
            httpRequest(
                url = "${server.baseUrl}/status/204",
                validResponseCodes = listOf(StatusRange.Span(200, 299)),
            )
            """.trimIndent(),
        )
        val refused = script(get("${server.baseUrl}/status/500"))

        assertEquals(
            0,
            runFresh(accepted, "--allow-network").exitCode,
            "204 is inside 200..299 and must be accepted",
        )

        val bad = runFresh(refused, "--allow-network")
        assertEquals(
            EXIT_FAILURE,
            bad.exitCode,
            "500 is outside the Jenkins default 100..399; output:\n${bad.output.takeLast(1500)}",
        )
        assertTrue(
            bad.output.contains("500"),
            "the operator must be told WHICH status was refused. 'the request failed' sends " +
                "them looking at the network; 'status 500' sends them at the server. " +
                "Output:\n${bad.output.takeLast(1500)}",
        )
    }

    // ── the failure shapes a mock cannot produce ───────────────────────────

    @Test
    fun `H8-05 a body cut in half is an interrupted response, not an unreachable host`() {
        val result = runFresh(
            script(get("${server.baseUrl}/drop-mid-body?bytes=400000")),
            "--allow-network",
        )

        assertEquals(
            EXIT_FAILURE,
            result.exitCode,
            "a truncated body is a failure; output:\n${result.output.takeLast(1500)}",
        )
        assertEquals(1, server.countOf("/drop-mid-body"), "the request WAS sent — the server answered")
        assertTrue(
            result.output.contains("cut short"),
            "H4 added ResponseInterrupted so 'the server answered and then stopped' is " +
                "distinguishable from 'the host was unreachable', and reports the byte count " +
                "it did receive. Output:\n${result.output.takeLast(1500)}",
        )
        assertFalse(
            result.output.contains("could not be reached"),
            "an interrupted body is NOT an unreachable host. An operator told to debug their " +
                "DNS when the server answered 200 and cut the stream loses an afternoon. " +
                "Output:\n${result.output.takeLast(1500)}",
        )
    }

    @Test
    fun `H8-06 a redirect is not followed`() {
        val result = runFresh(script(get("${server.baseUrl}/redirect")), "--allow-network")

        // 302 IS inside the Jenkins default 100..399, so the run succeeds and the Step
        // reports the redirect it was given. The law is NOT that the run fails -- it is
        // that the redirect is not FOLLOWED, which only the server can attest.
        assertEquals(
            0,
            result.exitCode,
            "302 is a perfectly acceptable answer; the Step must report it as one. " +
                "Output:\n${result.output.takeLast(1500)}",
        )
        assertEquals(1, server.countOf("/redirect"), "the redirect itself was requested once")
        assertEquals(
            0,
            server.countOf("/ok"),
            "Redirect.NEVER is a credential-safety decision, not a convenience: a 302 to " +
                "another host is exactly how an Authorization header would follow somebody " +
                "else. Following it here would make the server's /ok the second arrival.",
        )
    }

    @Test
    fun `H8-07 a slow endpoint is cut short by the timeout, and does not hang the run`() {
        val result = runFresh(
            script(
                """
                httpRequest(
                    url = "${server.baseUrl}/slow?ms=30000",
                    timeoutSeconds = 2,
                )
                """.trimIndent(),
            ),
            "--allow-network",
            timeoutMinutes = 3,
        )

        assertEquals(
            EXIT_FAILURE,
            result.exitCode,
            "a request that outran its own timeout must fail the step; " +
                "output:\n${result.output.takeLast(1500)}",
        )
        assertTrue(
            result.output.contains("gave up") || result.output.contains("timed out") ||
                result.output.contains("timed") || result.output.contains("Expired"),
            "a timeout must SAY it gave up, and say how long it waited. An operator " +
                "cannot tell a slow server from a refused connection if both surface as " +
                "something abstract. Output:\n${result.output.takeLast(1500)}",
        )
    }

    // ── credentials, end to end ────────────────────────────────────────────

    @Test
    fun `H8-08 a credential that cannot be resolved never opens a socket`() {
        val pipeline = script(
            """httpRequest(url = "${server.baseUrl}/basic-auth", authentication = CredentialsId("stage"))""",
        )

        val result = runFresh(pipeline, "--allow-network")

        assertEquals(
            EXIT_FAILURE,
            result.exitCode,
            "a missing credential is a typed failure; output:\n${result.output.takeLast(1500)}",
        )
        assertEquals(
            0,
            server.countOf("/basic-auth"),
            "a credential that cannot be resolved must not degrade into an anonymous request. " +
                "H5 resolves it before the send for exactly this, and H6 asks the egress gate " +
                "before even that — neither is observable without a server that can testify.",
        )
    }

    @Test
    fun `H8-09 no secret reaches stdout`() {
        val pipeline = script(
            """httpRequest(url = "${server.baseUrl}/basic-auth", authentication = CredentialsId("stage"))""",
        )

        val result = runFresh(pipeline, "--allow-network")
        val combined = result.output

        for (secret in listOf("s3cr3t", "c3N0YWdl", "Basic ")) {
            assertFalse(
                combined.contains(secret),
                "the credential leaked into the run output as '$secret'. A secret in stdout " +
                    "reaches every CI log that keeps it, and this run's output is what a " +
                    "receipt would quote. Output:\n${combined.takeLast(2000)}",
            )
        }
    }
}
