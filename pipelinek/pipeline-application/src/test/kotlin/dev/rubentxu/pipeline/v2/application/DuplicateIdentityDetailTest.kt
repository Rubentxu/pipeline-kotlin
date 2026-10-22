package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The duplicate-identity refusal, and the artifact it used to leave unnamed.
 *
 * Two artifacts can claim the same plugin identity, and only one of them can stay in the
 * composition. The refusal names the artifact that was REJECTED, because that is the path the
 * operator typed or found. The artifact that WON the name used to be named nowhere: the published
 * refusal vocabulary carries the identity twice and no artifact at all, so "this one was refused
 * because another one already has this name" left the operator without the one thing they could
 * act on.
 *
 * The guarantee here is narrow and it is the whole point: the refusal says WHICH artifact stays.
 * It is checked over values in memory, with no JAR, no classloader and no process. What this test
 * cannot say is that the sentence reaches the operator's console; that is what the executable
 * examples are for, and they ask it of the shipped binary.
 */
class DuplicateIdentityDetailTest {

    private val identity = "v1:plugin:example-uppercase-plugin/plugin/uppercase"
    private val incumbent = Path.of("/plugins/legit.jar")
    private val other = Path.of("/plugins/other.jar")

    private fun duplicateIdentity(): PluginManifestRejection.DuplicateIdentity =
        PluginManifestRejection.DuplicateIdentity(identity, identity)

    @Test
    @DisplayName("the refusal names the artifact that keeps the identity")
    fun theRefusalNamesTheArtifactThatKeepsTheIdentity() {
        val detail = PreLoadPluginAdmission.duplicateIdentityDetail(
            rejection = duplicateIdentity(),
            admittedByIdentity = mapOf(identity to incumbent),
        )

        assertNotNull(detail, "the refusal must name the artifact that keeps the identity")
        assertTrue(
            detail!!.contains(incumbent.toString()),
            "the detail must carry the path of the artifact that stays: $detail",
        )
    }

    @Test
    @DisplayName("the refusal names the artifact that stays and no other")
    fun theRefusalNamesTheArtifactThatStaysAndNoOther() {
        val detail = PreLoadPluginAdmission.duplicateIdentityDetail(
            rejection = duplicateIdentity(),
            admittedByIdentity = mapOf(identity to incumbent),
        )

        assertNotNull(detail, "the refusal must name the artifact that keeps the identity")
        assertTrue(
            !detail!!.contains(other.toString()),
            "the detail must not name an artifact that is not in the composition: $detail",
        )
    }

    @Test
    @DisplayName("an identity with no known artifact invents none")
    fun anIdentityWithNoKnownArtifactInventsNone() {
        // The refusal still stands; it simply has no incumbent to name, and saying nothing is
        // truer than guessing a path the runtime never saw.
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
