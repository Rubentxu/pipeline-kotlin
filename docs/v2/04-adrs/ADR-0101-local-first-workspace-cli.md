# ADR-0101 — CLI local-first: workspace attached por defecto, isolated explícito

**Status:** Accepted  
**Date:** 2026-10-01  
**Accepted:** 2026-10-01 by Rubentxu (product owner) — WU-RP-034 operator mandate  
**Supersedes:** the workspace/CWD clauses of ADR-0048 (see ADR-0101)  
**Scope:** CLI/product behavior

## Context

El uso local de PipelineK ha evolucionado desde "job obtiene un proyecto dentro de un workspace" hacia "PipelineK ejecuta automatización/CI sobre el proyecto donde ya está el usuario".

El default temporal obliga a recordar:

```bash
pipelinek run --workspace . pipeline.kts
```

La omisión ya ha producido fallos reales en dogfooding y smokes.

## Decision

El contrato final del CLI será:

```bash
pipelinek run pipeline.kts
# workspace = invocation directory

pipelinek run --workspace /path pipeline.kts
# workspace = /path

pipelinek run --isolated pipeline.kts
# workspace gestionado por PipelineK
```

`--workspace` y `--isolated` son incompatibles y fallan antes de compilar/ejecutar.

## Determinism

No se implementa autodetección de project root (`.git`, `pom.xml`, Gradle, etc.) en el default.

La regla es simple:

```text
no flag => invocation directory
```

El usuario puede elegir otra raíz con `--workspace`.

## Why not script directory?

El pipeline definition puede estar centralizado fuera del proyecto. Asociar workspace a su ubicación rompería casos como:

```bash
cd /repos/service-a
pipelinek run ~/.pipelinek/company/ci.pipeline.kts
```

## Breaking-change policy

Cambiar el default es un behavioral breaking change. La implementación debe:

1. introducir `--isolated` antes o en el mismo train;
2. documentar equivalencia del comportamiento antiguo: `run --isolated`;
3. conservar `--workspace .`;
4. incluir release note/migration note;
5. ejecutar UAT instalada de ambos modos;
6. no añadir heurísticas silenciosas para disimular la ruptura.

Al estar el producto en `0.x`, el cambio puede entrar en un nuevo minor/RC train según la política de versión vigente, pero debe declararse explícitamente.

## Consequences

### Positive

- el caso local más frecuente funciona sin ceremonia;
- self-hosting más robusto;
- mejor sustituto local de GitHub Actions;
- el modo scratch sigue disponible y más claro.

### Negative

- scripts que dependían implícitamente del scratch necesitan `--isolated`;
- `cleanWs/deleteDir` se vuelven más peligrosos si no se cierra antes ADR-0102.

## Gate

**El flip del default NO puede ocurrir antes de implementar la protección de operaciones destructivas sobre workspaces Attached.**
