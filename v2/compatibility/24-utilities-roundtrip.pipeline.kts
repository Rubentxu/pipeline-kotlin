// LFC-2E2 utilities OFFICIAL_PLUGIN live fixture.
//
// Runs end-to-end through the installed `pipelinek` distribution and exercises
// the three first-slice Steps: readJson, writeJson, sha256. The fixture
// writes a JSON document, reads it back, and computes the SHA-256 of the
// produced file via the typed Step.
//
// The plugin owns its typed Kotlin DSL façade; the script imports the
// extension functions explicitly. Steps are side-effects in the DSL: their
// typed Output is observed through the durable journal rather than bound to
// a `val`.
import dev.rubentxu.pipeline.v2.sdk.utilities.step.readJson
import dev.rubentxu.pipeline.v2.sdk.utilities.step.writeJson
import dev.rubentxu.pipeline.v2.sdk.utilities.step.writeJsonRaw
import dev.rubentxu.pipeline.v2.sdk.utilities.step.sha256

pipeline {
    stages {
        stage("utilities-roundtrip") {
            // 1) Write a JSON document to disk via the typed writeJson façade
            //    (uses writeJsonRaw to avoid script-side JsonElement dependency).
            writeJsonRaw("build/utils/data.json", """{"name":"alice","age":30}""")

            // 2) Read it back. The Step's typed Output is observed through
            //    the canonical journal; the script simply notes that the
            //    read happened.
            readJson("build/utils/data.json")

            // 3) Compute the SHA-256 of the produced file via the new Step.
            sha256("build/utils/data.json")

            // 4) Cross-check by echoing the file content with `core.sh` so
            //    the orchestrator can inspect the result end-to-end.
            sh("test -f build/utils/data.json && cat build/utils/data.json")
        }
    }
}
