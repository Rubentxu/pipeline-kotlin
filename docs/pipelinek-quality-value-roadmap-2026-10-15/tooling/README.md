# Herramientas incluidas (Python 3.10+, estándar)

`inventory_tests.py`: lee `git ls-files` y todos los archivos `.kt/.java` en `src/test`, observa nombres y APIs de proceso y crea CSV revisable. **No** invoca tests, no ejecuta Gradle, no retira nada. `classify()` es pura, el shell lee Git/archivos.

`import_pr140_inventory.sh`: trae la rama auditada a `refs/remotes/origin/audit/...` y extrae el CSV inicial a la ruta de salida. No mergea ni borra; se requiere remoto y permisos de lectura.

`retirement_guard.py`: valida evidencia de un *ciclo efímero* y, solo con `--apply`, elimina las rutas enumeradas bajo `.pipelinek-cycles/<cycle-id>` fuera del repo. **Nunca** elimina `src/test`. `inspect_paths()` concentra efectos y `adjudicate()` es pura. El manifest debe estar fuera del directorio que se elimina y su `status` debe ser `READY_TO_CLOSE`. El guard no comprueba por sí solo un servidor de evidencias externo: el agente verifica el veredicto/hashes antes de registrarlos.

## Ejecución mínima

```bash
python3 tooling/inventory_tests.py --repo /proyectos/pipeline-kotlin --output /tmp/pipelinek-tests.csv
python3 -m unittest discover -s tooling -p 'test_*.py' -v

# Los probes temporales se guardan fuera de Git:
mkdir -p /tmp/pipelinek-workspace/.pipelinek-cycles/BQ1-example
# Crear un probe real, ejecutarlo y completar manifest con su SHA256 y su sustituto.
python3 tooling/retirement_guard.py \
  --cycle-root /tmp/pipelinek-workspace/.pipelinek-cycles \
  --manifest /tmp/pipelinek-workspace/manifest.json
# --apply SOLO si devuelve DRY_RUN_OK y el operador ha validado pruebas/equivalencia.
```

## Alcance real (prototipo, no producto implementado)

Estas herramientas permiten **empezar** a clasificar y gestionar tests de ciclo sin efectos inesperados; la implementación Kotlin prevista vive en `v2/test-harness` y debe ofrecer la selección, veredicto, matriz y compatibilidad de las specs propuestas. El CLI definitivo y su integración con Gradle quedan por desarrollar en BQ1/BQ2/BQ4. No confundir la herramienta auxiliar entregada con una feature ya integrada o certificada.
