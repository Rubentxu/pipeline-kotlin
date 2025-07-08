package dev.rubentxu.pipeline.library

import dev.rubentxu.pipeline.compiler.GradleCompiler
import org.eclipse.jgit.api.Git
import java.io.File

/**
 * Retrieves and compiles a library from a remote Git repository.
 *
 * @property gradleCompiler The compiler used to build the library after cloning.
 */
class GitSource(private val gradleCompiler: GradleCompiler) : SourceRetriever {

    /**
     * Clones the Git repository and compiles the library.
     *
     * @param libraryConfiguration The configuration of the library to retrieve.
     * @return The compiled JAR file.
     */
    override fun retrieve(libraryConfiguration: LibraryConfiguration): File {
        val gitRepoPath = "build/to/local/git/repo"
        Git.cloneRepository()
            .setURI(libraryConfiguration.sourcePath)
            .setDirectory(File(gitRepoPath))
            .call()

        return gradleCompiler.compileAndJar(gitRepoPath, libraryConfiguration)
    }
}