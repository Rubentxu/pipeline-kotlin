package pipeline.kotlin.extensions

import com.google.gson.Gson
import dev.rubentxu.pipeline.dsl.StepsBlock
import java.io.File


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

fun StepsBlock.findFiles(glob: String): List<File> {
    val workingDir = this.pipeline.workingDir
    val directory = workingDir.toFile()

    val files = directory.listFiles { file -> file.name.matches(glob.toRegex()) }?.toList() ?: emptyList()
    return files
}