"""Complete synthetic A→B runs; no device execution or device evidence."""
import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import window_infos_keyboard as k
import window_infos_raw as w
import test_window_infos_raw as raw_tests
from test_window_infos_ime_state import synthetic_dump as ime_dump
from test_window_infos_input_state import synthetic_dump as input_dump

SOURCE = copy.deepcopy(raw_tests.SOURCE)
IDENTITIES = dict(raw_tests.IDENTITIES, **{name: {'bytes': 1, 'sha256': 'd' * 64} for name in k.EXTRA_SOURCES})


class SyntheticDriver:
    def __init__(self, root, *, original=None, enabled_positive=True, input_unknown=False,
                 enable_exit=0, restore_exit=0, restore_wrong=False, uninstall_exit=0,
                 baseline_incomplete=False, preflight_bad=False, empty_before=False,
                 empty_restore=False, setting_component='org.example.ime/.Ime'):
        self.root = Path(root); self.setting = original; self.original = original
        self.enabled_positive, self.input_unknown = enabled_positive, input_unknown
        self.enable_exit, self.restore_exit, self.restore_wrong = enable_exit, restore_exit, restore_wrong
        self.uninstall_exit, self.baseline_incomplete, self.preflight_bad = uninstall_exit, baseline_incomplete, preflight_bad
        self.empty_before, self.empty_restore, self.setting_component = empty_before, empty_restore, setting_component
        self.calls = []; self.installed = False; self.arm_calls = []
        self.jar, self.build = raw_tests.synthetic_build()
        (self.root/'fixture.apk').write_bytes(b'synthetic-apk')
        (self.root/'probe.jar').write_bytes(self.jar)
        (self.root/'probe.jar.json').write_text(json.dumps(self.build))
        avd_home = self.root/'avd'; config_dir = avd_home/'keyboard.avd'; config_dir.mkdir(parents=True)
        (config_dir/'config.ini').write_text('hw.lcd.width=320\nhw.keyboard=no\n')
        self.output = self.root/'output'
        self.args = SimpleNamespace(output=self.output, sdk_root=self.root/'sdk', fixture_apk=self.root/'fixture.apk',
                                    probe_jar=self.root/'probe.jar', avd_home=avd_home, avd_name='keyboard')

    def list_settings(self):
        text = 'default_input_method=' + self.setting_component + '\n'
        if self.setting is not None: text += k.SETTING + '=' + self.setting + '\n'
        return text.encode()

    def execute(self, argv, seconds, limit):
        self.calls.append(list(argv)); exit_code = 0
        if argv[-1] == 'devices': data = b'List of devices attached\nemulator-5554\tdevice\n'
        elif argv[-1] == 'ro.build.version.sdk': data = b'36\n' if self.preflight_bad else b'37\n'
        elif argv[-1] == 'ro.kernel.qemu': data = b'1\n'
        elif argv[-1] == 'get-current-user': data = b'0\n'
        elif argv[-2:] == ['id','-u']: data = b'2000\n'
        elif argv[-1] == 'input' and '/system/bin/dumpsys' in argv:
            data = b'incomplete synthetic input dump\n' if self.input_unknown else input_dump()
        elif argv[-2:] == ['list','secure']:
            journal = json.loads((self.output/'journal.json').read_text())
            data = (b'' if (self.empty_before and not journal['mutationAttempted'])
                    or (self.empty_restore and journal['restoreAttempted']) else self.list_settings())
        elif 'uninstall' in argv:
            assert argv[-1] == w.PACKAGE and self.installed
            self.installed = False; exit_code = self.uninstall_exit; data = b'Success\n'
        elif argv[-5:] == ['pm','list','packages','--user','0']:
            data = b'package:android\n' + (('package:' + w.PACKAGE + '\n').encode() if self.installed else b'')
        elif argv[-4:] == ['put','secure',k.SETTING,'1']:
            journal = json.loads((self.output/'journal.json').read_text())
            assert journal['mutationAttempted'] and journal['original'] == {'present': self.original is not None, 'value': self.original}
            self.setting = '1'; exit_code = self.enable_exit; data = b''
        elif argv[-4:] == ['put','secure',k.SETTING,'0'] or argv[-3:] == ['delete','secure',k.SETTING]:
            journal = json.loads((self.output/'journal.json').read_text())
            assert journal['mutationAttempted'] and journal['restoreAttempted']
            if not self.restore_wrong: self.setting = self.original
            exit_code = self.restore_exit; data = b'Deleted 1 rows\n' if self.original is None else b''
        else: raise AssertionError(argv)
        return data, {'status':'completed','exitCode':exit_code,'exceptionType':None}

    def top_factory(self, directory, source): return k.CommandRunner(directory, source, self.execute)

    def arm_factory(self, directory, source):
        arm = Path(directory).name; self.arm_calls.append(arm); enabled = arm == 'enabled'; offset = 20000 if enabled else 0
        raw = raw_tests.raw_result(); positive = enabled and self.enabled_positive
        raw['samples'][0] = raw_tests.sample(rows=[raw_tests.row(region=not positive)])
        raw['process']['startedElapsedMs'] += offset; raw['process']['finishedElapsedMs'] += offset
        for name in raw['times']: raw['times'][name] += offset
        raw['initial']['elapsedRealtimeMs'] += offset
        for sample in raw['samples']: sample['elapsedRealtimeMs'] += offset
        ready = raw_tests.ready(); ready['elapsedRealtimeMs'] += offset
        fixture = raw_tests.fixture(); fixture['uid'] += int(enabled)
        for name in ('startedElapsedMs','showRequestedElapsedMs','firstImeVisibleElapsedMs','receiptWrittenElapsedMs'): fixture[name] += offset
        def mutate(argv, data):
            if 'install' in argv:
                assert not self.installed; self.installed = True
            if argv[-1] == w.PACKAGE and '-U' in argv: data = ('package:' + w.PACKAGE + ' uid:' + str(fixture['uid']) + '\n').encode()
            if '/system/bin/dumpsys' in argv:
                data = ime_dump(keyboard='qwerty/v/v', settings_cache=2 if enabled else 1,
                                input_view_started=True, input_view_shown=enabled)
            if self.baseline_incomplete and not enabled and 'screencap' in argv: data = b'not PNG'
            return data
        execute, _ = raw_tests.fake_executor(self.jar, raw=raw, fixture_value=fixture, mutate=mutate)
        def stream(argv, seconds, limit, on_ready, source, ime, progress):
            data = raw_tests.marker_bytes(raw, ready)
            progress((w.READY + json.dumps(ready) + '\n').encode()); on_ready(ready); progress(data)
            return data, {'status':'completed','exitCode':0,'exceptionType':None}
        return w.CommandRunner(directory, source, execute, stream)


def passed_payloads(**options):
    """Reusable complete mapping for archive tests; patch SOURCE/IDENTITIES for real source files."""
    with tempfile.TemporaryDirectory() as temporary:
        raw_ids = {name: value for name, value in IDENTITIES.items() if name not in k.EXTRA_SOURCES}
        with patch.object(k, 'source_files', return_value=IDENTITIES), patch.object(w, 'source_files', return_value=raw_ids), \
             patch.object(raw_tests, 'SOURCE', SOURCE), patch.object(raw_tests, 'IDENTITIES', raw_ids):
            driver = SyntheticDriver(temporary, **options)
            k.prepare(driver.output, SOURCE)
            result = k.run_probe(driver.args, SOURCE, driver.top_factory, driver.arm_factory)
            k.finalize(driver.output, SOURCE, 'success' if result else 'failure', driver.top_factory)
            return k.tree_read(driver.output)


class KeyboardExperimentTest(unittest.TestCase):
    def harness(self, **options):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        raw_ids = {name: value for name, value in IDENTITIES.items() if name not in k.EXTRA_SOURCES}
        for obj, attr, value in ((k,'source_files',IDENTITIES),(w,'source_files',raw_ids)):
            manager = patch.object(obj, attr, return_value=value); manager.start(); self.addCleanup(manager.stop)
        for attr, value in (('SOURCE',SOURCE),('IDENTITIES',raw_ids)):
            manager = patch.object(raw_tests, attr, value); manager.start(); self.addCleanup(manager.stop)
        driver = SyntheticDriver(temporary.name, **options); k.prepare(driver.output, SOURCE)
        return driver

    def run_driver(self, driver):
        passed = k.run_probe(driver.args, SOURCE, driver.top_factory, driver.arm_factory)
        return passed, k.tree_read(driver.output)

    def test_prepared_tree_has_nine_original_files_and_no_setting_intent(self):
        driver = self.harness(); payloads = k.tree_read(driver.output)
        self.assertEqual(9, len(payloads)); report = k.validate_evidence(payloads, SOURCE)
        self.assertEqual('unverified', report['status']); self.assertFalse(payloads['journal.json']['mutationAttempted'])
        self.assertEqual([], driver.calls)

    def test_complete_baseline_failure_enabled_pass_and_absence_restored(self):
        value = passed_payloads(); report = k.validate_evidence(value, SOURCE)
        self.assertEqual('passed', report['status'])
        self.assertEqual('failed', report['arms']['baseline']['status'])
        self.assertEqual('RAW_POSITIVE_CALLBACK_NOT_OBSERVED', report['arms']['baseline']['failureReason'])
        self.assertTrue(report['arms']['baseline']['comparable'])
        self.assertEqual('passed', report['arms']['enabled']['status'])
        self.assertEqual({'status':'confirmed','mutationAttempted':True,'restoreAttempted':True}, report['restoration'])
        self.assertEqual({'present':False,'value':None}, value['journal.json']['original'])
        self.assertEqual(0, report['arms']['baseline']['positiveCallbackCount'])
        self.assertEqual(1, report['arms']['enabled']['positiveCallbackCount'])
        self.assertNotEqual(value['baseline/fixture.json']['uid'], value['enabled/fixture.json']['uid'])
        self.assertEqual(value['baseline/probe.jar'], value['enabled/probe.jar'])
        self.assertEqual(b'synthetic-apk', value['fixture.apk'])

    def test_original_zero_restored_by_put_zero_not_delete(self):
        driver = self.harness(original='0'); passed, value = self.run_driver(driver)
        self.assertTrue(passed); self.assertEqual('0', driver.setting)
        commands = {c['id']:c for c in value['commands.json']['commands']}
        self.assertEqual(['put','secure',k.SETTING,'0'], commands['restore_setting']['argv'][-4:])

    def test_existing_one_or_other_value_never_intervened(self):
        for original in ('1','null','2','false',''):
            with self.subTest(original=original):
                value = passed_payloads(original=original)
                self.assertEqual('KEYBOARD_ORIGINAL_SETTING_NOT_ELIGIBLE', value['report.json']['failureReason'])
                self.assertFalse(value['journal.json']['mutationAttempted'])
                self.assertFalse(value['journal.json']['restoreAttempted'])
                self.assertEqual([], value['baseline/commands.json']['commands'])

    def test_invalid_settings_list_does_not_turn_missing_into_false(self):
        self.assertEqual({'present':False,'value':None}, k.parse_setting(b'default_input_method=org.example.ime/.Ime\nunrelated=value\n'))
        for data in (b'',b'error=failed\n',b'show_ime_with_hard_keyboard=0\n',b'default_input_method=null\n',
                     b'show_ime_with_hard_keyboard=0\nshow_ime_with_hard_keyboard=0\n',
                     b' show_ime_with_hard_keyboard=0\n',b'show_ime_with_hard_keyboard=0',
                     b'Permission Denial\n',b'\xff\n',b'x=1\nx=2\n'):
            with self.subTest(data=data):
                with self.assertRaises(ValueError): k.parse_setting(data)

    def test_empty_exit_zero_list_cannot_enable_or_confirm_restore(self):
        before = passed_payloads(empty_before=True)
        self.assertFalse(before['journal.json']['mutationAttempted'])
        self.assertEqual([],before['baseline/commands.json']['commands'])
        restored = passed_payloads(empty_restore=True)
        self.assertEqual('failed_or_unobserved',restored['report.json']['restoration']['status'])
        self.assertEqual('failed',restored['report.json']['status'])

    def test_original_setting_component_must_match_baseline_before_uninstall(self):
        value = passed_payloads(setting_component='other.ime/.Ime')
        self.assertEqual('SETTING_CONTEXT_CHANGED',value['report.json']['failureReason'])
        self.assertFalse(value['journal.json']['mutationAttempted'])
        self.assertNotIn('uninstall_fixture',[c['id'] for c in value['commands.json']['commands']])

    def test_input_unknown_preserved_but_both_arms_and_restore_still_run(self):
        value = passed_payloads(input_unknown=True)
        self.assertEqual('KEYBOARD_INPUT_DEVICES_NOT_RECORDED', value['report.json']['failureReason'])
        self.assertEqual('unavailable', value['report.json']['inputDevicesStatus'])
        self.assertEqual('passed', value['report.json']['arms']['enabled']['status'])
        self.assertEqual('confirmed', value['report.json']['restoration']['status'])

    def test_preflight_rejects_before_input_or_settings(self):
        driver = self.harness(preflight_bad=True); passed, value = self.run_driver(driver)
        self.assertFalse(passed); self.assertEqual(['devices','api'], [c['id'] for c in value['commands.json']['commands']])
        self.assertEqual([], driver.arm_calls); self.assertIsNone(value['journal.json']['original'])

    def test_incomplete_baseline_never_uninstalls_or_changes_setting(self):
        driver = self.harness(baseline_incomplete=True); passed, value = self.run_driver(driver)
        self.assertFalse(passed); self.assertEqual(['baseline'], driver.arm_calls)
        self.assertEqual('KEYBOARD_BASELINE_NOT_COMPARABLE', value['report.json']['failureReason'])
        self.assertFalse(value['journal.json']['mutationAttempted'])
        self.assertNotIn('uninstall_fixture', [c['id'] for c in value['commands.json']['commands']])

    def test_uninstall_failure_never_writes_setting(self):
        value = passed_payloads(uninstall_exit=1)
        self.assertFalse(value['journal.json']['mutationAttempted'])
        self.assertEqual('not_needed', value['report.json']['restoration']['status'])
        self.assertEqual([], value['enabled/commands.json']['commands'])

    def test_failed_enable_write_still_restored_without_running_enabled_arm(self):
        driver = self.harness(enable_exit=1); passed, value = self.run_driver(driver)
        self.assertFalse(passed); self.assertIsNone(driver.setting)
        self.assertTrue(value['journal.json']['mutationAttempted']); self.assertTrue(value['journal.json']['restoreAttempted'])
        self.assertEqual('confirmed', value['report.json']['restoration']['status'])
        self.assertEqual(['baseline'], driver.arm_calls)

    def test_enabled_raw_failure_is_preserved_after_successful_restore(self):
        value = passed_payloads(enabled_positive=False)
        self.assertEqual('KEYBOARD_ENABLED_ARM_NOT_PASSED', value['report.json']['failureReason'])
        self.assertEqual('RAW_POSITIVE_CALLBACK_NOT_OBSERVED', value['enabled/report.json']['failureReason'])
        self.assertEqual('confirmed', value['report.json']['restoration']['status'])

    def test_restore_nonzero_cannot_claim_confirmed_even_with_matching_readback(self):
        value = passed_payloads(restore_exit=1)
        self.assertEqual('failed', value['report.json']['status'])
        self.assertEqual('failed_or_unobserved', value['report.json']['restoration']['status'])
        self.assertEqual('KEYBOARD_RESTORE_NOT_CONFIRMED', value['report.json']['failureReason'])

    def test_restore_wrong_readback_is_not_original_metadata_as_proof(self):
        value = passed_payloads(restore_wrong=True)
        self.assertEqual('failed_or_unobserved', value['report.json']['restoration']['status'])
        bad = copy.deepcopy(value); bad['report.json']['restoration']['status'] = 'confirmed'
        with self.assertRaisesRegex(ValueError,'KEYBOARD_RESTORATION_DERIVED'): k.validate_evidence(bad, SOURCE)

    def test_restore_and_finalize_are_idempotent_even_after_failed_restore(self):
        driver = self.harness(restore_exit=1); _, value = self.run_driver(driver)
        count = len(driver.calls)
        k.restore(driver.output, SOURCE, driver.top_factory)
        k.finalize(driver.output, SOURCE, 'failure', driver.top_factory)
        k.finalize(driver.output, SOURCE, 'failure', driver.top_factory)
        self.assertEqual(count,len(driver.calls))
        names = [c['id'] for c in k.tree_read(driver.output)['commands.json']['commands']]
        self.assertEqual(1,names.count('restore_setting')); self.assertEqual(1,names.count('enable_setting'))

    def test_interruption_after_durable_mutation_intent_gets_one_workflow_restore(self):
        driver = self.harness(); _, value = self.run_driver(driver)
        value['commands.json']['commands'] = [c for c in value['commands.json']['commands']
                                             if k.COMMAND_IDS.index(c['id']) <= k.COMMAND_IDS.index('packages_after_uninstall')]
        journal = value['journal.json']; journal['restoreAttempted'] = False; journal['restoreAttemptedAt'] = None
        report = value['report.json']; report.update(status='unverified',stage='enabling',failureReason=None,
                                                    completedAt=None,enabledAttempted=False,emulatorStepOutcome=None)
        for entry in (driver.output/'enabled').iterdir(): entry.unlink()
        w.prepare(driver.output/'enabled',SOURCE)
        for name in ('commands.json','journal.json','report.json'): w.write_json(driver.output/name,value[name])
        driver.setting = '1'  # A lost command result cannot prove the setting stayed unchanged.
        k.sync_report(driver.output); calls = len(driver.calls)
        restoration = k.restore(driver.output,SOURCE,driver.top_factory)
        self.assertEqual('confirmed',restoration['status']); self.assertIsNone(driver.setting)
        self.assertEqual(calls+2,len(driver.calls))
        result = k.finalize(driver.output,SOURCE,'cancelled',driver.top_factory)
        self.assertEqual('failed',result['status']); self.assertEqual('KEYBOARD_RUN_INTERRUPTED',result['failureReason'])
        self.assertEqual(calls+2,len(driver.calls))
        self.assertEqual([],k.tree_read(driver.output)['enabled/commands.json']['commands'])

    def test_interruption_after_restore_intent_never_retries_missing_write(self):
        driver = self.harness(); _, value = self.run_driver(driver)
        value['commands.json']['commands'] = [c for c in value['commands.json']['commands']
                                             if c['id'] not in {'restore_setting','settings_restored'}]
        value['report.json'].update(status='failed',failureReason='KEYBOARD_RUN_INTERRUPTED')
        for name in ('commands.json','report.json'): w.write_json(driver.output/name,value[name])
        driver.setting = '1'; k.sync_report(driver.output); calls = len(driver.calls)
        restored = k.restore(driver.output,SOURCE,driver.top_factory)
        self.assertEqual('failed_or_unobserved',restored['status'])
        k.finalize(driver.output,SOURCE,'cancelled',driver.top_factory)
        self.assertEqual(calls,len(driver.calls)); self.assertEqual('1',driver.setting)

    def test_second_run_is_rejected_without_extra_commands(self):
        driver = self.harness(); self.run_driver(driver); count = len(driver.calls)
        with self.assertRaises(ValueError): k.run_probe(driver.args, SOURCE, driver.top_factory, driver.arm_factory)
        self.assertEqual(count,len(driver.calls))

    def test_command_bytes_argv_and_intent_tampering_rejected(self):
        value = passed_payloads()
        for change in ('argv','bytes','intent','source','apk','child-source'):
            bad = copy.deepcopy(value)
            if change == 'argv': bad['commands.json']['commands'][9]['argv'][-1] = '0'
            elif change == 'bytes': bad['commands.json']['commands'][6]['outputBase64'] = ''
            elif change == 'intent': bad['journal.json']['mutationAttempted'] = False
            elif change == 'source': bad['journal.json']['source']['attempt'] += 1
            elif change == 'apk': bad['fixture.apk'] += b'changed'
            elif change == 'child-source': bad['baseline/report.json']['sourceFiles']['scripts/window_infos_raw.py']['sha256'] = 'e'*64
            with self.subTest(change=change):
                with self.assertRaises(ValueError): k.validate_evidence(bad, SOURCE)

    def test_arm_copy_does_not_satisfy_later_elapsed_order(self):
        value = passed_payloads(); bad = copy.deepcopy(value)
        for name in w.FILES:
            if 'baseline/'+name in value: bad['enabled/'+name] = copy.deepcopy(value['baseline/'+name])
        bad['report.json']['arms']['enabled'] = k.arm_summary(k.arm_payloads(bad,'enabled'))
        with self.assertRaises(ValueError): k.validate_evidence(bad,SOURCE)

    def test_avd_snapshot_rejects_duplicate_keyboard_entry(self):
        driver = self.harness()
        config = driver.args.avd_home/'keyboard.avd/config.ini'; config.write_text('hw.keyboard=no\nhw.keyboard=no\n')
        passed, value = self.run_driver(driver)
        self.assertFalse(passed); self.assertEqual('KEYBOARD_AVD_BASELINE',value['report.json']['failureReason'])
        self.assertEqual([],driver.calls)

    def test_tree_rejects_symlinks_unknown_paths_and_oversized_apk(self):
        driver = self.harness(); (driver.output/'unknown').write_bytes(b'x')
        with self.assertRaises(ValueError): k.tree_read(driver.output)
        (driver.output/'unknown').unlink(); (driver.output/'fixture.apk').symlink_to(driver.root/'fixture.apk')
        with self.assertRaises(ValueError): k.tree_read(driver.output)
        (driver.output/'fixture.apk').unlink(); (driver.output/'fixture.apk').write_bytes(b'x'*(k.MAX_APK+1))
        with self.assertRaises(ValueError): k.tree_read(driver.output)


if __name__ == '__main__': unittest.main()
