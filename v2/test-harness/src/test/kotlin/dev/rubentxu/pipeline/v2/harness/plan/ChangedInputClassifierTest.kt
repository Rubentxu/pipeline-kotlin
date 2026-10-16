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

        @Test
        fun `a path under v2 test-harness is NonProduction TestHarnessInternal`() {
            // The self-owned prefix is the audit's exemption:
            // a defect in the harness makes the harness
            // over-report, which is noisy but safe.
            val classified = ChangedInputClassifier.classify(
                "v2/test-harness/src/main/kotlin/dev/rubentxu/pipeline/v2/harness/plan/ImpactResolver.kt",
            )
            assertTrue(classified is ChangedInput.TestHarnessInternal) { "got $classified" }
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
            assertEquals(":pipeline-mystery-module", unk.proposedOwner)
            assertEquals("Unknown.pipeline-mystery-module", unk.proposedCapability)
        }

        @Test
        fun `a top-level path outside v2 is UnknownProduction`() {
            val classified = ChangedInputClassifier.classify("weird-file.txt")
            assertTrue(classified is ChangedInput.UnknownProduction) { "got $classified" }
            val unk = classified as ChangedInput.UnknownProduction
            assertNotNull(unk.proposedOwner)
            assertNotNull(unk.proposedCapability)
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
        fun `isProductionPath returns false for NonProduction TestHarnessInternal`() {
            assertFalse(
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
            assertEquals("Unknown.mystery-module", target.proposedCapability)
            assertEquals(":mystery-module", target.proposedOwner)
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
