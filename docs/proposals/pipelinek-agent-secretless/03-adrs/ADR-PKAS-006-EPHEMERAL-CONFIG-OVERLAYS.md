# ADR-PKAS-006 — Config overlays efímeros

**Status:** proposed.

## Decision

PipelineK no modifica por defecto la configuración persistente del usuario para introducir secretos.

Genera ficheros temporales owner-only y usa switches/env oficiales de cada tool para seleccionarlos.

## Examples

- Maven `-s`;
- npm `npm_config_userconfig`;
- curl `--netrc-file`;
- Docker `DOCKER_CONFIG`;
- Gradle overlay controlado.

## Cleanup

Debe verificarse en success/failure/timeout/cancel.

