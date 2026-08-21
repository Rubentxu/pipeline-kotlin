# Credentials Provider Specification

## 1. Modelo

```text
CredentialRef
    ↓ authorize + resolve
CredentialLease
    ↓ materialize
CredentialProjection
```

## 2. API

```kotlin
interface CredentialProvider {
    suspend fun resolve(
        request: CredentialRequest,
        context: CredentialContext
    ): CredentialLease
}
```

`CredentialRef` contiene provider/name/type/scope, nunca secret bytes.

## 3. Providers iniciales

- Jenkins Credentials adapter;
- Kubernetes Secret/ServiceAccount/CSI adapter;
- Workload Identity/OIDC;
- Vault posterior prioritario;
- AWS/Azure/GCP secret stores posteriores.

## 4. Projections

- EnvironmentProjection
- FileProjection
- VolumeProjection
- KubernetesCsiProjection
- SshAgentProjection
- DockerConfigProjection
- OidcTokenProjection

## 5. Jenkins familiarity

```kotlin
withCredentials(
    usernamePassword(
        credentialsId = "nexus",
        usernameVariable = "NEXUS_USER",
        passwordVariable = "NEXUS_PASSWORD"
    )
) {
    sh("./publish.sh")
}
```

Internamente genera lease/projections y redaction rules.

## 6. Workload identity first

En Kubernetes, preferir ServiceAccount/OIDC/CSI cuando el proveedor lo soporte. El control plane puede autorizar una projection sin recibir el valor secreto.

## 7. Security invariants

- secreto no aparece en EventEnvelope;
- secreto no se serializa al execution graph;
- TTL/scopes mínimos;
- revocation/expiry explícitos;
- redaction local antes de transmitir logs;
- audit registra referencia/uso autorizado, no valor.
