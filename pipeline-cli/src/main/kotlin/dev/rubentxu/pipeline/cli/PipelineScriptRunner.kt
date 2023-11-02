package dev.rubentxu.pipeline.cli

import java.io.File

import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
//import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
//import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
//
//
//fun evalFile(scriptFile: File): ResultWithDiagnostics<EvaluationResult> {
//
//    val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<PipelineScript> {
//        jvm {
//            dependenciesFromClassloader(
////                libraries = arrayOf(
////                    "core",
////                    "kotlinx-coroutine",
////                    "kotlin-stdlib",
////                    "kotlin-reflect",
////                    "kotlin-script-runtime",
////                    ),
//                classLoader = PipelineScript::class.java.classLoader,
//                wholeClasspath = true
//            )
////            dependenciesFromCurrentContext(
////                wholeClasspath = true
////            )
//            defaultImports("dev.rubentxu.pipeline.dsl.*")
//        }
//    }
//    return BasicJvmScriptingHost().eval(scriptFile.toScriptSource(), compilationConfiguration, null)
//}

fun evalWithScriptEngineManager(scriptFile: File): Any? {
    val engine = javax.script.ScriptEngineManager().getEngineByExtension("kts")!!
    return engine.eval(scriptFile.reader())

}

public class LoadException(message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause)