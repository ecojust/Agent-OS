#!/usr/bin/env python3
"""Bounded structural reader for the observed candidate ART method-trace format.

This is a short capability reader, not a call-stack or reset-caller analyzer.
The command receipt, not is_precise_trace, establishes a non-sampling request.
"""
import hashlib
import re
import struct

MAX_TRACE_BYTES = 16 * 1024 * 1024
MAX_SUMMARY_BYTES = 4 * 1024 * 1024
MAX_LINE_BYTES = 4096
MAX_THREADS = 4096
MAX_METHODS = 100000
MAX_RECORDS = 1500000
KIND = 'ime-method-trace-capability-analysis'
SOURCE_COMMIT = 'b753cf97923c3695338d21466fa14c57b480a59a'
SCOPE = {
    'structuralCapabilityOnly': True,
    'resetCallerEstablished': False,
    'methodInvocationCountEstablished': False,
    'absenceOfCallsProved': False,
    'stackBalanceAssessed': False,
    'encodedThreadIdsAreLinuxTids': False,
    'nonSamplingProvedByFile': False,
    'clockMappedToBoottime': False,
    'nativeMemoryBoundedByFileLimit': False,
    'sourceImageByteEquivalenceVerified': False,
    'referenceArtCommit': SOURCE_COMMIT,
}


class TraceError(ValueError):
    pass


def require(condition, reason):
    if not condition:
        raise TraceError(reason)


def _uint(text, maximum=(1 << 64) - 1, minimum=0):
    require(type(text) is str and re.fullmatch(r'0|[1-9][0-9]{0,19}', text),
            'TRACE_INTEGER_FORMAT')
    value = int(text)
    require(minimum <= value <= maximum, 'TRACE_INTEGER_RANGE')
    return value


def _boolean(text):
    require(text in ('true', 'false'), 'TRACE_BOOLEAN_FORMAT')
    return text == 'true'


def _descriptor(text):
    """Validate a bounded JVM method descriptor without recursive parsing."""
    def one(pos, allow_void=False):
        require(pos < len(text), 'TRACE_METHOD_SIGNATURE')
        arrays = 0
        while pos < len(text) and text[pos] == '[':
            pos += 1
            arrays += 1
        require(arrays <= 255 and pos < len(text), 'TRACE_METHOD_SIGNATURE')
        char = text[pos]
        if char in 'BCDFIJSZ' or (char == 'V' and allow_void and arrays == 0):
            return pos + 1
        require(char == 'L', 'TRACE_METHOD_SIGNATURE')
        end = text.find(';', pos + 1)
        require(end > pos + 1 and not any(c in '[().;' or c.isspace()
                    for c in text[pos + 1:end]), 'TRACE_METHOD_SIGNATURE')
        return end + 1

    require(text.startswith('('), 'TRACE_METHOD_SIGNATURE')
    pos = 1
    while pos < len(text) and text[pos] != ')':
        pos = one(pos)
    require(pos < len(text) and text[pos] == ')', 'TRACE_METHOD_SIGNATURE')
    require(one(pos + 1, True) == len(text), 'TRACE_METHOD_SIGNATURE')


def _read(data, result):
    require(data.startswith(b'*version\n'), 'TRACE_SUMMARY_START')
    marker = b'\n*end\n'
    end = data.find(marker, 0, MAX_SUMMARY_BYTES + 1)
    require(end >= 0, 'TRACE_SUMMARY_END_OR_LIMIT')
    summary_end = end + len(marker)
    require(summary_end <= MAX_SUMMARY_BYTES, 'TRACE_SUMMARY_LIMIT')
    text = data[:summary_end].decode('utf-8', 'strict')
    require(not any(ord(c) < 32 and c not in '\n\t' for c in text)
            and '\x7f' not in text, 'TRACE_SUMMARY_CONTROL')
    lines = text[:-1].split('\n')
    require(all(len(line.encode('utf-8')) <= MAX_LINE_BYTES for line in lines),
            'TRACE_LINE_LIMIT')
    require(len(lines) >= 7 and lines[0] == '*version' and lines[-1] == '*end',
            'TRACE_SUMMARY_STRUCTURE')
    version = _uint(lines[1], 65535)
    require(version in (2, 3), 'TRACE_VERSION_UNSUPPORTED')
    require(lines.count('*threads') == 1 and lines.count('*methods') == 1,
            'TRACE_DICTIONARY_SECTIONS')
    thread_at = lines.index('*threads')
    method_at = lines.index('*methods')
    require(2 < thread_at < method_at < len(lines) - 1, 'TRACE_SECTION_ORDER')
    metadata = {}
    required = {'data-file-overflow', 'clock', 'elapsed-time-usec', 'num-method-calls',
                'clock-call-overhead-nsec', 'vm', 'pid', 'is_streaming',
                'is_precise_trace', 'end_time', 'end_tsc', 'compilation-filter'}
    allocation = {'alloc-count', 'alloc-size', 'gc-count'}
    require(thread_at - 2 <= len(required) + len(allocation), 'TRACE_SUMMARY_KEYS')
    for line in lines[2:thread_at]:
        require('=' in line and '\t' not in line, 'TRACE_SUMMARY_FIELD')
        key, value = line.split('=', 1)
        require(key in required | allocation and key not in metadata,
                'TRACE_SUMMARY_KEY_UNKNOWN_OR_DUPLICATE')
        metadata[key] = value
    require(required <= metadata.keys() and
            (not (allocation & metadata.keys()) or allocation <= metadata.keys()),
            'TRACE_SUMMARY_KEYS')
    require(metadata['vm'] == 'art', 'TRACE_VM_UNSUPPORTED')
    clock = metadata['clock']
    require((version == 3 and clock == 'dual') or
            (version == 2 and clock in ('wall', 'thread-cpu')), 'TRACE_CLOCK_VERSION')
    elapsed = _uint(metadata['elapsed-time-usec'], (1 << 32) - 1, 1)
    declared = _uint(metadata['num-method-calls'], MAX_RECORDS)
    summary = {
        'pid': _uint(metadata['pid'], (1 << 31) - 1, 1),
        'vm': metadata['vm'], 'clock': clock,
        'overflow': _boolean(metadata['data-file-overflow']),
        'elapsedUs': elapsed, 'declaredRecordCount': declared,
        'clockCallOverheadNs': _uint(metadata['clock-call-overhead-nsec']),
        'isStreaming': _boolean(metadata['is_streaming']),
        'isPreciseTrace': _boolean(metadata['is_precise_trace']),
        'endMonotonicNs': _uint(metadata['end_time'], minimum=1),
        'endTimestampCounter': _uint(metadata['end_tsc']),
        'compilationFilter': metadata['compilation-filter'],
        'allocationCounters': {k: _uint(metadata[k]) for k in sorted(allocation)
                               if k in metadata},
    }
    result['summary'] = summary
    require(not summary['isStreaming'], 'TRACE_STREAMING_UNSUPPORTED')
    require(summary['isPreciseTrace'], 'TRACE_LOW_OVERHEAD_UNSUPPORTED')

    threads = {}
    require(method_at - thread_at - 1 <= MAX_THREADS, 'TRACE_THREAD_LIMIT')
    for line in lines[thread_at + 1:method_at]:
        fields = line.split('\t')
        require(len(fields) == 2 and fields[1], 'TRACE_THREAD_ROW')
        ident = _uint(fields[0], 65535, 1)
        require(ident not in threads, 'TRACE_THREAD_DUPLICATE')
        threads[ident] = fields[1]
    methods = set()
    require(len(lines) - method_at - 2 <= MAX_METHODS, 'TRACE_METHOD_LIMIT')
    for line in lines[method_at + 1:-1]:
        fields = line.split('\t')
        # The fixed modern ART source emits ID, class, name, signature, source.
        # Legacy six-field rows are not silently reinterpreted as this format.
        require(len(fields) == 5 and all(fields[i] for i in (0, 1, 2, 3)),
                'TRACE_METHOD_ROW_UNSUPPORTED')
        require(re.fullmatch(r'0|0x[0-9a-fA-F]{1,8}', fields[0]), 'TRACE_METHOD_ID')
        ident = int(fields[0], 16)
        require(ident & 3 == 0 and ident not in methods, 'TRACE_METHOD_ID_DUPLICATE_OR_BITS')
        _descriptor(fields[3])
        methods.add(ident)
    result['dictionaries'] = {'threadCount': len(threads), 'methodCount': len(methods),
                              'threadIdKind': 'encoded_index_not_linux_tid'}

    require(len(data) >= summary_end + 32, 'TRACE_BINARY_HEADER_TRUNCATED')
    binary = memoryview(data)[summary_end:]
    magic, binary_version, header_bytes, start = struct.unpack_from('<4sHHQ', binary)
    require(magic == b'SLOW', 'TRACE_BINARY_MAGIC')
    require(binary_version == version, 'TRACE_BINARY_VERSION')
    require(header_bytes == 32, 'TRACE_BINARY_HEADER_SIZE')
    record_bytes = 14 if version == 3 else 10
    if version == 3:
        require(struct.unpack_from('<H', binary, 16)[0] == record_bytes,
                'TRACE_RECORD_SIZE')
    reserve_from = 18 if version == 3 else 16
    require(all(b == 0 for b in binary[reserve_from:32]), 'TRACE_HEADER_RESERVED')
    require(start > 0 and summary['endMonotonicNs'] >= start * 1000,
            'TRACE_MONOTONIC_HEADER_INTERVAL')
    result['format'] = {'name': 'ART_V1_NON_STREAMING', 'version': version,
                        'headerBytes': header_bytes, 'recordBytes': record_bytes,
                        'summaryBytes': summary_end, 'binaryBytes': len(binary),
                        'endianness': 'little', 'startMonotonicUs': start,
                        'clockDomain': 'MONOTONIC_and_optional_per_thread_CPU',
                        'encodedThreadIdsAreLinuxTids': False}
    body_bytes = len(binary) - header_bytes
    require(body_bytes % record_bytes == 0, 'TRACE_RECORD_TRUNCATED_OR_TRAILING')
    count = body_bytes // record_bytes
    require(count <= MAX_RECORDS and count == declared, 'TRACE_RECORD_COUNT')
    actions = {'enter': 0, 'exit': 0, 'unwind': 0}
    action_names = ('enter', 'exit', 'unwind')
    referenced_methods = set()
    times = {}
    cpu_min = cpu_max = wall_min = wall_max = None
    for offset in range(header_bytes, len(binary), record_bytes):
        tid, encoded = struct.unpack_from('<HI', binary, offset)
        action, method = encoded & 3, encoded & ~3
        require(action < 3, 'TRACE_RECORD_ACTION')
        require(tid in threads and method in methods, 'TRACE_RECORD_REFERENCE')
        first = struct.unpack_from('<I', binary, offset + 6)[0]
        cpu = first if clock in ('dual', 'thread-cpu') else None
        wall = (struct.unpack_from('<I', binary, offset + 10)[0]
                if clock == 'dual' else first if clock == 'wall' else None)
        previous = times.get(tid)
        if previous is not None:
            require((cpu is None or cpu >= previous[0]) and
                    (wall is None or wall >= previous[1]), 'TRACE_THREAD_CLOCK_ORDER')
        require(wall is None or wall <= elapsed, 'TRACE_WALL_OUTSIDE_ELAPSED')
        times[tid] = (cpu, wall)
        if cpu is not None:
            cpu_min = cpu if cpu_min is None else min(cpu_min, cpu)
            cpu_max = cpu if cpu_max is None else max(cpu_max, cpu)
        if wall is not None:
            wall_min = wall if wall_min is None else min(wall_min, wall)
            wall_max = wall if wall_max is None else max(wall_max, wall)
        actions[action_names[action]] += 1
        referenced_methods.add(method)
    result['records'] = {'count': count, 'actions': actions,
                         'observedEncodedThreadCount': len(times),
                         'referencedMethodCount': len(referenced_methods),
                         'cpuDeltaRangeUs': None if cpu_min is None else [cpu_min, cpu_max],
                         'wallDeltaRangeUs': None if wall_min is None else [wall_min, wall_max],
                         'stackBalanceAssessed': False,
                         'recordCountIsMethodInvocationCount': False}
    if summary['overflow']:
        result['reasons'].append('TRACE_OVERFLOW')
    if not count or not threads or not methods:
        result['reasons'].append('TRACE_NO_EVENTS')


def parse_trace(data: bytes) -> dict:
    """Return deterministic bounded facts; malformed/unsupported originals stay unverified."""
    result = {'schemaVersion': 1, 'kind': KIND, 'status': 'unverified', 'reasons': [],
              'scope': dict(SCOPE), 'trace': None, 'format': None,
              'summary': None, 'dictionaries': None, 'records': None}
    if type(data) is not bytes:
        result['reasons'].append('TRACE_INPUT_TYPE')
        return result
    result['trace'] = {'bytes': len(data), 'sha256': None}
    if len(data) > MAX_TRACE_BYTES:
        result['reasons'].append('TRACE_INPUT_LIMIT')
        return result
    result['trace']['sha256'] = hashlib.sha256(data).hexdigest()
    try:
        _read(data, result)
    except TraceError as error:
        result['reasons'].append(str(error))
    except (UnicodeError, ValueError, struct.error):
        result['reasons'].append('TRACE_FORMAT_INVALID')
    if not result['reasons']:
        result['status'] = 'recorded'
    return result
