//@ expect-stdout LIVE_SPANS_RUN_SURVIVES_AN_UNREACHABLE_COLLECTOR
//@ expect-stdout LIVE_SPANS_THE_EXPORTER_FAILURE_IS_REPORTED
//@ expect-stdout LIVE_SPANS_RETRY_HAPPENED_UNDER_THE_LIVE_SPANS
//@ expect-stdout LIVE_SPANS_DISABLED_COSTS_NOTHING

// The live spans, with NO collector to receive them.
//
// THE GUARANTEE UNDER TRIAL
//
// Telemetry is a PROJECTION of a run, and a projection that could change the
// run would not be a projection. The live path opens a span at every
// boundary of the engine -- the run, the admission, the compile, the stage,
// the dispatch, the plugin, the retry attempt, the wait, the recovery and the
// release -- and every one of those spans ends up in an exporter that talks to
// a collector. When the collector is NOT there, the exporter fails, and the
// run's result must not notice.
//
// So this scenario runs a REAL pipeline -- a stage, a step and a retry --
// twice against the real distribution:
//
//   with telemetry ON and an unreachable collector   the run SUCCEEDS, the
//     exporter's failure is REPORTED rather than swallowed, and the retry
//     happens: the boundaries were live, and the run could not tell
//   with telemetry OFF                             the run SUCCEEDS, it does its
//     work, and the product says NOTHING about telemetry: a disabled run
//     costs nothing, not even a diagnostic
//
// WHY A NESTED RUN
//
// The telemetry switch is read by the process that STARTS the run, so the run
// that has to carry the setting is a run this scenario starts itself, with
// its own database and its own control root inside this scenario's own
// workspace. It shares no state with the scenario that observes it.
//
// WHY THE RETRY IS NOT DECORATION
//
// A run that never retries would leave the retry boundary unexercised, and
// the retry attempt is one of the boundaries that opens a span. The step
// fails on its first attempt and succeeds on its second, and the count of the
// attempts is what the scenario reads: the retry has to have HAPPENED, not
// merely been configured.
//
// WHY THERE IS NO DOLLAR SIGN IN HERE
//
// A pipeline program is Kotlin, so a dollar sign inside a shell command would
// be read as a template by the compiler of the pipeline that carries it --
// and the nested program is carried by a shell command of THIS program, so
// the nesting is two deep. Every command below is written so that it needs
// none: the exit status is read with `if` rather than with `$?`, and the
// attempt counter is read with `grep -c` rather than with a command
// substitution. The nested program is written through a QUOTED heredoc, so
// the shell that writes it expands nothing either.

val product = System.getenv("PIPELINEK_E2E_PRODUCT")
    ?: error("PIPELINEK_E2E_PRODUCT is not set: this scenario must run under examples/run.sh")

pipeline {
    stages {
        stage("live-spans") {
            // The nested program. The step inside the retry counts its own
            // attempts in a file that survives them, fails while that count
            // is one, and succeeds once it is two.
            sh(
                """
                cat > inner.pipeline.kts <<'INNER'
                pipeline {
                    stages {
                        stage("probe") {
                            retry(3) {
                                sh("echo attempt >> attempts.log && grep -c . attempts.log | sed 's/^/INNER_ATTEMPT_/'")
                                sh("grep -c . attempts.log | grep -qv '^1$'")
                            }
                        }
                    }
                }
                INNER
                """.trimIndent(),
            )

            // The live run: telemetry ON, pointed at a collector that is not
            // there. Nothing listens on the loopback interface at that port,
            // so the exporter's export fails for a reason that is about the
            // collector and about nothing else.
            //
            // The exporter's own timeout is bounded to a second, because the
            // failure under trial is the COLLECTOR'S absence and not how long
            // the exporter waits for it: an unbounded wait would make this
            // scenario expensive without changing the fact it observes.
            sh(
                """
                if PIPELINEK_OTEL_ENABLED=true OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:1 OTEL_EXPORTER_OTLP_TIMEOUT=1000 '${product}' run --isolated --db live.db --control-root live-control inner.pipeline.kts > live.out 2> live.err; then
                    echo LIVE_SPANS_RUN_SURVIVES_AN_UNREACHABLE_COLLECTOR
                else
                    echo "the live run failed with the unreachable collector:" >&2
                    cat live.err >&2
                    echo LEG_live_spans_run_survives_an_unreachable_collector
                fi
                """.trimIndent(),
            )

            // The failure of the exporter has to be REPORTED. A run whose
            // exporter failed silently would be a run that cannot be told
            // apart from a run that was never observed at all, and the
            // operator would read an empty trace as a quiet pipeline.
            sh(
                """
                if grep -qi opentelemetry live.err; then
                    echo LIVE_SPANS_THE_EXPORTER_FAILURE_IS_REPORTED
                else
                    echo "the live run said nothing about the exporter that failed:" >&2
                    cat live.err >&2
                    echo LEG_live_spans_the_exporter_failure_is_reported
                fi
                """.trimIndent(),
            )

            // The retry, under the live spans. The second attempt is the
            // witness: a run that configured a retry and never took it would
            // print a single attempt and pass every other check.
            sh(
                """
                if grep -q INNER_ATTEMPT_2 live.out; then
                    echo LIVE_SPANS_RETRY_HAPPENED_UNDER_THE_LIVE_SPANS
                else
                    echo "the live run did not reach a second attempt:" >&2
                    cat live.out >&2
                    echo LEG_live_spans_retry_happened_under_the_live_spans
                fi
                """.trimIndent(),
            )

            // The quiet run: the same program, with the switch off. It must
            // do its work -- the retry again -- and it must say NOTHING about
            // telemetry, because a disabled run is not allowed to cost
            // anything, and a diagnostic is a cost an operator pays.
            sh(
                """
                if env -u PIPELINEK_OTEL_ENABLED '${product}' run --isolated --db quiet.db --control-root quiet-control inner.pipeline.kts > quiet.out 2> quiet.err; then
                    if grep -q INNER_ATTEMPT_2 quiet.out && ! grep -qi opentelemetry quiet.err; then
                        echo LIVE_SPANS_DISABLED_COSTS_NOTHING
                    else
                        echo "the disabled run did not work, or talked about telemetry:" >&2
                        cat quiet.out >&2
                        cat quiet.err >&2
                        echo LEG_live_spans_disabled_costs_nothing
                    fi
                else
                    echo "the disabled run failed:" >&2
                    cat quiet.err >&2
                    echo LEG_live_spans_disabled_costs_nothing
                fi
                """.trimIndent(),
            )
        }
    }
}
