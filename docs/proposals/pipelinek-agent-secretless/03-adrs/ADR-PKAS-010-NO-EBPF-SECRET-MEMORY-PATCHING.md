# ADR-PKAS-010 — No eBPF para parchear secretos en memoria

**Status:** proposed.

## Decision

No usar eBPF para insertar/modificar secretos directamente en memoria de procesos como mecanismo primario.

## Allowed research

eBPF puede estudiarse para observación, policy o redirección de sockets/egress cuando tenga garantías verificables, pero no como sustituto mágico de un protocolo de credenciales.

## Rationale

El parcheo de memoria aumenta complejidad, fragilidad por ABI/kernel y superficie de privilegios sin eliminar la necesidad de definir quién posee el secreto y durante cuánto tiempo.

