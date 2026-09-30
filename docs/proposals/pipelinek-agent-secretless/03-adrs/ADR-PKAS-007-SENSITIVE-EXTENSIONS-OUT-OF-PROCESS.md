# ADR-PKAS-007 — Extensiones sensibles fuera de proceso

**Status:** proposed.

## Decision

Los plugins ordinarios pueden seguir in-process. Un provider/conector tercero que maneje material secreto no recibe acceso in-process por ServiceLoader de forma arbitraria.

Preferencia: protocolo local versionado y acotado, sin operación genérica de exportación de secretos.

## Built-ins

Integraciones auditadas incluidas con la distribución pueden ser trusted in-process si el review y las fitness rules lo permiten.

