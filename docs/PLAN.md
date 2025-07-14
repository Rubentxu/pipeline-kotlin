# Plan de Trabajo - MVP 1: Ejecutor Básico de Pipelines

Este documento describe los pasos para implementar la primera versión funcional del motor de pipelines.

## Tareas

- [ ] **1. Definir la estructura del DSL v1 inicial:** Crear las clases y funciones básicas del DSL (ej. `pipeline`, `stage`, `step`).
- [ ] **2. Implementar un "Hola Mundo" de pipeline:** Crear un script de ejemplo `hello-world.pipeline.kts` que use el DSL.
- [ ] **3. Crear el punto de entrada del CLI:** Implementar la lógica en `pipeline-cli` para aceptar un fichero `.pipeline.kts` como argumento.
- [ ] **4. Implementar el motor de ejecución simple:** El CLI invocará un motor que cargue y ejecute el script de Kotlin.
- [ ] **5. Integración y prueba end-to-end:** Asegurar que el CLI puede ejecutar el script "Hola Mundo" correctamente.
