// WU-LPR-063: real Maven project built through pipelinek (installed distribution).
pipeline {
    stages {
        stage("build") {
            sh("mvn -q -B package")
            sh("test -f target/maven-demo-1.0.0.jar")
            echo("MAVEN-DEMO-OK")
        }
    }
}
