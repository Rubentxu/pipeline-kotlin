package dev.rubentxu.pipeline.cli

import dev.rubentxu.pipeline.dsl.PipelineDsl

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.dependencies.*
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvm.util.scriptCompilationClasspathFromContextOrNull


@KotlinScript(
    displayName = "Pipeline Script",
    fileExtension = "pipeline.kts",
    compilationConfiguration = PipelineScriptCompilationConfiguration::class
)
abstract class PipelineScript

internal object PipelineScriptCompilationConfiguration :  ScriptCompilationConfiguration({
    defaultImports(DependsOn::class, Repository::class, PipelineDsl::class )
    implicitReceivers("dev.rubentxu.pipeline.dsl.*")
    ide {
        acceptedLocations(ScriptAcceptedLocation.Everywhere)
    }
    jvm {
        updateClasspath(
            classpath = scriptCompilationClasspathFromContextOrNull(
                "core",
                "pipeline-script",
                "kotlin-stdlib",
                "kotlinx-coroutines-core",
                classLoader = PipelineScript::class.java.classLoader
            )
        )
        dependenciesFromCurrentContext(
            "core", // dsl library jar name
            "pipeline-script", // script jar name
            "kotlin-scripting-dependencies" // DependsOn annotation is taken from this jar
        )
    }
})


