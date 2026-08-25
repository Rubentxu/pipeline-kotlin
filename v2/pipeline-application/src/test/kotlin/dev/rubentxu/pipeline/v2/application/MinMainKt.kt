package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShConfig
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DurableShellExecutor
import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal JVM main for UAT-LOCAL-001 external kill/resume test.
 *
 * This class is forked by UatLocal001KillDuringShTest via ProcessBuilder.
 * It launches a sh script, prints the control-dir path to stdout, then sleeps
 * (simulating runner doing work).
 *
 * Usage: java ... MinMainKt <controlDir> <markerPath> <sleepSeconds>
 */
object MinMainKt {
    private val executor = DurableShellExecutor()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            System.err.println("Usage: MinMainKt <controlDir> <markerPath> <sleepSeconds>")
            throw IllegalArgumentException("Not enough args")
        }
        val controlDir = Path.of(args[0])
        val markerPath = Path.of(args[1])
        val sleepSeconds = args[2].toInt()

        // Create control dir and script
        Files.createDirectories(controlDir)
        val scriptSh = controlDir.resolve("script.sh")
        val markerParent = markerPath.parent
        if (markerParent != null) {
            Files.createDirectories(markerParent)
        }

        // Write script: echo to marker, sleep, exit 0
        val script = "echo started >> '$markerPath'; sleep $sleepSeconds; echo done >> '$markerPath'; exit 0"
        Files.writeString(scriptSh, script)
        Files.setPosixFilePermissions(scriptSh, java.util.EnumSet.of(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        ))

        val config = DurableShConfig.fromSystemProperties()
        val opId = "uat-local-001-test"

        // Launch and detach
        val process = executor.launch(controlDir, script, opId, config)
        executor.detach(process, controlDir)

        // Print control dir for test to find
        println("CONTROL_DIR=$controlDir")
        println("PID=${process.pid()}")

        // Flush stdout before sleeping
        System.out.flush()

        // Sleep to simulate runner doing other work (test will kill us during this)
        Thread.sleep((sleepSeconds * 1000).toLong())
    }
}
