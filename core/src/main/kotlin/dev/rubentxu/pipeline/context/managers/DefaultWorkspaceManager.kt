package dev.rubentxu.pipeline.context.managers

import dev.rubentxu.pipeline.context.managers.interfaces.*
import dev.rubentxu.pipeline.logger.interfaces.ILogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.security.MessageDigest
import kotlin.io.path.*

/**
 * Default implementation of IWorkspaceManager with security integration and Jenkins-like functionality.
 */
class DefaultWorkspaceManager(
    initialDirectory: Path,
    internal val logger: ILogger? = null,
    securityPolicy: WorkspaceSecurity = DefaultWorkspaceSecurity()
) : IWorkspaceManager {

    private val tempWorkspaces = ConcurrentHashMap<String, Workspace>()
    private val workspaceStack = ArrayDeque<Workspace>()

    override val current: Workspace = DefaultWorkspace(
        initialDirectory.toAbsolutePath().normalize(),
        this
    ).also { workspaceStack.addLast(it) }

    override val security: WorkspaceSecurity = securityPolicy

    override suspend fun <T> dir(path: String, block: suspend Workspace.() -> T): T {
        val newWorkspacePath = current.path.resolve(path).normalize()
        
        if (!security.isPathAllowed(newWorkspacePath)) {
            throw SecurityException("Access denied to path: $newWorkspacePath")
        }

        val newWorkspace = DefaultWorkspace(newWorkspacePath, this)

        // Create the directory if it doesn't exist
        if (!Files.exists(newWorkspacePath)) {
            withContext(Dispatchers.IO) {
                Files.createDirectories(newWorkspacePath)
            }
        }

        logger?.debug("Changing to workspace directory: $newWorkspacePath")

        // Run the user's block within a new coroutine context that holds the new workspace
        return withContext(WorkspaceContextElement(newWorkspace)) {
            workspaceStack.addLast(newWorkspace)
            try {
                newWorkspace.block()
            } finally {
                workspaceStack.removeLast()
            }
        }
    }

    override suspend fun createWorkspace(path: Path, temporary: Boolean): Workspace {
        val absolutePath = path.toAbsolutePath().normalize()
        
        if (!security.isPathAllowed(absolutePath)) {
            throw SecurityException("Access denied to create workspace at: $absolutePath")
        }

        withContext(Dispatchers.IO) {
            Files.createDirectories(absolutePath)
        }

        val workspace = DefaultWorkspace(absolutePath, this)
        
        if (temporary) {
            val key = generateWorkspaceKey(absolutePath)
            tempWorkspaces[key] = workspace
            logger?.debug("Created temporary workspace: $absolutePath")
        }

        return workspace
    }

    override suspend fun <T> withWorkspace(workspace: Workspace, block: suspend Workspace.() -> T): T {
        return withContext(WorkspaceContextElement(workspace)) {
            workspaceStack.addLast(workspace)
            try {
                workspace.block()
            } finally {
                workspaceStack.removeLast()
            }
        }
    }

    override suspend fun getTempWorkspace(name: String): Workspace {
        return tempWorkspaces.computeIfAbsent(name) {
            val tempPath = Files.createTempDirectory("pipeline-workspace-$name")
            logger?.debug("Created temporary workspace '$name' at: $tempPath")
            DefaultWorkspace(tempPath, this)
        }
    }

    override fun resolve(relativePath: String): Path {
        val resolved = current.path.resolve(relativePath).normalize()
        if (!security.isPathAllowed(resolved)) {
            throw SecurityException("Access denied to path: $resolved")
        }
        return resolved
    }

    override fun resolve(relativePath: Path): Path {
        val resolved = current.path.resolve(relativePath).normalize()
        if (!security.isPathAllowed(resolved)) {
            throw SecurityException("Access denied to path: $resolved")
        }
        return resolved
    }

    override suspend fun cleanup() {
        logger?.debug("Cleaning up ${tempWorkspaces.size} temporary workspaces")
        
        tempWorkspaces.values.forEach { workspace ->
            try {
                workspace.clean()
                logger?.debug("Cleaned temporary workspace: ${workspace.path}")
            } catch (e: Exception) {
                logger?.warn("Failed to clean temporary workspace ${workspace.path}: ${e.message}")
            }
        }
        tempWorkspaces.clear()
    }

    override fun createScope(name: String, basePath: Path): IWorkspaceManager {
        val scopedPath = basePath.toAbsolutePath().normalize()
        logger?.debug("Creating scoped workspace manager '$name' at: $scopedPath")
        return DefaultWorkspaceManager(scopedPath, logger, security)
    }

    private fun generateWorkspaceKey(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(path.toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}

/**
 * Default implementation of Workspace interface.
 */
private class DefaultWorkspace(
    override val path: Path,
    private val manager: DefaultWorkspaceManager
) : Workspace {

    override val name: String = path.fileName?.toString() ?: "root"

    override fun file(relativePath: String): FileReference {
        val resolvedPath = path.resolve(relativePath).normalize()
        return DefaultFileReference(this, resolvedPath, manager.security, manager.logger)
    }

    override suspend fun mkdir(relativePath: String, recursive: Boolean): FileReference {
        val targetPath = path.resolve(relativePath).normalize()
        
        if (!manager.security.isOperationAllowed(WorkspaceOperation.CREATE, targetPath)) {
            throw SecurityException("Permission denied to create directory: $targetPath")
        }

        withContext(Dispatchers.IO) {
            if (recursive) {
                Files.createDirectories(targetPath)
            } else {
                Files.createDirectory(targetPath)
            }
        }

        manager.logger?.debug("Created directory: $targetPath")
        return DefaultFileReference(this, targetPath, manager.security, manager.logger)
    }

    override suspend fun exists(relativePath: String): Boolean = file(relativePath).exists()

    override suspend fun list(relativePath: String, recursive: Boolean): List<FileReference> = withContext(Dispatchers.IO) {
        val targetPath = path.resolve(relativePath).normalize()
        
        if (!manager.security.isOperationAllowed(WorkspaceOperation.READ, targetPath)) {
            throw SecurityException("Permission denied to list directory: $targetPath")
        }

        if (!Files.exists(targetPath) || !Files.isDirectory(targetPath)) {
            return@withContext emptyList()
        }

        val stream = if (recursive) Files.walk(targetPath) else Files.list(targetPath)
        
        stream.use { pathStream ->
            pathStream
                .filter { it != targetPath } // Exclude the directory itself
                .map { DefaultFileReference(this@DefaultWorkspace, it, manager.security, manager.logger) }
                .collect(Collectors.toList())
        }
    }

    override suspend fun findFiles(glob: String): List<FileReference> = withContext(Dispatchers.IO) {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
        
        Files.walk(path).use { pathStream ->
            pathStream
                .filter { Files.isRegularFile(it) }
                .filter { matcher.matches(path.relativize(it)) }
                .filter { manager.security.isOperationAllowed(WorkspaceOperation.READ, it) }
                .map { DefaultFileReference(this@DefaultWorkspace, it, manager.security, manager.logger) }
                .collect(Collectors.toList())
        }
    }

    override suspend fun archive(sourceDir: String, targetArchive: String, format: ArchiveFormat): Unit = withContext(Dispatchers.IO) {
        val sourcePath = path.resolve(sourceDir).normalize()
        val targetPath = path.resolve(targetArchive).normalize()
        
        if (!manager.security.isOperationAllowed(WorkspaceOperation.ARCHIVE, sourcePath) ||
            !manager.security.isOperationAllowed(WorkspaceOperation.CREATE, targetPath)) {
            throw SecurityException("Permission denied to archive $sourcePath to $targetPath")
        }

        when (format) {
            ArchiveFormat.ZIP -> createZipArchive(sourcePath, targetPath)
            else -> throw UnsupportedOperationException("Archive format $format not yet supported")
        }

        manager.logger?.info("Archived $sourcePath to $targetPath using $format format")
    }

    override suspend fun unarchive(sourceArchive: String, targetDir: String): Unit = withContext(Dispatchers.IO) {
        val sourcePath = path.resolve(sourceArchive).normalize()
        val targetPath = path.resolve(targetDir).normalize()
        
        if (!manager.security.isOperationAllowed(WorkspaceOperation.READ, sourcePath) ||
            !manager.security.isOperationAllowed(WorkspaceOperation.EXTRACT, targetPath)) {
            throw SecurityException("Permission denied to extract $sourcePath to $targetPath")
        }

        extractZipArchive(sourcePath, targetPath)
        manager.logger?.info("Extracted $sourcePath to $targetPath")
    }

    override suspend fun clean(): Unit = withContext(Dispatchers.IO) {
        if (!manager.security.isOperationAllowed(WorkspaceOperation.DELETE, path)) {
            throw SecurityException("Permission denied to clean workspace: $path")
        }

        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (dir != path) { // Don't delete the workspace root
                    Files.delete(dir)
                }
                return FileVisitResult.CONTINUE
            }
        })

        manager.logger?.info("Cleaned workspace: $path")
    }

    override suspend fun pwd(): String = path.toString()

    override suspend fun <T> changeDir(relativePath: String, block: suspend Workspace.() -> T): T {
        return manager.dir(relativePath, block)
    }

    private fun createZipArchive(sourcePath: Path, targetPath: Path) {
        ZipOutputStream(Files.newOutputStream(targetPath)).use { zos ->
            Files.walk(sourcePath).forEach { file ->
                if (!Files.isDirectory(file)) {
                    val zipEntry = ZipEntry(sourcePath.relativize(file).toString())
                    zos.putNextEntry(zipEntry)
                    Files.copy(file, zos)
                    zos.closeEntry()
                }
            }
        }
    }

    private fun extractZipArchive(sourcePath: Path, targetPath: Path) {
        Files.createDirectories(targetPath)
        
        ZipInputStream(Files.newInputStream(sourcePath)).use { zis ->
            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                val newPath = targetPath.resolve(zipEntry.name).normalize()
                
                // Security check to prevent zip slip attacks
                if (!newPath.startsWith(targetPath)) {
                    throw SecurityException("Zip entry '${zipEntry.name}' would extract outside target directory")
                }
                
                if (zipEntry.isDirectory) {
                    Files.createDirectories(newPath)
                } else {
                    newPath.parent?.let { parent ->
                        if (!Files.exists(parent)) {
                            Files.createDirectories(parent)
                        }
                    }
                    Files.copy(zis, newPath, StandardCopyOption.REPLACE_EXISTING)
                }
                zipEntry = zis.nextEntry
            }
        }
    }
}

/**
 * Default implementation of FileReference interface.
 */
private class DefaultFileReference(
    private val workspace: Workspace,
    override val path: Path,
    private val security: WorkspaceSecurity,
    private val logger: ILogger?
) : FileReference {

    override val name: String get() = path.fileName.toString()
    override val isDirectory: Boolean get() = Files.isDirectory(path)
    override val isFile: Boolean get() = Files.isRegularFile(path)
    override val size: Long get() = if (Files.exists(path)) Files.size(path) else 0L
    override val lastModified: Long get() = if (Files.exists(path)) Files.getLastModifiedTime(path).toMillis() else 0L
    override val permissions: Set<PosixFilePermission> 
        get() = try {
            Files.getPosixFilePermissions(path)
        } catch (e: UnsupportedOperationException) {
            emptySet() // Not supported on Windows
        }

    override suspend fun readText(): String = withContext(Dispatchers.IO) {
        validateOperation(WorkspaceOperation.READ)
        Files.readString(path)
    }

    override suspend fun writeText(content: String): Unit = withContext(Dispatchers.IO) {
        validateOperation(WorkspaceOperation.WRITE)
        path.parent?.let { parent ->
            if (!Files.exists(parent)) {
                Files.createDirectories(parent)
            }
        }
        Files.writeString(path, content)
        logger?.debug("Wrote ${content.length} characters to: $path")
    }

    override suspend fun appendText(content: String): Unit = withContext(Dispatchers.IO) {
        validateOperation(WorkspaceOperation.WRITE)
        Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        logger?.debug("Appended ${content.length} characters to: $path")
    }

    override suspend fun readBytes(): ByteArray = withContext(Dispatchers.IO) {
        validateOperation(WorkspaceOperation.READ)
        Files.readAllBytes(path)
    }

    override suspend fun writeBytes(content: ByteArray): Unit = withContext(Dispatchers.IO) {
        validateOperation(WorkspaceOperation.WRITE)
        path.parent?.let { parent ->
            if (!Files.exists(parent)) {
                Files.createDirectories(parent)
            }
        }
        Files.write(path, content)
        logger?.debug("Wrote ${content.size} bytes to: $path")
    }

    override suspend fun delete(): Unit = withContext(Dispatchers.IO) {
        validateOperation(WorkspaceOperation.DELETE)
        
        if (Files.isDirectory(path)) {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } else {
            Files.delete(path)
        }
        
        logger?.debug("Deleted: $path")
    }

    override suspend fun exists(): Boolean = withContext(Dispatchers.IO) {
        Files.exists(path)
    }

    override suspend fun copyTo(target: FileReference): Unit = withContext(Dispatchers.IO) {
        validateOperation(WorkspaceOperation.READ)
        if (!security.isOperationAllowed(WorkspaceOperation.COPY, target.path)) {
            throw SecurityException("Permission denied to copy to: ${target.path}")
        }
        
        target.path.parent?.let { parent ->
            if (!Files.exists(parent)) {
                Files.createDirectories(parent)
            }
        }
        
        Files.copy(path, target.path, StandardCopyOption.REPLACE_EXISTING)
        logger?.debug("Copied $path to ${target.path}")
    }

    override suspend fun moveTo(target: FileReference): Unit = withContext(Dispatchers.IO) {
        validateOperation(WorkspaceOperation.MOVE)
        if (!security.isOperationAllowed(WorkspaceOperation.MOVE, target.path)) {
            throw SecurityException("Permission denied to move to: ${target.path}")
        }
        
        target.path.parent?.let { parent ->
            if (!Files.exists(parent)) {
                Files.createDirectories(parent)
            }
        }
        
        Files.move(path, target.path, StandardCopyOption.REPLACE_EXISTING)
        logger?.debug("Moved $path to ${target.path}")
    }

    override suspend fun createSymbolicLink(target: FileReference): Unit = withContext(Dispatchers.IO) {
        validateOperation(WorkspaceOperation.CREATE)
        Files.createSymbolicLink(path, target.path)
        logger?.debug("Created symbolic link $path -> ${target.path}")
    }

    override suspend fun isSymbolicLink(): Boolean = withContext(Dispatchers.IO) {
        Files.isSymbolicLink(path)
    }

    override suspend fun readSymbolicLink(): FileReference? = withContext(Dispatchers.IO) {
        if (Files.isSymbolicLink(path)) {
            val target = Files.readSymbolicLink(path)
            DefaultFileReference(workspace, target, security, logger)
        } else {
            null
        }
    }

    private fun validateOperation(operation: WorkspaceOperation) {
        if (!security.isOperationAllowed(operation, path)) {
            throw SecurityException("Permission denied for $operation on: $path")
        }
        if (!security.validateFileAccess(this)) {
            throw SecurityException("File access validation failed for: $path")
        }
    }

    override fun toString(): String = "FileReference(path=${workspace.path.relativize(path)})"
}

/**
 * Default workspace security implementation with basic path validation.
 */
class DefaultWorkspaceSecurity : WorkspaceSecurity {
    
    // Patterns that are not allowed in paths for security
    private val blockedPatterns = listOf(
        Regex("\\.\\."), // Path traversal attempts
        Regex("/etc/"), // System configuration files
        Regex("/proc/"), // Process information
        Regex("/sys/"), // System files
        Regex("~/.ssh/"), // SSH keys
        Regex("~/.aws/"), // AWS credentials
    )
    
    override fun isPathAllowed(path: Path): Boolean {
        val pathString = path.toString()
        return blockedPatterns.none { it.containsMatchIn(pathString) }
    }
    
    override fun isOperationAllowed(operation: WorkspaceOperation, path: Path): Boolean {
        // Basic implementation - can be extended for more sophisticated security policies
        return isPathAllowed(path) && when (operation) {
            WorkspaceOperation.READ -> true
            WorkspaceOperation.WRITE, WorkspaceOperation.CREATE -> isWritableLocation(path)
            WorkspaceOperation.DELETE -> isDeletableLocation(path)
            WorkspaceOperation.EXECUTE -> isExecutableLocation(path)
            WorkspaceOperation.MOVE, WorkspaceOperation.COPY -> isWritableLocation(path)
            WorkspaceOperation.ARCHIVE, WorkspaceOperation.EXTRACT -> isWritableLocation(path)
        }
    }
    
    override fun validateFileAccess(file: FileReference): Boolean {
        return isPathAllowed(file.path)
    }
    
    private fun isWritableLocation(path: Path): Boolean {
        // Don't allow writing to system directories
        val pathString = path.toString()
        return !pathString.startsWith("/usr/") &&
               !pathString.startsWith("/bin/") &&
               !pathString.startsWith("/sbin/") &&
               !pathString.startsWith("/boot/")
    }
    
    private fun isDeletableLocation(path: Path): Boolean {
        // Even more restrictive for deletion
        return isWritableLocation(path) && !path.toString().startsWith("/home/")
    }
    
    private fun isExecutableLocation(path: Path): Boolean {
        // Allow execution only in specific directories
        val pathString = path.toString()
        return pathString.contains("/tmp/") ||
               pathString.contains("/workspace/") ||
               pathString.contains("/build/")
    }
}