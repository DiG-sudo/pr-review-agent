import pathlib
import tempfile
import time
import unittest
from unittest.mock import patch
import run_agent_e2e as runner


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.source = self.root / 'reports'; self.source.mkdir()
        self.dest = self.root / 'archive'

    def xml(self, content='<testsuite><testcase classname="C" name="test" time="1"/></testsuite>'):
        file = self.source / 'TEST-C.xml'; file.write_text(content); return file

    def test_fresh_xml_archived(self):
        self.xml()
        cases, errors = runner.collect_results(self.source, self.dest, 0)
        self.assertEqual(cases['C.test'][0], 'PASS'); self.assertFalse(errors)
        self.assertTrue((self.dest / 'TEST-C.xml').exists())

    def test_missing_stale_malformed_and_empty_fail_closed(self):
        for content in (None, '<broken', '<testsuite/>'):
            if content is not None: self.xml(content)
            cases, errors = runner.collect_results(self.source, self.dest, 0)
            self.assertTrue(errors)
        self.xml()
        self.assertTrue(runner.collect_results(self.source, self.dest, time.time()+1)[1])

    def test_failure_error_skip_are_not_pass(self):
        for tag, status in [('failure','FAIL'), ('error','ERROR'), ('skipped','SKIP')]:
            self.xml(f'<testsuite><testcase classname="C" name="test"><{tag}/></testcase></testsuite>')
            cases, _ = runner.collect_results(self.source, self.dest, 0)
            self.assertEqual(cases['C.test'][0], status)
            self.assertFalse(runner.coverage([dict(id='M1', tests=['test'], assertions='x')], cases)[1])

    def test_missing_coverage_is_incomplete(self):
        self.assertFalse(runner.coverage([dict(id='M1', tests=['missing'], assertions='x')], {})[1])

    def test_timeout_terminates_child(self):
        code = runner.run_command([runner.sys.executable, '-c', 'import time;time.sleep(30)'], self.root, self.root/'console.log', timeout=.05)
        self.assertEqual(code, 124)
        self.assertIn('timed out', (self.root/'console.log').read_text())

    def test_l0_missing_xml_stops_agent_and_still_archives(self):
        (self.root/'scripts').mkdir()
        (self.root/'scripts/agent_e2e_requirements.json').write_text('[]')
        calls = []
        def fake(command, root, output, timeout=600):
            calls.append(command); output.write_text('fake console'); return 0
        with patch.object(runner, 'ROOT', self.root), patch.object(runner, 'run_command', fake):
            self.assertNotEqual(runner.main([]), 0)
        self.assertEqual(len(calls), 2) # self tests and L0 only
        archive = pathlib.Path((self.root/'target/e2e-logs/LATEST.txt').read_text().strip())
        self.assertTrue((archive/'RESULTS.md').exists())
        self.assertIn('No fresh', (archive/'RESULTS.md').read_text())

    def test_nonzero_l0_stops_agent_even_when_xml_passes(self):
        (self.root/'scripts').mkdir()
        (self.root/'scripts/agent_e2e_requirements.json').write_text('[]')
        calls = []
        def fake(command, root, output, timeout=600):
            calls.append(command); output.write_text('fake')
            if command[0] == 'mvn':
                target = self.root/'target/surefire-reports'; target.mkdir()
                (target/'TEST-C.xml').write_text('<testsuite><testcase classname="C" name="test"/></testsuite>')
                return 7
            return 0
        with patch.object(runner, 'ROOT', self.root), patch.object(runner, 'run_command', fake):
            self.assertEqual(runner.main([]), 7)
        self.assertEqual(len(calls), 2)

    def test_zero_exit_does_not_hide_invalid_xml_or_skipped_tests(self):
        for content in ('<broken', '<testsuite><testcase classname="C" name="test"><skipped/></testcase></testsuite>',
                        '<testsuite><testcase classname="C" name="test"><failure/></testcase></testsuite>'):
            with self.subTest(content=content), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory); (root/'scripts').mkdir()
                (root/'scripts/agent_e2e_requirements.json').write_text('[]')
                def fake(command, cwd, output, timeout=600):
                    output.write_text('fake')
                    if command[0] == 'mvn':
                        target = root/'target/surefire-reports'; target.mkdir(exist_ok=True)
                        (target/'TEST-C.xml').write_text(content)
                    return 0
                with patch.object(runner, 'ROOT', root), patch.object(runner, 'run_command', fake):
                    self.assertNotEqual(runner.main(['--phase', 'l0']), 0)

    def test_agent_failure_archives_intermediate_logs_and_coverage(self):
        (self.root/'scripts').mkdir()
        (self.root/'scripts/agent_e2e_requirements.json').write_text('[{"id":"M1","tests":["test"],"assertions":"x"}]')
        def fake(command, root, output, timeout=600):
            output.write_text('fake')
            if command[0] == 'mvn':
                target = root/'target/surefire-reports'; target.mkdir()
                (target/'TEST-C.xml').write_text('<testsuite><testcase classname="C" name="test"><failure/></testcase></testsuite>')
                (root/'target/e2e-logs/intermediate.log').write_text('event=failed')
            return 0
        with patch.object(runner, 'ROOT', self.root), patch.object(runner, 'run_command', fake):
            self.assertEqual(runner.main(['--phase','agent']), 1)
        archive = pathlib.Path((self.root/'target/e2e-logs/LATEST.txt').read_text().strip())
        self.assertEqual((archive/'intermediate.log').read_text(), 'event=failed')
        self.assertIn('FAIL', (archive/'COVERAGE.md').read_text())
        self.assertTrue((archive/'agent/TEST-C.xml').exists())

    def test_junit_parameter_signature_matches_requirement(self):
        rows, complete = runner.coverage([dict(id='CTX', tests=['test'], assertions='x')],
                                         {'C.test(Path)': ('PASS', '1', 'l0/TEST-C.xml')})
        self.assertTrue(complete)
        self.assertIn('test(Path)', rows[0])

    def test_blocked_rule_never_hides_executed_failure(self):
        requirement = dict(id='DOM', tests=['test'], assertions='x', blocked='undefined rule')
        for actual in ('PASS', 'FAIL', 'ERROR'):
            rows, complete = runner.coverage([requirement], {'C.test': (actual, '1', 'agent/TEST-C.xml')})
            self.assertFalse(complete)
            self.assertIn('| ' + ('BLOCKED' if actual == 'PASS' else actual) + ' |', rows[0])
        rows, complete = runner.coverage([requirement], {})
        self.assertFalse(complete)
        self.assertIn('| NOT RUN |', rows[0])


if __name__ == '__main__':
    unittest.main()
