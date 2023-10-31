package dev.rubentxu.pipeline.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File

class PipelineScriptRunnerKtTest : StringSpec({



    "eval script hello world" {
        val scriptFile = File("testData/hello.pipeline.kts").toPath().toAbsolutePath().toFile()
        println("scriptFile: $scriptFile")
        val result = evalFile(scriptFile)
        result.reports.forEach {
            println(" : ${it.message}" + if (it.exception == null) "" else ": ${it.exception}")
        }
        println(result)

//        result.reports.size shouldBe 0

    }
//
    "eval script pipeline dsl" {
        val scriptFile = File("testData/HelloWorld.pipeline.kts")
        val result = evalFile(scriptFile)
        result.reports.forEach {
            println(" : ${it.message}" + if (it.exception == null) "" else ": ${it.exception}")
        }
        println(result)

//        result.reports.size shouldBe 0

    }

})
