package dev.rubentxu.pipeline.cli

import dev.rubentxu.pipeline.backend.evalWithScriptEngineManager
import dev.rubentxu.pipeline.dsl.PipelineResult
import dev.rubentxu.pipeline.dsl.Status
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micronaut.configuration.picocli.PicocliRunner
import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class PipelineScriptRunnerKtTest : StringSpec({


    "eval script hello world" {
        val scriptFile = File("testData/hello.pipeline.kts").path
        val configFile = File("testData/config.yaml").path

        println("scriptFile: $scriptFile")
        val result = evalWithScriptEngineManager(scriptFile, configFile)
        println("result: $result")

    }
//
    "eval script pipeline dsl" {
        val scriptFile = File("src/test/resources/HelloWorld.pipeline.kts").path
        val configFile = File("testData/config.yaml").path

        val result = evalWithScriptEngineManager(scriptFile, configFile)

        println("result: $result")

        result is PipelineResult
        result.status shouldBe Status.Success

    }

    "eval regex" {

        val errorMessage = """
    javax.script.ScriptException: ERROR Function invocation 'any(...)' expected (ScriptingHost474821de_Line_0.kts:9:11)
  """

        // El resto del código es igual

        val regex = """ERROR (.*) expected \(ScriptingHost.*.kts:(\d+):(\d+)\)""".toRegex()

        val match = regex.find(errorMessage) ?: throw RuntimeException("No se pudo parsear el error")

        val (error, line, space) = match.destructured

        println("Error in Pipeline definition: $error")
        println("Line: $line")
        println("Space: $space")


    }

    "eval with script manager pipeline dsl" {
        val scriptFile = File("testData/HelloWorld.pipeline.kts").path

        val configFile = File("testData/config.yaml").path
        val jarFile = File("build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar").path

        val result: PipelineResult =  evalWithScriptEngineManager(scriptFile, configFile)
        println("result with scriptManager: $result")
        println(result)
        result.status shouldBe Status.Success
    }

    "execute pipeline main function" {
        val ctx = ApplicationContext.run(Environment.CLI, Environment.TEST)
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos))

        val args = arrayOf("-c", "testData/config.yaml", "-s", "testData/HelloWorld.pipeline.kts")
        PicocliRunner.run(PipelineCliCommand::class.java, ctx, *args)
        baos.toString() shouldContain "HOLA MUNDO..."
    }

})
