package dev.rubentxu.pipeline.cli

import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarFile
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate


fun evalFile(scriptFile: File): ResultWithDiagnostics<EvaluationResult> {
//    val classpathUrl = PipelineScript::class.java.getResource(PipelineScript::class.java.simpleName + ".class").toString().split("!").first()
//    println(classpathUrl)
//    System.setProperty("kotlin.script.classpath", classpathUrl )

//    val path = PipelineScript::class.java.protectionDomain.codeSource.location.path
//    System.setProperty("kotlin.script.classpath", path)

    val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<PipelineScript> {
        jvm {
            dependenciesFromClassloader(
//                libraries = arrayOf(
//                    "kotlinx-coroutine",
//                    "kotlin-stdlib",
//                    "kotlin-reflection",
//                    "kotlin-script-runtime",
//                    ),
                classLoader = PipelineScript::class.java.classLoader,
                wholeClasspath = true
            )
        }
    }
    return BasicJvmScriptingHost().eval(scriptFile.toScriptSource(), compilationConfiguration, null)
}