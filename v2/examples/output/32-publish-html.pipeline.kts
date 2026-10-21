//@ expect-stdout index.html
val controlRoot = System.getenv("PIPELINEK_E2E_CONTROL_ROOT") ?: System.getProperty("user.dir")
pipeline {
    stages {
        stage("build") {
            sh("mkdir -p reports/lpr090")
            writeFile(file = "reports/lpr090/index.html", text = "<html><body>Hello LPR-090</body></html>")
            writeFile(file = "reports/lpr090/data.html", text = "<html><body>Data</body></html>")
            publishHTML(
                name = "lpr090-html",
                reportDir = "reports/lpr090",
                reportFiles = "**/*.html",
            )
            sh("find '$controlRoot/reports' -name '*.html'")
        }
    }
}
