# PipelineK — paquete de entrega continua con pruebas proporcionales y efímeras

**Corte de referencia:** `Rubentxu/pipeline-kotlin`, `main` @ `29981033ee1374f89e5711c7d5c2bc4b620e1b2a`, 2026-10-15.
**Naturaleza:** propuesta de integración, **NO** cambio aceptado ni release ejecutada.
**Objetivo:** bloques de valor completos; decisiones funcionales puras, efectos aislados y retirada de pruebas temporales con cobertura permanente trazable.

## Qué contiene

- `agent/MANDATO.md`: mandato completo para un agente de desarrollo, reglas STOP y definición de DONE.
- `overlay/docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md`: **adenda** al roadmap vigente, cinco hitos grandes BQ1..BQ5. Nunca sustituir `docs/v2/05-roadmap/ROADMAP.md`.
- `overlay/docs/v2/03-specifications/`: especificaciones propuestas de planificador, ejecución de pruebas, ontología, ciclo y cierre.
- `overlay/docs/v2/04-adrs/`: cuatro ADRs **propuestos**, con identificadores `ADR-PK-QV-*` para evitar colisiones.
- `overlay/docs/v2/06-quality/`: políticas de prueba, contratos y presupuestos.
- `overlay/docs/v2/07-uat/`: trazabilidad de UAT/AAT por hito, **no scripts permanentes**.
- `templates/`: plantillas de manifiestos y recibos por ciclo.
- `tooling/`: censo reproducible de tests + guardia no destructiva para cerrar un ciclo.
- `inventory/`: fotografía de auditoría y cómo sincronizar el inventario vivo de PR #140.
- `examples/`: ejemplo de ciclo y contrato técnico.

## Integración rápida

1. Revisar `INTEGRACION.md`; el paquete es **aditivo** y no sobrescribe los ADR ni el roadmap existentes.
2. Desde el checkout limpio, copiar `overlay/docs` a `docs` (después de revisar diffs): `cp -a RUTA_DEL_PAQUETE/overlay/docs/. ./docs/`.
3. Dejar `agent/`, `templates/` y `tooling/` **fuera del repositorio** en el workspace local, si la política de `AGENTS.md` no permite guardar la orquestación. Solo versionar en PipelineK contratos/specs/ADRs expresamente aceptados.
4. Dar `agent/MANDATO.md` al agente como instrucción inicial. El agente valida contra HEAD y crea una rama desde `main` para **BQ1**.
5. Ejecutar `python3 tooling/inventory_tests.py --repo /ruta/pipeline-kotlin --output /tmp/pipelinek-tests.csv` para inventario **completo de archivos** de pruebas del checkout; no presupone qué son UAT.
6. Integrar el CSV existente de PR #140 conforme a `inventory/BASELINE.md` (no asumir que la PR ya está mergeada).

## Garantías y limitaciones

- No se ha cambiado código ni generado releases: se entrega diseño y herramientas iniciales; el agente deberá implementar, probar y publicar cada hito.
- La política vigente `RESPONSIBILITY_MIGRATION_ROADMAP.md` **exige equivalencia externa antes de migrar/borrar UAT heredadas**; el documento impide una poda masiva insegura.
- **Prueba de ciclo** ≠ **contrato permanente**. Se borra el *código temporal* UAT/E2E/AAT de cada hito, se conservan contratos normativos, ejemplos de usuario, regresiones esenciales y evidencia verificable por SHA/artifact.
- Publicar `release candidate` en este repo **no** implica `CERTIFIED` o `STABLE_PROMOTED`. La certificación y promoción estable requieren el harness externo.
- Ante un bloqueo: entregar el bloque terminado donde sea correcto y registrar `BLOCKED_EXTERNAL` sin falsificar PASS; no declarar un hito completo si faltan sus condiciones de salida.
