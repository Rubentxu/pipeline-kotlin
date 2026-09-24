#!/usr/bin/env python3
"""verify-rp-043.py — Verificador externo N3 de WU-RP-043.

Qué hace: ejecuta la batería de escenarios dogfooding de WU-RP-043 y
emite un veredicto tipado a partir de evidencia verificable (códigos
de salida del proceso PipelineK, contenido de XML JUnit, observación
directa del filesystem y exit codes de comandos Probe).

NO se ejecuta dentro del motor PipelineK. NO depende de PipelineK para
verificarse a sí mismo (eso sería el sesgo que el operador prohíbe).
Lee artefactos y código de salida, no logs en stdout del propio motor.

Estados tipados:
    EXECUTED_PASS       -- el escenario pasó y la evidencia es íntegra.
    REUSED_VALID_EVIDENCE -- se reutiliza un resultado fresco del mismo
                            SHA+run; el verificador lo valida.
    FAIL                -- el escenario falló o la evidencia es corrupta.
    BLOCKED             -- el escenario no se pudo ejecutar (entorno).
    NOT_RUN             -- el escenario no figura en la lista activa.

UBICACIÓN DE EVIDENCIA (v2):
    Todo artefacto operativo se escribe bajo XDG:
        $XDG_STATE_HOME/pipelinek/verify/wu-rp-043/<scenario>/<run-id>/
    Por defecto: $HOME/.local/state/pipelinek/verify/wu-rp-043/...
    NADA se escribe dentro del checkout del proyecto. Esta restricción
    cumple la directiva del operador sobre mantener journals, eventos,
    logs de gestión y resultados operativos FUERA del workspace.

Uso:
    scripts/verify-rp-043.py [--root <repo>] [--scenario ID] ...
    scripts/verify-rp-043.py [--root <repo>] --all

Salida: NDJSON por línea (un objeto JSON por escenario ejecutado).
Código de salida del script:
    0 si todos los escenarios obligatorios están EXECUTED_PASS o
      REUSED_VALID_EVIDENCE.
    1 si AL MENOS uno está FAIL, BLOCKED o NOT_RUN (cuando obligatorio).
    2 si el script mismo está mal invocado.

Escenarios por defecto (4 desde 2026-09-24 N2):
    S1_COMPILE_GOOD         -- pipeline.kts real ejecuta gradle real,
                               escribe jar, exit 0, evidencia XML.
    S2_COMPILATION_FAIL     -- el .pipeline.kts no compila (inyectando un
                               error de sintaxis en una copia temporal);
                               PipelineK debe fallar y el verificador
                               debe detectarlo sin false-green.
    S3_TEST_FAIL            -- el stage assertCanDetectFailure invocado con
                               PIPELINEK_FORCE_FAIL=1 debe fallar.
    S4_N2_DEV_SUITE         -- el pipeline.kts extendido corre la suite
                               Gradle real (UatLocal005* + UatDsl001*)
                               y produce XML JUnit frescos con 0 failures.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Optional

# ─── Utilidades ──────────────────────────────────────────────────────────

REPO_DEFAULT = Path("/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin")
SCRIPT_NAME_DEFAULT = REPO_DEFAULT / ".pipeline.kts"
LAUNCHER_DEFAULT = REPO_DEFAULT / "scripts" / "run-pipelinek"
STATE_ROOT_DEFAULT = Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local" / "state")))
PROJECT_STATE_PREFIX = STATE_ROOT_DEFAULT / "pipelinek" / "projects"
VERIFY_ROOT_DEFAULT = STATE_ROOT_DEFAULT / "pipelinek" / "verify" / "wu-rp-043"

BINARY_DEFAULT = Path("/home/rubentxu/.local/share/pipelinek-dist-0.39.0/bin/pipelinek")


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def run(cmd: list[str], cwd: Path, timeout: int = 120, env: Optional[dict] = None) -> tuple[int, str, str]:
    """Run a subprocess; return (exit, stdout, stderr)."""
    try:
        proc = subprocess.run(
            cmd, cwd=str(cwd), capture_output=True,
            text=True, timeout=timeout, env=env,
        )
        return proc.returncode, proc.stdout, proc.stderr
    except subprocess.TimeoutExpired:
        return 124, "", "TIMEOUT after %ds" % timeout


# ─── Estructura del veredicto ─────────────────────────────────────────────


@dataclass
class Verdict:
    scenario: str
    state: str  # EXECUTED_PASS | REUSED_VALID_EVIDENCE | FAIL | BLOCKED | NOT_RUN
    started_at: float = field(default_factory=time.time)
    finished_at: Optional[float] = None
    details: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        d = asdict(self)
        d["started_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(self.started_at))
        if self.finished_at:
            d["finished_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(self.finished_at))
        return d


# ─── Purga defensiva (work-around al defecto del motor 0.39.0) ────────────


def purge_state_dir(state_proj: Path) -> None:
    """Pre-crea el árbol XDG del proyecto de test y vacía sólo el contenido.

    SqliteConnectionFactory (PipelineK 0.39.0) NO crea el directorio padre
    del --db SQLite; aborta con SQLException si journal/ no existe.
    Work-around local: creamos el árbol entero y vaciamos su contenido
    en cada run, sin borrar los directorios. Ver recibo N3 §6.1.
    """
    state_proj.mkdir(parents=True, exist_ok=True)
    for sub in ("journal", "control", "events", "logs", "runs"):
        sub_path = state_proj / sub
        sub_path.mkdir(parents=True, exist_ok=True)
        for child in sub_path.iterdir():
            if child.is_file():
                child.unlink()
            elif child.is_dir():
                shutil.rmtree(child, ignore_errors=True)


def write_evidence(scenario: str, run_id: str, stdout: str, stderr: str,
                   verify_root: Path, extra: dict = None) -> Path:
    """Escribe artefactos de un escenario bajo XDG (NO en el repo)."""
    ev_dir = verify_root / scenario / run_id
    ev_dir.mkdir(parents=True, exist_ok=True)
    (ev_dir / "stdout.json").write_text(stdout)
    (ev_dir / "stderr.log").write_text(stderr)
    if extra:
        for k, v in extra.items():
            if isinstance(v, (str, int, float, bool, list, dict)):
                (ev_dir / f"{k}.json").write_text(json.dumps(v, indent=2, default=str))
    return ev_dir


# ─── Helpers de parseo del JSON event-stream ──────────────────────────────


def parse_events(stdout: str) -> list[dict]:
    """El motor emite un array JSON compactado en una sola línea. Lo partimos
    por profundidad de llaves para extraer cada evento como dict."""
    line = stdout.strip()
    if not line:
        return []
    if not line.startswith("["):
        return []
    objs = []
    depth = 0
    start = None
    for i, ch in enumerate(line):
        if ch == "{":
            if depth == 0:
                start = i
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0 and start is not None:
                try:
                    objs.append(json.loads(line[start:i+1]))
                except json.JSONDecodeError:
                    pass
                start = None
    return objs


def event_runid(stdout: str) -> Optional[str]:
    events = parse_events(stdout)
    for e in events:
        if e.get("kind") == "RunStarted":
            return e.get("runId")
    return None


# ─── Escenarios ──────────────────────────────────────────────────────────


def s1_compile_good(root: Path, script: Path, launcher: Path, verify_root: Path) -> Verdict:
    """S1: pipeline.kts real ejecuta gradle real, escribe jar, exit 0."""
    v = Verdict(scenario="S1_COMPILE_GOOD", state="NOT_RUN")
    project = "pipeline-kotlin-3fda2f2cf251"
    purge_state_dir(PROJECT_STATE_PREFIX / project)

    jar = root / "v2/pipeline-application/src/test/resources/uat-rp-019-real-builds/gradle/good/build/libs/good.jar"
    if jar.exists():
        jar.unlink()

    v.started_at = time.time()
    rc, stdout, stderr = run(
        ["bash", str(launcher), "run", str(script.relative_to(root))],
        cwd=root, timeout=180,
    )
    v.finished_at = time.time()

    run_id = event_runid(stdout) or f"unknown-{int(v.started_at)}"
    extra = {"exit_code": rc, "jar_present": jar.exists(),
             "jar_sha256": sha256_file(jar) if jar.exists() else None}
    write_evidence("S1_COMPILE_GOOD", run_id, stdout, stderr, verify_root, extra)

    events = parse_events(stdout)
    ran_success = any(e.get("kind") == "RunFinished" and e.get("outcome") == "success"
                      for e in events)

    v.details["exit_code"] = rc
    v.details["stdout_bytes"] = len(stdout)
    v.details["stderr_bytes"] = len(stderr)
    v.details["state_dir_present"] = (PROJECT_STATE_PREFIX / project).exists()
    v.details["jar_present"] = jar.exists()
    v.details["jar_sha256"] = sha256_file(jar) if jar.exists() else None
    v.details["run_finished_success"] = ran_success

    if rc == 0 and ran_success and jar.exists():
        v.state = "EXECUTED_PASS"
        v.details["reason"] = "exit=0 AND RunFinished outcome=success AND jar escrito"
    else:
        v.state = "FAIL"
        v.details["reason"] = "exit=%d OR RunFinished outcome != success OR jar no escrito" % rc
    return v


def s2_compilation_fail(root: Path, script: Path, launcher: Path, verify_root: Path) -> Verdict:
    """S2: pipelinek validate sobre un .pipeline.kts con error sintáctico."""
    v = Verdict(scenario="S2_COMPILATION_FAIL", state="NOT_RUN")
    bad_script = Path("/tmp/rp-043-s2-bad.pipeline.kts")
    bad_script.parent.mkdir(parents=True, exist_ok=True)
    bad_script.write_text("syntax error !!!\n" + script.read_text())

    v.started_at = time.time()
    rc, stdout, stderr = run(
        [str(BINARY_DEFAULT), "validate", str(bad_script)],
        cwd=root, timeout=60,
    )
    v.finished_at = time.time()

    run_id = f"validate-{int(v.started_at)}"
    extra = {"exit_code": rc, "diagnostics_returned": '"diagnostics"' in stdout}
    write_evidence("S2_COMPILATION_FAIL", run_id, stdout, stderr, verify_root, extra)
    bad_script.unlink(missing_ok=True)

    v.details["exit_code"] = rc
    v.details["validation_returned_error"] = ("VALIDATION FAILED" in (stdout + stderr))
    v.details["diagnostics_returned"] = ('"diagnostics"' in stdout)

    if rc == 0 and not v.details["diagnostics_returned"]:
        v.state = "FAIL"
        v.details["reason"] = "pipelinek validate retornó 0 sin diagnostics — false-green"
    elif rc != 0 and (v.details["validation_returned_error"]
                       or v.details["diagnostics_returned"]):
        v.state = "EXECUTED_PASS"
        v.details["reason"] = "exit != 0 con diagnostics reportados"
    elif rc == 0 and v.details["diagnostics_returned"]:
        v.state = "FAIL"
        v.details["reason"] = "diagnostics pero exit=0: pipelinek no debe promover validación con errores"
    else:
        v.state = "FAIL"
        v.details["reason"] = "unexpected: rc=%d sin diagnostics ni mensaje de error" % rc
    return v


def s3_test_fail(root: Path, script: Path, launcher: Path, verify_root: Path) -> Verdict:
    """S3: ejecutar el pipeline.kts real con PIPELINEK_FORCE_FAIL=1.

    El stage assertCanDetectFailure invoca `sh("false")` cuando se setea
    la env var. El binario debe abortar con StepFailed + RunFinished
    outcome=failure + exit != 0. Es la prueba end-to-end de que el
    motor propaga fallos de Step correctamente.
    """
    v = Verdict(scenario="S3_TEST_FAIL", state="NOT_RUN")
    project = "rp-043-s3"
    purge_state_dir(PROJECT_STATE_PREFIX / project)

    env = os.environ.copy()
    env["PIPELINEK_FORCE_FAIL"] = "1"

    v.started_at = time.time()
    rc, stdout, stderr = run(
        [str(BINARY_DEFAULT), "run",
         "--db", str(PROJECT_STATE_PREFIX / project / "journal" / "db.sqlite"),
         "--control-root", str(PROJECT_STATE_PREFIX / project / "control"),
         "--workspace", str(root),
         str(script)],
        cwd=root, timeout=60, env=env,
    )
    v.finished_at = time.time()

    run_id = event_runid(stdout) or f"unknown-{int(v.started_at)}"
    extra = {"exit_code": rc, "force_fail": True}
    write_evidence("S3_TEST_FAIL", run_id, stdout, stderr, verify_root, extra)

    events = parse_events(stdout)
    saw_step_failed = any(e.get("kind") == "StepFailed" for e in events)
    saw_run_finished_failure = any(e.get("kind") == "RunFinished"
                                    and e.get("outcome") == "failure"
                                    for e in events)

    v.details["exit_code"] = rc
    v.details["saw_step_failed"] = saw_step_failed
    v.details["saw_run_finished_failure"] = saw_run_finished_failure

    if rc != 0 and saw_step_failed and saw_run_finished_failure:
        v.state = "EXECUTED_PASS"
        v.details["reason"] = "exit != 0 con StepFailed + RunFinished outcome=failure"
    elif rc == 0:
        v.state = "FAIL"
        v.details["reason"] = "exit=0 cuando se esperaba != 0 (false-green)"
    elif rc != 0 and not saw_step_failed:
        v.state = "FAIL"
        v.details["reason"] = "exit != 0 sin StepFailed en el stream"
    else:
        v.state = "FAIL"
        v.details["reason"] = f"unexpected rc={rc}"
    return v


def s4_n2_dev_suite(root: Path, script: Path, launcher: Path, verify_root: Path) -> Verdict:
    """S4 (N2): el pipeline.kts extendido corre la suite Gradle real.

    Verifica que:
      - el pipeline termina con exit=0 y RunFinished outcome=success
      - los XML JUnit de UatLocal005* y UatDsl001* están FRESCOS (<= 600s)
      - el conteo agregado es tests>=40, failures=0, errors=0
      - NO se contabiliza PASS si algún XML falta o tiene failures/errors

    Los artefactos viven en XDG; los XMLs JUnit los produce Gradle dentro
    del checkout (build/test-results/test/), que SÍ es estado legítimo del
    Step "runDevSuite" — Gradle lo escribe como efecto esperado, no como
    estado de gestión del verificador.
    """
    v = Verdict(scenario="S4_N2_DEV_SUITE", state="NOT_RUN")
    project = "rp-043-s4-n2"
    purge_state_dir(PROJECT_STATE_PREFIX / project)

    # Limpiar XMLs previos para verificar frescura post-run
    xml_dir = root / "v2/pipeline-application/build/test-results/test"
    cleaned = []
    if xml_dir.exists():
        for f in list(xml_dir.glob("TEST-*UatLocal005*")) + list(xml_dir.glob("TEST-*UatDsl001*")):
            cleaned.append(f.name)
            f.unlink()

    v.started_at = time.time()
    rc, stdout, stderr = run(
        [str(BINARY_DEFAULT), "run",
         "--db", str(PROJECT_STATE_PREFIX / project / "journal" / "db.sqlite"),
         "--control-root", str(PROJECT_STATE_PREFIX / project / "control"),
         "--workspace", str(root),
         str(script)],
        cwd=root, timeout=600,
    )
    v.finished_at = time.time()

    run_id = event_runid(stdout) or f"unknown-{int(v.started_at)}"
    extra = {"exit_code": rc, "xmls_cleaned_before_run": cleaned}
    write_evidence("S4_N2_DEV_SUITE", run_id, stdout, stderr, verify_root, extra)

    events = parse_events(stdout)
    ran_success = any(e.get("kind") == "RunFinished" and e.get("outcome") == "success"
                      for e in events)

    # Conteo de tests por XML fresco
    import xml.etree.ElementTree as ET
    classes_found = []
    total_tests = 0
    total_fail = 0
    total_err = 0
    total_skip = 0
    max_age = 0.0
    if rc == 0 and ran_success:
        now = time.time()
        for x in sorted(xml_dir.glob("TEST-*UatLocal005*")) + sorted(xml_dir.glob("TEST-*UatDsl001*")):
            tree = ET.parse(x)
            root_el = tree.getroot()
            t = int(root_el.attrib.get("tests", "0"))
            fl = int(root_el.attrib.get("failures", "0"))
            er = int(root_el.attrib.get("errors", "0"))
            sk = int(root_el.attrib.get("skipped", "0"))
            age = now - x.stat().st_mtime
            classes_found.append({"class": x.stem.replace("TEST-dev.rubentxu.pipeline.v2.application.", ""),
                                  "tests": t, "failures": fl, "errors": er, "skipped": sk,
                                  "age_s": round(age, 1)})
            total_tests += t
            total_fail += fl
            total_err += er
            total_skip += sk
            max_age = max(max_age, age)

    v.details["exit_code"] = rc
    v.details["run_finished_success"] = ran_success
    v.details["classes_count"] = len(classes_found)
    v.details["total_tests"] = total_tests
    v.details["total_failures"] = total_fail
    v.details["total_errors"] = total_err
    v.details["total_skipped"] = total_skip
    v.details["max_xml_age_s"] = round(max_age, 1)
    v.details["xml_per_class"] = classes_found

    if rc != 0 or not ran_success:
        v.state = "FAIL"
        v.details["reason"] = f"pipeline no completó con éxito: rc={rc} ran_success={ran_success}"
    elif len(classes_found) < 9:
        v.state = "FAIL"
        v.details["reason"] = f"sólo {len(classes_found)}/9 clases seleccionadas — patrón no exhaustivo"
    elif total_fail > 0 or total_err > 0:
        v.state = "FAIL"
        v.details["reason"] = f"Gradle suite tiene {total_fail} failures + {total_err} errors"
    elif max_age > 600:
        v.state = "FAIL"
        v.details["reason"] = f"XMLs no son frescos: max_age={max_age:.0f}s > 600s"
    elif total_tests < 40:
        v.state = "FAIL"
        v.details["reason"] = f"Gradle suite ejecutó {total_tests} tests (<40 esperados)"
    else:
        v.state = "EXECUTED_PASS"
        v.details["reason"] = (
            f"exit=0 + RunFinished success + {len(classes_found)} clases / {total_tests} tests "
            f"0 failures / 0 errors / {total_skip} skipped / max_age={max_age:.0f}s"
        )
    return v


# ─── Dispatcher ──────────────────────────────────────────────────────────


SCENARIOS = {
    "S1_COMPILE_GOOD": s1_compile_good,
    "S2_COMPILATION_FAIL": s2_compilation_fail,
    "S3_TEST_FAIL": s3_test_fail,
    "S4_N2_DEV_SUITE": s4_n2_dev_suite,
}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", type=Path, default=REPO_DEFAULT,
                    help="Path al checkout del repo (default: %(default)s)")
    ap.add_argument("--script", type=Path, default=None,
                    help="Path al .pipeline.kts (default: <root>/.pipeline.kts)")
    ap.add_argument("--launcher", type=Path, default=None,
                    help="Path al launcher scripts/run-pipelinek")
    ap.add_argument("--verify-root", type=Path, default=VERIFY_ROOT_DEFAULT,
                    help="Directorio XDG para evidencia (default: %(default)s)")
    ap.add_argument("--scenario", action="append", default=None,
                    help="Escenario(s) a ejecutar (repetible). Default: todos los de WU-RP-043.")
    ap.add_argument("--list", action="store_true",
                    help="Listar escenarios disponibles y salir.")
    args = ap.parse_args()

    if args.list:
        for k, fn in SCENARIOS.items():
            print(f"  {k}  {fn.__doc__.splitlines()[0] if fn.__doc__ else ''}")
        return 0

    root = args.root.resolve()
    script = (args.script or root / ".pipeline.kts").resolve()
    launcher = (args.launcher or root / "scripts" / "run-pipelinek").resolve()
    verify_root = args.verify_root.resolve()
    verify_root.mkdir(parents=True, exist_ok=True)

    if not script.exists():
        print(f"ERROR: script no existe: {script}", file=sys.stderr)
        return 2
    if not launcher.exists():
        print(f"ERROR: launcher no existe: {launcher}", file=sys.stderr)
        return 2

    selected = args.scenario or list(SCENARIOS.keys())
    verdicts: list[Verdict] = []
    for sc_id in selected:
        if sc_id not in SCENARIOS:
            v = Verdict(scenario=sc_id, state="NOT_RUN",
                        details={"reason": f"unknown scenario; disponibles: {list(SCENARIOS)}"})
            print(json.dumps(v.to_dict()))
            continue
        v = SCENARIOS[sc_id](root, script, launcher, verify_root)
        print(json.dumps(v.to_dict()))
        verdicts.append(v)

    n_pass = sum(1 for v in verdicts if v.state in ("EXECUTED_PASS", "REUSED_VALID_EVIDENCE"))
    n_fail = sum(1 for v in verdicts if v.state in ("FAIL", "BLOCKED"))
    print(f"\n# SUMMARY: {n_pass}/{len(verdicts)} pass, {n_fail} fail",
          file=sys.stderr)
    return 0 if n_fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
