package dev.rubentxu.pipeline.casc

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
) {
    companion object {
        fun fromMap(map: Map<*, *>): KubernetesConfig {
            val templatesMap: List<Map<String, Any>> = map.validateAndGet("kubernetes.templates").isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>

            val templates: List<Template> = templatesMap.map {
                return@map Template.fromMap(it)
            }
            return KubernetesConfig(
                name = map.validateAndGet("kubernetes.name").isString().throwIfInvalid("name is required in KubernetesConfig"),
                serverUrl = map.validateAndGet("kubernetes.serverUrl").isString().throwIfInvalid("serverUrl is required in KubernetesConfig"),
                serverCertificate = map.validateAndGet("kubernetes.serverCertificate").isString().throwIfInvalid("serverCertificate is required in KubernetesConfig"),
                skipTlsVerify = map.validateAndGet("kubernetes.skipTlsVerify").isBoolean().defaultValueIfInvalid(false) as Boolean,
                credentialsId = map.validateAndGet("kubernetes.credentialsId").isString().throwIfInvalid("credentialsId is required in KubernetesConfig"),
                namespace = map.validateAndGet("kubernetes.namespace").isString().throwIfInvalid("namespace is required in KubernetesConfig"),
                pipelineUrl = map.validateAndGet("kubernetes.pipelineUrl").isString().throwIfInvalid("pipelineUrl is required in KubernetesConfig"),
                pipelineTunnel = map.validateAndGet("kubernetes.pipelineTunnel").isString().throwIfInvalid("pipelineTunnel is required in KubernetesConfig"),
                containerCapStr = map.validateAndGet("kubernetes.containerCapStr").isNumber().defaultValueIfInvalid(10) as Int,
                maxRequestsPerHostStr = map.validateAndGet("kubernetes.maxRequestsPerHostStr").isNumber().defaultValueIfInvalid(32) as Int,
                retentionTimeout = map.validateAndGet("kubernetes.retentionTimeout").isNumber().defaultValueIfInvalid(5) as Int,
                connectTimeout = map.validateAndGet("kubernetes.connectTimeout").isNumber().defaultValueIfInvalid(5) as Int,
                readTimeout = map.validateAndGet("kubernetes.readTimeout").isNumber().defaultValueIfInvalid(15) as Int,
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
    val envVars: List<EnvVar>?
) {
    companion object {
        fun fromMap(it: Map<String, Any>): Template {
            val volumesMap: List<Map<String, Any>> = it.validateAndGet("volumes").isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>
            val volumes: List<Volume> = volumesMap.map {
                return@map Volume.fromMap(it)
            }

            val containersMap: List<Map<String, Any>> = it.validateAndGet("containers").isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>
            val containers: List<Container> = containersMap.map {
                return@map Container.fromMap(it)
            }

            val imagePullSecretsMap: List<Map<String, Any>> = it.validateAndGet("imagePullSecrets").isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>
            val imagePullSecrets: List<ImagePullSecret> = imagePullSecretsMap.map {
                return@map ImagePullSecret.fromMap(it)
            }

            val envVarsMap: List<Map<String, Any>> = it.validateAndGet("envVars").isList().defaultValueIfInvalid(emptyList<Map<String, Any>>()) as List<Map<String, Any>>
            val envVars: List<EnvVar> = envVarsMap.map {
                val keyPair = it.get("envVar") as Map<String, Any>
                val key = keyPair.get("key") as String
                val value = keyPair.get("value") as Any
                return@map EnvVar(key, value)
            }

            return Template(
                name = it.validateAndGet("name").isString().throwIfInvalid("name is required in Template"),
                serviceAccount = it.validateAndGet("serviceAccount").isString().defaultValueIfInvalid("") as String,
                instanceCap = it.validateAndGet("instanceCap").isNumber().defaultValueIfInvalid(10) as Int,
                idleMinutes = it.validateAndGet("idleMinutes").isNumber().defaultValueIfInvalid(5) as Int,
                label = it.validateAndGet("label").isString().defaultValueIfInvalid("") as String,
                showRawYaml = it.validateAndGet("showRawYaml").isBoolean().defaultValueIfInvalid(false) as Boolean,
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
) {
    companion object {
        fun fromMap(it: Map<String, Any>): Volume {
            val hostPathVolumeMap: Map<String, Any> = it.validateAndGet("hostPathVolume").isMap().defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>
            val emptyDirVolumeMap: Map<String, Any> = it.validateAndGet("emptyDirVolume").isMap().defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>
            val configMapVolumeMap: Map<String, Any> = it.validateAndGet("configMapVolume").isMap().defaultValueIfInvalid(emptyMap<String, Any>()) as Map<String, Any>

            return Volume(
                hostPathVolume = HostPathVolume.fromMap(hostPathVolumeMap),
                emptyDirVolume = EmptyDirVolume.fromMap(emptyDirVolumeMap),
                configMapVolume = ConfigMapVolume.fromMap(configMapVolumeMap)
            )
        }
    }
}

data class HostPathVolume(
    val mountPath: String,
    val hostPath: String
) {
    companion object {
        fun fromMap(hostPathVolumeMap: Map<String, Any>): HostPathVolume? {
            if (hostPathVolumeMap.isEmpty()) return null
            return HostPathVolume(
                mountPath = hostPathVolumeMap.validateAndGet("mountPath").isString().throwIfInvalid("mountPath is required in HostPathVolume"),
                hostPath = hostPathVolumeMap.validateAndGet("hostPath").isString().throwIfInvalid("hostPath is required in HostPathVolume")
            )
        }
    }
}

data class EmptyDirVolume(
    val memory: Boolean,
    val mountPath: String
) {
    companion object {
        fun fromMap(emptyDirVolumeMap: Map<String, Any>): EmptyDirVolume? {
            if (emptyDirVolumeMap.isEmpty()) return null
            return EmptyDirVolume(
                memory = emptyDirVolumeMap.validateAndGet("memory").isBoolean().defaultValueIfInvalid(false) as Boolean,
                mountPath = emptyDirVolumeMap.validateAndGet("mountPath").isString().throwIfInvalid("mountPath is required in EmptyDirVolume")
            )
        }
    }
}

data class ConfigMapVolume(
    val configMapName: String,
    val mountPath: String,
    val subPath: String
) {
    companion object {
        fun fromMap(configMapVolumeMap: Map<String, Any>): ConfigMapVolume? {
            if (configMapVolumeMap.isEmpty()) return null
            return ConfigMapVolume(
                configMapName = configMapVolumeMap.validateAndGet("configMapName").isString().throwIfInvalid("configMapName is required in ConfigMapVolume"),
                mountPath = configMapVolumeMap.validateAndGet("mountPath").isString().throwIfInvalid("mountPath is required in ConfigMapVolume"),
                subPath = configMapVolumeMap.validateAndGet("subPath").isString().throwIfInvalid("subPath is required in ConfigMapVolume")
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
) {
    companion object {
        fun fromMap(it: Map<String, Any>): Container {
            return Container(
                name = it.validateAndGet("name").isString().throwIfInvalid("name is required in Container"),
                image = it.validateAndGet("image").isString().throwIfInvalid("image is required in Container"),
                privileged = it.validateAndGet("privileged").isBoolean().defaultValueIfInvalid(false) as Boolean,
                alwaysPullImage = it.validateAndGet("alwaysPullImage").isBoolean().defaultValueIfInvalid(false) as Boolean,
                command = it.validateAndGet("command").isString().defaultValueIfInvalid("") as String,
                args = it.validateAndGet("args").isString().defaultValueIfInvalid("") as String,
                workingDir = it.validateAndGet("workingDir").isString().defaultValueIfInvalid("") as String,
                ttyEnabled = it.validateAndGet("ttyEnabled").isBoolean().defaultValueIfInvalid(false) as Boolean,
                resourceRequestCpu = it.validateAndGet("resourceRequestCpu").isString().defaultValueIfInvalid("") as String,
                resourceRequestMemory = it.validateAndGet("resourceRequestMemory").isString().defaultValueIfInvalid("") as String,
                resourceLimitCpu = it.validateAndGet("resourceLimitCpu").isString().defaultValueIfInvalid("") as String,
                resourceLimitMemory = it.validateAndGet("resourceLimitMemory").isString().defaultValueIfInvalid("") as String
            )
        }
    }
}

data class ImagePullSecret(
    val name: String
) {
    companion object {
        fun fromMap(it: Map<String, Any>): ImagePullSecret {
            return ImagePullSecret(
                name = it.validateAndGet("name").isString().throwIfInvalid("name is required in ImagePullSecret")
            )
        }
    }
}

class EnvVar(override val key: String, override val value: Any) : Map.Entry<String, Any>  {

}

data class KeyPair(
    val key: String,
    val value: String
)
