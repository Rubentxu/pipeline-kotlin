package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Timeout

/**
 * The same operation, written at stage level and inside a body of steps.
 *
 * ## The guarantee under trial
 *
 * That a body of steps declares the SAME steps a stage declares, and that it
 * answers the SAME values: the operation is one operation, and where it is
 * written does not change what it declares.
 *
 * ## Why this row exists
 *
 * The contract of a body declares every operation WITHOUT a value by default,
 * and the forms that omit a value are offered as EXTENSIONS that repeat the
 * whole parameter list with the same values. The value an extension supplies
 * and the value the member of the stage supplies live in two files, and
 * nothing but a row like this one holds them together: a value that drifts
 * would make the body declare another step, and the drift would be invisible
 * in the plan of a pipeline that never wrote the operation at stage level.
 *
 * ## What this file does NOT freeze
 *
 * The shape of the `StepSpec` family, the vocabulary of the scopes and the
 * order in which the steps run. This file writes the operations and reads
 * the steps they declare.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class StepBodyScopeParityTest {

    /**
     * One operation, written twice: at stage level and inside a body.
     *
     * The two lambdas are written apart because the two receivers are two
     * types, and the point of the row is that the SAME call compiles in both
     * and declares the same thing.
     *
     * @param name what the row names, for the report of the run
     * @param atStage the call, written at stage level
     * @param inBody the same call, written inside a body of steps
     */
    private class Row(
        val name: String,
        val atStage: StageScope.() -> Any?,
        val inBody: StepBodyScope.() -> Any?,
    )

    /** The key a plugin registers its step under, for the row of a plugin. */
    private val pluginKey = PluginStepId("example.parity")

    /** The payload of that step, for the row of a plugin. */
    private val pluginInput = EncodedStepValue("{\"kind\":\"parity\"}")

    /**
     * The operations a body of steps has to declare the same steps the stage
     * declares.
     *
     * Every operation of the DSL is here, and every one of them is written
     * in the shape an author writes: with the values omitted that the
     * operation declares by default, and with the NAMED spelling for the two
     * operations whose parameter name is part of the contract.
     */
    private val rows: List<Row> = listOf(
        Row(
            "echo",
            atStage = { echo("hello") },
            inBody = { echo("hello") },
        ),
        Row(
            "sh as a command",
            atStage = { sh("echo hello") },
            inBody = { sh("echo hello") },
        ),
        Row(
            "sh as a script, named",
            atStage = { sh(script = "echo hello") },
            inBody = { sh(script = "echo hello") },
        ),
        Row(
            "error",
            atStage = { error("boom") },
            inBody = { error("boom") },
        ),
        Row(
            "sleep",
            atStage = { sleep(1) },
            inBody = { sleep(1) },
        ),
        Row(
            "env",
            atStage = { env("HOME") },
            inBody = { env("HOME") },
        ),
        Row(
            "isUnix",
            atStage = { isUnix() },
            inBody = { isUnix() },
        ),
        Row(
            "pwd",
            atStage = { pwd() },
            inBody = { pwd() },
        ),
        Row(
            "param",
            atStage = { param("TARGET") },
            inBody = { param("TARGET") },
        ),
        Row(
            "artifactQuery",
            atStage = { artifactQuery("reports") },
            inBody = { artifactQuery("reports") },
        ),
        Row(
            "writeFile",
            atStage = { writeFile("out.txt", "content") },
            inBody = { writeFile("out.txt", "content") },
        ),
        Row(
            "readFile",
            atStage = { readFile("out.txt") },
            inBody = { readFile("out.txt") },
        ),
        Row(
            "fileExists",
            atStage = { fileExists("out.txt") },
            inBody = { fileExists("out.txt") },
        ),
        Row(
            "deleteDir",
            atStage = { deleteDir("build") },
            inBody = { deleteDir("build") },
        ),
        Row(
            "cleanWs",
            atStage = { cleanWs() },
            inBody = { cleanWs() },
        ),
        Row(
            "archiveArtifacts",
            atStage = { archiveArtifacts("target/**") },
            inBody = { archiveArtifacts("target/**") },
        ),
        Row(
            "milestone",
            atStage = { milestone(1, "phase") },
            inBody = { milestone(1, "phase") },
        ),
        Row(
            "stash",
            atStage = { stash("saved", "**/*.txt") },
            inBody = { stash("saved", "**/*.txt") },
        ),
        Row(
            "unstash",
            atStage = { unstash("saved") },
            inBody = { unstash("saved") },
        ),
        Row(
            "publishHTML",
            atStage = { publishHTML("report", "reports") },
            inBody = { publishHTML("report", "reports") },
        ),
        Row(
            "git",
            atStage = { git("https://example.invalid/repo.git") },
            inBody = { git("https://example.invalid/repo.git") },
        ),
        Row(
            "checkout of a carrier",
            atStage = { checkout(scmGit("https://example.invalid/repo.git")) },
            inBody = { checkout(scmGit("https://example.invalid/repo.git")) },
        ),
        Row(
            "withEnv",
            atStage = { withEnv(listOf("TARGET=one")) { echo("inside") } },
            inBody = { withEnv(listOf("TARGET=one")) { echo("inside") } },
        ),
        Row(
            "dir",
            atStage = { dir("sub") { echo("inside") } },
            inBody = { dir("sub") { echo("inside") } },
        ),
        Row(
            "lock",
            atStage = { lock("resource") { echo("inside") } },
            inBody = { lock("resource") { echo("inside") } },
        ),
        Row(
            "input",
            atStage = { input("question?") { echo("inside") } },
            inBody = { input("question?") { echo("inside") } },
        ),
        Row(
            "catchError",
            atStage = { catchError { echo("inside") } },
            inBody = { catchError { echo("inside") } },
        ),
        Row(
            "warnError",
            atStage = { warnError("careful") { echo("inside") } },
            inBody = { warnError("careful") { echo("inside") } },
        ),
        Row(
            "waitUntil",
            atStage = { waitUntil { sh("test -f marker") } },
            inBody = { waitUntil { sh("test -f marker") } },
        ),
        Row(
            "timestamps",
            atStage = { timestamps { echo("inside") } },
            inBody = { timestamps { echo("inside") } },
        ),
        Row(
            "timeout",
            atStage = { timeout(1, "SECONDS") { echo("inside") } },
            inBody = { timeout(1, "SECONDS") { echo("inside") } },
        ),
        Row(
            "retry",
            atStage = { retry(2) { echo("inside") } },
            inBody = { retry(2) { echo("inside") } },
        ),
        Row(
            "slot",
            atStage = { slot("named") { echo("inside") } },
            inBody = { slot("named") { echo("inside") } },
        ),
        Row(
            "withCredentials",
            atStage = {
                withCredentials(StepSpec.CredentialsBinding.string("entry", "SECRET")) { echo("inside") }
            },
            inBody = {
                withCredentials(StepSpec.CredentialsBinding.string("entry", "SECRET")) { echo("inside") }
            },
        ),
        Row(
            "the step of a plugin",
            atStage = { registryStep(pluginKey, pluginInput) },
            inBody = { registryStep(pluginKey, pluginInput) },
        ),
        Row(
            "the block of a plugin",
            atStage = { registryBlock(pluginKey, pluginInput) { echo("inside") } },
            inBody = { registryBlock(pluginKey, pluginInput) { echo("inside") } },
        ),
    )

    /**
     * The same call declares the same step, and answers the same value.
     *
     * The step the two declare is compared as DATA, and the value they
     * answer is compared as a value: an operation that declares another step
     * inside a body, or that answers another value, is a difference the
     * author would find at RUN time and not at the call site.
     */
    @TestFactory
    fun `the same call declares the same step at stage level and inside a body`(): List<DynamicTest> =
        rows.map { row ->
            DynamicTest.dynamicTest(row.name) {
                val stage = StageScope("parity")
                val body = stage.blockScope("steps")

                val valueAtStageLevel = row.atStage(stage)
                val valueInTheBody = row.inBody(body)

                assertEquals(
                    valueAtStageLevel,
                    valueInTheBody,
                    "the operation answers another value inside a body",
                )
                assertEquals(
                    stage.steps(),
                    body.steps(),
                    "the operation declares another step inside a body",
                )
            }
        }
}
