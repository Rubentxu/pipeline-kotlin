import hashlib
import json
import pathlib
import subprocess
import tempfile
import unittest

from inventory_tests import classify
from retirement_guard import adjudicate, inspect_paths, Verified, Refused


class InventoryTests(unittest.TestCase):
    def test_explicit_ua_matches(self):
        result = classify(pathlib.Path('v2/pipeline-application/src/test/kotlin/FooUatTest.kt'), '@Test fun testIt() { }')
        self.assertEqual(result['kind_hint'], 'EXPLICIT_UAT_E2E')
        self.assertEqual(result['test_annotated'], '1')

    def test_process_discovery_without_e2e_name(self):
        result = classify(pathlib.Path('v2/x/src/test/kotlin/BoringTest.kt'), 'ProcessBuilder("echo")')
        self.assertEqual(result['kind_hint'], 'PROCESS_USING_CANDIDATE')

    def test_helper_is_not_test_suite(self):
        result = classify(pathlib.Path('v2/x/src/test/kotlin/AbstractE2ETest.kt'), 'class AbstractE2ETest')
        self.assertEqual(result['kind_hint'], 'SUPPORT_OR_ABSTRACT')


class RetirementTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name) / '.pipelinek-cycles'
        self.root.mkdir()
        self.cycle = self.root / 'BQ1-test'
        self.cycle.mkdir()
        self.f = self.cycle / 'test.kt'
        self.f.write_text('fun aat() = true', encoding='utf-8')
        self.m = {'cycle_id': 'BQ1-test', 'status': 'READY_TO_CLOSE',
                  'source_sha': 'a' * 40, 'probes': [{
                      'id': 'AAT-BQ1-01', 'relative_path': 'test.kt', 'kind': 'AAT',
                      'contract_id': 'c1', 'verdict': 'PASS', 'executed': 1, 'skipped': 0,
                      'mutation_or_negative': 'bad xml rejected',
                      'replacement_kind': 'PERMANENT_CONTRACT', 'replacement_id': 'example.kt',
                      'replacement_evidence': 'sha256:abcd',
                      'content_sha256': hashlib.sha256(self.f.read_bytes()).hexdigest(),
                  }]}

    def test_valid_manifest_can_retire(self):
        r = adjudicate(self.m, inspect_paths(self.m, self.root))
        self.assertIsInstance(r, Verified)
        self.assertEqual(r.targets, (self.f,))

    def test_zero_tests_fail_closed(self):
        self.m['probes'][0]['executed'] = 0
        self.assertIsInstance(adjudicate(self.m, inspect_paths(self.m, self.root)), Refused)

    def test_all_skipped_fail_closed(self):
        self.m['probes'][0]['skipped'] = 1
        self.assertIsInstance(adjudicate(self.m, inspect_paths(self.m, self.root)), Refused)

    def test_wrong_hash_refuses(self):
        self.m['probes'][0]['content_sha256'] = '0' * 64
        self.assertIsInstance(adjudicate(self.m, inspect_paths(self.m, self.root)), Refused)

    def test_relative_path_escape_refuses(self):
        self.m['probes'][0]['relative_path'] = '../outside.txt'
        self.assertIsInstance(adjudicate(self.m, inspect_paths(self.m, self.root)), Refused)

    def test_missing_replacement_refuses(self):
        self.m['probes'][0]['replacement_evidence'] = ''
        self.assertIsInstance(adjudicate(self.m, inspect_paths(self.m, self.root)), Refused)

    def test_symlink_refuses(self):
        (self.cycle / 'link.kt').symlink_to(self.f)
        self.m['probes'][0]['relative_path'] = 'link.kt'
        self.assertIsInstance(adjudicate(self.m, inspect_paths(self.m, self.root)), Refused)

    def test_dry_run_does_not_delete(self):
        man = pathlib.Path(self.tmp.name) / 'manifest.json'
        man.write_text(json.dumps(self.m))
        tool = pathlib.Path(__file__).with_name('retirement_guard.py')
        p = subprocess.run(['python3', str(tool), '--cycle-root', str(self.root), '--manifest', str(man)], capture_output=True, text=True)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertTrue(self.f.exists())
        self.assertIn('DRY_RUN_OK', p.stdout)

    def test_apply_deletes_only_authorized_file(self):
        man = pathlib.Path(self.tmp.name) / 'manifest.json'
        man.write_text(json.dumps(self.m))
        spare = self.cycle / 'spare.txt'
        spare.write_text('keep me')
        tool = pathlib.Path(__file__).with_name('retirement_guard.py')
        p = subprocess.run(['python3', str(tool), '--cycle-root', str(self.root), '--manifest', str(man), '--apply'], capture_output=True, text=True)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertFalse(self.f.exists())
        self.assertTrue(spare.exists())


if __name__ == '__main__':
    unittest.main()
