package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class Lfc1CanonicalStepDescriptorTest {

    private val domainDescriptorRelativePath =
        "pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/StepDescriptor.kt"

    @Test
    fun `StepDescriptor has exactly one production authority in the domain`() {
        val declarations = FitnessPaths.walkKotlinFiles(FitnessPaths.v2Root())
            .filter { it.toString().replace('\\', '/').contains("/src/main/kotlin/") }
            .filter { file ->
                Regex("""(?m)^\s*(?:data\s+)?class\s+StepDescriptor\b""")
                    .containsMatchIn(Files.readString(file))
            }
            .map { FitnessPaths.v2Root().relativize(it).toString().replace('\\', '/') }
            .sorted()

        assertTrue(
            declarations == listOf(domainDescriptorRelativePath),
            "StepDescriptor must have one domain authority. Expected $domainDescriptorRelativePath; actual: $declarations",
        )
    }

    @Test
    fun `domain StepDescriptor exposes typed static step metadata`() {
        val source = Files.readString(FitnessPaths.v2Root().resolve(domainDescriptorRelativePath))
        listOf(
            "val stepId: String",
            "val name: String",
            "val executionLocation: ExecutionLocation",
            "val effects: List<Effect>",
            "val replayPolicy: ReplayPolicy",
        ).forEach { field ->
            assertTrue(source.contains(field), "Domain StepDescriptor must declare $field")
        }
    }
}
