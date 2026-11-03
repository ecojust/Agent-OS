import copy
import csv
import io
import json
from pathlib import Path
import tempfile
import unittest

import window_infos_reset_trace_analysis as a


def csv_bytes(name, rows):
    output = io.StringIO(); writer = csv.writer(output, lineterminator='\n')
    writer.writerow(a.SCHEMAS[name])
    for row in rows:
        writer.writerow([str(row.get(k, '')).encode().hex() if k in a.TEXT_COLUMNS
                         else '[NULL]' if row.get(k) is None else row[k]
                         for k in a.SCHEMAS[name]])
    return output.getvalue().encode()


def fixture():
    source = {'repository':'ecojust/Agent-OS','commit':'a'*40,'runId':123,'attempt':1}
    context = {'source':source,'inputFiles':{},'arms':{}}
    for arm, ready in [('baseline',100),('enabled',300)]:
        context['arms'][arm] = {'status':'recorded','reason':None,'package':'com.android.inputmethod.latin',
                               'uid':10112,'readyElapsedMs':ready,'freezeElapsedMs':ready+100,'showElapsedMs':ready+20}
    def sl(ident,name,ts,dur,parent=None,depth=0):
        return dict(id=ident,name=name,ts=ts*1000000,dur=dur*1000000,parent_id=parent,depth=depth,
                    track_id=10,utid=20,upid=30,tid=999,pid=999,is_main_thread=1)
    tables = {
        'trace-slices.csv':[sl(1,'clientTransactionExecuted',325,30),
                            sl(2,a.TARGET_NAMES[0],330,10,1,1),sl(3,a.TARGET_NAMES[1],331,2,2,2)],
        'trace-clocks.csv':[dict(snapshot_id=0,ts=50000000,clock_id=6,clock_name='BOOTTIME',clock_value=50000000,machine_id=0)],
        'trace-processes.csv':[dict(upid=30,pid=999,name='com.android.inputmethod.latin',uid=10112,
                                    start_ts=None,end_ts=None,cmdline='com.android.inputmethod.latin',machine_id=0)],
        'trace-stats.csv':[dict(name='traced_buf_bytes_written',idx=0,severity='info',source='trace',value=10000)],
        'trace-bounds.csv':[dict(start_ts=0,end_ts=500000000)],
        'trace-metadata.csv':[dict(name='trace_time_clock_id',int_value=6,str_value='',machine_id=0),
                              dict(name='all_data_source_started_ns',int_value=50000000,str_value='',machine_id=0),
                              dict(name='tracing_disabled_ns',int_value=450000000,str_value='',machine_id=0)]}
    return tables, context


def derive(tables, context):
    return a.derive({n:csv_bytes(n,r) for n,r in tables.items()},context)


class AncestryTests(unittest.TestCase):
    def setUp(self): self.tables,self.context=fixture()
    def run_analysis(self): return derive(self.tables,self.context)
    def reset(self): return self.run_analysis()['events'][0]

    def test_positive_actual_parent_chain_and_raw_window(self):
        result=self.run_analysis(); reset=result['events'][0]
        self.assertEqual('recorded',result['status'])
        self.assertEqual('configuration_dispatch_supported',reset['callerClassification'])
        self.assertEqual([1],reset['clientTransactionAncestorIds'])
        self.assertEqual('after_raw_window',reset['rawContext'][0]['phase'])
        self.assertEqual('after_show_in_raw_window',reset['rawContext'][1]['phase'])
        self.assertEqual(10000000,reset['rawContext'][1]['showOffsetNs'])
        self.assertIsNone(reset['process']['start_ts'])

    def test_absence_does_not_infer_settings_observer(self):
        self.tables['trace-slices.csv'][1]['parent_id']=None
        reset=self.reset()
        self.assertEqual('unknown',reset['callerClassification'])
        self.assertEqual([],reset['clientTransactionAncestorIds'])

    def test_config_changed_is_not_the_proven_transaction_path(self):
        self.tables['trace-slices.csv'][0]['name']='configChanged'
        reset=self.reset()
        self.assertEqual('unknown',reset['callerClassification'])
        self.assertEqual([1],reset['configChangedAncestorIds'])

    def test_time_overlap_without_parent_link_is_not_ancestry(self):
        self.tables['trace-slices.csv'][1]['parent_id']=None
        self.assertEqual('unknown',self.reset()['callerClassification'])

    def test_missing_cyclic_and_cross_track_ancestors_fail_closed(self):
        for change in ({'parent_id':999},{'parent_id':2}):
            with self.subTest(change=change):
                tables,context=fixture();tables['trace-slices.csv'][1].update(change)
                self.assertEqual('unknown',derive(tables,context)['events'][0]['callerClassification'])
        self.tables['trace-slices.csv'][0]['track_id']=11
        self.assertIn('ANCESTOR_TRACK_MISMATCH',self.reset()['classificationReasons'])

    def test_incomplete_slice_and_nonmain_thread_not_positive(self):
        self.tables['trace-slices.csv'][1]['dur']=-1
        self.assertEqual('unknown',self.reset()['callerClassification'])
        self.tables,self.context=fixture();self.tables['trace-slices.csv'][1]['is_main_thread']=0
        self.assertIn('IME_MAIN_THREAD_NOT_PROVED',self.reset()['classificationReasons'])

    def test_zero_duration_or_missing_depth_never_supports_caller(self):
        self.tables['trace-slices.csv'][1]['dur']=0
        self.assertEqual('unknown',self.reset()['callerClassification'])
        self.tables,self.context=fixture();self.tables['trace-slices.csv'][1]['depth']=2
        self.assertIn('ANCESTOR_INTERVAL_OR_DEPTH_INVALID',self.reset()['classificationReasons'])

    def test_wrong_uid_or_restart_never_claims_unique_process(self):
        self.tables['trace-processes.csv'][0]['uid']=10113
        result=self.run_analysis()
        self.assertEqual('unknown',result['imeIdentity']['status'])
        self.assertEqual(0,result['configurationDispatchSupportedCount'])
        self.tables,self.context=fixture()
        extra=copy.deepcopy(self.tables['trace-processes.csv'][0]);extra['upid']=31;extra['pid']=1000
        self.tables['trace-processes.csv'].append(extra)
        self.assertEqual('unknown',self.reset()['callerClassification'])

    def test_known_process_end_is_enforced(self):
        self.tables['trace-processes.csv'][0]['end_ts']=335000000
        self.assertIn('SLICE_OUTSIDE_KNOWN_PROCESS_LIFETIME',self.reset()['classificationReasons'])

    def test_null_or_mismatched_thread_identity_and_missing_root(self):
        for values in ({'tid':None,'pid':None},{'pid':1000,'tid':1000}):
            with self.subTest(values=values):
                tables,context=fixture();tables['trace-slices.csv'][1].update(values)
                self.assertIn('IME_MAIN_THREAD_NOT_PROVED',derive(tables,context)['events'][0]['classificationReasons'])
        self.tables['trace-slices.csv'][0]['depth']=2
        self.tables['trace-slices.csv'][1]['depth']=3
        self.tables['trace-slices.csv'][2]['depth']=4
        self.assertIn('ANCESTOR_ROOT_DEPTH_MISSING',self.reset()['classificationReasons'])

    def test_clock_missing_preserves_local_ancestry_but_not_raw_time(self):
        self.tables['trace-clocks.csv']=[]
        result=self.run_analysis(); reset=result['events'][0]
        self.assertEqual('unknown',result['status'])
        self.assertEqual('configuration_dispatch_supported',reset['callerClassification'])
        self.assertEqual('unknown',reset['rawContext'][1]['phase'])
        self.assertIsNone(reset['rawContext'][1]['showOffsetNs'])

    def test_inconsistent_clock_mapping_is_unknown(self):
        self.tables['trace-clocks.csv'][0]['clock_value']+=1
        self.assertEqual('unknown',self.run_analysis()['clockMapping']['status'])

    def test_loss_even_info_severity_blocks_classification(self):
        self.tables['trace-stats.csv'].append(dict(name='ftrace_cpu_overrun_end',idx=0,severity='info',source='trace',value=1))
        self.assertIn('TRACE_LOSS_OR_ERROR',self.reset()['classificationReasons'])

    def test_readiness_and_trace_end_required(self):
        self.tables['trace-metadata.csv']=self.tables['trace-metadata.csv'][:1]
        result=self.run_analysis()
        self.assertEqual('unknown',result['status'])
        self.assertEqual('unknown',result['events'][0]['callerClassification'])

    def test_after_frozen_is_not_a_b_arm_event(self):
        self.context['arms']['enabled']['freezeElapsedMs']=325
        self.assertEqual('after_raw_window',self.reset()['rawContext'][1]['phase'])

    def test_empty_slices_is_only_not_observed(self):
        self.tables['trace-slices.csv']=[]
        result=self.run_analysis()
        self.assertEqual('not_observed',result['resetObservation'])
        self.assertFalse(result['resetAbsenceProvesNoCalls'])

    def test_partially_covered_raw_window_makes_analysis_unknown(self):
        self.context['arms']['enabled']['freezeElapsedMs']=700
        result=self.run_analysis()
        self.assertEqual('unknown',result['status'])
        self.assertFalse(result['armCoverage']['enabled']['rawWindowFullyCovered'])

    def test_duplicate_slice_and_corrupt_csv_rejected(self):
        self.tables['trace-slices.csv'].append(self.tables['trace-slices.csv'][1].copy())
        with self.assertRaisesRegex(a.AnalysisError,'SLICE_ID'): self.run_analysis()
        with self.assertRaises(a.AnalysisError): a.parse_csv('trace-bounds.csv',b'wrong\n1\n')
        with self.assertRaises(a.AnalysisError): a.parse_csv('trace-processes.csv',csv_bytes('trace-processes.csv',[])+b'1,2,QQ,3,0,0,00,0\n')

    def test_invalid_bounds_are_unknown_not_zero(self):
        self.tables['trace-bounds.csv'][0]['start_ts']=None
        result=self.run_analysis()
        self.assertEqual('unknown',result['status'])
        self.assertIsNone(result['traceQuality']['bounds'][0]['start_ts'])


class OriginalContextTests(unittest.TestCase):
    def test_missing_arm_originals_are_explicit_unknown(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);_,context=fixture()
            (root/'report.json').write_text(json.dumps({'source':context['source']}))
            result=a.load_context(root)
            self.assertEqual('unknown',result['arms']['baseline']['status'])
            self.assertIsNone(result['arms']['enabled']['showElapsedMs'])

    def test_duplicate_json_nonfinite_symlink_rejected(self):
        for data in (b'{"a":1,"a":2}',b'{"a":NaN}',b'{"a":1e999}'):
            with self.assertRaises(a.AnalysisError): a.decode_json(data)
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp);(p/'file').write_text('{}');(p/'link').symlink_to(p/'file')
            with self.assertRaises(a.AnalysisError):a.read_file(p/'link')

    def test_analysis_recomputed_not_accepted_from_status(self):
        tables,context=fixture();data={n:csv_bytes(n,r) for n,r in tables.items()}
        result=a.make_analysis(data,context,b'not-a-real-trace-test-only')
        self.assertEqual(context['source'],result['source'])
        self.assertEqual(a.identity(b'not-a-real-trace-test-only'),result['trace'])
        self.assertEqual(set(a.QUERY_FILES),set(result['queries']))
        self.assertFalse(result['scope']['applicationCompatibilityAcceptance'])

    def test_archive_replay_rejects_tampered_derived_classification(self):
        tables,context=fixture();data={n:csv_bytes(n,r) for n,r in tables.items()}
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);comparison=root/'comparison';comparison.mkdir();output=root/'analysis';output.mkdir()
            (comparison/'report.json').write_text(json.dumps({'source':context['source']}))
            for arm,c in context['arms'].items():
                folder=comparison/arm;folder.mkdir()
                (folder/'report.json').write_text(json.dumps({'source':context['source'],'ime':{'package':c['package'],'uid':c['uid']}}))
                (folder/'raw-result.json').write_text(json.dumps({'source':context['source'],'imePackage':c['package'],'imeUid':c['uid'],
                    'times':{'readyElapsedMs':c['readyElapsedMs'],'freezeElapsedMs':c['freezeElapsedMs']}}))
                (folder/'fixture.json').write_text(json.dumps({'source':context['source'],'showImeAttempts':1,'showRequestedElapsedMs':c['showElapsedMs']}))
            trace=root/'trace.pftrace';trace.write_bytes(b'synthetic trace identity for replay test')
            for name,value in data.items():(output/name).write_bytes(value)
            result=a.make_analysis(data,a.load_context(comparison),trace.read_bytes())
            (output/'analysis.json').write_text(json.dumps(result))
            self.assertEqual('recorded',a.validate_analysis(output,trace,comparison)['status'])
            result['events'][0]['callerClassification']='settings_observer_proved'
            (output/'analysis.json').write_text(json.dumps(result))
            with self.assertRaisesRegex(a.AnalysisError,'ANALYSIS_DERIVATION_MISMATCH'):
                a.validate_analysis(output,trace,comparison)


if __name__ == '__main__': unittest.main()
