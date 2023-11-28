package dev.rubentxu.pipeline.model.agents

import dev.rubentxu.pipeline.model.config.Configuration
import dev.rubentxu.pipeline.model.config.MapConfigurationBuilder
import dev.rubentxu.pipeline.steps.EnvVars
import dev.rubentxu.pipeline.validation.validateAndGet

data class KubernetesConfig(
    val name: String,
    val serverUrl: String,
    val serverCertificate: String,
    val skipTlsVerify: Boolean,
    val credentialsId: String,
    val namespace: String,
    val pipelineUrl: String,
    val pipelineTunnel: String,
    val containerCapStr: Int,
    val maxRequestsPerHostStr: Int,
    val retentionTimeout: Int,
    val connectTimeout: Int,
    val readTimeout: Int,
    val templates: List<Template>
): Configuration {
    companion object: MapConfigurationBuilder<KubernetesConfig> {
        override fun build(data: Map<String, Any>): KubernetesConfig {
            val templatesMap: List<Map<String, Any>> = data.validateAndGet("kubernetes.templates").isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val templates: List<Template> = templatesMap.map {
                return@map Template.build(it)
            }
            return KubernetesConfig(
                name = data.validateAndGet("kubernetes.name").isString().throwIfInvalid("name is required in KubernetesConfig"),
                serverUrl = data.validateAndGet("kubernetes.serverUrl").isString().throwIfInvalid("serverUrl is required in KubernetesConfig"),
                serverCertificate = data.validateAndGet("kubernetes.serverCertificate").isString().throwIfInvalid("serverCertificate is required in KubernetesConfig"),
                skipTlsVerify = data.validateAndGet("kubernetes.skipTlsVerify").isBoolean().defaultValueIfInvalid(false) as Boolean,
                credentialsId = data.validateAndGet("kubernetes.credentialsId").isString().throwIfInvalid("credentialsId is required in KubernetesConfig"),
                namespace = data.validateAndGet("kubernetes.namespace").isString().throwIfInvalid("namespace is required in KubernetesConfig"),
                pipelineUrl = data.validateAndGet("kubernetes.pipelineUrl").isString().throwIfInvalid("pipelineUrl is required in KubernetesConfig"),
                pipelineTunnel = data.validateAndGet("kubernetes.pipelineTunnel").isString().throwIfInvalid("pipelineTunnel is required in KubernetesConfig"),
                containerCapStr = data.validateAndGet("kubernetes.containerCapStr").isNumber().defaultValueIfInvalid(10) as Int,
                maxRequestsPerHostStr = data.validateAndGet("kubernetes.maxRequestsPerHostStr").isNumber().defaultValueIfInvalid(32) as Int,
                retentionTimeout = data.validateAndGet("kubernetes.retentionTimeout").isNumber().defaultValueIfInvalid(5) as Int,
                connectTimeout = data.validateAndGet("kubernetes.connectTimeout").isNumber().defaultValueIfInvalid(5) as Int,
                readTimeout = data.validateAndGet("kubernetes.readTimeout").isNumber().defaultValueIfInvalid(15) as Int,
                templates = templates
            )

        }
    }
}

data class Template(
    val name: String,
    val serviceAccount: String?,
    val instanceCap: Int?,
    val idleMinutes: Int?,
    val label: String?,
    val showRawYaml: Boolean?,
    val volumes: List<Volume>?,
    val containers: List<Container>,
    val imagePullSecrets: List<ImagePullSecret>?,
    val envVars: EnvVars?
): Configuration {
    companion object: MapConfigurationBuilder<Template> {
        override fun build(data: Map<String, Any>): Template {
            val volumesMap: List<Map<String, Any>> = data.validateAndGet("volumes")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val volumes: List<Volume> = volumesMap.map {
                return@map Volume.build(it)
            }

            val containersMap: List<Map<String, Any>> = data.validateAndGet("containers")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val containers: List<Container> = containersMap.map {
                return@map Container.build(it)
            }

            val imagePullSecretsMap: List<Map<String, Any>> = data.validateAndGet("imagePullSecrets")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val imagePullSecrets: List<ImagePullSecret> = imagePullSecretsMap.map {
                return@map ImagePullSecret.build(it)
            }

            val envVarsListMap: List<Map<String, Any>> = data.validateAndGet("envVars")
                .isList()
                .defaultValueIfInvalid(emptyList<Map<String, String>>()) as List<Map<String, Any>>

            val envVarsMap =  envVarsListMap
                .map { it.mapValues { it.value.toString() } }
                .fold(emptyMap<String, String>()) { acc, map ->
                    acc + map
                }

            val envVars = EnvVars(envVarsMap)

            return Template(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in Template"),
                serviceAccount = data.validateAndGet("serviceAccount").isString().defaultValueIfInvalid("") as String,
                instanceCap = data.validateAndGet("instanceCap").isNumber().defaultValueIfInvalid(10) as Int,
                idleMinutes = data.validateAndGet("idleMinutes").isNumber().defaultValueIfInvalid(5) as Int,
                label = data.validateAndGet("label").isString().defaultValueIfInvalid("") as String,
                showRawYaml = data.validateAndGet("showRawYaml").isBoolean().defaultValueIfInvalid(false) as Boolean,
                volumes = volumes,
                containers = containers,
                imagePullSecrets = imagePullSecrets,
                envVars = envVars
            )
        }
    }
}

data class Volume(
    val hostPathVolume: HostPathVolume?,
    val emptyDirVolume: EmptyDirVolume?,
    val configMapVolume: ConfigMapVolume?
): Configuration {
    companion object: MapConfigurationBuilder<Volume> {
        override fun build(data: Map<String, Any>): Volume {
            val hostPathVolumeMap: Map<String, Any> = data.validateAndGet("hostPathVolume")
                .isMap()
                .defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>

            val emptyDirVolumeMap: Map<String, Any> = data.validateAndGet("emptyDirVolume")
                .isMap()
                .defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>

            val configMapVolumeMap: Map<String, Any> = data.validateAndGet("configMapVolume")
                .isMap()
                .defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>

            return Volume(
                hostPathVolume = HostPathVolume.build(hostPathVolumeMap),
                emptyDirVolume = EmptyDirVolume.build(emptyDirVolumeMap),
                configMapVolume = ConfigMapVolume.build(configMapVolumeMap)
            )
        }
    }
}

data class HostPathVolume(
    val mountPath: String,
    val hostPath: String
): Configuration {
    companion object: MapConfigurationBuilder<HostPathVolume> {
        override fun build(data: Map<String, Any>): HostPathVolume? {
            if (data.isEmpty()) return null
            return HostPathVolume(
                mountPath = data.validateAndGet("mountPath").isString().throwIfInvalid("mountPath is required in HostPathVolume"),
                hostPath = data.validateAndGet("hostPath").isString().throwIfInvalid("hostPath is required in HostPathVolume")
            )
        }
    }
}

data class EmptyDirVolume(
    val memory: Boolean,
    val mountPath: String
): Configuration {
    companion object: MapConfigurationBuilder<EmptyDirVolume> {
        override fun build(data: Map<String, Any>): EmptyDirVolume? {
            if (data.isEmpty()) return null
            return EmptyDirVolume(
                memory = data.validateAndGet("memory").isBoolean().defaultValueIfInvalid(false) as Boolean,
                mountPath = data.validateAndGet("mountPath").isString().throwIfInvalid("mountPath is required in EmptyDirVolume")
            )
        }
    }
}

data class ConfigMapVolume(
    val configMapName: String,
    val mountPath: String,
    val subPath: String
): Configuration {
    companion object: MapConfigurationBuilder<ConfigMapVolume> {
        override fun build(data: Map<String, Any>): ConfigMapVolume? {
            if (data.isEmpty()) return null
            return ConfigMapVolume(
                configMapName = data.validateAndGet("configMapName").isString().throwIfInvalid("configMapName is required in ConfigMapVolume"),
                mountPath = data.validateAndGet("mountPath").isString().throwIfInvalid("mountPath is required in ConfigMapVolume"),
                subPath = data.validateAndGet("subPath").isString().throwIfInvalid("subPath is required in ConfigMapVolume")
            )
        }
    }
}

data class Container(
    val name: String,
    val image: String,
    val privileged: Boolean,
    val alwaysPullImage: Boolean,
    val command: String,
    val args: String,
    val workingDir: String,
    val ttyEnabled: Boolean,
    val resourceRequestCpu: String,
    val resourceRequestMemory: String,
    val resourceLimitCpu: String,
    val resourceLimitMemory: String
): Configuration {
    companion object: MapConfigurationBuilder<Container> {
        override fun build(data: Map<String, Any>): Container {
            return Container(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in Container"),
                image = data.validateAndGet("image").isString().throwIfInvalid("image is required in Container"),
                privileged = data.validateAndGet("privileged").isBoolean().defaultValueIfInvalid(false) as Boolean,
                alwaysPullImage = data.validateAndGet("alwaysPullImage").isBoolean().defaultValueIfInvalid(false) as Boolean,
                command = data.validateAndGet("command").isString().defaultValueIfInvalid("") as String,
                args = data.validateAndGet("args").isString().defaultValueIfInvalid("") as String,
                workingDir = data.validateAndGet("workingDir").isString().defaultValueIfInvalid("") as String,
                ttyEnabled = data.validateAndGet("ttyEnabled").isBoolean().defaultValueIfInvalid(false) as Boolean,
                resourceRequestCpu = data.validateAndGet("resourceRequestCpu").isString().defaultValueIfInvalid("") as String,
                resourceRequestMemory = data.validateAndGet("resourceRequestMemory").isString().defaultValueIfInvalid("") as String,
                resourceLimitCpu = data.validateAndGet("resourceLimitCpu").isString().defaultValueIfInvalid("") as String,
                resourceLimitMemory = data.validateAndGet("resourceLimitMemory").isString().defaultValueIfInvalid("") as String
            )
        }
    }
}

data class ImagePullSecret(
    val name: String
): Configuration {
    companion object: MapConfigurationBuilder<ImagePullSecret> {
        override fun build(data: Map<String, Any>): ImagePullSecret {
            return ImagePullSecret(
                name = data.validateAndGet("name").isString().throwIfInvalid("name is required in ImagePullSecret")
            )
        }
    }
}
