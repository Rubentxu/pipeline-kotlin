package dev.rubentxu.pipeline.v2.harness.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * H1 TODO 1: the [ChangedInput] ADT classifies a path by
 * *semantic effect*, not by substring. The classifier is
 * the source of truth; [ImpactResolver.isProductionPath] is a
 * thin derived view.
 *
 * Each test pins ONE property: a path is classified as
 * [ChangedInput.Production.SourceCode] (not BuildComposition,
 * not ServiceLoader, not Unknown). The test class exists so
 * a future change to the classifier (a new clause, a renamed
 * rule) breaks the test rather than silently re-routing a
 * path to a different variant.
 */
class ChangedInputClassifierTest {

    @Nested
    inner class ProductionClassifications {

        @Test
        fun `a kt source under src main is Production SourceCode`() {
            val classified = ChangedInputClassifier.classify(
                "v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt",
            )
            assertTrue(classified is ChangedInput.SourceCode) { "got $classified" }
        }

        @Test
        fun `a java source under src main is Production SourceCode`() {
            val classified = ChangedInputClassifier.classify(
                "v2/pipeline-application/src/main/java/dev/rubentxu/pipeline/v2/application/Main.java",
            )
            assertTrue(classified is ChangedInput.SourceCode) { "got $classified" }
        }

        @Test
        fun `a proto schema anywhere is Production SourceCode (wire contract)`() {
            // The previous `isProductionPath` accepted `.proto` at any
            // location. The ADT keeps the same semantic: a protobuf
            // change is a wire-contract change.
            val classified = ChangedInputClassifier.classify(
                "v2/pipeline-events/proto/event.proto",
            )
            assertTrue(classified is ChangedInput.SourceCode) { "got $classified" }
        }

        @Test
        fun `a gradle build script is Production BuildComposition`() {
            val classified = ChangedInputClassifier.classify("v2/pipeline-application/build.gradle.kts")
            assertTrue(classified is ChangedInput.BuildComposition) { "got $classified" }
        }

        @Test
        fun `a libs versions toml is Production BuildComposition`() {
            val classified = ChangedInputClassifier.classify(
                "v2/pipeline-application/gradle/libs.versions.toml",
            )
            assertTrue(classified is ChangedInput.BuildComposition) { "got $classified" }
        }

        @Test
        fun `a gradle properties at any depth is Production BuildComposition`() {
            val classified = ChangedInputClassifier.classify("v2/gradle.properties")
            assertTrue(classified is ChangedInput.BuildComposition) { "got $classified" }
        }

        @Test
        fun `a gradle wrapper jar is Production BuildComposition`() {
            val classified = ChangedInputClassifier.classify("v2/gradle/wrapper/gradle-wrapper.jar")
            assertTrue(classified is ChangedInput.BuildComposition) { "got $classified" }
        }

        @Test
        fun `a gradle wrapper properties is Production BuildComposition`() {
            val classified = ChangedInputClassifier.classify(
                "v2/gradle/wrapper/gradle-wrapper.properties",
            )
            assertTrue(classified is ChangedInput.BuildComposition) { "got $classified" }
        }

        @Test
        fun `a gradlew script is Production BuildComposition`() {
            val classified = ChangedInputClassifier.classify("v2/gradlew")
            assertTrue(classified is ChangedInput.BuildComposition) { "got $classified" }
        }

        @Test
        fun `a META-INF services resource is Production ServiceLoader`() {
            val classified = ChangedInputClassifier.classify(
                "v2/pipeline-application/src/main/resources/META-INF/services/" +
                    "dev.rubentxu.pipeline.v2.application.Steps",
            )
            assertTrue(classified is ChangedInput.ServiceLoader) { "got $classified" }
        }

        @Test
        fun `a release script under scripts is Production DistributionScript`() {
            val classified = ChangedInputClassifier.classify(
                "scripts/release-pipelinek.sh",
            )
            assertTrue(classified is ChangedInput.DistributionScript) { "got $classified" }
            val ds = classified as ChangedInput.DistributionScript
            assertEquals("release-pipelinek.sh", ds.scriptName)
        }

        @Test
        fun `an install script under scripts is Production DistributionScript`() {
            val classified = ChangedInputClassifier.classify(
                "scripts/install-pipelinek.sh",
            )
            assertTrue(classified is ChangedInput.DistributionScript) { "got $classified" }
        }
    }

    @Nested
    inner class NonProductionClassifications {

        /**
         * The three paths are the ones the gate named, verbatim, when it
         * refused a real change on 2026-10-18. They are not a
         * hand-picked sample: they are the gate's own output.
         *
         * The claim is that a repository-owned TEST SCRIPT is a test
         * source. It is not an unclassified production path, and the
         * difference is operational, not cosmetic: an
         * `UnknownProduction` makes the resolver refuse, so before this
         * rule the gate could not certify ANY change touching the
         * installer harness or the release publisher — including a
         * change to `scripts/validation-gate.py`, the gate's own entry
         * point. The remedy the refusal names ("add a catalogue row")
         * is nonsense for a test script: a test harness is not a
         * product contract, and binding it as one would buy a green by
         * writing a lie into the catalogue.
         *
         * `TestSource` is the honest variant and it already exists. The
         * new case is deliberately NOT `TestHarnessInternal`: BQ4 P0
         * removed a self-owned prefix exemption from the classifier
         * because a prefix exemption reads as coverage while being a
         * silent green, and reusing that variant for a second prefix
         * would reintroduce the defect under a second name. A naming
         * convention over an explicit case list is the opposite of a
         * per-file table, which ADR-PK-QV-002 D2 forbids.
         */
        @Test
        fun `a repository test script under scripts is NonProduction TestSource`() {
            for (path in listOf(
                "scripts/test_install_pipelinek.py",
                "scripts/test_release_publisher.sh",
                "scripts/test_release_publisher_assets.sh",
            )) {
                val classified = ChangedInputClassifier.classify(path)
                assertTrue(classified is ChangedInput.TestSource) {
                    "$path classified as $classified; the gate refuses an UnknownProduction, " +
                        "so a change to the installer harness or the release publisher cannot be " +
                        "certified at all."
                }
            }
        }

        /**
         * The control for the row above, and the reason the row cannot be
         * satisfied by widening the exemption. `scripts/` is not one
         * thing: the release and install entry points decide what ships
         * and stay Production. A rule that made the whole directory
         * non-production would turn the operator-facing half of the
         * release path into a silent green.
         */
        @Test
        fun `a release script is still Production when a test script beside it is not`() {
            assertTrue(
                ChangedInputClassifier.isProductionPath("scripts/release-pipelinek.sh"),
                "widening the test-script exemption swallowed a DistributionScript; the two " +
                    "scripts live in one directory and mean different things.",
            )
        }

        @Test
        fun `a kt source under v2 test-harness src main is Production SourceCode`() {
            // BQ4 P0 (HITO 1 "selección y evidencia vinculadas",
            // 2026-10-17): the previous shape classified any
            // path under `v2/test-harness/` as
            // `TestHarnessInternal` (NonProduction) and the
            // resolver ignored it. A defect in the harness's
            // production code was therefore a SILENT GREEN:
            // the gate saw no production change and discharged
            // on the plan. The audit's RC4 follow-up named
            // this the most expensive defect class in this
            // repository: the harness IS the test-harness
            // product, and changes to its `.kt` production
            // sources are product changes the gate MUST
            // verify. The exemption is removed; harness
            // `src/main/**/*.kt` falls through to the
            // standard `SourceCode` rule and is classified as
            // Production.
            val classified = ChangedInputClassifier.classify(
                "v2/test-harness/src/main/kotlin/dev/rubentxu/pipeline/v2/harness/plan/ImpactResolver.kt",
            )
            assertTrue(classified is ChangedInput.SourceCode) { "got $classified" }
        }

        @Test
        fun `a test source outside v2 test-harness is NonProduction TestSource`() {
            val classified = ChangedInputClassifier.classify(
                "v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/FooTest.kt",
            )
            assertTrue(classified is ChangedInput.TestSource) { "got $classified" }
        }

        @Test
        fun `a markdown doc is NonProduction Documentation`() {
            val classified = ChangedInputClassifier.classify("docs/v2/README.md")
            assertTrue(classified is ChangedInput.Documentation) { "got $classified" }
        }

        @Test
        fun `a path under docs is NonProduction Documentation`() {
            val classified = ChangedInputClassifier.classify("docs/v2/05-roadmap/ROADMAP.md")
            assertTrue(classified is ChangedInput.Documentation) { "got $classified" }
        }
    }

    @Nested
    inner class UnknownProductionClassifications {

        @Test
        fun `a v2 path with no matching rule is UnknownProduction with a v2 module proposal`() {
            // The H1 mandate: a path that has the surface of a
            // production change but the rules do not match is
            // refused, not silently dropped. The proposal is
            // derived from the path: `v2/foo/bar` proposes
            // `:foo` and `Unknown.foo`.
            val classified = ChangedInputClassifier.classify(
                "v2/pipeline-mystery-module/some/unknown/file.txt",
            )
            assertTrue(classified is ChangedInput.UnknownProduction) { "got $classified" }
            val unk = classified as ChangedInput.UnknownProduction
            // BQ4 v24 (typed proposal pair, 2026-10-17):
            // the proposal is now a single [Proposal]
            // carrier; the test reads the pair atomically
            // via `proposal.capability` / `proposal.owner`.
            assertEquals(":pipeline-mystery-module", unk.proposal.owner)
            assertEquals("Unknown.pipeline-mystery-module", unk.proposal.capability)
        }

        @Test
        fun `a top-level path outside v2 is UnknownProduction`() {
            val classified = ChangedInputClassifier.classify("weird-file.txt")
            assertTrue(classified is ChangedInput.UnknownProduction) { "got $classified" }
            val unk = classified as ChangedInput.UnknownProduction
            // BQ4 v24: the proposal is always non-null
            // (the [Proposal] carrier self-protects).
            assertNotNull(unk.proposal)
            assertNotNull(unk.proposal.owner)
            assertNotNull(unk.proposal.capability)
        }
    }

    @Nested
    inner class DerivedProductionProjection {

        // These tests pin the [ImpactResolver.isProductionPath]
        // backwards-compatibility projection. The H1 mandate
        // says the ADT is the source of truth; the Boolean
        // is a derived view. A future Production variant that
        // isProductionPath returns `false` for will fail
        // these tests.
        @Test
        fun `isProductionPath returns true for Production SourceCode`() {
            assertTrue(
                ChangedInputClassifier.isProductionPath(
                    "v2/pipeline-application/src/main/kotlin/Foo.kt",
                ),
            )
        }

        @Test
        fun `isProductionPath returns true for Production BuildComposition`() {
            assertTrue(
                ChangedInputClassifier.isProductionPath("v2/gradle.properties"),
            )
        }

        @Test
        fun `isProductionPath returns true for Production ServiceLoader`() {
            assertTrue(
                ChangedInputClassifier.isProductionPath(
                    "v2/pipeline-application/src/main/resources/META-INF/services/X",
                ),
            )
        }

        @Test
        fun `isProductionPath returns true for Production DistributionScript`() {
            assertTrue(
                ChangedInputClassifier.isProductionPath("scripts/release-pipelinek.sh"),
            )
        }

        @Test
        fun `isProductionPath returns true for Production SourceCode under v2 test-harness src main`() {
            // BQ4 P0 (HITO 1, 2026-10-17): the previous
            // shape had `isProductionPath returns false for
            // NonProduction TestHarnessInternal` pinning the
            // self-owned prefix exemption. The audit's
            // "selección y evidencia vinculadas" bullet
            // removed the exemption; harness `src/main/**/*.kt`
            // is now SourceCode (Production). The test name
            // is updated to match.
            assertTrue(
                ChangedInputClassifier.isProductionPath(
                    "v2/test-harness/src/main/kotlin/Foo.kt",
                ),
            )
        }

        @Test
        fun `isProductionPath returns false for NonProduction TestSource`() {
            assertFalse(
                ChangedInputClassifier.isProductionPath(
                    "v2/pipeline-application/src/test/kotlin/FooTest.kt",
                ),
            )
        }

        @Test
        fun `isProductionPath returns false for NonProduction Documentation`() {
            assertFalse(ChangedInputClassifier.isProductionPath("docs/v2/README.md"))
        }
    }

    @Nested
    inner class ResolverIntegration {

        @Test
        fun `an UnknownProduction path is refused with the proposed capability and owner`() {
            // The H1 mandate: avoid silent selection. A
            // `v2/mystery-module/...` path is refused with
            // the proposed capability/owner so the operator
            // can add a catalogue row or a classifier rule.
            val plan = ImpactResolver.resolve(
                listOf("v2/mystery-module/some/file.txt"),
            )
            assertTrue(plan is ValidationPlan.Refused) { "got $plan" }
            val refused = plan as ValidationPlan.Refused
            val unclassified = refused.reasons.filterIsInstance<SelectionRefusal.UnclassifiedPath>()
            assertTrue(unclassified.isNotEmpty()) { "got ${refused.reasons}" }
            val target = unclassified.first()
            assertEquals("v2/mystery-module/some/file.txt", target.path)
            // BQ4 v24 (typed proposal pair, 2026-10-17):
            // the proposal is now a single [Proposal]
            // carrier; the test reads the pair atomically
            // rather than via two parallel `proposed*`
            // fields.
            assertNotNull(target.proposal)
            assertEquals("Unknown.mystery-module", target.proposal?.capability)
            assertEquals(":mystery-module", target.proposal?.owner)
        }

        @Test
        fun `a real production path under v2 resolves to a Selected plan with capabilities`() {
            // Sanity: the typed ADT path still produces a
            // Selected plan when the catalogue binds the
            // path. This is the audit's original BQ1-11
            // invariant re-pinned on the new ADT.
            val plan = ImpactResolver.resolve(
                listOf("v2/gradle/wrapper/gradle-wrapper.properties"),
            )
            assertTrue(plan is ValidationPlan.Selected) { "got $plan" }
            val selected = plan as ValidationPlan.Selected
            assertTrue(
                selected.capabilities.contains("Build.composition"),
                "Build.composition must be selected, got ${selected.capabilities}",
            )
        }
    }
}
