package pipeline.kotlin.extensions

import com.google.gson.Gson
import dev.rubentxu.pipeline.dsl.StepsBlock
import dev.rubentxu.pipeline.model.PipelineError
import dev.rubentxu.pipeline.model.workspace.WorkspaceManager
import java.io.File
import java.nio.file.Path


fun StepsBlock.readJSON(file: File): Map<String, Any> {
    val json = file.readText()
    return Gson().fromJson(json, Map::class.java) as Map<String, Any>
}

fun StepsBlock.readJSON(text: String): Map<String, Any> {
    return Gson().fromJson(text, Map::class.java) as Map<String, Any>
}

fun StepsBlock.writeJSON(file: String, json: Map<String, Any>) {
    val json = Gson().toJson(json)
    File(file).writeText(json)
}

fun StepsBlock.writeJSON(returnText: Boolean, json: Map<String, Any>): String {
    return Gson().toJson(json)

}

suspend fun StepsBlock.findFiles(glob: String): List<Path> {
    val result =  this.context.getService(WorkspaceManager::class)
        .map {
            it.findFiles(glob)
        }

    if (result.isFailure) {
        throw PipelineError("Error finding files: ${result.exceptionOrNull()?.message}")
    }
    return result.getOrNull()!!
}