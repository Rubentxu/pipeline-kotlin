# Baseline de GitHub y criterio de revalidación

**Comprobación realizada mediante plugin GitHub el 10-10-2026** (los HEAD pueden cambiar, no son locks perpetuos):

- PipelineK main: [`543e1cc5e61f6c92fe1c7ffbd8b513bbe90395b4`](https://github.com/Rubentxu/pipeline-kotlin/commit/543e1cc5e61f6c92fe1c7ffbd8b513bbe90395b4).
- Fabric main: [`e6e4fd3e191dd483994af89418c93ecd5ffdb3e0`](https://github.com/Rubentxu/pipelinek-fabric/commit/e6e4fd3e191dd483994af89418c93ecd5ffdb3e0).
- Sobre HEAD Fabric se reconfirmaron en fuentes: `FactNoticeReceiver` permanece con `OutputSpooled→Retained(NotDurable)` y dedupe executor; `FileSpool` usa `mapNotNull` al leer entradas; `LocalPipelineKExecutionStrategy` conserva `StringBuilder`/`waitFor` para facts.
- El `reference/BASELINE.md` recoge lectura anterior del repo Fabric `2a0df74e36afd9cbba1e5dfab70175ae08d689b4`; cuando difiera con este fichero prevalece la medición sobre `e6e4fd` para los tres hallazgos especificados, y la lectura real del nuevo HEAD de trabajo sobre ambos.

**Cada agente** debe obtener `git rev-parse HEAD`, `git ls-remote origin refs/heads/main`, último tag publicado y SHA del SDK que resuelve un consumidor REAL, registrándolo con fecha. No declarar que algo está sin implementar por mera aparición de un nombre en documento.
