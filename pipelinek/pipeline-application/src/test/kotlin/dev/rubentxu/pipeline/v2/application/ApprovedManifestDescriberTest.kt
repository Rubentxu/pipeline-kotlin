package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.ParameterSpec
import dev.rubentxu.pipeline.v2.service.ServiceCodec
import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The description of a script's parameters comes from the manifest an operator approved for it,
 * and from nothing else.
 *
 * ## The guarantee under trial
 *
 * The describer answers with the declaration of an approved manifest only when the manifest
 * still describes the script it names: the script is admitted with the rule a start admits it
 * with, the manifest sits next to it, its contract is the one its digest names, the declaration
 * is one a start accepts, and the script's bytes are the ones the manifest was approved for.
 * Each of those failures is refused with a reason of its own. The script is never compiled: a
 * script that does not compile is described by its approved manifest.
 *
 * ## Why each row is here
 *
 * - The admitted row is the case.
 * - One row per reason, so a reason that is never raised, or raised for another failure, fails
 *   a row of its own.
 * - The names a start refuses are refused here with the same refusal, so describing a script is
 *   never a way to probe the machine outside the root.
 * - The script that does not compile is the proof that nothing was compiled.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ApprovedManifestDescriberTest {

    @TempDir
    lateinit var root: Path

    private lateinit var scripts: Path

    @BeforeEach
    fun material() {
        scripts = Files.createDirectories(root.resolve("scripts"))
    }

    @Test
    fun `a script with an approved manifest is described with its declaration`() {
        approved(SCRIPT, BODY, CONTRACT)

        val described = assertInstanceOf(ServiceParameterDescribing.Described::class.java, describe(SCRIPT))

        assertEquals(CONTRACT, described.contract)
        assertEquals(ServiceCodec.parameterContractDigest(CONTRACT), described.contractSha256)
    }

    @Test
    fun `a script that does not compile is described by its approved manifest, because nothing compiles it`() {
        approved(SCRIPT, "this is not ( kotlin", CONTRACT)

        val described = assertInstanceOf(ServiceParameterDescribing.Described::class.java, describe(SCRIPT))

        assertEquals(CONTRACT, described.contract)
    }

    @Test
    fun `a script without an approved manifest is refused`() {
        Files.writeString(scripts.resolve(SCRIPT), BODY)

        assertRefusedWith("the-script-has-no-approved-manifest", describe(SCRIPT))
    }

    @Test
    fun `a manifest this build cannot read is refused`() {
        Files.writeString(scripts.resolve(SCRIPT), BODY)
        Files.writeString(scripts.resolve(SCRIPT + ParameterManifest.SUFFIX), "{\"schema\":\"other\"}")

        assertRefusedWith("the-manifest-is-not-readable", describe(SCRIPT))
    }

    @Test
    fun `a manifest larger than the bound is refused without being read`() {
        Files.writeString(scripts.resolve(SCRIPT), BODY)
        Files.write(scripts.resolve(SCRIPT + ParameterManifest.SUFFIX), ByteArray(ParameterManifest.MAX_BYTES.toInt() + 1))

        assertRefusedWith("the-manifest-is-not-readable", describe(SCRIPT))
    }

    @Test
    fun `a manifest whose contract is not the one its digest names is refused`() {
        approved(SCRIPT, BODY, CONTRACT)
        val manifest = scripts.resolve(SCRIPT + ParameterManifest.SUFFIX)
        Files.writeString(manifest, Files.readString(manifest).replace("\"target\"", "\"other\""))

        assertRefusedWith("the-manifest-contract-does-not-match-its-digest", describe(SCRIPT))
    }

    @Test
    fun `a script that changed after its manifest was approved is refused`() {
        approved(SCRIPT, BODY, CONTRACT)
        Files.writeString(scripts.resolve(SCRIPT), "$BODY\n// changed")

        assertRefusedWith("the-script-changed-since-its-manifest-was-approved", describe(SCRIPT))
    }

    @Test
    fun `a declaration a start would refuse is refused with the reason of the start`() {
        approved(SCRIPT, BODY, listOf(ParameterSpec("target", "string"), ParameterSpec("target", "string")))
        assertRefusedWith("the-declaration-is-not-legal", describe(SCRIPT))

        approved(SCRIPT, BODY, listOf(ParameterSpec("PIPELINEK_TARGET", "string")))
        assertRefusedWith("the-parameter-name-is-reserved", describe(SCRIPT))
    }

    @Test
    fun `a name a start refuses is refused here with the same refusal`() {
        approved(SCRIPT, BODY, CONTRACT)
        val outside = Files.writeString(root.resolve("outside.kts"), BODY)
        Files.writeString(root.resolve("outside.kts" + ParameterManifest.SUFFIX), ParameterManifest.encode(ParameterManifest.digestOf(outside), CONTRACT))

        for (name in listOf("../outside.kts", outside.toString(), "", "missing.kts")) {
            assertEquals(
                ServiceParameterDescribing.Refused(ServiceRefusal.ScriptNotAdmitted(name)),
                describe(name),
                "the name '$name' is one a start refuses",
            )
        }
        assertEquals(
            ServiceParameterDescribing.Refused(ServiceRefusal.ScriptNotAdmitted(SCRIPT)),
            ApprovedManifestDescriber(ServiceStartAdmission(null, null)).describe(SCRIPT),
            "a service without a root of scripts admits no script",
        )
    }

    private fun describe(script: String): ServiceParameterDescribing =
        ApprovedManifestDescriber(ServiceStartAdmission(scripts, null)).describe(script)

    /** Writes [body] as [name] and the manifest an operator would approve for it, with [contract]. */
    private fun approved(name: String, body: String, contract: List<ParameterSpec>) {
        val script = Files.writeString(scripts.resolve(name), body)
        Files.writeString(
            scripts.resolve(name + ParameterManifest.SUFFIX),
            ParameterManifest.encode(ParameterManifest.digestOf(script), contract),
        )
    }

    private fun assertRefusedWith(reason: String, describing: ServiceParameterDescribing) {
        val refused = assertInstanceOf(ServiceParameterDescribing.Refused::class.java, describing)
        val refusal = assertInstanceOf(ServiceRefusal.DescribeParametersRefused::class.java, refused.refusal)
        assertEquals(reason, refusal.reason, "the refusal names another reason: ${refusal.detail}")
    }

    private companion object {
        const val SCRIPT: String = "deploy.pipeline.kts"
        const val BODY: String = "pipeline { }\n"

        val CONTRACT: List<ParameterSpec> = listOf(
            ParameterSpec("target", "string"),
            ParameterSpec("region", "choice", "eu", listOf("eu", "us")),
        )
    }
}
