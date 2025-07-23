package dev.rubentxu.pipeline.context.managers.interfaces

import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.coroutines.CoroutineContext

/**
 * A reference to a file or directory within a Workspace.
 * Provides a fluent, object-oriented API for file operations with security integration.
 */
interface FileReference {
    val path: Path
    val name: String
    val isDirectory: Boolean
    val isFile: Boolean
    val size: Long
    val lastModified: Long
    val permissions: Set<PosixFilePermission>

    suspend fun readText(): String
    suspend fun writeText(content: String)
    suspend fun appendText(content: String)
    suspend fun readBytes(): ByteArray
    suspend fun writeBytes(content: ByteArray)
    suspend fun delete()
    suspend fun exists(): Boolean
    suspend fun copyTo(target: FileReference)
    suspend fun moveTo(target: FileReference)
    suspend fun createSymbolicLink(target: FileReference)
    suspend fun isSymbolicLink(): Boolean
    suspend fun readSymbolicLink(): FileReference?
}

/**
 * Represents a workspace directory and provides high-level, safe operations within it.
 * Inspired by Jenkins pipeline workspace management with enhanced security.
 */
interface Workspace {
    /** The absolute path of this workspace. */
    val path: Path
    
    /** The workspace name (last component of path). */
    val name: String

    /**
     * Resolves a relative path against the workspace path safely.
     * @return A [FileReference] for fluent operations.
     */
    fun file(relativePath: String): FileReference

    /**
     * Creates a subdirectory within the workspace.
     * @param relativePath The directory path relative to workspace
     * @param recursive Whether to create parent directories if they don't exist
     */
    suspend fun mkdir(relativePath: String, recursive: Boolean = true): FileReference

    /**
     * Checks if a file or directory exists at the given relative path.
     */
    suspend fun exists(relativePath: String): Boolean

    /**
     * Lists all files and directories in the specified directory.
     * @param relativePath The directory path relative to workspace (default: root)
     * @param recursive Whether to list files recursively
     */
    suspend fun list(relativePath: String = ".", recursive: Boolean = false): List<FileReference>

    /**
     * Finds files within the workspace that match a glob pattern.
     * Example: `findFiles("src/**/*.kt")`
     */
    suspend fun findFiles(glob: String): List<FileReference>

    /**
     * Archives (zips) the content of a source directory into a target zip file.
     * Both paths are relative to the workspace.
     */
    suspend fun archive(sourceDir: String, targetArchive: String, format: ArchiveFormat = ArchiveFormat.ZIP)

    /**
     * Extracts an archive file into a target directory.
     * Both paths are relative to the workspace.
     */
    suspend fun unarchive(sourceArchive: String, targetDir: String)

    /**
     * Cleans up the workspace by deleting all contents.
     * Use with caution!
     */
    suspend fun clean()

    /**
     * Gets the current working directory relative to the workspace.
     */
    suspend fun pwd(): String

    /**
     * Changes the working directory within the workspace for scoped operations.
     */
    suspend fun <T> changeDir(relativePath: String, block: suspend Workspace.() -> T): T
}

/**
 * Archive formats supported by the workspace manager.
 */
enum class ArchiveFormat {
    ZIP, TAR, TAR_GZ, TAR_BZ2
}

/**
 * Security policy for workspace operations.
 */
interface WorkspaceSecurity {
    fun isPathAllowed(path: Path): Boolean
    fun isOperationAllowed(operation: WorkspaceOperation, path: Path): Boolean
    fun validateFileAccess(file: FileReference): Boolean
}

/**
 * Workspace operations for security validation.
 */
enum class WorkspaceOperation {
    READ, WRITE, DELETE, EXECUTE, CREATE, MOVE, COPY, ARCHIVE, EXTRACT
}

/**
 * Manages the concept of a "current workspace" for the pipeline with enhanced features.
 * Provides Jenkins-like workspace functionality with security integration and performance monitoring.
 */
interface IWorkspaceManager {
    /** Provides the current active Workspace. */
    val current: Workspace
    
    /** Security policy for workspace operations. */
    val security: WorkspaceSecurity

    /**
     * Executes a block of code with the workspace temporarily changed to a subdirectory.
     * This is the equivalent of the `dir` step in Jenkins.
     *
     * @param path The subdirectory path, relative to the current workspace.
     * @param block The code to execute within the new workspace. The receiver `this` is the new Workspace.
     */
    suspend fun <T> dir(path: String, block: suspend Workspace.() -> T): T

    /**
     * Creates a new workspace at the specified path.
     * @param path The absolute path for the new workspace
     * @param temporary Whether this workspace should be cleaned up automatically
     */
    suspend fun createWorkspace(path: Path, temporary: Boolean = false): Workspace

    /**
     * Switches to a different workspace temporarily.
     * @param workspace The workspace to switch to
     * @param block The code to execute within the workspace context
     */
    suspend fun <T> withWorkspace(workspace: Workspace, block: suspend Workspace.() -> T): T

    /**
     * Gets or creates a temporary workspace for isolated operations.
     * Temporary workspaces are automatically cleaned up.
     */
    suspend fun getTempWorkspace(name: String = "temp"): Workspace

    /**
     * Resolves a path against the current workspace with security validation.
     * @param relativePath The path to resolve
     * @return The resolved absolute path
     */
    fun resolve(relativePath: String): Path

    /**
     * Resolves a Path against the current workspace with security validation.
     * @param relativePath The path to resolve
     * @return The resolved absolute path
     */
    fun resolve(relativePath: Path): Path

    /**
     * Cleans up all temporary workspaces and resources.
     */
    suspend fun cleanup()

    /**
     * Creates a scoped workspace manager for nested operations.
     * @param name The scope name for debugging and logging
     * @param basePath The base path for the scoped manager
     */
    fun createScope(name: String, basePath: Path): IWorkspaceManager
}

// --- Coroutine-Safe Context Management ---

/**
 * A CoroutineContext.Element to safely propagate the current Workspace.
 * This replaces the unsafe ThreadLocal pattern.
 */
internal class WorkspaceContextElement(val workspace: Workspace) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<WorkspaceContextElement>

    override val key: CoroutineContext.Key<*> = Key
}

/**
 * A suspend function to safely get the current Workspace from the coroutine context.
 */
suspend fun currentWorkspace(): Workspace {
    return kotlinx.coroutines.currentCoroutineContext()[WorkspaceContextElement.Key]?.workspace
        ?: error("No Workspace found in the current CoroutineContext.")
}