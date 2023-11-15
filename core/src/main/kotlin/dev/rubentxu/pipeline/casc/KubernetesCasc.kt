package dev.rubentxu.pipeline.casc

data class JenkinsConfig(
    val jenkins: Jenkins
)

data class Jenkins(
    val clouds: List<Cloud>
)


data class KubernetesConfig(
    val name: String,
    val serverUrl: String,
    val serverCertificate: String,
    val skipTlsVerify: Boolean,
    val credentialsId: String,
    val namespace: String,
    val jenkinsUrl: String,
    val jenkinsTunnel: String,
    val containerCapStr: Int,
    val maxRequestsPerHostStr: Int,
    val retentionTimeout: Int,
    val connectTimeout: Int,
    val readTimeout: Int,
    val templates: List<Template>
)

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
)

data class Volume(
    val hostPathVolume: HostPathVolume?,
    val emptyDirVolume: EmptyDirVolume?,
    val configMapVolume: ConfigMapVolume?
)

data class HostPathVolume(
    val mountPath: String,
    val hostPath: String
)

data class EmptyDirVolume(
    val memory: Boolean,
    val mountPath: String
)

data class ConfigMapVolume(
    val configMapName: String,
    val mountPath: String,
    val subPath: String
)

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
)

data class ImagePullSecret(
    val name: String
)

data class EnvVar(
    val envVar: KeyPair
)

data class KeyPair(
    val key: String,
    val value: String
)
