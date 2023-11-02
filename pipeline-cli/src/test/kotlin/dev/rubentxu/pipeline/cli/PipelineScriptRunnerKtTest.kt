package dev.rubentxu.pipeline.cli

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
import kotlin.script.experimental.jvm.util.isError

class PipelineScriptRunnerKtTest : StringSpec({


    "eval script hello world" {
        val scriptFile = File("testData/hello.pipeline.kts").toPath().toAbsolutePath().toFile()
        println("scriptFile: $scriptFile")
        val result = evalWithScriptEngineManager(scriptFile)
        println("result: $result")

    }
//
    "eval script pipeline dsl" {
        val scriptFile = File("testData/HelloWorld.pipeline.kts")
        val result = evalWithScriptEngineManager(scriptFile) as PipelineResult

        println("result: $result")

        result is PipelineResult
        result.status shouldBe Status.Success

    }

    "eval with script manager pipeline dsl" {
        val scriptFile = File("testData/HelloWorld.pipeline.kts")

        val result: PipelineResult = evalWithScriptEngineManager(scriptFile) as PipelineResult
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
