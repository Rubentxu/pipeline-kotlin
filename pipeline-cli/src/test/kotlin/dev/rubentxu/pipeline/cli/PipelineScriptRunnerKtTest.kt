package dev.rubentxu.pipeline.cli

import com.github.ajalt.clikt.testing.test
import dev.rubentxu.pipeline.dsl.PipelineResult
import dev.rubentxu.pipeline.dsl.Status
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldHave
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.script.experimental.jvm.util.isError

class PipelineScriptRunnerKtTest : StringSpec({



    "eval script hello world" {
        val scriptFile = File("testData/hello.pipeline.kts").toPath().toAbsolutePath().toFile()
        println("scriptFile: $scriptFile")
        val result = evalFile(scriptFile)
        result.reports.forEach {
            println(" : ${it.message}" + if (it.exception == null) "" else ": ${it.exception}")
        }
        println(result)

        result.reports.size shouldBe 5
        result.isError() shouldBe false

    }
//
    "eval script pipeline dsl" {
        val scriptFile = File("testData/HelloWorld.pipeline.kts")
        val result = evalFile(scriptFile)
        result.reports.forEach {
            println(" : ${it.message}" + if (it.exception == null) "" else ": ${it.exception}")
        }
        println(result)

        result.reports.size shouldBe 5

    }

    "eval with script manager pipeline dsl" {
        val scriptFile = File("testData/HelloWorld.pipeline.kts")

        val result: PipelineResult =  evalWithScriptEngineManager(scriptFile) as PipelineResult
        println("result with scriptManager: $result")
        println(result)
        result.status shouldBe Status.Success
    }

    "execute pipeline main function" {
        val pipelineCli = PipelineCli()
        pipelineCli.main(arrayOf("-c", "testData/config.yaml", "-s", "testData/HelloWorld.pipeline.kts"))
    }

    "execute pipeline command test" {
        val pipelineCli = PipelineCli()
        val result = pipelineCli.test( "-c", "testData/config.yaml", "-s", "testData/HelloWorld.pipeline.kts")

        println("result: $result")
        println(result.stdout)
       result.statusCode shouldBe 0

    }
})
