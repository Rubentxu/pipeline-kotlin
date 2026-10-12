# Bloqueo REAL en GitHub: configuración recomendada

**Repositorios:** [Rubentxu/pipeline-kotlin](https://github.com/Rubentxu/pipeline-kotlin) y [Rubentxu/pipelinek-fabric](https://github.com/Rubentxu/pipelinek-fabric). No se han instalado reglas ni modificado el GitHub remoto al crear este paquete.

## Dos gates complementarios (sin GitHub Actions)

| Gate | Momento | Impide |
|---|---|---|
| `cross-repo/candidate-interop` | Antes de merge a `main`/rama release | integrar PK ABI que Fabric no consume o viceversa |
| `cross-repo/previous-pair-certified` | Antes de aceptar PR de M(n+1) | comenzar/hacer merge de siguiente hito con M(n) incompleto |
| `ci/local-release-admission` | Antes del merge | eludir suite local/release y guardrails del repo |
| `cross-repo/pair-certified` | Tras publicar ambos tags | declarar par M(n) listo para siguiente hito |

**No crear workflows GitHub Actions**: publicar estados `commit status`/`check-run` desde el harness local existente de PipelineK si es admisible, o un Jenkins/runner propio de confianza, usando permisos mínimos y tokens con expiración. Fabric puede usar el mismo runner cross-repo o uno separado, pero el contrato de gate debe ser común.

### Reglas en las DOS ramas de destino

- Prohibir bypass salvo owner autorizado/auditable, force-push y borrar historia; exigir revisión de PR y `cross-repo/candidate-interop` + `cross-repo/previous-pair-certified` + `ci/local-release-admission` para merges pertenecientes a esta iniciativa.
- Si las reglas no pueden aplicarse selectivamente por rama/PR del evolutivo, evaluar cómo no bloquear tareas independientes: usar rama de integración protegida `integration/runtime-fabric-v1` y promover solo después a `main` con la misma evidencia. Evitar crear nueva autoridad de ROADMAP por crear rama.
- Proteger tags/release refs y restringir su creación al publisher confiable. Validar que el tag publicado apunta a commit contenido en `main`; no reescribir una release existente.
- El publisher configura su propio control de pre-release `PAIR_CANDIDATE_GREEN`, publicación de PK cuando cambie, consumo de PK publicado en Fabric, publicación Fabric, y certificación final `PAIR_CERTIFIED`.
- Fijar status checks a SHA exacto del PR, no a rama mutable, y comprobar que no están obsoletos al fusionar. Verificar la última versión del artefacto del productor en el job externo.
- El recibo certificado y sus evidencias van a un almacén versionado/acotado de artefactos de release; en los repos solo referencias al recibo por digest desde las autoridades de producto.

## No hay transacción de releases entre dos repositorios

GitHub no ofrece operación atómica sobre dos tags en repos distintos. Por ello `PAIR_CERTIFIED` se exige antes de desbloquear M(n+1), no como condición para publicar el tag PK necesario para que Fabric lo consuma. Un fallo de Fabric deja un PK compatible hacia atrás publicado, sin promover el par. Último par certificado = objetivo seguro de rollback.

## Prueba de la política de protección

1. Crear una rama de ensayo en cada repo y enviar un PR con cambios inocuos pero sin status cross-repo. Debe impedir merge.
2. Publicar status desde runner en SHA exacto de PR y comprobar que el merge se habilita solo cuando todas las puertas están verdes.
3. Sustituir el SHA del head y verificar que el status anterior ya no habilita merge.
4. Simular intento de publicar tag con cuenta sin permiso y verificar rechazo.
5. Intentar M(n+1) con recibo de M(n) ausente y verificar `BLOCKED`, tanto por script local como por el runner que reporta status.

**Si no existen reglaset/branch protection y publisher aislado, el script es una protección de proceso del agente, no un bloqueo remoto a prueba de `git push` manual.** Registrar esa limitación sin afirmar que ya está protegida GitHub.
