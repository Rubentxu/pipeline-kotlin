package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ParameterSpec
import dev.rubentxu.pipeline.v2.dsl.RunInputs
import dev.rubentxu.pipeline.v2.service.ServiceCodec
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import java.io.IOException
import java.nio.file.Files

/**
 * Where the service takes the declaration of a script's parameters from.
 *
 * The answer is never an exception: the name comes from the wire, and a script the service
 * cannot describe is a refusal the client has to read.
 */
internal fun interface ServiceParameterDescriber {

    /** The declaration of the parameters of [script], or the refusal that there is none. */
    fun describe(script: String): ServiceParameterDescribing
}

/** What a describer found for a script. */
internal sealed interface ServiceParameterDescribing {

    /** The declaration of the script, and the digest the service names it with. */
    data class Described(val contract: List<ParameterSpec>, val contractSha256: String) : ServiceParameterDescribing

    /** The script is not described, and [refusal] says why. */
    data class Refused(val refusal: ServiceRefusal) : ServiceParameterDescribing
}

/**
 * The describer that reads the manifest an operator approved for a script, and compiles nothing.
 *
 * ## Why a manifest, and not the compiler
 *
 * Because compiling a script runs code of the script: a top-level statement, an initialiser, a
 * dependency the compiler resolves. A client that may ask for a description may not run the
 * script, so the declaration is taken on the operator's side —`pipelinek validate
 * --parameter-manifest`— and approved by being placed next to the script, inside the root the
 * operator declared.
 *
 * ## What is checked, in this order
 *
 * 1. The script is admitted by the rule a start admits it by: a name inside the root of scripts,
 *    never a path. A name a start refuses is refused here with the same `script-not-admitted`, so
 *    describing is never a way to probe the machine outside the root.
 * 2. Its manifest, `<script>.parameters.json`, is admitted by the same rule:
 *    `the-script-has-no-approved-manifest` otherwise.
 * 3. The manifest is at most [ParameterManifest.MAX_BYTES] and of this build's closed shape:
 *    `the-manifest-is-not-readable` otherwise.
 * 4. Its contract is the one its digest names: `the-manifest-contract-does-not-match-its-digest`
 *    otherwise, which is a manifest edited by hand after it was written.
 * 5. Its declaration is one a start accepts, judged by [RunInputs.declarationRefusal]: the reason
 *    of `RunInputs` otherwise, so a description is never a form whose every submission is refused.
 * 6. The script's bytes are the ones the manifest was approved for:
 *    `the-script-changed-since-its-manifest-was-approved` otherwise, and
 *    `the-script-is-not-readable` when the bytes cannot be read.
 *
 * The checks that read the manifest come before the one that reads the script, so a script is
 * digested only when there is an approved declaration to compare it with.
 */
internal class ApprovedManifestDescriber(
    private val admission: ServiceStartAdmission,
) : ServiceParameterDescriber {

    override fun describe(script: String): ServiceParameterDescribing {
        val scriptPath = when (val admitted = admission.admit(script, null)) {
            is ServiceStartAdmissionOutcome.Admitted -> admitted.script
            is ServiceStartAdmissionOutcome.Refused -> return ServiceParameterDescribing.Refused(admitted.refusal)
        }
        val manifestName = script + ParameterManifest.SUFFIX
        val manifestPath = when (val admitted = admission.admit(manifestName, null)) {
            is ServiceStartAdmissionOutcome.Admitted -> admitted.script
            is ServiceStartAdmissionOutcome.Refused -> return refused(
                NO_MANIFEST,
                "there is no approved manifest '$manifestName' next to the script; the operator " +
                    "writes it with `pipelinek validate --parameter-manifest`",
            )
        }
        val text = try {
            if (Files.size(manifestPath) > ParameterManifest.MAX_BYTES) {
                return refused(NOT_READABLE, "the manifest is larger than ${ParameterManifest.MAX_BYTES} bytes")
            }
            Files.readString(manifestPath)
        } catch (_: IOException) {
            return refused(NOT_READABLE, "the manifest '$manifestName' could not be read as UTF-8 text")
        }
        val manifest = when (val reading = ParameterManifest.read(text)) {
            is ParameterManifestReading.Read -> reading.manifest
            is ParameterManifestReading.Unreadable -> return refused(NOT_READABLE, reading.detail)
        }
        if (ServiceCodec.parameterContractDigest(manifest.contract) != manifest.contractSha256) {
            return refused(
                DIGEST_MISMATCH,
                "the contract of the manifest is not the one its 'contractSha256' names; the " +
                    "manifest was changed after it was written",
            )
        }
        RunInputs.declarationRefusal(manifest.contract)?.let { refusal ->
            return refused(refusal.reason, refusal.detail)
        }
        val scriptSha256 = try {
            ParameterManifest.digestOf(scriptPath)
        } catch (_: IOException) {
            return refused(SCRIPT_NOT_READABLE, "the script '$script' could not be read to compare it with its manifest")
        }
        if (scriptSha256 != manifest.scriptSha256) {
            return refused(
                SCRIPT_CHANGED,
                "the script's bytes are not the ones its manifest was approved for; the operator " +
                    "approves a new manifest for the script as it is now",
            )
        }
        return ServiceParameterDescribing.Described(manifest.contract, manifest.contractSha256)
    }

    private fun refused(reason: String, detail: String): ServiceParameterDescribing =
        ServiceParameterDescribing.Refused(ServiceRefusal.DescribeParametersRefused(reason, detail))

    private companion object {
        const val NO_MANIFEST: String = "the-script-has-no-approved-manifest"
        const val NOT_READABLE: String = "the-manifest-is-not-readable"
        const val DIGEST_MISMATCH: String = "the-manifest-contract-does-not-match-its-digest"
        const val SCRIPT_CHANGED: String = "the-script-changed-since-its-manifest-was-approved"
        const val SCRIPT_NOT_READABLE: String = "the-script-is-not-readable"
    }
}
