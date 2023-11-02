package dev.rubentxu.pipeline.cli

import java.io.File

fun evalWithScriptEngineManager(scriptFile: File): Any? {
    val engine = javax.script.ScriptEngineManager().getEngineByExtension("kts")!!
    return engine.eval(scriptFile.reader())

}