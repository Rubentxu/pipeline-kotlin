# v0.46.0 — promoción estable y cierre del train RP-5 (productor)

Estado: **PUBLICADA** — el operador ejecutó el human gate y publicó la release.
Cycle SDDK: `p-1f3622e11c093341/rp-5-exact-candidate-certification`

## Identidad publicada (tupla completa)

| Campo | Valor |
| --- | --- |
| Tag | `v0.46.0` (origin, `refs/tags/v0.46.0` → objeto `1e0209bc`) |
| SourceCommit | `63ef3220e51bb432ecbc571f781eba0f4a160266` |
| CandidateId / ZIP SHA256 | `59478baf669407aa905239e3754aff48d77d20f8fad4de8e48464583fb929397` |
| CandidateSequence | 3 (latestEligible del train 0.46.0) |
| Manifest / Handoff / SBOM | ver tupla en [`CANDIDATE_0460_RP5_EVIDENCE_BUNDLE.md`](../../v2/08-production-readiness/CANDIDATE_0460_RP5_EVIDENCE_BUNDLE.md) |

**Los bytes publicados son los bytes de la candidata.** El tag apunta al mismo
source commit que produjo el ZIP `59478baf…` entregado al harness: sin rebuild,
sin cambio de versión, sin RC→GA rewriting.

## Cadencia final del train 0.46.0

| Seq | Source | CandidateId | Final |
| --- | --- | --- | --- |
| 1 | `3e03440b` | `50f98e0c…` | superseded |
| 2 | `f4f53601` | `50f98e0c…` (mismo ZIP + SBOM sidecar) | superseded |
| 3 | `63ef3220` | `59478baf…` | **PUBLICADA como v0.46.0** |

## Estado de RP-5 al cierre del train

Filas de productor selladas sobre el candidato exacto (PR-011..015): gate
forzado 19m34s 278/278; gitleaks 0; osv 0 tras remediación (6 hallazgos: ReDoS
de `AntPathMatcher` alcanzable desde `excludes` de usuario + drift `bcpkix`);
Kover 78,7% con umbrales verdes; pitest ejecutado (strength 73%, 322 mutaciones
sin cobertura como seguimiento); SLO RSS 8.192 MiB medido sobre tres calibraciones.

El veredicto del harness (PR-016) queda como certificación externa en curso del
otro carril; el operador, como human gate, publicó con esos bytes.

## Cierre administrativo

- WorkItems del ciclo RP-5: `639c2c61` Done. Sin items colgantes.
- Ciclo `train-s2-directive-plugin` (S2-D): sus tres WorkItems en Done; la
  divergencia de scope con RP-034 permanece documentada en
  [`SCOPE_DIVERGENCE_RECONCILIATION_S2D_RP034.md`](SCOPE_DIVERGENCE_RECONCILIATION_S2D_RP034.md).
  El contenido funcional (directivas externas certificadas, UAT instalada) está
  en la release; la reconciliación formal del ciclo se hace en el siguiente
  bloque, no arrastrada a S3.
- Siguiente train activo: **PR-017 RunLifecycleEngine** (rama
  `pr017-run-lifecycle-engine`, baseline de caracterización lifecycle + body ya
  fijados, merge a main tras RP-5 GO estable).
