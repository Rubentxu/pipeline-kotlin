package dev.rubentxu.pipeline.scripting.consumer

import dev.rubentxu.pipeline.v2.domain.FailureKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * P3-E E6 — the four compatibility dimensions, separated, because they are different properties
 * with different failure modes and only one of them is observable from here.
 *
 * The repository's rule is that "it compiles" is not conservation, so each dimension is named and
 * pinned on its own.
 *
 * BINARY is the fourth dimension and it is deliberately NOT asserted here: this build cannot
 * observe a consumer's `NoSuchMethodError`. `getFailureKind()` changed its descriptor from `String`
 * to `FailureKind`, a real break for any artifact compiled against the previous one. That cannot be
 * made go away by a test, so it is recorded in `v2/contract/published-contract-exceptions.json`
 * against the real SHA — a receipt, not an assertion. Writing a test that "asserts" it would be
 * `assertTrue(true)` with a paragraph attached, which is the failure this file exists against.
 */
@DisplayName("P3-E E6 — single-coordinate consumer: source, semantic and wire dimensions")
class ScriptingAuthoringTest {

    /**
     * SOURCE — compatible in the habitual case.
     *
     * `error("msg")` needs no vocabulary and no ceremony. That was the real risk of typing the
     * parameter: a migration that buys compile-time safety by making the common case verbose has
     * traded one defect for a worse one.
     */
    @Test
    fun `el caso habitual no cambia para el autor`() {
        assertEquals(
            FailureKind.USER,
            ScriptingAuthoring.defaultForm().failureKind,
            "error(\"msg\") es la forma mayoritaria y no puede cambiar. Si esto falla, la " +
                "migracion costo ergonomicidad a cambio de seguridad de tipos.",
        )
    }

    /**
     * SOURCE — the explicit case is expressed typed, and that is the PREFERRED spelling.
     *
     * ~~A consumer that wrote `error("boom", "USER")` no longer compiles. That is the recorded break.~~
     *
     * **Corrected (P3-E E6):** it does compile. The bridge keeps
     * `error(message: String, failureKind: String)` on the STABLE authoring surface, validating
     * the token into `FailureKind` at script construction. `error` is declared STABLE in
     * `DSL_SURFACE_MANIFEST.md`, so typing the parameter silently converted a published surface
     * into a breaking one — and ten UAT scenarios that legitimately wrote the Jenkins spelling
     * stopped compiling, which is how the difference between the two surfaces was discovered.
     *
     * What is NOT restored is the failure mode. `error("boom", "USR")` compiles now and is REFUSED
     * when the pipeline is built, before any run exists; `error("boom", FailureKind.USR)` does not
     * compile at all. Both are improvements, and neither is "nothing changed".
     *
     * What still must not come back is a `String` overload that ACCEPTS anything: two spellings for
     * one decision, only one of them checked, is the ambiguity this migration exists to remove.
     * The compatibility proof lives separately in `LegacyAuthoringCompatibilityTest`, on purpose.
     */
    @Test
    fun `el caso explicito se expresa tipado y proyecta el token historico`() {
        assertEquals(
            FailureKind.USER,
            ScriptingAuthoring.explicitForm(FailureKind.USER).failureKind,
            "el kind explicito llega como valor de dominio, no como texto.",
        )
        assertEquals(
            "USER",
            ScriptingAuthoring.wireToken(FailureKind.USER),
            "FailureKind.name es el token historico. Un observador que ya lea \"USER\" del stream " +
                "durable no puede distinguir este registro de uno escrito antes del cambio, y esa " +
                "indistinguibilidad es la garantia que S8 congela.",
        )
    }

    /**
     * WIRE — the token grammar every durable reader matches on.
     *
     * The tempting assertion here is `kind.name == kind.name`, which has no failure mode. The real
     * risk is SHAPE: a case added later as `Unknown`, or with an empty name, would satisfy every
     * other assertion in this file while writing a token that no external reader can match — and it
     * would fail closed at READ time on records this runtime itself wrote.
     */
    @Test
    fun `todo token durable cumple la gramatica que un lector externo puede emparejar`() {
        val grammar = Regex("[A-Z][A-Z0-9_]*")

        for (kind in FailureKind.entries) {
            val token = kind.name
            assertTrue(
                grammar.matches(token),
                "el token durable de $kind es '$token' y no cumple [A-Z][A-Z0-9_]*. Un nombre en " +
                    "minusculas, mixto o vacio escribe en el stream algo que ningun lector externo " +
                    "puede emparejar.",
            )
        }

        assertEquals(
            10,
            FailureKind.entries.size,
            "el vocabulario cerrado debe seguir siendo el mismo. Anadir un caso es un cambio al " +
                "vocabulario durable y no debe pasar por esta puerta sin que alguien lo mire.",
        )
    }
}
