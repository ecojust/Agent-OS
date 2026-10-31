#!/usr/bin/env python3
"""Analyze one archived Notes v4 run's same-read suppressed diagnostics.

No Android/host collection modules, networking, device actions, or mutation of
source evidence. Original capture-after-failure observations are never used.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

MARKER = 'NOTES_CONFIRMATION_DIAGNOSTIC_V1 '
CLASS = 'dev.agentos.compat.ApplicationSetCompatibilityInstrumentedTest'
METHODS = {'notesWithAgentDisabled': ('notes-1.7.0', 'off'),
           'notesWithAgentBoundAndIdle': ('notes-1.7.0', 'idle'),
           'calculatorWithAgentDisabled': ('calculator-1.4.0', 'off'),
           'calculatorWithAgentBoundAndIdle': ('calculator-1.4.0', 'idle')}
LABELS = ['30', '31', '32', '33', '34', '35', '36', '37.0']
FIELDS = ['isOnScreen', 'isVisible', 'isReadyForDisplay()', 'mLastHidden',
          'mEnterAnimationPending', 'mAnimatingExit', 'mHasSurface', 'Surface: shown']
VALUES = {'TRUE', 'FALSE', 'MISSING', 'OTHER', 'CONFLICT'}
MAX_DIAGNOSTIC = 16 * 1024
MAX_PAYLOAD = 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def object_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate JSON key: ' + key)
        result[key] = value
    return result


DECODER = json.JSONDecoder(object_pairs_hook=object_pairs)


def read_json(path):
    require(not path.is_symlink() and path.is_file(), 'missing or linked file: ' + str(path))
    data = path.read_bytes()
    require(len(data) <= MAX_PAYLOAD, 'oversized evidence JSON')
    return DECODER.decode(data.decode('utf-8')), data


def digest(data):
    return hashlib.sha256(data).hexdigest()


def extract(output):
    """Keep every occurrence; never infer a case from ordering alone."""
    require(type(output) is str and len(output.encode('utf-8')) <= MAX_PAYLOAD, 'invalid instrumentation output')
    context = None
    occurrences = []
    offset = 0
    for line_number, line in enumerate(output.splitlines(keepends=True), 1):
        if line.startswith('INSTRUMENTATION_RESULT:'):
            context = None
        found = re.match(r'INSTRUMENTATION_STATUS: test=(\w+)\s*$', line)
        if not found:
            found = re.search(r'(?:Error in |^\d+\) )(\w+)\(' + re.escape(CLASS) + r'\)', line)
        if found:
            require(found.group(1) in METHODS, 'unexpected instrumentation test method')
            context = found.group(1)
        else:
            frame = re.search(r'\bat ' + re.escape(CLASS) + r'\.(\w+)\(', line)
            if frame and frame.group(1) in METHODS:
                context = frame.group(1)
        position = line.find(MARKER)
        if position >= 0:
            require(line.count(MARKER) == 1, 'ambiguous diagnostic marker line')
            require(context in METHODS, 'diagnostic lacks explicit test method context')
            payload = line[position + len(MARKER):].rstrip('\r\n')
            require(len(payload) + len(MARKER) <= MAX_DIAGNOSTIC, 'oversized diagnostic message')
            value, consumed = DECODER.raw_decode(payload)
            require(not payload[consumed:].strip(), 'unexpected bytes after diagnostic JSON')
            require(type(value) is dict, 'diagnostic must be an object')
            raw = payload[:consumed]
            occurrences.append({'method': context, 'line': line_number,
                                'characterOffset': offset + position, 'rawJson': raw,
                                'rawJsonSha256': digest(raw.encode('utf-8')), 'value': value})
        offset += len(line)
    return occurrences


def integer(value):
    return type(value) is int and -(2**63) <= value < 2**63


def inventory_summary(value, started, label, issues):
    if value is None:
        return None
    require(type(value) is dict, 'inventory must be object or null')
    expected = {'sequence', 'startedElapsedMillis', 'readCompletedElapsedMillis', 'focusedWindowId',
                'focusedLayer', 'scanComplete', 'imeCount', 'notRetained', 'imeEntries'}
    require(set(value) == expected, 'unexpected inventory diagnostic shape')
    for key in expected - {'scanComplete', 'imeEntries'}:
        require(integer(value[key]), 'noninteger inventory field')
    require(type(value['scanComplete']) is bool and type(value['imeEntries']) is list, 'invalid inventory metadata')
    count, rows = value['imeCount'], value['imeEntries']
    if not (count >= 0 and len(rows) == min(count, 8) and value['notRetained'] == count - len(rows)):
        issues.append(label + ': inventory prefix accounting mismatch')
    if value['startedElapsedMillis'] < started:
        issues.append(label + ': inventory before confirmation start')
    if value['readCompletedElapsedMillis'] != -1 and value['readCompletedElapsedMillis'] < value['startedElapsedMillis']:
        issues.append(label + ': inventory read time reversed')
    counts = Counter()
    for row in rows:
        require(type(row) is list and len(row) == 4 and all(integer(x) for x in row[:3]), 'invalid inventory entry')
        wid, display, layer, reason = row
        expected_reason = ('SAME_WINDOW' if wid == value['focusedWindowId'] else
                           'OTHER_DISPLAY' if display != 0 else
                           'BELOW_FOCUSED' if layer < value['focusedLayer'] else 'CANDIDATE_REQUIRES_IDENTITY')
        if reason != expected_reason:
            issues.append(label + ': inventory filter does not match original metadata')
        counts[reason] += 1
    return {'sequence': value['sequence'], 'scanComplete': value['scanComplete'],
            'focusedWindowId': value['focusedWindowId'], 'focusedLayer': value['focusedLayer'],
            'imeCount': count, 'notRetained': value['notRetained'], 'retainedFilterCounts': dict(counts),
            'entries': rows, 'startedElapsedMillis': value['startedElapsedMillis'],
            'readCompletedElapsedMillis': value['readCompletedElapsedMillis']}


def analyze_diagnostic(value, proof):
    issues = []
    if value.get('diagnosticTruncated') is True:
        return {'availability': 'truncated', 'consistencyIssues': ['explicit diagnostic truncation']}
    if value.get('diagnosticSerializationFailed') is True:
        return {'availability': 'serialization_failed', 'consistencyIssues': ['explicit diagnostic serialization failure']}
    expected = {'clock', 'confirmationStartedElapsedMillis', 'deadlineElapsedMillis', 'frozenElapsedMillis', 'stage',
                'hiddenSamplesClaimed', 'hiddenDiagnosticsObserved', 'hiddenDiagnosticsNotRetained',
                'inventoryEntryColumns', 'backDecisionInventory', 'lastInventory', 'hiddenSamples', 'diagnosticTruncated'}
    require(set(value) == expected, 'unexpected normal diagnostic shape')
    require(value['clock'] == 'SystemClock.elapsedRealtime' and value['diagnosticTruncated'] is False, 'unknown diagnostic clock/status')
    require(value['stage'] in {'NOT_STARTED', 'TITLE_PREPARATION', 'BACK_SUBMISSION', 'HIDDEN_PREPARATION', 'CONFIRM_SUBMISSION', 'CONFIRM_ACCEPTED'}, 'unknown confirmation stage')
    require(value['inventoryEntryColumns'] == ['windowId', 'displayId', 'layer', 'filter'], 'unknown inventory column order')
    for key in ['confirmationStartedElapsedMillis', 'deadlineElapsedMillis', 'frozenElapsedMillis',
                'hiddenSamplesClaimed', 'hiddenDiagnosticsObserved', 'hiddenDiagnosticsNotRetained']:
        require(integer(value[key]), 'noninteger confirmation diagnostic field')
    started, deadline = value['confirmationStartedElapsedMillis'], value['deadlineElapsedMillis']
    if deadline - started != 5000 or value['frozenElapsedMillis'] < started:
        issues.append('original 5000ms deadline or freeze ordering mismatch')
    if not isinstance(proof, dict):
        issues.append('original confirmation receipt unavailable')
        proof = {}
    if value['stage'] != proof.get('stage') or value['hiddenSamplesClaimed'] != proof.get('hiddenSamples'):
        issues.append('diagnostic differs from original v4 receipt stage/sample count')
    rows = value['hiddenSamples']
    require(type(rows) is list and len(rows) <= 4, 'invalid hidden diagnostic prefix')
    if not (0 <= value['hiddenSamplesClaimed'] <= 4 and len(rows) <= value['hiddenDiagnosticsObserved'] <= value['hiddenSamplesClaimed']
            and value['hiddenDiagnosticsObserved'] - len(rows) == value['hiddenDiagnosticsNotRetained']):
        issues.append('hidden diagnostic prefix accounting mismatch')
    decision = inventory_summary(value['backDecisionInventory'], started, 'back decision', issues)
    last = inventory_summary(value['lastInventory'], started, 'last inventory', issues)
    samples, previous_ordinal, previous_end = [], 0, started
    for row in rows:
        require(type(row) is dict and set(row) == {'ordinal', 'startedElapsedMillis', 'endedElapsedMillis', 'wms', 'beforeInventory'},
                'invalid hidden sample shape')
        for key in ['ordinal', 'startedElapsedMillis', 'endedElapsedMillis']:
            require(integer(row[key]), 'noninteger hidden sample field')
        n, begin, end = row['ordinal'], row['startedElapsedMillis'], row['endedElapsedMillis']
        if not (previous_ordinal < n <= value['hiddenSamplesClaimed'] and previous_end <= begin <= end <= value['frozenElapsedMillis']):
            issues.append('hidden sample ordering mismatch at ordinal ' + str(n))
        inv = inventory_summary(row['beforeInventory'], started, 'sample ' + str(n), issues)
        if inv is not None and inv['readCompletedElapsedMillis'] > begin:
            issues.append('inventory occurred after its WMS sample at ordinal ' + str(n))
        wms = row['wms']
        allowed_wms = {'parseStage', 'outcome', 'refusal', 'dumpStartedElapsedMillis', 'dumpEndedElapsedMillis',
                       'selectedToken', 'inputMethodRecordCount', 'recordIndex', 'recordEnd', 'ownerUid',
                       'ownerMatches', 'displayMatches', 'packageMatches', 'fields'}
        require(type(wms) is dict and set(wms) <= allowed_wms, 'invalid WMS metadata')
        if 'parseStage' in wms:
            require(wms['parseStage'] in {'dump_header', 'window_reference', 'window_record', 'identity', 'visibility', 'hidden_verified'}, 'unknown WMS parse stage')
        if 'outcome' in wms:
            require(wms['outcome'] in {'HIDDEN_VERIFIED', 'REFUSED', 'READ_OR_PARSE_EXCEPTION'}, 'unknown WMS outcome')
        if 'refusal' in wms:
            require(wms['refusal'] in {'configured_system_input_method_unverified', 'input_method_window_dump_unavailable',
                                      'input_method_window_reference_unavailable', 'input_method_window_reference_ambiguous',
                                      'input_method_window_record_missing_or_ambiguous', 'input_method_window_identity_mismatch',
                                      'input_method_is_visible_animating_or_unverified', 'input_method_surface_is_visible_or_unverified'},
                    'unknown WMS refusal')
        if 'selectedToken' in wms:
            require(type(wms['selectedToken']) is str and re.fullmatch('[0-9a-fA-F]{1,32}', wms['selectedToken']) is not None, 'invalid bounded WMS token')
        if 'recordEnd' in wms:
            require(wms['recordEnd'] in {'next_window', 'dump_end'}, 'unknown WMS record end')
        for key in ['ownerMatches', 'displayMatches', 'packageMatches']:
            if key in wms:
                require(type(wms[key]) is bool, 'nonboolean WMS identity result')
        for key in ['ownerUid', 'recordIndex', 'inputMethodRecordCount']:
            if key in wms:
                require(integer(wms[key]), 'nonnumeric WMS identity/result')
        if 'dumpStartedElapsedMillis' in wms or 'dumpEndedElapsedMillis' in wms:
            ds, de = wms.get('dumpStartedElapsedMillis'), wms.get('dumpEndedElapsedMillis')
            if not (integer(ds) and integer(de) and begin <= ds <= de <= end):
                issues.append('WMS read ordering mismatch at ordinal ' + str(n))
        fields = wms.get('fields')
        if fields is not None:
            require(type(fields) is dict and set(fields) == set(FIELDS), 'unexpected WMS field set')
            for field in fields.values():
                require(type(field) is dict and set(field) == {'count', 'value'} and integer(field['count'])
                        and field['count'] >= 0 and field['value'] in VALUES, 'invalid WMS field observation')
        samples.append({'ordinal': n, 'startedElapsedMillis': begin, 'endedElapsedMillis': end,
                        'wms': wms, 'beforeInventory': inv})
        previous_ordinal, previous_end = n, end
    return {'availability': 'retained_prefix', 'stage': value['stage'],
            'hiddenSamplesClaimed': value['hiddenSamplesClaimed'], 'hiddenDiagnosticCount': len(rows),
            'claimedWithoutDiagnostic': value['hiddenSamplesClaimed'] - value['hiddenDiagnosticsObserved'],
            'backDecisionInventory': decision, 'lastInventory': last, 'samples': samples,
            'consistencyIssues': issues}


def analyze(root, source, run_id, attempt):
    manifest, manifest_raw = read_json(root / 'manifest.json')
    identity = manifest['sourceRun']
    require(identity['repository'] == 'ecojust/Agent-OS' and identity['commit'] == source
            and identity['runId'] == run_id and identity['attempt'] == attempt, 'source identity mismatch')
    require(manifest['schemaVersion'] == 4 and manifest['expectedCombinationCount'] == 32, 'wrong v4 matrix')
    require([v['matrixLabel'] for v in manifest['apis']] == LABELS, 'missing/reordered/duplicate API lanes')
    cases, lanes, counts = [], [], Counter()
    for lane in manifest['apis']:
        label = lane['matrixLabel']
        base = root / ('api-' + label)
        declared = {f['path']: f for f in lane['files']}
        require(len(declared) == len(lane['files']), 'duplicate payload path')
        payloads = {}
        for filename in ['report.json', 'ui-results.json', 'instrumentation.json']:
            if filename not in declared:
                require(not (base / filename).exists(), 'undeclared analysis payload')
                continue
            value, raw = read_json(base / filename)
            require(len(raw) == declared[filename]['bytes']
                    and digest(raw) == declared[filename]['sha256'], 'payload integrity mismatch')
            payloads[filename] = (value, raw)
        if len(payloads) != 3:
            missing = [name for name in ['report.json', 'ui-results.json', 'instrumentation.json'] if name not in payloads]
            report = payloads.get('report.json', ({}, b''))[0]
            if report:
                require(report['schemaVersion'] == 4 and report['source'] == {
                    'repository': 'ecojust/Agent-OS', 'runId': run_id, 'attempt': attempt, 'commit': source}, 'mixed partial report source/schema')
            partial_states = {(app['appId'], state['state']): state
                              for app in report.get('apps', []) for state in app['states']}
            instrument, instrument_raw = payloads.get('instrumentation.json', ({}, b''))
            occurrences = extract(instrument['output']) if 'output' in instrument else []
            for app, state in METHODS.values():
                status = lane['apps'][app][state]
                require(status in {'passed', 'failed', 'unverified'}, 'unknown partial lane status')
                original = partial_states.get((app, state), {})
                if original:
                    require(status == original['status'], 'partial report differs from manifest outcome')
                counts[status] += 1
                item = {'api': label, 'app': app, 'state': state, 'originalStatus': status,
                        'originalStage': original.get('stage', 'unavailable'), 'inputFilesMissing': missing,
                        'originalReportError': report.get('error')}
                if app == 'notes-1.7.0':
                    matching = [o for o in occurrences if METHODS[o['method']] == (app, state)]
                    unique = {o['rawJsonSha256']: o['value'] for o in matching}
                    proof = original.get('result', {}).get('confirmationPreparation')
                    item.update(originalConfirmationPreparation=proof, diagnosticOccurrences=len(matching), distinctDiagnosticCount=len(unique),
                                analysis={'availability': 'incomplete_raw_payloads',
                                          'consistencyIssues': ['missing required raw payloads; original status retained without promotion']})
                cases.append(item)
            lanes.append({'api': label, 'inputFilesMissing': missing, 'originalReportError': report.get('error'),
                          'instrumentationSha256': digest(instrument_raw) if instrument_raw else None,
                          'instrumentationBytes': len(instrument_raw), 'commandCompleted': instrument.get('commandCompleted'),
                          'occurrences': occurrences})
            continue
        report, report_raw = payloads['report.json']
        ui, ui_raw = payloads['ui-results.json']
        require(report['schemaVersion'] == 4 and report['source'] == {
            'repository': 'ecojust/Agent-OS', 'runId': run_id, 'attempt': attempt, 'commit': source}, 'mixed report source/schema')
        require(ui['schemaVersion'] == 4 and ui['source'] == {
            'sha': source, 'runId': str(run_id), 'attempt': str(attempt), 'expectedApi': str(int(float(label))),
            'deviceSerial': report['device']['serial']}, 'mixed UI source/schema')
        report_states = {app['appId']: app['states'] for app in report['apps']}
        ui_states = {app['appId']: app['states'] for app in ui['apps']}
        require(len(report_states) == len(ui_states) == 2 and report['uiResultsSha256'] == digest(ui_raw)
                and report_states == ui_states, 'report differs from original UI payload')
        instrument, instrument_raw = payloads['instrumentation.json']
        occurrences = extract(instrument['output'])
        matched = {}
        for occurrence in occurrences:
            matched.setdefault(METHODS[occurrence['method']], {})[occurrence['rawJsonSha256']] = occurrence['value']
        originals = {}
        for app in ui['apps']:
            for state in app['states']:
                key = (app['appId'], state['state'])
                require(key not in originals, 'duplicate app/state')
                originals[key] = state
        require(set(originals) == set(METHODS.values()), 'unexpected four-case identity set')
        for app, state in METHODS.values():
            original = originals[(app, state)]
            status = original['status']
            require(status in {'passed', 'failed', 'unverified'}, 'unknown original state status')
            counts[status] += 1
            item = {'api': label, 'app': app, 'state': state, 'originalStatus': status, 'originalStage': original['stage']}
            if app == 'notes-1.7.0':
                proof = original.get('result', {}).get('confirmationPreparation')
                unique = matched.get((app, state), {})
                item['originalConfirmationPreparation'] = proof
                item['diagnosticOccurrences'] = sum(METHODS[o['method']] == (app, state) for o in occurrences)
                item['distinctDiagnosticCount'] = len(unique)
                if len(unique) == 1:
                    item['analysis'] = analyze_diagnostic(next(iter(unique.values())), proof)
                    if status != 'failed':
                        item['analysis']['consistencyIssues'].append('failure diagnostic attached to nonfailed original state')
                else:
                    issues = []
                    if unique:
                        issues.append('multiple distinct failure diagnostics for one original case; none selected')
                    elif status == 'failed' and isinstance(proof, dict) and proof.get('stage') in {
                            'TITLE_PREPARATION', 'BACK_SUBMISSION', 'HIDDEN_PREPARATION', 'CONFIRM_SUBMISSION'}:
                        issues.append('confirmation failure diagnostic absent; no preparation samples reconstructed')
                    item['analysis'] = {'availability': 'absent' if not unique else 'ambiguous', 'consistencyIssues': issues}
            cases.append(item)
        lanes.append({'api': label, 'instrumentationSha256': digest(instrument_raw),
                      'instrumentationBytes': len(instrument_raw), 'commandCompleted': instrument['commandCompleted'],
                      'occurrences': occurrences})
    actual_counts = {key: counts[key] for key in ['passed', 'failed', 'unverified']}
    require(actual_counts == manifest['stateCounts'] and len(cases) == 32, 'matrix counts mismatch')
    return {'schemaVersion': 1, 'kind': 'notes-same-read-diagnostic-analysis', 'sourceRun': identity,
            'manifestSha256': digest(manifest_raw), 'originalStateCounts': actual_counts, 'plannedCombinations': 32,
            'cases': cases, 'lanes': lanes, 'analysisScriptSha256': digest(Path(__file__).read_bytes()),
            'limitations': ['All evidence belongs to this one source/run/attempt; no historical samples are merged.',
                            'Diagnostic occurrences repeated by JUnit are preserved and deduplicated by exact raw JSON SHA within each case.',
                            'No capture-after-failure observation is used to fill a preparation sample.',
                            'Only backDecisionInventory precedes the original BACK branch; later beforeInventory samples cannot explain an earlier no-BACK decision.',
                            'TYPE_INPUT_METHOD inventory metadata does not establish configured IME root identity or a shared identifier with the WMS window token.',
                            'No original status is promoted, no hidden-state predicate is relaxed, and no physical-presentation proof is claimed.',
                            'No elapsedRealtime BACK input timestamps were recorded; only START-relative samples and pre-decision/post-BACK ordering can be reported.',
                            'This checks the three used payload hashes and local source links, not a full GitHub artifact provenance audit.']}


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--archive', type=Path, required=True)
    p.add_argument('--source', required=True)
    p.add_argument('--run-id', type=int, required=True)
    p.add_argument('--attempt', type=int, default=1)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    require(re.fullmatch('[0-9a-f]{40}', args.source) is not None and args.run_id > 0 and args.attempt > 0, 'invalid expected identity')
    result = analyze(args.archive, args.source, args.run_id, args.attempt)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'originalStateCounts': result['originalStateCounts'], 'plannedCombinations': 32,
                      'diagnosticCases': sum(c.get('distinctDiagnosticCount', 0) == 1 for c in result['cases']),
                      'consistencyIssues': sum(len(c.get('analysis', {}).get('consistencyIssues', [])) for c in result['cases'])}))


if __name__ == '__main__':
    main()
