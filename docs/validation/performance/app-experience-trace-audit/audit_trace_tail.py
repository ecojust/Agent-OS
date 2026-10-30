#!/usr/bin/env python3
"""Read-only, stdlib-only protobuf tail audit for archived app-experience traces.

Run from the repository root. This audit adds no acceptance gate and never edits
original evidence. A complete gzip/protobuf boundary cannot rule out a file that
was copied at a valid packet boundary before later packets became available.
"""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path
import struct

RAW_LIMIT = 256 * 1024 * 1024
GZIP_LIMIT = 64 * 1024 * 1024
SERVICE_NAMES = {1: 'allDataSourcesStarted', 2: 'tracingStarted',
                 3: 'allDataSourcesFlushed', 4: 'readBuffersCompleted',
                 5: 'tracingDisabled', 6: 'seizedForBugreport',
                 7: 'slowStartingDataSources', 8: 'lastFlushSlowDataSources',
                 9: 'flushStarted'}
CPU_NAMES = {1: 'cpu', 2: 'entries', 3: 'overrun', 4: 'commitOverrun',
             5: 'currentBufferBytes', 6: 'oldestEventSeconds', 7: 'nowSeconds',
             8: 'droppedEvents', 9: 'readEvents'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def varint(data, offset):
    result = 0
    for shift in range(0, 70, 7):
        byte = data[offset]
        offset += 1
        result |= (byte & 127) << shift
        if byte < 128:
            return result, offset
    raise ValueError('invalid protobuf varint')


def fields(data):
    offset = 0
    while offset < len(data):
        start = offset
        tag, offset = varint(data, offset)
        wire = tag & 7
        if wire == 0:
            value, offset = varint(data, offset)
        elif wire == 2:
            size, offset = varint(data, offset)
            value = memoryview(data)[offset:offset + size]
            offset += size
        elif wire in (1, 5):
            size = 8 if wire == 1 else 4
            value = memoryview(data)[offset:offset + size]
            offset += size
        else:
            raise ValueError('unsupported protobuf wire type')
        if offset > len(data) or tag >> 3 == 0:
            raise ValueError('incomplete protobuf field')
        yield tag >> 3, wire, value, offset - start


def scalars(data, names):
    result = {}
    for number, wire, value, _ in fields(data):
        if number not in names:
            continue
        if wire == 0:
            result[names[number]] = value
        elif wire == 1:
            result[names[number]] = struct.unpack('<d', value)[0]
    return result


def audit_lane(lane, expected_run, expected_commit):
    capture = json.loads((lane / 'capture.json').read_text())
    trace_capture = json.loads((lane / 'trace-capture.json').read_text())
    report = json.loads((lane / 'experience-diagnostics.json').read_text())
    source = capture['sourceRun']
    if (source['runId'] != expected_run or source['attempt'] != 1
            or source['commit'] != expected_commit):
        raise ValueError('source binding mismatch')
    path = lane / 'trace.pftrace.gz'
    if path.is_symlink() or not 0 < path.stat().st_size <= GZIP_LIMIT:
        raise ValueError('invalid compressed trace')
    compressed = path.read_bytes()
    with gzip.open(path, 'rb') as stream:
        raw = stream.read(RAW_LIMIT + 1)
    if not 0 < len(raw) < RAW_LIMIT:
        raise ValueError('invalid raw trace size')
    if (trace_capture['trace']['bytes'] != len(raw)
            or trace_capture['trace']['sha256'] != digest(raw)
            or trace_capture['compressed']['bytes'] != len(compressed)
            or trace_capture['compressed']['sha256'] != digest(compressed)):
        raise ValueError('original trace checksum mismatch')
    offset = packet_count = 0
    cpu = {}
    clocks = collections.Counter()
    primary = collections.Counter()
    clock_offsets = []
    services = {}
    packet_clocks = collections.Counter()
    sequence_defaults = {}
    unknown_sequence_defaults = set()
    snapshot_clock_modes = {3: collections.Counter(), 6: collections.Counter()}
    last_ftrace_stats = None
    last_trace_stats = None
    last_packet = None
    boot_packet_timestamps = []
    for number, wire, packet, length in fields(raw):
        if (number, wire) != (1, 2):
            raise ValueError('unexpected top-level trace field')
        values = list(fields(packet))
        pv = {n: v for n, w, v, _ in values if w == 0}
        seq = pv.get(10)
        if pv.get(13, 0) & 1 or pv.get(41, 0):  # Modern and legacy clear flags.
            sequence_defaults.pop(seq, None)
            unknown_sequence_defaults.discard(seq)
        elif pv.get(42, 0):  # Previous packet may have changed sequence defaults.
            sequence_defaults.pop(seq, None)
            unknown_sequence_defaults.add(seq)
        for n, w, v, _ in values:
            if (n, w) == (59, 2):  # TracePacketDefaults.timestamp_clock_id.
                sequence_defaults[seq] = next((v for n, w, v, _ in fields(v)
                                               if (n, w) == (58, 0)), 6)
                unknown_sequence_defaults.discard(seq)
        packet_clock = pv.get(58, None if seq in unknown_sequence_defaults
                              else sequence_defaults.get(seq, 6))
        packet_count += 1
        last_packet = dict(offset=offset, bytes=length, timestampRaw=pv.get(8),
                           timestampClockId=packet_clock if 8 in pv else None,
                           payloadFieldNumbers=[n for n, w, _, _ in values if w == 2])
        if 8 in pv:
            packet_clocks[str(packet_clock) if packet_clock is not None else 'unknown'] += 1
            if packet_clock == 6:
                boot_packet_timestamps.append(pv[8])
        offset += length
        for n, w, value, _ in values:
            if (n, w) == (1, 2):
                bundle = list(fields(value))
                bv = {n: v for n, w, v, _ in bundle if w == 0}
                ftrace_clock = bv.get(5, 0)
                clocks[ftrace_clock] += 1
                item = cpu.setdefault((bv.get(1), ftrace_clock),
                    dict(cpu=bv.get(1), ftraceClockId=ftrace_clock,
                         lastEventTimestampRaw=0, events=0, lostEventsBundles=0))
                item['lostEventsBundles'] += int(bool(bv.get(3, 0)))
                for en, ew, ev, _ in bundle:
                    if (en, ew) == (2, 2):
                        ts = next((v for n, w, v, _ in fields(ev)
                                   if (n, w) == (1, 0)), None)
                        item['events'] += 1
                        if ts is not None:
                            item['lastEventTimestampRaw'] = max(item['lastEventTimestampRaw'], ts)
            elif (n, w) == (6, 2):
                snapshot = {}
                for cn, cw, cv, _ in fields(value):
                    if (cn, cw) == (2, 0):
                        primary[cv] += 1
                    elif (cn, cw) == (1, 2):
                        cs = {n: v for n, w, v, _ in fields(cv) if w == 0}
                        snapshot[cs[1]] = cs[2]
                        if cs[1] in snapshot_clock_modes:
                            snapshot_clock_modes[cs[1]][(cs.get(4, 1), cs.get(3, 0))] += 1
                if (3 in snapshot and 6 in snapshot
                        and set(snapshot_clock_modes[3]) == {(1, 0)}
                        and set(snapshot_clock_modes[6]) == {(1, 0)}):
                    clock_offsets.append(snapshot[6] - snapshot[3])
            elif (n, w) == (34, 2):
                phase = next((v for n, w, v, _ in fields(value)
                              if (n, w) == (1, 0)), None)
                if phase == 2:
                    last_ftrace_stats = [scalars(v, CPU_NAMES)
                        for n, w, v, _ in fields(value) if (n, w) == (2, 2)]
            elif (n, w) == (35, 2):
                last_trace_stats = scalars(value, {12: 'flushesRequested',
                    13: 'flushesSucceeded', 14: 'flushesFailed', 15: 'finalFlushOutcome'})
            elif (n, w) == (69, 2):
                for en, ew, ev, _ in fields(value):
                    if en in SERVICE_NAMES:
                        item = services.setdefault(SERVICE_NAMES[en], dict(count=0,
                            lastTimestampRaw=None, lastTimestampClockId=None))
                        item['count'] += 1
                        item['lastTimestampRaw'] = pv.get(8)
                        item['lastTimestampClockId'] = packet_clock if 8 in pv else None
    if offset != len(raw):
        raise ValueError('trace did not end on a protobuf boundary')
    watermark = None
    if report['trials']:
        ref = report['trials'][-1]
        payload = (lane / ref['path']).read_bytes()
        if len(payload) != ref['bytes'] or digest(payload) != ref['sha256']:
            raise ValueError('resource report checksum mismatch')
        trial = json.loads(payload)
        if trial.get('frames'):
            frame = trial['frames'][-1]
            watermark = dict(path=str(lane / ref['path']), sha256=digest(payload),
                trialIndex=ref['index'], targetPid=trial['targetAfter']['pid'],
                lastFrameIndex=frame['index'], vsyncNanos=frame['vsyncNs'],
                drawMonotonicNanos=frame['drawNanoTime'],
                callbackMonotonicNanos=frame['callbackNanoTime'],
                trialEndElapsedNanos=trial['endedNanos'],
                providerClockDomain=trial['targetAfter'].get('clockDomain'))
    boot_absolute_ns = set(snapshot_clock_modes[6]) == {(1, 0)}
    mono_absolute_ns = set(snapshot_clock_modes[3]) == {(1, 0)}
    clocks_comparable = (set(primary) == {6} and set(clocks) == {0}
                         and boot_absolute_ns and mono_absolute_ns)
    offset_range = ([min(clock_offsets), max(clock_offsets)]
                    if clock_offsets and clocks_comparable else None)
    max_ftrace = max((r['lastEventTimestampRaw'] for r in cpu.values()), default=0)
    for item in cpu.values():
        item['lastEventBoottimeNanos'] = (item['lastEventTimestampRaw']
            if item['ftraceClockId'] == 0 and boot_absolute_ns else None)
    for item in services.values():
        item['lastBoottimeNanos'] = (item['lastTimestampRaw']
            if item['lastTimestampClockId'] == 6 and boot_absolute_ns else None)
    last_packet['timestampBoottimeNanos'] = (last_packet['timestampRaw']
        if last_packet['timestampClockId'] == 6 and boot_absolute_ns else None)
    gap_range = ([watermark['drawMonotonicNanos'] + v - max_ftrace for v in offset_range]
                 if watermark and offset_range and watermark['providerClockDomain'] ==
                    'CLOCK_MONOTONIC_System.nanoTime' else None)
    return dict(api=lane.name.removeprefix('api-'),
        trace=dict(path=str(path), compressedBytes=len(compressed),
                   compressedSha256=digest(compressed), rawBytes=len(raw),
                   rawSha256=digest(raw)),
        capture=dict(schemaVersion=trace_capture['schemaVersion'],
                     configVersion=trace_capture['configVersion'],
                     status=trace_capture['status'], stopped=trace_capture['stopped'],
                     error=trace_capture['error']),
        originalReport=dict(status=report['status'], profile=report['profile'],
                            launchRefs=len(report['launches']), resourceRefs=len(report['trials'])),
        structuralChecks=dict(gzipDecodedWithCrc=True, endsAtCompleteProtobufPacket=True,
                              rawAndCompressedHashesMatchOriginalCapture=True),
        packetCount=packet_count, lastPacket=last_packet,
        maximumBoottimePacketTimestampNanos=(max(boot_packet_timestamps)
            if boot_packet_timestamps and boot_absolute_ns else None),
        primaryTraceClocks=dict(primary), ftraceClocks=dict(clocks),
        packetTimestampClocks=dict(packet_clocks),
        clockPreconditions=dict(primaryIsBoottime=set(primary) == {6},
            allFtraceBundlesUseDefaultBoottime=set(clocks) == {0},
            bootSnapshotsUseAbsoluteNanoseconds=boot_absolute_ns,
            monotonicSnapshotsUseAbsoluteNanoseconds=mono_absolute_ns,
            gapClockDomainsComparable=clocks_comparable,
            snapshotModes={str(k): [dict(unitMultiplierNs=mode[0],
                isIncremental=bool(mode[1]), snapshots=count)
                for mode, count in modes.items()]
                for k, modes in snapshot_clock_modes.items()}),
        bootMinusMonotonicSnapshotNanosRange=offset_range,
        perCpu=list(cpu.values()), lastEndFtraceStats=last_ftrace_stats,
        lastTraceStatsFlushCounters=last_trace_stats, serviceEvents=services,
        finalResourceWatermark=watermark,
        finalResourceDrawAfterLastFtraceNanosRange=gap_range,
        interpretation=('auxiliary_resource_tail_coverage_gap_observed'
                        if gap_range and min(gap_range) > 1_000 else 'no_gap_proven_by_watermark'),
        mechanism='undetermined; no new acceptance gate')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-root', required=True, type=Path)
    parser.add_argument('--run-id', required=True, type=int)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    lanes = [audit_lane(p, args.run_id, args.source_commit)
             for p in sorted(args.evidence_root.iterdir()) if p.is_dir()]
    result = dict(schemaVersion=1, kind='agent-os-independent-trace-tail-audit',
        sourceRun=dict(repository='ecojust/Agent-OS', runId=args.run_id, attempt=1,
                       commit=args.source_commit),
        method='stdlib gzip CRC + full protobuf boundary parse; original trace/report hash binding',
        clockMethod='Resolve timestamp_clock_id or per-sequence TracePacketDefaults, resetting on modern/legacy incremental-state-clear flags; after a previous-packet-dropped flag keep missing defaults unknown until reset or restated. Otherwise default to BOOTTIME per proto. Packet timestamps outside verified absolute-nanosecond BOOTTIME remain raw, not compared. Compute resource gaps only when primary clock is 6, every ftrace bundle uses default clock 0, all BOOTTIME/MONOTONIC snapshots have unit multiplier 1 and is_incremental false, and the report declares System.nanoTime. Otherwise preserve unknown/null.',
        scriptSha256=digest(Path(__file__).read_bytes()),
        fieldReferences=dict(tracePacket='https://github.com/google/perfetto/blob/v59.1/protos/perfetto/trace/trace_packet.proto',
            packetDefaults='https://github.com/google/perfetto/blob/v59.1/protos/perfetto/trace/trace_packet_defaults.proto',
            clockSnapshot='https://github.com/google/perfetto/blob/v59.1/protos/perfetto/trace/clock_snapshot.proto',
            ftraceBundle='https://github.com/google/perfetto/blob/v59.1/protos/perfetto/trace/ftrace/ftrace_event_bundle.proto',
            ftraceStats='https://github.com/google/perfetto/blob/v59.1/protos/perfetto/trace/ftrace/ftrace_stats.proto',
            serviceEvent='https://github.com/google/perfetto/blob/v59.1/protos/perfetto/trace/perfetto/tracing_service_event.proto'),
        conclusions=dict(physicalTruncationProven=False,
            stopFlushFailureRecorded=any((r['lastTraceStatsFlushCounters'] or {}).get('flushesFailed', 0) > 0
                or 'lastFlushSlowDataSources' in r['serviceEvents'] for r in lanes),
            originalAcceptanceStatusesUnchanged=True,
            caveat='Complete gzip/protobuf boundaries do not rule out omission at a packet boundary. Missing auxiliary resource ftrace does not change the frozen startup-TTID or independent FrameMetrics gates. Absence of a final service/stat packet alone does not prove its generating operation failed.'),
        apis=lanes)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    print(json.dumps(dict(output=str(args.output), apis=len(lanes),
                         bytes=args.output.stat().st_size,
                         gapApis=[r['api'] for r in lanes if r['interpretation'].startswith('auxiliary')])))


if __name__ == '__main__':
    main()
