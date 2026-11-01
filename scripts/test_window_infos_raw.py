import base64
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile
import zlib

import window_infos_raw as w
from test_window_infos_ime_state import synthetic_dump

SOURCE = {'repository': w.REPOSITORY, 'commit': 'a' * 40, 'runId': 123, 'attempt': 1}
IME = {'componentBefore': 'org.example.ime/.Ime', 'componentAfter': 'org.example.ime/.Ime',
       'package': 'org.example.ime', 'uid': 10042, 'uidAfter': 10042, 'displayId': 0}
JAVA = ['tools/window-infos-raw/src/dev/agentos/raw/' + n + '.java' for n in
        ['WindowInfosRawMain', 'RawCollector', 'RawListenerSession', 'RawSession']]
JAVA += ['tools/window-infos-raw/stubs/android/view/InputWindowHandle.java',
         'tools/window-infos-raw/stubs/android/window/WindowInfosListener.java']
IDENTITIES = {n: {'bytes': 1, 'sha256': 'b' * 64} for n in (*w.SOURCE_PATHS, *JAVA)}


def row(index=2, binder=1, config=0, frame=None, region=False):
    frame = [0, 800, 640, 1280] if frame is None else frame
    empty = frame[0] >= frame[2] or frame[1] >= frame[3]
    return {'rowIndex': index, 'binderId': binder, 'inputConfig': config, 'frame': frame,
            'regionEmpty': region, 'notVisible': bool(config & 2), 'isClone': bool(config & 65536),
            'frameEmpty': empty, 'passesFourPredicates': not (config & (2 | 65536)) and not region and not empty,
            'ownerUid': 10042, 'layoutParamsType': 2011, 'displayId': 0}


def sample(kind='CALLBACK', rows=None, tick=3000, sequence=1):
    rows = [row()] if rows is None else rows
    ids = [r['binderId'] for r in rows]
    return {'kind': kind, 'sequence': 0 if kind == 'INITIAL' else sequence, 'elapsedRealtimeMs': tick,
            'rawWindowCount': max(5, max([r['rowIndex'] for r in rows], default=0) + 1),
            'typeCount': len(rows), 'displayTypeCount': len(rows), 'uidTypeDisplayCount': len(rows),
            'nullPackageCount': 0, 'candidateRowCount': len(rows), 'nullTokenCount': ids.count(0),
            'distinctBinderCount': len(set(ids) - {0}), 'complete': True, 'issues': [], 'rows': rows}


def raw_result():
    return {'schemaVersion': 1, 'source': SOURCE, 'imePackage': IME['package'], 'imeUid': 10042, 'displayId': 0,
            'process': {'sdk': 37, 'uid': 2000, 'startedElapsedMs': 1000, 'finishedElapsedMs': 14020,
                        'fingerprintSha256': w.sha(b'synthetic/fingerprint')},
            'limits': dict(w.LIMITS), 'admission': [{'id': n, 'status': 'MATCHED'} for n in w.ADMISSION_IDS],
            'constructionStatus': 'CONSTRUCTED', 'registrationStatus': 'REGISTERED', 'unregistrationStatus': 'RETURNED',
            'resultStatus': 'RECORDED', 'constructAttempts': 1, 'registerAttempts': 1, 'unregisterAttempts': 1,
            'times': {'constructedElapsedMs': 1100, 'registeredElapsedMs': 1500, 'readyElapsedMs': 2000,
                      'observationDeadlineElapsedMs': 14000, 'freezeElapsedMs': 14000, 'unregisteredElapsedMs': 14010},
            'collectionFrozen': True, 'callbacksSeen': 1, 'callbacksStored': 1, 'callbacksDropped': 0,
            'callbacksOutsideWindow': 0, 'binderIdsAssigned': 1, 'outputTruncated': False, 'outputOmittedCallbacks': 0,
            'initial': sample('INITIAL', rows=[], tick=1490), 'samples': [sample()]}


def ready():
    return {'schemaVersion': 1, 'source': SOURCE, 'imePackage': IME['package'], 'imeUid': 10042,
            'displayId': 0, 'registered': True, 'elapsedRealtimeMs': 2000}


def fixture():
    return {'schemaVersion': 1, 'kind': 'agent-os-raw-window-fixture', 'source': SOURCE, 'phase': 'ime_visible',
            'sdkInt': 37, 'uid': 10123, 'displayId': 0, 'startedElapsedMs': 2100, 'showImeAttempts': 1,
            'showImeAccepted': True, 'showRequestedElapsedMs': 2200, 'imeVisibleObserved': True,
            'firstImeVisibleElapsedMs': 3200, 'receiptWrites': 3, 'receiptWrittenElapsedMs': 3201}


def marker_bytes(value=None, ready_value=None):
    return ((w.READY + json.dumps(ready() if ready_value is None else ready_value, separators=(',', ':')) + '\n')
            + w.MARKER + json.dumps(raw_result() if value is None else value, separators=(',', ':')) + '\n').encode()


def png():
    def chunk(kind, body): return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(b'\x00\xff\xff\xff\xff')) + chunk(b'IEND', b'')


def synthetic_build():
    """Small structural dex fixture; never executed or presented as Android evidence."""
    names = ['dev.agentos.raw.' + n for n in ['RawCollector', 'RawListenerSession', 'RawSession', 'WindowInfosRawMain']]
    descriptors = [('L' + name.replace('.', '/') + ';').encode() for name in names]
    count = len(names); strings = 112; types = strings + count * 4; classes = types + count * 4
    data = bytearray(classes + count * 32)
    data[:8] = b'dex\n035\0'
    for offset, value in [(36, 112), (40, 0x12345678), (56, count), (60, strings),
                          (64, count), (68, types), (96, count), (100, classes)]: struct.pack_into('<I', data, offset, value)
    for i, text in enumerate(descriptors):
        struct.pack_into('<I', data, strings + 4 * i, len(data)); data.extend(bytes([len(text)]) + text + b'\0')
        struct.pack_into('<I', data, types + 4 * i, i); struct.pack_into('<I', data, classes + 32 * i, i)
    struct.pack_into('<I', data, 32, len(data)); data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into('<I', data, 8, zlib.adler32(data[12:])); dex = bytes(data)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive: archive.writestr('classes.dex', dex)
    jar = buffer.getvalue()
    source_names = JAVA + ['scripts/build_window_infos_raw.py']
    stubs = sorted('/repo/' + n for n in JAVA if '/stubs/' in n)
    src = sorted('/repo/' + n for n in JAVA if '/src/' in n)
    build = {'schemaVersion': 1, 'kind': 'window-infos-raw-dex-build', 'jarBytes': len(jar), 'jarSha256': w.sha(jar),
             'classesDexBytes': len(dex), 'classesDexSha256': w.sha(dex), 'packagedClasses': names,
             'compileOnlyStubClasses': ['android.view.InputWindowHandle', 'android.window.WindowInfosListener', 'android.window.WindowInfosListener$DisplayInfo'],
             'sourceFiles': [{'path': n, **IDENTITIES[n]} for n in source_names],
             'androidJar': {'api': 35, 'bytes': 1234, 'sha256': 'c' * 64}, 'javacVersion': 'javac 17.0.1',
             'd8Version': 'D8 synthetic', 'commands': [
                 ['/jdk/bin/javac', '--release', '8', '-encoding', 'UTF-8', '-cp', '/sdk/android.jar', '-d', '/tmp/stubs', *stubs],
                 ['/jdk/bin/javac', '--release', '8', '-encoding', 'UTF-8', '-cp', '/sdk/android.jar:/tmp/stubs', '-d', '/tmp/classes', *src],
                 ['/sdk/d8', '--lib', '/sdk/android.jar', '--min-api', '30', '--output', '/tmp/dex', '/tmp/owned-classes.jar']],
             'runtimeHiddenPolicyMutation': False, 'compileOnlyStubsPackaged': False}
    return jar, build


def fake_executor(jar, raw=None, fixture_value=None, mutate=None, ime_result=None):
    properties = dict(zip(w.PROPERTIES, ['1', '1', '37', 'x86_64', '1', 'synthetic/fingerprint']))
    def execute(argv, seconds, limit):
        if argv[-1] == 'devices': data = b'List of devices attached\nemulator-5554\tdevice\n'
        elif 'getprop' in argv: data = (properties.get(argv[-1], '') + '\n').encode()
        elif argv[-1] == 'get-current-user': data = b'0\n'
        elif argv[-2:] == ['id', '-u']: data = b'2000\n'
        elif 'test' in argv: data = b''
        elif argv[-3:] == ['pm', 'list', 'packages']: data = b'package:android\n'
        elif argv[-1] == 'default_input_method': data = b'org.example.ime/.Ime\n'
        elif argv[-3:] == ['ime', 'list', '-s']: data = b'org.example.ime/.Ime\n'
        elif '-U' in argv: data = ('package:' + argv[-1] + ' uid:' + ('10123' if argv[-1] == w.PACKAGE else '10042') + '\n').encode()
        elif 'badging' in argv: data = ("package: name='dev.agentos.compat' versionCode='1'\nsdkVersion:'30'\ntargetSdkVersion:'35'\n").encode()
        elif 'install' in argv: data = b'Performing Streamed Install\nSuccess\n'
        elif 'path' in argv: data = b'package:/data/app/~~abc/fixture-xyz/base.apk\n'
        elif 'push' in argv: data = b'1 file pushed\n'
        elif 'sha256sum' in argv: data = (w.sha(jar) + '  ' + w.remote_jar(SOURCE) + '\n').encode()
        elif 'start' in argv: data = b'Status: ok\nActivity: dev.agentos.compat/.RawWindowFixtureActivity\nComplete\n'
        elif '/system/bin/dumpsys' in argv: data = synthetic_dump()
        elif 'screencap' in argv: data = png()
        elif 'run-as' in argv: data = json.dumps(fixture() if fixture_value is None else fixture_value).encode()
        elif 'force-stop' in argv or 'rm' in argv: data = b''
        else: raise AssertionError(argv)
        if mutate: data = mutate(argv, data)
        if '/system/bin/dumpsys' in argv and ime_result is not None: return data, ime_result
        return data, {'status': 'completed', 'exitCode': 0, 'exceptionType': None}
    def stream(argv, seconds, limit, on_ready, source, ime, progress):
        data = marker_bytes(raw)
        progress((w.READY + json.dumps(ready()) + '\n').encode()); on_ready(ready()); progress(data)
        return data, {'status': 'completed', 'exitCode': 0, 'exceptionType': None}
    return execute, stream


def passed_payloads(raw=None, fixture_value=None, mutate=None, ime_result=None):
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary); jar, build = synthetic_build()
        (root/'fixture.apk').write_bytes(b'synthetic-apk'); (root/'probe.jar').write_bytes(jar)
        (root/'probe.jar.json').write_text(json.dumps(build)); output = root/'output'
        execute, stream = fake_executor(jar, raw, fixture_value, mutate, ime_result)
        with patch.object(w, 'source_files', return_value=IDENTITIES):
            w.prepare(output, SOURCE)
            args = SimpleNamespace(output=output, sdk_root=root/'sdk', fixture_apk=root/'fixture.apk', probe_jar=root/'probe.jar')
            w.run_probe(args, SOURCE, lambda directory, source: w.CommandRunner(directory, source, execute, stream))
            w.finalize(output, SOURCE, 'success')
        return w.read_payloads(output)


class RawContractTest(unittest.TestCase):
    def test_bound_raw_marker_and_single_registration(self):
        ready_value, value = w.parse_markers(marker_bytes(), SOURCE, IME)
        self.assertEqual(2000, ready_value['elapsedRealtimeMs']); self.assertTrue(w.raw_passed(value))
        self.assertEqual(1, w.positive_callbacks(value, fixture()))

    def test_multiple_clone_rows_same_binder_preserved(self):
        value = raw_result(); value['samples'][0] = sample(rows=[row(), row(3, config=65536), row(4, config=2)])
        w.validate_raw(value, SOURCE, IME)
        self.assertEqual(1, value['samples'][0]['distinctBinderCount']); self.assertEqual(1, w.positive_callbacks(value, fixture()))

    def test_distinct_binders_null_token_and_pre_stimulus_are_not_positive(self):
        for rows, tick in [([row(), row(3, binder=2)], 3000), ([row(binder=0)], 3000), ([row()], 2100), ([row(config=2)], 3000)]:
            value = raw_result(); value['binderIdsAssigned'] = 2; value['samples'][0] = sample(rows=rows, tick=tick)
            w.validate_raw(value, SOURCE, IME); self.assertEqual(0, w.positive_callbacks(value, fixture()))

    def test_initial_can_follow_callback_in_time_and_id_assignment(self):
        value = raw_result(); value['initial'] = sample('INITIAL', [row(binder=2)], tick=3500)
        value['binderIdsAssigned'] = 2; w.validate_raw(value, SOURCE, IME)

    def test_predicate_and_counter_tampering_rejected(self):
        for mutate in [lambda v: v['samples'][0]['rows'][0].update(isClone=True),
                       lambda v: v['samples'][0].update(distinctBinderCount=0),
                       lambda v: v.update(callbacksSeen=2), lambda v: v.update(registerAttempts=2),
                       lambda v: v['admission'][0].update(status='ACCESS_DENIED'),
                       lambda v: v['samples'][0]['rows'][0].update(frame=[0,0,0,0])]:
            value = raw_result(); mutate(value)
            with self.assertRaises(ValueError): w.validate_raw(value, SOURCE, IME)

    def test_overflow_cannot_claim_recorded_but_failed_original_valid(self):
        value = raw_result(); value.update(callbacksSeen=2, callbacksDropped=1)
        with self.assertRaises(ValueError): w.validate_raw(value, SOURCE, IME)
        value['resultStatus'] = 'INCOMPLETE'; w.validate_raw(value, SOURCE, IME)

    def test_duplicate_foreign_and_unbound_markers_rejected(self):
        good = marker_bytes()
        wrong = ready(); wrong['source'] = dict(SOURCE, attempt=True)
        for data in [good + good, good.replace(w.READY.encode(), b'log ' + w.READY.encode()), marker_bytes(ready_value=wrong),
                     good.replace(b'"schemaVersion":1', b'"schemaVersion":1,"schemaVersion":1', 1),
                     good.replace(w.MARKER.encode(), b'OTHER '), good.rstrip(b'\n'), good.split(b'\n',1)[1] + good.split(b'\n',1)[0] + b'\n']:
            with self.assertRaises(ValueError): w.parse_markers(data, SOURCE, IME)

    def test_failed_fixture_phases_are_retained(self):
        for phase in ['focus_lost', 'post_rejected']:
            value = fixture(); value.update(phase=phase, showImeAttempts=0, showImeAccepted=None, showRequestedElapsedMs=None,
                                           imeVisibleObserved=False, firstImeVisibleElapsedMs=None, receiptWrites=2)
            w.validate_fixture(value, SOURCE, 10123); self.assertFalse(w.fixture_passed(value))

    def test_no_insets_visibility_is_not_positive_control(self):
        value=fixture(); value.update(phase='show_requested', imeVisibleObserved=False, firstImeVisibleElapsedMs=None, receiptWrites=2)
        w.validate_fixture(value, SOURCE, 10123); self.assertFalse(w.fixture_passed(value))

    def test_real_png_format_check_rejects_crc_truncation_and_suffix(self):
        good=png(); self.assertEqual(1,w.png_identity(good)['width'])
        for bad in [good[:-1],good+b'junk',good[:30]+b'bad'+good[33:]]:
            with self.assertRaises(ValueError):w.png_identity(bad)

    def test_dex_bytes_and_compile_only_claim_are_revalidated(self):
        jar,metadata=synthetic_build();w.validate_build(metadata,jar,IDENTITIES)
        for mutate in [lambda v:v.update(compileOnlyStubsPackaged=True),lambda v:v.update(packagedClasses=['android.view.InputWindowHandle']),
                       lambda v:v['sourceFiles'][0].update(sha256='c'*64),lambda v:v.update(jarSha256='c'*64)]:
            changed=copy.deepcopy(metadata);mutate(changed)
            with self.assertRaises(ValueError):w.validate_build(changed,jar,IDENTITIES)


class EndToEndHostTest(unittest.TestCase):
    def test_successful_driver_originals_replay(self):
        payloads=passed_payloads();self.assertEqual('passed',w.validate_evidence(payloads,SOURCE)['status'])
        self.assertEqual(1,payloads['report.json']['positiveCallbackCount']);self.assertIn('screenshot.png',payloads)

    def test_raw_failure_is_preserved_without_pass_promotion(self):
        raw=raw_result();raw['samples'][0]=sample(rows=[row(config=2)])
        payloads=passed_payloads(raw=raw);self.assertEqual('failed',payloads['report.json']['status'])
        self.assertEqual(raw,payloads['raw-result.json']);w.validate_evidence(payloads,SOURCE)

    def test_changed_ime_and_missing_fixture_visible_fail(self):
        seen=0
        def mutate(argv,data):
            nonlocal seen
            if argv[-1]=='default_input_method':
                seen+=1
                if seen==2:return b'org.example.other/.Ime\n'
            return data
        payloads=passed_payloads(mutate=mutate);self.assertEqual('IME_CONFIGURATION_CHANGED',payloads['report.json']['failureReason'])
        self.assertEqual('failed',payloads['report.json']['status'])

    def test_pass_cannot_be_reconstructed_from_mutated_report_or_bytes(self):
        original=passed_payloads()
        mutations=[lambda p:p['report.json'].update(positiveCallbackCount=2),lambda p:p['capture.json'].update(secureWindowFlagsChanged=True),
                   lambda p:p['raw-result.json']['samples'][0]['rows'][0].update(notVisible=True),
                   lambda p:p.update({'screenshot.png':png()+b'junk'}),
                   lambda p:p['commands.json']['commands'][-1]['argv'].__setitem__(-1,'/data/local/tmp/other.jar')]
        for mutate in mutations:
            p=copy.deepcopy(original);mutate(p)
            with self.assertRaises(ValueError):w.validate_evidence(p,SOURCE)

    def test_command_overlap_cannot_be_fabricated(self):
        p=passed_payloads();commands=p['commands.json']['commands'];lookup={c['id']:c for c in commands}
        lookup['screenshot']['startedAt']=lookup['raw_probe']['startedAt']
        with self.assertRaises(ValueError):w.validate_evidence(p,SOURCE)

    def test_prepared_receipt_and_failed_finalize_remain_unverified(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(w,'source_files',return_value=IDENTITIES):
            w.prepare(directory,SOURCE);w.finalize(directory,SOURCE,'failure')
            self.assertEqual('unverified',w.validate_evidence(w.read_payloads(directory),SOURCE)['status'])

    def test_nondefault_vm_options_stop_before_install_and_registration(self):
        def mutate(argv,data):return b'-Xhidden-api-policy:disabled\n' if argv[-1]=='dalvik.vm.extra-opts' else data
        p=passed_payloads(mutate=mutate);self.assertEqual('failed',p['report.json']['status'])
        self.assertFalse(p['report.json']['rawAttempted']);self.assertNotIn('install_fixture',[c['id'] for c in p['commands.json']['commands']])

    def test_one_ime_observation_after_raw_before_screenshot(self):
        p = passed_payloads(); report = p['report.json']
        self.assertEqual(2, report['schemaVersion']); self.assertEqual('recorded', report['imeStateStatus'])
        commands = p['commands.json']['commands']; ids = [c['id'] for c in commands]
        self.assertEqual(30, len(ids)); self.assertEqual(1, ids.count('ime_state'))
        self.assertLess(ids.index('raw_probe'), ids.index('ime_state'))
        self.assertLess(ids.index('ime_state'), ids.index('screenshot'))
        observed = commands[ids.index('ime_state')]
        self.assertEqual((12, 262144), (observed['timeoutSeconds'], observed['byteLimit']))
        self.assertEqual(w.ime_state_command(report['tools']['adb'], report['device']['serial']), observed['argv'])
        self.assertEqual('AFTER_RAW_EXIT_BEFORE_SCREENSHOT_NON_ATOMIC', p['ime-state.json']['scope'])

    def test_ime_dump_failed_outcomes_retain_prefix_and_finish_postchecks(self):
        for status, code in [('timed_out', -9), ('output_limit', -9), ('cancelled', -9), ('completed', 1)]:
            for raw_failure in (False, True):
                raw = raw_result()
                if raw_failure: raw['samples'][0] = sample(rows=[row(region=True)])
                p = passed_payloads(raw=raw, ime_result={'status': status, 'exitCode': code, 'exceptionType': None})
                self.assertEqual('RAW_POSITIVE_CALLBACK_NOT_OBSERVED' if raw_failure else 'IME_STATE_NOT_RECORDED',
                                 p['report.json']['failureReason'])
                self.assertEqual(0 if raw_failure else 1, p['report.json']['positiveCallbackCount'])
                self.assertEqual('unavailable', p['ime-state.json']['status'])
                self.assertIsNone(p['ime-state.json']['analysis'])
                commands = {c['id']: c for c in p['commands.json']['commands']}
                self.assertEqual(synthetic_dump(), w.command_data(commands['ime_state']))
                self.assertEqual('captured', p['capture.json']['status'])
                self.assertTrue(all(w.command_ok(commands[key]) for key in w.LEGACY_POST_IDS))
                w.validate_evidence(p, SOURCE)

    def test_ime_dump_unavailable_does_not_replace_raw_failure(self):
        raw = raw_result(); raw['samples'][0] = sample(rows=[row(region=True)])
        for bad in (b'*** SERVICE DUMP TIMEOUT EXPIRED ***\n', b'\xff', b'partial\n'):
            p = passed_payloads(raw=raw, mutate=lambda argv,data: bad if '/system/bin/dumpsys' in argv else data)
            self.assertEqual('RAW_POSITIVE_CALLBACK_NOT_OBSERVED', p['report.json']['failureReason'])
            self.assertEqual('unavailable', p['report.json']['imeStateStatus'])
            self.assertEqual(0, p['report.json']['positiveCallbackCount'])
            self.assertEqual('captured', p['capture.json']['status']); w.validate_evidence(p, SOURCE)

    def test_unexpected_parser_exception_remains_separate(self):
        raw = raw_result(); raw['samples'][0] = sample(rows=[row(region=True)])
        with patch.object(w.ime_state, 'analyze', side_effect=RuntimeError('synthetic parser fault')):
            p = passed_payloads(raw=raw)
            self.assertEqual('RAW_POSITIVE_CALLBACK_NOT_OBSERVED', p['report.json']['failureReason'])
            self.assertEqual('PARSER_EXCEPTION', p['ime-state.json']['reason'])
            self.assertEqual('captured', p['capture.json']['status']); w.validate_evidence(p, SOURCE)

    def test_late_fixture_visibility_cannot_extend_raw_window(self):
        value = fixture(); value.update(firstImeVisibleElapsedMs=15000, receiptWrittenElapsedMs=15001)
        p = passed_payloads(fixture_value=value)
        self.assertEqual('failed', p['report.json']['status'])
        self.assertEqual('FIXTURE_STIMULUS_OUTSIDE_RAW_WINDOW', p['report.json']['failureReason'])
        self.assertEqual(1, p['report.json']['positiveCallbackCount']); w.validate_evidence(p, SOURCE)

    def test_ime_derived_original_and_timing_tampering_rejected(self):
        original = passed_payloads()
        def command(p): return next(c for c in p['commands.json']['commands'] if c['id'] == 'ime_state')
        mutations = [lambda p: p['ime-state.json']['expected'].update(uid=10043),
                     lambda p: p['ime-state.json'].update(reason='INVENTED'),
                     lambda p: p['report.json'].update(imeStateStatus='not_attempted'),
                     lambda p: command(p)['argv'].__setitem__(7, '80s'),
                     lambda p: command(p).update(startedAt=p['commands.json']['commands'][0]['startedAt']),
                     lambda p: p.pop('ime-state.json')]
        for mutate in mutations:
            p = copy.deepcopy(original); mutate(p)
            with self.assertRaises(ValueError): w.validate_evidence(p, SOURCE)

    def test_ime_observation_cannot_follow_abnormal_raw_exit(self):
        p = passed_payloads(); p['report.json'].update(status='failed', failureReason='RAW_PROCESS_NOT_NORMAL')
        next(c for c in p['commands.json']['commands'] if c['id'] == 'raw_probe')['exitCode'] = 1
        with self.assertRaises(ValueError): w.validate_evidence(p, SOURCE)


class StreamingProcessTest(unittest.TestCase):
    def test_once_ready_stimulus_and_partial_reads(self):
        data=marker_bytes();seen=[]
        command=[sys.executable,'-c','import sys;sys.stdout.buffer.write('+repr(data)+');sys.stdout.flush()']
        output,result=w.stream_capture(command,2,w.MAX_OUTPUT,lambda value:seen.append(value),SOURCE,IME)
        self.assertEqual('completed',result['status']);self.assertEqual(data,output);self.assertEqual(1,len(seen))

    def test_deadline_after_eof_still_kills_and_preserves_prefix(self):
        command=[sys.executable,'-c',"import os,time;os.write(1,b'prefix');os.close(1);os.close(2);time.sleep(30)"]
        start=time.monotonic();output,result=w.stream_capture(command,.2,4096,lambda _:None,SOURCE,IME)
        self.assertEqual(b'prefix',output);self.assertEqual('timed_out',result['status']);self.assertLess(time.monotonic()-start,2)

    def test_duplicate_ready_kills_without_second_stimulus(self):
        data=(w.READY+json.dumps(ready())+'\n').encode()*2;seen=[]
        command=[sys.executable,'-c','import sys;sys.stdout.buffer.write('+repr(data)+');sys.stdout.flush()']
        output,result=w.stream_capture(command,2,4096,lambda value:seen.append(value),SOURCE,IME)
        self.assertEqual('exception',result['status']);self.assertEqual(1,len(seen));self.assertEqual(data,output)

    def test_output_limit_and_read_exception_keep_original_prefix(self):
        command=[sys.executable,'-c',"import sys;sys.stdout.write('x'*10000);sys.stdout.flush()"]
        output,result=w.stream_capture(command,2,128,lambda _:None,SOURCE,IME)
        self.assertEqual('output_limit',result['status']);self.assertEqual(b'x'*128,output)
        calls=0
        def reader(fd,size):
            nonlocal calls
            calls+=1
            if calls==1:return b'first'
            raise OSError('synthetic')
        output,result=w.stream_capture([sys.executable,'-c','import time;print(1,flush=True);time.sleep(5)'],2,4096,lambda _:None,SOURCE,IME,reader=reader)
        self.assertEqual(b'first',output);self.assertEqual('exception',result['status'])


if __name__=='__main__':unittest.main()
