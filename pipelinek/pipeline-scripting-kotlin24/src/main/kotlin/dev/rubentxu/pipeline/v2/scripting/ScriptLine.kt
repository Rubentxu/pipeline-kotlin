package dev.rubentxu.pipeline.v2.scripting

/**
 * The line of the script a throwable came through, or 0 when it is unknown.
 *
 * The frames of the script are the frames of [scriptClass], the class that was evaluated, and of
 * the classes nested in it (`Script$...`: its lambdas and the classes the script declares), with a
 * line. The first of them in the stack trace is the line of the script the throwable came through:
 * a frame of the DSL or of a library above it is skipped, and a gate the DSL raises while it builds
 * a stage points at the call to that stage.
 *
 * The class, and not the name of the file in the frame, identifies the script. A stored artifact
 * is shared by every file with the same text, and its classes keep naming the file that stored it:
 * a reuse from a file with another name has no frame that names its own file, while the class it
 * evaluated is still the one the frames name. A script with no file is found the same way.
 *
 * [scriptClass] is null when the throwable was not raised by an evaluated script, and the line is
 * then unknown. The JVM records no column, so the caller has none to add.
 */
internal fun scriptLineOf(throwable: Throwable, scriptClass: String?): Int {
    if (scriptClass == null) return 0
    val nested = "$scriptClass$"
    return throwable.stackTrace.firstOrNull { frame ->
        (frame.className == scriptClass || frame.className.startsWith(nested)) && frame.lineNumber > 0
    }?.lineNumber ?: 0
}
