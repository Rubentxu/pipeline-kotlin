import dev.rubentxu.pipeline.core.interfaces.IWorkspace
import java.nio.file.*

class WorkspaceImpl(private val workspacePath: String) : IWorkspace {
    override val currentPath: Path = Path.of(workspacePath)
    private val watchedFiles: MutableMap<String, Long> = mutableMapOf()

    override fun checkWatchedFiles(abortPipeline: Boolean): Boolean {
        for ((path, lastModified) in watchedFiles) {
            val file = currentPath.resolve(path).toFile()
            if (file.lastModified() != lastModified) {
                if (abortPipeline) {
                    return false
                }
                watchedFiles[path] = file.lastModified()
            }
        }
        return true
    }

    override fun checkWatchedFiles(
        abortPipeline: Boolean,
        inclusions: List<String>,
        exclusions: List<String>
    ): Boolean {
        val includedFiles = inclusions.flatMap { glob ->
            Files.walk(currentPath).filter { path ->
                FileSystems.getDefault().getPathMatcher("glob:$glob").matches(currentPath.relativize(path))
            }.toList()
        }.map { it.toString() }

        val excludedFiles = exclusions.flatMap { glob ->
            Files.walk(currentPath).filter { path ->
                FileSystems.getDefault().getPathMatcher("glob:$glob").matches(currentPath.relativize(path))
            }.toList()
        }.map { it.toString() }

        watchedFiles.keys.retainAll(includedFiles - excludedFiles)

        return checkWatchedFiles(abortPipeline)
    }

    override fun globMatch(pattern: String, str: String, caseSensitive: Boolean): Boolean {
        val matcher: PathMatcher = if (caseSensitive) {
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        } else {
            FileSystems.getDefault().getPathMatcher("glob:${pattern.toLowerCase()}")
        }
        return matcher.matches(Paths.get(str))
    }

    override fun createDirectoriesIfNotExist(dirs: List<String>) {
        dirs.forEach { dir ->
            val path = currentPath.resolve(dir)
            if (!Files.exists(path)) {
                Files.createDirectories(path)
            }
        }
    }

    override fun directoryExists(directory: String): Boolean {
        return Files.exists(currentPath.resolve(directory))
    }
}