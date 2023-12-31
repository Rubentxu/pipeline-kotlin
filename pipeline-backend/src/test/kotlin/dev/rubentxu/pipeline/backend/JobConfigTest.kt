package dev.rubentxu.pipeline.backend

import dev.rubentxu.pipeline.backend.cdi.CascManager
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
//    "Should get Job Config from the job config file" {
//        val cascManager = CascManager()
//
//        val resourcePath = JobConfigTest::class.java.classLoader.getResource("casc/job.yaml").path
//        val testYamlPath = Path.of(resourcePath)
//
//        val config = cascManager.resolveConfig(testYamlPath).getOrThrow()
//
//        config.name shouldBe "pipeline-job"
//        config.trigger shouldBe CronTrigger("H/5 * * * *")
//        config.environmentVars?.size shouldBe 2
//        config.environmentVars?.get("JOB_NAME") shouldBe "Ejemplo-pipeline"
//        config.environmentVars?.get("MENSAJE") shouldBe "Hola Mundo"
//        config.publisher?.shouldNotBeNull()
//        config.publisher?.mailer?.recipients shouldBe "admin@localhost"
//        config.publisher?.mailer?.notifyEveryUnstableBuild shouldBe true
//        config.publisher?.mailer?.sendToIndividuals shouldBe true
//        config.publisher?.archiveArtifacts?.artifacts shouldBe "target/*.jar"
//        config.publisher?.archiveArtifacts?.allowEmptyArchive shouldBe true
//        config.publisher?.archiveArtifacts?.onlyIfSuccessful shouldBe true
//        config.publisher?.archiveArtifacts?.fingerprint shouldBe true
//        config.publisher?.archiveArtifacts?.excludes shouldBe "target/*.war"
//        config.publisher?.archiveArtifacts?.artifacts shouldBe "target/*.jar"
////        config.jobParameters?.size shouldBe 5
////        config.jobParameters?.get(0)?.shouldBeInstanceOf<StringJobParameter>()
////        config.jobParameters?.get(0)?.name shouldBe "FOO"
////        config.jobParameters?.get(0)?.defaultValue shouldBe "BAR"
////        config.jobParameters?.get(0)?.description shouldBe "FOO BAR"
////        config.jobParameters?.get(1)?.shouldBeInstanceOf<ChoiceJobParameter>()
////        config.jobParameters?.get(1)?.name shouldBe "BAZ"
////        (config.jobParameters?.get(1) as ChoiceJobParameter)?.choices shouldBe listOf("1", "2")
////        config.jobParameters?.get(2)?.shouldBeInstanceOf<BooleanJobParameter>()
////        config.jobParameters?.get(2)?.name shouldBe "QUX"
////        config.jobParameters?.get(2)?.defaultValue shouldBe true
////        config.jobParameters?.get(2)?.description shouldBe "QUX"
////        config.jobParameters?.get(3)?.shouldBeInstanceOf<PasswordJobParameter>()
////        config.jobParameters?.get(3)?.name shouldBe "QUUX"
////        config.jobParameters?.get(3)?.defaultValue shouldBe "QUUX"
////        config.jobParameters?.get(3)?.description shouldBe "QUUX"
////        config.jobParameters?.get(4)?.shouldBeInstanceOf<TextJobParameter>()
////        config.jobParameters?.get(4)?.name shouldBe "CORGE"
////        config.jobParameters?.get(4)?.defaultValue shouldBe "CORGE"
////        config.jobParameters?.get(4)?.description shouldBe "CORGE"
//        config.projectSource?.shouldBeInstanceOf<ProjectSourceCode>()
//        config.projectSource?.name shouldBe "pipeline-config"
//        config.projectSource?.scmReferenceId shouldBe "pipeline-config-id"
//        config.pluginsDefinitionSource?.size shouldBe 1
//        config.pluginsDefinitionSource?.get(0)?.shouldBeInstanceOf<PluginsDefinitionSource>()
//        config.pluginsDefinitionSource?.get(0)?.name shouldBe "internal-pipeline-library"
//        config.pluginsDefinitionSource?.get(0)?.scmReferenceId shouldBe "internal-pipeline-library-id"
//        config.pipelineFileSource?.shouldBeInstanceOf<PipelineFileSource>()
//        config.pipelineFileSource?.name shouldBe "pipeline-config"
//        config.pipelineFileSource?.scmReferenceId shouldBe "pipeline-config-id"
//        config.pipelineFileSource?.relativeScriptPath shouldBe "Jenkinsfile"
//
//    }
//
//    "Should validate required fields in Job Config" {
//        val cascManager = CascManager()
//
//        val resourcePath = PipelineContextTest::class.java.classLoader.getResource("casc/job.yaml").path
//        val testYamlPath = Path.of(resourcePath)
//
//        val config = cascManager.resolveConfig(testYamlPath).getOrThrow()
//        val job = config.job!!
//
//            job.name should {
//                it.shouldNotBeNull()
//                it.shouldNotBeEmpty()
//            }
//            job.trigger.shouldNotBeNull()
//            job.environmentVars.shouldNotBeEmpty()
//            job.publisher.shouldNotBeNull()
//            job.projectSource.shouldNotBeNull()
//            job.pluginsDefinitionSource.shouldNotBeEmpty()
//            job.pipelineFileSource.shouldNotBeNull()
//
//    }
//
//    "Should validate field types in Job Config" {
//        val cascManager = CascManager()
//
//        val resourcePath = PipelineContextTest::class.java.classLoader.getResource("casc/job.yaml").path
//        val testYamlPath = Path.of(resourcePath)
//
//        val config = cascManager.resolveConfig(testYamlPath).getOrThrow()
//        val job = config.job!!
//
//
//            job.name.shouldBeTypeOf<String>()
//
//            job.environmentVars.shouldBeTypeOf<EnvVars>()
//            job.publisher.shouldBeTypeOf<Publisher>()
//
//            job.projectSource.shouldBeTypeOf<ProjectSourceCode>()
//            job.pluginsDefinitionSource.forEach { it.shouldBeInstanceOf<PluginsDefinitionSource>() }
//            job.pipelineFileSource.shouldBeTypeOf<PipelineFileSource>()
//
//    }
})