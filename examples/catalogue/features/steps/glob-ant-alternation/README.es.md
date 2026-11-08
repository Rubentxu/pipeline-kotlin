# Glob Ant Alternation

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `steps`

**Resultado esperado:** `success`

## Que ejercita

Los pasos del DSL, uno a uno, y que garantiza cada uno

## Las etapas y los pasos

### 1. `build`

- `writeFile` — `file = "src/keep.txt", text = "keep"`
- `writeFile` — `file = "src/drop.pem", text = "drop"`
- `stash` — `name = "glob-alternation", includes = "src/**/*.{txt,md}"`

### 2. `use`

- `sh` — `rm -rf src`
- `unstash` — `name = "glob-alternation"`
- `sh` — `test -f src/keep.txt`
- `sh` — `test ! -f src/drop.pem`
- `sh` — `ls src`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `keep.txt` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/steps/glob-ant-alternation/glob-ant-alternation.pipeline.kts
pipelinek run      examples/catalogue/features/steps/glob-ant-alternation/glob-ant-alternation.pipeline.kts
```

## Su linaje

- **Nombre historico:** `33-glob-ant-alternation.pipeline.kts`
- **Ruta de origen:** `examples/steps/33-glob-ant-alternation.pipeline.kts`
- **Huella del cuerpo:** `sha256:c005316abe09d9cad99a0c19cf1cfd561a139af5ed1ee4482ce73ff4928e44a6`
