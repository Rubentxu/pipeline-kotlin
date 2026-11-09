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
 * usage: sdk-zip <sdkRepo> <version> <distDir> <bomGroup:bomArtifact> <group:artifact> [<group:artifact>...]
 * exit  0 = built, 1 = failed
 * ```
 * The coordinate the BOM itself is published under comes FIRST, and the
 * managed coordinates follow it: the BOM's own coordinate is not one of the
 * coordinates it pins, and a single list of "the coordinates" cannot say which
 * one is which. The managed coordinates are passed as `group:artifact` pairs on
 * the command line; the builder completes each with the version. The pair
 * list must be non-empty (an empty BOM is a defect, not a no-op).
*/
fun main(args: Array<String>) {
    if (args.size < 5) {
        System.err.println(
            "usage: sdk-zip <sdkRepo> <version> <distDir> <bomGroup:bomArtifact> <group:artifact> [...]",
        )
        exitProcess(2)
    }
    val sdkRepo = Paths.get(args[0])
    val version = args[1]
    val distDir: Path = Paths.get(args[2])
    val bomCoordinate: Pair<String, String> = args[3].let { pair ->
        val parts = pair.split(":")
        require(parts.size == 2) {
            "the BOM coordinate must be 'group:artifact', got '$pair'"
        }
        parts[0] to parts[1]
    }
    val coordinates: List<Pair<String, String>> = args.drop(4).map { pair ->
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
            bomCoordinate = bomCoordinate,
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
