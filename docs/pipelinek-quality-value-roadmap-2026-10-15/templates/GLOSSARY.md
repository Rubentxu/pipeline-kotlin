# Glosario de lenguaje ubicuo

- **ADT:** algebraic data type, suma/producto con casos cerrados y `when` exhaustivo.
- **Core funcional:** funciones deterministas que transforman datos inmutables sin I/O ni tiempo global.
- **Interpretador de efectos:** ejecuta una intención (proceso, filesystem, DB, red) y devuelve observación tipada; no duplica reglas de negocio.
- **Seam:** frontera del código donde se sustituye un efecto o se aísla una responsabilidad para verificarla independientemente.
- **Capability:** garantía pública observable por el consumidor; no equivale a una clase test.
- **Contract:** ley que la capacidad debe satisfacer, sea cual sea el test que la verifique.
- **Scenario:** una entrada concreta con oráculo observable; `*.pipeline.kts` puede ser especificación permanente.
- **AAT:** aceptación adversarial del ciclo de trabajo del agente, NO guard permanente.
- **UAT:** aceptación del consumidor real; temporal si es probe de feature, heredada protegida si rige una obligación normativa.
- **E2E:** ensayo de un flujo de extremo a extremo, normalmente caro y dependiente de distribución/servicios.
- **HF0…HF6:** escalera de fidelidad definida en ADR-0072. Su nivel es propiedad de la frontera atravesada, no de la anotación de test.
- **Mutation witness:** cambio deliberado que un test correcto debe detectar, preferentemente por la causa exacta.
- **Equivalent proof:** demuestra misma garantía/defecto y frontera, con artefacto/veredicto enlazados.
- **CandidateId:** identidad de candidata asociada a bytes/manifest; no es sinónimo de tag.
- **Fail-closed:** ante dato no verificable se rechaza o bloquea, nunca convierte en verde.
- **Closeout:** último paso del bloque que promociona leyes, borra probes efímeros, prueba SHA final y publica RC si autorizado.
