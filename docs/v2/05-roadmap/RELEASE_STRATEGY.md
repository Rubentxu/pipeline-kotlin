# Release & Compatibility Strategy

## Versiones independientes

- Runtime SemVer
- DSL API SemVer
- Plugin API SemVer
- Protocol major/minor
- Manifest apiVersion
- Event schema versions

## Matriz publicada por release

Ejemplo conceptual:

```text
Pipeline Runtime: 2.0.x
Kotlin: 2.4.10
JDK Worker: 21 (25 validated tier-2 until promoted)
DSL API: 2.0
Plugin API: v1
Protocol: v1
Jenkins baseline: supported modern LTS line
Kubernetes: supported versions by integration test matrix
```

## Canales

- nightly
- alpha
- beta
- rc
- stable

## Kotlin upgrades

1. nightly compatibility corpus con RC/EAP;
2. abrir upgrade issue con diff de diagnostics/perf;
3. adapter branch nueva si hay API churn;
4. full UAT compiler/replay;
5. promote only on stable unless explicit experimental channel.

## Protocol

Workers deben negociar versión antes de lease. Rolling upgrade debe permitir al menos una ventana de convivencia control-plane/worker dentro de la misma protocol major.

## Plugins

Plugin lockfile fija version+digest. Resolver debe rechazar plugin incompatible con Plugin API/runtime range antes de ejecutar pipeline.
