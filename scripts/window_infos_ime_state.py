#!/usr/bin/env python3
"""Pure, bounded parser for the fixed AOSP17 ordinary IMMS/current-IME text dump.

The caller must first require complete command output, exit zero, no truncation and
its frozen timeout. UID is external configured-package evidence, not a UID read
from the current IME process. This later dump cannot upgrade a raw-window result.

Field grammar comes from frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
InputMethodManagerService, InputMethodBindingController, InputMethodService,
Configuration and EditorInfo. This source review does not attest the device image.
"""
import hashlib
import json
import re

MAX_DUMP_BYTES = 262144
MAX_LINES = 8192
MAX_LINE_CHARS = 8192
MAX_SERVICE_CHARS = 65536
MAX_REGION_RECTS = 64
FIXTURE_PACKAGE = 'dev.agentos.compat'
FIELD_NAMES = ('configuration', 'mViewsCreated', 'mInputStarted', 'mInputViewStarted', 'mShowInputRequested',
               'mIsInputViewShown', 'mDecorViewVisible', 'mWindowVisible', 'mCandidatesVisibility',
               'contentTopInsets', 'visibleTopInsets', 'touchableInsets', 'touchableRegion',
               'settingsObserver', 'inputEditorInfo')
SCOPE = {
    'observation': 'later_non_atomic_current_ime_service_dump',
    'configuredUidSource': 'external_expected_only',
    'currentImeProcessUidVerified': False,
    'rawWindowBinderAssociation': False,
    'rawPositiveResultMayBeUpgraded': False,
    'settingsObserverIsLiveSettingRead': False,
    'computedInsetsAreFinalWmsOrSfRegion': False,
    'viewsCreatedProvesNonNullInputView': False,
    'sourceImageEquivalenceVerified': False,
}
ERROR_MARKERS = (
    "*** SERVICE 'input_method' DUMP TIMEOUT (",
    "Can't find service: input_method",
    "Error with service 'input_method' while ",
    'Failed to create pipe to dump service info for input_method:',
    'Error in poll while dumping service input_method :',
    'Failed to read while dumping service input_method:',
    'Failed to write while dumping service input_method:',
    'Error: invalid timeout(seconds) number:',
    'Error: invalid timeout(milliseconds) number:',
    'usage: dumpsys',
    'Failed to dump input method client:',
    'Failed to dump input method client in focused window:',
    'Failed to dump input method service:',
    'No input method client.',
    'No input method service.',
    "Warning: Current input method client doesn't match the last focused window.",
    'Exception:', 'Permission Denial:', 'Timeout waiting for dump', 'Interrupted waiting for dump',
)
PACKAGE = r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+'
CLASS = r'[A-Za-z_$][A-Za-z0-9_.$]*'
NUMBER = r'-?(?:0|[1-9][0-9]{0,9})'
BOOL = r'(true|false)'
# A subclass may append unrelated details after super.dump(). Repeated fields at
# the exact base indentation make the observation ambiguous even after its footer.
TARGET_PREFIXES = (
    '  Configuration=', '  mViewsCreated=', '  mDecorViewVisible=', '  mInputStarted=',
    '  mShowInputRequested=', '  mCandidatesVisibility=', '  mIsInputViewShown=',
    '  Last computed insets:', '    contentTopInsets=', '  mSettingsObserver=',
    '  mInputEditorInfo:', '    inputType=', '    packageName=',
    '  mInputViewStarted=', '  mWindowVisible=', '    visibleTopInsets=',
    '    touchableInsets=', '    touchableRegion=',
)
STANDALONE_SECONDARY_PREFIXES = (
    '  mInputViewStarted=', '  mWindowVisible=', '    visibleTopInsets=',
    '    touchableInsets=', '    touchableRegion=',
)


class Incomplete(ValueError):
    pass


def require(condition, reason):
    if not condition:
        raise Incomplete(reason)


def component_identity(component, package):
    require(isinstance(package, str) and len(package) <= 128 and re.fullmatch(PACKAGE, package), 'EXPECTED_IDENTITY_INVALID')
    require(isinstance(component, str) and len(component) <= 320 and component.count('/') == 1, 'EXPECTED_IDENTITY_INVALID')
    pkg, cls = component.split('/')
    require(pkg == package and re.fullmatch(r'\.?' + CLASS, cls), 'EXPECTED_IDENTITY_INVALID')
    expanded = pkg + cls if cls.startswith('.') else cls
    require(expanded.startswith(pkg + '.') and len(expanded) <= 256, 'EXPECTED_IDENTITY_INVALID')
    return pkg + '/' + expanded, expanded


def normalize_component(component):
    require(isinstance(component, str) and component.count('/') == 1, 'COMPONENT_FORMAT')
    return component_identity(component, component.split('/')[0])[0]


def integer(value):
    require(re.fullmatch(NUMBER, value), 'INTEGER_FORMAT')
    parsed = int(value)
    require(-(2**31) <= parsed <= 2**31 - 1, 'INTEGER_RANGE')
    return parsed


def empty_analysis(data):
    bounded = type(data) is bytes and len(data) < MAX_DUMP_BYTES
    return {
        'schemaVersion': 1, 'status': 'unavailable', 'reason': 'NOT_PARSED', 'scope': dict(SCOPE),
        'input': {'bytes': len(data) if type(data) is bytes else None,
                  'sha256': hashlib.sha256(data).hexdigest() if bounded else None},
        'identity': {'matched': False, 'expectedComponent': None, 'expectedPackage': None,
                     'configuredUid': None, 'uidSource': 'external_expected_only', 'expectedUserId': None,
                     'managerUserId': None, 'userId': None, 'selectedImeId': None, 'currentImeId': None,
                     'currentDisplayId': None, 'currentImePresent': None, 'serviceClass': None},
        'boundary': {'managerHeaderCount': None, 'userHeaderCount': None, 'bindingControllerCount': None,
                     'serviceHeaderCount': None, 'serviceTerminalCount': None,
                     'serviceBodyBytes': None, 'serviceBodySha256': None},
        'fields': {name: {'status': 'unavailable', 'value': None} for name in FIELD_NAMES},
    }


def unique_line(lines, prefix, pattern, reason):
    selected = [line for line in lines if line.startswith(prefix)]
    require(len(selected) == 1, reason)
    match = re.fullmatch(pattern, selected[0])
    require(match is not None, reason)
    return match


def balanced(value):
    depth = 0
    for char in value:
        if char == '{':
            depth += 1
            if depth > 8:
                return False
        elif char == '}':
            depth -= 1
            if depth < 0:
                return False
    return depth == 0


def configuration(value):
    require(len(value) <= 4096 and value.startswith('{') and value.endswith('}') and balanced(value), 'CONFIGURATION_FORMAT')
    pattern = r'(?<!\S)(\?keyb|-keyb|qwerty|12key|keys=' + NUMBER + r')/(\?|v|h|s|' + NUMBER + r')/(\?|v|h|' + NUMBER + r')(?=\s|\})'
    matches = re.findall(pattern, value)
    require(len(matches) == 1, 'CONFIGURATION_KEYBOARD_UNIQUE')
    keyboard, hidden, hard = matches[0]
    key_values = {'?keyb': 0, '-keyb': 1, 'qwerty': 2, '12key': 3}
    hidden_values = {'?': 0, 'v': 1, 'h': 2, 's': 3}
    hard_values = {'?': 0, 'v': 1, 'h': 2}
    return {'keyboard': key_values[keyboard] if keyboard in key_values else integer(keyboard[5:]),
            'keyboardHidden': hidden_values[hidden] if hidden in hidden_values else integer(hidden),
            'hardKeyboardHidden': hard_values[hard] if hard in hard_values else integer(hard),
            'printedToken': '/'.join(matches[0])}


def region(value):
    require(len(value) <= 4096 and value.startswith('SkRegion(') and value.endswith(')'), 'REGION_FORMAT')
    body = value[len('SkRegion('):-1]
    pattern = r'\((' + NUMBER + '),(' + NUMBER + '),(' + NUMBER + '),(' + NUMBER + r')\)'
    matches = list(re.finditer(pattern, body))
    require(len(matches) <= MAX_REGION_RECTS and ''.join(m.group(0) for m in matches) == body, 'REGION_RECT_FORMAT')
    rects = [[integer(v) for v in match.groups()] for match in matches]
    require(all(r[0] < r[2] and r[1] < r[3] for r in rects), 'REGION_NONEMPTY_RECT')
    return {'rectangles': rects, 'empty': not rects, 'coordinateOrder': 'left_top_right_bottom',
            'scope': 'InputMethodService_mTmpInsets_touchableRegion_only'}


def settings_observer(value):
    if value == 'null':
        return {'observerPresent': False, 'cachedShowImeWithHardKeyboard': None, 'cacheMeaning': 'OBSERVER_NULL'}
    match = re.fullmatch(r'SettingsObserver\{mShowImeWithHardKeyboard=([012])\}', value)
    require(match is not None, 'SETTINGS_OBSERVER_FORMAT')
    number = int(match[1])
    return {'observerPresent': True, 'cachedShowImeWithHardKeyboard': number,
            'cacheMeaning': ('UNKNOWN', 'FALSE', 'TRUE')[number]}


def parse_fields(body, result):
    """Only fixed-depth whole lines inside the selected complete IMS body are eligible."""
    fields = result['fields']; duplicate_seen = False
    def read(names, prefix, pattern, converters):
        nonlocal duplicate_seen
        selected = [line for line in body if line.startswith(prefix)]
        if not selected:
            for name in names: fields[name] = {'status': 'missing', 'value': None}
            return
        if len(selected) != 1:
            duplicate_seen = True
            for name in names: fields[name] = {'status': 'invalid', 'value': None}
            return
        match = re.fullmatch(pattern, selected[0])
        try:
            require(match is not None, 'FIELD_FORMAT')
            values = [convert(match) for convert in converters]
        except (Incomplete, ValueError, IndexError, TypeError):
            for name in names: fields[name] = {'status': 'invalid', 'value': None}
            return
        for name, value in zip(names, values): fields[name] = {'status': 'present', 'value': value}
    boolean = lambda n: lambda match: match[n] == 'true'
    read(['configuration'], '  Configuration=', r'  Configuration=(.*)', [lambda m: configuration(m[1])])
    read(['mViewsCreated'], '  mViewsCreated=', r'  mViewsCreated=' + BOOL, [boolean(1)])
    read(['mDecorViewVisible', 'mWindowVisible'], '  mDecorViewVisible=',
         r'  mDecorViewVisible=' + BOOL + r' mDecorViewWasVisible=' + BOOL + r' mWindowVisible=' + BOOL + r' mInShowWindow=' + BOOL,
         [boolean(1), boolean(3)])
    read(['mInputStarted', 'mInputViewStarted'], '  mInputStarted=',
         r'  mInputStarted=' + BOOL + r' mInputViewStarted=' + BOOL + r' mCandidatesViewStarted=' + BOOL,
         [boolean(1), boolean(2)])
    read(['mShowInputRequested'], '  mShowInputRequested=',
         r'  mShowInputRequested=' + BOOL + r' mLastShowInputRequested=' + BOOL + r' mShowInputFlags=0x[0-9a-f]{1,8}', [boolean(1)])
    read(['mCandidatesVisibility'], '  mCandidatesVisibility=',
         r'  mCandidatesVisibility=(' + NUMBER + r') mFullscreenApplied=' + BOOL + r' mIsFullscreen=' + BOOL + r' mExtractViewHidden=' + BOOL,
         [lambda m: integer(m[1])])
    read(['mIsInputViewShown'], '  mIsInputViewShown=',
         r'  mIsInputViewShown=' + BOOL + r' mStatusIcon=' + NUMBER, [boolean(1)])
    insets_headers = [i for i, line in enumerate(body) if line.startswith('  Last computed insets:')]
    insets_names = ['contentTopInsets', 'visibleTopInsets', 'touchableInsets', 'touchableRegion']
    if len(insets_headers) == 1 and body[insets_headers[0]] == '  Last computed insets:' \
            and insets_headers[0] + 1 < len(body) and body[insets_headers[0] + 1].startswith('    contentTopInsets='):
        read(insets_names, '    contentTopInsets=',
             r'    contentTopInsets=(' + NUMBER + r') visibleTopInsets=(' + NUMBER + r') touchableInsets=(' + NUMBER + r') touchableRegion=(.*)',
             [lambda m: integer(m[1]), lambda m: integer(m[2]), lambda m: integer(m[3]), lambda m: region(m[4])])
    else:
        duplicate_seen |= len(insets_headers) > 1
        for name in insets_names: fields[name] = {'status': 'missing' if not insets_headers else 'invalid', 'value': None}
    read(['settingsObserver'], '  mSettingsObserver=', r'  mSettingsObserver=(.*)', [lambda m: settings_observer(m[1])])
    editors = [(i, line) for i, line in enumerate(body) if line.startswith('  mInputEditorInfo:')]
    if not editors: fields['inputEditorInfo'] = {'status': 'missing', 'value': None}
    elif len(editors) != 1:
        duplicate_seen = True; fields['inputEditorInfo'] = {'status': 'invalid', 'value': None}
    else:
        index, header = editors[0]
        end = index + 1
        while end < len(body) and (not body[end] or body[end].startswith('    ')):
            end += 1
        children = [i for i, line in enumerate(body) if line.startswith(('    inputType=', '    packageName='))]
        orphan = any(not index < child < end for child in children)
        if orphan or (header == '  mInputEditorInfo: null' and children):
            fields['inputEditorInfo'] = {'status': 'invalid', 'value': None}
        elif header == '  mInputEditorInfo: null':
            fields['inputEditorInfo'] = {'status': 'present', 'value': {'isNull': True, 'inputType': None,
                                                                     'packageName': None, 'fixturePackageMatches': None}}
        elif header != '  mInputEditorInfo:': fields['inputEditorInfo'] = {'status': 'invalid', 'value': None}
        else:
            editor_lines = body[index + 1:end]
            try:
                input_type = unique_line(editor_lines, '    inputType=', r'    inputType=0x([0-9a-f]{1,8}) imeOptions=0x[0-9a-f]{1,8} privateImeOptions=.*', 'EDITOR_INPUT_TYPE')
                pkg = unique_line(editor_lines, '    packageName=', r'    packageName=(' + PACKAGE + r'|null) autofillId=\S+ fieldId=' + NUMBER + r' fieldName=.*', 'EDITOR_PACKAGE')
                package = None if pkg[1] == 'null' else pkg[1]
                fields['inputEditorInfo'] = {'status': 'present', 'value': {'isNull': False,
                     'inputType': int(input_type[1], 16), 'packageName': package,
                     'fixturePackageMatches': None if package is None else package == FIXTURE_PACKAGE}}
            except Incomplete:
                fields['inputEditorInfo'] = {'status': 'invalid', 'value': None}
    if duplicate_seen: result['reason'] = 'DUPLICATE_FIELD'
    elif any(f['status'] == 'invalid' for f in fields.values()): result['reason'] = 'INVALID_FIELD'
    elif any(f['status'] != 'present' for f in fields.values()): result['reason'] = 'MISSING_FIELD'
    else: result['status'], result['reason'] = 'recorded', None


def _analyze(data, component, package, uid, user_id, result):
    normalized, expanded = component_identity(component, package)
    require(type(uid) is int and 0 < uid < 2**31 and type(user_id) is int and 0 <= user_id < 21475
            and uid // 100000 == user_id, 'EXPECTED_IDENTITY_INVALID')
    result['identity'].update(expectedComponent=normalized, expectedPackage=package, configuredUid=uid,
                              expectedUserId=user_id)
    require(type(data) is bytes, 'INPUT_TYPE')
    require(0 < len(data) < MAX_DUMP_BYTES, 'INPUT_SIZE_LIMIT')
    try: text = data.decode('utf-8')
    except UnicodeError: raise Incomplete('INPUT_UTF8') from None
    require(text.endswith('\n'), 'INCOMPLETE_LAST_LINE')
    require(not any(ord(c) < 32 and c not in '\n\r\t' for c in text), 'CONTROL_CHARACTER')
    # Captured stderr is part of this input: error text invalidates the whole observation even with exit0.
    require(not any(marker in text for marker in ERROR_MARKERS), 'DUMP_ERROR_MARKER')
    original_lines = text.splitlines(keepends=True)
    lines = [line.removesuffix('\n').removesuffix('\r') for line in original_lines]
    require(len(lines) <= MAX_LINES and all(len(line) <= MAX_LINE_CHARS for line in lines), 'LINE_LIMIT')
    boundary = result['boundary']; identity = result['identity']
    manager = [i for i, line in enumerate(lines) if line.startswith('Input Method Manager Service state:')]
    boundary['managerHeaderCount'] = len(manager)
    require(len(manager) == 1 and lines[manager[0]] == 'Input Method Manager Service state:'
            and not any(line.strip() for line in lines[:manager[0]]), 'MANAGER_BOUNDARY')
    users = [(i, line) for i, line in enumerate(lines) if line.startswith('  UserId=')]
    boundary['userHeaderCount'] = len(users)
    require(len(users) == 1 and re.fullmatch(r'  UserId=' + str(user_id), users[0][1]), 'USER_BOUNDARY')
    user_index = users[0][0]; identity['userId'] = user_id
    require(user_index > manager[0], 'USER_ORDER')
    current = unique_line(lines[manager[0] + 1:user_index], '  mCurrentImeUserId=',
                          r'  mCurrentImeUserId=(' + NUMBER + ')', 'MANAGER_USER')
    identity['managerUserId'] = integer(current[1]); require(identity['managerUserId'] == user_id, 'MANAGER_USER_MISMATCH')
    user_lines = lines[user_index + 1:]
    bindings = [i for i, line in enumerate(user_lines) if line.startswith('    mBindingController:')]
    boundary['bindingControllerCount'] = len(bindings)
    require(len(bindings) == 1 and user_lines[bindings[0]] == '    mBindingController:', 'BINDING_BOUNDARY')
    binding = []
    for line in user_lines[bindings[0] + 1:]:
        if line and not line.startswith('      '): break
        binding.append(line)
    selected = unique_line(binding, '      mSelectedImeId=', r'      mSelectedImeId=(\S+)', 'SELECTED_IME')
    current = unique_line(binding, '      mCurImeId=', r'      mCurImeId=(\S+)', 'CURRENT_IME')
    display = unique_line(binding, '      mCurDisplayId=', r'      mCurDisplayId=(' + NUMBER + ')', 'CURRENT_DISPLAY')
    ime = unique_line(binding, '      mCurIme=', r'      mCurIme=(\S{1,256})', 'CURRENT_IME_REFERENCE')
    identity.update(selectedImeId=normalize_component(selected[1]), currentImeId=normalize_component(current[1]),
                    currentDisplayId=integer(display[1]), currentImePresent=ime[1] != 'null')
    require(identity['selectedImeId'] == normalized and identity['currentImeId'] == normalized
            and identity['currentDisplayId'] == 0 and identity['currentImePresent'], 'BINDING_IDENTITY_MISMATCH')
    services = [i for i, line in enumerate(lines) if line.startswith('Input method service state for ')]
    boundary['serviceHeaderCount'] = len(services)
    require(len(services) == 1 and services[0] > user_index + 1 + bindings[0], 'SERVICE_BOUNDARY')
    header = re.fullmatch(r'Input method service state for (' + CLASS + r')@[0-9a-f]{1,16}:', lines[services[0]])
    require(header is not None, 'SERVICE_HEADER_FORMAT')
    identity['serviceClass'] = header[1]; require(header[1] == expanded, 'SERVICE_CLASS_MISMATCH')
    terminals = [i for i, line in enumerate(lines) if line.startswith('  mBackCallbackRegistered=')]
    boundary['serviceTerminalCount'] = len(terminals)
    require(len(terminals) == 1 and terminals[0] > services[0]
            and re.fullmatch(r'  mBackCallbackRegistered=' + BOOL, lines[terminals[0]]), 'SERVICE_TERMINAL')
    # The selected IME is the final IPC in the one-user ordinary dump. A following peer
    # header is not accepted as continuation of this service. Custom trailing IME details
    # may remain, but cannot contain another manager/user/IME identity section.
    require(not any(line.startswith(('Input Method Manager Service state:', '  UserId=', 'Input method service state for '))
                    for line in lines[terminals[0] + 1:]), 'SERVICE_TRAILING_PEER')
    require(not any(line.startswith(TARGET_PREFIXES) for line in lines[terminals[0] + 1:]),
            'SERVICE_TRAILING_TARGET_FIELD')
    body = lines[services[0] + 1:terminals[0]]
    require(all(not line or line.startswith('  ') for line in body), 'SERVICE_BODY_BOUNDARY')
    require(not any(line.startswith(STANDALONE_SECONDARY_PREFIXES) for line in body), 'ORPHAN_TARGET_FIELD')
    body_bytes = ''.join(original_lines[services[0] + 1:terminals[0]]).encode('utf-8')
    require(len(body_bytes) <= MAX_SERVICE_CHARS, 'SERVICE_BODY_LIMIT')
    boundary.update(serviceBodyBytes=len(body_bytes), serviceBodySha256=hashlib.sha256(body_bytes).hexdigest())
    identity['matched'] = True
    parse_fields(body, result)


def analyze(data, component, package, uid, user_id=0):
    """Return a deterministic observation or explicit unavailable state; never infer a missing false.

    ``uid`` must be the independently verified configured-package UID. No UID in
    InputBinding is interpreted as the IME UID: that field belongs to the client.
    The caller must not call this on timed-out/truncated/nonzero command output.
    """
    result = empty_analysis(data)
    try: _analyze(data, component, package, uid, user_id, result)
    except Incomplete as failure: result['reason'] = str(failure)
    except Exception: result['reason'] = 'PARSER_EXCEPTION'
    return result


def validate_analysis(value, data, component, package, uid, user_id=0):
    expected = analyze(data, component, package, uid, user_id)
    if json.dumps(value, sort_keys=True, separators=(',', ':')) != json.dumps(expected, sort_keys=True, separators=(',', ':')):
        raise ValueError('IME_STATE_ANALYSIS_MISMATCH')
    return expected
