// WU-LPR-062: real Gradle JVM project built through pipelinek (installed distribution).
// Exercises: actual subprocesses (gradle), artifact output (jar), failure path (broken test).
//
// S0-B: this fixture invoked `./gradlew`, but integration/gradle-demo has never
// contained a Gradle wrapper of its own — the repo's only wrapper lives at
// v2/gradlew. The smoke therefore failed with exit 127 ("No existe el fichero o
// el directorio") in the self-hosted "Real Project Gradle" stage, so the release
// pipeline could never pass. The wrapper is now referenced by its real path.
// `maven-demo` and `node-demo` use system `mvn`/`npm`, so this fixture is the
// only one that needed a repo-relative wrapper path.
pipeline {
    stages {
        stage("build") {
            sh("../../v2/gradlew --no-daemon -p . build")
            sh("test -f build/libs/gradle-demo.jar")
            echo("GRADLE-DEMO-OK")
        }
    }
}
