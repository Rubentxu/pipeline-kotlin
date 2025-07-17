package dev.rubentxu.pipeline.steps.extensions

import dev.rubentxu.pipeline.context.PipelineContext
import dev.rubentxu.pipeline.annotations.Step
import dev.rubentxu.pipeline.annotations.StepCategory
import dev.rubentxu.pipeline.annotations.SecurityLevel
import dev.rubentxu.pipeline.steps.builtin.*

/**
 * Kubernetes-related @Step functions with DSL v2 syntax.
 * 
 * These steps provide Kubernetes functionality with automatic PipelineContext injection
 * via the K2 compiler plugin, eliminating manual context access.
 */

/**
 * Applies Kubernetes manifests to a cluster.
 * 
 * @param manifestPath Path to YAML manifest file or directory
 * @param namespace Kubernetes namespace (optional)
 * @param kubeconfig Path to kubeconfig file (optional, uses default if not provided)
 * @param dryRun Whether to perform a dry run (default: false)
 * @param force Whether to force apply (default: false)
 * @return Apply result output
 */
@Step(
    name = "kubectlApply",
    description = "Apply Kubernetes manifests to cluster",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun kubectlApply(
    context: PipelineContext,
    manifestPath: String,
    namespace: String? = null,
    kubeconfig: String? = null,
    dryRun: Boolean = false,
    force: Boolean = false
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(manifestPath.isNotBlank()) { "Manifest path cannot be blank" }
    
    context.logger.info("+ kubectlApply: $manifestPath")
    
    // Verify kubectl is available
    sh(context, "kubectl version --client")
    
    // Verify manifest exists
    val fullManifestPath = context.workingDirectory.resolve(manifestPath)
    if (!fullManifestPath.toFile().exists()) {
        throw IllegalArgumentException("Manifest not found: $manifestPath")
    }
    
    // Build kubectl command
    val cmd = mutableListOf("kubectl apply -f $manifestPath")
    
    namespace?.let { cmd.add("-n $it") }
    kubeconfig?.let { cmd.add("--kubeconfig $it") }
    if (dryRun) cmd.add("--dry-run=client")
    if (force) cmd.add("--force")
    
    val command = cmd.joinToString(" ")
    val result = sh(context, command, returnStdout = true)
    
    context.logger.info("Successfully applied manifest: $manifestPath")
    return result
}

/**
 * Deletes Kubernetes resources.
 * 
 * @param resourceType Type of resource (e.g., "deployment", "service", "pod")
 * @param resourceName Name of the resource to delete
 * @param namespace Kubernetes namespace (optional)
 * @param kubeconfig Path to kubeconfig file (optional)
 * @param force Whether to force delete (default: false)
 * @param gracePeriod Grace period for deletion in seconds (optional)
 */
@Step(
    name = "kubectlDelete",
    description = "Delete Kubernetes resources",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun kubectlDelete(
    context: PipelineContext,
    resourceType: String,
    resourceName: String,
    namespace: String? = null,
    kubeconfig: String? = null,
    force: Boolean = false,
    gracePeriod: Int? = null
) {
    // Context is automatically injected by the K2 compiler plugin
    
    require(resourceType.isNotBlank()) { "Resource type cannot be blank" }
    require(resourceName.isNotBlank()) { "Resource name cannot be blank" }
    
    context.logger.info("+ kubectlDelete: $resourceType/$resourceName")
    
    val cmd = mutableListOf("kubectl delete $resourceType $resourceName")
    
    namespace?.let { cmd.add("-n $it") }
    kubeconfig?.let { cmd.add("--kubeconfig $it") }
    if (force) cmd.add("--force")
    gracePeriod?.let { cmd.add("--grace-period=$it") }
    
    val command = cmd.joinToString(" ")
    sh(context, command)
    
    context.logger.info("Successfully deleted $resourceType/$resourceName")
}

/**
 * Gets information about Kubernetes resources.
 * 
 * @param resourceType Type of resource (e.g., "pods", "services", "deployments")
 * @param resourceName Specific resource name (optional, gets all if not provided)
 * @param namespace Kubernetes namespace (optional)
 * @param kubeconfig Path to kubeconfig file (optional)
 * @param output Output format (default: "wide")
 * @return Resource information
 */
@Step(
    name = "kubectlGet",
    description = "Get Kubernetes resource information",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun kubectlGet(
    context: PipelineContext,
    resourceType: String,
    resourceName: String? = null,
    namespace: String? = null,
    kubeconfig: String? = null,
    output: String = "wide"
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(resourceType.isNotBlank()) { "Resource type cannot be blank" }
    
    context.logger.info("+ kubectlGet: $resourceType" + (resourceName?.let { "/$it" } ?: ""))
    
    val resource = if (resourceName != null) "$resourceType $resourceName" else resourceType
    val cmd = mutableListOf("kubectl get $resource")
    
    namespace?.let { cmd.add("-n $it") }
    kubeconfig?.let { cmd.add("--kubeconfig $it") }
    cmd.add("-o $output")
    
    val command = cmd.joinToString(" ")
    return sh(context, command, returnStdout = true)
}

/**
 * Describes Kubernetes resources with detailed information.
 * 
 * @param resourceType Type of resource
 * @param resourceName Name of the resource
 * @param namespace Kubernetes namespace (optional)
 * @param kubeconfig Path to kubeconfig file (optional)
 * @return Detailed resource description
 */
@Step(
    name = "kubectlDescribe",
    description = "Describe Kubernetes resource with detailed information",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun kubectlDescribe(
    context: PipelineContext,
    resourceType: String,
    resourceName: String,
    namespace: String? = null,
    kubeconfig: String? = null
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(resourceType.isNotBlank()) { "Resource type cannot be blank" }
    require(resourceName.isNotBlank()) { "Resource name cannot be blank" }
    
    context.logger.info("+ kubectlDescribe: $resourceType/$resourceName")
    
    val cmd = mutableListOf("kubectl describe $resourceType $resourceName")
    
    namespace?.let { cmd.add("-n $it") }
    kubeconfig?.let { cmd.add("--kubeconfig $it") }
    
    val command = cmd.joinToString(" ")
    return sh(context, command, returnStdout = true)
}

/**
 * Gets logs from Kubernetes pods.
 * 
 * @param podName Name of the pod
 * @param namespace Kubernetes namespace (optional)
 * @param container Container name (optional, for multi-container pods)
 * @param kubeconfig Path to kubeconfig file (optional)
 * @param follow Whether to follow logs (default: false)
 * @param tail Number of lines to show from the end (optional)
 * @param since Show logs since duration (e.g., "5m", "1h") (optional)
 * @return Pod logs
 */
@Step(
    name = "kubectlLogs",
    description = "Get logs from Kubernetes pods",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun kubectlLogs(
    context: PipelineContext,
    podName: String,
    namespace: String? = null,
    container: String? = null,
    kubeconfig: String? = null,
    follow: Boolean = false,
    tail: Int? = null,
    since: String? = null
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(podName.isNotBlank()) { "Pod name cannot be blank" }
    
    context.logger.info("+ kubectlLogs: $podName")
    
    val cmd = mutableListOf("kubectl logs $podName")
    
    namespace?.let { cmd.add("-n $it") }
    container?.let { cmd.add("-c $it") }
    kubeconfig?.let { cmd.add("--kubeconfig $it") }
    if (follow) cmd.add("-f")
    tail?.let { cmd.add("--tail=$it") }
    since?.let { cmd.add("--since=$it") }
    
    val command = cmd.joinToString(" ")
    return sh(context, command, returnStdout = true)
}

/**
 * Scales Kubernetes deployments.
 * 
 * @param deploymentName Name of the deployment
 * @param replicas Number of replicas to scale to
 * @param namespace Kubernetes namespace (optional)
 * @param kubeconfig Path to kubeconfig file (optional)
 */
@Step(
    name = "kubectlScale",
    description = "Scale Kubernetes deployments",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun kubectlScale(
    context: PipelineContext,
    deploymentName: String,
    replicas: Int,
    namespace: String? = null,
    kubeconfig: String? = null
) {
    // Context is automatically injected by the K2 compiler plugin
    
    require(deploymentName.isNotBlank()) { "Deployment name cannot be blank" }
    require(replicas >= 0) { "Replicas must be non-negative" }
    
    context.logger.info("+ kubectlScale: $deploymentName to $replicas replicas")
    
    val cmd = mutableListOf("kubectl scale deployment $deploymentName --replicas=$replicas")
    
    namespace?.let { cmd.add("-n $it") }
    kubeconfig?.let { cmd.add("--kubeconfig $it") }
    
    val command = cmd.joinToString(" ")
    sh(context, command)
    
    context.logger.info("Successfully scaled $deploymentName to $replicas replicas")
}

/**
 * Waits for Kubernetes resource conditions.
 * 
 * @param resourceType Type of resource to wait for
 * @param resourceName Name of the resource
 * @param condition Condition to wait for (e.g., "condition=ready", "delete")
 * @param namespace Kubernetes namespace (optional)
 * @param kubeconfig Path to kubeconfig file (optional)
 * @param timeout Timeout duration (default: "300s")
 */
@Step(
    name = "kubectlWait",
    description = "Wait for Kubernetes resource conditions",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun kubectlWait(
    context: PipelineContext,
    resourceType: String,
    resourceName: String,
    condition: String,
    namespace: String? = null,
    kubeconfig: String? = null,
    timeout: String = "300s"
) {
    // Context is automatically injected by the K2 compiler plugin
    
    require(resourceType.isNotBlank()) { "Resource type cannot be blank" }
    require(resourceName.isNotBlank()) { "Resource name cannot be blank" }
    require(condition.isNotBlank()) { "Condition cannot be blank" }
    
    context.logger.info("+ kubectlWait: $resourceType/$resourceName for $condition")
    
    val cmd = mutableListOf("kubectl wait $resourceType $resourceName --for=$condition --timeout=$timeout")
    
    namespace?.let { cmd.add("-n $it") }
    kubeconfig?.let { cmd.add("--kubeconfig $it") }
    
    val command = cmd.joinToString(" ")
    sh(context, command)
    
    context.logger.info("Successfully waited for $resourceType/$resourceName condition: $condition")
}

/**
 * Deploys a Helm chart to Kubernetes.
 * 
 * @param releaseName Name of the Helm release
 * @param chart Helm chart name or path
 * @param namespace Kubernetes namespace (optional)
 * @param values Map of values to override in the chart
 * @param valuesFile Path to values file (optional)
 * @param createNamespace Whether to create namespace if it doesn't exist (default: true)
 * @param upgrade Whether to upgrade if release exists (default: true)
 * @param wait Whether to wait for deployment to complete (default: true)
 * @param timeout Timeout for deployment (default: "300s")
 * @return Helm deployment output
 */
@Step(
    name = "helmDeploy",
    description = "Deploy Helm chart to Kubernetes",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun helmDeploy(
    context: PipelineContext,
    releaseName: String,
    chart: String,
    namespace: String? = null,
    values: Map<String, String> = emptyMap(),
    valuesFile: String? = null,
    createNamespace: Boolean = true,
    upgrade: Boolean = true,
    wait: Boolean = true,
    timeout: String = "300s"
): String {
    // Context is automatically injected by the K2 compiler plugin
    
    require(releaseName.isNotBlank()) { "Release name cannot be blank" }
    require(chart.isNotBlank()) { "Chart cannot be blank" }
    
    context.logger.info("+ helmDeploy: $releaseName ($chart)")
    
    // Verify Helm is available
    sh(context, "helm version")
    
    val action = if (upgrade) "upgrade --install" else "install"
    val cmd = mutableListOf("helm $action $releaseName $chart")
    
    namespace?.let { 
        cmd.add("-n $it")
        if (createNamespace) cmd.add("--create-namespace")
    }
    
    values.forEach { (key, value) ->
        cmd.add("--set $key=$value")
    }
    
    valuesFile?.let { cmd.add("-f $it") }
    if (wait) cmd.add("--wait --timeout $timeout")
    
    val command = cmd.joinToString(" ")
    val result = sh(context, command, returnStdout = true)
    
    context.logger.info("Successfully deployed Helm release: $releaseName")
    return result
}

/**
 * Uninstalls a Helm release.
 * 
 * @param releaseName Name of the Helm release to uninstall
 * @param namespace Kubernetes namespace (optional)
 * @param wait Whether to wait for uninstall to complete (default: true)
 * @param timeout Timeout for uninstall (default: "300s")
 */
@Step(
    name = "helmUninstall",
    description = "Uninstall Helm release",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun helmUninstall(
    context: PipelineContext,
    releaseName: String,
    namespace: String? = null,
    wait: Boolean = true,
    timeout: String = "300s"
) {
    // Context is automatically injected by the K2 compiler plugin
    
    require(releaseName.isNotBlank()) { "Release name cannot be blank" }
    
    context.logger.info("+ helmUninstall: $releaseName")
    
    val cmd = mutableListOf("helm uninstall $releaseName")
    
    namespace?.let { cmd.add("-n $it") }
    if (wait) cmd.add("--wait --timeout $timeout")
    
    val command = cmd.joinToString(" ")
    sh(context, command)
    
    context.logger.info("Successfully uninstalled Helm release: $releaseName")
}