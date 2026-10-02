# WU-092 `core.input` — Recibo de release (RP6-B, ciclo `rp6b-input`)

> status: RELEASED en `main` (push `e68b5143..bcaf13ce`)
> verificación: `docs/v2/07-uat/WU092_INPUT_RECEIPT.md`,
> `docs/v2/07-uat/WU092_INPUT_VERIFICATION_REPORT.md`

## 1. Publicación

```text
repositorio  https://github.com/Rubentxu/pipeline-kotlin.git
rama         main
rango        e68b5143..bcaf13ce   (fast-forward, 0 commits perdidos)
```

| SHA | Cambio |
| --- | --- |
| `523ffb0a` | feat(input): contrato de dominio y ADTs cerrados de `core.input` |
| `d0a02bd6` | fix(input): respuesta por fichero segura, y corrección del propio diseño |
| `e16b8e28` | feat(input): superficie DSL con `CoreInputWireCodec` como autoridad única |
| `9895a2b5` | fix(input): routing de producción, y una pregunta rehusada ya falla el run |
| `025808a4` | docs(uat): recibo de certificación con los nueve criterios |
| `bcaf13ce` | docs(uat): informe de verificación, incluidas las mutaciones que sobrevivieron |

## 2. Semver

```text
feat(input)  → MINOR   (superficie nueva: input en StageScope, 33 variantes del IR sellado)
fix(input)   → PATCH   (dos defectos de producción y una spec rectificada)
docs         → sin efecto
```

Los commits mezclan `feat` y `fix` porque el `fix` no era un extra. Sin
`9895a2b5`, la superficie `feat` publicaba un `input` que abortado, expirado o nunca
contestado terminaba el run **en verde con exit 0** — un permiso denegado reportado como
concedido. Sin `d0a02bd6`, además, el canal leía un fichero a medio escribir como si
fuera una decisión. Una release que sólo contuviera los `feat` habría publicado ambos.

`d0a02bd6` incluye además la corrección de la spec que lo precedió: al implementar G2
aparecieron dos casos muertos en la ADT de denegaciones (`MalformedResponse`,
`AlreadyAnswered`) y se eliminaron antes de escribir el código, porque un caso muerto en
una ADT cerrada es peor que un caso ausente.

## 3. Estado de la verificación

| Verificación | Estado | Evidencia |
| --- | --- | --- |
| Suite de certificación | PASS | 2740 tests, 0 fallos, 0 errores, 131 skipped (22m12s) sobre `9895a2b5` |
| UAT HF2 | PASS | `UatInputBlockDurableTest` WI-L1..WI-L9 (9/9) |
| Mutaciones | PASS | 4 cortan; 3 sobreviven y están documentadas como hallazgo (§4) |
| Ratchet del coordinador | PASS | 552 líneas, sin tocar |
| CI remoto | NOT_RUN | `.github/workflows/` no contiene pipelines de producto |

## 4. Efectos pendientes

Ninguno bloqueante. Registrado para el corte siguiente:

- **DEBT-WIRE-AUTHORITY** (`bl-bl-01M3YGH3FE000387X13PG607G0`, P2, Triaged): `Dir`,
  `WithEnv`, `TimeoutBlock` y `RetryBlock` siguen autorando wire inline en el
  compilador. `core.input` no participa: su payload lo escribe únicamente
  `CoreInputWireCodec`, y un guard estructural lo hace cumplir.
- **Guardas de autoridad de wire de dos formas.** El de lock es léxico y el de input
  es estructural. Unificarlos exige decidir antes qué forma tiene un guard léxico para
  un Step cuyo vocabulario se solapa con otro (`put("message"` es compartido con
  `Error`, `WarnError`, `Unstable` y `CatchError`). Eso es un evolutivo horizontal.
- **Sin CI de producto.** Toda la evidencia de RP-6-A y RP-6-B es local. Mientras
  `.github/workflows/` sólo tenga Dependabot, ningún recibo de este tren puede
  afirmar verificación remota.

## 5. Reanudación

Si el trabajo continúa:

```text
SIGUIENTE   RP6-C (no started): el siguiente Step born-behind-registry
PENDIENTE   decisión sobre guardas de wire: una forma o dos, y cuál
PENDIENTE   servidor/agente para responder sin disco (§8 de la spec, sin decidir)
PENDIENTE   autorización de submitter → terreno de when (S1-C), no de este Step
```

El punto de entrada para releer el estado es el propio ciclo SDDK
`p-1f3622e11c093341/rp6b-input` y `docs/v2/05-roadmap/ROADMAP.md`, no este documento.
