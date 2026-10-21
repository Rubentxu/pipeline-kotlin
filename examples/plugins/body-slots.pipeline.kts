//@ expect-stdout compensated
import example.block.slotPlan
val home = System.getenv("PIPELINEK_E2E_HOME") ?: System.getProperty("user.dir")
pipeline {
    stages {
        stage("Slots") {
            slotPlan(
                forwardCommand = "echo forward >> '$home/forward.log'; exit 1",
                compensateCommand = "echo compensated >> '$home/compensated.log'",
            )
            sh("test -f '$home/compensated.log'")
            sh("cat '$home/compensated.log'")
            slotPlan(
                forwardCommand = "echo forward >> '$home/forward.log'",
                compensateCommand = "echo compensated >> '$home/should-not-exist.log'",
            )
            sh("test ! -f '$home/should-not-exist.log'")
        }
    }
}
