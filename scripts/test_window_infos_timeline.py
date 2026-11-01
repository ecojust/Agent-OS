"""Synthetic immutable Git archives for the independent offline analyzer."""
import base64
import copy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import window_infos_timeline as t
import test_window_infos_keyboard as producer_fixture
from test_window_infos_ime_state import synthetic_dump as ime_dump


def entry(sequence, elapsed, *, duration=94, status='STATUS_CANCEL', reason='RESET_NEW_CONFIGURATION',
          origin='ORIGIN_IME', tag='org.example.ime:a51e97cc', request='Window{synthetic-not-an-identity}'):
    tail=' lastProgressTime=2026-11-01 12:22:56.019' if status=='STATUS_TIMEOUT' else ''
    return (f'      #{sequence} TYPE_SHOW - {status} - {tag} ({duration}ms):\n'
            f'        startTime=2026-11-01 12:23:06.325 (timestamp={elapsed}) {origin}\n'
            f'        reason={reason} PHASE_SERVER_POST_LAYOUT_NOTIFY_CONTROLS_CHANGED{tail}\n'
            '        userId=0 displayId=0\n'
            f'        requestWindowName={request}\n')


def history_dump(completed=None, active=None):
    completed=[entry(12,22300,duration=394,status='STATUS_SUCCESS',reason='IME_REQUESTED_CHANGED_LISTENER',origin='ORIGIN_SERVER'),
               entry(7,15000,duration=10006,status='STATUS_TIMEOUT',reason='CONTROLS_CHANGED',origin='ORIGIN_SERVER'),
               entry(13,30157),entry(14,30272,duration=2,reason='CONTROLS_CHANGED',origin='ORIGIN_SERVER')] if completed is None else completed
    active=[] if active is None else active
    block=('  mImeTrackerService#History:\n'+f'    mActiveEntries: {len(active)} elements\n'+''.join(active)
           +f'    mCompletedEntries: {len(completed)} elements\n'+''.join(completed))
    return ime_dump().replace(b'  mImeTrackerService#History:\n',block.encode())


def git(repo,*args):
    return subprocess.run(['git','-C',str(repo),*args],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout.decode().strip()


def save(path,value):path.write_text(json.dumps(value,indent=2)+'\n')
def encoded(command,data):command.update(**t.identity(data),outputBase64=base64.b64encode(data).decode())


class TimelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.home=tempfile.TemporaryDirectory();cls.addClassCleanup(cls.home.cleanup)
        cls.base=Path(cls.home.name)/'repo';cls.base.mkdir()
        git(cls.base,'init','-q');git(cls.base,'config','user.name','Synthetic test');git(cls.base,'config','user.email','synthetic@example.invalid')
        identities={}
        for path in producer_fixture.IDENTITIES:
            target=cls.base/path;target.parent.mkdir(parents=True,exist_ok=True);blob=('synthetic source '+path+'\n').encode()
            target.write_bytes(blob);identities[path]=t.identity(blob)
        target=cls.base/t.PROGRAM_RELATIVE;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(t.PROGRAM.read_bytes())
        git(cls.base,'add','.');git(cls.base,'commit','-qm','synthetic source')
        cls.source_commit=git(cls.base,'rev-parse','HEAD')
        source={'repository':'ecojust/Agent-OS','commit':cls.source_commit,'runId':123,'attempt':1}
        with patch.object(producer_fixture,'SOURCE',source),patch.object(producer_fixture,'IDENTITIES',identities):
            payloads=producer_fixture.passed_payloads()
        for arm in ('baseline','enabled'):
            cm=payloads[arm+'/commands.json']['commands'];by_id={c['id']:c for c in cm}
            dump=history_dump(completed=[]) if arm=='baseline' else history_dump()
            encoded(by_id['ime_state'],dump)
            if arm=='enabled':
                raw=payloads[arm+'/raw-result.json'];negative=copy.deepcopy(raw['samples'][0]);positive=copy.deepcopy(negative)
                negative.update(sequence=1,elapsedRealtimeMs=22777);negative['rows'][0].update(regionEmpty=True,passesFourPredicates=False)
                positive.update(sequence=2,elapsedRealtimeMs=30335)
                raw.update(callbacksSeen=2,callbacksStored=2,samples=[negative,positive])
                wire=base64.b64decode(by_id['raw_probe']['outputBase64']).splitlines()
                final=b'WINDOW_INFOS_RAW_RESULT_V1 '+json.dumps(raw,separators=(',',':')).encode()
                encoded(by_id['raw_probe'],wire[0]+b'\n'+final+b'\n')
                receipt=payloads[arm+'/fixture.json'];receipt.update(firstImeVisibleElapsedMs=22407,receiptWrittenElapsedMs=22408)
                encoded(by_id['fixture_receipt'],json.dumps(receipt).encode())
        cls.relative='screenshots/window-infos-keyboard-123-1';archive=cls.base/cls.relative;archive.mkdir(parents=True)
        for name,value in payloads.items():
            p=archive/name;p.parent.mkdir(parents=True,exist_ok=True)
            p.write_bytes((json.dumps(value,indent=2)+'\n').encode() if name.endswith('.json') else value)
        repo_id={'id':22,'full_name':'ecojust/Agent-OS'}
        save(archive/'source-run.json',{'id':123,'run_attempt':1,'head_sha':cls.source_commit,'path':'.github/workflows/window-infos-raw.yml',
                                      'event':'workflow_dispatch','head_branch':'master','repository':repo_id,'head_repository':repo_id,
                                      'status':'completed','conclusion':'success'})
        save(archive/'source-jobs.json',[{'total_count':1,'jobs':[{'id':77,'name':'keyboard','run_id':123,'run_attempt':1,'head_sha':cls.source_commit,
                                                              'status':'completed','conclusion':'success'}]}])
        save(archive/'source-artifacts.json',[{'total_count':1,'artifacts':[{'id':88,'name':'window-infos-keyboard-api-37.0-attempt-1',
                                                                         'workflow_run':{'id':123,'head_sha':cls.source_commit,'head_branch':'master',
                                                                                         'repository_id':22,'head_repository_id':22}}]}])
        manifest={'schemaVersion':1,'kind':'window-infos-keyboard-archive','source':source,'sourceConclusion':'success',
                  'keyboardJob':{'id':77,'conclusion':'success'},'artifact':{'id':88},'files':[]}
        save(archive/'manifest.json',manifest);cls.refresh_manifest(archive)
        git(cls.base,'add','.');git(cls.base,'commit','-qm','synthetic immutable archive')
        cls.archive_commit=git(cls.base,'rev-parse','HEAD')

    @staticmethod
    def refresh_manifest(archive):
        path=archive/'manifest.json';value=json.loads(path.read_text())
        value['files']=[{'path':p.relative_to(archive).as_posix(),**t.identity(p.read_bytes())}
                        for p in sorted(archive.rglob('*')) if p.is_file() and p.name!='manifest.json']
        save(path,value)

    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup)
        self.repo=Path(temp.name)/'repo';shutil.copytree(self.base,self.repo)
        self.archive=self.repo/self.relative;self.revision=self.archive_commit

    def analyze(self):return t.analyze(self.archive,self.repo,self.revision,self.source_commit)

    def mutate_json(self,name,change):
        p=self.archive/name;value=json.loads(p.read_text());change(value);save(p,value)
        self.refresh_manifest(self.archive);git(self.repo,'add','.');git(self.repo,'commit','--amend','-qm','synthetic altered evidence')
        self.revision=git(self.repo,'rev-parse','HEAD')

    def test_complete_originals_produce_late_positive_offsets_without_acceptance_claim(self):
        value=self.analyze();self.assertEqual('recorded',value['status'],value['reason'])
        self.assertTrue(value['analysisProgramSourceVerified']);self.assertEqual(self.source_commit,value['analysisSourceCommit'])
        a=value['arms']['baseline']['raw'];b=value['arms']['enabled']['raw']
        self.assertEqual(0,a['positiveCallbackCountRecomputed']);self.assertIsNone(a['firstPositive'])
        self.assertEqual(8135,b['showToFirstPositiveMs']);self.assertEqual(207,b['showToInsetsVisibleMs'])
        self.assertEqual(7558,b['previousCallbackGapMs']);self.assertFalse(b['fiveSecondFromThisFixtureShow']['firstPositiveWithinReference'])
        events={e['sequence']:e for e in value['arms']['enabled']['imeTrackerHistory']['events']}
        self.assertEqual(178,events[13]['offsets']['firstPositiveMinusEventStartMs'])
        self.assertEqual(63,events[14]['offsets']['firstPositiveMinusEventStartMs'])
        self.assertEqual(84,events[13]['offsets']['firstPositiveMinusTrackingFinishMs'])
        self.assertFalse(value['scope']['causalInference']);self.assertFalse(value['scope']['originalNotesAcceptance'])

    def test_history_preserves_completion_order_and_separate_start_order(self):
        value=t.parse_history(history_dump())
        self.assertEqual('recorded',value['status'])
        self.assertEqual([12,7,13,14],[e['sequence'] for e in value['events']])
        self.assertEqual([1,2,3,4],[e['printOrdinal'] for e in value['events']])
        self.assertEqual([7,12,13,14],value['elapsedStartOrderSequenceNumbers'])
        self.assertEqual(['STATUS_SUCCESS','STATUS_TIMEOUT','STATUS_CANCEL','STATUS_CANCEL'],[e['status'] for e in value['events']])
        self.assertNotIn('synthetic-not-an-identity',json.dumps(value))
        self.assertEqual('org.example.ime:a51e97cc',value['events'][0]['tagPrinted'])

    def test_complete_empty_history_is_distinct_from_unknown(self):
        value=t.parse_history(history_dump(completed=[]))
        self.assertEqual('recorded',value['status']);self.assertEqual([],value['events'])
        value=t.parse_history(b'')
        self.assertEqual('unknown',value['status']);self.assertIsNone(value['events'])

    def test_missing_duplicate_truncated_or_foreign_history_is_unknown(self):
        data=history_dump()
        cases=[data.replace(b'mCompletedEntries: 4',b'mCompletedEntries: 5'),
               data.replace(b'#14 TYPE_SHOW',b'#13 TYPE_SHOW'),
               data.replace(b'  UserId=0\n',b''),
               data.replace(b'  mBackCallbackRegistered=false\n',b''),
               data.replace(b'  mImeTrackerService#History:\n',b'  mImeTrackerService#History:\n  mImeTrackerService#History:\n'),
               data.replace(b'    mCompletedEntries: 4 elements\n',b'    mCompletedEntries: 4 elements\nother process:\n')]
        for item in cases:
            with self.subTest(item=item[:80]):
                value=t.parse_history(item);self.assertEqual('unknown',value['status']);self.assertIsNone(value['events'])

    def test_unrecognized_status_and_invalid_duration_or_walltime_are_unknown(self):
        for status in ('STATUS_RUN','STATUS_FUTURE'):
            value=t.parse_history(history_dump(completed=[entry(1,20,status=status)]))
            self.assertEqual('unknown',value['status'])
        for data in (history_dump(completed=[entry(1,20,duration=-1)]),
                     history_dump().replace(b'2026-11-01 12:23:06.325',b'2026-99-01 12:23:06.325')):
            self.assertEqual('unknown',t.parse_history(data)['status'])

    def test_active_pending_start_preserves_unknown_clock_and_completed_fail(self):
        active=entry(1,0,status='STATUS_RUN',duration=0,origin='ORIGIN_NOT_SET',tag='not set').replace('TYPE_SHOW','TYPE_NOT_SET')
        value=t.parse_history(history_dump(active=[active],completed=[entry(2,20,status='STATUS_FAIL')]))
        self.assertEqual('recorded',value['status']);self.assertEqual('unknown',value['events'][0]['clockStatus'])
        self.assertIsNone(value['events'][0]['offsets']);self.assertIsNone(value['events'][0]['trackingFinishedElapsedMsFromDuration'])
        self.assertEqual([2],value['elapsedStartOrderSequenceNumbers']);self.assertFalse(value['startOrderContainsAllEntries'])
        # An active entry may have finished before its starting data arrived.
        self.assertEqual('recorded',t.parse_history(history_dump(active=[entry(1,0,status='STATUS_CANCEL',tag='not set')],completed=[]))['status'])

    def test_dump_errors_utf8_control_and_limits_never_become_empty_history(self):
        data=history_dump()
        for bad in (b'\xff\n',data[:-1],b'x'*t.MAX_DUMP,data+b'Timeout waiting for dump\n',
                    data+b'Permission Denial: test\n',data+b'\x00\n',
                    data.replace(b'mCompletedEntries: 4',b'mCompletedEntries: 201')):
            value=t.parse_history(bad);self.assertEqual('unknown',value['status']);self.assertIsNone(value['events'])

    def test_timed_out_original_command_keeps_history_unknown_and_raw_available(self):
        def change(value):
            item=next(c for c in value['commands'] if c['id']=='ime_state');item.update(status='timed_out',exitCode=None)
        self.mutate_json('enabled/commands.json',change);value=self.analyze()
        self.assertEqual('unknown',value['status']);self.assertEqual('recorded',value['arms']['enabled']['raw']['status'])
        history=value['arms']['enabled']['imeTrackerHistory'];self.assertEqual('IME_DUMP_COMMAND_NOT_COMPLETE',history['reason']);self.assertIsNone(history['events'])

    def test_report_final_labels_do_not_supply_numbers(self):
        self.mutate_json('enabled/report.json',lambda v:v.update(status='failed',positiveCallbackCount=999,failureReason='SYNTHETIC_WRONG_LABEL'))
        value=self.analyze();self.assertEqual('recorded',value['status'],value['reason'])
        self.assertEqual(1,value['arms']['enabled']['raw']['positiveCallbackCountRecomputed'])

    def test_manifest_bytes_and_archive_git_bytes_are_separate_gates(self):
        p=self.archive/'enabled/raw-result.json';p.write_bytes(p.read_bytes()+b'\n')
        self.assertEqual('MANIFEST_BYTES',self.analyze()['reason'])
        self.refresh_manifest(self.archive)
        self.assertEqual('ARCHIVE_GIT_BYTES',self.analyze()['reason'])

    def test_analysis_program_commit_must_match_executing_file(self):
        p=self.repo/t.PROGRAM_RELATIVE;p.write_text('# different analysis program\n')
        git(self.repo,'add','.');git(self.repo,'commit','-qm','different program');revision=git(self.repo,'rev-parse','HEAD')
        value=t.analyze(self.archive,self.repo,self.revision,revision)
        self.assertEqual('ANALYSIS_PROGRAM_SOURCE_MISMATCH',value['reason']);self.assertFalse(value['analysisProgramSourceVerified']);self.assertIsNone(value['arms'])

    def test_source_run_binding_cannot_be_relabeled(self):
        original=json.loads((self.archive/'source-run.json').read_text())
        cases=[(lambda v:v.update(run_attempt=2),'SOURCE_RUN_BINDING'),
               (lambda v:v.update(status='in_progress'),'SOURCE_RUN_BINDING'),
               (lambda v:v['head_repository'].update(id=999),'SOURCE_REPOSITORY_BINDING'),
               (lambda v:v.update(conclusion='failure'),'SOURCE_RUN_CONCLUSION')]
        for change,reason in cases:
            def mutate(value):value.clear();value.update(copy.deepcopy(original));change(value)
            self.mutate_json('source-run.json',mutate)
            value=self.analyze();self.assertEqual(reason,value['reason']);self.assertIsNone(value['arms'])

    def test_source_file_summary_must_match_original_commit(self):
        self.mutate_json('report.json',lambda v:v['sourceFiles']['scripts/window_infos_raw.py'].update(sha256='f'*64))
        self.assertEqual('SOURCE_FILE_BYTES',self.analyze()['reason'])

    def test_command_base64_bytes_and_raw_marker_source_are_checked(self):
        def change(value):next(c for c in value['commands'] if c['id']=='raw_probe')['sha256']='0'*64
        self.mutate_json('enabled/commands.json',change)
        self.assertEqual('COMMAND_BYTES',self.analyze()['reason'])

    def test_unknown_paths_or_symlinks_are_rejected_before_parsing(self):
        extra=self.archive/'unexpected.txt';extra.write_text('x')
        self.assertEqual('ARCHIVE_FILESET',self.analyze()['reason']);extra.unlink()
        source=self.archive/'enabled/raw-result.json';original=source.read_bytes();source.unlink();target=self.repo/'raw.json';target.write_bytes(original);source.symlink_to(target)
        self.assertEqual('ARCHIVE_FILESET',self.analyze()['reason'])

    def test_nonmonotonic_callback_elapsed_makes_raw_unknown_without_negative_gap(self):
        path=self.archive/'enabled/raw-result.json';raw=json.loads(path.read_text())
        raw['samples'][0]['elapsedRealtimeMs']=33000;save(path,raw)
        def change(value):
            command=next(c for c in value['commands'] if c['id']=='raw_probe')
            ready=base64.b64decode(command['outputBase64']).splitlines()[0]
            encoded(command,ready+b'\nWINDOW_INFOS_RAW_RESULT_V1 '+json.dumps(raw,separators=(',',':')).encode()+b'\n')
        self.mutate_json('enabled/commands.json',change)
        value=self.analyze();raw=value['arms']['enabled']['raw']
        self.assertEqual('RAW_CALLBACK_ELAPSED_ORDER',raw['reason']);self.assertIsNone(raw['callbacks'])
        self.assertIsNone(raw['firstPositive']);self.assertNotIn('previousCallbackGapMs',raw)
        self.assertTrue(all(e['offsets'] is None for e in value['arms']['enabled']['imeTrackerHistory']['events']))

    def test_malformed_json_object_boundaries_return_unknown_not_attribute_error(self):
        manifest_path=self.archive/'manifest.json';original=manifest_path.read_bytes();save(manifest_path,[])
        git(self.repo,'add','.');git(self.repo,'commit','--amend','-qm','malformed manifest');self.revision=git(self.repo,'rev-parse','HEAD')
        value=self.analyze();self.assertEqual('ARCHIVE_SCHEMA',value['reason']);self.assertIsNone(value['arms'])
        manifest_path.write_bytes(original)
        self.mutate_json('source-jobs.json',lambda v:v[0]['jobs'].append('invalid object'))
        value=self.analyze();self.assertEqual('ORIGIN_ITEM_SHAPE',value['reason']);self.assertIsNone(value['arms'])

    def test_archive_unique_parent_must_be_evidence_source(self):
        (self.repo/'unrelated.txt').write_text('synthetic unrelated commit\n')
        git(self.repo,'add','.');git(self.repo,'commit','-qm','not an isolated evidence commit');self.revision=git(self.repo,'rev-parse','HEAD')
        self.assertEqual('ARCHIVE_PARENT_NOT_SOURCE',self.analyze()['reason'])

    def test_nonfinite_and_deep_json_are_bounded(self):
        for data in (b'{"value":1e999}',b'{"value":NaN}',b'['*65+b'0'+b']'*65):
            with self.subTest(data=data[:30]):
                with self.assertRaises(ValueError):t.decode(data)

    def test_cli_writes_separate_result_and_refuses_original_directory(self):
        out=self.repo.parent/'analysis.json'
        args=['timeline','--archive',str(self.archive),'--repo',str(self.repo),'--archive-commit',self.revision,
              '--analysis-source-commit',self.source_commit,'--output',str(out)]
        with patch.object(sys,'argv',args):self.assertEqual(0,t.main())
        self.assertEqual('recorded',json.loads(out.read_text())['status'])
        with patch.object(sys,'argv',args):
            with self.assertRaisesRegex(ValueError,'OUTPUT_ALREADY_EXISTS'):t.main()
        args[-1]=str(self.archive/'new-analysis.json')
        with patch.object(sys,'argv',args):
            with self.assertRaisesRegex(ValueError,'OUTPUT_INSIDE_ORIGINAL_ARCHIVE'):t.main()


if __name__=='__main__':unittest.main()
