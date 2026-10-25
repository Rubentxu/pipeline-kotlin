//@ expect-stdout TELEMETRY_EVERY_LINE_NAMES_THE_TRACE
//@ expect-stdout TELEMETRY_BRANCHES_HAVE_A_SPAN_EACH
//@ expect-stdout TELEMETRY_OTLP_CARRIES_THE_TRACE
//@ expect-stdout TELEMETRY_OTLP_CARRIES_THE_METRICS
//@ expect-stdout TELEMETRY_OTLP_ALONE_IS_REFUSED

// The telemetry export, at the boundary a consumer actually reaches it.
//
// THE GUARANTEE UNDER TRIAL
//
// A consumer that asks the product for a run's telemetry gets an export it
// can USE: every line names the trace it belongs to, the branches of a
// parallel stage are distinguishable from one another, the OpenTelemetry
// rendering carries that same trace together with the run's metrics, and
// the OpenTelemetry shape asked for on its own is REFUSED instead of
// rendered with no run behind it.
//
// The unit rows guard the projection and the parser. What they cannot reach
// is the seam between them: a real run, a real event store, the real binary
// and the real exit code. This scenario walks that seam once, on purpose,
// so the wiring has a witness.
//
// WHY A NESTED RUN
//
// The export describes a run that has ALREADY finished, so the scenario has
// to produce one before it can read it. The run below is a nested one, with
// its own database and its own control root inside this scenario's own
// workspace: it shares no state with the scenario that observes it, and its
// run id is READ from the control root it wrote, never invented.
//
// WHY THE MARKERS AND NOT AN ABORT
//
// Each check prints its own marker when it holds and its own failure token
// when it does not, and the run carries on to the next one. The verdict
// comes from the `expect-stdout` directives above: a missing marker is a red
// row that NAMES the guarantee that was lost, instead of a run that stopped
// at the first failure and hid the rest. The failure tokens never contain
// an expected marker, so a failure can never be read as a pass.
//
// HOW THE PRODUCT IS REACHED
//
// Through the entry point the launcher writes for this scenario, which
// carries this scenario's own clock: a question that hangs stops with the
// scenario instead of hanging the suite.

val product = System.getenv("PIPELINEK_E2E_PRODUCT")
    ?: error("PIPELINEK_E2E_PRODUCT is not set: this scenario must run under examples/run.sh")

pipeline {
    stages {
        stage("telemetry") {
            // The nested run: three branches inside one stage, so a span id
            // built from the stage and the ordinal alone would collapse them
            // into one and the export would describe a run that never
            // happened.
            sh(
                """
                cat > inner.pipeline.kts <<'INNER'
                pipeline {
                    stages {
                        stage("branches") {
                            parallel {
                                branch("a") { echo("branch-a") }
                                branch("b") { echo("branch-b") }
                                branch("c") { echo("branch-c") }
                            }
                        }
                    }
                }
                INNER
                nested=0
                '${product}' run --isolated --db telemetry.db --control-root telemetry-control inner.pipeline.kts > inner.out 2> inner.err || nested=${'$'}?
                if [ "${'$'}nested" != "0" ]; then
                    echo "the nested run failed with ${'$'}nested:" >&2
                    cat inner.err >&2
                    echo LEG_telemetry_nested_run
                else
                    run_id=""
                    for record in telemetry-control/last-run/*; do
                        [ -f "${'$'}record" ] || continue
                        run_id="${'$'}(head -1 "${'$'}record")"
                        break
                    done
                    if [ -z "${'$'}run_id" ]; then
                        echo "the nested run left no run id in its own control root" >&2
                        echo LEG_telemetry_nested_run
                    else
                        echo "${'$'}run_id" > run-id.txt
                    fi
                fi
                """.trimIndent(),
            )

            // The export, line by line. Every line has to name the trace it
            // belongs to: a reader that receives ONE line -- the last one, a
            // line filtered out of a stream -- has to be able to say which
            // run it is looking at without consulting the header that came
            // before it.
            sh(
                """
                run_id="${'$'}(cat run-id.txt 2>/dev/null || true)"
                if [ -z "${'$'}run_id" ]; then
                    echo "there is no finished run to export" >&2
                    echo LEG_telemetry_export
                else
                    export_code=0
                    '${product}' inspect "${'$'}run_id" --db telemetry.db --telemetry > telemetry.jsonl 2> telemetry.err || export_code=${'$'}?
                    if [ "${'$'}export_code" != "0" ]; then
                        echo "the export failed with ${'$'}export_code:" >&2
                        cat telemetry.err >&2
                        echo LEG_telemetry_export
                    else
                        trace="${'$'}(sed -n 's/.*"traceId":"\([0-9a-f]\{32\}\)".*/\1/p' telemetry.jsonl | head -1)"
                        echo "${'$'}trace" > trace-id.txt
                        total="${'$'}(grep -c . telemetry.jsonl || true)"
                        named="${'$'}(grep -c "\"traceId\":\"${'$'}trace\"" telemetry.jsonl || true)"
                        if [ -n "${'$'}trace" ] && [ "${'$'}total" = "${'$'}named" ]; then
                            echo TELEMETRY_EVERY_LINE_NAMES_THE_TRACE
                        else
                            echo "${'$'}named of ${'$'}total lines name the trace ${'$'}trace" >&2
                            echo LEG_telemetry_every_line_names_the_trace
                        fi
                        spans="${'$'}(grep '"name":"pipelinek.step"' telemetry.jsonl | sed -n 's/.*"spanId":"\([0-9a-f]\{16\}\)".*/\1/p' | sort -u | wc -l)"
                        if [ "${'$'}spans" -ge 3 ]; then
                            echo TELEMETRY_BRANCHES_HAVE_A_SPAN_EACH
                        else
                            echo "the three branches share ${'$'}spans spans" >&2
                            echo LEG_telemetry_branches_have_a_span_each
                        fi
                    fi
                fi
                """.trimIndent(),
            )

            // The same export, rendered in the OpenTelemetry shape: one
            // document, carrying the trace of the run it describes and the
            // metrics a collector reads. It is the SAME export, not another
            // one -- the policy ran once and only the shape changed.
            sh(
                """
                run_id="${'$'}(cat run-id.txt 2>/dev/null || true)"
                trace="${'$'}(cat trace-id.txt 2>/dev/null || true)"
                otlp_code=0
                '${product}' inspect "${'$'}run_id" --db telemetry.db --telemetry --otlp > otlp.json 2> otlp.err || otlp_code=${'$'}?
                if [ "${'$'}otlp_code" != "0" ]; then
                    echo "the OTLP rendering failed with ${'$'}otlp_code:" >&2
                    cat otlp.err >&2
                    echo LEG_telemetry_otlp
                else
                    if grep -q "resourceSpans" otlp.json && grep -q "\"traceId\":\"${'$'}trace\"" otlp.json; then
                        echo TELEMETRY_OTLP_CARRIES_THE_TRACE
                    else
                        echo "the OTLP document does not carry the trace ${'$'}trace" >&2
                        echo LEG_telemetry_otlp_carries_the_trace
                    fi
                    if grep -q "resourceMetrics" otlp.json; then
                        echo TELEMETRY_OTLP_CARRIES_THE_METRICS
                    else
                        echo "the OTLP document carries no metric" >&2
                        echo LEG_telemetry_otlp_carries_the_metrics
                    fi
                fi
                """.trimIndent(),
            )

            // The shape asked for on its own: it renders the telemetry
            // export, so without the export it has no run to render, and the
            // product says so instead of printing an empty document.
            sh(
                """
                run_id="${'$'}(cat run-id.txt 2>/dev/null || true)"
                refuse=0
                '${product}' inspect "${'$'}run_id" --db telemetry.db --otlp > refuse.out 2> refuse.err || refuse=${'$'}?
                if [ "${'$'}refuse" != "0" ] && grep -q -- "--telemetry" refuse.err; then
                    echo TELEMETRY_OTLP_ALONE_IS_REFUSED
                else
                    echo "asking for --otlp alone answered ${'$'}refuse:" >&2
                    cat refuse.err >&2
                    echo LEG_telemetry_otlp_alone_is_refused
                fi
                """.trimIndent(),
            )
        }
    }
}
