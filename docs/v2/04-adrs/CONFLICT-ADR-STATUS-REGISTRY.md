# CONFLICT — el registro de estado de las ADR se contradice a sí mismo

**Registrado:** 2026-10-18 · `main@4dd4f725` · encontrado al cerrar H0 de
`PRODUCT_FIRST_PRODUCTION_READY_2026-10-17.md`.
**Clasificación:** conflicto normativo **NO resuelto**, por instrucción propia de
`ACTIVE_DOCUMENTS.md` §Autoridad: *"Si existen documentos normativos contradictorios, NO
inventar un ganador: registrar el conflicto y requerir ADR."*

> Este documento **no elige** qué side es la correcta y **no** ratifica nada. Lo mide y
> lo escala. Cualquier lectura de "qué ADR está en vigor" debe tratar este fichero como
> una advertencia activa hasta que exista una ADR que lo resuelva.

## El conflicto

Tres autoridades del repositorio describen el mismo campo `status` de forma incompatible.

```text
docs/v2/00-governance/ACTIVE_DOCUMENTS.md §5
    "docs/v2/03-specifications/STEP_PLUGIN_CERTIFICATION.md y ADR-0070..0099
     aceptados y AGENTS.md — límites normativos que un roadmap no revocar."
      -> el RANGO 0070..0099 está ACEPTADO, como bloque

AGENTS.md
    "Authority: ADR-0070..0074 + STEP_CONSTITUTION / STEP_PLUGIN_CERTIFICATION"
    "REGISTRY-PRIMARY Steps ... G0..G8 ... Never record DONE/PASS for an uncertified Step"
      -> invoca 0070..0074 como autoridad operativa vinculante

los propios ficheros, campo frontmatter `status:`
      -> 16 de esos mismos ADR dicen "proposed"
```

Los tres no pueden ser ciertos a la vez. Un ADR **`accepted`** no puede estar
`proposed`, y un ADR `proposed` no puede ser la autoridad que un `AGENTS.md` vinculante.

## Medición — censo sobre `ADR-*.md`, 111 ficheros

La unidad del censo es **`ADR-*.md`**, no el directorio. `docs/v2/04-adrs/` contiene también
documentos que no son ADR —este mismo registro entre ellos—.

**Corrección de la primera medición.** La primera versión de esta tabla se contó sobre
`*.md` (todo el directorio) y dio **112 ficheros / 62 sin campo**. Ambas cifras eran
incorrectas: el directorio contenía además un fichero que no es ADR, y al escribir este
registro su propio `UNSET` se sumó al bloque que documenta. Corregido a censar
`ADR-*.md`: **111 / 61**. Se registra en vez de corregirse en silencio porque la
tabla es la evidencia del hallazgo, y una evidencia con el sujeto equivocado no es
evidencia.

| Lectura de `status:` | Ficheros |
|---|---|
| `accepted` (4 formas de escritura) | 22 |
| `proposed` (2 formas de escritura) | 26 |
| **sin campo `status:`** | **61** |
| `superseded` | 1 |
| `PROPOSED/FUTURE` | 1 |
| total | **111** |

`22 + 26 + 61 + 1 + 1 = 111`. La aritmética **cierra**.

**Los 61 sin campo son el bloque mayoritario, y no son un subconjunto de los otros.** El
índice normativo dice "aceptados" sin criterio consultable, y para 61 de 111 ADR ese
criterio no existe en ningún sitio legible por máquina.

### Y el campo no está normalizado

Ocho formas distintas de decir lo mismo o casi:

```text
accepted      19
ACCEPTED       1   (con nota de renumeración y promoción embebida en el propio valor)
Accepted       1
ACCEPTED(PAR-D closure)  1
proposed      23
PROPOSED       3
PROPOSED/FUTURE  1
(sin campo)    61
```

Un lector que busque `status == "accepted"` distinguiendo mayúsculas encuentra **19 de los
22** ADR que lo están. Falla en silencio y **no en rojo**. Es la misma clase que un ADT con
varias formas para el mismo caso: `Fidelity` se corrigió por eso en `LaneSpec`, y el
dual-emitter/parser de `EvidenceManifest` se cerró por lo mismo. Aquí el ADT sería el estado
del ADR, y sus casos serían `accepted` / `proposed` / `superseded` / `UNSET` — hoy
representados por ocho escrituras distintas, cuatro de ellas para el mismo caso.

### Los 16 del rango declarado "aceptado" que dicen `proposed`

```text
ADR-0070 closed-structure-open-step-registry
ADR-0071 executable-scenarios-are-specifications
ADR-0072 layered-test-harness-fidelity          <- citado por AGENTS.md
ADR-0073 block-steps-reenter-through-body-invoker
ADR-0074 step-done-means-certified              <- citado por AGENTS.md
ADR-0075 retry-control-durable-reconciliation   <- citado por AGENTS.md
ADR-0082 local-production-ready-priority
ADR-0083 body-execution-engine-coordinator-boundary
ADR-0084 honest-dsl-runtime-values
ADR-0085 observation-plane-isolation
ADR-0086 console-event-value-separation
ADR-0087 batched-event-persistence
ADR-0088 cli-observation-contract
ADR-0089 distribution-artifact-authority-sdkman
ADR-0090 compatibility-contract-lpr
ADR-0092 plugin-identity-provider-registration-additive
```

`ADR-0072` y `ADR-0074` son los dos que `AGENTS.md` nombra como autoridad de la STEP
CONSTITUTION y de la secuencia de burn-down G0..G8. `ADR-0075` es la autoridad declarada de
RETRY-D. Si son `proposed`, tres bloques normativos de `AGENTS.md` descansan sobre ADRs no
ratificadas; si son `accepted`, entonces el campo `status:` de 16 ficheros es incorrecto y
`ADR-PK-DF-002` no está en una posición especial respecto a ellos.

## Por qué esto **enreda** el bloqueo de H0.4, en vez de resolverlo

> **Actualización 2026-10-18 — el bloqueo de H0.4 cambió, el conflicto NO.** El product owner concedió
> el gate humano y `ADR-PK-DF-002` pasó a `accepted`. Con ello H0.1 queda `DONE` y el bloqueo
> `BLOCKED_POLICY` de H0.4 desaparece, **pero este documento sigue íntegro**: la ratificación fue
> explícita y acotada a esa ADR, y las 21 restantes —incluidas `ADR-0071`/`0072`/`0074` que
> `ADR-PK-DF-002` lista en su `related:` y que `AGENTS.md` invoca como autoridad MANDATORY— siguen
> en `proposed`. Lo que este documento pedía antes de la ratificación —decidir cuál es el registro de
> verdad y aplicarlo a todo el rango— **sigue sin hacerse**. La línea de abajo ya no describe el
> estado de H0.4, y se conserva porque su análisis es la razón de que la ratificación no se presentara
> como si hubiera resuelto el registro.
>
> Además, H0.4 declaraba su síntoma como `FArchM3` en ROJO; medido, está 7/7 verde. El síntoma era
> falso, y su causa fue el escáner contando texto que el sanitizador enmascara.

El bloqueo vigente de H0.4 está redactado como si ratificar `ADR-PK-DF-002` fuera una
decisión local y acotada:

> `ADR-PK-DF-002` queda `proposed`. Las enmiendas a `AGENTS.md`, `ROADMAP.md`,
> `RESPONSIBILITY_MIGRATION_ROADMAP.md` §4 y tres SPECs requieren decisión del operador.

Eso **no es decidible tal como está escrito.** Pedir al operador que ratifique un ADR
cuando 21 más comparte el mismo estado —incluyendo los que ese mismo `AGENTS.md` invoca como
autoridad— no es una decisión binaria: primero hay que resolver **cuál es el registro de
verdad** del estado de una ADR.

Y la nota de `ADR-PK-DF-002` sobre sí misma agrava el problema:

```text
"Copiar un ADR proposed a su ruta canónica no lo ratifica"
```

Si el registro canónico de `ADR-PK-DF-001` está `accepted` mientras la copia de su ZIP dice
`proposed`, y 16 ADR del rango declarado-aceptado dicen `proposed` en su ruta canónica,
entonces **la ruta canónica no es un indicador fiable de ratificación**. La propia regla que
bloquea H0.4 descansa sobre un campo que ya se contradice 21 veces.

## Disposición propuesta — requiere ADR, NO una sesión

No se resuelve aquí. Se propone, para que el operador la ratifique o la rechace:

1. **Declarar cuál es la autoridad del estado de una ADR**: el frontmatter del fichero, el
   índice `ACTIVE_DOCUMENTS.md`, o un registro aparte. Hoy los tres existen y discrepan.
2. **Una ADR que resuelva los 26 `proposed` y los 61 `UNSET` como un bloque**, o al menos
   los 16 del rango 0070..0099. Ratificarlos uno a uno es lo que produjo el estado actual.
3. **Un valor canónico único** para `status`, y una comprobación mecánica que falle si
   aparece una forma nueva. Es el mismo patrón que `Fidelity` en `LaneSpec`: el defecto es
   que la representación tiene dos formas, no que falte un enum.
4. **Sólo entonces** reevaluar si H0.4 sigue siendo un bloqueo o si era un artefacto de un
   campo mal formado.

## Lo que este documento NO hace

- No ratifica ni deroga ninguna ADR.
- No elige ganador entre `ACTIVE_DOCUMENTS.md` y los frontmatters.
- No desbloquea H0.4. H0.4 sigue `BLOCKED`.
- No propone borrar ni mover ficheros. Nada se ha modificado en `docs/v2/04-adrs/` salvo
  la creación de este documento.

## Cómo reproducir la medición

Sobre `ADR-*.md` únicamente:

```bash
cd docs/v2/04-adrs
ls ADR-*.md | wc -l
# 111

for f in ADR-*.md; do grep -m1 -i '^status:' "$f" | cut -d: -f2- | tr -d ' "'; done \
  | sort | uniq -c | sort -rn
#   23 proposed
#   19 accepted
#    3 PROPOSED
#    1 superseded
#    1 PROPOSED/FUTURE
#    1 ACCEPTED(renumberedfromADR-0082…)
#    1 ACCEPTED(PAR-Dclosure)
#    1 Accepted
```

La forma vacía no aparece en `uniq -c`, porque no hay línea que cortar: hay que contarla
aparte y **sumarla** al total, o la tabla parece cuadrar cuando en realidad omite el bloque
mayoritario.

```bash
cd docs/v2/04-adrs
n=0; for f in ADR-*.md; do grep -q -i '^status:' "$f" || n=$((n+1)); done
echo "sin status: $n"
# sin status: 61
```

**Cierre aritmético.** Con cada forma de escritura como categoría propia:

```text
accepted:   19 + 1 Accepted + 1 ACCEPTED(PAR-Dclosure) + 1 ACCEPTED(renumbered…) = 22
proposed:   23 + 3 PROPOSED                                          = 26
otros:      1 PROPOSED/FUTURE + 1 superseded                        =  2
sin campo:                                                       = 61
                                                                  --------
                                                                    111  = ls ADR-*.md | wc -l
```

La suma cierra **exactamente**. Lo que no cierra es el acceso automatizable: para leer `22`
hay que decidir de antemano que `ACCEPTED(PAR-D closure)` pertenece a `accepted`, y un filtro
`status == "accepted"` en minúsculas sobre `accepted` encuentra **19 de 22**. Falla en
silencio y no en rojo. Esa ambigüedad *es* el hallazgo, y por eso el punto 3 de la disposición
propuesta pide un valor canónico único en vez de sólo una ratification masiva.

Si las cuentas no cuadran con la tabla, la tabla está mal y hay que rehacerla antes de usarla.