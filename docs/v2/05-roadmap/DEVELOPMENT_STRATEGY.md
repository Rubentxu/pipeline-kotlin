# Estrategia de desarrollo evolutivo

## 1. Walking skeleton primero

El primer objetivo no es una DSL completa: es demostrar el ciclo completo más pequeño:

```text
.pipeline.kts -> compile -> worker -> event -> store -> projection -> result
```

Después se expande capacidad sin cambiar la columna vertebral.

## 2. Strangler interno

V1 y V2 conviven. No se mueven clases V1 “tal cual” a módulos V2 sólo para aparentar progreso. Un componente se retira cuando:
- equivalente V2 existe;
- UAT pasa;
- consumers han migrado;
- observabilidad confirma uso residual cero o aceptado.

## 3. Tracer bullets

Para decisiones de alto riesgo se implementa una rebanada real muy pequeña, no un framework completo:
- Scripting Host Kotlin 2.4;
- durable replay con 2 Steps;
- FlowExecution custom mínimo;
- K8s Pod worker mínimo;
- ACK/fencing.

## 4. Feature flags

Usar flags sólo para rollout/reversibilidad, no para mantener dos arquitecturas indefinidamente. Cada flag incluye removal milestone.

## 5. Branching

Preferir ramas cortas/PRs verticales. Epics grandes se dividen por acceptance slice, no por capa técnica.

## 6. Risk-first sequencing

Orden elegido porque resuelve incertidumbres caras temprano:
1. scripting/Kotlin upgrade;
2. DSL/context/KSP;
3. durable replay;
4. distributed protocol;
5. Kubernetes;
6. Jenkins projection;
7. breadth de plugins;
8. graph/supply-chain escala.

## 7. Architecture fitness

Las reglas de dependencia, schema compatibility, no-secret, replay y protocol se automatizan. Un ADR sin test/guardrail termina convirtiéndose en una sugerencia.

## 8. UAT en cada milestone

UAT no se reserva para GA. Cada vertical tiene user acceptance observable y evidencia versionada.

## 9. Spikes con fecha de caducidad

Un spike termina en:
- ADR accept/reject;
- benchmark/evidence;
- código descartado o promovido explícitamente.

Nunca se “convierte accidentalmente” en producción.
