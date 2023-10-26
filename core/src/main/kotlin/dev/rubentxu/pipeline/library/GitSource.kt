package dev.rubentxu.pipeline.library

import dev.rubentxu.pipeline.compiler.GradleCompiler
import org.eclipse.jgit.api.Git
import java.io.File

class GitSource(private val gradleCompiler: GradleCompiler) : SourceRetriever {
    override fun retrieve(libraryConfiguration: LibraryConfiguration): File {
        val gitRepoPath = "build/to/local/git/repo"
        Git.cloneRepository()
            .setURI(libraryConfiguration.sourcePath)
            .setDirectory(File(gitRepoPath))
            .call()

        return gradleCompiler.compileAndJar(gitRepoPath, libraryConfiguration)
    }
}