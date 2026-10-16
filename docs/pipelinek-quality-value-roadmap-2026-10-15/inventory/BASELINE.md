# Inventario de tests — baseline verificado y actualización automática

## Fuente congelada a 15-10-2026

- Repo `Rubentxu/pipeline-kotlin` `main@29981033ee1374f89e5711c7d5c2bc4b620e1b2a`.
- 920 archivos Kotlin/Java bajo `src/test` según árbol Git inspeccionado.
- 85 coincidencias explícitas por nombre UAT/E2E/Acceptance, de las cuales una clase base `AbstractE2ETest.kt` no es una suite independiente.
- 27 clases candidatas adicionales por nombre; 43 detectadas adicionalmente por referencias de `ProcessBuilder`/`AppBinSupport`/`OwnedSubprocess`; 5 archivos de soporte; 9 scripts relacionados; total 169 entradas del primer ledger (no equivale a 169 pruebas descartables).
- PR de auditoría con CSV y procedimiento: https://github.com/Rubentxu/pipeline-kotlin/pull/140 (borrador al consultar).
- CSV de la rama de auditoría: https://github.com/Rubentxu/pipeline-kotlin/blob/audit/uat-e2e-retirement-inventory-20261015/v2/test-harness/inventory/uat-e2e-retirement.csv
- Procedimiento: https://github.com/Rubentxu/pipeline-kotlin/blob/audit/uat-e2e-retirement-inventory-20261015/v2/test-harness/inventory/README.md

## Para completar al 100% el censo de archivos EN TU CHECKOUT

```bash
# Rutas, categorías y heurísticas por contenido. Cero borrados.
python3 /RUTA/PAQUETE/tooling/inventory_tests.py \
  --repo "$PWD" --output /tmp/pipelinek-all-test-sources.csv

# Recuperar ledger inicial de PR #140 sin hacer merge, si se conserva la rama:
/RUTA/PAQUETE/tooling/import_pr140_inventory.sh "$PWD" /tmp/pipelinek-pr140.csv
```

**No sumar automáticamente** ambas tablas: un archivo puede aparecer en las dos. Unir por `path`, conservar `id` antiguo como alias y revisar el significado semántico leyendo código. `inventory_tests.py` hace un censo de **todos los archivos tracked** bajo `src/test` (no un análisis AST de cada caso dinámico). Procesos iniciados a través de helpers, pruebas generadas y scripts externos deben catalogarse manualmente. `@Test` puede ser una metaanotación y tests parametrizados generan múltiples casos: el script no afirma el número de ejecuciones. Inventariar además `scripts/sandbox`, `scripts/release`, `examples` y cualquier runner externo del `pipelinek-release-harness` al planificar migración.

## Cluster de consolidación sugerido (no permiso de borrado)

| Oleada | Candidatos a inspeccionar | Razón |
|---|---|---|
| A | `UatLocal005RegressionGateTest`, `UatStep00*`, `UatLocal003` | posibles solapamientos en shell/echo/error/timeout; verificar mutantes antes de decidir |
| B | `UatLocal010SmokeE2ESandbox*`, `CompatibilityCorpusTest`, `UatCompat001*` | coste grande, dependencias de cache/orden; equivalencia de corpus y producto |
| C | `*Installed*Uat*`, `ConsoleMultiOpInstallCanaryTest`, `R4ObservabilityCliInstallDistCanaryTest` | fronteras CLI/distribución que pertenecen al certifier externo cuando migradas |
| D | `UatLocal001/002`, `*Resume*`, `*SecretRedaction*`, `*Sandbox*` | alta sensibilidad: no retirar sin kill/resume/no-replay/no-secret reales |
| E | `pipeline-cli/src/test` legacy, `core/src/test/*.disabled`, scripts de `scripts/sandbox` | legado, confirmar no son consumo activo ni cobertura de una capacidad faltante |

## Checklist por cada fila antes de `DELETE_READY`

- [ ] Se leyó cuerpo de test, fixtures y call sites.
- [ ] `ContractId` y escenario normativo exacto trazado.
- [ ] Se clasificó HF efectivamente requerido (no inferido del nombre).
- [ ] Se anotó qué defecto/mutación detectaba, con positiva y negativa.
- [ ] Se implementó y ejecutó sustituto permanente o de harness externo contra el mismo SHA/artefacto.
- [ ] Se preservó misma huella de defecto y salida observable.
- [ ] Para legacy UAT, se cumplió `RESPONSIBILITY_MIGRATION_ROADMAP.md` y la decisión de retirada fue aprobada.
- [ ] Se borró test y fixtures huérfanas en commit aislado; los gates de SHA final continúan verdes.
