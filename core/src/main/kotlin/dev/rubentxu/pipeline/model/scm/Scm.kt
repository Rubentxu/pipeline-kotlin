package dev.rubentxu.pipeline.model.scm

sealed class Scm {
    abstract val url: String
    abstract val credentialsId: String?
}

data class GitScm(
    override val url: String,
    val branch: String = "main",
    override val credentialsId: String? = null,
    val doGenerateSubmoduleConfigurations: Boolean = false,
    val extensions: List<GitExtension> = emptyList()
) : Scm()

sealed class GitExtension

data class CloneOption(
    val shallow: Boolean = false,
    val noTags: Boolean = false,
    val reference: String? = null,
    val timeout: Int = 10
) : GitExtension()

data class CheckoutOption(
    val timeout: Int = 10
) : GitExtension()

data class SubmoduleOption(
    val disableSubmodules: Boolean = false,
    val recursiveSubmodules: Boolean = false,
    val trackingSubmodules: Boolean = false,
    val reference: String? = null,
    val timeout: Int = 10
) : GitExtension()

data class UserIdentity(
    val name: String,
    val email: String
) : GitExtension()

data class GitLfsExtension(
    val gitLfsExtension: Boolean = true
) : GitExtension()

data class WipeWorkspace(
    val wipeWorkspace: Boolean = true
) : GitExtension()