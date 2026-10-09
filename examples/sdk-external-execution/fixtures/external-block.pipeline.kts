// W6.2b fixture: an EXTERNAL block Step, executed by the installed distribution.
//
// The point of this fixture is not that `repeatBlock` compiles. It is that the host resolves an
// external body-bearing Step by its plugin-owned key, admits it, and runs its BODY -- the inner
// `sh` must actually execute three times. A block Step is the one plugin shape where "the file
// compiled" and "the body ran" are different claims, so the witness has to be the body's own
// effect rather than the plugin's presence.
//
// `repeatBlock(3)` declares the body three times; the plugin's typed output records
// `invocations` and `succeeded`, and the host prints the Step result. So a run that reports
// three successful invocations cannot be produced by parsing alone: the count is written by the
// engine after the body executed.
//
// This fixture deliberately uses no plugin-owned Step inside the body. `sh` is a CORE step, so
// what is under test is the external block's control of a core body, not a second plugin.
import example.block.repeatBlock

pipeline {
    stages {
        stage("ExternalBlock") {
            repeatBlock(3) {
                sh("echo block-body-ran")
            }
        }
    }
}
