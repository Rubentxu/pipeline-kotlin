package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepCatalogue
import dev.rubentxu.pipeline.v2.domain.step.StepCatalogueEntry
import dev.rubentxu.pipeline.v2.runtime.AgentDirectiveDefinition
import dev.rubentxu.pipeline.v2.runtime.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.runtime.WhenDirectiveDefinition
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The entries of the catalogue that publish a Jenkins equivalent whose
 * behaviour here is NOT the behaviour of Jenkins.
 *
 * ## The guarantee under trial
 *
 * That every entry of this family DECLARES the difference, and that the text
 * it declares says the fact the reader needs: what the equivalent of Jenkins
 * does, and what this product does instead.
 *
 * ## Why the guarantee is about the PUBLICATION
 *
 * Because the behaviour of the five forms is measured elsewhere, by the rows
 * that run them. This file is the surface where the reader of the catalogue
 * asks, and a reader who comes from Jenkins reads the equivalent of Jenkins
 * in the `jenkins` field and assumes the rest. A form that behaves
 * differently and says nothing is the defect this file is about.
 *
 * ## Why the vocabulary is fixed and the sentence is not
 *
 * Because a row that froze the whole sentence would fail every time the text
 * is reworded, and a row that froze nothing would pass with a text that says
 * something else. The words each entry has to carry are fixed —they are the
 * facts— and the words around them are free.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that the catalogue is complete, nor that every entry of
 * it declares a difference: some entries are the same in Jenkins and here.
 * It affirms that the entries of THIS family do.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class InheritedPromiseCatalogueTest {

    /**
     * The entry of `timestamps` declares that the scope decorates nothing.
     */
    @Test
    @DisplayName("the entry core.timestamps declares that the scope decorates nothing")
    fun theEntryCoreTimestampsDeclaresThatTheScopeDecoratesNothing() {
        declaresItsDifference(
            key = "core.timestamps",
            jenkinsSurface = "timestamps",
            vocabulary = listOf("TimestampsEntered", "TimestampsExited", "decorates nothing"),
        )
    }

    /**
     * The entry of `milestone` declares that the ordinal is observed within
     * the run.
     */
    @Test
    @DisplayName("the entry core.milestone declares that the ordinal is observed within the run")
    fun theEntryCoreMilestoneDeclaresThatTheOrdinalIsObservedWithinTheRun() {
        declaresItsDifference(
            key = "core.milestone",
            jenkinsSurface = "milestone",
            vocabulary = listOf("between builds", "within the run"),
        )
    }

    /**
     * The entry of `publishHTML` declares the limit of the retention of the
     * reports.
     */
    @Test
    @DisplayName("the entry core.publishHTML declares the limit of the retention of the reports")
    fun theEntryCorePublishHtmlDeclaresTheLimitOfTheRetentionOfTheReports() {
        declaresItsDifference(
            key = "core.publishHTML",
            jenkinsSurface = "publishHTML",
            vocabulary = listOf("keepAll", "per run"),
        )
    }

    /**
     * The entry of `input` declares that the submitter is an attribution and
     * not an authorization.
     */
    @Test
    @DisplayName("the entry core.input declares that the submitter is not an authorization")
    fun theEntryCoreInputDeclaresThatTheSubmitterIsNotAnAuthorization() {
        declaresItsDifference(
            key = "core.input",
            jenkinsSurface = "input",
            vocabulary = listOf("attribution", "not an authorization boundary"),
        )
    }

    /**
     * The entry of `waitUntil` declares that the predicate is the outcome of
     * the steps of the body.
     */
    @Test
    @DisplayName("the entry core.waitUntil declares that the predicate is the outcome of the steps")
    fun theEntryCoreWaitUntilDeclaresThatThePredicateIsTheOutcomeOfTheSteps() {
        declaresItsDifference(
            key = "core.waitUntil",
            jenkinsSurface = "waitUntil",
            vocabulary = listOf("outcome of the steps", "refused"),
        )
    }

    // --- the fixtures ------------------------------------------------------

    /**
     * Requires that the entry declares its difference, and that the text it
     * declares carries every word of the vocabulary.
     */
    private fun declaresItsDifference(key: String, jenkinsSurface: String, vocabulary: List<String>) {
        val entry = entries().firstOrNull { candidate -> candidate.key == key }
            ?: fail("the catalogue has no entry for the key `$key`: the reader of the " +
                "equivalent of Jenkins would find nothing to read")

        assertEquals(
            jenkinsSurface,
            entry.jenkinsSurface,
            "the entry `$key` publishes another equivalent of Jenkins: the reader is pointed " +
                "at a step that is not the one this row is about",
        )

        val divergence = entry.jenkinsDivergence
        assertTrue(
            divergence.isNotBlank(),
            "the entry `$key` publishes NO difference with the equivalent of Jenkins: a " +
                "reader who comes from Jenkins reads the equivalent and assumes the rest",
        )
        vocabulary.forEach { word ->
            assertTrue(
                divergence.contains(word),
                "the entry `$key` declares a difference that does not say `$word`: the text " +
                    "the reader gets is `$divergence`",
            )
        }
    }

    /** The catalogue of the core composition, with no plugin of the installation in it. */
    private fun entries(): List<StepCatalogueEntry> =
        StepCatalogue.entries(CoreStepRegistryFactory.registry(), coreDirectives())

    /** The registry of the core directives. */
    private fun coreDirectives(): DirectiveRegistry = DirectiveRegistry.Builder().apply {
        add(ErasedDirectiveDefinition(WhenDirectiveDefinition()))
        add(ErasedDirectiveDefinition(AgentDirectiveDefinition()))
    }.build()
}
