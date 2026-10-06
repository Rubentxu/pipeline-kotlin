# PipelineK — Actualización y rollback

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**Última release publicada**: `0.47.0`, confirmado el 2026-10-06 (`releases/latest` → `releases/tag/v0.47.0`).

> **Divergencia de documentación.** Esta página antes llevaba la cabecera *"Release verified against:
> pipelinek 0.39.0"*. Esa cabecera queda retirada: la ruta de actualización descrita aquí se leyó de
> la rama de desarrollo, no de un binario publicado. `0.47.0` es la release más reciente, pero la más
> reciente *con un recibo ejecutado* sigue siendo `0.39.0`.
> Registrado el 2026-10-06. Ver `docs/user/README.es.md` → "Divergencias conocidas".

> **Autoridad.** Este repositorio **no tiene CI remota desde 2026-09-30**: `.github/workflows/` no
> existe. El commit `754ddda0` eliminó los workflows de CI **Ninguna afirmación de esta página se apoya en un pipeline en verde, y nada
> aquí dice que el producto esté listo para producción.** La verificación de una actualización es
> local y manual — ese es justamente el objeto de esta página.

## Al terminar esta página podrás

- [ ] Instalar una versión nueva **junto a** la vieja, sin que nada se rompa mientras compruebas.
- [ ] Cambiar de versión con un comando, y volver atrás igual de fácil.
- [ ] Entender por qué un recibo de certificación de un commit antiguo no certifica tu versión nueva.
- [ ] Ejecutar una lista de comprobación local que sustituye a la CI remota que este proyecto no tiene.

## Palabras que vas a encontrar

| Palabra | Significado cotidiano | Aquí |
|---|---|---|
| Rollback | Volver al ajuste anterior | Reapuntar la versión activa |
| Recibo | La nota firmada que dice "comprobamos *este* fichero exacto" | Ligado a un SHA de commit; nunca se hereda |
| Certificación | La promesa de que una build pasó algunas comprobaciones | Un recibo para un SHA no prueba nada sobre el siguiente SHA |

## Qué te da una actualización y qué no

| | |
|---|---|
| ✅ Sí obtienes | Varias versiones en disco, un cambio con un comando y una vuelta atrás con otro |
| ✅ Sí obtienes | Digests que puedes comprobar tú mismo y una lista local que puedes ejecutar |
| ❌ No obtienes | Una señal de CI verde — no hay CI remota que pueda dar esa señal |
| ❌ No obtienes | Certificación heredada — un recibo cubre únicamente su propio commit |
| ❌ No obtienes | Garantía de que un journal `--db` antiguo siga abriéndose en una versión nueva |

## Los canales, con honestidad

| Canal | Estado | ¿Lo uso? |
|---|---|---|
| `scripts/install-pipelinek.sh` | **Disponible** | Sí. Es la ruta recomendada |
| ZIP de GitHub Releases | **Disponible** | Sí, pero gestionas tú el layout |
| Homebrew (`brew install`) | **No disponible** | No existe ningún tap para PipelineK |

## Actualizar con el instalador multiversión

**Analogía**: un buen técnico nunca tira la llave inglesa vieja antes de que la nueva haya demostrado
su valía. Conserva ambas, prueba la nueva, y sólo entonces suelta la anterior.

### 1. Mira qué tienes ahora

```bash
scripts/install-pipelinek.sh list
```

La versión activa se marca con `*` (`scripts/install-pipelinek.sh:406`).

### 2. Instala la versión nueva junto a la vieja

```bash
scripts/install-pipelinek.sh install <new-version>
```

Esto **no** cambia lo que está activo. Descarga, verifica el digest contra `SHA256SUMS`, extrae en
un directorio temporal, comprueba que el binario reporta exactamente `<new-version>`, y sólo entonces
crea `versions/<new-version>` (`scripts/install-pipelinek.sh:225-335`).

### 3. Pruébalo antes de comprometerte

```bash
scripts/install-pipelinek.sh use <new-version>
pipelinek version
pipelinek doctor
```

`pipelinek version` debe imprimir exactamente la versión que pediste. El instalador aplica la misma
regla en el momento de instalar y rechaza cualquier discrepancia
(`scripts/install-pipelinek.sh:310-335`).

### 4. Si funciona, quédate con ella

Nada que hacer — `use` es el interruptor.

### Revertir (rollback)

```bash
scripts/install-pipelinek.sh list
scripts/install-pipelinek.sh use <previous-version>   # volver atrás
pipelinek version
```

Y después, sólo cuando ya no pienses volver:

```bash
scripts/install-pipelinek.sh uninstall <new-version>
```

`uninstall` se niega a eliminar la versión **activa**
(`scripts/install-pipelinek.sh:430-433`): cambia primero y luego elimina.

## Actualizar con el ZIP

Si instalaste a mano, el esquema es el mismo: instalar al lado y luego cambiar.

```bash
VERSION=<new-version>
URL="https://github.com/Rubentxu/pipeline-kotlin/releases/download/v${VERSION}/pipelinek-${VERSION}.zip"
curl -fsSL -o "pipelinek-${VERSION}.zip" "${URL}"

# Verifica el digest del SHA256SUMS de la release ANTES de descomprimir.
# Para 0.47.0 es 2fa2d272e3b0ad385ef780e165028efaa83f92804a123f1836e17e0690d3301c
shasum -a 256 "pipelinek-${VERSION}.zip"    # macOS
sha256sum "pipelinek-${VERSION}.zip"        # Linux

ROOT="$HOME/.local/share/pipelinek"
unzip -q "pipelinek-${VERSION}.zip" -d "${ROOT}/versions"
export PATH="${ROOT}/versions/pipelinek-${VERSION}/bin:${PATH}"
pipelinek version
```

Para revertir, exporta la línea de `PATH` del directorio de la versión anterior.

| Release | SHA-256 del ZIP | SHA-256 del binario | Commit certificado | Procedencia del digest |
|---|---|---|---|---|
| `0.47.0` | `2fa2d272e3b0ad385ef780e165028efaa83f92804a123f1836e17e0690d3301c` | **NO VERIFICADO** | **NO VERIFICADO** | `SHA256SUMS` de la release |
| `0.39.0` | `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8` | `92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee` | `951b3cb5695ecc46c877776e330266e4bd44aa9e` | Recibo ejecutado |

## Por qué un recibo antiguo no certifica tu versión nueva

Un recibo es evidencia **sobre un commit**. Registra qué se comprobó, sobre qué bytes y en qué
condiciones. No es una propiedad que viaje hacia adelante.

| Commit | Qué existe | Qué dice un recibo suyo |
|---|---|---|
| `951b3cb5695ecc46c877776e330266e4bd44aa9e` | La release `0.39.0` — publicada, y la más reciente con un recibo ejecutado | Evidencia sólo sobre los bytes de `0.39.0` |
| `b08fa948` | La rama de desarrollo documentada aquí | Un estado distinto y posterior — sin evidencia heredada |

**Qué significa esto cuando actualizas:**

- Actualizar no arrastra la certificación. Los bytes nuevos están sin verificar hasta que tú los
  verifiques.
- Un recibo de un SHA antiguo no es evidencia del SHA que acabas de instalar.
- La ausencia de un recibo no es una señal de defecto, y la presencia de uno antiguo no es luz verde.
- La única certificación que puedes reclamar honestamente es la que ejecutaste tú, en la máquina que
  vas a usar de verdad.

## Tu lista de comprobación local

No hay CI remota contra la que apoyarte, así que esto la sustituye. Ejecútala tras cada
actualización.

| # | Comprobación | Condición de aprobado |
|---|---|---|
| 1 | Digest del artefacto | Coincide con el valor publicado en las notas de la release |
| 2 | `pipelinek version` | Imprime `pipeline <version>` — exactamente lo que pediste (`Main.kt:83`) |
| 3 | `pipelinek doctor` | Sale con `0`; tres líneas `jdk:`, `os:`, `workdir:` (`Main.kt:91-110`) |
| 4 | `scripts/install-pipelinek.sh doctor` | Informa del binario activo y avisa si el `PATH` resuelve `pipelinek` en otro sitio (`scripts/install-pipelinek.sh:465-478`) |
| 5 | Un pipeline real | Ejecuta los ejemplos del repositorio: `examples/run.sh` verifica los exit codes y los contratos de eventos |
| 6 | Tu propio pipeline | Tu script, en tu máquina, con tus datos |

> `validate` **no** es un ensayo de `run`. Puede imprimir `VALIDATION SUCCESSFUL` para un script que
> `run` rechaza con exit `2` (`Main.kt:191`, `:228`). Sólo los pasos 5 y 6 cuentan como ejecución real.

## Lo que una actualización no resuelve

| Pregunta | Estado |
|---|---|
| ¿Acepta la versión nueva un journal `--db` antiguo? | **NO VERIFICADO.** No hay ninguna prueba de compatibilidad de journals entre versiones registrada en este repositorio. Haz una copia de seguridad del `--db` antiguo |
| ¿Está la versión nueva lista para producción? | **Sin afirmaciones.** El gate de producto está bloqueado por condiciones externas, no por tu máquina |
| ¿Gestiona esto ya `sdk`? | **No.** Ver la tabla de canales de arriba |

## Trampas y bordes

Léelas después de una actualización correcta.

| Trampa | Qué ocurre | Qué hacer |
|---|---|---|
| Instalaste encima de la única copia | No hay vuelta atrás | Instala al lado, cambia, verifica y luego quita la vieja |
| `use <version>` sobre algo no instalado | El script se detiene con `Version <version> is not installed` | Ejecuta `install <version>` primero (`scripts/install-pipelinek.sh:362`) |
| `uninstall` sobre la versión activa | Rechazado, a propósito | Haz `use` de otra versión primero (`scripts/install-pipelinek.sh:430-433`) |
| Un `pipelinek` más antiguo antes en el `PATH` | `version` imprime el número equivocado | `scripts/install-pipelinek.sh doctor` nombra ambas rutas (`scripts/install-pipelinek.sh:474-478`) |
| `--resume` sin `--db` | Sale con `2` | `--resume` y `--rerun` exigen ambos `--db` (`CliParser.kt:196`, `:202`, `Main.kt:239`) |
| `--resume` junto con `--rerun` | Sale con `1` | Son mutuamente excluyentes (`CliParser.kt:196`, `:202`) |
| Flags **después** de la ruta del script | Se ignoran en silencio — sin error | Los flags van primero: `--db x --resume pipeline.kts`. El parser se detiene en el primer token que no empieza por `--` (`CliParser.kt:144-151`) |

## Siguiente

- [`quickstart.es.md`](quickstart.es.md) — ejecuta un pipeline de principio a fin en la versión que acabas de instalar.
- [`installation.es.md`](installation.es.md) — si todavía no lo has instalado.
- Hub: [`docs/user/README.es.md`](README.es.md).