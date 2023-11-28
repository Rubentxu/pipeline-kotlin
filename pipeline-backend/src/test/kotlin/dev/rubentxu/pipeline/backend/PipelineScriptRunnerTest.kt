package dev.rubentxu.pipeline.backend

import dev.rubentxu.pipeline.model.pipeline.PipelineResult
import dev.rubentxu.pipeline.model.pipeline.Status
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File


class PipelineScriptRunnerTest : StringSpec({


    "eval script hello world" {
        val scriptFile = File("testData/hello.pipeline.kts").path
        val configFile = File("testData/config.yaml").path

        println("scriptFile: $scriptFile")
        val result = PipelineScriptRunner.evalWithScriptEngineManager(scriptFile, configFile)
        println("result: $result")


    }
//
    "eval script pipeline dsl" {
        val scriptFile = File("testData/error.pipeline.kts").path
        val configFile = File("testData/config.yaml").path

        val result = PipelineScriptRunner.evalWithScriptEngineManager(scriptFile, configFile)

        println("result: $result")

        result is PipelineResult
        result.status shouldBe Status.Failure

    }

    "eval regex" {

        val errorMessage = """
    javax.script.ScriptException: ERROR Function invocation 'any(...)' expected (ScriptingHost474821de_Line_0.kts:9:11)
  """

        val regex = """ERROR (.*) expected \(ScriptingHost.*.kts:(\d+):(\d+)\)""".toRegex()

        val match = regex.find(errorMessage) ?: throw RuntimeException("No se pudo parsear el error")

        val (error, line, space) = match.destructured

        println("Error in Pipeline definition: $error")
        println("Line: $line")
        println("Space: $space")


    }

    "eval with script manager pipeline dsl" {
        val scriptFile = File("testData/success.pipeline.kts").path
        val configFile = File("testData/config.yaml").path
        val jarFile = File("build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar")

        val result: PipelineResult = PipelineScriptRunner.evalWithScriptEngineManager(scriptFile, configFile, jarFile)
        println("result with scriptManager: $result")
        println(result)
        result.status shouldBe Status.Success
    }


})
