#!/usr/bin/env python3
"""Run offline checks; preserve evidence and fail closed on incomplete results."""
import argparse
import datetime
import fcntl
import json
import hashlib
import re
import os
import pathlib
import shutil
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]


def collect_results(source, destination, started):
    """Only accept this execution's XML, never a previous green report."""
    destination.mkdir(parents=True, exist_ok=True)
    cases, errors = {}, []
    for file in sorted(source.glob('TEST-*.xml')):
        if file.stat().st_mtime < started:
            continue
        shutil.copy2(file, destination / file.name)
        try:
            suite = ET.parse(file).getroot()
            for case in suite.iter('testcase'):
                key = case.get('classname', '') + '.' + case.get('name', '')
                status = ('FAIL' if case.find('failure') is not None else
                          'ERROR' if case.find('error') is not None else
                          'SKIP' if case.find('skipped') is not None else 'PASS')
                if key in cases:
                    errors.append(f'Duplicate testcase: {key}')
                cases[key] = (status, case.get('time', '-'), f'{destination.name}/{file.name}')
        except (ET.ParseError, OSError) as error:
            errors.append(f'{file.name}: {type(error).__name__}')
    if not cases:
        errors.append('No fresh JUnit testcases')
    return cases, errors


def run_command(command, root, output, timeout=600):
    with output.open('w') as log:
        process = subprocess.Popen(command, cwd=root, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            return process.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            log.write('\nRUNNER: execution timed out or interrupted; process group stopped.\n')
            return 124


def coverage(requirements, cases):
    rows, passed = [], True
    for requirement in requirements:
        evidence, missing = [], []
        for method in requirement['tests']:
            matches = [(key, value) for key, value in cases.items()
                       if key.split('(', 1)[0].endswith('.' + method)]
            evidence.extend(matches)
            if not matches:
                missing.append(method)
        statuses = {value[0] for _, value in evidence}
        status = ('ERROR' if 'ERROR' in statuses else 'FAIL' if 'FAIL' in statuses else
                  'NOT RUN' if missing or not evidence else 'SKIP' if 'SKIP' in statuses else 'PASS')
        if requirement.get('blocked') and status == 'PASS':
            status = 'BLOCKED'
        passed &= status == 'PASS'
        links = ', '.join(f'[{key}]({value[2]})' for key, value in evidence)
        if missing:
            links += '; missing: ' + ', '.join(missing)
        if requirement.get('blocked'):
            links += '; BLOCKED: ' + requirement['blocked']
        rows.append(f"| {requirement['id']} | {requirement['assertions']} | {status} | {links or 'missing'} |")
    return rows, passed


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--phase', choices=('all', 'l0', 'agent'), default='all')
    args = parser.parse_args(argv)
    logs = ROOT / 'target/e2e-logs'
    logs.mkdir(parents=True, exist_ok=True)
    with (logs / '.run.lock').open('w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print('Another e2e runner owns the isolated environment.', file=sys.stderr)
            return 1
        run = logs / datetime.datetime.now().astimezone().strftime('%Y%m%d-%H%M%S-%f')
        run.mkdir()
        production = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                      for p in sorted((ROOT / 'src/main').rglob('*')) if p.is_file()}
        (run / 'production-before.json').write_text(json.dumps(production, indent=2))
        for name, command in [('commit.txt', ['git', 'rev-parse', 'HEAD']),
                              ('git-status.txt', ['git', 'status', '--short'])]:
            result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
            (run / name).write_text(result.stdout if result.returncode == 0 else 'Git metadata unavailable\n')
        plan = ROOT / 'docs/AGENT_END_TO_END_TEST_PLAN.md'
        if plan.exists():
            shutil.copy2(plan, run / plan.name)
        run_started = datetime.datetime.now().astimezone().isoformat()
        started_run = time.time()
        report = ['# Agent offline test results', '', 'Real core/MySQL/Redis; scripted model and GitHub substitutes.', '',
                  '| Phase | Test | Result | Seconds |', '| --- | --- | --- | --- |']
        all_cases, exit_code = {}, 0
        phases = ['l0', 'agent'] if args.phase == 'all' else [args.phase]
        try:
            if plan.exists():
                expected = set(re.findall(r'^\| (AG-[A-Z0-9]+-\d{3}) \|', plan.read_text(), re.MULTILINE))
                manifest = json.loads((ROOT / 'scripts/agent_e2e_requirements.json').read_text())
                actual = [item['id'] for item in manifest]
                if expected != set(actual) or len(actual) != len(set(actual)):
                    raise ValueError('Requirements manifest does not exactly match current plan IDs')
            self_code = run_command([sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts', '-p', 'test_run_agent_e2e.py'], ROOT, run / 'runner-tests.log')
            report.append(f'| runner | stdlib checks | {"PASS" if self_code == 0 else "FAIL"} | - |')
            if self_code:
                exit_code = self_code
            else:
                for phase in phases:
                    command = ['mvn', '-B', '-Dstyle.color=never']
                    if phase == 'l0':
                        command.append('-Dtest=*Test')
                    if phase == 'agent':
                        requirements = json.loads((ROOT / 'scripts/agent_e2e_requirements.json').read_text())
                        source = ROOT / 'src/test/java/com/guodi/pragent/e2e/ReviewAgentFlowIT.java'
                        if source.exists():
                            available = set(re.findall(r'@Test\s+void\s+(\w+)\(', source.read_text()))
                            selected = sorted({method for r in requirements for method in r['tests']} & available)
                            if not selected:
                                raise ValueError('No selected Agent methods for current requirements')
                            command.append('-Dtest=ReviewAgentFlowIT#' + '+'.join(selected))
                            (run / 'selected-agent-tests.json').write_text(json.dumps(selected, indent=2))
                        else:
                            command.append('-Dtest=ReviewAgentFlowIT')
                    command.append('test')
                    if phase == 'l0':
                        shutil.rmtree(ROOT / 'target/test-classes', ignore_errors=True)
                        shutil.rmtree(ROOT / 'target/maven-status/maven-compiler-plugin/testCompile', ignore_errors=True)
                    started = time.time()
                    print(f'Running {phase}; live output: {run / (phase + "-console.log")}', flush=True)
                    code = run_command(command, ROOT, run / f'{phase}-console.log')
                    cases, errors = collect_results(ROOT / 'target/surefire-reports', run / phase, started)
                    all_cases.update(cases)
                    for key, (status, seconds, _) in cases.items():
                        report.append(f'| {phase} | {key} | {status} | {seconds} |')
                    for error in errors:
                        report.append(f'| {phase} | {error} | ERROR | - |')
                    failed = code or errors or any(v[0] != 'PASS' for v in cases.values())
                    if failed:
                        exit_code = code or 1
                        if phase == 'l0':
                            break
                if 'agent' in phases:
                    requirements = json.loads((ROOT / 'scripts/agent_e2e_requirements.json').read_text())
                    rows, complete = coverage(requirements, all_cases)
                    (run / 'COVERAGE.md').write_text('\n'.join([
                        '# Offline requirements evidence', '',
                        '| Source clause | Assertions | Result | JUnit evidence |', '| --- | --- | --- | --- |', *rows, '',
                        'L4 and review quality: EXCLUDED by user instruction.']) + '\n')
                    if not complete:
                        exit_code = exit_code or 1
        except Exception as error:
            report.append(f'| runner | {type(error).__name__}: {error} | ERROR | - |')
            exit_code = exit_code or 1
        finally:
            after = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in sorted((ROOT / 'src/main').rglob('*')) if p.is_file()}
            (run / 'production-after.json').write_text(json.dumps(after, indent=2))
            report += ['', 'Started: ' + run_started,
                       'Finished: ' + datetime.datetime.now().astimezone().isoformat(),
                       'Environment: Java 21; MySQL isolated pr_review_agent_e2e_test; Redis DB 14; random HTTP port.',
                       'Production SHA-256 unchanged: ' + str(production == after)]
            if production != after:
                exit_code = exit_code or 1
            case_dir = run / 'cases'
            case_dir.mkdir(exist_ok=True)
            for file in logs.glob('*.log'):
                if file.stat().st_mtime >= started_run:
                    shutil.copy2(file, run / file.name)
                    if file.name != 'intermediate.log': shutil.copy2(file, case_dir / file.name)
            offline = logs / 'offline-model.json'
            if 'agent' in phases and all_cases:
                if not offline.exists() or offline.stat().st_mtime < started_run:
                    report.append('Offline model attestation missing: ERROR')
                    exit_code = exit_code or 1
                else:
                    proof = json.loads(offline.read_text())
                    shutil.copy2(offline, run / offline.name)
                    if proof.get('realLlmRequests') != 0 or proof.get('realChatModelBeans') != 0:
                        exit_code = exit_code or 1
                    report.append('Offline model attestation: ' + json.dumps(proof))
            for file in ('agent_e2e_requirements.json',):
                shutil.copy2(ROOT / 'scripts' / file, run / file)
            (run / 'ENVIRONMENT.md').write_text('Java 21; isolated MySQL pr_review_agent_e2e_test; Redis DB14; production Stream names isolated by DB14.\nModels: ScriptedChatModel only; all OpenAI auto-configurations excluded.\nSee offline-model.json for model boundary attestation; this is configuration evidence, not packet capture.\n')
            report += ['', 'Real model/GitHub smoke and quality evaluation: EXCLUDED.',
                       'Excluded: forced interrupt, direct same-task concurrency, external status mutation, duplicate call IDs.',
                       'Observer events are test-side evidence, not production Trace.', '', f'Exit code: {exit_code}', '']
            (run / 'RESULTS.md').write_text('\n'.join(report))
            (logs / 'LATEST.txt').write_text(str(run) + '\n')
            print(f'Results: {run / "RESULTS.md"}', flush=True)
        return exit_code


if __name__ == '__main__':
    sys.exit(main())
