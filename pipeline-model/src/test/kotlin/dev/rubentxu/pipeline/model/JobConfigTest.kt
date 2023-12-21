package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.model.jobs.*
import dev.rubentxu.pipeline.model.steps.EnvVars
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

        val resourcePath = PipelineContextTest::class.java.classLoader.getResource("casc/job.yaml").path
        val testYamlPath = Path.of(resourcePath)

        val config = cascManager.resolveConfig(testYamlPath).getOrThrow()

        config.job?.name shouldBe "pipeline-job"
        config.job?.trigger shouldBe CronTrigger("H/5 * * * *")       
        config.job?.environmentVars?.size shouldBe 2
        config.job?.environmentVars?.get("JOB_NAME") shouldBe "Ejemplo-pipeline"
        config.job?.environmentVars?.get("MENSAJE") shouldBe "Hola Mundo"
        config.job?.publisher?.shouldNotBeNull()
        config.job?.publisher?.mailer?.recipients shouldBe "admin@localhost"
        config.job?.publisher?.mailer?.notifyEveryUnstableBuild shouldBe true
        config.job?.publisher?.mailer?.sendToIndividuals shouldBe true
        config.job?.publisher?.archiveArtifacts?.artifacts shouldBe "target/*.jar"
        config.job?.publisher?.archiveArtifacts?.allowEmptyArchive shouldBe true
        config.job?.publisher?.archiveArtifacts?.onlyIfSuccessful shouldBe true
        config.job?.publisher?.archiveArtifacts?.fingerprint shouldBe true
        config.job?.publisher?.archiveArtifacts?.excludes shouldBe "target/*.war"
        config.job?.publisher?.archiveArtifacts?.artifacts shouldBe "target/*.jar"
//        config.job?.jobParameters?.size shouldBe 5
//        config.job?.jobParameters?.get(0)?.shouldBeInstanceOf<StringJobParameter>()
//        config.job?.jobParameters?.get(0)?.name shouldBe "FOO"
//        config.job?.jobParameters?.get(0)?.defaultValue shouldBe "BAR"
//        config.job?.jobParameters?.get(0)?.description shouldBe "FOO BAR"
//        config.job?.jobParameters?.get(1)?.shouldBeInstanceOf<ChoiceJobParameter>()
//        config.job?.jobParameters?.get(1)?.name shouldBe "BAZ"
//        (config.job?.jobParameters?.get(1) as ChoiceJobParameter)?.choices shouldBe listOf("1", "2")
//        config.job?.jobParameters?.get(2)?.shouldBeInstanceOf<BooleanJobParameter>()
//        config.job?.jobParameters?.get(2)?.name shouldBe "QUX"
//        config.job?.jobParameters?.get(2)?.defaultValue shouldBe true
//        config.job?.jobParameters?.get(2)?.description shouldBe "QUX"
//        config.job?.jobParameters?.get(3)?.shouldBeInstanceOf<PasswordJobParameter>()
//        config.job?.jobParameters?.get(3)?.name shouldBe "QUUX"
//        config.job?.jobParameters?.get(3)?.defaultValue shouldBe "QUUX"
//        config.job?.jobParameters?.get(3)?.description shouldBe "QUUX"
//        config.job?.jobParameters?.get(4)?.shouldBeInstanceOf<TextJobParameter>()
//        config.job?.jobParameters?.get(4)?.name shouldBe "CORGE"
//        config.job?.jobParameters?.get(4)?.defaultValue shouldBe "CORGE"
//        config.job?.jobParameters?.get(4)?.description shouldBe "CORGE"
        config.job?.projectSource?.shouldBeInstanceOf<ProjectSourceCode>()
        config.job?.projectSource?.name shouldBe "pipeline-config"
        config.job?.projectSource?.scmReferenceId shouldBe "pipeline-config-id"
        config.job?.pluginsDefinitionSource?.size shouldBe 1
        config.job?.pluginsDefinitionSource?.get(0)?.shouldBeInstanceOf<PluginsDefinitionSource>()
        config.job?.pluginsDefinitionSource?.get(0)?.name shouldBe "internal-pipeline-library"
        config.job?.pluginsDefinitionSource?.get(0)?.scmReferenceId shouldBe "internal-pipeline-library-id"
        config.job?.pipelineFileSource?.shouldBeInstanceOf<PipelineFileSource>()
        config.job?.pipelineFileSource?.name shouldBe "pipeline-config"
        config.job?.pipelineFileSource?.scmReferenceId shouldBe "pipeline-config-id"
        config.job?.pipelineFileSource?.relativeScriptPath shouldBe "Jenkinsfile"

    }

    "Should validate required fields in Job Config" {
        val cascManager = CascManager()

        val resourcePath = PipelineContextTest::class.java.classLoader.getResource("casc/job.yaml").path
        val testYamlPath = Path.of(resourcePath)

        val config = cascManager.resolveConfig(testYamlPath).getOrThrow()
        val job = config.job!!

            job.name should {
                it.shouldNotBeNull()
                it.shouldNotBeEmpty()
            }
            job.trigger.shouldNotBeNull()
            job.environmentVars.shouldNotBeEmpty()
            job.publisher.shouldNotBeNull()
            job.projectSource.shouldNotBeNull()
            job.pluginsDefinitionSource.shouldNotBeEmpty()
            job.pipelineFileSource.shouldNotBeNull()

    }

    "Should validate field types in Job Config" {
        val cascManager = CascManager()

        val resourcePath = PipelineContextTest::class.java.classLoader.getResource("casc/job.yaml").path
        val testYamlPath = Path.of(resourcePath)

        val config = cascManager.resolveConfig(testYamlPath).getOrThrow()
        val job = config.job!!


            job.name.shouldBeTypeOf<String>()

            job.environmentVars.shouldBeTypeOf<EnvVars>()
            job.publisher.shouldBeTypeOf<Publisher>()

            job.projectSource.shouldBeTypeOf<ProjectSourceCode>()
            job.pluginsDefinitionSource.forEach { it.shouldBeInstanceOf<PluginsDefinitionSource>() }
            job.pipelineFileSource.shouldBeTypeOf<PipelineFileSource>()

    }
})