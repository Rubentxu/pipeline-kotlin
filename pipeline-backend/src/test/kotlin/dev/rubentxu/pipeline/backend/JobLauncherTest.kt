package dev.rubentxu.pipeline.backend

import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.backend.jobs.JobLauncherImpl
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.jobs.JobResult
import dev.rubentxu.pipeline.model.jobs.PipelineFileSource
import dev.rubentxu.pipeline.model.jobs.ProjectSource
import dev.rubentxu.pipeline.model.jobs.Status
import dev.rubentxu.pipeline.model.logger.PipelineLogger
import dev.rubentxu.pipeline.model.repository.SourceCodeRepositoryManager
import dev.rubentxu.pipeline.model.steps.EnvVars
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.mockito.Mock
import org.mockito.Mockito.mock
import java.io.File
import java.nio.file.Path


class JobLauncherTest : StringSpec({


    "eval script hello world" {
        val scriptFile = File("testData/hello.pipeline.kts").path
        val configFile = File("testData/config.yaml").path

        val sourceCodeRepositoryManager = mock<SourceCodeRepositoryManager>()

        println("scriptFile: $scriptFile")
        val job = JobInstance(
            name = "test",
            environmentVars = EnvVars(mapOf()),
            publisher = null,
            projectSource = ProjectSource("test", IDComponent.create("scm-test-id")),
            emptyList(),
            pipelineFileSource = PipelineFileSource("test", Path.of("testData/hello.pipeline.kts"), IDComponent.create("scm-test-id")),
            trigger = null,
            sourceCodeRepositoryManager = sourceCodeRepositoryManager,
            parameters = emptyList(),
            logger = PipelineLogger.getLogger()
        )


        val execution = JobLauncherImpl().launch(job)
        val result = execution.result
        println("result: $result")


    }
//
//    "eval script pipeline dsl" {
//        val scriptFile = File("testData/error.pipeline.kts").path
//        val configFile = File("testData/config.yaml").path
//
//        val result = PipelineScriptRunner.evalWithScriptEngineManager(scriptFile, configFile)
//
//        println("result: $result")
//
//        result is JobResult
//        result.status shouldBe Status.Failure
//
//    }
//
//    "eval regex" {
//
//        val errorMessage = """
//    javax.script.ScriptException: ERROR Function invocation 'any(...)' expected (ScriptingHost474821de_Line_0.kts:9:11)
//  """
//
//        val regex = """ERROR (.*) expected \(ScriptingHost.*.kts:(\d+):(\d+)\)""".toRegex()
//
//        val match = regex.find(errorMessage) ?: throw RuntimeException("No se pudo parsear el error")
//
//        val (error, line, space) = match.destructured
//
//        println("Error in Pipeline definition: $error")
//        println("Line: $line")
//        println("Space: $space")
//
//
//    }
//
//    "eval with script manager pipeline dsl" {
//        val scriptFile = File("testData/success.pipeline.kts").path
//        val configFile = File("testData/config.yaml").path
//        val jarFile = File("build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar")
//
//        val result: JobResult = PipelineScriptRunner.evalWithScriptEngineManager(scriptFile, configFile, jarFile)
//        println("result with scriptManager: $result")
//        println(result)
//        result.status shouldBe Status.Success
//    }


})
