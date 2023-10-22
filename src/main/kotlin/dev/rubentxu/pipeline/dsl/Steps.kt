package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.steps.EnvVars
import dev.rubentxu.pipeline.steps.Initializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.logging.Logger

open class Steps(val pipeline: PipelineDsl) : Initializable, CoroutineScope by CoroutineScope(Dispatchers.Default) {

    val workingDir: Path = Path.of(System.getProperty("user.dir"))
    val log = Logger.getLogger(Steps::class.java.name)

    // inicializa env y params dentro de la clase
    internal val env: EnvVars = pipeline.env
    private val params: ConcurrentMap<String, Any> = ConcurrentHashMap()

    fun getSteps(): Steps {
        return this
    }

    override fun initialize(configuration: Map<String, Any>) {
        println("Run StepEXECUTOR")
        println(configuration)
        // Aquí necesitarás implementar tus propios métodos
        // Por ejemplo:
        // this.initializeWorkspace(configuration)
        // this.storeCredentials(expandCredentials)
        // this.storeGlobalConfigFiles(expandGlobalConfigFiles)
        // this.configureScm(configuration)
    }

    fun getParams(): ConcurrentMap<String, Any> {
        return params
    }

    fun getEnv(): EnvVars {
        return env
    }


    fun execute(closure: suspend Steps.() -> Unit) {
        launch {
            closure()
        }
    }
}