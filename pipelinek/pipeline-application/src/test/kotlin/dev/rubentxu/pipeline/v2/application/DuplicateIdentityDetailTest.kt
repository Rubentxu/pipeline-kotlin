package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The duplicate-identity refusal, the artifact it used to leave unnamed, and the claim it must
 * not make.
 *
 * Two artifacts can claim the same plugin identity. The refusal names the artifact that was
 * REJECTED, because that is the path the operator typed or found. The artifact that claimed the
 * identity FIRST used to be named nowhere: the published refusal vocabulary carries the identity
 * twice and no artifact at all, so "this one was refused because another one already has this
 * name" left the operator without the one thing they could act on.
 *
 * ## What the detail says, and what it must not say
 *
 * One refusal stops the WHOLE composition — this one is raised while the artifacts are being
 * pre-admitted, before there is a composition at all. So the detail names the artifact that had
 * already claimed the identity and says the composition is refused as a whole. It must NOT say
 * that the earlier artifact "stays in the composition": that would teach the operator their
 * pipeline is running with their plugin in it, and nothing of the sort happens.
 *
 * The guarantee here is narrow and it is the whole point: the refusal says WHICH artifact had
 * claimed the identity, and does not claim the run continues. It is checked over values in
 * memory, with no JAR, no classloader and no process. What this test cannot say is that the
 * sentence reaches the operator's console; that is what the executable examples are for, and they
 * ask it of the shipped binary.
 */
class DuplicateIdentityDetailTest {

    private val identity = "v1:plugin:example-uppercase-plugin/plugin/uppercase"
    private val incumbent = Path.of("/plugins/legit.jar")
    private val other = Path.of("/plugins/other.jar")

    private fun duplicateIdentity(): PluginManifestRejection.DuplicateIdentity =
        PluginManifestRejection.DuplicateIdentity(identity, identity)

    @Test
    @DisplayName("the refusal names the artifact that had already claimed the identity")
    fun theRefusalNamesTheArtifactThatClaimedTheIdentityFirst() {
        val detail = PreLoadPluginAdmission.duplicateIdentityDetail(
            rejection = duplicateIdentity(),
            admittedByIdentity = mapOf(identity to incumbent),
        )

        assertNotNull(detail, "the refusal must name the artifact that claimed the identity first")
        assertTrue(
            detail!!.contains(incumbent.toString()),
            "the detail must carry the path of the artifact that claimed the identity: $detail",
        )
    }

    @Test
    @DisplayName("the refusal does not claim the artifact stays in the composition")
    fun theRefusalDoesNotClaimTheArtifactStaysInTheComposition() {
        val detail = PreLoadPluginAdmission.duplicateIdentityDetail(
            rejection = duplicateIdentity(),
            admittedByIdentity = mapOf(identity to incumbent),
        )

        assertNotNull(detail, "the refusal must name the artifact that claimed the identity first")
        assertFalse(
            detail!!.contains("stays in the composition"),
            "a refusal stops the whole composition, so nothing stays in it: $detail",
        )
        assertTrue(
            detail.contains("the whole composition is refused"),
            "the detail must say that the composition is refused as a whole: $detail",
        )
    }

    @Test
    @DisplayName("the refusal names the artifact that claimed the identity and no other")
    fun theRefusalNamesTheArtifactThatClaimedTheIdentityAndNoOther() {
        val detail = PreLoadPluginAdmission.duplicateIdentityDetail(
            rejection = duplicateIdentity(),
            admittedByIdentity = mapOf(identity to incumbent),
        )

        assertNotNull(detail, "the refusal must name the artifact that claimed the identity first")
        assertFalse(
            detail!!.contains(other.toString()),
            "the detail must not name an artifact the runtime never saw: $detail",
        )
    }

    @Test
    @DisplayName("an identity with no known artifact invents none")
    fun anIdentityWithNoKnownArtifactInventsNone() {
        // The refusal still stands; it simply has no earlier artifact to name, and saying nothing
        // is truer than guessing a path the runtime never saw.
        val detail = PreLoadPluginAdmission.duplicateIdentityDetail(
            rejection = duplicateIdentity(),
            admittedByIdentity = emptyMap(),
        )

        assertNull(detail)
    }

    @Test
    @DisplayName("a refusal that is not a duplicate identity names no artifact")
    fun aRefusalThatIsNotADuplicateIdentityNamesNoArtifact() {
        val detail = PreLoadPluginAdmission.duplicateIdentityDetail(
            rejection = PluginManifestRejection.MalformedDocument("the document is not a manifest"),
            admittedByIdentity = mapOf(identity to incumbent),
        )

        assertNull(detail)
    }
}
