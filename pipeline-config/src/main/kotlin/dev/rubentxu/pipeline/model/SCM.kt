package dev.rubentxu.pipeline.model

import dev.rubentxu.pipeline.model.config.Configuration
import dev.rubentxu.pipeline.model.config.MapConfigurationBuilder
import dev.rubentxu.pipeline.validation.validate
import dev.rubentxu.pipeline.validation.validateAndGet

data class ScmConfig(
    val gitscm: GitScmConfig
) {
    companion object {
        fun fromMap(map: Map<*, *>): ScmConfig {
            val gitscmMap = map.validateAndGet("gitscm")
                .isMap()
                .defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>

            val gitscm = GitScmConfig.fromMap(gitscmMap)

            return ScmConfig(
                gitscm = gitscm
            )
        }
    }
}

data class GitScmConfig(
    val globalConfigName: String,
    val globalConfigEmail: String,
    val userRemoteConfigs: List<UserRemoteConfig>,
    val branches: List<BranchSpec>,
    val extensions: List<SCMExtension>
) {
    companion object {
        fun fromMap(gitscmMap: Map<String, Any>): GitScmConfig {
            val userRemoteConfigsMap = gitscmMap.validateAndGet("userRemoteConfigs")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val userRemoteConfigs = userRemoteConfigsMap.map {
                return@map UserRemoteConfig(
                    name = it.validateAndGet("name").isString().dependsOn(it,"url","credentialsId").defaultValueIfInvalid(""),
                    url = it.validateAndGet("url").dependsOn(it,"name","credentialsId").isString().defaultValueIfInvalid(""),
                    refspec = it.validateAndGet("refspec").isString().defaultValueIfInvalid(""),
                    credentialsId = it.validateAndGet("credentialsId").dependsOn(it,"name","url").isString().defaultValueIfInvalid("")
                )
            }

            val branchesMap = gitscmMap.validateAndGet("branches")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val branches = branchesMap.map {
                return@map BranchSpec(
                    name = it.validateAndGet("name").isString().defaultValueIfInvalid("")
                )
            }

            val extensionsMap = gitscmMap.validateAndGet("extensions")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val extensions : List<SCMExtension> = extensionsMap.map {
                return@map when (it?.keys?.first()) {
                    "sparseCheckoutPaths" -> SparseCheckoutPath.build(it.get(it?.keys?.first()) as Map<String, Any>)
                    "cloneOption" -> CloneOption.build(it.get(it?.keys?.first()) as Map<String, Any>)
                    "relativeTargetDirectory" -> RelativeTargetDirectory.build(it.get(it?.keys?.first()) as Map<String, Any>)
                    else -> {
                        throw IllegalArgumentException("Invalid extension type for '${it?.keys?.first()}'")
                    }
                }
            }

            return GitScmConfig(
                globalConfigName = gitscmMap.validateAndGet("globalConfigName").isString().defaultValueIfInvalid("pipelineUser"),
                globalConfigEmail = gitscmMap.validateAndGet("globalConfigEmail").isString().defaultValueIfInvalid(""),
                userRemoteConfigs = userRemoteConfigs,
                branches = branches,
                extensions = extensions
            )
        }
    }
}

data class UserRemoteConfig(
    val name: String,
    val url: String,
    val refspec: String,
    val credentialsId: String
)

data class BranchSpec(
    val name: String
)

interface SCMExtension: Configuration



data class SparseCheckoutPath(val sparseCheckoutPath: String) : SCMExtension {

    companion object : MapConfigurationBuilder<SparseCheckoutPath> {
        override fun build(map: Map<String, Any>): SparseCheckoutPath {
            val sparseCheckoutPath = map.validate("sparseCheckoutPath")
                .isString()
                .defaultValueIfInvalid("") as String
            return SparseCheckoutPath(sparseCheckoutPath)
        }
    }

}


data class CloneOption(
    val depth: Int,
    val timeout: Int,
    val noTags: Boolean,
    val shallow: Boolean
) : SCMExtension {

    companion object : MapConfigurationBuilder<CloneOption> {
        override fun build(map: Map<String, Any>): CloneOption {
            val depth = map.validateAndGet("depth")
                .isNumber()
                .defaultValueIfInvalid(0) as Int

            val timeout = map.validateAndGet("timeout")
                .isNumber()
                .defaultValueIfInvalid(120) as Int

            val noTags = map.validateAndGet("noTags")
                .isBoolean()
                .defaultValueIfInvalid(false) as Boolean

            val shallow = map.validate("shallow")
                .isBoolean()
                .defaultValueIfInvalid(false) as Boolean

            return CloneOption(depth, timeout, noTags, shallow)
        }
    }



}

data class RelativeTargetDirectory(val relativeTargetDirectory: String) : SCMExtension {

        companion object : MapConfigurationBuilder<RelativeTargetDirectory> {
            override fun build(map: Map<String, Any>): RelativeTargetDirectory {
                val relativeTargetDirectory = map.validate("relativeTargetDirectory")
                    .isString()
                    .defaultValueIfInvalid("") as String
                return RelativeTargetDirectory(relativeTargetDirectory)
            }
        }

}
