# Candidato v0.61.0-rc2 — archivo histórico

**Estado al archivado:** `CANDIDATE_PUBLISHED`, en proceso de certificación externa por el
harness (`pipelinek-release-harness`). El veredicto del harness NO se archiva aquí; cuando
emita su recibo, vivirá en una sección separada de `docs/v2/07-uat/`.

**Train:** `0.61.0-rc2` (publicada 2026-10-15).
**Commit de publicación:** `6a5e918309bbd5cb162e77bb6edc1f47e6a70b07`
(`release: v0.61.0-rc2 — fix PRODUCT IDENTITY LAW, publish candidate`).
**Build commit:** `4a6b161ec51fa5cd0d7128f1fca04333e23d9978` (post-PR #136, base de la candidata).
**PRs incorporadas:** #130 (WU-A/B), #133 (WU-B), #135 (WU-C), #136 (WU-C polish). Sin squash
(ADR-0099).

## Contenido

| Fichero | SHA-256 (verificado con `sha256sum -c SHA256SUMS`) |
|---|---|
| `git-head.txt` | `274a45c94ed0c9670a07dc0afe1cb36a23694be64f1233a9457b5e15e846f422` |
| `pipelinek-0.61.0.sbom.json` | `fa9fee844ba6c726c6478e0ede00d41b527c917fe69f9ae8904d35bee7bff7ae` |
| `pipelinek-0.61.0.zip` | `94c852e7724519857ae1226107c10d247053b5d034a98d4290540d04f3b1ff23` |
| `HANDOFF-TO-CERTIFIER.md` | (no consta en `SHA256SUMS`; el handoff describe el contenido y los criterios del productor; el veredicto no es del productor) |
| `SHA256SUMS` | (suma de los 3 anteriores; SHA256SUMS.txt es una copia) |
| `SHA256SUMS.txt` | (duplicado de SHA256SUMS) |

## Por qué se archivó aquí

`evidence/0-61-rc2/` estaba en `main` como directorio operativo de la candidata; cuando se
publicó la siguiente candidata y la rc2 quedó atrás, el directorio perdió su rol activo. Se
mueve íntegro a `docs/historico/0-61-rc2/` con su árbol y contenido byte-inmutables. La
copia en `evidence/` puede continuar existiendo en `main` si el operador la necesita para
la sesión del harness, o eliminarse por una limpieza posterior; el archive es la verdad
histórica.

## Lo que NO vive aquí

- El veredicto del harness externo. Cuando llegue, vivirá en `docs/v2/07-uat/` (recibos por
  candidata, no mezclados con la candidata archivada).
- Reconstrucciones. La candidata es inmutable: si se vuelve a necesitar el ZIP, este archivo
  es la fuente; nunca se reconstruye desde la receta.
- Reescritura de `SHA256SUMS` o del handoff. Si el archivo deja de coincidir, eso es un
  defecto del archivo, no motivo para "corregir" el manifest.
