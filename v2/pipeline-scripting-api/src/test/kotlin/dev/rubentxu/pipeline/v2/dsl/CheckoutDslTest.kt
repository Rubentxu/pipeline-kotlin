package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.scm.Scm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for checkout DSL functions.
 * Verifies the DSL functions return correct types and have correct parameter defaults.
 */
class CheckoutDslTest {

    @Test
    fun `scmGit creates CheckoutSpec with correct GitScm`() {
        val scope = StageScope("Test")
        val result = scope.scmGit("https://github.com/example/repo.git")

        // scmGit returns CheckoutSpec
        assertEquals(CheckoutSpec::class, result::class)
        val gitScm = result.scm as GitScm
        assertEquals("https://github.com/example/repo.git", gitScm.url)
        assertEquals("master", gitScm.branch)
        assertEquals(".", gitScm.relativeTargetDir)
    }

    @Test
    fun `scmGit with all params creates correct GitScm`() {
        val scope = StageScope("Test")
        val credId = CredentialsId("my-creds")
        val result = scope.scmGit(
            url = "https://github.com/example/repo.git",
            branch = "develop",
            credentialsId = credId,
            changelog = false,
            poll = false,
            relativeTargetDir = "src"
        )

        val gitScm = result.scm as GitScm
        assertEquals("develop", gitScm.branch)
        assertEquals(credId, gitScm.credentialsId)
        assertEquals(false, gitScm.changelog)
        assertEquals(false, gitScm.poll)
        assertEquals("src", gitScm.relativeTargetDir)
    }

    @Test
    fun `checkout accepts Scm directly`() {
        val scope = StageScope("Test")
        val scm: Scm = GitScm("https://github.com/example/repo.git")
        scope.checkout(scm)

        // If this compiles, checkout(scm: Scm) works
        // The step was added to the internal list
    }

    @Test
    fun `git shorthand desugars to checkout with scmGit`() {
        val scope = StageScope("Test")
        // git(...) calls checkout(scmGit(...))
        // Just verify it compiles
        scope.git("https://github.com/example/repo.git", "main", null, true, true)
    }
}
