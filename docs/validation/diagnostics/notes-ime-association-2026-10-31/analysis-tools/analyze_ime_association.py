#!/usr/bin/env python3
"""Analyze bounded post-failure IME diagnostics without changing original outcomes.

Only archived instrumentation, report and UI results are read. No device access,
network calls, mutation of evidence, or reconstruction of original v4 samples.
"""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import sys

# Importing versioned analysis helpers must not write into their evidence tree.
sys.dont_write_bytecode = True

GEOMETRY_PATH = (Path(__file__).resolve().parents[2] /
    'notes-target-geometry-2026-10-31/analysis-tools/analyze_geometry.py')
spec = importlib.util.spec_from_file_location('strict_geometry_analysis', GEOMETRY_PATH)
geometry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(geometry)
base = geometry.base
MARKER = 'NOTES_IME_ASSOCIATION_DIAGNOSTIC_V1 '
MAX_CHARS = 16 * 1024


def extract(output, marker=MARKER, max_chars=MAX_CHARS):
    """Strict status-block binding; retain malformed/unassigned raw diagnostics.

    Binding uses the reviewed geometry analyzer's block rules. The only change
    is preserving bounded malformed payloads as explicit invalid occurrences,
    rather than letting one bad line erase all other original evidence.
    """
    base.require(type(output) is str and len(output.encode('utf-8')) <= base.MAX_PAYLOAD,
                 'invalid instrumentation output')
    occurrences, block, mode, offset = [], [], 'free', 0

    def flush(kind, complete=False):
        nonlocal block
        if not block:
            return
        methods, classes, explicit, bad = set(), set(), set(), []
        for _, _, line in block:
            found = re.match(r'INSTRUMENTATION_STATUS: class=(\S+)\s*$', line)
            if found:
                classes.add(found.group(1))
            found = re.match(r'INSTRUMENTATION_STATUS: test=(\w+)\s*$', line)
            if found:
                methods.add(found.group(1))
            found = re.search(r'(?:Error in |^\d+\) )(\w+)\(' + re.escape(base.CLASS) + r'\)', line)
            if found:
                explicit.add(found.group(1))
            found = re.search(r'\bat ' + re.escape(base.CLASS) + r'\.(\w+)\(', line)
            if found and found.group(1) in base.METHODS:
                explicit.add(found.group(1))
        known = methods | explicit
        if methods - set(base.METHODS):
            bad.append('unknown test method in this block')
        if classes and classes != {base.CLASS}:
            bad.append('class field differs within this block')
        if kind == 'status' and not complete:
            bad.append('unterminated instrumentation status block')
        if len(known) != 1:
            bad.append('no unique explicit method in this block')
        if not explicit and classes != {base.CLASS}:
            bad.append('test field lacks matching class or explicit current stack')
        method = next(iter(known)) if len(known) == 1 and not bad else None
        if method not in base.METHODS:
            method = None
        for line_number, line_offset, line in block:
            position = line.find(marker)
            if position < 0:
                continue
            raw = line[position + len(marker):].rstrip('\r\n')
            parse_issues = []
            value = None
            if line.count(marker) != 1:
                parse_issues.append('multiple diagnostic markers in one line')
            if len(raw) + len(marker) > max_chars:
                parse_issues.append('oversized diagnostic message')
            if not raw.isascii():
                parse_issues.append('diagnostic message is not ASCII')
            if not parse_issues:
                try:
                    value, consumed = base.DECODER.raw_decode(raw)
                    if raw[consumed:].strip() or type(value) is not dict:
                        parse_issues.append('unexpected diagnostic payload or suffix')
                        value = None
                except (ValueError, RecursionError):
                    parse_issues.append('invalid diagnostic JSON or duplicate key')
            occurrences.append({
                'method': method, 'bindingStatus': 'bound' if method is not None else 'unassigned',
                'bindingScope': kind, 'bindingIssues': list(bad), 'line': line_number,
                'characterOffset': line_offset + position,
                'rawJson': raw[:max_chars - len(marker)], 'rawJsonChars': len(raw),
                'rawJsonRetainedComplete': len(raw) + len(marker) <= max_chars,
                'rawJsonSha256': base.digest(raw.encode('utf-8')),
                'parseStatus': 'invalid' if parse_issues else 'parsed',
                'parseIssues': parse_issues, 'value': value})
        block = []

    for number, line in enumerate(output.splitlines(keepends=True), 1):
        if line.startswith('INSTRUMENTATION_STATUS_CODE:'):
            flush(mode, True)
            mode = 'free'
        elif line.startswith('INSTRUMENTATION_RESULT:') or line.startswith('INSTRUMENTATION_CODE:'):
            flush(mode, False)
            mode = 'summary'
            block.append((number, offset, line))
        elif line.startswith('INSTRUMENTATION_STATUS:'):
            if mode != 'status':
                flush(mode, False)
                mode = 'status'
            if line.startswith('INSTRUMENTATION_STATUS: class=') and any(
                    item[2].startswith('INSTRUMENTATION_STATUS: class=') for item in block):
                flush(mode, False)
            block.append((number, offset, line))
        else:
            if mode != 'status' and re.search(r'(?:Error in |^\d+\) )(\w+)\(' + re.escape(base.CLASS) + r'\)', line):
                flush(mode, False)
                mode = 'summary'
            block.append((number, offset, line))
        offset += len(line)
    flush(mode, False)
    return occurrences


def unique_for_case(occurrences):
    """Duplicate printing is deduplicated only inside one explicitly bound case."""
    return {item['rawJsonSha256']: item for item in occurrences}


def safe_confirmation_analysis(value, proof, validator=base.analyze_diagnostic):
    """Keep malformed diagnostic evidence without swallowing source/hash errors."""
    try:
        return validator(value, proof)
    except (ValueError, TypeError, KeyError, RecursionError):
        return {'availability': 'invalid_schema', 'consistencyIssues': [
            'original confirmation diagnostic has invalid schema; raw occurrence retained without reconstruction']}


def original_freeze(prior, matching):
    values = [o['value'].get('frozenElapsedMillis') for o in matching]
    valid = (prior.get('availability') == 'retained_prefix' and not prior.get('consistencyIssues', [])
        and len({o['rawJsonSha256'] for o in matching}) == 1
        and bool(values) and all(integer(value) for value in values)
        and all(value == values[0] for value in values))
    return values[0] if valid else None


def integer(value, minimum=0, maximum=2**63 - 1):
    return type(value) is int and minimum <= value <= maximum


def binder(value):
    return type(value) is str and re.fullmatch(r'android\.os\.Binder(?:Proxy)?@[0-9a-fA-F]{1,16}', value) is not None


def rectangle(value):
    return type(value) is list and len(value) == 4 and all(integer(v, -2**31, 2**31-1) for v in value)


def region(value):
    return type(value) is str and len(value) <= 512 and re.fullmatch(r'SkRegion\([0-9() ,;-]*\)', value) is not None


COMMANDS = [('WMS_WINDOWS', 'dumpsys -t 2 window -a windows'),
            ('WMS_A11Y', 'dumpsys -t 2 window a11y'),
            ('ACCESSIBILITY', 'dumpsys -t 2 accessibility')]
COMMAND_KEYS = {'ordinal', 'id', 'command', 'startedElapsedMillis', 'endedElapsedMillis', 'status',
                'exitStatus', 'outputBytes', 'outputSha256', 'hashScope', 'outputComplete', 'parsed'}
COMMAND_STATUSES = {'COMPLETE', 'BYTE_LIMIT', 'TIMEOUT', 'INTERRUPTED', 'SERVICE_TIMEOUT',
    'PERMISSION_DENIED', 'COMMAND_UNAVAILABLE', 'READ_FAILED', 'SKIPPED_AFTER_TIMEOUT', 'SKIPPED_COLLECTOR_BUSY'}
TOP_KEYS = {'schemaVersion', 'phase', 'clock', 'startedElapsedMillis', 'endedElapsedMillis',
    'perCommandTimeoutMillis', 'totalTimeoutMillis', 'maxOutputBytesPerCommand', 'configuredIme',
    'commands', 'association', 'diagnosticTruncated'}
WMS_KEYS = {'status', 'reason', 'referenceCount', 'inputMethodRecordCount', 'selectedWindowStateToken',
           'ownerUid', 'displayId', 'ownerMatches', 'displayMatches', 'packageMatches', 'mClient', 'fields'}
WMS_ATTRIBUTE_KEYS = {'attributesParsedCount', 'attributesUnparsedCount'}
WMS_ATTRIBUTE_DETAIL_KEYS = {'attrsDiagnostics'}
ATTRS_REASONS = {'ATTRS_ANCHOR_MISSING', 'ATTRS_ANCHOR_DUPLICATE', 'DEPTH_LIMIT',
    'TYPE_VALUE_UNRECOGNIZED', 'TYPE_VALUE_FOLLOWED_BY_OPEN_BRACE', 'TOP_LEVEL_TYPE_MISSING',
    'TOP_LEVEL_TYPE_DUPLICATE', 'ATTRS_SIZE_LIMIT', 'ATTRS_UNCLOSED'}
ATTRS_LEXICAL_CLASSES = {'EMPTY', 'DECIMAL', 'SIGNED_DECIMAL', 'HEX', 'UPPERCASE_SYMBOL', 'OTHER'}
ATTRS_TAIL_KINDS = {'HIDING_SYSTEM_ALERT_WINDOWS', 'FORCE_REMOVING_WINDOWS',
                    'DESTROY_SURFACE_WINDOWS', 'RESIZING_WINDOWS'}
ATTRS_ENTRY_KEYS = {'recordOrdinal', 'recordEnd', 'headerRelation', 'attrsAnchorCount',
    'firstAttrsOffset', 'secondAttrsOffset', 'recordChars', 'scanChars', 'maxDepth',
    'topLevelTypeCount', 'reason', 'typeLexicalClass', 'typeValueLength', 'typeValueComplete',
    'firstKnownTailSection', 'knownTailSectionBetweenAnchors'}
WMS_REASONS = {'SOURCE_UNAVAILABLE', 'WMS_HEADER_UNRECOGNIZED', 'WMS_REFERENCE_MISSING_OR_AMBIGUOUS',
    'WMS_RECORD_MISSING_OR_AMBIGUOUS', 'WMS_IDENTITY_MISMATCH', 'WMS_CLIENT_UNRECOGNIZED',
    'WMS_CLIENT_AMBIGUOUS', 'WMS_ATTRIBUTES_INCOMPLETE', 'UNIQUE_CONFIGURED_IME_TEXT_CANDIDATE'}
POPULATOR_REASONS = {'SOURCE_UNAVAILABLE', 'POPULATOR_HEADER_UNRECOGNIZED',
    'POPULATOR_NOTIFICATION_UNRECOGNIZED', 'POPULATOR_NOTIFICATION_AMBIGUOUS',
    'POPULATOR_VISIBLE_AMBIGUOUS', 'POPULATOR_VISIBLE_UNRECOGNIZED', 'POPULATOR_DISPLAYS_AMBIGUOUS',
    'POPULATOR_DISPLAYS_UNRECOGNIZED', 'POPULATOR_LIST_INCOMPLETE', 'FILTERED_LISTS_PARSED'}
ACCESSIBILITY_REASONS = {'SOURCE_UNAVAILABLE', 'ACCESSIBILITY_PAIRED_FORMAT_UNRECOGNIZED',
    'ACCESSIBILITY_PAIRS_INCOMPLETE_OR_LEGACY', 'REPORTED_WINDOW_PAIRS_PARSED'}
ASSOCIATION_KEYS = {'status', 'reason', 'systemServerLifetime', 'atomicSnapshot', 'key', 'tokenCandidate',
                   'populatorVisibleMatchCount', 'populatorDisplayMatchCount', 'finalMatchCount'}


def derive(value):
    try:
        return _derive(value)
    except (ValueError, TypeError, KeyError, AttributeError, OverflowError, RecursionError):
        return {'availability': 'invalid_schema', 'consistencyIssues': [
            'diagnostic contains incompatible field types; raw occurrence retained without reconstruction']}


def _derive(value):
    issues = []
    def check(condition, message):
        if not condition:
            issues.append(message)
    if type(value) is not dict:
        return {'availability': 'invalid_schema', 'consistencyIssues': ['diagnostic is not an object']}
    for flag, availability in [('diagnosticSerializationFailed', 'serialization_failed'),
                               ('diagnosticIdentityUnavailable', 'identity_unavailable')]:
        if value.get(flag) is True:
            return {'availability': availability, 'consistencyIssues': [] if set(value) == {flag} else ['fallback has unexpected fields']}
    if value.get('diagnosticTruncated') is True:
        n = value.get('untruncatedChars')
        return {'availability': 'truncated', 'untruncatedChars': n,
            'consistencyIssues': [] if set(value) == {'diagnosticTruncated', 'untruncatedChars'} and integer(n, MAX_CHARS+1) else ['truncation fallback has unexpected fields or length']}
    check(set(value) == TOP_KEYS, 'unexpected top-level association diagnostic fields')
    check(type(value.get('schemaVersion')) is int and value['schemaVersion'] == 1,
          'unknown diagnostic schema version')
    check(value.get('phase') == 'POST_FAILURE_BEFORE_CAPTURE_CLEANUP' and value.get('clock') == 'SystemClock.elapsedRealtime'
          and value.get('diagnosticTruncated') is False, 'phase/clock/truncation differs')
    for key, expected in [('perCommandTimeoutMillis', 3000), ('totalTimeoutMillis', 9000), ('maxOutputBytesPerCommand', 262144)]:
        check(type(value.get(key)) is int and value[key] == expected, key + ' differs from frozen quota')
    started, ended = value.get('startedElapsedMillis'), value.get('endedElapsedMillis')
    times_valid = integer(started) and integer(ended) and started <= ended
    check(times_valid, 'collector monotonic times are unavailable/reversed')
    identity = value.get('configuredIme')
    if type(identity) is not dict:
        identity = {}
    check(set(identity) == {'ownerUid', 'packageName', 'component', 'identitySource'}, 'configured IME shape differs')
    check(integer(identity.get('ownerUid'), 0, 2**31 - 1), 'configured IME owner UID invalid')
    package, component = identity.get('packageName'), identity.get('component')
    identity_names = type(package) is str and type(component) is str and re.fullmatch(r'[A-Za-z0-9_.]{1,180}/[A-Za-z0-9_.$]{1,180}', component) is not None
    check(identity_names and component.split('/')[0] == package, 'configured IME component/package differs')
    check(identity.get('identitySource') == 'FROZEN_CONFIRMATION', 'configured identity was not frozen confirmation identity')
    commands = value.get('commands')
    if type(commands) is not list or len(commands) != 3:
        return {'availability': 'invalid_schema', 'consistencyIssues': issues + ['exactly three command receipts required']}
    previous_end = started if times_valid else 0
    stopped = None
    parsed_list, observed_commands = [], []
    for index, command in enumerate(commands):
        label = COMMANDS[index][0]
        def ccheck(condition, message):
            check(condition, label + ': ' + message)
        if type(command) is not dict:
            issues.append(label + ': command is not object')
            parsed_list.append({})
            continue
        ccheck(set(command) == COMMAND_KEYS, 'unexpected command fields')
        ccheck(type(command.get('ordinal')) is int and command['ordinal'] == index + 1
               and (command.get('id'), command.get('command')) == COMMANDS[index], 'command identity/order differs')
        status = command.get('status')
        ccheck(status in COMMAND_STATUSES, 'unknown command status')
        ccheck(command.get('exitStatus') == 'NOT_EXPOSED', 'shell exit status was inferred')
        begin, end = command.get('startedElapsedMillis'), command.get('endedElapsedMillis')
        skipped = status in {'SKIPPED_AFTER_TIMEOUT', 'SKIPPED_COLLECTOR_BUSY'}
        if skipped:
            ccheck(begin is None and end is None, 'skipped command has read times')
        else:
            valid = integer(begin) and integer(end) and previous_end <= begin <= end and (not times_valid or end <= ended)
            ccheck(valid, 'command read times unavailable/reversed/outside collector ordering')
            if integer(end):
                previous_end = end
        if stopped is not None:
            ccheck(status == stopped, 'read continued after timeout/interruption/busy')
        if status in {'TIMEOUT', 'SERVICE_TIMEOUT', 'INTERRUPTED'}:
            stopped = 'SKIPPED_AFTER_TIMEOUT'
        elif status == 'SKIPPED_COLLECTOR_BUSY':
            stopped = 'SKIPPED_COLLECTOR_BUSY'
        elif status == 'SKIPPED_AFTER_TIMEOUT':
            stopped = status
        n, sha, scope, complete = (command.get(k) for k in ['outputBytes', 'outputSha256', 'hashScope', 'outputComplete'])
        ccheck(integer(n, 0, 262144), 'retained output byte count outside cap')
        ccheck(type(complete) is bool, 'output completeness is not boolean')
        if status in {'COMPLETE', 'SERVICE_TIMEOUT', 'PERMISSION_DENIED', 'COMMAND_UNAVAILABLE'}:
            ccheck(complete is True and scope == 'FULL_OUTPUT', 'EOF/service result lacks complete output fingerprint')
        elif status == 'BYTE_LIMIT':
            ccheck(complete is False and scope == 'RETAINED_PREFIX' and n == 262144, 'byte limit receipt differs')
        elif status in COMMAND_STATUSES:
            ccheck(complete is False and scope == 'UNAVAILABLE' and sha is None, 'unfinished/skipped command has inferred full fingerprint')
            if skipped:
                ccheck(n == 0, 'skipped command retained bytes')
        if scope in {'FULL_OUTPUT', 'RETAINED_PREFIX'}:
            ccheck(type(sha) is str and re.fullmatch('[0-9a-f]{64}', sha) is not None, 'output fingerprint is not SHA-256')
        else:
            ccheck(scope == 'UNAVAILABLE' and sha is None, 'unknown output hash scope')
        parsed = command.get('parsed')
        if type(parsed) is not dict:
            ccheck(False, 'parsed receipt is not object')
            parsed = {}
        parsed_list.append(parsed)
        if status != 'COMPLETE':
            ccheck(parsed == {'status': 'UNAVAILABLE', 'reason': 'SOURCE_UNAVAILABLE'}, 'incomplete/error source supplied parsed association fields')
        if index == 0:
            validate_wms(parsed, identity, ccheck)
        elif index == 1:
            validate_populator(parsed, ccheck)
        else:
            validate_accessibility(parsed, ccheck)
        observed_commands.append({k: command.get(k) for k in ['ordinal', 'id', 'status', 'startedElapsedMillis', 'endedElapsedMillis', 'outputBytes', 'outputComplete', 'hashScope']})
    association = value.get('association')
    if type(association) is not dict:
        association = {}
    check(set(association) == ASSOCIATION_KEYS, 'association field set differs')
    check(association.get('status') in {'INCONCLUSIVE', 'TEXT_CANDIDATE_ONLY'} and association.get('systemServerLifetime') == 'NOT_VERIFIED'
          and association.get('atomicSnapshot') is False and association.get('key') == 'WMS_MCLIENT_TO_WINDOW_TOKEN',
          'association overclaims strong identity/lifecycle/atomicity or uses a different key')
    wms, pop, final = parsed_list
    token = wms.get('mClient') if wms.get('status') == 'SELECTED' and commands[0].get('status') == 'COMPLETE' else None
    check(association.get('tokenCandidate') == token, 'candidate does not equal selected WMS mClient')
    visible = pop.get('visible') if type(pop.get('visible')) is dict else {}
    displays = pop.get('displays') if type(pop.get('displays')) is dict else {}
    ready = token is not None and pop.get('status') == 'RECOGNIZED' and final.get('status') == 'RECOGNIZED'
    counts = [visible.get('matchCount'), displays.get('matchCount'), final.get('matchCount')]
    for key, expected in zip(['populatorVisibleMatchCount', 'populatorDisplayMatchCount', 'finalMatchCount'], counts):
        check(association.get(key) == (expected if ready else None), 'association summary ' + key + ' differs from independently complete source fields')
    attributes = all(type(row) is dict and row.get('displayId') == 0
        and (index != 2 or row.get('type') == 2011)
        for index, group in enumerate([visible, displays, final]) for row in group.get('matches', []))
    unique = all(integer(count, 0, 1) for count in counts)
    unambiguous = ready and unique and attributes
    check(association.get('status') == ('TEXT_CANDIDATE_ONLY' if unambiguous else 'INCONCLUSIVE'),
          'association status differs from bounded candidate multiplicity')
    reason = ('SOURCE_UNAVAILABLE' if not ready else 'CANDIDATE_ATTRIBUTES_MISMATCH' if not attributes
        else 'CANDIDATE_NOT_UNIQUE' if not unique else 'ORDERED_TEXT_COUNTS_ONLY')
    check(association.get('reason') == reason, 'association reason differs from independent fields')
    for group in [visible, displays, final]:
        matches = group.get('matches')
        if type(matches) is list:
            check(all(type(row) is dict and row.get('token') == token for row in matches), 'retained match does not equal selected mClient')
            if token is None:
                check(group.get('matchCount') is None and not matches, 'missing candidate was converted to an absent token')
    observations = {'wmsSelected': wms.get('status') == 'SELECTED', 'tokenCandidate': token,
        'populatorVisible': list_observation(visible, pop.get('status') == 'RECOGNIZED' and visible.get('complete') is True, token),
        'populatorDisplays': list_observation(displays, pop.get('status') == 'RECOGNIZED' and displays.get('complete') is True, token),
        'finalService': list_observation(final, final.get('status') == 'RECOGNIZED', token)}
    if issues:
        observations = {'wmsSelected': None, 'tokenCandidate': None,
            **{key: {'availability': 'invalid_schema', 'matchCount': None}
               for key in ['populatorVisible', 'populatorDisplays', 'finalService']}}
    return {'availability': 'complete' if not issues else 'invalid_schema', 'configuredIme': identity,
        'interpretationAllowed': not issues,
        'commandObservations': observed_commands, 'postFailureObservations': observations,
        'association': association, 'collectorDurationMillis': ended-started if times_valid else None,
        'collectorObservedOverBudgetMillis': max(0, ended-started-9000) if times_valid else None,
        'consistencyIssues': issues}


def validate_wms(parsed, identity, check):
    check(set(parsed) <= WMS_KEYS | WMS_ATTRIBUTE_KEYS | WMS_ATTRIBUTE_DETAIL_KEYS and {'status', 'reason'} <= set(parsed), 'unexpected WMS fields')
    check(parsed.get('status') in {'SELECTED', 'UNAVAILABLE'} and parsed.get('reason') in WMS_REASONS, 'unknown WMS parse status/reason')
    attribute_keys = set(parsed) & WMS_ATTRIBUTE_KEYS
    check(not attribute_keys or attribute_keys == WMS_ATTRIBUTE_KEYS, 'WMS attribute accounting requires both counters')
    for key in attribute_keys:
        check(integer(parsed[key], 0, 2**31-1), key + ' invalid')
    if attribute_keys == WMS_ATTRIBUTE_KEYS:
        understood, unparsed = parsed['attributesParsedCount'], parsed['attributesUnparsedCount']
        if integer(understood) and integer(parsed.get('inputMethodRecordCount')):
            check(parsed['inputMethodRecordCount'] <= understood, 'IME record count exceeds successfully parsed attribute records')
        if integer(unparsed):
            check((unparsed > 0) == (parsed.get('reason') == 'WMS_ATTRIBUTES_INCOMPLETE'),
                  'WMS attribute failure count/reason mismatch')
            if unparsed > 0:
                check(parsed.get('status') == 'UNAVAILABLE' and 'mClient' not in parsed and 'fields' not in parsed,
                      'unparsed WMS attributes supplied a selected candidate')
    if parsed.get('reason') == 'WMS_ATTRIBUTES_INCOMPLETE':
        check(attribute_keys == WMS_ATTRIBUTE_KEYS and integer(parsed.get('attributesUnparsedCount'), 1),
              'WMS incomplete attributes lack explicit positive failure count')
    if 'attrsDiagnostics' in parsed:
        check(attribute_keys == WMS_ATTRIBUTE_KEYS, 'WMS attribute details lack original paired counters')
        validate_attrs_diagnostics(parsed['attrsDiagnostics'], parsed, check)
    for key in ['referenceCount', 'inputMethodRecordCount', 'ownerUid', 'displayId']:
        if key in parsed:
            check(integer(parsed[key], 0, 2**31-1), key + ' invalid')
    for key in ['ownerMatches', 'displayMatches', 'packageMatches']:
        if key in parsed:
            check(type(parsed[key]) is bool, key + ' invalid')
    if 'selectedWindowStateToken' in parsed:
        token = parsed['selectedWindowStateToken']
        check(type(token) is str and re.fullmatch('[0-9a-fA-F]{1,32}', token) is not None, 'WindowState token invalid')
    if 'mClient' in parsed:
        check(binder(parsed['mClient']), 'mClient is not bounded Java binder text')
    if parsed.get('status') == 'SELECTED':
        check(set(parsed) - WMS_ATTRIBUTE_KEYS - WMS_ATTRIBUTE_DETAIL_KEYS == WMS_KEYS, 'selected WMS record lacks exact selected fields')
        check(parsed.get('reason') == 'UNIQUE_CONFIGURED_IME_TEXT_CANDIDATE' and parsed.get('referenceCount') == 1 and parsed.get('inputMethodRecordCount') == 1,
              'WMS selection not uniquely justified')
        check(all(parsed.get(key) is True for key in ['ownerMatches', 'displayMatches', 'packageMatches'])
              and parsed.get('ownerUid') == identity.get('ownerUid') and parsed.get('displayId') == 0, 'selected WMS identity mismatch')
    fields = parsed.get('fields')
    if fields is not None:
        check(type(fields) is dict and set(fields) == set(base.FIELDS), 'WMS visibility field set differs')
        if type(fields) is dict:
            for item in fields.values():
                valid = type(item) is dict and set(item) == {'count', 'value'} and integer(item.get('count')) and item.get('value') in base.VALUES
                check(valid, 'invalid WMS visibility observation')
                if valid:
                    check((item['count'] == 0) == (item['value'] == 'MISSING'), 'WMS missing field/count mismatch')


def validate_attrs_diagnostics(value, wms, check):
    """Validate bounded structural metadata; never infer ownership or relax selection."""
    expected = {'schemaVersion', 'maxRetained', 'recordCount', 'unparsedCount', 'omittedCount', 'entries'}
    check(type(value) is dict and set(value) == expected, 'attribute details have unexpected fields')
    if type(value) is not dict:
        return
    check(type(value.get('schemaVersion')) is int and value['schemaVersion'] == 1,
          'attribute details schema version differs')
    check(type(value.get('maxRetained')) is int and value['maxRetained'] == 8,
          'attribute retained detail cap differs')
    for key in ['recordCount', 'unparsedCount', 'omittedCount']:
        check(integer(value.get(key), 0, 262144), 'attribute ' + key + ' is not a bounded count')
    records, failures, omitted = (value.get(k) for k in ['recordCount', 'unparsedCount', 'omittedCount'])
    parsed, unparsed = wms.get('attributesParsedCount'), wms.get('attributesUnparsedCount')
    if all(integer(n, 0, 262144) for n in [records, failures, omitted, parsed, unparsed]):
        check(records == parsed + unparsed and failures == unparsed,
              'attribute record/failure accounting differs from original counters')
    entries = value.get('entries')
    check(type(entries) is list and len(entries) <= 8, 'attribute details exceed retained cap or are not a list')
    if type(entries) is not list:
        return
    if integer(failures):
        check(len(entries) == min(failures, 8) and omitted == failures - len(entries),
              'attribute retained prefix/omitted count mismatch')
    previous = 0
    for entry in entries:
        check(type(entry) is dict and set(entry) == ATTRS_ENTRY_KEYS, 'unexpected attribute failure entry fields')
        if type(entry) is not dict:
            continue
        ordinal, chars = entry.get('recordOrdinal'), entry.get('recordChars')
        check(integer(ordinal, 1, records if integer(records) else 0) and ordinal > previous,
              'attribute failure ordinals are missing, duplicated, reordered or outside record count')
        if integer(ordinal):
            previous = ordinal
        check(entry.get('recordEnd') == ('DUMP_END' if ordinal == records else 'NEXT_WINDOW_HEADER'),
              'attribute record boundary differs from ordinal/count')
        check(entry.get('headerRelation') in {'MATCHES_SELECTED_REFERENCE', 'OTHER_RECOGNIZED_WINDOW', 'UNRECOGNIZED'},
              'unknown attribute header text relation')
        check(integer(chars, 1, 262144), 'attribute record UTF16 length outside retained output bound')
        anchors, first, second, scan, depth, types = (entry.get(k) for k in [
            'attrsAnchorCount', 'firstAttrsOffset', 'secondAttrsOffset', 'scanChars', 'maxDepth', 'topLevelTypeCount'])
        check(integer(anchors, 0, chars if integer(chars) else 0), 'attribute anchor count invalid')
        for key, offset in [('first', first), ('second', second)]:
            check(offset is None or integer(offset, 0, chars-1 if integer(chars) else -1), key + ' attribute offset outside record')
        check(integer(scan, 0, 8192) and integer(depth, 0, 9) and integer(types, 0, 8192),
              'attribute scan/depth/type counters outside fixed bounds')
        if all(integer(n) for n in [scan, depth, types]):
            check(depth <= scan and types <= scan, 'attribute depth/type counters exceed observed outer cursor prefix')
        reason = entry.get('reason')
        check(reason in ATTRS_REASONS, 'unknown attribute rejection reason')
        if integer(anchors):
            check((first is None) == (anchors == 0) and (second is None) == (anchors < 2),
                  'attribute anchor offsets do not match anchor count')
            if anchors >= 2 and integer(first) and integer(second):
                check(first < second, 'first two attribute offsets are not ordered')
            if anchors != 1:
                check(reason == ('ATTRS_ANCHOR_MISSING' if anchors == 0 else 'ATTRS_ANCHOR_DUPLICATE')
                      and scan == 0 and depth == 0 and types == 0, 'early anchor refusal has inferred scan metadata')
            else:
                check(reason not in {'ATTRS_ANCHOR_MISSING', 'ATTRS_ANCHOR_DUPLICATE'}, 'one anchor cannot have missing/duplicate reason')
                check(integer(scan, 1, 8192) and integer(depth, 1, 9), 'scanned attribute record lacks opening-brace progress')
                if integer(first) and integer(chars) and integer(scan):
                    check(scan <= chars-first, 'outer cursor scan exceeds remaining record characters')
                    if reason == 'ATTRS_SIZE_LIMIT':
                        check(chars-first >= 8192, 'size limit without at least8192 available codeunits')
                    if reason == 'ATTRS_UNCLOSED':
                        check(chars-first < 8192, 'unclosed reason exceeds size-limit classification')
        if reason == 'DEPTH_LIMIT':
            check(depth == 9, 'depth limit did not reach first disallowed depth')
        elif integer(depth):
            check(depth <= 8, 'non-depth refusal exceeded depth limit')
        if reason == 'TOP_LEVEL_TYPE_MISSING':
            check(types == 0, 'missing top-level type has an observed type')
        elif reason == 'TOP_LEVEL_TYPE_DUPLICATE':
            check(integer(types, 2, 8192), 'duplicate top-level type lacks two observed fields')
        lexical, length, complete = (entry.get(k) for k in ['typeLexicalClass', 'typeValueLength', 'typeValueComplete'])
        if reason in {'TYPE_VALUE_UNRECOGNIZED', 'TYPE_VALUE_FOLLOWED_BY_OPEN_BRACE'}:
            check(lexical in ATTRS_LEXICAL_CLASSES and integer(length, 0, 65) and type(complete) is bool,
                  'invalid bounded type-prefix metadata')
            check(integer(types, 1, 8192), 'type-value refusal lacks encountered top-level type field')
            check((lexical == 'EMPTY') == (length == 0), 'empty type-prefix class/length mismatch')
            if lexical == 'SIGNED_DECIMAL':
                check(integer(length, 2, 65), 'signed decimal prefix too short')
            if lexical == 'HEX':
                check(integer(length, 3, 65), 'hex prefix too short')
            if complete is False:
                check(length == 65, 'incomplete type prefix did not reach fixed lookahead cap')
            if reason == 'TYPE_VALUE_FOLLOWED_BY_OPEN_BRACE':
                check(integer(length, 1, 64) and complete is True,
                      'brace-followed recognized type has incomplete/empty/overlong value')
                check(lexical != 'SIGNED_DECIMAL', 'signed type value cannot reach recognized-type brace refusal')
            elif lexical in {'DECIMAL', 'UPPERCASE_SYMBOL'} and integer(length, 1, 64):
                check(False, 'unrecognized type contradicts a bounded class accepted by the original parser')
        else:
            check(lexical is None and length is None and complete is None,
                  'non-type refusal contains type contents or inferred lexical metadata')
        tail, between = entry.get('firstKnownTailSection'), entry.get('knownTailSectionBetweenAnchors')
        tail_valid = (type(tail) is dict and set(tail) == {'kind', 'offset'} and tail.get('kind') in ATTRS_TAIL_KINDS
            and integer(tail.get('offset'), 0, chars-1 if integer(chars) else -1))
        check(tail is None or tail_valid, 'unknown/nonstructural tail heading or invalid offset')
        if integer(anchors) and anchors < 2:
            check(between is None, 'fewer than two anchors has inferred between-anchor heading result')
        elif integer(anchors):
            check(type(between) is bool, 'between-anchor heading result is not boolean')
            if tail is None:
                check(between is False, 'between-anchor heading true without any known heading')
            elif tail_valid and integer(first) and integer(second):
                offset = tail['offset']
                if first < offset < second:
                    check(between is True, 'known first heading between anchors was ignored')
                elif offset >= second:
                    check(between is False, 'first heading after second anchor cannot establish a heading between')
                # When first heading precedes first anchor, a later heading may be
                # between anchors. The first-only receipt cannot infer absence.


def validate_matches(group, keys, count, check):
    rows = group.get('matches')
    check(type(rows) is list and len(rows) <= 4, 'matches exceed retained prefix limit or are not a list')
    if type(rows) is not list:
        return
    if integer(count):
        check(len(rows) == min(count, 4), 'retained match prefix/count mismatch')
    for row in rows:
        check(type(row) is dict and set(row) == keys, 'match row shape differs')
        if type(row) is not dict:
            continue
        check(binder(row.get('token')), 'match token invalid')
        for key in ['displayId', 'type', 'layer', 'accessibilityWindowId']:
            if key in keys:
                check(integer(row.get(key), -2**31, 2**31-1), key + ' invalid')
        for key in ['frame', 'bounds']:
            if key in keys:
                check(row.get(key) is None or rectangle(row[key]), key + ' invalid')
                if group.get('complete') is True or group.get('status') == 'RECOGNIZED':
                    check(rectangle(row.get(key)), 'complete match has missing ' + key)
        for key in ['touchableRegion', 'region']:
            if key in keys:
                check(row.get(key) is None or region(row[key]), key + ' invalid')
                if group.get('complete') is True or group.get('status') == 'RECOGNIZED':
                    check(region(row.get(key)), 'complete match has missing ' + key)
        if 'isClone' in keys:
            check(type(row.get('isClone')) is bool, 'clone flag invalid')
        if 'parent' in keys:
            check(row.get('parent') is None or binder(row['parent']), 'parent token invalid')
        if 'transform' in keys:
            transform = row.get('transform')
            valid = type(transform) is str and len(transform) <= 512 and (transform == 'null'
                or re.fullmatch(r'Matrix\{[0-9eE+., \[\]()-]*\}', transform) is not None)
            check(transform is None or valid, 'transform is not a bounded numeric Matrix/null text')
            if group.get('complete') is True:
                check(valid, 'complete match has missing transform')
        for key in ['scaleFactor', 'alpha']:
            if key in keys:
                number = row.get(key)
                valid = type(number) in (int, float) and math.isfinite(number)
                check(number is None or valid, key + ' is not finite numeric/null')
                if group.get('complete') is True:
                    check(valid, 'complete match has missing ' + key)


def validate_populator(parsed, check):
    check(set(parsed) <= {'status', 'reason', 'notificationsEnabled', 'visible', 'displays'} and {'status', 'reason'} <= set(parsed), 'unexpected Populator fields')
    check(parsed.get('status') in {'RECOGNIZED', 'UNAVAILABLE'} and parsed.get('reason') in POPULATOR_REASONS, 'unknown Populator status/reason')
    if 'notificationsEnabled' in parsed:
        check(type(parsed['notificationsEnabled']) is bool, 'Populator notifications flag invalid')
    for key, expected in [('visible', {'declaredCount', 'parsedCount', 'tokenFieldCount', 'complete', 'matchCount', 'matches'}),
                          ('displays', {'declaredDisplayCount', 'parsedDisplayCount', 'complete', 'matchCount', 'matches'})]:
        if key not in parsed:
            check(parsed.get('status') != 'RECOGNIZED', 'recognized Populator lacks ' + key)
            continue
        group = parsed[key]
        check(type(group) is dict and set(group) == expected, 'Populator ' + key + ' shape differs')
        if type(group) is not dict:
            continue
        check(type(group.get('complete')) is bool, 'Populator completeness invalid')
        for field in expected - {'complete', 'matchCount', 'matches'}:
            check(integer(group.get(field), 0, 2**31-1), 'Populator ' + field + ' invalid')
        count = group.get('matchCount')
        check(count is None or integer(count), 'Populator match count invalid')
        if group.get('complete') is not True:
            check(count is None, 'incomplete Populator list converted to observed count')
        if parsed.get('status') == 'RECOGNIZED':
            check(group.get('complete') is True, 'recognized Populator list is incomplete')
        if group.get('complete') is True and key == 'visible':
            check(group.get('declaredCount') == group.get('parsedCount') == group.get('tokenFieldCount') and integer(group.get('parsedCount'), 0, 256), 'complete visible handle accounting differs')
            if integer(count) and integer(group.get('parsedCount')):
                check(count <= group['parsedCount'], 'visible match count exceeds parsed handles')
        if group.get('complete') is True and key == 'displays':
            check(group.get('declaredDisplayCount') == group.get('parsedDisplayCount') and integer(group.get('parsedDisplayCount'), 0, 32), 'complete display accounting differs')
        validate_matches(group, {'token', 'displayId', 'frame', 'touchableRegion', 'isClone', 'transform', 'scaleFactor', 'alpha'}, count, check)
    if parsed.get('status') == 'RECOGNIZED':
        check(set(parsed) == {'status', 'reason', 'notificationsEnabled', 'visible', 'displays'}, 'recognized Populator lacks complete field set')
        check(parsed.get('reason') == 'FILTERED_LISTS_PARSED', 'recognized Populator reason differs')


def validate_accessibility(parsed, check):
    keys = {'status', 'reason', 'a11yWindowCount', 'windowInfoCount', 'pairCount', 'matchCount', 'matches'}
    check(set(parsed) <= keys and {'status', 'reason'} <= set(parsed), 'unexpected final accessibility fields')
    check(parsed.get('status') in {'RECOGNIZED', 'UNAVAILABLE'} and parsed.get('reason') in ACCESSIBILITY_REASONS, 'unknown accessibility status/reason')
    for key in ['a11yWindowCount', 'windowInfoCount', 'pairCount']:
        if key in parsed:
            check(integer(parsed[key], 0, 2**31-1), 'final window count invalid')
    count = parsed.get('matchCount')
    check(count is None or integer(count), 'final match count invalid')
    if parsed.get('status') != 'RECOGNIZED':
        check(count is None, 'unavailable final format converted to observed count')
    else:
        check(set(parsed) == keys and parsed.get('reason') == 'REPORTED_WINDOW_PAIRS_PARSED'
              and parsed.get('a11yWindowCount') == parsed.get('windowInfoCount') == parsed.get('pairCount')
              and integer(parsed.get('pairCount'), 1), 'recognized final window pairing incomplete')
    if 'matches' in parsed:
        validate_matches(parsed, {'token', 'displayId', 'type', 'layer', 'accessibilityWindowId', 'region', 'bounds', 'parent'}, count, check)
    if integer(count) and integer(parsed.get('pairCount')):
        check(count <= parsed['pairCount'], 'final match count exceeds parsed pairs')


def list_observation(group, recognized, token):
    count = group.get('matchCount')
    if not recognized or token is None or not integer(count):
        return {'availability': 'unavailable', 'matchCount': None}
    return {'availability': 'observed', 'matchCount': count,
            'candidate': 'absent_from_this_post_failure_list' if count == 0 else 'unique_text_candidate' if count == 1 else 'ambiguous_text_candidates',
            'matches': group.get('matches', [])}

def analyze(archive, source, run_id, attempt=1, confirmation_output=None):
    original_extract = base.extract
    original_validator = base.analyze_diagnostic
    try:
        base.extract = lambda output: [item for item in extract(
            output, base.MARKER, base.MAX_DIAGNOSTIC)
            if item['bindingStatus'] == 'bound' and item['parseStatus'] == 'parsed']
        base.analyze_diagnostic = safe_confirmation_analysis
        original = base.analyze(archive, source, run_id, attempt)
    finally:
        base.extract = original_extract
        base.analyze_diagnostic = original_validator
    original['bindingPolicy'] = 'complete status block or explicit current summary/stack; malformed and unassigned retained separately'
    original['bindingAuditScriptSha256'] = base.digest(Path(__file__).read_bytes())
    original['bindingAudits'] = []
    lanes, by_case, originals = [], {}, {}
    for label in base.LABELS:
        ui_path = archive / ('api-' + label) / 'ui-results.json'
        if ui_path.exists():
            ui, _ = base.read_json(ui_path)
            for app in ui['apps']:
                for state in app['states']:
                    originals[(label, app['appId'], state['state'])] = state
        path = archive / ('api-' + label) / 'instrumentation.json'
        if not path.exists():
            lanes.append({'api': label, 'instrumentationAvailable': False, 'occurrences': []})
            continue
        instrument, raw = base.read_json(path)
        occurrences = extract(instrument['output'])
        confirmations = extract(instrument['output'], base.MARKER, base.MAX_DIAGNOSTIC)
        original['bindingAudits'].append({'api': label,
            'boundOccurrences': sum(item['bindingStatus'] == 'bound' and item['parseStatus'] == 'parsed' for item in confirmations),
            'unassignedOrMalformedOccurrences': [item for item in confirmations if item['bindingStatus'] != 'bound' or item['parseStatus'] != 'parsed']})
        lanes.append({'api': label, 'instrumentationAvailable': True,
            'instrumentationSha256': base.digest(raw), 'instrumentationBytes': len(raw),
            'occurrences': occurrences})
        for item in occurrences:
            if item['bindingStatus'] != 'bound':
                continue
            app, state = base.METHODS[item['method']]
            by_case.setdefault((label, app, state), []).append(item)
    cases = []
    for old in original['cases']:
        key = old['api'], old['app'], old['state']
        occurrences = by_case.get(key, [])
        distinct = unique_for_case(occurrences)
        original_state = originals.get(key, {})
        item = {k: old[k] for k in ['api', 'app', 'state', 'originalStatus', 'originalStage']}
        item.update(originalError=original_state.get('error'),
            associationDiagnosticOccurrences=len(occurrences), distinctAssociationDiagnosticCount=len(distinct))
        if old['app'] == 'notes-1.7.0':
            item['originalConfirmationPreparation'] = old.get('originalConfirmationPreparation')
        if len(distinct) == 1:
            occurrence = next(iter(distinct.values()))
            if occurrence['parseStatus'] == 'parsed':
                item['analysis'] = derive(occurrence['value'])
            else:
                item['analysis'] = {'availability': 'invalid_json', 'consistencyIssues': occurrence['parseIssues']}
            issues = item['analysis']['consistencyIssues']
            if old['originalStatus'] != 'failed' or old['app'] != 'notes-1.7.0':
                issues.append('post-failure IME diagnostic attached to original nonfailed or non-Notes case')
            if 'confirmation_input_method_hidden_not_verified' not in (item['originalError'] or ''):
                issues.append('original error does not establish hidden-not-verified trigger')
            if original_state.get('installedIdentityVerified') is not True:
                issues.append('original installed identity was not verified')
            prior = old.get('analysis', {})
            matching = [o for lane in original['lanes'] if lane['api'] == old['api'] for o in lane['occurrences']
                        if base.METHODS[o['method']] == (old['app'], old['state'])]
            freeze = original_freeze(prior, matching)
            begin = (occurrence.get('value') or {}).get('startedElapsedMillis')
            if freeze is not None and integer(begin):
                item['postFailureTiming'] = {'originalFrozenElapsedMillis': freeze,
                    'collectorStartedElapsedMillis': begin, 'startedAfterOriginalFreeze': begin >= freeze,
                    'gapAfterOriginalFreezeMillis': begin - freeze}
                if begin < freeze:
                    issues.append('post-failure collector started before original failure freeze')
            elif occurrence['parseStatus'] == 'parsed' and item['analysis']['availability'] == 'complete':
                issues.append('unique original confirmation freeze time unavailable; no before/after timing reconstructed')
            if issues and item['analysis'].get('interpretationAllowed') is True:
                item['analysis'].update(availability='invalid_schema', interpretationAllowed=False,
                    postFailureObservations={'wmsSelected': None, 'tokenCandidate': None,
                        **{name: {'availability': 'invalid_schema', 'matchCount': None}
                           for name in ['populatorVisible', 'populatorDisplays', 'finalService']}})
        else:
            item['analysis'] = {'availability': 'absent' if not distinct else 'ambiguous',
                'consistencyIssues': [] if not distinct else ['multiple distinct post-failure diagnostics for one original case; no preferred value chosen']}
        cases.append(item)
    if confirmation_output is not None:
        confirmation_output.write_text(json.dumps(original, ensure_ascii=False, indent=2) + '\n')
    return {'schemaVersion': 1, 'kind': 'post-failure-notes-ime-association-analysis',
        'sourceRun': original['sourceRun'], 'manifestSha256': original['manifestSha256'],
        'originalStateCounts': original['originalStateCounts'], 'plannedCombinations': 32,
        'cases': cases, 'lanes': lanes,
        'unassignedAssociationOccurrences': sum(o['bindingStatus'] != 'bound' for lane in lanes for o in lane['occurrences']),
        'malformedAssociationOccurrences': sum(o['parseStatus'] != 'parsed' for lane in lanes for o in lane['occurrences']),
        'unassignedOrMalformedConfirmationOccurrences': sum(len(lane['unassignedOrMalformedOccurrences']) for lane in original['bindingAudits']),
        'analysisScriptSha256': base.digest(Path(__file__).read_bytes()),
        'bindingReferenceScriptSha256': base.digest(GEOMETRY_PATH.read_bytes()),
        'confirmationAnalysisScriptSha256': base.digest(geometry.BASE_PATH.read_bytes()),
        'limitations': [
            'These are sequential post-failure reads, before capture/cleanup; none can fill an original v4 decision or hidden sample.',
            'IWindow binder text provides candidates, not strong object identity; system_server lifetime is not verified.',
            'Missing/duplicate/unavailable tokens, unrecognized formats and incomplete commands remain inconclusive.',
            'Populator output is already filtered, and final service windows have further reporting rules; absence cannot name a rejection reason.',
            'No dump alone proves IME pixels hidden/visible, a platform bug or a stable application fix.',
            'Original statuses, errors, v4 sampling/action budgets and the 32-combination denominator remain unchanged.',
            'This analysis validates used payload hashes/source; the full artifact/provenance audit is separate.']}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--run-id', type=int, required=True)
    parser.add_argument('--attempt', type=int, default=1)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--confirmation-output', type=Path)
    args = parser.parse_args()
    base.require(re.fullmatch('[0-9a-f]{40}', args.source) is not None and args.run_id > 0 and args.attempt > 0,
                 'invalid expected source identity')
    result = analyze(args.archive, args.source, args.run_id, args.attempt, args.confirmation_output)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'counts': result['originalStateCounts'],
        'associationCases': sum(c['distinctAssociationDiagnosticCount'] == 1 for c in result['cases']),
        'caseConsistencyIssues': sum(len(c['analysis']['consistencyIssues']) for c in result['cases']),
        'unassignedAssociationOccurrences': result['unassignedAssociationOccurrences'],
        'malformedAssociationOccurrences': result['malformedAssociationOccurrences'],
        'unassignedOrMalformedConfirmationOccurrences': result['unassignedOrMalformedConfirmationOccurrences']}))


if __name__ == '__main__':
    main()
