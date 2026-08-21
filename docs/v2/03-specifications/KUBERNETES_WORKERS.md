# Kubernetes Ephemeral Workers Specification

## 1. Objetivo

Kubernetes es el primer provider de workers dinámicos, pero el runtime sólo depende de `WorkerProvisioner`.

```kotlin
interface WorkerProvisioner {
    suspend fun provision(request: WorkerRequest): WorkerLease
    suspend fun terminate(workerId: WorkerId, reason: TerminationReason)
}
```

## 2. Lifecycle

```text
REQUESTED -> PROVISIONING -> STARTING -> CONNECTED -> READY
                                                  -> LEASED -> RUNNING
RUNNING -> DRAINING -> TERMINATED
ANY -> LOST/FAILED
```

Cada transición significativa produce evento.

## 3. Capabilities

Worker anuncia, entre otras:
- os/arch;
- CPU/memory;
- Java/JDK;
- runtimes Docker/Podman/K8s tools;
- GPU/VRAM opcional;
- security profile;
- cached plugin/image/repo hints.

Scheduler selecciona por hard requirements + soft affinity.

## 4. Pod model

El Pod debe contener un `pipeline-worker`. Puede coexistir con sidecar/tool containers y permitir `container("name") {}` para ejecutar en contextos específicos.

No requiere contenedor `jnlp` ni Jenkins inbound agent.

## 5. DSL familiar

```kotlin
agent {
    kubernetes {
        cloud("production")
        inheritFrom("java-build")
        defaultContainer("builder")
        yamlFile("ci/pod.yaml")
        retries(2)
    }
}
```

La semántica se traduce a `WorkerTemplate` propio.

## 6. Jenkins Kubernetes bridge

Adapter opcional:

```text
Jenkins KubernetesCloud/PodTemplate
             ↓
PodTemplateAdapter
             ↓
WorkerTemplate
             ↓
KubernetesWorkerProvisioner
```

Sólo reutiliza configuración/template semantics; no launcher/Remoting.

## 7. Failure classification

- Pod unschedulable -> INFRASTRUCTURE
- image pull -> INFRASTRUCTURE/CONFIG
- eviction/preemption -> INFRASTRUCTURE, normalmente retryable
- process exit != 0 -> BUILD
- policy denied -> POLICY
- worker protocol incompatible -> SYSTEM/CONFIG

## 8. Warm pools

Después del MVP se permite `mode: PerRun | Reusable | WarmPool`. PerRun es baseline para aislamiento y simplicidad; WarmPool optimiza cold start/caches.

## 9. Data locality

Scheduler puede puntuar:
- repo cache;
- Gradle/Maven cache;
- image layers;
- artifact locality;
- node zone;
- cost/load.

Nunca viola hard security/capability constraints por afinidad.
