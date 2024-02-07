package dev.rubentxu.pipeline.backend.factories.agents


import arrow.core.raise.result
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parZip
import dev.rubentxu.pipeline.backend.coroutines.parZipResult
import dev.rubentxu.pipeline.backend.factories.PipelineDomainFactory
import dev.rubentxu.pipeline.backend.mapper.PropertySet
import dev.rubentxu.pipeline.backend.mapper.required
import dev.rubentxu.pipeline.model.IDComponent
import dev.rubentxu.pipeline.model.agents.*
import dev.rubentxu.pipeline.model.steps.EnvVars

class KubernetesAgentFactory {

    companion object : PipelineDomainFactory<List<KubernetesAgent>> {
        override val rootPath: String = "agents.clouds[*].kubernetes"

        override suspend fun create(data: PropertySet): Result<List<KubernetesAgent>> = runCatching {
            getRootListPropertySet(data)
                ?.parMap { properties ->
                    val id = IDComponent.create(properties.required<String>("id").getOrThrow())
                    val labels = properties.required<List<String>>("labels").getOrThrow()
                    val templates = KubernetesTemplateFactory.create(properties).getOrThrow()

                    kubernetesAgent(id, properties, templates, labels).getOrThrow()
                } ?: emptyList()
        }

        private suspend fun kubernetesAgent(
            id: IDComponent,
            properties: PropertySet,
            templates: List<K8sTemplate>,
            labels: List<String>,
        ): Result<KubernetesAgent> {
            return parZipResult(
                { properties.required<String>("name") },
                { properties.required<String>("serverUrl") },
                { properties.required<String>("serverCertificate") },
                { properties.required<Boolean>("skipTlsVerify") },
                { properties.required<String>("credentialsId") },
                { properties.required<String>("namespace") },
                { properties.required<String>("pipelineUrl") },
                { properties.required<String>("pipelineTunnel") },
                { properties.required<Int>("containerCapStr") },

                ) {
                    name, serverUrl, serverCertificate, skipTlsVerify, credentialsId, namespace, pipelineUrl, pipelineTunnel,
                    containerCapStr,
                ->

                val maxRequestsPerHostStr = properties.required<Int>("maxRequestsPerHostStr")
                val retentionTimeout = properties.required<Int>("retentionTimeout")
                val connectTimeout = properties.required<Int>("connectTimeout")
                val readTimeout = properties.required<Int>("readTimeout")

                KubernetesAgent(
                    id = id,
                    name = name,
                    serverUrl = serverUrl,
                    serverCertificate = serverCertificate,
                    skipTlsVerify = skipTlsVerify,
                    credentialsId = credentialsId,
                    namespace = namespace,
                    pipelineUrl = pipelineUrl,
                    pipelineTunnel = pipelineTunnel,
                    containerCapStr = containerCapStr,
                    maxRequestsPerHostStr = maxRequestsPerHostStr.getOrThrow(),
                    retentionTimeout = retentionTimeout.getOrThrow(),
                    connectTimeout = connectTimeout.getOrThrow(),
                    readTimeout = readTimeout.getOrThrow(),
                    templates = templates,
                    labels = labels,
                    type = "kubernetes"
                )
            }



            KubernetesAgent(
                id = id,
                name = properties.required<String>("name").getOrThrow() as String,
                serverUrl = properties.required<String>("serverUrl").getOrThrow(),
                serverCertificate = properties.required<String>("serverCertificate").getOrThrow(),
                skipTlsVerify = properties.required<Boolean>("skipTlsVerify").getOrThrow(),
                credentialsId = properties.required<String>("credentialsId").getOrThrow(),
                namespace = properties.required<String>("namespace").getOrThrow(),
                pipelineUrl = properties.required<String>("pipelineUrl").getOrThrow(),
                pipelineTunnel = properties.required<String>("pipelineTunnel").getOrThrow(),
                containerCapStr = properties.required<Int>("containerCapStr").getOrThrow(),
                maxRequestsPerHostStr = properties.required<Int>("maxRequestsPerHostStr").getOrThrow(),
                retentionTimeout = properties.required<Int>("retentionTimeout").getOrThrow(),
                connectTimeout = properties.required<Int>("connectTimeout").getOrThrow(),
                readTimeout = properties.required<Int>("readTimeout").getOrThrow(),
                templates = templates,
                labels = labels,
                type = "kubernetes"
            )
        }
    }


}

class KubernetesTemplateFactory {

    companion object : PipelineDomainFactory<List<K8sTemplate>> {
        override val rootPath: String = "templates"

        override suspend fun create(kubernetesProperties: PropertySet): Result<List<K8sTemplate>> = result {
            getRootListPropertySet(kubernetesProperties)
                ?.parMap { templateProperties ->
                    createK8sTemplate(templateProperties).bind()
                } ?: emptyList()
        }

        suspend fun createK8sTemplate(templateProperties: PropertySet): Result<K8sTemplate> {
            return parZipResult(
                { templateProperties.required<String>("name") },
                { templateProperties.required<String>("serviceAccount") },
                { templateProperties.required<Int>("instanceCap") },
                { templateProperties.required<Int>("idleMinutes") },
                { templateProperties.required<String>("label") },
                { templateProperties.required<Boolean>("showRawYaml") },
                { VolumeFactory.create(templateProperties) },
                { ContainerFactory.create(templateProperties) },
                { templateProperties.required<List<String>>("imagePullSecrets[*].name") }

            ) { name, serviceAccount, instanceCap, idleMinutes, label, showRawYaml, volumes, containers, imagePullSecrets ->
                val envVars: EnvVars = result {
                    val envVarsMap = templateProperties.required<List<PropertySet>>("envVars").bind()
                        .map {
                            it.required<String>("key").getOrThrow() to it.required<String>("value").bind()
                        }.toMap()

                    EnvVars(envVarsMap)
                }.getOrElse { EnvVars(emptyMap()) }


                K8sTemplate(
                    name = name,
                    serviceAccount = serviceAccount,
                    instanceCap = instanceCap,
                    idleMinutes = idleMinutes,
                    label = label,
                    showRawYaml = showRawYaml,
                    volumes = volumes,
                    containers = containers,
                    imagePullSecrets = imagePullSecrets,
                    envVars = envVars
                )
            }

        }
    }
}

class VolumeFactory {

    companion object : PipelineDomainFactory<List<Volume>> {
        override val rootPath: String = "volumes"


        override suspend fun create(templateProperties: PropertySet): Result<List<Volume>> {
            return createVolume(templateProperties)
        }

        private suspend fun createVolume(volumeProperties: PropertySet): Result<List<Volume>> =
            runCatching {
                parZip(
                    { HostPathVolumeFactory.create(volumeProperties).getOrThrow() },
                    { EmptyDirVolumeFactory.create(volumeProperties).getOrThrow() },
                    { ConfigMapVolumeFactory.create(volumeProperties).getOrThrow() }
                ) { hostPathVolume, emptyDirVolume, configMapVolume ->
                    buildList {
                        addAll(hostPathVolume)
                        addAll(emptyDirVolume)
                        addAll(configMapVolume)
                    }
                }
            }
    }
}

class HostPathVolumeFactory {

    companion object : PipelineDomainFactory<List<HostPathVolume>> {
        override val rootPath: String = "volumes[*].hostPathVolume"


        override suspend fun create(data: PropertySet): Result<List<HostPathVolume>> =
            runCatching {
                getRootListPropertySet(data)
                    ?.parMap { hostPathVolumeProperties ->
                        HostPathVolume(
                            mountPath = hostPathVolumeProperties.required<String>("mountPath").getOrThrow(),
                            hostPath = hostPathVolumeProperties.required<String>("hostPath").getOrThrow()
                        )
                    } ?: emptyList()
            }
    }
}

class EmptyDirVolumeFactory {
    companion object : PipelineDomainFactory<List<EmptyDirVolume>> {
        override val rootPath: String = "volumes[*].emptyDirVolume"


        override suspend fun create(data: PropertySet): Result<List<EmptyDirVolume>> =
            runCatching {
                getRootListPropertySet(data)
                    ?.parMap { emptyDirVolumeProperties ->
                        EmptyDirVolume(
                            memory = emptyDirVolumeProperties.required<Boolean>("memory").getOrThrow(),
                            mountPath = emptyDirVolumeProperties.required<String>("mountPath").getOrThrow()
                        )
                    } ?: emptyList()
            }
    }
}

class ConfigMapVolumeFactory {

    companion object : PipelineDomainFactory<List<ConfigMapVolume>> {
        override val rootPath: String = "volumes[*].configMapVolume"


        override suspend fun create(data: PropertySet): Result<List<ConfigMapVolume>> =
            runCatching {
                getRootListPropertySet(data)
                    ?.parMap { configMapVolumeProperties ->
                        ConfigMapVolume(
                            configMapName = configMapVolumeProperties.required<String>("configMapName")
                                .getOrThrow(),
                            mountPath = configMapVolumeProperties.required<String>("mountPath").getOrThrow(),
                            subPath = configMapVolumeProperties.required<String>("subPath").getOrThrow()
                        )
                    } ?: emptyList()
            }
    }
}


class ContainerFactory {

    companion object : PipelineDomainFactory<List<Container>> {
        override val rootPath: String = "containers"


        override suspend fun create(data: PropertySet): Result<List<Container>> =
            runCatching {
                getRootListPropertySet(data)
                    ?.parMap { containerProperties ->
                        createContainer(containerProperties).getOrThrow()
                    } ?: emptyList()
            }

        private suspend fun createContainer(data: PropertySet): Result<Container> {
            return parZipResult(
                { data.required<String>("name") },
                { data.required<String>("image") },
                { data.required<Boolean>("privileged") },
                { data.required<Boolean>("alwaysPullImage") },
                { data.required<String>("command") },
                { data.required<String>("args") },
                { data.required<String>("workingDir") },
                { data.required<Boolean>("ttyEnabled") },
                { data.required<String>("resourceRequestCpu") }
            ) { name, image, privileged, alwaysPullImage, command, args, workingDir, ttyEnabled, resourceRequestCpu ->

                val resourceRequestMemory = data.required<String>("resourceRequestMemory").getOrThrow()
                val resourceLimitCpu = data.required<String>("resourceLimitCpu").getOrThrow()
                val resourceLimitMemory = data.required<String>("resourceLimitMemory").getOrThrow()

                Container(
                    name = name,
                    image = image,
                    privileged = privileged,
                    alwaysPullImage = alwaysPullImage,
                    command = command,
                    args = args,
                    workingDir = workingDir,
                    ttyEnabled = ttyEnabled,
                    resourceRequestCpu = resourceRequestCpu,
                    resourceRequestMemory = resourceRequestMemory,
                    resourceLimitCpu = resourceLimitCpu,
                    resourceLimitMemory = resourceLimitMemory
                )
            }
        }
    }
}
