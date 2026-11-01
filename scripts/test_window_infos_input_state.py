#!/usr/bin/env python3
"""Synthetic fixed-source InputReader fixtures; these are never device evidence."""
import hashlib
import json
import random
import unittest

import window_infos_input_state as state


def synthetic_dump(*, keyboard_type=2, enabled=True, built_in=False, device_id=1,
                   sources='KEYBOARD | DPAD', classes='KEYBOARD | ALPHAKEY | DPAD',
                   external=False, virtual_device=False, empty=False):
    """A complete selected native subsection, with unrelated Dispatcher fields."""
    hub_id = 42 if built_in else device_id
    raw_id = 0 if built_in else hub_id
    builtin_id = hub_id if built_in else -2
    alias = ' (aka device 0 - built-in keyboard)' if built_in else ''
    hub = '' if empty else (
        f'    {hub_id}: Synthetic alphabetic keyboard{alias}\n'
        f'      Classes: {classes}\n'
        '      Path: /dev/input/event1\n'
        f'      Enabled: {str(enabled).lower()}\n'
        '      Descriptor: synthetic-only\n'
        '      Location: synthetic\n'
        '      ControllerNumber: 0\n'
        '      UniqueId: \n'
        '      Identifier: bus=0x0006, vendor=0x0000, product=0x0000, version=0x0000, bluetoothAddress=<none>\n'
        '      KeyLayoutFile: /system/usr/keylayout/Generic.kl\n'
        '      KeyCharacterMapFile: /system/usr/keychars/Virtual.kcm\n'
        '      ConfigurationFile: <none>\n'
        '      VideoDevice: <none>\n'
        '      SysfsDevicePath: <none>\n')
    reader = '' if empty else (
        f'  Device {device_id}: Synthetic alphabetic keyboard\n'
        f'    EventHub Devices: [ {raw_id} ] \n'
        '    Generation: 3\n'
        f'    IsExternal: {str(external).lower()}\n'
        f'    IsVirtualDevice: {str(virtual_device).lower()}\n'
        '    IsWaking: false\n'
        '    AssociatedDisplayPort: <none>\n'
        '    AssociatedDisplayUniqueIdByPort: <none>\n'
        '    AssociatedDisplayUniqueIdByDescriptor: <none>\n'
        '    SysfsRootPath:     <none>\n'
        '    HasMic:     false\n'
        f'    Sources: {sources}\n'
        f'    KeyboardType: {keyboard_type}\n'
        '    ControllerNum: 0\n'
        '    Keyboard Input Mapper:\n'
        '      Parameters:\n'
        '        OrientationAware: false\n'
        '      Orientation: ROTATION_0\n'
        '      KeyDowns: 0 keys currently down\n'
        '      MetaState: 0x0\n'
        '      KeyboardLayoutInfo: <not set>\n')
    return (
        'INPUT MANAGER (dumpsys input)\n\n'
        'Input Manager State:\n'
        '  Display not interactive: {}\n'
        '  System UI Lights Out: false\n'
        '  Pointer Speed: 0\n'
        '  Display with Mouse Scaling Disabled: {}\n'
        '  Pointer Gestures Enabled: true\n'
        '  Pointer Capture: NONE, seq=0\n\n'
        'Event Hub State:\n'
        f'  BuiltInKeyboardId: {builtin_id}\n'
        '  Devices:\n' + hub +
        '  Unattached video devices:\n'
        '    <none>\n\n'
        f'Input Reader State (Nums of device: {0 if empty else 1}):\n' + reader +
        '  NextTimeout: 9223372036854775807\n'
        '  Configuration:\n'
        '    ExcludedDeviceNames: []\n'
        '    VirtualKeyQuietTime: 0.0ms\n'
        '    WheelVelocityControlParameters: scale=1.000, lowThreshold=15.000, highThreshold=50.000, acceleration=4.000\n'
        '    PointerGesture:\n'
        '      Enabled: true\n'
        '      Viewports:\n\n'
        'UnwantedInteractionBlocker:\n'
        '  mEnablePalmRejection: 0\n\n'
        'Input Dispatcher State:\n'
        '  DispatchEnabled: true\n'
        '  Device 1: unrelated dispatcher text\n'
        '    Sources: TOUCHSCREEN\n'
        '    KeyboardType: 0\n'
        '    IsExternal: true\n\n'
        'Input Manager Service (Java) State:\n'
        '  Synthetic unparsed service tail\n').encode('utf-8')


class InputStateTest(unittest.TestCase):
    def unavailable(self, data, reason=None):
        result = state.analyze(data)
        self.assertEqual('unavailable', result['status'])
        self.assertIsNone(result['devices'])
        self.assertIsNone(result['eventHubDevices'])
        self.assertEqual('unavailable', result['readerConfiguration']['status'])
        if reason:
            self.assertEqual(reason, result['reason'])
        return result

    def test_complete_capability_and_external_scope_are_recorded(self):
        value = state.analyze(synthetic_dump())
        self.assertEqual('recorded', value['status']); self.assertIsNone(value['reason'])
        device = value['devices'][0]
        self.assertEqual(1, device['id'])
        self.assertEqual([1], device['eventHubIds']); self.assertEqual([1], device['eventHubDeviceIds'])
        self.assertEqual(2, device['keyboardType'])
        self.assertEqual(['KEYBOARD', 'DPAD'], device['sources']['names'])
        self.assertTrue(value['eventHubDevices'][0]['enabled'])
        self.assertEqual(['KEYBOARD', 'ALPHAKEY', 'DPAD'], value['eventHubDevices'][0]['classes']['names'])
        self.assertFalse(value['scope']['deviceCapabilityProvesQwertyConfigurationCause'])
        self.assertFalse(value['scope']['rawPositiveResultMayBeUpgraded'])

    def test_configuration_fields_are_explicitly_not_printed(self):
        value = state.analyze(synthetic_dump())
        self.assertEqual({'status': 'not_printed', 'value': None}, value['readerConfiguration']['keyboard'])
        self.assertEqual({'status': 'not_printed', 'value': None}, value['readerConfiguration']['hardKeyboardHidden'])
        self.assertEqual('recorded', value['readerConfiguration']['status'])

    def test_disabled_and_non_alphabetic_devices_are_data_not_failure(self):
        value = state.analyze(synthetic_dump(keyboard_type=0, enabled=False, sources='UNKNOWN', classes='0x0'))
        self.assertEqual('recorded', value['status'])
        self.assertFalse(value['eventHubDevices'][0]['enabled'])
        self.assertEqual(0, value['devices'][0]['keyboardType'])
        self.assertEqual(['UNKNOWN'], value['devices'][0]['sources']['names'])

    def test_virtual_device_property_and_negative_id_are_not_conflated(self):
        for device_id, virtual in [(-1, False), (3, True)]:
            value = state.analyze(synthetic_dump(device_id=device_id, virtual_device=virtual))
            self.assertEqual('recorded', value['status'])
            self.assertEqual(device_id, value['devices'][0]['id'])
            self.assertIs(virtual, value['devices'][0]['isVirtualDevice'])

    def test_builtin_zero_alias_resolves_to_real_event_hub_id(self):
        value = state.analyze(synthetic_dump(built_in=True))
        self.assertEqual('recorded', value['status'])
        self.assertEqual([0], value['devices'][0]['eventHubIds'])
        self.assertEqual([42], value['devices'][0]['eventHubDeviceIds'])
        self.assertEqual(42, value['boundary']['builtInKeyboardId'])
        self.assertTrue(value['eventHubDevices'][0]['builtInKeyboardAlias'])

    def test_alias_mismatch_and_unresolved_id_are_unknown(self):
        for data in (
            synthetic_dump(built_in=True).replace(b' (aka device 0 - built-in keyboard)', b''),
            synthetic_dump().replace(b'BuiltInKeyboardId: -2', b'BuiltInKeyboardId: 9'),
            synthetic_dump().replace(b'EventHub Devices: [ 1 ] ', b'EventHub Devices: [ 0 ] '),
            synthetic_dump().replace(b'EventHub Devices: [ 1 ] ', b'EventHub Devices: [ 9 ] ')):
            self.unavailable(data)

    def test_unknown_hex_bits_preserved_without_interpreting_as_absent(self):
        value = state.analyze(synthetic_dump(sources='0x00000002', classes='KEYBOARD | 0x40000000'))
        self.assertEqual('recorded', value['status'])
        self.assertEqual('0x00000002', value['devices'][0]['sources']['unclassifiedHex'])
        self.assertEqual('0x40000000', value['eventHubDevices'][0]['classes']['unclassifiedHex'])

    def test_unique_complete_zero_reader_devices_is_explicit(self):
        value = state.analyze(synthetic_dump(empty=True))
        self.assertEqual('recorded', value['status'])
        self.assertEqual([], value['devices']); self.assertEqual([], value['eventHubDevices'])
        self.assertEqual(0, value['boundary']['declaredReaderDeviceCount'])

    def test_missing_reader_is_unknown_not_empty(self):
        self.unavailable(synthetic_dump().replace(b'Input Reader State (Nums of device: 1):', b'Reader omitted:'))

    def test_dispatcher_cannot_supply_missing_reader_field(self):
        data = synthetic_dump().replace(b'    KeyboardType: 2\n', b'')
        self.unavailable(data, 'READER_KEYBOARD_TYPE')

    def test_mapper_indentation_cannot_supply_reader_field(self):
        data = synthetic_dump().replace(b'    KeyboardType: 2\n', b'      KeyboardType: 2\n')
        self.unavailable(data, 'READER_KEYBOARD_TYPE')

    def test_duplicate_header_or_wrong_order_is_unknown(self):
        data = synthetic_dump()
        for header in (b'Event Hub State:', b'Input Reader State (Nums of device: 1):',
                       b'Input Dispatcher State:', b'Input Manager Service (Java) State:'):
            self.unavailable(data.replace(header, header + b'\n' + header), 'SECTION_BOUNDARY')
        self.unavailable(data.replace(b'UnwantedInteractionBlocker:', b'Input Dispatcher State:')
                         .replace(b'Input Dispatcher State:\n  DispatchEnabled:', b'UnwantedInteractionBlocker:\n  DispatchEnabled:'),
                         'SECTION_ORDER')

    def test_duplicate_fields_and_invalid_values_are_unknown(self):
        for field in (b'    KeyboardType: 2', b'    IsExternal: false', b'    Sources: KEYBOARD | DPAD',
                      b'      Enabled: true', b'      Classes: KEYBOARD | ALPHAKEY | DPAD'):
            self.unavailable(synthetic_dump().replace(field, field + b'\n' + field, 1))
        for old, new in ((b'KeyboardType: 2', b'KeyboardType: 3'),
                         (b'IsExternal: false', b'IsExternal: 0'),
                         (b'Sources: KEYBOARD | DPAD', b'Sources: KEYBOARD | KEYBOARD'),
                         (b'Classes: KEYBOARD | ALPHAKEY | DPAD', b'Classes: NEW_UNKNOWN_NAME')):
            self.unavailable(synthetic_dump().replace(old, new))

    def test_declared_device_count_and_duplicate_ids_checked(self):
        data = synthetic_dump()
        self.unavailable(data.replace(b'Nums of device: 1', b'Nums of device: 2'), 'READER_DEVICE_COUNT')
        start = data.index(b'  Device 1:'); end = data.index(b'  NextTimeout:')
        duplicated = data[:end] + data[start:end] + data[end:]
        self.unavailable(duplicated.replace(b'Nums of device: 1', b'Nums of device: 2'), 'DUPLICATE_READER_DEVICE')
        start = data.index(b'    1: Synthetic'); end = data.index(b'  Unattached video devices:')
        self.unavailable(data[:end] + data[start:end] + data[end:], 'DUPLICATE_EVENT_HUB_DEVICE')

    def test_repeated_or_out_of_bounds_hub_references_unknown(self):
        for value in ('1 1', '2147483648', '-2147483649', ' '.join(str(i) for i in range(33))):
            self.unavailable(synthetic_dump().replace(b'[ 1 ] ', ('[ ' + value + ' ] ').encode()))

    def test_multiple_event_hub_devices_can_join_one_reader(self):
        data = synthetic_dump()
        start = data.index(b'    1: Synthetic'); end = data.index(b'  Unattached video devices:')
        extra = data[start:end].replace(b'    1: Synthetic', b'    2: Synthetic', 1)
        value = state.analyze((data[:end] + extra + data[end:]).replace(b'[ 1 ] ', b'[ 1 2 ] '))
        self.assertEqual('recorded', value['status'])
        self.assertEqual([1, 2], value['devices'][0]['eventHubDeviceIds'])

    def test_unattached_event_hub_device_is_preserved_not_assigned_by_name(self):
        data = synthetic_dump()
        start = data.index(b'    1: Synthetic'); end = data.index(b'  Unattached video devices:')
        extra = data[start:end].replace(b'    1: Synthetic', b'    2: Synthetic', 1)
        value = state.analyze(data[:end] + extra + data[end:])
        self.assertEqual('recorded', value['status'])
        self.assertEqual([1], value['devices'][0]['eventHubDeviceIds'])
        self.assertEqual([1, 2], [d['id'] for d in value['eventHubDevices']])

    def test_input_and_subsection_hashes_use_original_crlf_bytes(self):
        for data in (synthetic_dump(), synthetic_dump().replace(b'\n', b'\r\n')):
            value = state.analyze(data)
            self.assertEqual('recorded', value['status'])
            self.assertEqual(hashlib.sha256(data).hexdigest(), value['input']['sha256'])
            hub = data[data.index(b'Event Hub State:'):data.index(b'Input Reader State')]
            reader = data[data.index(b'Input Reader State'):data.index(b'UnwantedInteractionBlocker:')]
            for label, body in (('eventHub', hub), ('reader', reader)):
                self.assertEqual(len(body), value['boundary'][label + 'Bytes'])
                self.assertEqual(hashlib.sha256(body).hexdigest(), value['boundary'][label + 'Sha256'])

    def test_truncation_before_selected_section_terminal_is_unknown(self):
        data = synthetic_dump()
        end = data.index(b'Input Manager Service (Java) State:') + len(b'Input Manager Service (Java) State:')
        for size in range(0, end, 17):
            self.unavailable(data[:size])
        self.unavailable(data[:-1], 'INPUT_INCOMPLETE')

    def test_missing_device_and_configuration_terminals_unknown(self):
        for field in (b'      SysfsDevicePath: <none>\n', b'    ControllerNum: 0\n', b'      Viewports:\n'):
            self.unavailable(synthetic_dump().replace(field, b''))

    def test_errors_exit_zero_could_hide_are_rejected(self):
        for marker in state.ERROR_MARKERS:
            self.unavailable(synthetic_dump() + marker.encode() + b'\n', 'DUMP_ERROR')

    def test_bounds_encoding_control_characters_and_types(self):
        for data in (None, '', bytearray(b'a'), b'', b'a' * state.MAX_DUMP_BYTES,
                     b'\xff\n', synthetic_dump() + b'\x00\n', synthetic_dump() + b'\x01\n',
                     synthetic_dump() + b'x' * (state.MAX_LINE_CHARS + 1) + b'\n',
                     synthetic_dump() + b'\n' * state.MAX_LINES):
            self.unavailable(data)
        self.unavailable(synthetic_dump().replace(b'Nums of device: 1', b'Nums of device: 129'), 'DEVICE_BOUND')

    def test_device_names_are_bounded_and_keep_printed_unicode(self):
        data = synthetic_dump().replace(b'Synthetic alphabetic keyboard', 'Synthetic 键盘'.encode())
        value = state.analyze(data); self.assertEqual('recorded', value['status'])
        self.assertEqual('Synthetic 键盘', value['devices'][0]['name'])
        self.unavailable(synthetic_dump().replace(b'Synthetic alphabetic keyboard', b'x' * 257), 'DEVICE_NAME')

    def test_dump_output_cannot_supply_configuration_or_cause_claim(self):
        data = synthetic_dump().replace(b'    ExcludedDeviceNames:', b'    keyboard: 2\n    hardKeyboardHidden: 1\n    ExcludedDeviceNames:')
        value = state.analyze(data)
        self.assertEqual('recorded', value['status'])
        self.assertIsNone(value['readerConfiguration']['keyboard']['value'])
        self.assertFalse(value['scope']['deviceCapabilityProvesQwertyConfigurationCause'])

    def test_random_bytes_fail_closed_without_exception(self):
        rng = random.Random(37)
        for _ in range(100):
            data = bytes(rng.randrange(256) for _ in range(rng.randrange(512)))
            self.unavailable(data)

    def test_json_result_is_bounded(self):
        self.assertLess(len(json.dumps(state.analyze(synthetic_dump())).encode()), 8192)


if __name__ == '__main__':
    unittest.main()
