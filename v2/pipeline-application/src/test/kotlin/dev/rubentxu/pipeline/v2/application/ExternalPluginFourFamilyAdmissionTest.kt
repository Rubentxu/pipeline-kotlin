package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.PluginAdmissionResult
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestRejection
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import java.nio.file.Path
import java.util.jar.JarFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * S6/I — ONE external artifact contributing all four families, admitted.
 *
 * ## What this class is really about
 *
 * Not "a plugin can have a directive". It is about a cross-check that was **green while proving
 * nothing**. `PluginAdmissionGate.admitThenLoad` collected only Step definitions and handed the
 * rest to [dev.rubentxu.pipeline.v2.domain.step.PluginManifestValidator] as their empty defaults,
 * so `missingDirectives` and `missingEvents` were always exactly what the manifest declared. The
 * measured consequence: a plugin that declared a Directive or an Event could not be admitted at
 * all, however faithfully it implemented them.
 *
 * Nothing caught it, because nothing exercised it. The four official plugins declare no directive
 * and no event, so `emptySet() - emptySet()` is empty and the row passed — the same defect shape
 * as the prohibition over a file that no longer exists, and the same lesson: a check whose green
 * comes from the absence of its subject has not run.
 *
 * ## Why the CONTROL row is the load-bearing one
 *
 * [the unmutated external plugin is admitted] would fail on the pre-fix gate — that is the whole
 * non-vacuity argument, and it is a fact about this artifact rather than about a mutated one. The
 * mutants then show each direction of each family actually refusing.
 */
@DisplayName("S6/I one external JAR contributes Step + Directive + Event + Capability, and admission cross-checks all four")
class ExternalPluginFourFamilyAdmissionTest {

    private val contributorClass = "example.uppercase.UppercaseContributor"

    private fun admit(jar: Path): PluginAdmissionResult =
        PluginArtifactFixture.withScopedLoader(jar) { loader ->
            PluginAdmissionGate.admitThenLoad(
                contributorClassName = contributorClass,
                classLoader = loader,
                runtimeVersion = SemVer(0, 47, 0),
                alreadyAdmitted = emptySet(),
                strict = true,
            )
        }

    /** The refusal detail, asserting on the RESULT being a cross-check refusal and not something else. */
    private fun crossCheckDetail(jar: Path): String {
        val result = admit(jar)
        assertTrue(
            result is PluginAdmissionResult.Refused,
            "the mutant must be refused; it was admitted, which means the cross-check did not run. Got $result",
        )
        val invalid = (result as PluginAdmissionResult.Refused).rejection
        assertTrue(
            invalid is PluginManifestRejection.InvalidManifest,
            "the refusal must come from the cross-check, not from the pre-load gate or a malformed " +
                "document; got $invalid",
        )
        return (invalid as PluginManifestRejection.InvalidManifest).detail
    }

    @Test
    @DisplayName("CONTROL: the unmutated four-family plugin is admitted")
    fun theUnmutatedExternalPluginIsAdmitted() {
        val result = admit(PluginArtifactFixture.builtExternalPluginJar())

        assertTrue(
            result is PluginAdmissionResult.Admitted,
            "the plugin declares exactly what it contributes, so admission must succeed. If this " +
                "fails with missingDirectives/missingEvents, the gate is comparing the declaration " +
                "against an EMPTY implementation set rather than the real one. Got $result",
        )
    }

    @Test
    @DisplayName("the artifact really carries a manifest and all four ServiceLoader descriptors")
    fun theArtifactCarriesEveryFamilyDescriptor() {
        // Non-vacuity of the premise. Without this, the control above could pass for the wrong
        // reason — an artifact that declares nothing at all is trivially consistent with
        // contributing nothing, and the rows below would be mutants of a subject that was empty.
        JarFile(PluginArtifactFixture.builtExternalPluginJar().toFile()).use { jar ->
            val names = jar.entries().toList().map { it.name }.toSet()

            for (required in listOf(
                "META-INF/pipelinek/plugin-manifest.json",
                "META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor",
                "META-INF/services/dev.rubentxu.pipeline.v2.domain.directive.DirectiveContributor",
                "META-INF/services/dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor",
                "META-INF/services/dev.rubentxu.pipeline.v2.domain.step.RuntimeCapabilityContributor",
            )) {
                assertTrue(names.contains(required), "the plugin JAR does not carry $required; found $names")
            }
        }
    }

    @Test
    @DisplayName("a Directive the manifest declares and the plugin does NOT contribute is refused")
    fun declaredButAbsentDirectiveIsRefused() {
        val jar = PluginArtifactFixture.externalJarWithRewrittenManifest { text ->
            text.replace(
                """"directives": ["example.uppercase.casedOn"]""",
                """"directives": ["example.uppercase.casedOn", "example.uppercase.ghost"]""",
            )
        }

        val detail = crossCheckDetail(jar)
        assertTrue(
            detail.contains("example.uppercase.ghost"),
            "the refusal must NAME the Directive that has no contributor; got '$detail'",
        )
        assertTrue(
            detail.contains("declared Directives with no contributor"),
            "the refusal must state the direction of the mismatch; got '$detail'",
        )
    }

    @Test
    @DisplayName("a Directive the plugin contributes and the manifest does NOT declare is refused")
    fun contributedButUndeclaredDirectiveIsRefused() {
        val jar = PluginArtifactFixture.externalJarWithRewrittenManifest { text ->
            val rewritten = text.replace(
                """"directives": ["example.uppercase.casedOn"],""",
                """"directives": [],""",
            )
            assertTrue(rewritten != text, "the manifest no longer declares example.uppercase.casedOn")
            rewritten
        }

        val detail = crossCheckDetail(jar)
        assertTrue(
            detail.contains("example.uppercase.casedOn"),
            "the refusal must NAME the contributed Directive that was never declared; got '$detail'",
        )
        assertTrue(
            detail.contains("contributed Directives absent from the manifest"),
            "the refusal must state the direction of the mismatch; got '$detail'",
        )
    }

    @Test
    @DisplayName("an Event the manifest declares and the plugin does NOT contribute is refused")
    fun declaredButAbsentEventIsRefused() {
        val jar = PluginArtifactFixture.externalJarWithRewrittenManifest { text ->
            text.replace(
                """"events": ["example.uppercase.applied"]""",
                """"events": ["example.uppercase.applied", "example.uppercase.never-emitted"]""",
            )
        }

        val detail = crossCheckDetail(jar)
        assertTrue(
            detail.contains("example.uppercase.never-emitted"),
            "the refusal must NAME the Event kind nothing contributes; got '$detail'",
        )
        assertTrue(
            detail.contains("declared Events with no contributor"),
            "the refusal must state the direction of the mismatch; got '$detail'",
        )
    }

    @Test
    @DisplayName("a contributed Event the manifest does NOT declare is refused")
    fun contributedButUndeclaredEventIsRefused() {
        val jar = PluginArtifactFixture.externalJarWithRewrittenManifest { text ->
            val rewritten = text.replace(
                """"events": ["example.uppercase.applied"],""",
                """"events": [],""",
            )
            assertTrue(rewritten != text, "the manifest no longer declares example.uppercase.applied")
            rewritten
        }

        val detail = crossCheckDetail(jar)
        assertTrue(
            detail.contains("example.uppercase.applied"),
            "the refusal must NAME the contributed Event that was never declared; got '$detail'",
        )
        assertTrue(
            detail.contains("contributed Events absent from the manifest"),
            "the refusal must state the direction of the mismatch; got '$detail'",
        )
    }

    /**
     * BLOCK 1-J — the Step that was ADDED to prove a new construct needs no core change.
     *
     * These two rows exist because the no-core-change claim is a claim about a DIFF, and a diff is
     * not something a test can re-derive. What a test CAN hold is the two halves around it: the new
     * Step is in the cross-check like any other, and it is reachable through the open registry with
     * both of its declared capabilities. The empty core diff is recorded in the receipt, measured
     * on the tree.
     */
    @Test
    @DisplayName("the announcedCased Step added in 1-J is cross-checked like every other")
    fun theNewlyAddedStepIsCrossChecked() {
        val jar = PluginArtifactFixture.externalJarWithRewrittenManifest { text ->
            val rewritten = text.replace(
                """{"stepKey": "example.uppercase.announcedCased", "declaredCapabilities": ["example.uppercase.case-table", "plugin.event-emission"]},""",
                "",
            )
            assertTrue(rewritten != text, "the manifest no longer declares example.uppercase.announcedCased")
            rewritten
        }

        val detail = crossCheckDetail(jar)
        assertTrue(
            detail.contains("example.uppercase.announcedCased"),
            "a Step added by an external plugin must enter the cross-check through the same door as " +
                "every other one; if this is refused for some other reason the seam is not open. Got '$detail'",
        )
        assertTrue(
            detail.contains("implemented Steps absent from the manifest"),
            "the refusal must name the direction of the mismatch, and here the direction is fixed: " +
                "this mutant removes the DECLARATION while the implementation stays, so the Step is " +
                "contributed and undeclared. Got '$detail'",
        )
    }

    @Test
    @DisplayName("the newly added Step reaches the registry with BOTH of its capabilities")
    fun theNewlyAddedStepIsRegisteredWithBothCapabilities() {
        @Suppress("UNCHECKED_CAST")
        val definitions = PluginArtifactFixture.withScopedLoader(
            PluginArtifactFixture.builtExternalPluginJar(),
        ) { loader ->
            val contributorClass = loader.loadClass("example.uppercase.UppercaseContributor")
            val contributor = contributorClass.getDeclaredConstructor().newInstance() as
                dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
            contributor.definitions().toList()
        }

        val announced = definitions.singleOrNull {
            it.contract.key.value == "example.uppercase.announcedCased"
        }
        assertTrue(announced != null, "the Step added in BLOCK 1-J must be discoverable by the contributor")

        assertEquals(
            setOf("example.uppercase.case-table", "plugin.event-emission"),
            announced!!.contract.requiredCapabilities.map { it.key }.toSet(),
            "both seams must reach the registry: one this plugin SUPPLIES and one only the HOST has. " +
                "A Step that declared only one would prove composition, not composition of owners.",
        )
    }

    /**
     * The capability family has NO manifest-vs-contributor direction, and that is a measured gap,
     * not a design choice — recorded here so it is not rediscovered as if it were fine.
     *
     * ## What was measured
     *
     * The manifest's top-level `capabilities` set is a DEMAND set: it must equal the union of the
     * Steps' `requiredCapabilities`. It is checked by [dev.rubentxu.pipeline.v2.domain.step.PluginManifestValidator],
     * which every plugin must OPT IN to calling from its own `registrations()`. The admission gate
     * does not check it.
     *
     * The consequence was produced, not predicted: a mutant that adds `example.uppercase.unused` to
     * the top-level set — a capability no Step requires — is ADMITTED. This plugin does not call
     * the validator, because it is not required to.
     *
     * Closing it in the gate is NOT done here, on purpose. The gate would then reject this very
     * repository's `SentinelPluginArtifact`, whose manifest declares `test.sentinel` with no Step
     * because a bare marker class cannot provide one — a class compiled from a source string under
     * a platform-parent loader cannot implement a PipelineK SPI, because `PluginStepId`,
     * `StepCapability` and `EncodedStepValue` are `@JvmInline`. Fixing that fixture is a separate
     * piece of work about the sentinel, not about BLOCK 1-I, and smuggling it in would make this
     * commit's evidence a claim about something it did not measure.
     *
     * So the gap is stated instead of half-closed. Whoever takes it should first decide whether the
     * gate or the per-plugin validator owns the comparison; both owning it is the duplication this
     * repository has been removing block after block.
     */
    @Test
    @DisplayName("KNOWN GAP: an unbacked top-level capability is currently ADMITTED, and this records that")
    fun unbackedTopLevelCapabilityIsCurrentlyAdmitted() {
        val jar = PluginArtifactFixture.externalJarWithRewrittenManifest { text ->
            val rewritten = text.replace(
                """"capabilities": ["example.uppercase.case-table", "plugin.event-emission"]""",
                """"capabilities": ["example.uppercase.case-table", "plugin.event-emission", "example.uppercase.unused"]""",
            )
            assertTrue(rewritten != text, "the manifest no longer declares the capability set")
            rewritten
        }

        val result = admit(jar)

        // Characterisation, NOT a certification, and deliberately so. The row pins the gap shut so
        // that the day the gate starts checking capabilities, THIS row fails and the fix announces
        // itself in the failure message instead of being discovered later as an unexplained break.
        assertTrue(
            result is PluginAdmissionResult.Admitted,
            "The admission gate USED to refuse an unbacked top-level capability and no longer does. " +
                "This row is a characterisation of a known gap: PluginManifestValidator checks the " +
                "capability set but every plugin opts into calling it, and the gate does not. Update " +
                "it to assert refusal once the gate takes ownership of that comparison. Got $result",
        )
    }
}
