# Configuration Manifests Specification

## 1. Objetivo

Adoptar patrones de Kubernetes para recursos declarativos: `apiVersion`, `kind`, `metadata`, `spec`, `status`, labels, conditions y reconciliation.

## 2. Recursos

- PipelineRuntime
- WorkerPool
- ExecutionProfile
- CredentialProvider
- ArtifactStore
- CacheStore
- PluginRepository
- PolicySet
- KubernetesCluster
- EventStore
- GraphProjection

## 3. Ejemplo WorkerPool

```yaml
apiVersion: pipeline.dev/v1alpha1
kind: WorkerPool
metadata:
  name: java-builders
  labels:
    runtime: java
spec:
  provider:
    kubernetes:
      clusterRef: production
  lifecycle:
    mode: PerRun
  capabilities:
    os: linux
    arch: amd64
    cpu: "4"
    memory: 8Gi
  podTemplate:
    spec:
      serviceAccountName: pipeline-worker
      containers:
        - name: worker
          image: registry/pipeline-worker:2.0
status:
  observedGeneration: 12
  readyWorkers: 0
  activeWorkers: 7
  conditions:
    - type: Ready
      status: "True"
```

## 4. Spec/status

Users/config management escriben `spec`. Controllers/adapters materializan `status`. Nunca se mezcla desired state con observation.

## 5. Versioning/migrations

Cada kind tiene schema version. Conversion/migration functions permiten evolucionar alpha→beta→v1. Unknown fields policy debe ser explícita; configuración crítica falla en vez de ignorarse silenciosamente.

## 6. Validation

- structural schema;
- semantic validation;
- references resolution;
- policy validation;
- dry-run;
- rendered/effective configuration inspection.

## 7. Reuse

Los mismos manifiestos deben funcionar:
- desde filesystem/SCM;
- Jenkins configuration adapter;
- API futura;
- potenciales CRDs Kubernetes.
