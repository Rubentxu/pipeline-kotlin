package dev.rubentxu.pipeline.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.micronaut.configuration.picocli.PicocliRunner
import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class PipelineCliCommandTest : StringSpec({


    "execute pipeline main function" {
        val ctx = ApplicationContext.run(Environment.CLI, Environment.TEST)
//        val baos = ByteArrayOutputStream()
//        System.setOut(PrintStream(baos))

        val args = arrayOf("-c", "testData/config.yaml", "-s", "testData/success.pipeline.kts")
        PicocliRunner.run(PipelineCliCommand::class.java, ctx, *args)


//        println(baos.toString())
//        baos.toString() shouldContain "HOLA MUNDO..."


    }

    "execute jar" {
        val baos = ByteArrayOutputStream()
//        System.setOut(PrintStream(baos))

        val args = arrayOf("-c", "testData/config.yaml", "-s", "testData/success.pipeline.kts")
        PipelineCliCommand.main(args)
//        baos.toString() shouldContain "HOLA MUNDO..."
//        println(baos.toString())
    }

})
