import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import analyze_ime_association as a


def block(payload, method='notesWithAgentDisabled', terminal=True, class_name=a.base.CLASS):
    return ('INSTRUMENTATION_STATUS: class=' + class_name + '\n' +
            'INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: original\n' +
            '\tSuppressed: java.lang.AssertionError: ' + a.MARKER + payload + '\n' +
            'INSTRUMENTATION_STATUS: test=' + method + '\n' +
            ('INSTRUMENTATION_STATUS_CODE: -2\n' if terminal else ''))


class BindingTest(unittest.TestCase):
    def test_marker_before_method_binds_complete_block(self):
        found = a.extract(block('{}'))
        self.assertEqual('bound', found[0]['bindingStatus'])
        self.assertEqual('notesWithAgentDisabled', found[0]['method'])
        self.assertEqual('parsed', found[0]['parseStatus'])

    def test_no_context_borrowing_after_status_boundary(self):
        output = block('{}') + ('INSTRUMENTATION_STATUS: class=' + a.base.CLASS + '\n' +
            a.MARKER + '{}\nINSTRUMENTATION_STATUS_CODE: -2\n')
        found = a.extract(output)
        self.assertEqual(['bound', 'unassigned'], [x['bindingStatus'] for x in found])
        self.assertIsNone(found[1]['method'])
        self.assertEqual('{}', found[1]['rawJson'])

    def test_unclosed_conflicting_unknown_class_and_unknown_method(self):
        for output in [block('{}', terminal=False), block('{}', class_name='other.Test'),
                       block('{}', method='unknownMethod'),
                       block('{}').replace('stack=java', 'stack=java\n\tat ' + a.base.CLASS +
                           '.notesWithAgentBoundAndIdle(Test.java:1)\njava')]:
            with self.subTest(output=output):
                found = a.extract(output)[0]
                self.assertEqual('unassigned', found['bindingStatus'])
                self.assertTrue(found['bindingIssues'])

    def test_duplicate_prints_kept_and_dedup_only_inside_case(self):
        output = block('{}') + 'Error in notesWithAgentDisabled(' + a.base.CLASS + '):\n' + a.MARKER + '{}\n'
        found = a.extract(output)
        self.assertEqual(2, len(found))
        self.assertEqual(1, len(a.unique_for_case(found)))
        both = a.extract(block('{}') + block('{}', method='notesWithAgentBoundAndIdle'))
        by_method = {}
        for occurrence in both:
            by_method.setdefault(occurrence['method'], []).append(occurrence)
        self.assertEqual(2, sum(len(a.unique_for_case(v)) for v in by_method.values()))

    def test_strict_binding_parity_with_reviewed_geometry_analyzer(self):
        fixtures = [block('{}'), block('{}', terminal=False), block('{}', class_name='other.Test'),
                    a.MARKER + '{}\n', block('{}') + block('{}', method='notesWithAgentBoundAndIdle')]
        for output in fixtures:
            own = a.extract(output)
            prior = a.geometry.extract_marker(output, a.MARKER, a.MAX_CHARS)
            self.assertEqual([{k: x[k] for k in ['method', 'bindingStatus', 'bindingScope', 'bindingIssues', 'line', 'characterOffset', 'rawJsonSha256']} for x in prior],
                             [{k: x[k] for k in ['method', 'bindingStatus', 'bindingScope', 'bindingIssues', 'line', 'characterOffset', 'rawJsonSha256']} for x in own])

    def test_invalid_payloads_retained_without_erasing_other_cases(self):
        for payload in ['{"schemaVersion":1,"schemaVersion":1}', '[1]', '{', '{}suffix', '{} ' + a.MARKER + '{}', '{"secret":"任意"}']:
            with self.subTest(payload=payload):
                found = a.extract(block(payload) + block('{}', method='notesWithAgentBoundAndIdle'))
                self.assertEqual(2, len(found))
                self.assertEqual('invalid', found[0]['parseStatus'])
                self.assertEqual(payload, found[0]['rawJson'])
                self.assertIsNone(found[0]['value'])
                self.assertEqual('parsed', found[1]['parseStatus'])

    def test_oversized_prefix_is_bounded_hash_covers_full_raw(self):
        payload = '{"k":"' + 'x' * a.MAX_CHARS + '"}'
        found = a.extract(block(payload))[0]
        self.assertFalse(found['rawJsonRetainedComplete'])
        self.assertEqual(a.MAX_CHARS - len(a.MARKER), len(found['rawJson']))
        self.assertEqual(a.base.digest(payload.encode()), found['rawJsonSha256'])
        self.assertEqual(len(payload), found['rawJsonChars'])
        self.assertEqual('invalid', found['parseStatus'])

    def test_raw_whitespace_and_hash_preserved_exactly(self):
        payload = '{ "n": 1 }  '
        found = a.extract(block(payload))[0]
        self.assertEqual(payload, found['rawJson'])
        self.assertEqual(a.base.digest(payload.encode()), found['rawJsonSha256'])

    def test_semantically_invalid_original_confirmation_is_retained_as_invalid(self):
        found = a.safe_confirmation_analysis({}, {})
        self.assertEqual('invalid_schema', found['availability'])
        self.assertTrue(found['consistencyIssues'])


def fixture():
    token = 'android.os.BinderProxy@a1b2'
    frame = [0, 100, 320, 640]
    region = 'SkRegion((0,100,320,640))'
    row = {'token': token, 'displayId': 0, 'frame': frame, 'touchableRegion': region, 'isClone': False,
           'transform': 'null', 'scaleFactor': 1.0, 'alpha': 1.0}
    selected = {'status': 'SELECTED', 'reason': 'UNIQUE_CONFIGURED_IME_TEXT_CANDIDATE',
        'referenceCount': 1, 'inputMethodRecordCount': 1, 'selectedWindowStateToken': 'aabbcc',
        'ownerUid': 10012, 'displayId': 0, 'ownerMatches': True, 'displayMatches': True,
        'packageMatches': True, 'mClient': token,
        'fields': {k: {'count': 1, 'value': 'TRUE'} for k in a.base.FIELDS}}
    pop = {'status': 'RECOGNIZED', 'reason': 'FILTERED_LISTS_PARSED', 'notificationsEnabled': True,
        'visible': {'declaredCount': 3, 'parsedCount': 3, 'tokenFieldCount': 3, 'complete': True, 'matchCount': 1, 'matches': [copy.deepcopy(row)]},
        'displays': {'declaredDisplayCount': 1, 'parsedDisplayCount': 1, 'complete': True, 'matchCount': 1, 'matches': [copy.deepcopy(row)]}}
    final = {'status': 'RECOGNIZED', 'reason': 'REPORTED_WINDOW_PAIRS_PARSED',
        'a11yWindowCount': 3, 'windowInfoCount': 3, 'pairCount': 3, 'matchCount': 1,
        'matches': [{'token': token, 'displayId': 0, 'type': 2011, 'layer': 9, 'accessibilityWindowId': 12,
                     'region': region, 'bounds': frame, 'parent': None}]}
    return {'schemaVersion': 1, 'phase': 'POST_FAILURE_BEFORE_CAPTURE_CLEANUP',
        'clock': 'SystemClock.elapsedRealtime', 'startedElapsedMillis': 10000, 'endedElapsedMillis': 10300,
        'perCommandTimeoutMillis': 3000, 'totalTimeoutMillis': 9000, 'maxOutputBytesPerCommand': 262144,
        'configuredIme': {'ownerUid': 10012, 'packageName': 'com.android.inputmethod.latin',
            'component': 'com.android.inputmethod.latin/.LatinIME', 'identitySource': 'FROZEN_CONFIRMATION'},
        'commands': [{'ordinal': index+1, 'id': command[0], 'command': command[1],
            'startedElapsedMillis': 10000+index*100, 'endedElapsedMillis': 10100+index*100,
            'status': 'COMPLETE', 'exitStatus': 'NOT_EXPOSED', 'outputBytes': 500, 'outputSha256': 'a'*64,
            'hashScope': 'FULL_OUTPUT', 'outputComplete': True, 'parsed': parsed}
            for index, (command, parsed) in enumerate(zip(a.COMMANDS, [selected, pop, final]))],
        'association': {'status': 'TEXT_CANDIDATE_ONLY', 'reason': 'ORDERED_TEXT_COUNTS_ONLY', 'systemServerLifetime': 'NOT_VERIFIED',
            'atomicSnapshot': False, 'key': 'WMS_MCLIENT_TO_WINDOW_TOKEN', 'tokenCandidate': token,
            'populatorVisibleMatchCount': 1, 'populatorDisplayMatchCount': 1, 'finalMatchCount': 1},
        'diagnosticTruncated': False}


def summary_unavailable(value):
    value['association'].update(status='INCONCLUSIVE', reason='SOURCE_UNAVAILABLE', populatorVisibleMatchCount=None,
        populatorDisplayMatchCount=None, finalMatchCount=None)


class SchemaTest(unittest.TestCase):
    def test_complete_sequential_text_candidate(self):
        result = a.derive(fixture())
        self.assertEqual([], result['consistencyIssues'])
        self.assertEqual('complete', result['availability'])
        self.assertEqual(300, result['collectorDurationMillis'])
        self.assertEqual('unique_text_candidate', result['postFailureObservations']['finalService']['candidate'])
        self.assertFalse(result['association']['atomicSnapshot'])
        self.assertEqual('NOT_VERIFIED', result['association']['systemServerLifetime'])

    def test_observed_absence_is_separate_from_unavailable_legacy(self):
        value = fixture()
        value['commands'][2]['parsed'].update(matchCount=0, matches=[])
        value['association']['finalMatchCount'] = 0
        result = a.derive(value)
        self.assertEqual([], result['consistencyIssues'])
        self.assertEqual('absent_from_this_post_failure_list', result['postFailureObservations']['finalService']['candidate'])
        value['commands'][2]['parsed'] = {'status': 'UNAVAILABLE', 'reason': 'ACCESSIBILITY_PAIRS_INCOMPLETE_OR_LEGACY',
            'a11yWindowCount': 0, 'windowInfoCount': 0, 'pairCount': 0, 'matchCount': None, 'matches': []}
        summary_unavailable(value)
        result = a.derive(value)
        self.assertEqual([], result['consistencyIssues'])
        self.assertEqual({'availability': 'unavailable', 'matchCount': None}, result['postFailureObservations']['finalService'])
        self.assertEqual(1, result['postFailureObservations']['populatorVisible']['matchCount'])
        self.assertEqual(1, result['postFailureObservations']['populatorDisplays']['matchCount'])

    def test_unavailable_must_not_have_observed_zero(self):
        value = fixture()
        value['commands'][2]['parsed'].update(status='UNAVAILABLE', reason='ACCESSIBILITY_PAIRS_INCOMPLETE_OR_LEGACY', matchCount=0, matches=[])
        summary_unavailable(value)
        result = a.derive(value)
        self.assertTrue(result['consistencyIssues'])
        self.assertEqual('invalid_schema', result['postFailureObservations']['finalService']['availability'])
        value = fixture()
        value['commands'][1]['parsed']['visible'].update(complete=False, matchCount=0, matches=[])
        result = a.derive(value)
        self.assertTrue(result['consistencyIssues'])
        self.assertEqual('invalid_schema', result['postFailureObservations']['populatorVisible']['availability'])

    def test_repeated_populator_presentations_are_not_duplicate_objects(self):
        result = a.derive(fixture())
        self.assertEqual('TEXT_CANDIDATE_ONLY', result['association']['status'])
        value = fixture()
        pop = value['commands'][1]['parsed']['visible']
        pop['matches'] *= 2
        pop['matchCount'] = 2
        value['association'].update(status='INCONCLUSIVE', reason='CANDIDATE_NOT_UNIQUE', populatorVisibleMatchCount=2)
        result = a.derive(value)
        self.assertEqual([], result['consistencyIssues'])
        self.assertEqual('ambiguous_text_candidates', result['postFailureObservations']['populatorVisible']['candidate'])

    def test_timeout_then_skips_cannot_create_observed_absence(self):
        value = fixture()
        summary_unavailable(value)
        value['association']['tokenCandidate'] = None
        for index, command in enumerate(value['commands']):
            command.update(status='TIMEOUT' if index == 0 else 'SKIPPED_AFTER_TIMEOUT', outputComplete=False,
                outputSha256=None, hashScope='UNAVAILABLE', outputBytes=12 if index == 0 else 0,
                parsed={'status': 'UNAVAILABLE', 'reason': 'SOURCE_UNAVAILABLE'})
            if index:
                command.update(startedElapsedMillis=None, endedElapsedMillis=None)
        result = a.derive(value)
        self.assertEqual([], result['consistencyIssues'])
        self.assertEqual('unavailable', result['postFailureObservations']['populatorVisible']['availability'])
        value['commands'][1] = fixture()['commands'][1]
        self.assertTrue(a.derive(value)['consistencyIssues'])

    def test_service_timeout_complete_output_still_unavailable(self):
        value = fixture()
        value['commands'][2].update(status='SERVICE_TIMEOUT', parsed={'status': 'UNAVAILABLE', 'reason': 'SOURCE_UNAVAILABLE'})
        summary_unavailable(value)
        result = a.derive(value)
        self.assertEqual([], result['consistencyIssues'])
        self.assertEqual('unavailable', result['postFailureObservations']['finalService']['availability'])

    def test_byte_limit_prefix_not_complete_output(self):
        value = fixture()
        value['commands'][2].update(status='BYTE_LIMIT', outputBytes=262144, outputComplete=False, hashScope='RETAINED_PREFIX',
            parsed={'status': 'UNAVAILABLE', 'reason': 'SOURCE_UNAVAILABLE'})
        summary_unavailable(value)
        self.assertEqual([], a.derive(value)['consistencyIssues'])
        value['commands'][2]['hashScope'] = 'FULL_OUTPUT'
        self.assertTrue(a.derive(value)['consistencyIssues'])

    def test_different_token_or_wrong_command_order_rejected(self):
        value = fixture()
        value['commands'][2]['parsed']['matches'][0]['token'] = 'android.os.BinderProxy@dead'
        self.assertTrue(a.derive(value)['consistencyIssues'])
        value = fixture()
        value['commands'][1], value['commands'][2] = value['commands'][2], value['commands'][1]
        self.assertTrue(a.derive(value)['consistencyIssues'])

    def test_fallbacks_and_invalid_types_are_explicit(self):
        for flag, expected in [('diagnosticIdentityUnavailable', 'identity_unavailable'), ('diagnosticSerializationFailed', 'serialization_failed')]:
            self.assertEqual(expected, a.derive({flag: True})['availability'])
            self.assertTrue(a.derive({flag: True, 'extra': 1})['consistencyIssues'])
        self.assertEqual('truncated', a.derive({'diagnosticTruncated': True, 'untruncatedChars': 20000})['availability'])
        self.assertTrue(a.derive({'diagnosticTruncated': True, 'untruncatedChars': True})['consistencyIssues'])
        for key, bad in [('schemaVersion', True), ('commands', [1, 2, 3]), ('association', []), ('configuredIme', True), ('startedElapsedMillis', []), ('totalTimeoutMillis', True)]:
            value = fixture()
            value[key] = bad
            self.assertTrue(a.derive(value)['consistencyIssues'])

    def test_atomic_lifecycle_claim_and_count_prefix_refused(self):
        value = fixture()
        value['association'].update(atomicSnapshot=True, systemServerLifetime='SAME_PROCESS')
        self.assertTrue(a.derive(value)['consistencyIssues'])
        value = fixture()
        value['commands'][2]['parsed']['matches'] = []
        self.assertTrue(a.derive(value)['consistencyIssues'])

    def test_null_candidate_does_not_become_zero_count(self):
        value = fixture()
        value['commands'][0]['parsed'] = {'status': 'UNAVAILABLE', 'reason': 'WMS_CLIENT_UNRECOGNIZED'}
        value['association']['tokenCandidate'] = None
        summary_unavailable(value)
        for group in [value['commands'][1]['parsed']['visible'], value['commands'][1]['parsed']['displays'], value['commands'][2]['parsed']]:
            group.update(matchCount=None, matches=[])
        self.assertEqual([], a.derive(value)['consistencyIssues'])
        value['commands'][2]['parsed']['matchCount'] = 0
        self.assertTrue(a.derive(value)['consistencyIssues'])


class AdditionalConsistencyTest(unittest.TestCase):
    def test_match_count_cannot_exceed_actual_handle_or_pair_count(self):
        for index, group_key in [(1, 'visible'), (2, None)]:
            value = fixture()
            group = value['commands'][index]['parsed']
            if group_key:
                group = group[group_key]
            group['matchCount'] = 4
            group['matches'] *= 4
            value['association'].update(status='INCONCLUSIVE', reason='CANDIDATE_NOT_UNIQUE')
            value['association']['populatorVisibleMatchCount' if index == 1 else 'finalMatchCount'] = 4
            result = a.derive(value)
            self.assertTrue(result['consistencyIssues'])
            self.assertFalse(result['interpretationAllowed'])
            self.assertEqual('invalid_schema', result['postFailureObservations']['finalService']['availability'])

    def test_recognized_populator_requires_notification_metadata(self):
        value = fixture()
        del value['commands'][1]['parsed']['notificationsEnabled']
        self.assertTrue(a.derive(value)['consistencyIssues'])

    def test_display_and_final_type_mismatch_stay_inconclusive(self):
        for index, field, changed in [(1, 'displayId', 1), (2, 'type', 1)]:
            value = fixture()
            group = value['commands'][index]['parsed']
            if index == 1:
                group = group['visible']
            group['matches'][0][field] = changed
            value['association'].update(status='INCONCLUSIVE', reason='CANDIDATE_ATTRIBUTES_MISMATCH')
            self.assertEqual([], a.derive(value)['consistencyIssues'])
            value['association'].update(status='TEXT_CANDIDATE_ONLY', reason='ORDERED_TEXT_COUNTS_ONLY')
            self.assertTrue(a.derive(value)['consistencyIssues'])

    def test_semantically_invalid_or_ambiguous_freeze_is_not_used(self):
        prior = {'availability': 'retained_prefix'}
        for bad in [[], {}, True, -1, None, '9000']:
            self.assertIsNone(a.original_freeze(prior, [{'value': {'frozenElapsedMillis': bad}, 'rawJsonSha256': 'a'}]))
        good = {'value': {'frozenElapsedMillis': 9000}, 'rawJsonSha256': 'a'}
        self.assertEqual(9000, a.original_freeze(prior, [good, good]))
        self.assertIsNone(a.original_freeze({'availability': 'invalid_schema'}, [good]))
        self.assertIsNone(a.original_freeze(dict(prior, consistencyIssues=['original mismatch']), [good]))
        self.assertIsNone(a.original_freeze(prior, [good, dict(good, rawJsonSha256='b')]))

    def test_numeric_geometry_transform_never_accepts_content_or_nonfinite(self):
        for field, bad in [('transform', 'private-window-title'), ('scaleFactor', True), ('alpha', float('nan')), ('frame', None)]:
            value = fixture()
            value['commands'][1]['parsed']['visible']['matches'][0][field] = bad
            self.assertTrue(a.derive(value)['consistencyIssues'])

    def test_final_case_ordering_guard_suppresses_pre_freeze_observations(self):
        # Isolate case-level checks after schema derivation. Source/hash validation
        # remains the original base analyzer; this mock does not test provenance.
        original = {'sourceRun': {'commit': 'a'*40}, 'manifestSha256': 'b'*64,
            'originalStateCounts': {'passed': 0, 'failed': 1, 'unverified': 0},
            'cases': [{'api': '30', 'app': 'notes-1.7.0', 'state': 'off', 'originalStatus': 'failed',
                'originalStage': 'create_note', 'analysis': {'availability': 'retained_prefix', 'consistencyIssues': []},
                'originalConfirmationPreparation': {'stage': 'HIDDEN_PREPARATION'}}],
            'lanes': [{'api': '30', 'occurrences': [{'method': 'notesWithAgentDisabled',
                'rawJsonSha256': 'c'*64, 'value': {'frozenElapsedMillis': 11000}}]}]}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lane = root/'api-30'
            lane.mkdir()
            (lane/'instrumentation.json').write_text(json.dumps({'output': block(json.dumps(fixture()))}))
            (lane/'ui-results.json').write_text(json.dumps({'apps': [{'appId': 'notes-1.7.0', 'states': [{
                'state': 'off', 'status': 'failed', 'stage': 'create_note', 'installedIdentityVerified': True,
                'error': 'confirmation_input_method_hidden_not_verified'}]}]}))
            with patch.object(a.base, 'analyze', return_value=original):
                result = a.analyze(root, 'a'*40, 1)
        case = result['cases'][0]
        self.assertFalse(case['postFailureTiming']['startedAfterOriginalFreeze'])
        self.assertEqual('invalid_schema', case['analysis']['availability'])
        self.assertFalse(case['analysis']['interpretationAllowed'])
        self.assertEqual({'availability': 'invalid_schema', 'matchCount': None},
                         case['analysis']['postFailureObservations']['finalService'])


if __name__ == '__main__':
    unittest.main()
