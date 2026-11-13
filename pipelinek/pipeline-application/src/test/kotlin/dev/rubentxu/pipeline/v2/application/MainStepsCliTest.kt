package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.StepDescriptorRegistry
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveRegistry
import dev.rubentxu.pipeline.v2.domain.directive.ErasedDirectiveDefinition
import dev.rubentxu.pipeline.v2.domain.step.JenkinsForms
import dev.rubentxu.pipeline.v2.domain.step.StepCatalogue
import dev.rubentxu.pipeline.v2.domain.step.StepCatalogueEntry
import dev.rubentxu.pipeline.v2.runtime.AgentDirectiveDefinition
import dev.rubentxu.pipeline.v2.runtime.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.runtime.WhenDirectiveDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/**
 * The catalogue of steps and directives, and the two verbs that publish it.
 *
 * ## The guarantee under trial
 *
 * That the catalogue answers about the composition this process would run, and
 * that what it publishes is a document a consumer can parse: the facts are read
 * from the descriptor and the codec, the identity is the key the runtime
 * resolves, and the JSON is JSON.
 *
 * ## Why this instrument
 *
 * The catalogue is a projection of the registries, so most of it can be
 * checked against the core registry alone — no disk, no classloader, no
 * process. The two rows that run the verbs pay for the whole path, including
 * the admission of the plugins of the installation, and they are the ones that
 * prove the published document is parseable.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class MainStepsCliTest {

    @Test
    fun `the catalogue carries one entry per key of the composition`() {
        val entries = coreEntries()
        val registry = coreRegistry()
        val declaredOnly = StepDescriptorRegistry.standard().keys().count { registry.definition(it) == null }

        assertEquals(
            registry.keys().size + coreDirectives().keys().size + declaredOnly,
            entries.size,
            "every key of the registries, and every declared family, is an entry of the catalogue",
        )
        assertEquals(
            entries.map { it.key }.sorted(),
            entries.map { it.key },
            "the catalogue is an index: its order does not depend on the order the keys were registered in",
        )
    }

    @Test
    fun `the catalogue carries the families the DSL writes and the composition does not register`() {
        val entries = coreEntries()

        val declared = StepDescriptorRegistry.standard().keys().map { it.value }
        val missing = declared.filter { key -> entries.none { it.key == key } }
        assertEquals(
            emptyList<String>(),
            missing,
            "every declared family is an entry of the catalogue, plugin or not",
        )
        val retry = entries.first { it.key == "core.retry" }
        val step = assertInstanceOf(StepCatalogueEntry.Step::class.java, retry, "core.retry is a step")
        assertEquals(
            "declared",
            step.body.kind,
            "core.retry takes a body: it is a scope, and the catalogue says so",
        )
        assertEquals(
            "RETRYING",
            step.body.policy,
            "the body of core.retry executes in the shape the declaration names",
        )
    }

    @Test
    fun `an entry carries the facts the descriptor of its step declares`() {
        val sh = coreEntries().first { it.key == "core.sh" }

        val step = assertInstanceOf(StepCatalogueEntry.Step::class.java, sh, "core.sh is a step")
        assertEquals("sh", step.name, "the name is the one the descriptor declares")
        assertEquals("core", step.provider, "the provider is the one the descriptor declares")
        assertEquals(listOf("EXECUTES_SUBPROCESS"), step.effects, "the effects are the declared ones")
        assertEquals("RERUN", step.replayPolicy, "the replay policy is the declared one")
        assertEquals(
            "ExternalSubprocess",
            step.recoveryPolicy,
            "the recovery policy is the declared one",
        )
        assertEquals(
            listOf("shellOperations"),
            step.requiredCapabilities,
            "the capabilities are the ones the contract requires",
        )
    }

    @Test
    fun `the document parses as JSON and names its schema`() {
        val document = MainStepsCli.document(coreEntries())

        val parsed = Json.parseToJsonElement(document.toString()).jsonObject
        assertEquals(
            "pipelinek.steps/3",
            parsed["schema"]?.jsonPrimitive?.content,
            "the document names the schema a consumer parses it by",
        )
        assertEquals(
            coreEntries().size,
            parsed["entries"]?.jsonArray?.size,
            "the document carries one entry per key of the composition",
        )
    }

    @Test
    fun `the document carries the global divergences, four of the language and five of the product`() {
        val document = MainStepsCli.document(coreEntries())

        val parsed = Json.parseToJsonElement(document.toString()).jsonObject
        val divergences = parsed["divergences"]?.jsonArray ?: fail("the document carries the divergences")
        assertEquals(
            9,
            divergences.size,
            "the document carries the nine differences that are the same for every script",
        )
        val byScope = divergences.groupBy { it.jsonObject["scope"]?.jsonPrimitive?.content.orEmpty() }
        assertEquals(
            listOf("language", "product"),
            byScope.keys.sorted(),
            "a divergence says whether the language or the product imposes it",
        )
        assertEquals(4, byScope.getValue("language").size, "four of the differences the language imposes")
        assertEquals(5, byScope.getValue("product").size, "five of the differences the product decides")
        assertTrue(
            divergences.all { it.jsonObject["reason"]?.jsonPrimitive?.content?.isNotBlank() == true },
            "every divergence says WHY the two products differ",
        )
    }

    @Test
    fun `the document carries the forms of Jenkins and what the compiler prints for each one`() {
        val document = MainStepsCli.document(coreEntries())

        val parsed = Json.parseToJsonElement(document.toString()).jsonObject
        val forms = parsed["forms"]?.jsonArray ?: fail("the document carries the forms")
        assertEquals(
            JenkinsForms.global().size,
            forms.size,
            "the document carries one form for every shape the recognition can name",
        )
        assertEquals(
            JenkinsForms.global().map { form -> form.shape.name.lowercase() },
            forms.map { form -> form.jsonObject["shape"]?.jsonPrimitive?.content },
            "the forms of the document are the ones the code declares, in the same order",
        )
        assertTrue(
            forms.all { form ->
                val entry = form.jsonObject
                entry["jenkinsForm"]?.jsonPrimitive?.content?.isNotBlank() == true &&
                    entry["pipelineKForm"]?.jsonPrimitive?.content?.isNotBlank() == true &&
                    entry["reason"]?.jsonPrimitive?.content?.isNotBlank() == true &&
                    entry["suggestion"]?.jsonPrimitive?.content?.isNotBlank() == true
            },
            "every form says how Jenkins writes it, how this product writes it, why and what the compiler prints",
        )
    }

    @Test
    fun `every entry of the catalogue declares its equivalent in Jenkins or the divergence that says why it has none`() {
        val entries = installedEntries()

        val blank = entries.filter { entry ->
            entry["jenkinsSurface"]?.jsonPrimitive?.content.isNullOrBlank() &&
                entry["jenkinsDivergence"]?.jsonPrimitive?.content.isNullOrBlank()
        }
        assertEquals(
            emptyList<String>(),
            blank.map { it["key"]?.jsonPrimitive?.content },
            "an entry with no equivalent in Jenkins says WHY it has none: an empty pair of facts " +
                "is a statement the reader cannot tell from an oversight",
        )
    }

    @Test
    fun `the card of an entry names its equivalent in Jenkins and its divergence`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()

        val code = MainDescribeCli.main(
            arrayOf("core.sh"),
            PrintStream(out, true, Charsets.UTF_8.name()),
            PrintStream(err, true, Charsets.UTF_8.name()),
        )

        assertEquals(CliExit.SUCCESS.code, code, "the verb answers with success: ${err.toString()}")
        val card = out.toString(Charsets.UTF_8.name())
        assertTrue(card.contains("jenkins"), "the card names the equivalent in Jenkins: $card")
        assertTrue(card.contains("divergence"), "the card names the divergence of the entry: $card")
    }

    @Test
    fun `the index has one line per entry of the document`() {
        val entries = coreEntries()

        val index = MainStepsCli.index(entries)

        assertEquals(entries.size, index.size, "every entry has a line in the index")
        assertTrue(
            index.any { it.contains("core.sh") },
            "the index names the entries by the key the runtime resolves",
        )
    }

    @Test
    fun `a key names the entry that carries it`() {
        val found = resolve("core.sh", coreEntries())

        val resolution = assertInstanceOf(StepResolution.Found::class.java, found, "the key names one entry")
        assertEquals("core.sh", resolution.entry.key, "the entry is the one the key names")
    }

    @Test
    fun `a name names the entry that carries it`() {
        val found = resolve("sh", coreEntries())

        val resolution = assertInstanceOf(StepResolution.Found::class.java, found, "the name names one entry")
        assertEquals("core.sh", resolution.entry.key, "the name 'sh' is the name of core.sh")
    }

    @Test
    fun `a name two entries carry is refused with both of them named`() {
        val shared = listOf(
            stepEntry(key = "core.sh", name = "sh"),
            stepEntry(key = "other.sh", name = "sh"),
        )

        val refused = resolve("sh", shared)

        val resolution = assertInstanceOf(StepResolution.Refused::class.java, refused, "the name names two entries")
        val error = assertInstanceOf(CliError.UnknownStep::class.java, resolution.error, "the refusal is typed")
        assertTrue(error.ambiguous, "a name that names two entries is not an unknown name")
        assertEquals(listOf("core.sh", "other.sh"), error.keys, "both of the entries it names are named")
    }

    @Test
    fun `a name nobody carries is refused with the entries that come close`() {
        val refused = resolve("slee", coreEntries())

        val resolution = assertInstanceOf(StepResolution.Refused::class.java, refused, "no entry carries that name")
        val error = assertInstanceOf(CliError.UnknownStep::class.java, resolution.error, "the refusal is typed")
        assertTrue(!error.ambiguous, "a name nobody carries is not ambiguous")
        assertEquals(listOf("core.sleep"), error.keys, "the entries that come close are named")
    }

    @Test
    fun `the verb publishes a document a consumer can parse`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()

        val code = MainStepsCli.main(
            arrayOf("--json"),
            PrintStream(out, true, Charsets.UTF_8.name()),
            PrintStream(err, true, Charsets.UTF_8.name()),
        )

        assertEquals(CliExit.SUCCESS.code, code, "the verb answers with success: ${err.toString()}")
        val parsed = Json.parseToJsonElement(out.toString(Charsets.UTF_8.name())).jsonObject
        assertEquals(
            "pipelinek.steps/3",
            parsed["schema"]?.jsonPrimitive?.content,
            "what the verb publishes is the document the schema names",
        )
    }

    @Test
    fun `the verb reads the card of the entry the name names`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()

        val code = MainDescribeCli.main(
            arrayOf("core.sh"),
            PrintStream(out, true, Charsets.UTF_8.name()),
            PrintStream(err, true, Charsets.UTF_8.name()),
        )

        assertEquals(CliExit.SUCCESS.code, code, "the verb answers with success: ${err.toString()}")
        assertTrue(
            out.toString(Charsets.UTF_8.name()).startsWith("core.sh"),
            "the card names the entry it is the card of",
        )
    }

    @Test
    fun `a name nobody carries and a name two entries carry are refused in different words`() {
        val unknown = CliError.UnknownStep("shell", emptyList(), ambiguous = false)
            .refusalLines("describe")
            .joinToString("\n")
        val ambiguous = CliError.UnknownStep("sh", listOf("core.sh", "other.sh"), ambiguous = true)
            .refusalLines("describe")
            .joinToString("\n")

        assertTrue(
            unknown.contains("no step named"),
            "a name nobody carries says so: $unknown",
        )
        assertTrue(
            ambiguous.contains("more than one entry"),
            "a name two entries carry says THAT, and not that the name is unknown: $ambiguous",
        )
        assertTrue(
            ambiguous.contains("core.sh") && ambiguous.contains("other.sh"),
            "both of the entries the name points at are named: $ambiguous",
        )
    }

    @Test
    fun `a plugin the admission refuses is refused here with the words the admission uses`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()

        val code = MainStepsCli.main(
            arrayOf("--plugin-jar", "/tmp/a-plugin-that-is-not-there.jar"),
            PrintStream(out, true, Charsets.UTF_8.name()),
            PrintStream(err, true, Charsets.UTF_8.name()),
        )

        assertEquals(CliExit.INVALID_ARGUMENT.code, code, "a refused plugin stops the verb")
        assertTrue(
            err.toString().contains("Plugin admission refused for /tmp/a-plugin-that-is-not-there.jar"),
            "the refusal names the artifact, with the words the admission of a run uses: ${err.toString()}",
        )
    }

    /** The catalogue of the core composition, with no plugin of the installation in it. */
    private fun coreEntries(): List<StepCatalogueEntry> =
        StepCatalogue.entries(coreRegistry(), coreDirectives())

    /**
     * The entries of the catalogue of the INSTALLED composition.
     *
     * It is the composition the verb publishes: the core registry, the declared
     * families and the plugins of the installation. The count depends on which
     * plugins are installed, and that is why no row asserts a number: the
     * guarantee is that EVERY entry declares its equivalent or its divergence,
     * and that holds for any composition.
     */
    private fun installedEntries(): List<JsonObject> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()

        val code = MainStepsCli.main(
            arrayOf("--json"),
            PrintStream(out, true, Charsets.UTF_8.name()),
            PrintStream(err, true, Charsets.UTF_8.name()),
        )

        assertEquals(CliExit.SUCCESS.code, code, "the verb answers with success: ${err.toString()}")
        val parsed = Json.parseToJsonElement(out.toString(Charsets.UTF_8.name())).jsonObject
        val entries = parsed["entries"]?.jsonArray
            ?: fail("the document the verb publishes carries no entries")
        return entries.map { it.jsonObject }
    }

    /** The registry of the core steps. */
    private fun coreRegistry() = CoreStepRegistryFactory.registry()

    /** The registry of the core directives. */
    private fun coreDirectives(): DirectiveRegistry = DirectiveRegistry.Builder().apply {
        add(ErasedDirectiveDefinition(WhenDirectiveDefinition()))
        add(ErasedDirectiveDefinition(AgentDirectiveDefinition()))
    }.build()

    /** An entry built by hand, for the rows that need a shape the core does not have. */
    private fun stepEntry(key: String, name: String): StepCatalogueEntry.Step = StepCatalogueEntry.Step(
        key = key,
        name = name,
        provider = "core",
        jenkinsSurface = "",
        deprecation = "",
        effects = emptyList(),
        replayPolicy = "MEMOIZED",
        recoveryPolicy = "None",
        requiredCapabilities = emptyList(),
        executionLocation = "CONTROLLER",
        body = dev.rubentxu.pipeline.v2.domain.step.StepCatalogueBody(kind = "none"),
        inputSchema = "{}",
        outputSchema = "{}",
    )
}
