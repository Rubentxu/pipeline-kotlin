# Modelo de plugins y confianza

## 1. Dos ecosistemas

### Plugins ordinarios

Pueden seguir el modelo actual in-process cuando no reciben material secreto:

- `StepDefinitionContributor`;
- codecs;
- handlers que consumen capabilities autorizadas;
- DSL facades;
- output renderers sobre datos sanitizados;
- ToolAdapters que sólo describen projections.

### Extensiones sensibles

Providers/connectors que pueden llegar a material secreto requieren una frontera más fuerte.

No se permite por defecto:

```text
third-party JAR
  → ServiceLoader
  → raw SecretHandle
```

## 2. Built-ins trusted

Pueden ser módulos auditados distribuidos con PipelineK:

- local encrypted provider;
- Linux Secret Service adapter;
- ASV session adapter;
- legacy env/file projection;
- typed config projections.

## 3. Terceros sensibles out-of-process

Protocolo estrecho y versionado, preferentemente UDS local inicialmente:

```text
describe
capabilities
prepare-use
sign / proxy / connect / derive-short-lived
revoke
close
```

Evitar método genérico `get-secret`.

## 4. Plugin identity

Toda extensión debe aportar:

- plugin id;
- semantic version;
- digest/provenance;
- supported protocol version;
- declared capabilities;
- security posture(s);
- supported tool/adapters;
- platform constraints.

## 5. Admission

Un Step declara capabilities y el runtime las aporta sólo si policy lo permite.

Un Step/plugin no obtiene una referencia al `CredentialProviderRegistry` completo. Recibe una capability acotada para su invocation.

## 6. ToolAdapters

Los adapters pueden ser in-process si no reciben secreto. Su responsabilidad es transformar:

```text
ToolIdentity + CredentialRequirement
            ↓
CredentialProjectionRequest
```

La materialización la realiza una autoridad trusted posterior.

## 7. Security review classification

Cada plugin se clasifica:

```text
PURE
FILESYSTEM
PROCESS
NETWORK
CREDENTIAL-AWARE-NONSECRET
CREDENTIAL-SENSITIVE
```

`CREDENTIAL-SENSITIVE` exige trust policy adicional y, por defecto, proceso separado.

