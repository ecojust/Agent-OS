import copy,json,unittest
import analyze_geometry as a


def fixture():
 return {'schemaVersion':1,'guard':'TARGET_FULLY_INSIDE','snapshot':{'schemaVersion':1,'snapshotKind':'LAST_OBSERVATION','operationStage':'SET_TITLE','resourceClass':'TITLE_FIELD','readPhase':'TARGET_CHECK','appWindowId':7,'appBounds':[0,10,100,100],'displayId':0,'displayBounds':[0,0,100,200],'imeWindowId':2,'imeBounds':[0,150,100,200],'matchedCount':1,'visibleMatchCount':1,'matchScanComplete':True,'targetPresent':True,'targetBounds':[-1,20,99,90],'targetVisible':True,'targetEnabled':True,'targetWindowId':7},'lastAction':'CLICK_NEW_NOTE','lastActionResult':'ACCEPTED','actionCounterScope':'CURRENT_HARNESS','actionAttempts':1,'actionAttemptCountCap':64,'actionAttemptCountAtLimit':False,'homeAttempts':0,'diagnosticTruncated':False}

class AnalysisTest(unittest.TestCase):
 def test_exact_geometry_guard_and_edge(self):
  r=a.derive(fixture());self.assertEqual([],r['consistencyIssues']);self.assertEqual(['left'],r['recomputedGeometry']['edgesOutside']);self.assertFalse(r['recomputedGeometry']['fullyInside'])
 def test_empty_target_distinguished_from_outside(self):
  v=fixture();v['snapshot']['targetBounds']=[20,30,20,40];r=a.derive(v);self.assertTrue(r['recomputedGeometry']['targetEmpty']);self.assertEqual([],r['recomputedGeometry']['edgesOutside'])
 def test_empty_app_and_signed_extremes(self):
  v=fixture();v['snapshot']['appBounds']=[2**31-1,0,-2**31,100];r=a.derive(v);self.assertTrue(r['recomputedGeometry']['appEmpty']);self.assertEqual([],r['consistencyIssues'])
 def test_null_bounds_never_filled(self):
  v=fixture();v['snapshot']['targetBounds']=None;r=a.derive(v);self.assertFalse(r['recomputedGeometry']['available']);self.assertTrue(r['consistencyIssues'])
 def test_containment_contradiction_detected(self):
  v=fixture();v['snapshot']['targetBounds']=[1,20,99,90];r=a.derive(v);self.assertTrue(r['recomputedGeometry']['fullyInside']);self.assertTrue(r['consistencyIssues'])
 def test_failure_fallbacks_explicit(self):
  self.assertEqual('truncated',a.derive({'diagnosticTruncated':True,'untruncatedChars':5000})['availability']);self.assertEqual('serialization_failed',a.derive({'diagnosticSerializationFailed':True})['availability'])
 def test_junit_context_and_repeated_prints(self):
  payload=json.dumps(fixture(),separators=(',',':'))
  output='\tat '+a.base.CLASS+'.notesWithAgentDisabled(ApplicationSetCompatibilityInstrumentedTest.java:76)\n\tSuppressed: java.lang.AssertionError: '+a.MARKER+payload+'\nError in notesWithAgentDisabled('+a.base.CLASS+'):\n\tSuppressed: java.lang.AssertionError: '+a.MARKER+payload+'\n'
  r=a.extract(output);self.assertEqual(2,len(r));self.assertEqual(1,len({x['rawJsonSha256']for x in r}));self.assertTrue(all(x['method']=='notesWithAgentDisabled'for x in r));self.assertEqual('NOTES_CONFIRMATION_DIAGNOSTIC_V1 ',a.base.MARKER)
 def test_unbound_oversized_and_duplicate_json_rejected(self):
  self.assertEqual('unassigned',a.extract(a.MARKER+json.dumps(fixture()))[0]['bindingStatus'])
  with self.assertRaises(ValueError):a.extract('INSTRUMENTATION_STATUS: test=notesWithAgentDisabled\n'+a.MARKER+'{"k":"'+'a'*4100+'"}\n')
  with self.assertRaises(ValueError):a.extract('INSTRUMENTATION_STATUS: test=notesWithAgentDisabled\n'+a.MARKER+'{"schemaVersion":1,"schemaVersion":1}\n')
 def test_counter_scope_and_unknown_enum_not_accepted(self):
  v=fixture();v['actionAttempts']=64;v['actionAttemptCountAtLimit']=True;r=a.derive(v);self.assertEqual([],r['consistencyIssues']);v['lastAction']='unknown content';self.assertTrue(a.derive(v)['consistencyIssues'])

 def test_terminal_boundary_cannot_reuse_previous_case(self):
  p=json.dumps(fixture())
  output='INSTRUMENTATION_STATUS: class='+a.base.CLASS+'\nINSTRUMENTATION_STATUS: test=notesWithAgentDisabled\nINSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_STATUS: class='+a.base.CLASS+'\nINSTRUMENTATION_STATUS: current=2\n'+a.MARKER+p+'\nINSTRUMENTATION_STATUS_CODE: -2\n'
  r=a.extract(output);self.assertEqual(1,len(r));self.assertIsNone(r[0]['method']);self.assertEqual('unassigned',r[0]['bindingStatus'])
 def test_test_field_after_diagnostic_binds_same_complete_block(self):
  output='INSTRUMENTATION_STATUS: class='+a.base.CLASS+'\nINSTRUMENTATION_STATUS: stack=error\n'+a.MARKER+json.dumps(fixture())+'\nINSTRUMENTATION_STATUS: test=notesWithAgentBoundAndIdle\nINSTRUMENTATION_STATUS_CODE: -2\n'
  self.assertEqual('notesWithAgentBoundAndIdle',a.extract(output)[0]['method'])
 def test_conflicting_frame_and_test_are_unassigned(self):
  output='INSTRUMENTATION_STATUS: class='+a.base.CLASS+'\n\tat '+a.base.CLASS+'.notesWithAgentDisabled(Test.java:1)\n'+a.MARKER+json.dumps(fixture())+'\nINSTRUMENTATION_STATUS: test=notesWithAgentBoundAndIdle\nINSTRUMENTATION_STATUS_CODE: -2\n'
  self.assertEqual('unassigned',a.extract(output)[0]['bindingStatus'])
 def test_fallback_shapes_and_boolean_schema_not_accepted(self):
  self.assertTrue(a.derive({'diagnosticSerializationFailed':True,'snapshot':{}})['consistencyIssues'])
  self.assertTrue(a.derive({'diagnosticTruncated':True,'untruncatedChars':1})['consistencyIssues'])
  self.assertTrue(a.derive({'diagnosticTruncated':True,'untruncatedChars':True})['consistencyIssues'])
  v=fixture();v['schemaVersion']=True;self.assertTrue(a.derive(v)['consistencyIssues'])
  v=fixture();v['snapshot']['schemaVersion']=True;self.assertTrue(a.derive(v)['consistencyIssues'])
 def test_mismatched_class_and_unterminated_block_not_assigned(self):
  for output in ['INSTRUMENTATION_STATUS: class=other.Test\nINSTRUMENTATION_STATUS: test=notesWithAgentDisabled\n'+a.MARKER+json.dumps(fixture())+'\nINSTRUMENTATION_STATUS_CODE: -2\n','INSTRUMENTATION_STATUS: class='+a.base.CLASS+'\nINSTRUMENTATION_STATUS: test=notesWithAgentDisabled\n'+a.MARKER+json.dumps(fixture())+'\n']:
   self.assertEqual('unassigned',a.extract(output)[0]['bindingStatus'])

if __name__=='__main__':unittest.main()
