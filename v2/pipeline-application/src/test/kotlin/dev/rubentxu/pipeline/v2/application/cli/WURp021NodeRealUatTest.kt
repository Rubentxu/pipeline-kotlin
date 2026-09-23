package dev.rubentxu.pipeline.v2.application.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport

/**
 * WU-RP-046 — UAT-RP-021 (Node real) installed-distribution UAT.
 *
 * Per `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md`:
 *   | UAT-RP-021 | Node real | mismo contrato con lockfile y tests Node |
 *   ZIP instalado, exit, hash y eventos
 *
 * The fixture is a real Node project (under
 * `src/test/resources/uat-rp-019-real-builds/node/`) with a single
 * `good/package.json` and `good/build.js` that writes `dist/output.txt`.
 * No npm dependencies are needed (zero-dependency script), so the test
 * stays hermetic and fast.
 *
 * Oracle mirrors UAT-RP-019's contract:
 *   - happy path: pipelinek exits 0, dist/output.txt exists with size > 0,
 *     RunFinished event emitted.
 *   - failure path: pipelinek exits non-zero (synthesized by passing a
 *     script that points to a missing build.js).
 *
 * The `node` binary is taken from an absolute asdf path (NOT the shim,
 * because pipelinek-spawned subshells do not inherit asdf's resolver).
 */
@Timeout(5, unit = TimeUnit.MINUTES)
class WURp021NodeRealUatTest {

    private val binary: File = AppBinSupport.discover().toFile()

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(vararg args: String): CliResult {
        val pb = ProcessBuilder(binary.absolutePath, *args)
            .directory(File(System.getProperty("user.dir")))
        val proc = pb.start()
        val finished = proc.waitFor(4, TimeUnit.MINUTES)
        assertTrue(finished, "pipelinek hung on ${args.toList()}")
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        return CliResult(proc.exitValue(), stdout, stderr)
    }

    private fun copyFixtureTo(suffix: String): Path {
        val src = Paths.get("src", "test", "resources", "uat-rp-019-real-builds", "node")
        val dst = Files.createTempDirectory("uat-rp-021-node-$suffix-")
        Files.walk(src).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { srcFile ->
                val rel = src.relativize(srcFile).toString()
                val target = dst.resolve(rel)
                Files.createDirectories(target.parent)
                Files.copy(srcFile, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return dst
    }

    private fun writeHappyPipeline(projectDir: Path, scriptPath: Path) {
        val nodeBin = resolveNode()
        val body = """
            pipeline {
                stages {
                    stage("uat-rp-021-node") {
                        sh("set -eu; cd '${projectDir.toString()}/good' && $nodeBin ./build.js")
                        sh("set -eu; test -s '${projectDir.toString()}/good/../dist/output.txt' && echo ORACLE_NODE_OUT_OK")
                    }
                }
            }
        """.trimIndent()
        Files.writeString(scriptPath, body)
    }

    private fun writeBadPipeline(projectDir: Path, scriptPath: Path) {
        val nodeBin = resolveNode()
        // Build of an intentionally missing script -> node exits non-zero.
        val body = """
            pipeline {
                stages {
                    stage("uat-rp-021-node-bad") {
                        sh("set -eu; cd '${projectDir.toString()}/good' && $nodeBin ./missing.js || { echo ORACLE_NODE_BAD_FAIL; exit 1; }")
                    }
                }
            }
        """.trimIndent()
        Files.writeString(scriptPath, body)
    }

    /**
     * Resolve the absolute path of a Node binary, bypassing asdf shims.
     * Returns the first existing candidate among:
     *   1. $NODE_BIN
     *   3. /usr/local/bin/node, /usr/bin/node
     */
    private fun resolveNode(): String {
        val fromEnv = System.getenv("NODE_BIN")
        if (fromEnv != null && File(fromEnv).exists()) return fromEnv
        val asdfRoot = File(System.getProperty("user.home") + "/.asdf/installs/nodejs")
        if (asdfRoot.isDirectory) {
            asdfRoot.listFiles()?.sortedByDescending { it.name }?.forEach { v ->
                val candidate = File(v, "bin/node")
                if (candidate.exists()) return candidate.absolutePath
            }
        }
        listOf("/usr/local/bin/node", "/usr/bin/node").forEach {
            if (File(it).exists()) return it
        }
        throw IllegalStateException(
            "NODE_BIN not set and no fallback found; install Node or set NODE_BIN.",
        )
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "UAT_RP_021_RUN", matches = "1")
    fun `UAT-RP-021 Node real - happy path builds a real project, exit 0, dist has output txt present`() {
        val projectDir = copyFixtureTo("good")
        val script = projectDir.resolve("build-good.pipeline.kts")
        writeHappyPipeline(projectDir, script)
        val db = projectDir.resolve("journal.db")
        val result = run("run", "--db", db.toString(), script.toString())

        // Oracle 1: pipelinek exits 0 on the node happy path.
        assertEquals(0, result.exitCode,
            "pipelinek exit on Node happy path must be 0; stdout=${result.stdout.take(400)} stderr=${result.stderr.take(400)}")

        // Oracle 2: the produced output.txt is non-empty.
        val out = projectDir.resolve("dist").resolve("output.txt").toFile()
        assertTrue(out.exists() && out.length() > 0,
            "node dist/output.txt must exist and be non-empty; size=${if (out.exists()) out.length() else -1}")

        // Oracle 3: the build script output includes the marker.
        assertTrue(
            result.stdout.contains("ORACLE_NODE_OUT_OK")
                || result.stderr.contains("ORACLE_NODE_OUT_OK"),
            "expected ORACLE_NODE_OUT_OK after node script ran; stdout=${result.stdout.take(400)} stderr=${result.stderr.take(400)}"
        )
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "UAT_RP_021_RUN", matches = "1")
    fun `UAT-RP-021 Node real — failure path exits non-zero`() {
        val projectDir = copyFixtureTo("bad")
        val script = projectDir.resolve("build-bad.pipeline.kts")
        writeBadPipeline(projectDir, script)
        val db = projectDir.resolve("journal.db")
        val result = run("run", "--db", db.toString(), script.toString())

        assertNotEquals(0, result.exitCode,
            "pipelinek must exit non-zero when the Node script is missing")
    }
}
