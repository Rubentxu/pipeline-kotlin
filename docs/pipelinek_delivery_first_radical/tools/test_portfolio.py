#!/usr/bin/env python3
"""Inventario SOLO LECTURA: identifica candidatos de poda sin ejecutar tests ni borrar archivos.
Uso: python3 test_portfolio.py --repo /ruta/pipeline-kotlin --base <sha> --out /tmp/candidatos.tsv
stdlib Python; no network, ni GitHub API, ni escritura dentro de repo.
"""
import argparse
import collections
from pathlib import Path
import re
import subprocess
import sys


def git(repo, *args):
    p = subprocess.run(["git", "-C", str(repo), *args], stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE, check=False)
    if p.returncode:
        raise RuntimeError(f"git {' '.join(args)}: {p.stderr.decode(errors='replace')}")
    return p.stdout


def is_test(path):
    return path.startswith('v2/') and '/src/test/' in path and path.endswith(('.kt', '.java'))


def classify(path, contents):
    name = Path(path).name
    if path.startswith('v2/test-harness/'):
        return 'R1_HARNESS_MODULE', 'retirada completa condicionada: consumidores+ADR+equivalencia'
    if path.endswith('application/harness/InProcessProductProbeExecutor.kt'):
        return 'R1_QA_IN_RUNTIME', 'mover a test o borrar tras revalidar HF1'
    if not is_test(path):
        return None
    if 'pipeline-event-harness' in path:
        return 'KEEP_PUBLIC_FEATURE_REVIEW', 'pipeline events verify tiene consumidor productivo'
    # Tests de alto riesgo no se proponen para borrado solo por su nombre.
    critical = bool(re.search(r'(Secret|Credential|Replay|Recovery|Kill|Sandbox|Resume|Integrity|Tamper|Concurrency|Redact)', name, re.I))
    disabled = '@Disabled' in contents
    migrated = bool(re.search(r'@Disabled\s*\([\s\S]{0,600}?migrated-pending', contents, re.I | re.S))
    historical = bool(re.search(r'@Disabled\s*\([\s\S]{0,600}?(?:historical|superseded|snapshot)', contents, re.I | re.S))
    # Un @Disabled que DECLARA su propia preservación es un archivo deliberado, no un
    # resto muerto. Medido el 2026-10-17 sobre la población R0: 54 de 60 llevan
    # "Preserved verbatim for traceability". Las palabras `historical|superseded|snapshot`
    # que disparan R0 son exactamente el vocabulario de ese archivo, así que la
    # clasificación anterior proponía borrar, con la autoridad de la herramienta, 54
    # registros que sus autores eligieron conservar. La rama de trazabilidad va PRIMERO
    # y es la que decide; R0 queda solo para lo que no declara intención de archivo.
    # Equivalencia real de la población: 4 de 51 sucesores nombrados están vivos
    # (tools/disabled_equivalence_audit.py).
    traceable = bool(re.search(
        r'@Disabled\s*\([\s\S]{0,600}?(?:preserved verbatim|verbatim for traceability|for traceability)',
        contents, re.I | re.S,
    ))
    class_decl = re.search(r'(?m)^\s*(?:(?:internal|public)\s+)?class\s+\w+',contents)
    pre_class = contents[max(0, class_decl.start()-500):class_decl.start()] if class_decl else ''
    # A class-level @Disabled occurs after KDoc and before class; a method-level one occurs later.
    class_disabled = bool(re.search(
        r'@Disabled\s*\([^)]*\)\s*(?:@\w+(?:\([^)]*\))?\s*)*$',pre_class,re.S
    ))
    if name == 'UatLocal005BannedImportsTest.kt':
        return 'KEEP_OR_REPLACE_ARCH', 'prohibición JGit protegida; sustituto arquitectónico mínimo'
    # Orden por fuerza de la senal, de mas fuerte a mas debil:
    #   migrated-pending  -> "no tocar hasta que aterrice la migracion" (manda sobre todo)
    #   traceability      -> archivo deliberado, la razon dice por que se conserva
    #   historical        -> solo la APARIENCIA de historico; es el unico que autoriza podar
    # `migrated` iba despues de `historical`, asi que al ensanchar la ventana de busqueda
    # un archivo con `@Disabled("migrated-pending")` de nivel clase fue reclasificado como
    # podable: exactamente la perdida de proteccion que este clasificador no debe permitir.
    if migrated:
        return 'R2_CRITICAL_OR_PENDING', 'revision critica; NO borrar sin prueba equivalente'
    if traceable:
        return 'R0_ARCHIVED_TRACEABILITY', 'archivo deliberado; NO podar — la razón declara preservación para trazabilidad; auditar sucesores con disabled_equivalence_audit.py'
    if disabled and class_disabled and not critical:
        return 'R2_DISABLED_CLASS_REVIEW', 'sin ejecución; borrar tras equivalencia y política'
    if migrated or (disabled and class_disabled):
        return 'R2_CRITICAL_OR_PENDING', 'revisión crítica; NO borrar sin prueba equivalente'
    if name in ('CoordinatorGrowthGuardrailTest.kt',):
        return 'R0_SOURCE_SHAPE_REVIEW', 'ratchet LOC, preferir contrato arquitectónico real'
    if name.endswith('FitnessTest.kt') or 'CarrierStrictness' in name or 'Guardrail' in name:
        return 'R3_FITNESS_REVIEW', 'evaluar si protege comportamiento o solo forma/texto de fuente'
    if 'Uat' in name or 'E2E' in name or 'Acceptance' in name:
        return 'R2_UAT_E2E_REVIEW', 'retirar/mover a certifier si riesgo y equivalencia permiten'
    # R0_PRUNE va ULTIMO, como fallback. Antes precedia a las ramas de fitness y UAT, y
    # al ensanchar su ventana reclasifico `CoreEmitEventMigrationReadinessFitnessTest`
    # de "evaluar si protege comportamiento" a "eliminar metodos historicos": una prueba
    # de fitness deshabilitada nunca es podable por parecer historica, porque su propia
    # rama existe para exigir un juicio distinto. R0 solo debe alcanzar lo que ninguna
    # otra propiedad reconocio.
    if historical:
        return 'R0_PRUNE_DISABLED_METHODS', 'eliminar métodos históricos @Disabled, conservar vivos'
    return None


def diff_summary(repo, base):
    try:
        rows = git(repo,'diff','--numstat',f'{base}...HEAD','--').decode(errors='replace').splitlines()
    except Exception as e:
        return {'unavailable': str(e)}
    group = collections.defaultdict(lambda: [0,0,0])
    for row in rows:
        bits = row.split('\t',2)
        if len(bits) != 3: continue
        added, removed, path = bits
        if not added.isdigit() or not removed.isdigit(): continue
        if '/src/test/' in path: key='tests'
        elif path.startswith('v2/test-harness/src/main/'): key='harness-main'
        elif '/src/main/' in path: key='product-main'
        elif path.startswith(('docs/','evidence/')): key='docs-evidence'
        elif '/build.gradle' in path or 'scripts/' in path or path in ('justfile','v2/settings.gradle.kts'): key='build-scripts'
        else: key='other'
        group[key][0]+=1;group[key][1]+=int(added);group[key][2]+=int(removed)
    return dict(sorted(group.items()))


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--repo',type=Path,required=True)
    ap.add_argument('--base',default='0253d3d670602273915387ef5aac0f4b14d74556')
    ap.add_argument('--out',type=Path,default=None,help='TSV fuera del Git; stdout cuando no se indica')
    a=ap.parse_args(); root=a.repo.resolve()
    if not (root/'.git').exists() and not (root/'HEAD').exists():
        ap.error(f'{root} no parece un checkout de git')
    head=git(root,'rev-parse','HEAD').decode().strip()
    paths=[s.decode(errors='replace') for s in git(root,'ls-files','-z').split(b'\x00') if s]
    totals=collections.Counter()
    lines=['status\tpath\tmotivo']
    for rel in paths:
        if rel.endswith('.kt') and is_test(rel): totals['tests-v2']+=1
        if rel.startswith('v2/test-harness/src/main/') and rel.endswith('.kt'): totals['harness-main']+=1
        if rel.startswith('v2/test-harness/src/test/') and rel.endswith('.kt'): totals['harness-tests']+=1
        if rel.startswith('v2/pipeline-application/src/test/') and rel.endswith('.kt'): totals['app-tests']+=1
        if not rel.endswith(('.kt','.java')): continue
        p=root/rel
        if not p.is_file():continue
        try: s=p.read_text(encoding='utf-8',errors='replace')
        except OSError:continue
        if 'Fitness' in p.name and is_test(rel):totals['fitness-named']+=1
        if is_test(rel) and '@Disabled' in s:totals['contains-disabled-annotation']+=1
        item=classify(rel,s)
        if not item:continue
        state,why=item
        totals['candidate-'+state]+=1
        lines.append('\t'.join((state,rel,why)))
    report='\n'.join(lines)+'\n'
    if a.out:
        if root==a.out.resolve() or root in a.out.resolve().parents:
            ap.error('--out debe estar fuera del repositorio (ej. /tmp/candidatos.tsv)')
        a.out.parent.mkdir(parents=True,exist_ok=True)
        a.out.write_text(report,encoding='utf8')
        print(f'Inventario: {a.out} ({len(lines)-1} candidatos a revisar; ninguno borrado)')
    else:sys.stdout.write(report)
    print(f'HEAD: {head}',file=sys.stderr)
    print(f'Totales: {dict(sorted(totals.items()))}',file=sys.stderr)
    print(f'Diff {a.base}...HEAD [files, added, removed]: {diff_summary(root,a.base)}',file=sys.stderr)
    print('NO BORRA NADA. Los candidatos son revisión, no autorización de eliminación.',file=sys.stderr)

if __name__=='__main__':
    try:main()
    except Exception as exc:
        print(f'ERROR: {exc}',file=sys.stderr);sys.exit(2)
