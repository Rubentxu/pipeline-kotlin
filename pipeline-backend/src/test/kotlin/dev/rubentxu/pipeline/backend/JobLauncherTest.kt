package dev.rubentxu.pipeline.backend

import dev.rubentxu.pipeline.backend.jobs.JobInstance
import dev.rubentxu.pipeline.backend.jobs.JobLauncherImpl
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.PipelineContext
import dev.rubentxu.pipeline.model.logger.PipelineLogger
import dev.rubentxu.pipeline.model.repository.SourceCodeConfig
import dev.rubentxu.pipeline.model.repository.SourceCodeRepositoryManager
import dev.rubentxu.pipeline.model.repository.SourceCodeType
import io.kotest.core.spec.style.StringSpec
import org.mockito.Mockito.mock
import java.io.File


class JobLauncherTest : StringSpec({


    "eval script hello world" {
        val scriptFile = File("testData/hello.pipeline.kts").path
        val configFile = File("testData/config.yaml").path


        val sourceCodeConfig = SourceCodeConfig(
            repositoryId = IDComponent.create("scm-test-id"),
            name = "test",
            description = null,
            relativePath = null,
            sourceCodeType = SourceCodeType.PROJECT,
        )
        println("scriptFile: $scriptFile")
        val job = JobInstance(
            name = "test",
            publisher = null,
            projectSourceCode= sourceCodeConfig,
            pipelineSourceCode =  sourceCodeConfig,
            trigger = null,
            parameters = emptyList(),
            pluginsSources = emptyList(),
        )


        val execution = JobLauncherImpl().launch(job, mock(PipelineContext::class.java))
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
