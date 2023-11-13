package dev.rubentxu.pipeline.model.scm

import java.util.concurrent.ConcurrentHashMap

class GitSCM() {
    var url: String? = null
    var branch: String = "master"
    var credentialsId: String? = null
    var changelog: Boolean? = null
    var poll: Boolean? = null
    var doGenerateSubmoduleConfigurations: Boolean = false
    val extensions: MutableMap<Class<out ScmExtensions>, ScmExtensions> = ConcurrentHashMap()

    constructor(configMap: Map<String, Any>) : this() {
        val scm = configMap["scm"] as? Map<String, Any> ?: error("GitSCM configuration not found")
        val branches = scm["branches"] as? List<Map<String, Any>>
        val userRemoteConfigs = scm["userRemoteConfigs"] as? List<Map<String, Any>>

        branch(branches?.first()?.get("name") as? String ?: error("GitSCM branches not found"))
        doGenerateSubmoduleConfigurations = scm["doGenerateSubmoduleConfigurations"] as? Boolean ?: false
        url(userRemoteConfigs?.first()?.get("url") as? String ?: error("GitSCM userRemoteConfigs url not found"))
        credentialsId(userRemoteConfigs.first()["credentialsId"] as? String ?: error("GitSCM credentialsId url not found"))
        resolveExtensions(scm["extensions"] as? List<Map<String, Any>> ?: emptyList())
    }

    private fun resolveExtensions(extensions: List<Map<String, Any>>) {
        extensions.forEach {
            when (it["\$class"]) {
                "CloneOption" -> cloneOption(
                    it["depth"] as Int,
                    it["timeout"] as Int,
                    it["noTags"] as Boolean,
                    it["shallow"] as Boolean
                )
                "RelativeTargetDirectory" -> relativeTargetDirectory(it["relativeTargetDir"] as String)
                "SparseCheckoutPaths" -> sparseCheckoutPath((it["sparseCheckoutPaths"] as List<Map<String, Any>>)?.first()?.get("path") as String)
                // Agregar más casos según sea necesario
            }
        }
    }

    fun url(url: String) = apply { this.url = url }

    fun branch(branch: String) = apply { this.branch = branch }

    fun credentialsId(credentialsId: String) = apply { this.credentialsId = credentialsId }

    fun changelog(changelog: Boolean) = apply { this.changelog = changelog }

    fun poll(poll: Boolean) = apply { this.poll = poll }

    fun sparseCheckoutPath(sparseCheckoutPath: String) = apply {
        sparseCheckoutPath?.let {
            this.extensions[SparseCheckoutPath::class.java] = SparseCheckoutPath(it)
        }
    }

    fun relativeTargetDirectory(relativeTargetDirectory: String) = apply {
        relativeTargetDirectory?.let {
            this.extensions[RelativeTargetDirectory::class.java] = RelativeTargetDirectory(it)
        }
    }

    fun cloneOption(depth: Int = 0, timeout: Int = 120, noTags: Boolean = false, shallow: Boolean = false) = apply {
        this.extensions[CloneOption::class.java] = CloneOption(depth, timeout, noTags, shallow)
    }

    fun toMap(): Map<String, Any> {
        val resolveExtensions = extensions.map { it.value.toMap() }
        val scmMap = mutableMapOf<String, Any>(
            "scm" to mapOf(
                "\$class" to "GitSCM",
                "branches" to listOf(mapOf("name" to branch)),
                "doGenerateSubmoduleConfigurations" to doGenerateSubmoduleConfigurations,
                "extensions" to resolveExtensions,
                "submoduleCfg" to emptyList<Any>(),
                "userRemoteConfigs" to listOf(mapOf("credentialsId" to credentialsId, "url" to url))
            )
        )

        changelog?.let { scmMap["changelog"] = it }
        poll?.let { scmMap["poll"] = it }
        return scmMap
    }
}
