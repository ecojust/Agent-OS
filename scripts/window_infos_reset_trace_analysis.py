#!/usr/bin/env python3
"""Bounded, conditional IME reset ancestry analysis of original Perfetto data.

No missing event is evidence for the SettingsObserver entry point. A synchronous
clientTransactionExecuted ancestor is positive evidence only under the reviewed
framework source and no nested Looper dispatch assumption, not image equivalence.
"""
import argparse
import csv
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import resource
import stat
import subprocess
import tempfile

import experience_trace as perfetto

KIND = 'window-infos-reset-trace-analysis'
PROGRAM = Path(__file__).resolve()
PROGRAM_PATH = 'scripts/window_infos_reset_trace_analysis.py'
MAX_TRACE = 32 * 1024 * 1024
QUERY_LIMIT = 4 * 1024 * 1024
MAX_ROWS = 50000
TARGET_NAMES = ('IMS.resetStateForNewConfiguration', 'IMS.initViews', 'IMS.onComputeInsets')
QUERY_FILES = ('trace-slices.csv', 'trace-clocks.csv', 'trace-processes.csv',
               'trace-stats.csv', 'trace-bounds.csv', 'trace-metadata.csv')
ANALYSIS_FILES = set(QUERY_FILES) | {'analysis.json'}
ARMS = ('baseline', 'enabled')
SCOPE = {'diagnosticOnly': True, 'applicationCompatibilityAcceptance': False,
         'originalNotesReplayed': False, 'comparisonStatusChanged': False,
         'settingsObserverInferredFromMissingAncestor': False,
         'sourceImageEquivalenceVerified': False, 'binderIdentityProved': False,
         'fixedSourceInference': True, 'noNestedLooperDispatchAssumed': True,
         'sourceCommit': '94b4c163b7dfe5ce3607f7bb8456f9573f7de57d',
         'resetTrackingReasonIsCompleteResetCount': False,
         'absentSlicesProveNoCalls': False, 'captureStatusValidatedByParent': True}


class AnalysisError(ValueError):
    pass


def require(ok, reason):
    if not ok:
        raise AnalysisError(reason)


def identity(data):
    return {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def read_file(path, limit=QUERY_LIMIT):
    path = Path(path)
    require(not any(p.is_symlink() for p in (path, *path.parents)), 'SYMLINK_PATH')
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and 0 <= info.st_size <= limit, 'FILE_TYPE_OR_LIMIT')
        data = stream.read(limit + 1)
        require(len(data) == info.st_size and len(data) <= limit, 'FILE_CHANGED_OR_LIMIT')
        return data


def decode_json(data):
    require(type(data) is bytes and len(data) <= QUERY_LIMIT, 'JSON_BOUND')
    def pairs(values):
        result = {}
        for key, value in values:
            require(key not in result, 'JSON_DUPLICATE_KEY')
            result[key] = value
        return result
    try:
        def finite_float(text):
            require(len(text) <= 64, 'JSON_FLOAT_BOUND')
            value = float(text)
            require(math.isfinite(value), 'JSON_NONFINITE')
            return value
        result = json.loads(data, object_pairs_hook=pairs,
                            parse_float=finite_float,
                            parse_constant=lambda _: (_ for _ in ()).throw(AnalysisError('JSON_NONFINITE')))
        pending = [(result, 0)]; count = 0
        while pending:
            value, depth = pending.pop(); count += 1
            require(depth <= 64 and count <= 200000, 'JSON_STRUCTURE_LIMIT')
            if isinstance(value, dict): pending.extend((v, depth + 1) for v in value.values())
            elif isinstance(value, list): pending.extend((v, depth + 1) for v in value)
        return result
    except (ValueError, UnicodeError, RecursionError) as error:
        if isinstance(error, AnalysisError): raise
        raise AnalysisError('JSON_FORMAT') from None


def integer(value, minimum=0):
    return type(value) is int and minimum <= value < 2**63


def same(left, right):
    return json.dumps(left, sort_keys=True, separators=(',', ':')) == json.dumps(right, sort_keys=True, separators=(',', ':'))


def queries():
    # Hex strings avoid trace_processor's CSV printer ambiguities for commas,
    # embedded quotes and line breaks in trace-provided names.
    return {
        'trace-slices.csv': '''WITH RECURSIVE selected(id) AS (
 SELECT id FROM slice WHERE name IN ('IMS.resetStateForNewConfiguration','IMS.initViews','IMS.onComputeInsets')
 UNION SELECT s.parent_id FROM slice s JOIN selected q ON s.id=q.id WHERE s.parent_id IS NOT NULL
)
SELECT s.id,s.parent_id,s.ts,s.dur,s.depth,s.track_id,hex(s.name) AS name,
 t.utid,t.upid,t.tid,p.pid,t.is_main_thread
FROM selected q JOIN slice s ON s.id=q.id
LEFT JOIN thread_track tt ON tt.id=s.track_id LEFT JOIN thread t ON t.utid=tt.utid
LEFT JOIN process p ON p.upid=t.upid ORDER BY s.id;''',
        'trace-clocks.csv': 'SELECT snapshot_id,ts,clock_id,hex(clock_name) AS clock_name,clock_value,machine_id FROM clock_snapshot ORDER BY id;',
        'trace-processes.csv': 'SELECT upid,pid,hex(name) AS name,uid,start_ts,end_ts,hex(cmdline) AS cmdline,machine_id FROM process ORDER BY upid;',
        'trace-stats.csv': 'SELECT hex(name) AS name,idx,hex(severity) AS severity,hex(source) AS source,value FROM stats ORDER BY name,idx;',
        'trace-bounds.csv': 'SELECT start_ts,end_ts FROM trace_bounds;',
        'trace-metadata.csv': 'SELECT hex(name) AS name,int_value,hex(str_value) AS str_value,machine_id FROM metadata ORDER BY id;',
    }


SCHEMAS = {
    'trace-slices.csv': ('id','parent_id','ts','dur','depth','track_id','name','utid','upid','tid','pid','is_main_thread'),
    'trace-clocks.csv': ('snapshot_id','ts','clock_id','clock_name','clock_value','machine_id'),
    'trace-processes.csv': ('upid','pid','name','uid','start_ts','end_ts','cmdline','machine_id'),
    'trace-stats.csv': ('name','idx','severity','source','value'),
    'trace-bounds.csv': ('start_ts','end_ts'),
    'trace-metadata.csv': ('name','int_value','str_value','machine_id'),
}
TEXT_COLUMNS = {'name','clock_name','cmdline','severity','source','str_value'}


def parse_csv(name, data):
    require(name in SCHEMAS and type(data) is bytes and len(data) <= QUERY_LIMIT, 'CSV_BOUND_OR_NAME')
    try:
        lines = csv.reader(io.StringIO(data.decode('ascii')), strict=True)
        require(tuple(next(lines)) == SCHEMAS[name], 'CSV_HEADER')
        rows = []
        for values in lines:
            require(len(values) == len(SCHEMAS[name]) and len(rows) < MAX_ROWS, 'CSV_ROW_BOUND')
            item = {}
            for key, value in zip(SCHEMAS[name], values):
                if key in TEXT_COLUMNS:
                    require(len(value) <= 16384 and re.fullmatch(r'(?:[0-9A-Fa-f]{2})*', value), 'CSV_HEX_STRING')
                    item[key] = bytes.fromhex(value).decode('utf-8')
                elif value == '[NULL]': item[key] = None
                else:
                    require(re.fullmatch(r'-?(?:0|[1-9][0-9]{0,18})', value), 'CSV_INTEGER')
                    item[key] = int(value)
                    require(-(2**63) <= item[key] < 2**63, 'CSV_INTEGER_BOUND')
            rows.append(item)
        return rows
    except (ValueError, UnicodeError, csv.Error, StopIteration) as error:
        if isinstance(error, AnalysisError): raise
        raise AnalysisError('CSV_FORMAT') from None


def load_context(comparison):
    """Read identity and clock context; never use producer acceptance as proof."""
    comparison = Path(comparison).absolute()
    inputs = {}
    def read(name):
        data = read_file(comparison / name); inputs[name] = identity(data)
        return decode_json(data)
    top = read('report.json')
    require(type(top) is dict and type(top.get('source')) is dict, 'CONTEXT_SOURCE')
    source = top['source']
    require(set(source) == {'repository','commit','runId','attempt'}
            and source['repository'] == 'ecojust/Agent-OS'
            and re.fullmatch('[0-9a-f]{40}', str(source['commit']))
            and integer(source['runId'], 1) and integer(source['attempt'], 1), 'CONTEXT_SOURCE')
    arms = {}
    for arm in ARMS:
        entry = {'status': 'unknown', 'reason': 'CONTEXT_NOT_RECORDED',
                 'package': None, 'uid': None, 'readyElapsedMs': None,
                 'freezeElapsedMs': None, 'showElapsedMs': None}
        report_path = comparison / arm / 'report.json'
        if report_path.exists():
            report = read(arm + '/report.json')
            require(type(report) is dict and report.get('source') == source, 'CONTEXT_SOURCE_MISMATCH')
            ime = report.get('ime')
            if isinstance(ime, dict):
                package = ime.get('package'); uid = ime.get('uid')
                require(type(package) is str and re.fullmatch(r'[A-Za-z][A-Za-z0-9_.]{0,199}', package)
                        and integer(uid, 1), 'CONTEXT_IME')
                entry.update(package=package, uid=uid)
        raw_path, fixture_path = comparison / arm / 'raw-result.json', comparison / arm / 'fixture.json'
        if raw_path.exists():
            raw = read(arm + '/raw-result.json')
            require(type(raw) is dict and raw.get('source') == source and raw.get('imePackage') == entry['package']
                    and raw.get('imeUid') == entry['uid'], 'CONTEXT_RAW_IDENTITY')
            times = raw.get('times', {})
            require(type(times) is dict, 'CONTEXT_RAW_TIMES')
            ready, freeze = times.get('readyElapsedMs'), times.get('freezeElapsedMs')
            if integer(ready) and integer(freeze) and ready < freeze:
                entry.update(readyElapsedMs=ready, freezeElapsedMs=freeze)
        if fixture_path.exists():
            fixture = read(arm + '/fixture.json')
            require(type(fixture) is dict and fixture.get('source') == source, 'CONTEXT_FIXTURE_SOURCE')
            show = fixture.get('showRequestedElapsedMs')
            if integer(show) and fixture.get('showImeAttempts') == 1:
                entry['showElapsedMs'] = show
        if all(entry[k] is not None for k in ('package','uid','readyElapsedMs','freezeElapsedMs','showElapsedMs')):
            require(entry['readyElapsedMs'] <= entry['showElapsedMs'] <= entry['freezeElapsedMs'], 'CONTEXT_SHOW_OUTSIDE_WINDOW')
            entry.update(status='recorded', reason=None)
        arms[arm] = entry
    known = {(a['package'], a['uid']) for a in arms.values() if a['package'] is not None}
    require(len(known) <= 1, 'CONTEXT_IME_CHANGED')
    return {'source': source, 'arms': arms, 'inputFiles': inputs}


def _metadata(rows, name):
    return [r['int_value'] for r in rows if r['name'] == name]


def _trace_quality(raw):
    stats = raw['trace-stats.csv']
    bad = [r for r in stats if r['value'] not in (None, 0) and
           (r['severity'] in ('error','data_loss') or
            re.search(r'(?i)(lost|loss|overrun|overwrite|error|discard|dropped)', r['name']))]
    # Pinned v59.1 has four severities, including "notice". Its notice counters
    # include skipped packets and setup failures; zero is valid, while nonzero
    # remains conservatively unknown without calling every notice data loss.
    notices = [r for r in stats if r['severity'] == 'notice' and r['value'] not in (None, 0)]
    metadata = raw['trace-metadata.csv']
    started = _metadata(metadata, 'all_data_source_started_ns')
    disabled = _metadata(metadata, 'tracing_disabled_ns')
    bounds = raw['trace-bounds.csv']
    reasons = []
    if len(bounds) != 1 or not integer(bounds[0]['start_ts']) or not integer(bounds[0]['end_ts']) or bounds[0]['start_ts'] >= bounds[0]['end_ts']:
        reasons.append('TRACE_BOUNDS_UNKNOWN')
    if len(started) != 1 or not integer(started[0]): reasons.append('DATA_SOURCE_READY_UNKNOWN')
    if len(disabled) != 1 or not integer(disabled[0]): reasons.append('TRACE_END_UNKNOWN')
    if not stats: reasons.append('TRACE_STATS_MISSING')
    if any(not r['name'] or r['value'] is None or r['severity'] not in ('info','notice','error','data_loss') for r in stats):
        reasons.append('TRACE_STATS_INVALID')
    if bad: reasons.append('TRACE_LOSS_OR_ERROR')
    if notices: reasons.append('TRACE_NOTICE_NONZERO')
    begin = started[0] if len(started) == 1 and integer(started[0]) else None
    end = disabled[0] if len(disabled) == 1 and integer(disabled[0]) else None
    if begin is not None and end is not None and begin >= end: reasons.append('TRACE_READY_AFTER_END')
    return {'status': 'recorded' if not reasons else 'unknown', 'reasons': reasons,
            'allDataSourcesStartedNs': begin, 'tracingDisabledNs': end,
            'bounds': bounds, 'nonzeroLossOrErrorStats': bad, 'nonzeroNoticeStats': notices,
            'noLossDoesNotProveEveryCallWasTraced': True}


def _clock(raw):
    ids = _metadata(raw['trace-metadata.csv'], 'trace_time_clock_id')
    clocks = [r for r in raw['trace-clocks.csv'] if r['clock_id'] == 6 and r['machine_id'] in (None,0)]
    good = ids == [6] and bool(clocks) and all(r['clock_name'] == 'BOOTTIME'
             and integer(r['ts']) and r['ts'] == r['clock_value'] for r in clocks)
    return {'status': 'recorded' if good else 'unknown', 'traceClockIds': ids,
            'boottimeSnapshots': clocks, 'elapsedRealtimeMapping': 'trace_ns_equals_boottime_ns' if good else None,
            'reason': None if good else 'BOOTTIME_MAPPING_NOT_PROVED'}


def _ancestors(row, lookup):
    result = []; seen = {row['id']}; current = row
    while current['parent_id'] is not None:
        parent_id = current['parent_id']
        if parent_id in seen or parent_id not in lookup or len(result) >= 128:
            return result, 'ANCESTOR_CHAIN_INCOMPLETE_OR_CYCLIC'
        parent = lookup[parent_id]; seen.add(parent_id)
        if any(parent[k] != row[k] for k in ('track_id','utid','upid','tid','pid','is_main_thread')):
            return result, 'ANCESTOR_TRACK_MISMATCH'
        if (not integer(parent['dur'], 1) or not integer(current['dur'], 1)
                or parent['ts'] > current['ts'] or parent['ts'] + parent['dur'] < current['ts'] + current['dur']
                or parent['depth'] + 1 != current['depth']):
            return result, 'ANCESTOR_INTERVAL_OR_DEPTH_INVALID'
        result.append(parent); current = parent
    return result, None if current['depth'] == 0 else 'ANCESTOR_ROOT_DEPTH_MISSING'


def derive(extractions, context):
    """Recompute from fixed CSV originals. No subprocesses or capture claims."""
    require(type(extractions) is dict and set(extractions) == set(QUERY_FILES), 'EXTRACTION_FILESET')
    raw = {name: parse_csv(name, extractions[name]) for name in QUERY_FILES}
    slices = raw['trace-slices.csv']; lookup = {}
    for row in slices:
        require(integer(row['id']) and row['id'] not in lookup and integer(row['ts'])
                and integer(row['track_id']) and integer(row['depth']) and integer(row['dur'], -1), 'SLICE_ID_OR_TIME')
        lookup[row['id']] = row
    processes = raw['trace-processes.csv']; pmap = {}
    for p in processes:
        require(integer(p['upid']) and p['upid'] not in pmap and integer(p['pid']), 'PROCESS_ID')
        for k in ('start_ts','end_ts'):
            require(p[k] is None or integer(p[k]), 'PROCESS_TIME')
        pmap[p['upid']] = p
    quality, clock = _trace_quality(raw), _clock(raw)
    known = {(a['package'], a['uid']) for a in context['arms'].values() if a['package'] is not None}
    identity_pair = next(iter(known)) if len(known) == 1 else None
    matches = [p for p in processes if identity_pair and (p['name'],p['uid']) == identity_pair and p['machine_id'] in (0,None)]
    # A second matching process instance may indicate restart/PID reuse; retain
    # all events, but do not assign a unique IME identity for causal inference.
    process = matches[0] if len(matches) == 1 else None
    events = []; unmatched = []
    for row in slices:
        if row['name'] not in TARGET_NAMES: continue
        reasons = []; p = pmap.get(row['upid'])
        if identity_pair and p is not None and (p['name'],p['uid']) != identity_pair:
            unmatched.append(row['id']); continue
        if p is None or process is None or p['upid'] != process['upid']:
            reasons.append('IME_PROCESS_INSTANCE_NOT_UNIQUE')
        if (not all(integer(row[k], 1) for k in ('tid','pid','utid','upid'))
                or row['tid'] != row['pid'] or row['is_main_thread'] != 1
                or p is None or row['pid'] != p['pid']):
            reasons.append('IME_MAIN_THREAD_NOT_PROVED')
        end = row['ts'] + row['dur'] if integer(row['dur'], 1) else None
        if end is None: reasons.append('SLICE_INCOMPLETE_OR_ZERO_DURATION')
        if p is not None and end is not None:
            if ((p['start_ts'] is not None and row['ts'] < p['start_ts'])
                    or (p['end_ts'] is not None and end > p['end_ts'])):
                reasons.append('SLICE_OUTSIDE_KNOWN_PROCESS_LIFETIME')
        ancestors, chain_error = _ancestors(row, lookup)
        if chain_error: reasons.append(chain_error)
        if p is not None and any((p['start_ts'] is not None and ancestor['ts'] < p['start_ts']) or
                                 (p['end_ts'] is not None and ancestor['ts'] + ancestor['dur'] > p['end_ts'])
                                 for ancestor in ancestors):
            reasons.append('ANCESTOR_OUTSIDE_KNOWN_PROCESS_LIFETIME')
        if quality['status'] != 'recorded': reasons.extend(quality['reasons'])
        begin, finish = quality['allDataSourcesStartedNs'], quality['tracingDisabledNs']
        if begin is None or finish is None or end is None or not begin <= row['ts'] <= end <= finish:
            reasons.append('SLICE_OUTSIDE_READY_TRACE_INTERVAL')
        bounds = quality['bounds']
        if (len(bounds) != 1 or end is None or not integer(bounds[0]['start_ts'])
                or not integer(bounds[0]['end_ts'])
                or not bounds[0]['start_ts'] <= row['ts'] <= end <= bounds[0]['end_ts']):
            reasons.append('SLICE_OUTSIDE_TRACE_BOUNDS')
        transaction = [a for a in ancestors if a['name'] == 'clientTransactionExecuted'
                       and a['ts'] <= row['ts'] and end is not None and end <= a['ts'] + a['dur']
                       and (a['ts'] < row['ts'] or end < a['ts'] + a['dur'])]
        phases = []
        for arm, c in context['arms'].items():
            item = {'arm': arm, 'phase': 'unknown', 'showOffsetNs': None, 'windowFullyCovered': None}
            if clock['status'] == 'recorded' and c['status'] == 'recorded':
                ready, frozen, show = (c[k] * 1000000 for k in ('readyElapsedMs','freezeElapsedMs','showElapsedMs'))
                item['showOffsetNs'] = row['ts'] - show
                item['phase'] = ('before_raw_window' if row['ts'] < ready else
                                 'after_raw_window' if row['ts'] > frozen else
                                 'before_show' if row['ts'] < show else 'after_show_in_raw_window')
                item['windowFullyCovered'] = bool(quality['status'] == 'recorded' and begin <= ready and frozen <= finish
                                                and bounds[0]['start_ts'] <= ready and frozen <= bounds[0]['end_ts'])
            phases.append(item)
        classification = 'configuration_dispatch_supported' if row['name'] == TARGET_NAMES[0] and transaction and not reasons else 'unknown'
        events.append({'slice': row, 'process': p, 'ancestors': ancestors,
                       'clientTransactionAncestorIds': [a['id'] for a in transaction],
                       'configChangedAncestorIds': [a['id'] for a in ancestors if a['name'] == 'configChanged'],
                       'callerClassification': classification, 'classificationReasons': list(dict.fromkeys(reasons)) or
                       ([] if classification != 'unknown' else ['NO_POSITIVE_CALLER_EVIDENCE']),
                       'rawContext': phases})
    events.sort(key=lambda e: (e['slice']['ts'], e['slice']['id']))
    resets = [e for e in events if e['slice']['name'] == TARGET_NAMES[0]]
    coverage = {}
    for arm, c in context['arms'].items():
        covered = False
        if quality['status'] == 'recorded' and clock['status'] == 'recorded' and c['status'] == 'recorded':
            ready, frozen = c['readyElapsedMs'] * 1000000, c['freezeElapsedMs'] * 1000000
            covered = (quality['allDataSourcesStartedNs'] <= ready < frozen <= quality['tracingDisabledNs']
                       and quality['bounds'][0]['start_ts'] <= ready < frozen <= quality['bounds'][0]['end_ts'])
        coverage[arm] = {'status': 'recorded' if covered else 'unknown', 'rawWindowFullyCovered': covered}
    complete = (quality['status'] == 'recorded' and clock['status'] == 'recorded' and process is not None
                and all(c['rawWindowFullyCovered'] for c in coverage.values()))
    return {'status': 'recorded' if complete else 'unknown', 'traceQuality': quality, 'clockMapping': clock,
            'imeIdentity': {'status': 'recorded' if process else 'unknown', 'matchingProcesses': matches,
                            'openLifecycleEndpointsRemainNull': True,
                            'identityBasis': 'unique trace process instance, exact package/UID, main thread; not Binder identity'},
            'armCoverage': coverage, 'events': events, 'unmatchedTargetSliceIds': unmatched, 'resetCountObserved': len(resets),
            'configurationDispatchSupportedCount': sum(e['callerClassification'] == 'configuration_dispatch_supported' for e in resets),
            'resetObservation': 'observed' if resets else 'not_observed',
            'resetAbsenceProvesNoCalls': False}


def _processor_identity():
    return {'version': perfetto.VERSION, 'bytes': perfetto.PROCESSOR_BYTES,
            'sha256': perfetto.PROCESSOR_SHA256, 'url': perfetto.PROCESSOR_URL,
            'arguments': ['--full-sort'], 'queryTimeoutSeconds': 60,
            'perQueryOutputByteLimit': QUERY_LIMIT}


def make_analysis(extractions, context, trace_bytes):
    return {'schemaVersion': 1, 'kind': KIND, 'scope': SCOPE, 'source': context['source'],
            'context': context, 'trace': identity(trace_bytes), 'processor': _processor_identity(),
            'program': {'path': PROGRAM_PATH, **identity(read_file(PROGRAM))},
            'queries': {name: {**identity(extractions[name]), 'sqlSha256': identity(sql.encode())['sha256']}
                        for name, sql in queries().items()}, **derive(extractions, context)}


def _limits():
    resource.setrlimit(resource.RLIMIT_FSIZE, (QUERY_LIMIT + 1, QUERY_LIMIT + 1))
    resource.setrlimit(resource.RLIMIT_AS, (2 * 1024**3, 2 * 1024**3))
    resource.setrlimit(resource.RLIMIT_CPU, (55, 60))


def analyze(trace_path, comparison_dir, processor_path, output_dir):
    trace_path = Path(trace_path).absolute()
    trace_bytes = read_file(trace_path, MAX_TRACE)
    require(bool(trace_bytes), 'TRACE_EMPTY')
    context = load_context(comparison_dir)
    program_bytes = read_file(PROGRAM)
    processor = perfetto.verify_processor(processor_path)
    output = Path(output_dir).absolute()
    require(not any(p.is_symlink() for p in (output, *output.parents)), 'OUTPUT_SYMLINK')
    require(not output.resolve().is_relative_to(Path(comparison_dir).resolve()), 'OUTPUT_INSIDE_COMPARISON')
    output.mkdir(parents=True, exist_ok=True)
    require(not list(output.iterdir()), 'OUTPUT_NOT_EMPTY')
    extracted = {}
    for name, sql in queries().items():
        with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
            try:
                result = subprocess.run([str(processor), '--full-sort', 'query', '--format', 'csv', str(trace_path)],
                                        input=sql.encode(), stdout=stdout, stderr=stderr, timeout=60, preexec_fn=_limits)
            except subprocess.TimeoutExpired:
                raise AnalysisError('PROCESSOR_TIMEOUT') from None
            require(result.returncode == 0, 'PROCESSOR_QUERY_FAILED')
            require(stdout.tell() <= QUERY_LIMIT and stderr.tell() <= QUERY_LIMIT, 'PROCESSOR_OUTPUT_LIMIT')
            stdout.seek(0); data = stdout.read(QUERY_LIMIT + 1)
        parse_csv(name, data)
        with (output / name).open('xb') as stream: stream.write(data)
        extracted[name] = data
    require(read_file(trace_path, MAX_TRACE) == trace_bytes, 'TRACE_CHANGED_DURING_ANALYSIS')
    require(same(context, load_context(comparison_dir)), 'CONTEXT_CHANGED_DURING_ANALYSIS')
    require(read_file(PROGRAM) == program_bytes, 'PROGRAM_CHANGED_DURING_ANALYSIS')
    perfetto.verify_processor(processor_path)
    value = make_analysis(extracted, context, trace_bytes)
    data = (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()
    require(len(data) <= QUERY_LIMIT, 'ANALYSIS_OUTPUT_LIMIT')
    with (output / 'analysis.json').open('xb') as stream: stream.write(data)
    return value


def validate_analysis(output_dir, trace_path, comparison_dir):
    output = Path(output_dir)
    require(output.is_dir() and not output.is_symlink()
            and {p.name for p in output.iterdir()} == ANALYSIS_FILES, 'ANALYSIS_FILESET')
    extracted = {name: read_file(output / name) for name in QUERY_FILES}
    actual = decode_json(read_file(output / 'analysis.json'))
    expected = make_analysis(extracted, load_context(comparison_dir), read_file(trace_path, MAX_TRACE))
    require(same(actual, expected), 'ANALYSIS_DERIVATION_MISMATCH')
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    command = sub.add_parser('analyze')
    command.add_argument('--trace', required=True, type=Path)
    command.add_argument('--comparison', required=True, type=Path)
    command.add_argument('--processor', required=True, type=Path)
    command.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        result = analyze(args.trace, args.comparison, args.processor, args.output)
        print(json.dumps({'status': result['status'], 'resetCountObserved': result['resetCountObserved'],
                          'configurationDispatchSupportedCount': result['configurationDispatchSupportedCount']}))
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(json.dumps({'status': 'unknown', 'reason': str(error)[:300]}))
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
