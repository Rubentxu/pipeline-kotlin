package dev.rubentxu.pipeline.core.jobs

import dev.rubentxu.pipeline.core.interfaces.ILogger
import dev.rubentxu.pipeline.core.interfaces.IPipelineContext
import dev.rubentxu.pipeline.core.pipeline.PipelineContext
import io.kotest.core.spec.style.StringSpec
import org.mockito.Mockito.mock
import java.io.File
import java.io.InputStreamReader

class JobLauncherTest : StringSpec({


    "eval script hello world" {
        val scriptFile = File("testData/hello.pipeline.kts").path
        val configFile = File("testData/config.yaml").path
        val logger: ILogger = mock(ILogger::class.java)
        val scriptReader: InputStreamReader = File(scriptFile).reader()


        val execution: JobExecution = JobLauncherImpl(logger).launch(mock(IPipelineContext::class.java), scriptReader)
        val result = execution.result
        println("result: $result")


    }

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