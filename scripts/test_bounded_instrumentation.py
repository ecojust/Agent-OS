"""Isolated CI runner regressions; no emulator, Gradle or personal data is used."""

import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest

import run_bounded_instrumentation as bounded

SCRIPTS = Path(__file__).resolve().parent


class BoundedInstrumentationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='instrumentation-runner-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'scripts').mkdir()
        for name in ('run_android_tests.sh', 'run_bounded_instrumentation.py',
                     'print_android_test_failures.py', 'collect_android_evidence.py',
                     'collect_performance_evidence.py', 'collect_idle_pair_evidence.py',
                     'collect_stress_evidence.py', 'collect_active_pair_evidence.py'):
            shutil.copyfile(SCRIPTS / name, self.root / 'scripts' / name)
        (self.root / 'bin').mkdir()
        adb = self.root / 'bin/adb'
        adb.write_text('''#!/usr/bin/env python3
import os,sys,time
from pathlib import Path
args=sys.argv[1:]
with Path('adb-calls.txt').open('a') as log: log.write(' '.join(args)+'\\n')
if args==['shell','getprop','ro.build.version.sdk']: print(os.environ.get('FAKE_SDK','30'))
elif args==['shell','getprop','ro.kernel.qemu']: print(1)
elif args[:4]==['shell','settings','get','global']: print('1.0')
elif args[:4]==['shell','settings','put','global']: pass
elif args[:2]==['shell','run-as']: pass
elif args==['install','-r','-t','--force-queryable','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
 if not Path(args[-1]).is_file(): sys.exit(18)
 if os.environ.get('FAKE_INSTALL_EXIT')=='hang': time.sleep(60)
 sys.exit(int(os.environ.get('FAKE_INSTALL_EXIT','0')))
elif args[:2]==['shell','dumpsys'] or args[:2]==['shell','ps'] or args[:1]==['logcat']:
 print('synthetic fixture diagnostic only')
else: sys.exit(1)
''')
        adb.chmod(0o755)
        gradle = self.root / 'gradlew'
        gradle.write_text('''#!/usr/bin/env python3
import json,os,signal,subprocess,sys,time
from pathlib import Path
with Path('gradle-calls.jsonl').open('a') as log: log.write(json.dumps(sys.argv[1:])+'\\n')
assembling=':app:assembleDebugAndroidTest' in sys.argv
if not assembling: Path('connected-started').touch()
Path('gradle-pid').write_text(str(os.getpid()))
mode=os.environ.get('FAKE_ASSEMBLE_MODE','0') if assembling else os.environ['FAKE_GRADLE_MODE']
if mode in ('hang','detached'):
 if mode=='detached':
  signal.signal(signal.SIGTERM,signal.SIG_IGN)
  subprocess.Popen([sys.executable,'-c',
   "import os,signal,time;from pathlib import Path;signal.signal(signal.SIGTERM,signal.SIG_IGN);Path('detached-pid').write_text(str(os.getpid()));time.sleep(60)"],start_new_session=True)
 while True: time.sleep(1)
result=int(mode)
if assembling and not result:
 apk=Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk');apk.parent.mkdir(parents=True,exist_ok=True);apk.write_bytes(b'synthetic APK only')
if result and not assembling:
 report=Path('app/build/outputs/androidTest-results/partial.xml');report.parent.mkdir(parents=True)
 report.write_text('<testsuite><testcase name="existing_failure"><failure>synthetic failure</failure></testcase></testsuite>')
sys.exit(result)
''')
        gradle.chmod(0o755)
        self.env = os.environ.copy()
        self.env.update(PATH=str(self.root / 'bin') + os.pathsep + self.env['PATH'],
                        GITHUB_ACTIONS='true', RUNNER_OS='Linux',
                        GITHUB_REPOSITORY='ecojust/Agent-OS', GITHUB_SHA='a' * 40,
                        GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1',
                        ANDROID_TEST_TIMEOUT_SECONDS='1', ANDROID_TEST_TERMINATE_GRACE_SECONDS='1')

    def run_shell(self, mode, matrix_label='30', scope='full'):
        self.env['FAKE_GRADLE_MODE'] = mode
        self.env['FAKE_SDK'] = matrix_label
        result = subprocess.run(['bash', 'scripts/run_android_tests.sh', matrix_label, scope], cwd=self.root,
                                env=self.env, capture_output=True, text=True, timeout=12)
        return result

    def execution(self):
        return json.loads((self.root / 'instrumentation-diagnostics/api-30/execution.json').read_text())

    def assert_collected(self, exit_code):
        for folder in ('test-evidence', 'performance-evidence'):
            record = json.loads((self.root / folder / 'api-30/capture.json').read_text())
            self.assertEqual(exit_code, record['gradleExitCode'])
            self.assertEqual('unavailable', record['captureStatus'])
        # No fixture captures or performance measurements are invented.
        self.assertFalse(list((self.root / 'test-evidence').rglob('*.png')))

    def test_normal_completion_keeps_zero_and_attempts_both_collectors(self):
        result = self.run_shell('0')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('completed', self.execution()['status'])
        self.assert_collected(0)

    def test_idle_pair_scope_preserves_failure_and_collects_its_separate_report(self):
        result = self.run_shell('23', scope='idle-pair-diagnostic')
        self.assertEqual(23, result.returncode, result.stderr)
        calls = [json.loads(line) for line in (self.root / 'gradle-calls.jsonl').read_text().splitlines()]
        self.assertEqual(1, len(calls))
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.class='
                      'dev.agentos.app.IdlePairedPerformanceInstrumentedTest#'
                      'collectAlternatingServiceOffAndIdlePairs', calls[0])
        self.assertNotIn('--force-queryable', (self.root / 'adb-calls.txt').read_text())
        self.assert_collected(23)
        record = json.loads((self.root / 'idle-pair-evidence/api-30/capture.json').read_text())
        self.assertEqual(23, record['gradleExitCode'])
        self.assertEqual('unavailable', record['captureStatus'])
        self.assertIsNone(record['report'])

    def test_full_keeps_idle_pair_probe_outside_its_existing_denominator(self):
        result = self.run_shell('0')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = [json.loads(line) for line in (self.root / 'gradle-calls.jsonl').read_text().splitlines()]
        excluded = next(value for value in calls[-1]
                        if value.startswith('-Pandroid.testInstrumentationRunnerArguments.notClass='))
        self.assertIn('dev.agentos.app.IdlePairedPerformanceInstrumentedTest', excluded.split('=', 1)[1].split(','))
        self.assertIn('dev.agentos.app.SegmentedStressInstrumentedTest', excluded.split('=', 1)[1].split(','))
        self.assertFalse((self.root / 'idle-pair-evidence').exists())
        self.assertFalse((self.root / 'stress-evidence').exists())
        self.assertFalse((self.root / 'active-pair-evidence').exists())
        self.assertIn('dev.agentos.app.ActivePairedPerformanceInstrumentedTest', excluded.split('=', 1)[1].split(','))

    def test_stress_scope_has_its_own_longer_default_and_preserves_failed_measurement(self):
        self.env.pop('ANDROID_TEST_TIMEOUT_SECONDS')
        result = self.run_shell('23', scope='stress-diagnostic')
        self.assertEqual(23, result.returncode, result.stderr)
        execution = self.execution()
        self.assertEqual(2400, execution['timeoutSeconds'])
        self.assertEqual('segmented-stress', execution['timeoutProfile'])
        calls = [json.loads(line) for line in (self.root / 'gradle-calls.jsonl').read_text().splitlines()]
        self.assertEqual(1, len(calls))
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.class='
                      'dev.agentos.app.SegmentedStressInstrumentedTest#'
                      'collectThirtyMinuteTaskStopAndServiceCycles', calls[0])
        self.assert_collected(23)
        capture = json.loads((self.root / 'stress-evidence/api-30/capture.json').read_text())
        self.assertEqual(23, capture['gradleExitCode'])
        self.assertEqual('unavailable', capture['captureStatus'])
        self.assertIsNone(capture['report'])

    def test_active_pair_scope_uses_standard_budget_and_preserves_failure(self):
        self.env.pop('ANDROID_TEST_TIMEOUT_SECONDS')
        result = self.run_shell('23', scope='active-pair-diagnostic')
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertEqual(1200, self.execution()['timeoutSeconds'])
        self.assertEqual('standard', self.execution()['timeoutProfile'])
        calls = [json.loads(line) for line in (self.root / 'gradle-calls.jsonl').read_text().splitlines()]
        self.assertEqual(1, len(calls))
        self.assertIn('-Pandroid.testInstrumentationRunnerArguments.class='
                      'dev.agentos.app.ActivePairedPerformanceInstrumentedTest#'
                      'collectAlternatingEquivalentManualAndAgentTasks', calls[0])
        self.assert_collected(23)
        capture = json.loads((self.root / 'active-pair-evidence/api-30/capture.json').read_text())
        self.assertEqual(23, capture['gradleExitCode'])
        self.assertEqual('unavailable', capture['captureStatus'])
        self.assertIsNone(capture['report'])
        self.assertFalse((self.root / 'stress-evidence').exists())

    def test_existing_scope_keeps_twenty_minute_default(self):
        self.env.pop('ANDROID_TEST_TIMEOUT_SECONDS')
        result = self.run_shell('0', scope='owned-control-diagnostic')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1200, self.execution()['timeoutSeconds'])
        self.assertEqual('standard', self.execution()['timeoutProfile'])

    def test_longer_timeout_requires_explicit_profile_and_remains_bounded(self):
        for profile, seconds in [('standard', '1201'), ('standard', '2400'), ('segmented-stress', '2401')]:
            with self.subTest(profile=profile, seconds=seconds):
                result = subprocess.run([sys.executable, 'scripts/run_bounded_instrumentation.py',
                                         '--matrix-label', '30', '--timeout-profile', profile,
                                         '--timeout-seconds', seconds, '--', './gradlew'],
                                        cwd=self.root, env=self.env, capture_output=True, text=True, timeout=5)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertFalse((self.root / 'gradle-pid').exists())

    def test_api30_full_installs_only_the_fixed_test_apk_once_before_connected(self):
        result = self.run_shell('0')
        self.assertEqual(0, result.returncode, result.stderr)
        calls = [json.loads(line) for line in (self.root / 'gradle-calls.jsonl').read_text().splitlines()]
        self.assertEqual(['--no-daemon', ':app:assembleDebugAndroidTest'], calls[0])
        self.assertEqual(2, len(calls))
        self.assertEqual(':app:connectedDebugAndroidTest', calls[1][-1])
        self.assertIn('-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true', calls[1])
        self.assertTrue(any(value.startswith('-Pandroid.testInstrumentationRunnerArguments.notClass=')
                            for value in calls[1]))
        installs = [line for line in (self.root / 'adb-calls.txt').read_text().splitlines()
                    if line.startswith('install ')]
        self.assertEqual(['install -r -t --force-queryable '
                          'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'], installs)
        self.assertEqual(1, self.execution()['timeoutSeconds'])

    def test_other_api_and_diagnostic_scope_do_not_change_apk_installation(self):
        for api, scope in [('31', 'full'), ('30', 'owned-control-diagnostic')]:
            with self.subTest(api=api, scope=scope):
                for name in ('adb-calls.txt', 'gradle-calls.jsonl'):
                    (self.root / name).unlink(missing_ok=True)
                result = self.run_shell('0', api, scope)
                self.assertEqual(0, result.returncode, result.stderr)
                calls = [json.loads(line) for line in (self.root / 'gradle-calls.jsonl').read_text().splitlines()]
                self.assertEqual(1, len(calls))
                self.assertEqual(':app:connectedDebugAndroidTest', calls[0][-1])
                self.assertNotIn('--force-queryable', (self.root / 'adb-calls.txt').read_text())

    def test_test_apk_assembly_failure_does_not_install_or_start_connected(self):
        self.env['FAKE_ASSEMBLE_MODE'] = '19'
        result = self.run_shell('0')
        self.assertEqual(19, result.returncode, result.stderr)
        self.assertFalse((self.root / 'connected-started').exists())
        self.assertNotIn('--force-queryable', (self.root / 'adb-calls.txt').read_text())
        self.assert_collected(19)

    def test_test_apk_install_failure_does_not_start_connected_and_is_not_retried(self):
        self.env['FAKE_INSTALL_EXIT'] = '17'
        result = self.run_shell('0')
        self.assertEqual(17, result.returncode, result.stderr)
        self.assertFalse((self.root / 'connected-started').exists())
        self.assertEqual(1, (self.root / 'adb-calls.txt').read_text().count('--force-queryable'))
        self.assertEqual('completed', self.execution()['status'])
        self.assert_collected(17)

    def test_original_watchdog_also_bounds_the_added_assembly(self):
        self.env['FAKE_ASSEMBLE_MODE'] = 'hang'
        result = self.run_shell('0')
        self.assertEqual(124, result.returncode, result.stderr)
        self.assertFalse((self.root / 'connected-started').exists())
        self.assertNotIn('--force-queryable', (self.root / 'adb-calls.txt').read_text())
        self.assertEqual('timed_out', self.execution()['status'])
        self.assertLess(self.execution()['elapsedSeconds'], 6)
        self.assert_collected(124)

    def test_original_watchdog_also_bounds_the_added_install(self):
        self.env['FAKE_INSTALL_EXIT'] = 'hang'
        result = self.run_shell('0')
        self.assertEqual(124, result.returncode, result.stderr)
        self.assertFalse((self.root / 'connected-started').exists())
        self.assertEqual(1, (self.root / 'adb-calls.txt').read_text().count('--force-queryable'))
        self.assertEqual('timed_out', self.execution()['status'])
        self.assertLess(self.execution()['elapsedSeconds'], 6)
        self.assert_collected(124)

    def test_failure_exit_and_existing_partial_junit_are_preserved(self):
        result = self.run_shell('23')
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertEqual('completed', self.execution()['status'])
        self.assert_collected(23)
        record = json.loads((self.root / 'test-evidence/api-30/capture.json').read_text())
        self.assertEqual((1, 1), (record['junit']['tests'], record['junit']['failures']))

    def test_command_124_is_not_mislabelled_as_watchdog_timeout(self):
        result = self.run_shell('124')
        self.assertEqual(124, result.returncode, result.stderr)
        self.assertEqual('completed', self.execution()['status'])
        self.assertIsNone(self.execution()['diagnostics'])
        self.assert_collected(124)

    def test_timeout_has_explicit_status_diagnostics_and_both_collectors(self):
        result = self.run_shell('hang')
        self.assertEqual(124, result.returncode, result.stderr)
        record = self.execution()
        self.assertEqual('timed_out', record['status'])
        self.assertLess(record['elapsedSeconds'], 6)
        self.assertEqual('attempted', record['diagnostics']['status'])
        self.assertEqual(5, len(record['diagnostics']['commands']))
        self.assertIn('Partial test results remain partial', result.stdout)
        self.assert_collected(124)
        self.assertFalse(list(self.root.rglob('*.xml')))

    def test_detached_term_ignoring_descendant_is_killed_and_reaped(self):
        result = self.run_shell('detached')
        self.assertEqual(124, result.returncode, result.stderr)
        self.assert_collected(124)
        for name in ('gradle-pid', 'detached-pid'):
            pid = int((self.root / name).read_text())
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)

    def test_term_to_shell_is_forwarded_and_keeps_cancelled_evidence(self):
        self.check_cancel_signal(signal.SIGTERM, 143)

    def test_interrupt_to_shell_is_forwarded_and_keeps_cancelled_evidence(self):
        self.check_cancel_signal(signal.SIGINT, 130)

    def check_cancel_signal(self, signum, expected):
        self.env.update(FAKE_GRADLE_MODE='hang', ANDROID_TEST_TIMEOUT_SECONDS='30')
        process = subprocess.Popen(['bash', 'scripts/run_android_tests.sh', '30'], cwd=self.root,
                                   env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 4
            while not (self.root / 'gradle-pid').exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue((self.root / 'gradle-pid').exists())
            process.send_signal(signum)
            stdout, stderr = process.communicate(timeout=8)
            self.assertEqual(expected, process.returncode, stdout + stderr)
            self.assertEqual('cancelled', self.execution()['status'])
            self.assert_collected(expected)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()

    def test_diagnostic_failure_does_not_change_timeout_result(self):
        # Preflight works, but the diagnostic proof of a synthetic emulator fails.
        path = self.root / 'bin/adb'
        path.write_text(path.read_text().replace("print(1)", "sys.exit(9)"))
        result = self.run_shell('hang')
        self.assertEqual(124, result.returncode, result.stderr)
        self.assertEqual('synthetic_emulator_not_verified', self.execution()['diagnostics']['reason'])
        self.assert_collected(124)

    def test_collector_process_failures_do_not_replace_test_failure_or_timeout(self):
        for name in ('collect_android_evidence.py', 'collect_performance_evidence.py'):
            (self.root / 'scripts' / name).write_text(
                f"from pathlib import Path\nPath('{name}.called').touch()\nraise SystemExit(99)\n")
        for mode, expected in (('23', 23), ('hang', 124)):
            with self.subTest(mode=mode):
                result = self.run_shell(mode)
                self.assertEqual(expected, result.returncode, result.stderr)
                for name in ('collect_android_evidence.py', 'collect_performance_evidence.py'):
                    marker = self.root / (name + '.called')
                    self.assertTrue(marker.exists())
                    marker.unlink()

    def test_timeout_outside_github_emulator_does_not_collect_device_details(self):
        self.env['GITHUB_ACTIONS'] = 'false'
        result = self.run_shell('hang')
        self.assertEqual(124, result.returncode)
        self.assertEqual('not_github_linux_ci', self.execution()['diagnostics']['reason'])
        calls = (self.root / 'adb-calls.txt').read_text()
        self.assertNotIn('dumpsys', calls)
        self.assertNotIn('logcat', calls)
        self.assert_collected(124)

    def test_diagnostic_output_and_runtime_are_independently_bounded(self):
        data, record = bounded.bounded_capture(
            [sys.executable, '-c', "import sys;sys.stdout.write('x'*1000000)"], 1, 100)
        self.assertEqual(100, len(data))
        self.assertEqual('output_limit', record['status'])
        start = time.monotonic()
        data, record = bounded.bounded_capture(
            [sys.executable, '-c', 'import time;time.sleep(60)'], 0.1, 100)
        self.assertEqual('timed_out', record['status'])
        self.assertLess(time.monotonic() - start, 2)

    def test_preflight_commands_are_bounded_and_failure_does_not_run_gradle(self):
        timeout = self.root / 'bin/timeout'
        timeout.write_text('#!/bin/sh\nprintf "%s\\n" "$*" > preflight-timeout-call\nexit 124\n')
        timeout.chmod(0o755)
        result = self.run_shell('0')
        self.assertEqual(2, result.returncode)
        self.assertFalse((self.root / 'gradle-pid').exists())
        self.assertIn('--signal=TERM --kill-after=2s 10s adb shell getprop',
                      (self.root / 'preflight-timeout-call').read_text())
        self.assert_collected(2)


if __name__ == '__main__':
    unittest.main()
