package dev.rubentxu.pipeline.dsl

import dev.rubentxu.pipeline.context.managers.interfaces.ArchiveFormat
import dev.rubentxu.pipeline.context.managers.interfaces.FileReference
import dev.rubentxu.pipeline.context.managers.interfaces.IWorkspaceManager
import dev.rubentxu.pipeline.context.managers.interfaces.Workspace

/**
 * Jenkins-like workspace operations for pipeline DSL.
 * Provides familiar pipeline operations for file and directory management.
 * 
 * These extension functions add Jenkins-style convenience methods to the workspace manager.
 */

/**
 * Convenience extension for reading a file as text.
 */
suspend fun IWorkspaceManager.readFile(path: String): String {
    return current.file(path).readText()
}

/**
 * Convenience extension for writing text to a file.
 */
suspend fun IWorkspaceManager.writeFile(path: String, content: String) {
    current.file(path).writeText(content)
}

/**
 * Convenience extension for appending text to a file.
 */
suspend fun IWorkspaceManager.appendFile(path: String, content: String) {
    current.file(path).appendText(content)
}

/**
 * Convenience extension for checking if a file exists.
 */
suspend fun IWorkspaceManager.fileExists(path: String): Boolean {
    return current.exists(path)
}

/**
 * Convenience extension for deleting a file or directory.
 */
suspend fun IWorkspaceManager.deleteFile(path: String) {
    current.file(path).delete()
}

/**
 * Convenience extension for creating a directory.
 */
suspend fun IWorkspaceManager.mkdir(path: String): FileReference {
    return current.mkdir(path, recursive = true)
}

/**
 * Convenience extension for listing files in a directory.
 */
suspend fun IWorkspaceManager.listFiles(path: String = ".", recursive: Boolean = false): List<FileReference> {
    return current.list(path, recursive)
}

/**
 * Convenience extension for finding files with a glob pattern.
 */
suspend fun IWorkspaceManager.findFiles(glob: String): List<FileReference> {
    return current.findFiles(glob)
}

/**
 * Convenience extension for copying files.
 */
suspend fun IWorkspaceManager.copyFile(source: String, destination: String) {
    val sourceFile = current.file(source)
    val destFile = current.file(destination)
    sourceFile.copyTo(destFile)
}

/**
 * Convenience extension for moving files.
 */
suspend fun IWorkspaceManager.moveFile(source: String, destination: String) {
    val sourceFile = current.file(source)
    val destFile = current.file(destination)
    sourceFile.moveTo(destFile)
}

/**
 * Convenience extension for creating zip archives.
 */
suspend fun IWorkspaceManager.zip(sourceDir: String, targetFile: String) {
    current.archive(sourceDir, targetFile, ArchiveFormat.ZIP)
}

/**
 * Convenience extension for extracting archives.
 */
suspend fun IWorkspaceManager.unzip(sourceFile: String, targetDir: String) {
    current.unarchive(sourceFile, targetDir)
}

/**
 * Convenience extension for getting current working directory.
 */
suspend fun IWorkspaceManager.pwd(): String {
    return current.pwd()
}

/**
 * Convenience extension for cleaning the workspace.
 */
suspend fun IWorkspaceManager.cleanWs() {
    current.clean()
}

/**
 * Extension functions for Workspace to add Jenkins-style operations
 */

/**
 * Creates a stash of files matching the given pattern.
 */
suspend fun Workspace.stash(name: String, includes: String = "**/*", excludes: String? = null) {
    // Create a temporary directory for the stash
    val stashDir = ".pipeline-stashes/$name"
    mkdir(stashDir)
    
    // Find files matching the includes pattern
    val files = findFiles(includes)
    val filteredFiles = if (excludes != null) {
        val excludePattern = findFiles(excludes).map { it.path }.toSet()
        files.filter { it.path !in excludePattern }
    } else {
        files
    }
    
    // Copy files to stash directory preserving structure
    filteredFiles.forEach { file ->
        val relativePath = path.relativize(file.path)
        val stashFile = file("$stashDir/$relativePath")
        file.copyTo(stashFile)
    }
    
    // Archive the stash
    archive(stashDir, ".pipeline-stashes/$name.zip")
    file(stashDir).delete() // Clean up temporary directory
}

/**
 * Unstashes previously stashed files.
 */
suspend fun Workspace.unstash(name: String) {
    val stashFile = ".pipeline-stashes/$name.zip"
    if (exists(stashFile)) {
        unarchive(stashFile, ".")
    } else {
        throw IllegalArgumentException("Stash '$name' not found")
    }
}