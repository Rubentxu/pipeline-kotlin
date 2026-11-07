package dev.rubentxu.pipeline.v2.release

import dev.rubentxu.pipeline.v2.domain.digest.Sha256
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The interpreter at the publication boundary.
 *
 * The pure decider ([evaluateMavenPublication]) is the authority on whether a
 * request may publish a Maven bundle. This object is the effectful
 * interpreter that turns that verdict into an outcome the door can fail on:
 * it reads the documents, measures the bytes, prints the operator
 * diagnostic and returns a value.
 *
 * Keeping the pass/fail decision out of here is deliberate. A script that
 * re-derived the rules could disagree with the model, and the model is what
 * the tests pin. This only measures and reports.
 *
 * A document that cannot be read is not a document that admits nothing: it
 * is a document nobody can account for, and the refusal says so by name.
 */
object MavenPublicationAdmission {

    /**
     * Read the documents, measure the bytes and decide whether the request
     * may publish the bundle.
     */
    fun admit(
        requestPath: Path,
        manifestPath: Path,
        certificationPath: Path,
        bundleRoot: Path,
        sdkZip: Path,
        publishedRoot: Path,
    ): MavenPublicationOutcome {
        val requestText = readText(requestPath)
            ?: return MavenPublicationOutcome.Refused(
                "the request at $requestPath does not exist or cannot be read. A request " +
                    "nobody can account for is not a request that publishes nothing.",
            )
        val request = try {
            MavenPublicationCodec.decodeRequest(requestText)
        } catch (e: Exception) {
            return MavenPublicationOutcome.Refused(
                "the request at $requestPath is not a request this build can act on: " +
                    "${e.message ?: e::class.java.simpleName}",
            )
        }

        val manifestText = readText(manifestPath)
            ?: return MavenPublicationOutcome.Refused(
                "the manifest at $manifestPath does not exist or cannot be read. A request that " +
                    "names no manifest publishes nothing anybody can check.",
            )
        val manifest = try {
            MavenPublicationCodec.decodeManifest(manifestText)
        } catch (e: Exception) {
            return MavenPublicationOutcome.Refused(
                "the manifest at $manifestPath is not a manifest this build can act on: " +
                    "${e.message ?: e::class.java.simpleName}",
            )
        }

        val certification = readCertification(certificationPath)
        if (certification is CertificationRead.Failed) {
            return MavenPublicationOutcome.Refused(certification.reason)
        }
        val certificationDocument = (certification as? CertificationRead.Read)?.certification

        if (!Files.isDirectory(bundleRoot)) {
            return MavenPublicationOutcome.Refused(
                "the bundle at $bundleRoot is not a directory, so the bytes that would be " +
                    "published were not measured. A publication of bytes nobody measured is a " +
                    "publication with no guarantee.",
            )
        }

        if (!Files.isRegularFile(sdkZip)) {
            return MavenPublicationOutcome.Refused(
                "the SDK ZIP at $sdkZip does not exist, so the contracts of the candidate could " +
                    "not be compared with the contracts of the bundle.",
            )
        }
        val sdkZipDigest = try {
            Sha256.ofFile(sdkZip)
        } catch (e: IOException) {
            return MavenPublicationOutcome.Refused(
                "the SDK ZIP at $sdkZip could not be read, so its digest was not measured: " +
                    "${e.message ?: e::class.java.simpleName}. The contracts of the candidate " +
                    "could not be compared with the contracts of the bundle.",
            )
        }
        if (sdkZipDigest != manifest.sdkZip.sha256) {
            return MavenPublicationOutcome.Refused(
                "the SDK ZIP at $sdkZip has digest $sdkZipDigest and the manifest names " +
                    "${manifest.sdkZip.sha256}. The contracts compared would be the contracts of " +
                    "another candidate.",
            )
        }

        if (!Files.isDirectory(publishedRoot)) {
            return MavenPublicationOutcome.Refused(
                "the target repository at $publishedRoot could not be read, so the state of the " +
                    "version was not measured. A repository that cannot be read is not a " +
                    "repository without the version.",
            )
        }

        val bundle = try {
            MavenBundleMeasurement.measure(bundleRoot)
        } catch (e: IOException) {
            return MavenPublicationOutcome.Refused(
                "the bundle at $bundleRoot holds a file that could not be read, so the bytes " +
                    "that would be published were not measured: ${e.message ?: e::class.java.simpleName}. " +
                    "A publication of bytes nobody measured is a publication with no guarantee.",
            )
        }
        val observed = try {
            MavenObservedPublication(
                bundleArtifacts = bundle.artifacts,
                bundleSha256 = bundle.sha256,
                published = MavenBundleMeasurement.measurePublished(
                    publishedRoot,
                    request.productVersion.toString(),
                ),
                certifiedContracts = MavenBundleMeasurement.contractsIn(sdkZip),
                manifestSha256 = Sha256.ofFile(manifestPath),
            )
        } catch (e: IOException) {
            return MavenPublicationOutcome.Refused(
                "the state of the publication could not be measured: " +
                    "${e.message ?: e::class.java.simpleName}. A publication nobody measured is " +
                    "a publication with no guarantee.",
            )
        }

        return when (
            val verdict = evaluateMavenPublication(
                request = request,
                manifest = manifest,
                certification = certificationDocument,
                observed = observed,
            )
        ) {
            is MavenPublicationVerdict.Admitted -> MavenPublicationOutcome.Admitted(verdict.render())
            is MavenPublicationVerdict.Rejected -> MavenPublicationOutcome.Refused(verdict.render())
        }
    }

    /**
     * The certification, or the reason why there is not one.
     *
     * A file that is not there is a request without a certification, and the
     * model names that refusal. A file that is there and cannot be parsed is
     * a different fact, and it refuses here: a certification nobody can read
     * is not a certification that admits nothing.
     */
    private fun readCertification(path: Path): CertificationRead {
        if (!Files.exists(path)) return CertificationRead.Absent
        val text = readText(path) ?: return CertificationRead.Failed(
            "the certification at $path exists but cannot be read. A certification nobody can " +
                "read is not a certification that admits nothing.",
        )
        return when (val decoded = PromotionCertificationCodec.decode(text)) {
            is PromotionCertificationDecode.Accepted -> CertificationRead.Read(decoded.certification)
            is PromotionCertificationDecode.Rejected -> CertificationRead.Failed(
                "the certification at $path is not a certification this build can act on: " +
                    decoded.render(),
            )
        }
    }

    /** The text of the document at [path], or null when it cannot be read. */
    private fun readText(path: Path): String? = try {
        if (Files.isRegularFile(path)) Files.readString(path) else null
    } catch (e: IOException) {
        null
    }
}

/**
 * What the certification at the boundary is: a document, an absence, or a
 * failure to read one.
 */
private sealed interface CertificationRead {

    /** There is no certification: the request was made without one. */
    data object Absent : CertificationRead

    /** The certification, read and understood. */
    data class Read(val certification: PromotionCertification) : CertificationRead

    /** The certification is there and could not be acted on. */
    data class Failed(val reason: String) : CertificationRead
}

/**
 * Outcome of the publication check at the boundary.
 */
sealed interface MavenPublicationOutcome {

    /**
     * The request may publish the bundle. [summary] is the verdict's own
     * diagnostic, carried through verbatim so the publication log shows what
     * the gate concluded rather than a flattened "OK".
     */
    data class Admitted(val summary: String) : MavenPublicationOutcome

    /** The request may NOT publish. The reason is in [reason]. */
    data class Refused(val reason: String) : MavenPublicationOutcome
}
