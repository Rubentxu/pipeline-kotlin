// Fixture: DSL malformado a propósito. El pipeline debe fallar con
// diagnostics tipados y sin efectos en el workspace.
// Este fichero NO es DSL válido: usa un step inexistente y un bloque sin cerrar.
pipeline {
    stages {
        stage("broken-stage") {
            stepInexistente("esto no existe en ninguna version")
        // bloque stages sin cerrar deliberadamente
}
