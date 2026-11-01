#!/usr/bin/env python3
"""Bounded, pure parser of the AOSP17 InputReader/EventHub dump subsections.

The caller must require a complete command, exit zero and its frozen byte/time
bounds before calling analyze. A newline or a later section header cannot prove
that the entire service dump completed. The selected native sections are parsed
by fixed indentation; similarly named Dispatcher/Java/mapper fields are excluded.

Format: frameworks/native ae266dcb706d083868578cfedce381ef44488a07,
InputReader.cpp, InputDevice.cpp, EventHub.cpp, InputReaderBase.cpp, Input.cpp and
ftl/flags.h; frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
InputManagerService.java and its JNI implementation. Neither Reader Configuration
nor the native manager dump prints keyboard/hardKeyboardHidden. These are not
fabricated from device capabilities. Source review does not attest a device image.
"""
import hashlib
import re

MAX_DUMP_BYTES = 262144
MAX_LINES = 8192
MAX_LINE_CHARS = 8192
MAX_DEVICES = 128
MAX_NAME_CHARS = 256
MAX_EVENT_HUB_IDS = 32
NUMBER = r'-?(?:0|[1-9][0-9]{0,9})'
SOURCE_NAMES = frozenset(('KEYBOARD', 'DPAD', 'GAMEPAD', 'TOUCHSCREEN', 'MOUSE',
                         'STYLUS', 'BLUETOOTH_STYLUS', 'TRACKBALL', 'MOUSE_RELATIVE',
                         'TOUCHPAD', 'TOUCH_NAVIGATION', 'JOYSTICK', 'HDMI', 'SENSOR',
                         'ROTARY_ENCODER'))
CLASS_NAMES = frozenset(('KEYBOARD', 'ALPHAKEY', 'TOUCH', 'CURSOR', 'TOUCH_MT', 'DPAD',
                        'GAMEPAD', 'SWITCH', 'JOYSTICK', 'VIBRATOR', 'MIC',
                        'EXTERNAL_STYLUS', 'ROTARY_ENCODER', 'SENSOR', 'BATTERY',
                        'LIGHT', 'TOUCHPAD', 'VIRTUAL', 'EXTERNAL'))
SCOPE = {
    'observation': 'InputReader_and_EventHub_native_dump_subsections',
    'completeCommandRequiredByCaller': True,
    'entireJavaServiceTailValidated': False,
    'readerAndEventHubAreAtomicSnapshot': False,
    'deviceCapabilityProvesQwertyConfigurationCause': False,
    'negativeDeviceIdIsDifferentFromIsVirtualDevice': True,
    'rawWindowBinderAssociation': False,
    'rawPositiveResultMayBeUpgraded': False,
    'sourceImageEquivalenceVerified': False,
}
ERROR_MARKERS = (
    "*** SERVICE 'input' DUMP TIMEOUT (", "Can't find service: input",
    "Error with service 'input' while ", 'Failed to create pipe to dump service info for input:',
    'Error in poll while dumping service input :', 'Failed to read while dumping service input:',
    'Failed to write while dumping service input:', 'Error: invalid timeout(', 'usage: dumpsys',
    'Exception:', 'Permission Denial:', 'Timeout waiting for dump', 'Interrupted waiting for dump',
)


class Incomplete(ValueError):
    pass


def require(condition, reason):
    if not condition:
        raise Incomplete(reason)


def integer(value):
    require(re.fullmatch(NUMBER, value), 'INTEGER_FORMAT')
    result = int(value)
    require(-(2**31) <= result < 2**31, 'INTEGER_RANGE')
    return result


def unique(lines, prefix, pattern, reason):
    selected = [line for line in lines if line.startswith(prefix)]
    require(len(selected) == 1, reason)
    match = re.fullmatch(pattern, selected[0])
    require(match is not None, reason)
    return match


def name(value):
    require(0 < len(value) <= MAX_NAME_CHARS and not any(ord(c) < 32 or ord(c) == 127 for c in value),
            'DEVICE_NAME')
    return value


def flags(value, allowed, *, sources=False):
    """Preserve unknown numeric bits; never silently interpret them as no source."""
    require(len(value) <= 512, 'FLAGS_BOUND')
    if sources and value in {'UNKNOWN', 'ANY'}:
        return {'printed': value, 'names': [value], 'unclassifiedHex': None}
    parts = value.split(' | ')
    require(1 <= len(parts) <= 32 and len(set(parts)) == len(parts), 'FLAGS_FORMAT')
    names = []; numeric = None
    for part in parts:
        if part in allowed:
            require(numeric is None, 'FLAGS_FORMAT')
            names.append(part)
        else:
            pattern = r'0x[0-9a-f]{8}' if sources else r'0x[0-9a-f]{1,8}'
            require(re.fullmatch(pattern, part) and numeric is None, 'FLAGS_FORMAT')
            require(not sources or len(parts) == 1, 'FLAGS_FORMAT')
            numeric = part
    return {'printed': value, 'names': names, 'unclassifiedHex': numeric}


def empty_analysis(data):
    bounded = type(data) is bytes and len(data) < MAX_DUMP_BYTES
    return {
        'schemaVersion': 1, 'status': 'unavailable', 'reason': 'NOT_PARSED', 'scope': dict(SCOPE),
        'input': {'bytes': len(data) if type(data) is bytes else None,
                  'sha256': hashlib.sha256(data).hexdigest() if bounded else None},
        'boundary': {'eventHubHeaderCount': None, 'readerHeaderCount': None,
                     'declaredReaderDeviceCount': None, 'builtInKeyboardId': None,
                     'eventHubBytes': None, 'eventHubSha256': None,
                     'readerBytes': None, 'readerSha256': None},
        'readerConfiguration': {
            'status': 'unavailable',
            'keyboard': {'status': 'not_printed', 'value': None},
            'hardKeyboardHidden': {'status': 'not_printed', 'value': None}},
        'devices': None, 'eventHubDevices': None,
    }


def parse_event_hub(lines):
    built_in = integer(unique(lines, '  BuiltInKeyboardId:',
                              r'  BuiltInKeyboardId: (' + NUMBER + ')', 'BUILT_IN_KEYBOARD')[1])
    starts = [i for i, line in enumerate(lines) if line.startswith('  Devices:')]
    ends = [i for i, line in enumerate(lines) if line.startswith('  Unattached video devices:')]
    require(len(starts) == len(ends) == 1 and lines[starts[0]] == '  Devices:'
            and lines[ends[0]] == '  Unattached video devices:' and starts[0] < ends[0], 'EVENT_HUB_DEVICES_BOUNDARY')
    body = lines[starts[0] + 1:ends[0]]
    headers = [(i, re.fullmatch(r'    (' + NUMBER + r'): (.+)', line))
               for i, line in enumerate(body) if line and not line.startswith('      ')]
    require(len(headers) <= MAX_DEVICES and all(match is not None for _, match in headers), 'EVENT_HUB_DEVICE_HEADER')
    require(not body or (headers and headers[0][0] == 0), 'EVENT_HUB_DEVICE_HEADER')
    devices = []; identifiers = set()
    for index, (start, match) in enumerate(headers):
        end = headers[index + 1][0] if index + 1 < len(headers) else len(body)
        block = body[start + 1:end]
        device_id = integer(match[1]); require(device_id not in identifiers, 'DUPLICATE_EVENT_HUB_DEVICE')
        identifiers.add(device_id)
        suffix = ' (aka device 0 - built-in keyboard)'
        alias = match[2].endswith(suffix)
        device_name = name(match[2][:-len(suffix)] if alias else match[2])
        require(alias == (built_in >= 0 and built_in == device_id), 'BUILT_IN_ALIAS_MISMATCH')
        classes = flags(unique(block, '      Classes:', r'      Classes: (.+)', 'EVENT_HUB_CLASSES')[1], CLASS_NAMES)
        enabled = unique(block, '      Enabled:', r'      Enabled: (true|false)', 'EVENT_HUB_ENABLED')[1] == 'true'
        # This unconditional late field establishes that every selected device's base dump finished.
        unique(block, '      SysfsDevicePath:', r'      SysfsDevicePath: .*', 'EVENT_HUB_DEVICE_TERMINAL')
        devices.append({'id': device_id, 'name': device_name, 'builtInKeyboardAlias': alias,
                        'enabled': enabled, 'classes': classes})
    require(built_in < 0 or built_in in identifiers, 'BUILT_IN_DEVICE_MISSING')
    return built_in, devices


def parse_reader(lines, declared, built_in, event_hub):
    config = [i for i, line in enumerate(lines) if line.startswith('  Configuration:')]
    timeout = [i for i, line in enumerate(lines) if line.startswith('  NextTimeout:')]
    require(len(config) == len(timeout) == 1 and lines[config[0]] == '  Configuration:'
            and timeout[0] + 1 == config[0]
            and re.fullmatch(r'  NextTimeout: -?[0-9]{1,20}', lines[timeout[0]]), 'READER_CONFIGURATION_BOUNDARY')
    config_body = lines[config[0] + 1:]
    unique(config_body, '    ExcludedDeviceNames:', r'    ExcludedDeviceNames: \[.*\]', 'READER_CONFIGURATION_FORMAT')
    unique(config_body, '    VirtualKeyQuietTime:', r'    VirtualKeyQuietTime: -?[0-9]+\.[0-9]ms', 'READER_CONFIGURATION_FORMAT')
    unique(config_body, '    WheelVelocityControlParameters:',
           r'    WheelVelocityControlParameters: scale=-?[0-9]+\.[0-9]{3}, lowThreshold=-?[0-9]+\.[0-9]{3}, highThreshold=-?[0-9]+\.[0-9]{3}, acceleration=-?[0-9]+\.[0-9]{3}', 'READER_CONFIGURATION_FORMAT')
    unique(config_body, '    PointerGesture:', r'    PointerGesture:', 'READER_CONFIGURATION_FORMAT')
    unique(config_body, '      Enabled:', r'      Enabled: (true|false)', 'READER_CONFIGURATION_FORMAT')
    viewport = unique(config_body, '      Viewports:', r'      Viewports:', 'READER_CONFIGURATION_TERMINAL')
    viewport_index = config_body.index(viewport[0])
    require(all(not line or line.startswith('        ') for line in config_body[viewport_index + 1:]),
            'READER_CONFIGURATION_TERMINAL')
    body = lines[:timeout[0]]
    headers = [(i, re.fullmatch(r'  Device (' + NUMBER + r'): (.+)', line))
               for i, line in enumerate(body) if line.startswith('  Device ')]
    require(len(headers) == declared and all(match is not None for _, match in headers), 'READER_DEVICE_COUNT')
    require(not any(body[:headers[0][0]]) if headers else not any(body), 'READER_DEVICE_HEADER')
    devices = []; identifiers = set(); used_hubs = set(); hubs = {d['id']: d for d in event_hub}
    for index, (start, match) in enumerate(headers):
        end = headers[index + 1][0] if index + 1 < len(headers) else len(body)
        block = body[start + 1:end]
        device_id = integer(match[1]); require(device_id not in identifiers, 'DUPLICATE_READER_DEVICE')
        identifiers.add(device_id)
        raw_ids = unique(block, '    EventHub Devices:', r'    EventHub Devices: \[ ((?:' + NUMBER + r' )+)\] ', 'READER_EVENT_HUB_IDS')[1]
        raw_ids = [integer(v) for v in raw_ids.split()]
        require(1 <= len(raw_ids) <= MAX_EVENT_HUB_IDS and len(set(raw_ids)) == len(raw_ids), 'READER_EVENT_HUB_IDS')
        require(0 not in raw_ids or built_in >= 0, 'READER_EVENT_HUB_ASSOCIATION')
        resolved_ids = [built_in if v == 0 else v for v in raw_ids]
        require(all(v in hubs for v in resolved_ids) and len(set(resolved_ids)) == len(resolved_ids)
                and not used_hubs.intersection(resolved_ids), 'READER_EVENT_HUB_ASSOCIATION')
        used_hubs.update(resolved_ids)
        external = unique(block, '    IsExternal:', r'    IsExternal: (true|false)', 'READER_EXTERNAL')[1] == 'true'
        virtual = unique(block, '    IsVirtualDevice:', r'    IsVirtualDevice: (true|false)', 'READER_VIRTUAL')[1] == 'true'
        sources = flags(unique(block, '    Sources:', r'    Sources: (.+)', 'READER_SOURCES')[1], SOURCE_NAMES, sources=True)
        keyboard = integer(unique(block, '    KeyboardType:', r'    KeyboardType: ([012])', 'READER_KEYBOARD_TYPE')[1])
        unique(block, '    ControllerNum:', r'    ControllerNum: ' + NUMBER, 'READER_DEVICE_TERMINAL')
        devices.append({'id': device_id, 'name': name(match[2]), 'eventHubIds': raw_ids,
                        'eventHubDeviceIds': resolved_ids, 'isExternal': external,
                        'isVirtualDevice': virtual, 'sources': sources, 'keyboardType': keyboard})
    return devices


def analyze(data):
    result = empty_analysis(data)
    try:
        require(type(data) is bytes and 0 < len(data) < MAX_DUMP_BYTES, 'INPUT_BOUND')
        try:
            text = data.decode('utf-8')
        except UnicodeError:
            raise Incomplete('INPUT_UTF8') from None
        require(text.endswith('\n') and '\x00' not in text, 'INPUT_INCOMPLETE')
        original_lines = text.splitlines(keepends=True)
        lines = [line.removesuffix('\n').removesuffix('\r') for line in original_lines]
        require(len(lines) <= MAX_LINES and all(len(line) <= MAX_LINE_CHARS for line in lines), 'INPUT_LINES_BOUND')
        require(not any(ord(c) < 32 and c not in '\t\r\n' for c in text), 'INPUT_CONTROL_CHARACTER')
        require(not any(marker in text for marker in ERROR_MARKERS), 'DUMP_ERROR')
        positions = {}
        for key, prefix, pattern in (
                ('manager', 'INPUT MANAGER (dumpsys input)', r'INPUT MANAGER \(dumpsys input\)'),
                ('native', 'Input Manager State:', r'Input Manager State:'),
                ('hub', 'Event Hub State:', r'Event Hub State:'),
                ('reader', 'Input Reader State', r'Input Reader State \(Nums of device: ([0-9]{1,3})\):'),
                ('blocker', 'UnwantedInteractionBlocker:', r'UnwantedInteractionBlocker:'),
                ('dispatcher', 'Input Dispatcher State:', r'Input Dispatcher State:'),
                ('java', 'Input Manager Service (Java) State:', r'Input Manager Service \(Java\) State:')):
            selected = [(i, line) for i, line in enumerate(lines) if line.startswith(prefix)]
            if key in {'hub', 'reader'}:
                result['boundary']['eventHubHeaderCount' if key == 'hub' else 'readerHeaderCount'] = len(selected)
            require(len(selected) == 1, 'SECTION_BOUNDARY')
            index, line = selected[0]; match = re.fullmatch(pattern, line)
            require(match is not None, 'SECTION_BOUNDARY')
            positions[key] = index
            if key == 'reader':
                declared = int(match[1]); require(declared <= MAX_DEVICES, 'DEVICE_BOUND')
                result['boundary']['declaredReaderDeviceCount'] = declared
        require(list(positions.values()) == sorted(set(positions.values())) and positions['manager'] == 0, 'SECTION_ORDER')
        hub_lines = lines[positions['hub'] + 1:positions['reader']]
        reader_lines = lines[positions['reader'] + 1:positions['blocker']]
        # A new unindented section inside either selected block invalidates the binding.
        require(all(not line or line.startswith('  ') for line in hub_lines + reader_lines), 'SELECTED_SECTION_CONTENT')
        built_in, hubs = parse_event_hub(hub_lines)
        devices = parse_reader(reader_lines, declared, built_in, hubs)
        for key, start, end in (('eventHub', positions['hub'], positions['reader']),
                                ('reader', positions['reader'], positions['blocker'])):
            raw = ''.join(original_lines[start:end]).encode('utf-8')
            result['boundary'][key + 'Bytes'] = len(raw)
            result['boundary'][key + 'Sha256'] = hashlib.sha256(raw).hexdigest()
        result['boundary']['builtInKeyboardId'] = built_in
        result['readerConfiguration']['status'] = 'recorded'
        result.update(status='recorded', reason=None, devices=devices, eventHubDevices=hubs)
    except Incomplete as failure:
        result['reason'] = str(failure)
    return result
