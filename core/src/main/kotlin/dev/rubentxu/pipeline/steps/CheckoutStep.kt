package dev.rubentxu.pipeline.steps

import dev.rubentxu.pipeline.context.StepExecutionContext
import dev.rubentxu.pipeline.model.scm.Scm
import dev.rubentxu.pipeline.model.scm.GitScm
import dev.rubentxu.pipeline.model.scm.CloneOption
import dev.rubentxu.pipeline.model.scm.CheckoutOption
import dev.rubentxu.pipeline.model.scm.SubmoduleOption
import dev.rubentxu.pipeline.model.scm.UserIdentity
import dev.rubentxu.pipeline.model.scm.WipeWorkspace
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.CredentialsProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Secure checkout step implementation that provides Git SCM operations within the pipeline context.
 * This step can only be used within a StepsBlock and has access to controlled pipeline resources.
 */
class CheckoutStep(private val context: StepExecutionContext) {
    
    suspend fun execute(scm: Scm): CheckoutResult = withContext(Dispatchers.IO) {
        context.logger.info("+ checkout ${scm.url}")
        
        when (scm) {
            is GitScm -> executeGitCheckout(scm)
            else -> throw UnsupportedOperationException("SCM type ${scm::class.simpleName} is not supported")
        }
    }
    
    private suspend fun executeGitCheckout(gitScm: GitScm): CheckoutResult = withContext(Dispatchers.IO) {
        val workingDir = Paths.get(context.workingDirectory)
        val repositoryDir = workingDir.resolve(".git")
        
        // Check if we need to wipe the workspace
        val wipeWorkspace = gitScm.extensions.filterIsInstance<WipeWorkspace>().firstOrNull()
        if (wipeWorkspace?.wipeWorkspace == true && Files.exists(workingDir)) {
            context.logger.info("Wiping workspace: ${workingDir.toAbsolutePath()}")
            workingDir.toFile().deleteRecursively()
        }
        
        // Create working directory if it doesn't exist
        Files.createDirectories(workingDir)
        
        val git = if (Files.exists(repositoryDir)) {
            // Repository exists, open it
            context.logger.info("Opening existing repository at: ${workingDir.toAbsolutePath()}")
            Git.open(workingDir.toFile())
        } else {
            // Clone repository
            context.logger.info("Cloning repository from: ${gitScm.url}")
            cloneRepository(gitScm, workingDir)
        }
        
        try {
            // Checkout the specified branch
            checkoutBranch(git, gitScm)
            
            // Handle submodules if needed
            handleSubmodules(git, gitScm)
            
            // Get commit information
            val commit = git.repository.resolve("HEAD")
            val commitId = commit?.name ?: "unknown"
            
            context.logger.info("Checkout completed successfully. HEAD at: $commitId")
            
            CheckoutResult(
                success = true,
                commitId = commitId,
                branch = gitScm.branch,
                url = gitScm.url
            )
        } finally {
            git.close()
        }
    }
    
    private fun cloneRepository(gitScm: GitScm, workingDir: Path): Git {
        val cloneCommand = Git.cloneRepository()
            .setURI(gitScm.url)
            .setDirectory(workingDir.toFile())
            .setBranch(gitScm.branch)
        
        // Apply clone options
        val cloneOptions = gitScm.extensions.filterIsInstance<CloneOption>().firstOrNull()
        cloneOptions?.let { options ->
            cloneCommand.setCloneAllBranches(!options.shallow)
            cloneCommand.setCloneSubmodules(!options.shallow)
            if (options.noTags) {
                cloneCommand.setNoTags()
            }
            cloneCommand.setTimeout(options.timeout)
        }
        
        // Apply credentials if provided
        val credentialsProvider = createCredentialsProvider(gitScm)
        credentialsProvider?.let { cloneCommand.setCredentialsProvider(it) }
        
        return cloneCommand.call()
    }
    
    private fun checkoutBranch(git: Git, gitScm: GitScm) {
        val checkoutCommand = git.checkout()
            .setName(gitScm.branch)
        
        // Apply checkout options
        val checkoutOptions = gitScm.extensions.filterIsInstance<CheckoutOption>().firstOrNull()
        checkoutOptions?.let { options ->
            // JGit doesn't have a direct timeout option for checkout
            // We could implement this with a timeout wrapper if needed
        }
        
        checkoutCommand.call()
    }
    
    private fun handleSubmodules(git: Git, gitScm: GitScm) {
        val submoduleOptions = gitScm.extensions.filterIsInstance<SubmoduleOption>().firstOrNull()
        
        if (submoduleOptions?.disableSubmodules != true) {
            try {
                // Initialize submodules
                git.submoduleInit().call()
                
                // Update submodules
                val submoduleUpdate = git.submoduleUpdate()
                if (submoduleOptions?.recursiveSubmodules == true) {
                    // JGit doesn't have direct recursive option, but we can iterate
                    // This would require more complex implementation
                }
                submoduleUpdate.call()
                
                context.logger.info("Submodules updated successfully")
            } catch (e: Exception) {
                context.logger.error("Failed to update submodules: ${e.message}")
                // Don't fail the whole checkout for submodule issues
            }
        }
    }
    
    private fun createCredentialsProvider(gitScm: GitScm): CredentialsProvider? {
        val credentialsId = gitScm.credentialsId ?: return null
        
        // This is a simplified implementation
        // In a real implementation, you would fetch credentials from a secure store
        val username = context.environment["GIT_USERNAME"] ?: return null
        val password = context.environment["GIT_PASSWORD"] ?: context.environment["GIT_TOKEN"] ?: return null
        
        return UsernamePasswordCredentialsProvider(username, password)
    }
}

/**
 * Result of a checkout operation
 */
data class CheckoutResult(
    val success: Boolean,
    val commitId: String,
    val branch: String,
    val url: String,
    val error: String? = null
)