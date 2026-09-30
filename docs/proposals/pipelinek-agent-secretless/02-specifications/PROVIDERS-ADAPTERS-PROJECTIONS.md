# Providers, adapters y projections

## 1. Separación de responsabilidades

### CredentialProvider

Dónde reside o cómo se deriva la identidad.

Ejemplos:

- local encrypted store;
- Linux Secret Service;
- Agent Secretless Vault;
- Vault;
- AWS/Azure/GCP secret stores;
- OIDC/workload identity.

### ToolCredentialAdapter

Cómo una herramienta concreta puede consumir identidad.

Ejemplos:

- Git;
- SSH;
- curl;
- Maven;
- Gradle;
- npm;
- Docker;
- pip;
- Cargo;
- Terraform.

### CredentialProjection

Forma concreta de entrega/uso.

Ejemplos:

- signer socket;
- credential helper;
- HTTP proxy;
- short-lived token;
- env;
- stdin;
- temp file;
- config overlay;
- Docker config;
- `.netrc`.

### ExecutionBackend

Dónde se ejecuta la herramienta.

- local existing shell spine;
- future hardened local backend;
- ASV isolated worker fallback;
- future container/remote worker.

## 2. Built-ins iniciales

### Providers

1. `local-encrypted` — adapter sobre infraestructura local existente.
2. `linux-secret-service` — integración D-Bus Freedesktop Secret Service.
3. `agent-secretless` — session/capability adapter; nunca generic get-secret.
4. `environment-compat` — disponible sólo como modo explícitamente degradado.

### Projections

- `environment`;
- `stdin` cuando el tool lo soporta y el lifecycle es claro;
- `ephemeral-file`;
- `config-overlay`;
- `ssh-agent`;
- `credential-helper`;
- `http-proxy`/service proxy;
- `short-lived-token`.

## 3. Linux Secret Service

La abstracción preferida en Linux es `org.freedesktop.secrets`, no un parser directo de KeePassXC.

Beneficios:

- KeePassXC puede actuar como provider de Secret Service;
- GNOME Keyring/KWallet-compatible implementations pueden funcionar detrás de la misma API;
- desacopla PipelineK del formato interno de una aplicación concreta.

Debe documentarse la limitación de que el entorno de escritorio puede tener un único provider principal o políticas propias; discovery no equivale a garantía de disponibilidad.

## 4. Config overlays

Regla: no editar config real del usuario por defecto.

Crear run dir efímero con permisos restrictivos:

```text
/run/user/$UID/pipelinek/<run-id>/credentials/
```

o XDG runtime equivalent.

Ejemplos:

### curl

Preferir proxy/session. Fallback:

```text
.netrc temporal + --netrc-file
```

### Maven

```text
settings.xml temporal + mvn -s <file>
```

### Gradle

```text
properties overlay + GRADLE_USER_HOME/config controlado
```

No sacrificar automáticamente caches. Diseñar separación config secreta/cache:

```text
run config dir (ephemeral, private)
shared cache dir (non-secret, reusable)
```

### npm

```text
.npmrc temporal + npm_config_userconfig=<file>
```

### Git HTTPS

Preferir credential helper/broker; nunca token embebido en URL.

### Git SSH

Preferir `SSH_AUTH_SOCK`/signer.

### Docker

Preferir credential helper; fallback `DOCKER_CONFIG` temporal.

## 5. Typed projections

Evitar template libre como API principal.

Preferir:

```text
MavenServerProjection
GradlePropertiesProjection
NpmRegistryProjection
NetrcProjection
DockerConfigProjection
GitCredentialHelperProjection
```

Un `CustomTemplateProjection` puede existir como extensión de menor confianza/posture y con policy explícita.

## 6. Cleanup

Toda projection efímera implementa lifecycle:

```text
prepare → activate → execute → deactivate → wipe/delete → verify cleanup
```

Cleanup debe ocurrir en success, failure, timeout y cancellation. Los residuos retenidos para post-mortem nunca pueden incluir secreto sin una decisión explícita y cifrado/protección adecuados.


## 7. Carga por defecto y disable explícito

Los built-ins auditados pueden registrarse por defecto como **candidatos**, pero discovery no implica uso automático.

Política sugerida:

```text
local-encrypted       registered/enabled
linux-secret-service registered if platform supports D-Bus; availability probed lazily
agent-secretless      registered; eligible only if broker/session endpoint is available
legacy-env-compat     registered but NOT eligible for a stronger minimum posture
```

El operador puede desactivar explícitamente providers/adapters:

```toml
[providers]
disabled = ["linux-secret-service"]
```

No hacer fail-open hacia otro provider cuando un profile fija uno concreto.
