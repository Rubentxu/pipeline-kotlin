package dev.rubentxu.pipeline.v2.release

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Command-line entry point for the PRODUCER of the Maven publication
 * manifest.
 *
 * It reads the candidate's handoff and the bundle that was staged for that
 * candidate, and writes the two documents of the channel: the manifest,
 * which says what is published, and the request, which asks for that
 * manifest to be published.
 *
 * Why it is a program and not a hand: a manifest written by hand is a
 * promise that nobody keeps. Every digest in it has to be the digest of the
 * bytes, and the only way to know that is to measure them.
 *
 * The candidate's identity is NOT derived here. It is read from the handoff,
 * which is the document that already binds the candidate to its SDK ZIP and
 * to its version. This producer reads that binding and measures the bundle;
 * it does not decide who the candidate is.
 *
 * ```text
 * usage: maven-publication-manifest <handoff.json> <bundleDir> <outDir>
 * exit  0 = the manifest and the request were written, 1 = refused, 2 = usage error
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println(
            "usage: maven-publication-manifest <handoff.json> <bundleDir> <outDir>",
        )
        exitProcess(2)
    }

    val handoffPath = Paths.get(args[0])
    val bundleRoot = Paths.get(args[1])
    val outDir = Paths.get(args[2])

    val handoffText = readText(handoffPath)
    if (handoffText == null) {
        System.err.println(
            "[release] maven publication manifest NOT written — the handoff at $handoffPath " +
                "does not exist or cannot be read. A manifest that names no candidate is not a " +
                "manifest.",
        )
        exitProcess(1)
    }

    val handoff = try {
        CandidateHandoffCodec.decode(handoffText)
    } catch (e: Exception) {
        System.err.println(
            "[release] maven publication manifest NOT written — the handoff at $handoffPath is " +
                "not a handoff this build can act on: ${e.message ?: e::class.java.simpleName}",
        )
        exitProcess(1)
    }

    val sdk = handoff.sdk
    if (sdk == null) {
        System.err.println(
            "[release] maven publication manifest NOT written — the candidate " +
                "${handoff.candidateId.canonical} carries no SDK ZIP. The manifest binds the " +
                "bundle to the SDK ZIP of its candidate, and without it there is nothing to bind " +
                "the contracts to.",
        )
        exitProcess(1)
    }

    if (!Files.isDirectory(bundleRoot)) {
        System.err.println(
            "[release] maven publication manifest NOT written — the bundle at $bundleRoot is " +
                "not a directory, so the bytes were not measured.",
        )
        exitProcess(1)
    }

    val bundle = MavenBundleMeasurement.measure(bundleRoot)
    if (bundle.artifacts.isEmpty()) {
        System.err.println(
            "[release] maven publication manifest NOT written — the bundle at $bundleRoot holds " +
                "no artifact. A manifest that publishes nothing is not a manifest.",
        )
        exitProcess(1)
    }

    val manifest = MavenPublicationManifest(
        candidateId = handoff.candidateId,
        productVersion = handoff.productVersion,
        sdkZip = MavenFileDigest(sdk.name, sdk.sha256),
        artifacts = bundle.artifacts,
        bundleSha256 = bundle.sha256,
    )

    try {
        Files.createDirectories(outDir)
    } catch (e: IOException) {
        System.err.println(
            "[release] maven publication manifest NOT written — the output directory $outDir " +
                "could not be created: ${e.message ?: e::class.java.simpleName}",
        )
        exitProcess(1)
    }

    val manifestPath = outDir.resolve(MANIFEST_NAME)
    val requestPath = outDir.resolve(REQUEST_NAME)
    try {
        Files.writeString(manifestPath, MavenPublicationCodec.encode(manifest))
        val request = MavenPublicationRequest(
            candidateId = handoff.candidateId,
            productVersion = handoff.productVersion,
            manifestName = manifestPath.fileName.toString(),
            manifestSha256 = sha256Of(manifestPath),
        )
        Files.writeString(requestPath, MavenPublicationCodec.encode(request))
    } catch (e: IOException) {
        System.err.println(
            "[release] maven publication manifest NOT written — the documents could not be " +
                "written: ${e.message ?: e::class.java.simpleName}",
        )
        exitProcess(1)
    }

    println("[release] maven publication manifest WRITTEN")
    println("[release]   candidate_id: ${handoff.candidateId.canonical}")
    println("[release]   version:     ${handoff.productVersion}")
    println("[release]   artifacts:   ${manifest.artifacts.size}")
    println("[release]   bundle:      ${manifest.bundleSha256}")
    println("[release]   manifest:    $manifestPath")
    println("[release]   request:     $requestPath")
}

/** The name the manifest is filed under. */
private const val MANIFEST_NAME: String = "maven-publication-manifest.json"

/** The name the request is filed under. */
private const val REQUEST_NAME: String = "maven-publication-request.json"

/** The text of the document at [path], or null when it cannot be read. */
private fun readText(path: Path): String? = try {
    if (Files.isRegularFile(path)) Files.readString(path) else null
} catch (e: IOException) {
    null
}
