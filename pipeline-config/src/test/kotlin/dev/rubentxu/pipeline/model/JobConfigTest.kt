package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.steps.EnvVars
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeTypeOf
import java.nio.file.Path

class JobConfigTest : StringSpec({
    "Should get Job Config from the job config file" {
        val cascManager = CascManager()

        val resourcePath = PipelineConfigTest::class.java.classLoader.getResource("casc/job.yaml").path
        val testYamlPath = Path.of(resourcePath)

        val config = cascManager.resolveConfig(testYamlPath).getOrThrow()

        config.jobs?.size shouldBe 1
        config.jobs?.get(0)?.name shouldBe "pipeline-job"
        config.jobs?.get(0)?.triggers?.size shouldBe 1
        config.jobs?.get(0)?.triggers?.get(0)?.cron shouldBe "H/5 * * * *"
        config.jobs?.get(0)?.environmentVars?.size shouldBe 2
        config.jobs?.get(0)?.environmentVars?.get("JOB_NAME") shouldBe "Ejemplo-pipeline"
        config.jobs?.get(0)?.environmentVars?.get("MENSAJE") shouldBe "Hola Mundo"
        config.jobs?.get(0)?.publisher?.shouldNotBeNull()
        config.jobs?.get(0)?.publisher?.mailer?.recipients shouldBe "admin@localhost"
        config.jobs?.get(0)?.publisher?.mailer?.notifyEveryUnstableBuild shouldBe true
        config.jobs?.get(0)?.publisher?.mailer?.sendToIndividuals shouldBe true
        config.jobs?.get(0)?.publisher?.archiveArtifacts?.artifacts shouldBe "target/*.jar"
        config.jobs?.get(0)?.publisher?.archiveArtifacts?.allowEmptyArchive shouldBe true
        config.jobs?.get(0)?.publisher?.archiveArtifacts?.onlyIfSuccessful shouldBe true
        config.jobs?.get(0)?.publisher?.archiveArtifacts?.fingerprint shouldBe true
        config.jobs?.get(0)?.publisher?.archiveArtifacts?.excludes shouldBe "target/*.war"
        config.jobs?.get(0)?.publisher?.archiveArtifacts?.artifacts shouldBe "target/*.jar"
        config.jobs?.get(0)?.parameters?.size shouldBe 5
        config.jobs?.get(0)?.parameters?.get(0)?.shouldBeInstanceOf<StringParameter>()
        config.jobs?.get(0)?.parameters?.get(0)?.name shouldBe "FOO"
        config.jobs?.get(0)?.parameters?.get(0)?.defaultValue shouldBe "BAR"
        config.jobs?.get(0)?.parameters?.get(0)?.description shouldBe "FOO BAR"
        config.jobs?.get(0)?.parameters?.get(1)?.shouldBeInstanceOf<ChoiceParameter>()
        config.jobs?.get(0)?.parameters?.get(1)?.name shouldBe "BAZ"
        (config.jobs?.get(0)?.parameters?.get(1) as ChoiceParameter)?.choices shouldBe listOf("1", "2")
        config.jobs?.get(0)?.parameters?.get(2)?.shouldBeInstanceOf<BooleanParameter>()
        config.jobs?.get(0)?.parameters?.get(2)?.name shouldBe "QUX"
        config.jobs?.get(0)?.parameters?.get(2)?.defaultValue shouldBe true
        config.jobs?.get(0)?.parameters?.get(2)?.description shouldBe "QUX"
        config.jobs?.get(0)?.parameters?.get(3)?.shouldBeInstanceOf<PasswordParameter>()
        config.jobs?.get(0)?.parameters?.get(3)?.name shouldBe "QUUX"
        config.jobs?.get(0)?.parameters?.get(3)?.defaultValue shouldBe "QUUX"
        config.jobs?.get(0)?.parameters?.get(3)?.description shouldBe "QUUX"
        config.jobs?.get(0)?.parameters?.get(4)?.shouldBeInstanceOf<TextParameter>()
        config.jobs?.get(0)?.parameters?.get(4)?.name shouldBe "CORGE"
        config.jobs?.get(0)?.parameters?.get(4)?.defaultValue shouldBe "CORGE"
        config.jobs?.get(0)?.parameters?.get(4)?.description shouldBe "CORGE"
        config.jobs?.get(0)?.projectSource?.shouldBeInstanceOf<ProjectSource>()
        config.jobs?.get(0)?.projectSource?.name shouldBe "pipeline-config"
        config.jobs?.get(0)?.projectSource?.scmReferenceId shouldBe "pipeline-config-id"
        config.jobs?.get(0)?.librarySources?.size shouldBe 1
        config.jobs?.get(0)?.librarySources?.get(0)?.shouldBeInstanceOf<LibrarySource>()
        config.jobs?.get(0)?.librarySources?.get(0)?.name shouldBe "internal-pipeline-library"
        config.jobs?.get(0)?.librarySources?.get(0)?.scmReferenceId shouldBe "internal-pipeline-library-id"
        config.jobs?.get(0)?.pipelineFileSource?.shouldBeInstanceOf<PipelineFileSource>()
        config.jobs?.get(0)?.pipelineFileSource?.name shouldBe "pipeline-config"
        config.jobs?.get(0)?.pipelineFileSource?.scmReferenceId shouldBe "pipeline-config-id"
        config.jobs?.get(0)?.pipelineFileSource?.scriptPath shouldBe "Jenkinsfile"

    }

    "Should validate required fields in Job Config" {
        val cascManager = CascManager()

        val resourcePath = PipelineConfigTest::class.java.classLoader.getResource("casc/job.yaml").path
        val testYamlPath = Path.of(resourcePath)

        val config = cascManager.resolveConfig(testYamlPath).getOrThrow()

        config.jobs?.forEach { job ->
            job.name should {
                it.shouldNotBeNull()
                it.shouldNotBeEmpty()
            }
            job.triggers.shouldNotBeEmpty()
            job.environmentVars.shouldNotBeEmpty()
            job.publisher.shouldNotBeNull()
            job.parameters.shouldNotBeEmpty()
            job.projectSource.shouldNotBeNull()
            job.librarySources.shouldNotBeEmpty()
            job.pipelineFileSource.shouldNotBeNull()
        }
    }

    "Should validate field types in Job Config" {
        val cascManager = CascManager()

        val resourcePath = PipelineConfigTest::class.java.classLoader.getResource("casc/job.yaml").path
        val testYamlPath = Path.of(resourcePath)

        val config = cascManager.resolveConfig(testYamlPath).getOrThrow()

        config.jobs?.forEach { job ->
            job.name.shouldBeTypeOf<String>()
            job.triggers.forEach { it.shouldBeInstanceOf<Trigger>() }
            job.environmentVars.shouldBeTypeOf<EnvVars>()
            job.publisher.shouldBeTypeOf<Publisher>()
            job.parameters.forEach { it.shouldBeInstanceOf<Parameter>() }
            job.projectSource.shouldBeTypeOf<ProjectSource>()
            job.librarySources.forEach { it.shouldBeInstanceOf<LibrarySource>() }
            job.pipelineFileSource.shouldBeTypeOf<PipelineFileSource>()
        }
    }
})