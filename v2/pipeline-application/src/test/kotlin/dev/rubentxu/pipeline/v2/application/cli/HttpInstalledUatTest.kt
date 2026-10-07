package dev.rubentxu.pipeline.v2.application.cli

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.application.support.HermeticHttpServer
import dev.rubentxu.pipeline.v2.application.support.ProcessPeakRss
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Credential
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
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

    private data class CliResult(
        val exitCode: Int,
        val output: String,
        val peakRssBytes: Long?,
        /**
         * The run's durable journal, when the scenario went through [runFresh].
         *
         * Carried so a redaction scenario can read the bytes the run actually
         * persisted. Asserting a secret is absent from stdout is half the claim;
         * the other half is that it is absent from what a later run would replay
         * from, and that file is not reachable from a scenario that does not know
         * its path.
         */
        val journal: File? = null,
    )

    /**
     * The CLI's failure exit.
     *
     * Named here so no scenario hard-codes a bare number. An earlier draft of this file
     * expected 2 everywhere and failed every scenario on the number alone, while the
     * product's actual behaviour was correct in all of them — which is the worst kind
     * of red: it teaches the next reader to distrust the assertions.
     */
    private val exitFailure = 1

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
        env: Map<String, String> = emptyMap(),
        onStart: (Long) -> Unit = {},
    ): CliResult {
        val builder = ProcessBuilder(binary.absolutePath, *args).redirectErrorStream(true)
        // `environment()` ADDS to the inherited environment rather than replacing it, so
        // a scenario that passes one variable does not silently strip PATH from the
        // launcher it is trying to exercise.
        builder.environment().putAll(env)
        val proc = builder.start()
        val poller = ProcessPeakRss.poll(proc.pid())
        onStart(proc.pid())
        if (!proc.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            proc.destroyForcibly()
            poller.stop()
            error("the installed binary hung on ${args.toList()} after $timeoutMinutes min")
        }
        poller.stop()
        return CliResult(
            exitCode = proc.exitValue(),
            output = proc.inputStream.bufferedReader().readText() +
                " PEAK_RSS=" + (poller.peakBytes ?: -1L),
            // Carried on the result rather than parsed back out of the text, so a
            // memory scenario that forgets to assert on it still prints it in the
            // failure. Re-collecting evidence for a regression is a tax nobody pays
            // twice.
            peakRssBytes = poller.peakBytes,
        )
    }

    private fun runFresh(
        pipeline: File,
        vararg extraArgs: String,
        timeoutMinutes: Long = 5,
        env: Map<String, String> = emptyMap(),
        onStart: (Long) -> Unit = {},
    ): CliResult {
        val db = File(tempDir("h8-db-"), "db.sqlite")
        val ctl = tempDir("h8-ctl-").absolutePath
        return run(
            "run",
            "--db",
            db.absolutePath,
            "--control-root",
            ctl,
            *extraArgs,
            pipeline.absolutePath,
            timeoutMinutes = timeoutMinutes,
            env = env,
            onStart = onStart,
        ).let { it.copy(journal = db.takeIf { file -> file.isFile }) }
    }

    /**
     * The command line of [rootPid] and its descendants, joined, polled until [needle] appears.
     *
     * The pid a `ProcessBuilder` hands back is the launcher's `/bin/sh`, NOT the JVM: the start
     * script runs `java` as a child rather than `exec`-ing it, so the budget this suite declares
     * is invisible on the root pid's own cmdline. Reading only the root is how a row ends up
     * certifying a flag that never reached a JVM.
     *
     * The deadline bounds a POLL, and is not an assertion about duration — nothing here judges
     * how long anything took.
     */
    private fun cmdlineTree(rootPid: Long, needle: String, deadlineMillis: Long = 20_000): String {
        val deadline = System.nanoTime() + deadlineMillis * 1_000_000
        var seen = ""
        while (System.nanoTime() < deadline) {
            seen = ProcessHandle.of(rootPid).map { root ->
                (listOf(root) + root.descendants().toList())
                    .mapNotNull { handle ->
                        runCatching {
                            Files.readString(Paths.get("/proc/${handle.pid()}/cmdline"))
                                .replace('\u0000', ' ')
                        }.getOrNull()
                    }
                    .joinToString("  ||  ")
            }.orElse("")
            if (seen.contains(needle)) return seen
            Thread.sleep(50)
        }
        return seen
    }

    /**
     * A real `credentials.bin`, and the environment the CLI reads it from.
     *
     * Written through [LocalSecretStore] rather than by hand, because a fixture that
     * does not go through the product's own store is a fixture that would still pass
     * if the store format changed under it. The passphrase is returned rather than
     * kept in a field so that no scenario can reach it by accident — a secret held by
     * the harness is a secret the harness can print in a failure message.
     */
    private fun credentialStoreEnv(
        id: String,
        username: String,
        password: String,
    ): Map<String, String> = credentialStoreEnv(
        UsernamePassword(
            id = CredentialsId(id),
            username = username,
            password = password.toByteArray(StandardCharsets.UTF_8),
        ),
    )

    /**
     * The same environment, built around an ARBITRARY [Credential].
     *
     * The username/password overload cannot express the wrong-kind case, and the
     * wrong-kind case is one of the two halves that make a credential test worth
     * running: a store that only ever holds a supported kind proves nothing about
     * what happens when it holds an unsupported one.
     */
    private fun credentialStoreEnv(credential: Credential): Map<String, String> {
        val storePath = tempDir("h8-store-").toPath().resolve("credentials.bin")
        val passphrase = "h8-${credential.id.value}-passphrase"
        LocalSecretStore(storePath, passphrase.toCharArray()).use { store ->
            store.add(credential.id, credential)
        }
        // The passphrase is read from the String, never from a CharArray the store
        // still holds. `LocalSecretStore.close()` zeroes that array in place — a
        // deliberate hardening — so a CharArray captured before the `use` block
        // hands ProcessBuilder nineteen NUL characters, and the failure surfaces as
        // "Invalid environment variable value" pointing at the harness rather than
        // at the thing that erased it.
        return mapOf(
            "PIPELINE_CREDENTIALS_STORE" to storePath.toString(),
            "PIPELINE_STORE_PASSPHRASE" to passphrase,
        )
    }


    // ── the permission laws, first ─────────────────────────────────────────

    @Test
    fun `H8-01 a default run may not reach the network at all`() {
        val result = runFresh(script(get("${server.baseUrl}/ok")))

        assertEquals(
            exitFailure,
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
            exitFailure,
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
            exitFailure,
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
            exitFailure,
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
            exitFailure,
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

    /**
     * A secret that is actually sent must not be observable anywhere afterwards.
     *
     * ## The defect this replaces
     *
     * This scenario used to run with NO credential store at all and then assert that
     * `"s3cr3t"` was absent from the output. That was a green that could not fail:
     * with no store the credential never resolved, the header was never built, and
     * the literal had no path to the console in the first place. It would have kept
     * passing after someone added a debug log of the Authorization header, which is
     * precisely the regression it was supposed to catch.
     *
     * A redaction canary is only evidence when the secret is LIVE. So the store is
     * real here, H8-14's success is asserted first to prove the secret really was
     * resolved and sent, and only then are the observable surfaces swept.
     *
     * ## Why the journal is swept too
     *
     * stdout is what a CI log keeps. The journal is what the next run REPLAYS FROM,
     * so a secret there outlives the run that leaked it and is copied into every
     * subsequent attempt. It is read as raw bytes rather than queried, because the
     * claim is about bytes on disk and not about which column happens to hold them.
     */
    @Test
    fun `H8-09 no secret reaches stdout or the journal`() {
        val env = credentialStoreEnv("stage", "stage", "s3cr3t")
        val pipeline = script(
            """httpRequest(url = "${server.baseUrl}/basic-auth", authentication = CredentialsId("stage"))""",
        )

        val result = runFresh(pipeline, "--allow-network", env = env)

        // The precondition. Without it the assertions below are vacuous, and a
        // vacuous redaction test is worse than none: it is a green that lies.
        assertEquals(
            0,
            result.exitCode,
            "this scenario is only meaningful if the credential really resolved and the " +
                "request really carried it. If the run failed, the secret was never sent " +
                "and 'no secret in the output' proves nothing. Output:\n${result.output.takeLast(1500)}",
        )
        assertTrue(
            server.requests.any { it.path == "/basic-auth" && it.carriedAuthorization },
            "the server must have seen an Authorization header, or the secret was never " +
                "in play and the redaction assertions below are theatre",
        )

        val combined = result.output
        for (secret in listOf("s3cr3t", "c3N0YWdl", "Basic ")) {
            assertFalse(
                combined.contains(secret),
                "the credential leaked into the run output as '$secret'. A secret in stdout " +
                    "reaches every CI log that keeps it, and this run's output is what a " +
                    "receipt would quote. Output:\n${combined.takeLast(2000)}",
            )
        }

        val journal = result.journal
            ?: error("the run wrote no journal, so 'no secret in the journal' cannot be asserted")
        val journalBytes = journal.readBytes()
        for (secret in listOf("s3cr3t", "c3N0YWdl")) {
            assertFalse(
                journalBytes.toString(StandardCharsets.ISO_8859_1).contains(secret),
                "the credential was persisted into the run journal as '$secret'. The journal " +
                    "is what a later attempt replays FROM, so a secret here outlives the run " +
                    "that leaked it. Journal: $journal",
            )
        }
    }

    // ── the property H4 was written for ────────────────────────────────────

    @Test
    fun `H8-10 a response twice the heap budget completes, so the body is not materialised`() {
        // H4's claim, restated so that it can actually FAIL:
        //
        //   the subscriber bounds the body BEFORE holding it, rather than holding it and
        //   then truncating what it already has
        //
        // The previous shape of this row measured a RATIO of peak-RSS growth to body growth
        // and cut it at 0.5. That is a size assertion, which Harness Fidelity 3 forbids,
        // and it failed on a loaded machine at 0.65 having passed at 0.19 — it was
        // measuring the machine rather than `http.request`. Raising the threshold would
        // only move the machine's noise band upward, so the ratio is removed rather than
        // retuned: a number that changes with the load has no verdict to give.
        //
        // What replaces it is a DECLARED budget plus a discrete outcome:
        //
        //   budget = 256 MiB of heap for the WHOLE process, imposed by the launcher
        //   body   = 512 MiB, which does not fit in that budget even once
        //
        // The claim is then no longer "memory grew slowly" but "a body twice its own heap
        // budget still completes". A subscriber that materialised the body would have to
        // hold 512 MiB in a 256 MiB heap and would die with OutOfMemoryError; the bounded
        // subscriber keeps a 1 MiB prefix and digests the rest, so it cannot and does not.
        //
        // Both observations are discrete: an exit code and a request count. Nothing here
        // reads a duration, a ratio or a magnitude, so the row cannot be moved by load.
        // Peak RSS is still collected and printed because it is worth knowing, but it is
        // characterisation and no longer load-bearing.
        //
        // The 256 MiB budget was measured, not guessed: the installed binary compiles and
        // runs a plain pipeline under `-Xmx256m` (it still fits at 128m), so this cap
        // constrains the HTTP subscriber rather than starving the script compiler it shares
        // the JVM with.
        val budgetBytes = 256L * 1024 * 1024
        val budget = "-Xmx256m"
        val bodyBytes = 2 * budgetBytes
        // The guard is the row's own honesty check. It failed the FIRST time it ran because
        // it compared `bodyBytes > 2 * 256 MiB` — that is 512 MiB > 512 MiB, which is false —
        // so the separation this row claims in prose was not the separation the code checked.
        // Stating the budget once, in bytes, and deriving the body from it is what keeps the
        // comment and the assertion from drifting apart again.
        assertTrue(
            bodyBytes >= 2 * budgetBytes,
            "the body must be at least twice the heap budget, or the world this row exists to " +
                "exclude — a subscriber holding the body whole — would simply fit. " +
                "bodyBytes=$bodyBytes budgetBytes=$budgetBytes",
        )

        val pipeline = script(get("${server.baseUrl}/large?bytes=$bodyBytes"))
        var observedCmdline: String = ""
        val result = runFresh(
            pipeline,
            "--allow-network",
            timeoutMinutes = 5,
            // PIPELINEK_OPTS, and NOT DEFAULT_JVM_OPTS. The launcher assigns
            // `DEFAULT_JVM_OPTS=""` in its own body, so an inherited value of that name is
            // overwritten before it is ever read: it is decoration. This row's first version
            // set it, measured nothing, and would have certified a budget the process never
            // had. `PIPELINEK_OPTS` is the product-owned variable the launcher appends, and
            // `JAVA_OPTS` is honoured too; both were checked against an 8m cap, which fails
            // with a real OutOfMemoryError where the other runs fine.
            env = mapOf("PIPELINEK_OPTS" to budget),
            onStart = { pid -> observedCmdline = cmdlineTree(pid, budget) },
        )

        // The budget must be OBSERVED on a real JVM's command line. Without this the row
        // asserts "it completes within 256 MiB" while running under whatever ceiling the
        // machine happens to impose, which is the decorative-configuration failure mode this
        // block exists to remove, reintroduced one level up.
        assertTrue(
            observedCmdline.isNotEmpty(),
            "no process in the child's tree exposed a command line; the budget cannot be " +
                "confirmed as applied, so the assertion below would be vacuous",
        )
        assertTrue(
            observedCmdline.contains(budget),
            "the child must actually run under the budget this row claims; $budget did not appear " +
                "in any command line of its process tree. A budget that is set but not applied " +
                "makes every assertion below vacuous. Observed: $observedCmdline",
        )

        // The observation, and it is ONE value rather than a comparison.
        assertEquals(
            0,
            result.exitCode,
            "a $bodyBytes-byte response must complete inside a $budget heap budget. A subscriber " +
                "that materialised the body could not: it would need $bodyBytes bytes in a " +
                "$budgetBytes-byte heap. Output:\n" + result.output.takeLast(1500),
        )
        // The other green, closed explicitly: an exit 0 that never made the request.
        assertEquals(
            1,
            server.countOf("/large"),
            "the request WAS sent and the server answered; an exit 0 with no request behind it " +
                "would prove nothing about the subscriber",
        )

        // Characterisation, deliberately not an assertion. Peak RSS legitimately EXCEEDS the
        // heap budget — it includes metaspace, code cache and direct buffers — which is
        // exactly why an RSS ceiling would have measured the launcher instead of the
        // subscriber.
        println(
            "H8-10 characterisation (not asserted): budget=$budget bodyBytes=$bodyBytes " +
                "peakRssBytes=${result.peakRssBytes}",
        )
    }

    @Test
    fun `H8-11 sequential requests reuse one connection`() {
        // The client cannot see this: a reused keep-alive socket and a fresh one look
        // identical to any assertion that counts requests. The server can, because the
        // client port is the socket identity.
        val pipeline = script(
            listOf("/ok", "/ok", "/ok").joinToString("\n") { get("${server.baseUrl}$it") },
        )

        val result = runFresh(pipeline, "--allow-network")

        assertEquals(0, result.exitCode, "output:\n${result.output.takeLast(1200)}")
        assertEquals(3, server.countOf("/ok"), "three requests must have arrived")
        assertEquals(
            1,
            server.distinctClientSockets(),
            "three sequential requests to the same host must share one socket. A new " +
                "connection per request is a new handshake and a new round trip per call, " +
                "which is the difference between a pipeline that polls a service and one " +
                "that stalls on it.",
        )
    }

    // ── the safety law, at the delivery level ──────────────────────────────

    @Test
    fun `H8-12 resuming a finished run does not send the request again`() {
        // The one scenario where being wrong costs money. `http.request` declares
        // ReplayPolicy.NEVER precisely because a POST may have taken effect even when
        // the response never came back -- so a resume must ABORT rather than resend.
        //
        // The server is the only witness that matters here. The journal can show a row;
        // only the server can show what the WORLD saw. A test asserting "one row" would
        // pass even if the second request went out and the row was never written.
        val pipeline = script(
            """
            httpRequest(
                url = "${server.baseUrl}/echo",
                method = HttpMethod.Post,
                body = "$CHARGE",
            )
            """.trimIndent(),
        )
        val db = File(tempDir("h8-resume-db-"), "db.sqlite").absolutePath
        val ctl = tempDir("h8-resume-ctl-").absolutePath

        val fresh = run("run", "--allow-network", "--db", db, "--control-root", ctl, pipeline.absolutePath)
        assertEquals(0, fresh.exitCode, "the fresh run must succeed; output:\n${fresh.output.takeLast(1200)}")
        assertEquals(1, server.countOf("/echo"), "the fresh run sends exactly once")

        val resumed = run("run", "--allow-network", "--db", db, "--control-root", ctl, "--resume", pipeline.absolutePath)

        assertEquals(
            1,
            server.countOf("/echo"),
            "THE assertion. A resume of a finished run re-sent a POST whose effect this " +
                "process cannot observe. The run must abort instead: reuse would claim a " +
                "success nobody witnessed, and a resend would do the thing twice. " +
                "Resume output:\n${resumed.output.takeLast(1200)}",
        )
        assertTrue(
            resumed.output.contains("Replay aborted"),
            "the resumed run must say it ABORTED. An engine that silently skipped a journaled " +
                "Step would look identical to one that reused it, and only the first is safe " +
                "for a POST. Output:\n${resumed.output.takeLast(1200)}",
        )
        assertEquals(
            exitFailure,
            resumed.exitCode,
            "the resumed run must not close green. Reporting success for a request this " +
                "process never sent is the failure mode MEMOIZED would have produced. " +
                "Output:\n${resumed.output.takeLast(1200)}",
        )
        // Recorded, not asserted: this is the operator-facing half of the abort, and it
        // is the open decision from the previous gate. An operator who sees only
        // "Replay aborted for '<opId>'" cannot tell whether their POST already took
        // effect. The run aborting is correct; saying WHY it must not re-send is the
        // part still missing, and this line is the evidence for that argument.
        assertTrue(
            resumed.output.contains("Replay aborted"),
            "see the note above: the abort message names the operation but not the " +
                "consequence for a non-idempotent request",
        )
    }

    @Test
    fun `H8-13 two requests in one stage both arrive`() {
        val pipeline = script(
            """
            ${get("${server.baseUrl}/echo")}
            ${get("${server.baseUrl}/echo")}
            """.trimIndent(),
        )

        val result = runFresh(pipeline, "--allow-network")

        assertEquals(0, result.exitCode, "output:\n${result.output.takeLast(1200)}")
        assertEquals(
            2,
            server.countOf("/echo"),
            "two Steps are two requests. A compiler or a registry that collapsed them would " +
                "pass every single-Step scenario in this file.",
        )
    }

    // ── the three H8-C holes ───────────────────────────────────────────────

    /**
     * The positive half of H8-08, and it is not redundant with it.
     *
     * H8-08 proves a credential that cannot be resolved never opens a socket. A
     * handler that resolved nothing AT ALL — that is, one which treats every
     * `authentication` as unresolvable — passes H8-08, passes H8-09, and is
     * useless. Only a request that actually arrives carrying the header can tell
     * "credentials work" from "credentials always fail".
     *
     * And the server is still the witness: the pipeline could log an Authorization
     * header it never sent.
     */
    @Test
    fun `H8-14 a resolvable credential authenticates, and the server saw the header`() {
        val env = credentialStoreEnv("stage", "stage", "s3cr3t")
        val pipeline = script(
            """httpRequest(url = "${server.baseUrl}/basic-auth", authentication = CredentialsId("stage"))""",
        )

        val result = runFresh(pipeline, "--allow-network", env = env)

        assertEquals(
            0,
            result.exitCode,
            "a credential that resolves must let the request through. /basic-auth answers 401 " +
                "to anything but the right pair, and 401 is inside the Jenkins default " +
                "100..399, so a run that sent no header would close GREEN here. " +
                "Output:\n${result.output.takeLast(1500)}",
        )
        val arrivals = server.requests.filter { it.path == "/basic-auth" }
        assertEquals(1, arrivals.size, "exactly one request, and it must have reached the server")
        assertTrue(
            arrivals.single().carriedAuthorization,
            "the server received no Authorization header, so a 200 here would mean the " +
                "endpoint is not the one under test. The recorder keeps a BOOLEAN rather " +
                "than the header value, on purpose: a witness that stores credentials is a " +
                "witness that eventually prints them into a failure message.",
        )
    }

    /**
     * The OTHER fail-closed half, and it is not the one H8-08 already covers.
     *
     * H8-08 runs with no credential store at all, which exercises
     * `StoreUnavailable`. This runs with a store that is present, openable and
     * correctly unlocked — and simply does not contain the requested name. That is
     * `Absent`, a different case with a different human cause (a typo in the pipeline,
     * not a missing configuration), and a plugin that collapsed the two would still
     * pass H8-08.
     *
     * The store holds a REAL, RESOLVABLE credential under a different name, so the run
     * is not refused for want of infrastructure. If it still sends nothing, the refusal
     * is about the name and nothing else.
     */
    @Test
    fun `H8-15 an unknown name in a working store is refused and sends nothing`() {
        val env = credentialStoreEnv("stage", "stage", "s3cr3t")
        val pipeline = script(
            """httpRequest(url = "${server.baseUrl}/basic-auth", authentication = CredentialsId("stge"))""",
        )

        val result = runFresh(pipeline, "--allow-network", env = env)

        assertEquals(
            exitFailure,
            result.exitCode,
            "a name the store does not hold is a typed failure; output:\n${result.output.takeLast(1500)}",
        )
        assertEquals(
            0,
            server.countOf("/basic-auth"),
            "THE assertion. The store was healthy and held a valid credential under another " +
                "name, and the request still must not be sent. Degrading an unresolvable name " +
                "into an anonymous request is the defect: the pipeline would report success " +
                "having silently dropped the authentication the author asked for.",
        )
    }

    /**
     * The wrong-kind half, and the one that justifies the narrow port.
     *
     * `BasicCredentialSource` can only ever return a username and a password. A
     * `SecretText` is a real, decryptable credential that is simply not one of those, so
     * this is a credential the run was entitled to read and still must not put on the
     * wire. A wider port — a `CredentialProvider`, say — would have to make this decision
     * too, and would make it with more authority than the request needs.
     *
     * `SecretText` is chosen over `SshPrivateKey`/`Certificate` because it is the shape a
     * pipeline author reaches for by mistake, so it is the case most likely to be met.
     */
    @Test
    fun `H8-16 a credential of the wrong kind is refused and sends nothing`() {
        val env = credentialStoreEnv(
            SecretText(
                id = CredentialsId("token"),
                bytes = "not-a-username-password".toByteArray(StandardCharsets.UTF_8),
            ),
        )
        val pipeline = script(
            """httpRequest(url = "${server.baseUrl}/basic-auth", authentication = CredentialsId("token"))""",
        )

        val result = runFresh(pipeline, "--allow-network", env = env)

        assertEquals(
            exitFailure,
            result.exitCode,
            "a credential the plugin cannot use is a typed failure, not a silent downgrade; " +
                "output:\n${result.output.takeLast(1500)}",
        )
        assertEquals(
            0,
            server.countOf("/basic-auth"),
            "THE assertion. The credential resolved — the store was readable and the entry " +
                "existed — and the request still must not be sent. 'It resolved' is not " +
                "'it is usable', and only the server can tell the two apart.",
        )
    }

    /**
     * ## The cancellation scenario this file does NOT have, and why
     *
     * H8 was meant to include a cancellation scenario. It is absent because
     * `timeout { }` does not bound `http.request`, and the mechanism is not a bug in
     * the transport:
     *
     * ```text
     * timeout(time = 2, unit = "SECONDS") { … }
     *     └── BodyExecutionEngine.projectScope
     *            └── ScopedBody(stageShOptions.copy(timeoutMs = effective))
     *                   └── consumed by the core.sh WATCHDOG
     *                          └── http.request never reads it
     * ```
     *
     * `core.sh` honours the block budget because it is the shell Step and the
     * watchdog is its budget. `http.request` carries its own `timeoutSeconds` in its
     * input and ignores `ShOptions.timeoutMs` entirely. A measured run of
     * `timeout(2s) { httpRequest("/slow?ms=30000") }` against the installed binary
     * aborts after ~35s — the Step's own default — not after 2s.
     *
     * So the Jenkins-idiomatic `timeout(time: 5, unit: 'MINUTES') { httpRequest(…) }`
     * places NO bound on the request today. Closing that means the handler has to
     * read `EXECUTION_BUDGET_CAPABILITY` and derive `min(step, block)`, which is a
     * behaviour change to a Step — and H8 is the certification block, not the place
     * to change what is being certified. It is recorded as a known limitation with
     * this mechanism, and the scenario is left unwritten rather than written to
     * assert that a 35s wait is correct.
     *
     * H8-07 remains the timeout evidence, and it is honest about its scope: it
     * proves the Step's OWN `timeoutSeconds` is enforced and does not hang the run.
     */

    /**
     * ## The H4 digest scenario this file also does NOT have, and why
     *
     * H4's law is that the digest covers the bytes the process never kept. It is
     * proven in-process by the module's own subscriber tests. It cannot be proven
     * here, for one reason with two halves:
     *
     * ```text
     * 1. stdout   — no DomainEvent carries a Step's typed output.
     *               StepFinished has no output field and there is no
     *               StepOutputCaptured, so `pipelinek run` reports that the Step
     *               succeeded and nothing about status, body, digest or truncation.
     *
     * 2. journal  — `operation_journal.output` does not hold the readable envelope
     *               for this Step either. The stored bytes are not the JSON the
     *               codec produces (verified against a real 8 MiB run).
     * ```
     *
     * Together those are one operator-facing fact: **a Step's output is not
     * observable from the installed distribution.** An author who wants to know
     * what a request returned has nowhere to look.
     *
     * Reading it would take either a published-contract change (an output-carrying
     * event, which is a new member of `DomainEvent` and therefore a new member of
     * `pipeline-events.api`) or a decryption key the CLI deliberately does not
     * expose. H8 is the block that certifies, not the block that changes what is
     * certified, so both are recorded as known limitations and the scenario is left
     * unwritten rather than written to assert something the product cannot say.
     */

    private companion object {
        /** The body both POST scenarios send, escaped for the generated .pipeline.kts. */
        const val CHARGE = """{\"charge\":1}"""
    }
}
