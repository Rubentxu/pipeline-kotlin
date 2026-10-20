package dev.rubentxu.pipeline.v2.release

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Build entry point for [SdkZipBuilder].
 * The Gradle task `:pipeline-release:sdkZip` shells out to this main
 * because Gradle cannot call Kotlin project code directly. The classpath
 * is the same as `:pipeline-release`'s main runtime classpath, so the
 * Kotlin code runs as compiled project code, not as a copy.
 * ```text
 * usage: sdk-zip <sdkRepo> <version> <distDir> <group:artifact> [<group:artifact>...]
 * exit  0 = built, 1 = failed
 * ```
 * The managed coordinates are passed as `group:artifact` pairs on the
 * command line; the builder completes each with the version. The pair
 * list must be non-empty (an empty BOM is a defect, not a no-op).
*/
fun main(args: Array<String>) {
    if (args.size < 4) {
        System.err.println(
            "usage: sdk-zip <sdkRepo> <version> <distDir> <group:artifact> [...]",
        )
        exitProcess(2)
    }
    val sdkRepo = Paths.get(args[0])
    val version = args[1]
    val distDir: Path = Paths.get(args[2])
    val coordinates: List<Pair<String, String>> = args.drop(3).map { pair ->
        val parts = pair.split(":")
        require(parts.size == 2) {
            "managed coordinate must be 'group:artifact', got '$pair'"
        }
        parts[0] to parts[1]
    }
    if (!Files.isDirectory(sdkRepo)) {
        System.err.println(
            "[release] FATAL — SDK repository not found at $sdkRepo. " +
                "Did publishSdkForExternalPlugin run?",
        )
        exitProcess(1)
    }
    try {
        val (zip, json) = SdkZipBuilder.build(
            sdkRepo = sdkRepo,
            version = version,
            managedCoordinates = coordinates,
            distDir = distDir,
        )
        println("[release] SDK ZIP:    $zip")
        println("[release] BOM JSON:   $json")
    } catch (ex: IllegalArgumentException) {
        System.err.println("[release] SDK build refused: ${ex.message}")
        exitProcess(1)
    }
}
