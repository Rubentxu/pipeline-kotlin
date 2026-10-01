# Candidate 0.46.0 — RP-5 evidence bundle (producer side)

Estado: **EN CURSO** — filas PR-010..013 selladas sobre el candidato exacto;
PR-014/015/016 pendientes.
Cycle SDDK: `p-1f3622e11c093341/rp-5-exact-candidate-certification`
WorkItem: `639c2c61` (PR-011)

## Identidad de la candidata (tupla completa)

| Campo | Valor |
| --- | --- |
| ReleaseTrain | 0.46.0 |
| CandidateSequence | **3** |
| SourceCommit | `63ef3220e51bb432ecbc571f781eba0f4a160266` |
| CandidateId / ZIP SHA256 | `59478baf669407aa905239e3754aff48d77d20f8fad4de8e48464583fb929397` |
| ZIP size | 91.159.891 bytes |
| DistributionManifest SHA256 | `148eab557d00382783d5fc83a4f621e98e7a27062153161295416fc48de59693` |
| CandidateHandoff SHA256 | `cc2f721e8af7832466cf50095716ea8aaaad1bb57130faea6875f56f3240bb4d` |
| SBOM SHA256 | `5591480be83676a1172a3b714f52926b92a54ef133998a7652f9ce1f1e66ddd2` |

Entrega: `pipelinek-release-harness/inputs/dogfood/0.46.0/` — el digest del ZIP
entregado coincide con el CandidateId del handoff (verificado post-copia).

### Cadencia del train 0.46.0

| Seq | Source | CandidateId | Estado |
| --- | --- | --- | --- |
| 1 | `3e03440b` | `50f98e0c…` | SUPERSEDED — sin SBOM en handoff |
| 2 | `f4f53601` | `50f98e0c…` (mismo ZIP; SBOM añadido como sidecar) | **SUPERSEDED** por c3 |
| 3 | `63ef3220` | `59478baf…` (NUEVO — deps remediadas dentro del ZIP) | **latestEligible** |

La igualdad c1/c2 y la diferencia c2/c3 son ambas casos de
`CandidateReproducibilityTest`: identidad material del ZIP separada de la
envolvente de procedencia.

## Filas selladas sobre el candidato exacto

| PR | Fila | Veredicto | Evidencia |
| --- | --- | --- | --- |
| 011 | Full gate con re-ejecución total (`--rerun-tasks`) sobre `63ef3220` | **PASS — BUILD SUCCESSFUL in 19m 34s, 278/278 tasks executed** | log de la tarea; cero UP-TO-DATE |
| 011 | Hallazgo: oráculo de contención en el gate | **CORREGIDO** (`333e22bb`) | 18,7 MB/s best-of-3 bajo carga vs PASS en aislamiento; probe → `performanceTest`, floor intacto |
| 012 | Secret scan (gitleaks 8.30.1, `--no-git`, árbol `63ef3220`) | **PASS — 0 findings** (164,95 MB) | `/tmp` report; fila de árbol exacto |
| 012 | SCA (osv-scanner 2.6.0 sobre el SBOM de c3) | **PASS — 0 vulnerabilidades** (eran 6) | re-run tras remediación |
| 012 | SBOM CycloneDX en el material de admission | **CORREGIDO** (`f4f53601`) | `sbom: null` ya no es posible; gap de supply-chain, no packaging |
| 013 | Kover agregado sobre el árbol del candidato | **PASS — LINE 17.072/21.692 = 78,7%** (44 paquetes); umbrales por módulo (`koverVerify`) verdes dentro del check | `build/reports/kover/report.xml` |

### PR-012: dos findings distintos, no "actualización de dependencias"

**F-1 — riesgo alcanzable desde superficie de producto.** Cadena causal:

```text
patron excludes aportado por el usuario (DSL de artifacts)
        ↓
pipeline-artefacts-local (AntStyleGlob)
        ↓
AntPathMatcher (spring-core 6.2.4)
        ↓
GHSA-659m-px2c-25wj / CVE-2026-41848 — ReDoS
```

La remediación (spring-core 6.2.19) cierra el ReDoS en la clase exacta que
consume input del usuario. No era higiene de catálogo.

**F-2 — drift interno del catálogo.** `bcpkix` estaba hardcodeado a `1.80`
mientras `bcprov` usaba `version.ref = 1.80.2`: el módulo pkix iba por detrás
incluso de su propio prove. Unificado en `1.86` vía `version.ref` para los tres
artefactos BC.

## Pendientes del train

| PR | Alcance | Estado |
| --- | --- | --- |
| 014 | Mutación selectiva `:pipeline-domain:pitest` (`domain.durable.*`) | ⬜ |
| 015 | RSS/SLO con metodología normativa (sin scripts en `scripts/`: localizar M5 o declarar BLOCKED) | ⬜ |
| 016 | Harness externo + 2 repos + fallo recuperable + replay + `RP-5 PRODUCT_GATE_GO` | ⬜ — ejecución externa |

## Ley vigente

- Ninguna fila hereda veredicto de otro SHA; cada una nombra la tupla.
- Un cambio de producción desde aquí ⇒ sequence 4 y caducidad de las filas
  materialmente afectadas.
- `git.tag` / `git.release` siguen siendo `human_gate`: sólo tras
  CERTIFIED del harness sobre estos bytes.
