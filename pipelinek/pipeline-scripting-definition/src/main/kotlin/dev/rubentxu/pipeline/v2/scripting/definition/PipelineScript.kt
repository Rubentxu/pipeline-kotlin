package dev.rubentxu.pipeline.v2.scripting.definition

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.ide

/**
 * The definition of a `.pipeline.kts`: the one place that says how a pipeline script is compiled.
 *
 * The runner compiles every script with this definition, and an IDE discovers the same class
 * through the template marker this artifact carries
 * (`META-INF/kotlin/script/templates/dev.rubentxu.pipeline.v2.scripting.definition.PipelineScript.classname`).
 * The marker is a resource written by hand: nothing in the Kotlin toolchain generates it.
 *
 * The definition declares what the script SEES (its default imports) and what the compiler
 * REFUSES (its options). It declares no classpath: the runner adds the product's jars and the
 * plugins' jars on top of [PipelineScriptCompilationConfiguration] when it compiles, and an IDE
 * resolves the classpath of the module the script lives in.
 *
 * It is an abstract class and not an interface because the compiler generates the class of each
 * script as a SUBCLASS of the template. It has no member because a pipeline script needs none.
 */
@KotlinScript(
    displayName = "PipelineK pipeline",
    fileExtension = "pipeline.kts",
    compilationConfiguration = PipelineScriptCompilationConfiguration::class,
)
@Suppress("AbstractClassCanBeInterface")
abstract class PipelineScript

/**
 * The compilation configuration of [PipelineScript].
 *
 * THE OPTIONS ARE THE DIAGNOSTICS. A `.pipeline.kts` that discards the carrier a pure builder
 * returns (for example a `scmGit(...)` that never reaches a `checkout(...)`) is refused by the
 * COMPILER, with the line and the column of the call:
 *
 * - `-Xreturn-value-checker=check` turns on Kotlin's return-value checker, which reads
 *   `@MustUseReturnValues` on the DSL's builder scopes. On its own it reports a WARNING, and a
 *   warning does not stop a compilation.
 * - `-Xwarning-level=RETURN_VALUE_NOT_USED:error` raises the diagnostic of a discarded call to an
 *   error. It covers a call in statement position at the top level of the script.
 * - `-Xwarning-level=RETURN_VALUE_NOT_USED_COERCION:error` raises the diagnostic of a discarded
 *   call inside a lambda whose expected type returns `Unit`, which is every `stage { }` body.
 *   Without it the discard inside a stage stays a warning. Measured on Kotlin 2.4.10.
 *
 * `-Werror` is not an alternative: the compiler ignores it when it compiles a script.
 *
 * The raised level applies to the whole script, not only to the DSL: a discarded value of a
 * standard-library function whose result must be used (`"x".uppercase()`, `list.map { }`) is an
 * error too. Functions the standard library declares as ignorable (`MutableList.add`,
 * `MutableMap.put`) are not reported.
 *
 * The compiler is the first line. The second stays where it was: the DSL's carrier registry
 * (`rejectUnconsumedCarriers()`) refuses an unconsumed carrier when the script is EVALUATED,
 * whatever compiled it.
 *
 * `-Xuse-fast-jar-file-system=false` routes jar reads through the regular `JarFile` path, so the
 * compiler does not call `sun.misc.Unsafe::invokeCleaner`, which warns on JDK 21+.
 */
object PipelineScriptCompilationConfiguration : ScriptCompilationConfiguration({
    defaultImports(
        "dev.rubentxu.pipeline.v2.dsl.pipeline",
        "dev.rubentxu.pipeline.v2.dsl.stages",
        "dev.rubentxu.pipeline.v2.dsl.stage",
        "dev.rubentxu.pipeline.v2.dsl.echo",
        "dev.rubentxu.pipeline.v2.dsl.sh",
        "dev.rubentxu.pipeline.v2.dsl.PipelineSpec",
        "dev.rubentxu.pipeline.v2.dsl.StageSpec",
        "dev.rubentxu.pipeline.v2.dsl.StepSpec",
        "dev.rubentxu.pipeline.v2.dsl.PipelineScope",
        "dev.rubentxu.pipeline.v2.dsl.StagesScope",
        "dev.rubentxu.pipeline.v2.dsl.StageScope",
        "dev.rubentxu.pipeline.v2.dsl.StageBuilder",
        "dev.rubentxu.pipeline.v2.scripting.CompiledScriptedEntryPoint",
        "dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity",
        "dev.rubentxu.pipeline.v2.scripting.ScriptedStepFacade",
        "dev.rubentxu.pipeline.v2.scripting.ScriptedCallSiteId",
        "dev.rubentxu.pipeline.v2.scripting.ScriptedDynamicScopeId",
        "dev.rubentxu.pipeline.v2.scripting.ScriptedSourceId",
        "dev.rubentxu.pipeline.v2.scripting.ScriptedBlockName",
        "dev.rubentxu.pipeline.v2.scripting.ScriptedSourceLocation",
        "dev.rubentxu.pipeline.v2.scripting.ReturnStdout",
        "dev.rubentxu.pipeline.v2.scripting.ReturnStatus",
        "dev.rubentxu.pipeline.v2.domain.CredentialsId",
        "dev.rubentxu.pipeline.v2.domain.CredentialsRef",
    )
    compilerOptions(
        "-Xreturn-value-checker=check",
        "-Xwarning-level=RETURN_VALUE_NOT_USED:error",
        "-Xwarning-level=RETURN_VALUE_NOT_USED_COERCION:error",
        "-Xuse-fast-jar-file-system=false",
    )
    ide {
        acceptedLocations(ScriptAcceptedLocation.Everywhere)
    }
}) {
    // An `object` that extends a `Serializable` configuration has to resolve to its own instance
    // when it is deserialized, or a second copy of the definition would exist. Java serialization
    // calls it by reflection: the compiled-script cache writes the configuration into its jar
    // (`saveToJar`) and reads it back (`loadScriptFromJar`).
    @Suppress("UnusedPrivateFunction")
    private fun readResolve(): Any = PipelineScriptCompilationConfiguration
}
