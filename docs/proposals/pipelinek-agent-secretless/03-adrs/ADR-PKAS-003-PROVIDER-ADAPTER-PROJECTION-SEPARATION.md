# ADR-PKAS-003 — Separar Provider, ToolAdapter y Projection

**Status:** proposed.

## Decision

- Provider: origen/derivación de identidad.
- ToolAdapter: conocimiento de una herramienta/protocolo.
- Projection: mecanismo concreto de uso.
- ExecutionBackend: lugar/aislamiento de ejecución.

## Consequence

Evita combinaciones NxM hardcodeadas (`VaultMavenProvider`, `AsvNpmProvider`, etc.) y permite mezclar providers y herramientas de forma abierta.

