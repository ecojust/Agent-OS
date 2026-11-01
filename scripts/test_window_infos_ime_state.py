#!/usr/bin/env python3
"""Synthetic fixed-source text fixtures, never device evidence."""
import copy
import hashlib
import json
import random
import unittest

import window_infos_ime_state as ims


COMPONENT = 'org.example.ime/.Ime'
PACKAGE = 'org.example.ime'
UID = 10042


def synthetic_dump(component=COMPONENT, *, user_id=0, keyboard='-keyb/v/h',
                   settings_cache=1, region='SkRegion()', input_view_started=False,
                   input_view_shown=False, editor_package='dev.agentos.compat',
                   editor_null=False):
    """Return a source-shaped *synthetic* ordinary dump for parser/host/archive tests.

    It deliberately reports a visible decor with no started/shown input view and
    VISIBLE insets plus an empty IMS region. Those negative values are diagnostic
    data, not incomplete capture, and do not establish final WMS/SF region state.
    """
    package, cls = component.split('/')
    expanded = package + cls if cls.startswith('.') else cls
    editor = ('  mInputEditorInfo: null\n' if editor_null else
              '  mInputEditorInfo:\n'
              '    inputType=0x20001 imeOptions=0x12000000 privateImeOptions=null\n'
              '    actionLabel=null actionId=0\n'
              '    initialSelStart=0 initialSelEnd=0 initialToolType=0 initialCapsMode=0x0\n'
              '    hintText=null label=null\n'
              f'    packageName={editor_package} autofillId=null fieldId=42 fieldName=null\n'
              '    hintLocales=null\n'
              '    supportedHandwritingGestureTypes=\n'
              '    supportedHandwritingGesturePreviewTypes=\n'
              '    isStylusHandwritingEnabled=false\n'
              '    writingToolsEnabled=true\n'
              '    contentMimeTypes=null\n')
    observer = ('null' if settings_cache is None else
                'SettingsObserver{mShowImeWithHardKeyboard=' + str(settings_cache) + '}')
    text = (
        'Input Method Manager Service state:\n'
        '  mSystemReady=true\n'
        '  mInteractive=true\n'
        '  mConcurrentMultiUserModeEnabled=false\n'
        '  mPreventImeStartupUnlessTextEditor=true\n'
        f'  mCurrentImeUserId={user_id}\n'
        '  mStylusIds=[]\n'
        '  mMenuController:\n'
        '    mShowImeWithHardKeyboard=false\n'
        '  mStartInputHistory:\n'
        '    mInputStarted=false mInputViewStarted=true mCandidatesViewStarted=true\n'
        '  mImeTrackerService#History:\n'
        f'  UserId={user_id}\n'
        '    mBindingController:\n'
        f'      mSelectedImeId={component}\n'
        '      mCurrentSubtype=null\n'
        '      mCurSeq=1\n'
        f'      mCurImeId={component}\n'
        '      mCurDisplayId=0\n'
        '      mCurIme=com.android.server.inputmethod.IInputMethodInvoker@1234\n'
        '    mCurClient=ClientState{abc}\n'
        '    mFocusedWindowPerceptible=true\n'
        '    mBoundToMethod=true\n'
        '    Input Methods:\n'
        '\n'
        'Input method client state for android.view.inputmethod.InputMethodManager@1234:\n'
        '  mInputStarted=false mInputViewStarted=true mCandidatesViewStarted=true\n'
        '  Configuration={1.0 qwerty/h/v}\n'
        '\n'
        f'Input method service state for {expanded}@abc123:\n'
        '  mViewsCreated=true\n'
        '  mDecorViewVisible=true mDecorViewWasVisible=false mWindowVisible=true mInShowWindow=false\n'
        f'  Configuration={{1.0 0mcc0mnc [en_US] ldltr sw320dp w320dp h640dp 160dpi nrml long port finger {keyboard} -nav/h winConfig={{ mBounds=Rect(0, 0 - 320, 640) }} s.8}}\n'
        '  mToken=android.os.BinderProxy@0123\n'
        '  mInputBinding=InputBinding{android.os.BinderProxy@4567 / uid 10277 / pid 1730}\n'
        '  mInputConnection=android.view.inputmethod.RemoteInputConnection@0042\n'
        '  mStartedInputConnection=android.view.inputmethod.RemoteInputConnection@0042\n'
        f'  mInputStarted=true mInputViewStarted={str(input_view_started).lower()} mCandidatesViewStarted=false\n'
        + editor +
        '  mShowInputRequested=true mLastShowInputRequested=false mShowInputFlags=0x0\n'
        '  mCandidatesVisibility=8 mFullscreenApplied=false mIsFullscreen=false mExtractViewHidden=false\n'
        '  mExtractedText: null\n'
        '  mExtractedToken=0\n'
        f'  mIsInputViewShown={str(input_view_shown).lower()} mStatusIcon=0\n'
        '  Last computed insets:\n'
        f'    contentTopInsets=640 visibleTopInsets=640 touchableInsets=2 touchableRegion={region}\n'
        f'  mSettingsObserver={observer}\n'
        '  mNavigationBarController={mImeDrawsImeNavBar=false}\n'
        '  mBackCallbackRegistered=false\n'
    )
    return text.encode('utf-8')


class ImeStateTest(unittest.TestCase):
    def analyze(self, data=None, **kwargs):
        args = dict(component=COMPONENT, package=PACKAGE, uid=UID, user_id=0)
        args.update(kwargs)
        return ims.analyze(synthetic_dump() if data is None else data, **args)

    def unavailable(self, data, reason=None, **kwargs):
        value = self.analyze(data, **kwargs)
        self.assertEqual('unavailable', value['status'])
        self.assertIsNotNone(value['reason'])
        if reason: self.assertEqual(reason, value['reason'])
        return value

    def test_complete_negative_state_is_recorded_without_raw_upgrade(self):
        value = self.analyze()
        self.assertEqual('recorded', value['status'])
        self.assertIsNone(value['reason'])
        self.assertTrue(value['identity']['matched'])
        fields = value['fields']
        self.assertFalse(fields['mInputViewStarted']['value'])
        self.assertFalse(fields['mIsInputViewShown']['value'])
        self.assertTrue(fields['mDecorViewVisible']['value'])
        self.assertTrue(fields['mViewsCreated']['value'])
        self.assertEqual(2, fields['touchableInsets']['value'])
        self.assertTrue(fields['touchableRegion']['value']['empty'])
        self.assertEqual('FALSE', fields['settingsObserver']['value']['cacheMeaning'])
        self.assertFalse(value['scope']['computedInsetsAreFinalWmsOrSfRegion'])
        self.assertFalse(value['scope']['rawPositiveResultMayBeUpgraded'])
        self.assertFalse(value['scope']['sourceImageEquivalenceVerified'])
        self.assertFalse(value['scope']['viewsCreatedProvesNonNullInputView'])

    def test_input_bytes_and_service_body_have_separate_hashes(self):
        data = synthetic_dump(); value = self.analyze(data)
        self.assertEqual(hashlib.sha256(data).hexdigest(), value['input']['sha256'])
        body = data.split(b'Input method service state for ', 1)[1].split(b'\n', 1)[1].split(b'  mBackCallbackRegistered=', 1)[0]
        self.assertEqual(len(body), value['boundary']['serviceBodyBytes'])
        self.assertEqual(hashlib.sha256(body).hexdigest(), value['boundary']['serviceBodySha256'])

    def test_crlf_service_hash_uses_original_bytes(self):
        data = synthetic_dump().replace(b'\n', b'\r\n')
        value = self.analyze(data)
        self.assertEqual('recorded', value['status'])
        body = data.split(b'Input method service state for ', 1)[1].split(b'\r\n', 1)[1].split(b'  mBackCallbackRegistered=', 1)[0]
        self.assertEqual(len(body), value['boundary']['serviceBodyBytes'])
        self.assertEqual(hashlib.sha256(body).hexdigest(), value['boundary']['serviceBodySha256'])

    def test_short_and_expanded_components_normalize(self):
        value = self.analyze(synthetic_dump('org.example.ime/org.example.ime.Ime'))
        self.assertEqual('recorded', value['status'])
        self.assertEqual('org.example.ime/org.example.ime.Ime', value['identity']['currentImeId'])

    def test_uid_is_external_and_input_binding_is_client_uid(self):
        value = self.analyze()
        self.assertEqual(UID, value['identity']['configuredUid'])
        self.assertEqual('external_expected_only', value['identity']['uidSource'])
        self.assertFalse(value['scope']['currentImeProcessUidVerified'])
        self.assertFalse(value['scope']['rawWindowBinderAssociation'])
        self.assertNotIn('10277', json.dumps(value))
        for uid in (True, 0, -1, 100042, 2**31):
            with self.subTest(uid=uid): self.unavailable(synthetic_dump(), 'EXPECTED_IDENTITY_INVALID', uid=uid)

    def test_expected_identity_rejected_without_throwing(self):
        for kwargs in ({'component': None}, {'package': 'wrong.package'}, {'user_id': True},
                       {'component': 'org.example.ime/Ime'}, {'uid': '10042'}):
            with self.subTest(kwargs=kwargs): self.unavailable(synthetic_dump(), 'EXPECTED_IDENTITY_INVALID', **kwargs)

    def test_other_user_matching_external_uid_is_explicit(self):
        value = self.analyze(synthetic_dump(user_id=10), user_id=10, uid=1010042)
        self.assertEqual('recorded', value['status'])
        self.assertEqual(10, value['identity']['userId'])

    def test_client_and_history_fields_cannot_supply_missing_service_fields(self):
        data = synthetic_dump().replace(b'  mInputStarted=true mInputViewStarted=false mCandidatesViewStarted=false\n', b'')
        value = self.unavailable(data, 'MISSING_FIELD')
        self.assertEqual({'status': 'missing', 'value': None}, value['fields']['mInputStarted'])
        self.assertEqual({'status': 'present', 'value': True}, value['fields']['mDecorViewVisible'])

    def test_manager_user_and_binding_identity_must_be_unique_and_match(self):
        cases = (
            (b'  mCurrentImeUserId=0', b'  mCurrentImeUserId=1', 'MANAGER_USER_MISMATCH'),
            (b'  UserId=0\n', b'  UserId=0\n  UserId=0\n', 'USER_BOUNDARY'),
            (b'    mBindingController:\n', b'    mBindingController:\n    mBindingController:\n', 'BINDING_BOUNDARY'),
            (b'      mSelectedImeId=org.example.ime/.Ime', b'      mSelectedImeId=other.package/.Ime', 'BINDING_IDENTITY_MISMATCH'),
            (b'      mCurImeId=org.example.ime/.Ime', b'      mCurImeId=org.example.ime/.Other', 'BINDING_IDENTITY_MISMATCH'),
            (b'      mCurDisplayId=0', b'      mCurDisplayId=1', 'BINDING_IDENTITY_MISMATCH'),
            (b'      mCurIme=com.android.server.inputmethod.IInputMethodInvoker@1234', b'      mCurIme=null', 'BINDING_IDENTITY_MISMATCH'),
            (b'      mCurDisplayId=0', b'      mCurDisplayId=0\n      mCurDisplayId=0', 'CURRENT_DISPLAY'),
        )
        for old, new, reason in cases:
            with self.subTest(reason=reason): self.unavailable(synthetic_dump().replace(old, new), reason)

    def test_foreign_and_duplicate_service_headers_fail_before_fields(self):
        for data in (synthetic_dump().replace(b'org.example.ime.Ime@abc123:', b'org.example.ime.Other@abc123:'),
                     synthetic_dump() + b'Input method service state for org.example.ime.Ime@ff:\n'):
            value = self.unavailable(data)
            self.assertTrue(all(field['status'] == 'unavailable' for field in value['fields'].values()))

    def test_complete_service_requires_exact_unique_terminal_and_no_peer_body(self):
        cases = (synthetic_dump().replace(b'  mBackCallbackRegistered=false\n', b''),
                 synthetic_dump() + b'  mBackCallbackRegistered=false\n',
                 synthetic_dump().replace(b'  mBackCallbackRegistered=false', b'  mBackCallbackRegistered=FALSE'),
                 synthetic_dump().replace(b'  mViewsCreated=true\n', b'Other process state:\n'))
        for data in cases:
            with self.subTest(data=data[-90:]): self.unavailable(data)

    def test_target_fields_after_footer_are_rejected_even_if_identical(self):
        data = synthetic_dump()
        source_lines = data.splitlines()
        for prefix in ims.TARGET_PREFIXES:
            lines = [line for line in source_lines if line.startswith(prefix.encode())]
            if not lines:
                self.assertIn(prefix, ims.STANDALONE_SECONDARY_PREFIXES)
                lines = [(prefix + '0').encode()]
            with self.subTest(prefix=prefix):
                value = self.unavailable(data + lines[-1] + b'\n', 'SERVICE_TRAILING_TARGET_FIELD')
                self.assertTrue(all(field['status'] == 'unavailable' for field in value['fields'].values()))

    def test_standalone_composite_members_are_ambiguous(self):
        for prefix in ims.STANDALONE_SECONDARY_PREFIXES:
            data = synthetic_dump().replace(b'  mViewsCreated=true\n', b'  mViewsCreated=true\n' + (prefix + '0\n').encode())
            with self.subTest(prefix=prefix): self.unavailable(data, 'ORPHAN_TARGET_FIELD')

    def test_unrelated_custom_suffix_does_not_supply_missing_base_fields(self):
        data = synthetic_dump() + b'Custom IME diagnostics:\n  syntheticFlag=false\n'
        self.assertEqual('recorded', self.analyze(data)['status'])
        data = data.replace(b'  mViewsCreated=true\n', b'')
        self.assertEqual('missing', self.unavailable(data, 'MISSING_FIELD')['fields']['mViewsCreated']['status'])

    def test_manager_user_cannot_be_supplied_by_service_subdump(self):
        data = synthetic_dump().replace(b'  mCurrentImeUserId=0\n', b'')
        data = data.replace(b'  mViewsCreated=true\n', b'  mViewsCreated=true\n  mCurrentImeUserId=0\n')
        self.unavailable(data, 'MANAGER_USER')

    def test_truncated_and_unsupported_command_text_is_unknown(self):
        for data in (b'', synthetic_dump()[:-1], synthetic_dump()[:1200], b'\xff\n', b'\x00\n',
                     b'x' * ims.MAX_DUMP_BYTES, b'x' * (ims.MAX_LINE_CHARS + 1) + b'\n',
                     b'\n' * (ims.MAX_LINES + 1)):
            with self.subTest(length=len(data)): self.unavailable(data)

    def test_all_fixed_source_failure_markers_override_apparently_complete_dump(self):
        for marker in ims.ERROR_MARKERS:
            with self.subTest(marker=marker):
                value = self.unavailable(synthetic_dump() + (marker + ' synthetic\n').encode(), 'DUMP_ERROR_MARKER')
                self.assertTrue(all(field['status'] == 'unavailable' for field in value['fields'].values()))

    def test_duplicate_target_field_is_not_resolved_first_or_last(self):
        line = b'  mIsInputViewShown=false mStatusIcon=0\n'
        value = self.unavailable(synthetic_dump().replace(line, line + line), 'DUPLICATE_FIELD')
        self.assertEqual({'status': 'invalid', 'value': None}, value['fields']['mIsInputViewShown'])
        self.assertEqual('present', value['fields']['configuration']['status'])

    def test_missing_field_is_not_false(self):
        data = synthetic_dump().replace(b'  mSettingsObserver=SettingsObserver{mShowImeWithHardKeyboard=1}\n', b'')
        value = self.unavailable(data, 'MISSING_FIELD')
        self.assertEqual({'status': 'missing', 'value': None}, value['fields']['settingsObserver'])

    def test_noncanonical_boolean_is_invalid(self):
        value = self.unavailable(synthetic_dump().replace(b'mIsInputViewShown=false', b'mIsInputViewShown=0'), 'INVALID_FIELD')
        self.assertIsNone(value['fields']['mIsInputViewShown']['value'])

    def test_configuration_known_and_future_integer_tokens_preserved(self):
        cases = {'-keyb/v/h': (1, 1, 2), 'qwerty/h/v': (2, 2, 1), '12key/s/?': (3, 3, 0),
                 '?keyb/?/?': (0, 0, 0), 'keys=9/7/8': (9, 7, 8)}
        for token, expected in cases.items():
            with self.subTest(token=token):
                value = self.analyze(synthetic_dump(keyboard=token))
                self.assertEqual('recorded', value['status'])
                config = value['fields']['configuration']['value']
                self.assertEqual(expected, tuple(config[k] for k in ('keyboard', 'keyboardHidden', 'hardKeyboardHidden')))

    def test_configuration_missing_duplicate_or_unbalanced_token_is_unknown(self):
        for token in ('unsupported', '-keyb/v/h qwerty/v/h', 'keys=2147483648/v/h', '-keyb/v/h {'):
            with self.subTest(token=token): self.unavailable(synthetic_dump(keyboard=token), 'INVALID_FIELD')

    def test_cache_unknown_false_true_and_null_observer_are_distinct(self):
        for cache, expected in ((0, 'UNKNOWN'), (1, 'FALSE'), (2, 'TRUE'), (None, 'OBSERVER_NULL')):
            with self.subTest(cache=cache):
                value = self.analyze(synthetic_dump(settings_cache=cache))
                self.assertEqual('recorded', value['status'])
                observer = value['fields']['settingsObserver']['value']
                self.assertEqual(expected, observer['cacheMeaning'])
                self.assertEqual(cache, observer['cachedShowImeWithHardKeyboard'])
                self.assertFalse(value['scope']['settingsObserverIsLiveSettingRead'])
        self.unavailable(synthetic_dump(settings_cache=3), 'INVALID_FIELD')

    def test_region_rectangles_are_ims_data_only(self):
        value = self.analyze(synthetic_dump(region='SkRegion((-5,0,20,40)(30,50,100,200))'))
        self.assertEqual('recorded', value['status'])
        region = value['fields']['touchableRegion']['value']
        self.assertEqual([[-5, 0, 20, 40], [30, 50, 100, 200]], region['rectangles'])
        self.assertFalse(region['empty'])
        self.assertEqual('InputMethodService_mTmpInsets_touchableRegion_only', region['scope'])

    def test_region_unsupported_malformed_and_bound_are_unknown(self):
        for region in ('Rect(0,0,1,1)', 'SkRegion((0,0,0,1))', 'SkRegion((0,0,1,2147483648))',
                       'SkRegion(' + '(0,0,1,1)' * 65 + ')'):
            with self.subTest(region=region[:50]): self.unavailable(synthetic_dump(region=region), 'INVALID_FIELD')

    def test_insets_line_must_immediately_follow_unique_service_header(self):
        for data in (synthetic_dump().replace(b'  Last computed insets:\n', b''),
                     synthetic_dump().replace(b'  Last computed insets:\n', b'  Last computed insets:\n  unrelated=true\n'),
                     synthetic_dump().replace(b'  Last computed insets:\n', b'  Last computed insets:\n  Last computed insets:\n')):
            with self.subTest(data=data[-400:]): self.unavailable(data)

    def test_editor_package_is_client_observation_and_null_is_recorded(self):
        value = self.analyze()
        self.assertEqual({'isNull': False, 'inputType': 0x20001, 'packageName': 'dev.agentos.compat',
                          'fixturePackageMatches': True}, value['fields']['inputEditorInfo']['value'])
        value = self.analyze(synthetic_dump(editor_package='other.package'))
        self.assertEqual('recorded', value['status'])
        self.assertFalse(value['fields']['inputEditorInfo']['value']['fixturePackageMatches'])
        value = self.analyze(synthetic_dump(editor_null=True))
        self.assertEqual('recorded', value['status'])
        self.assertEqual({'isNull': True, 'inputType': None, 'packageName': None, 'fixturePackageMatches': None},
                         value['fields']['inputEditorInfo']['value'])

    def test_editor_unsigned_input_type_bits_and_null_package(self):
        value = self.analyze(synthetic_dump(editor_package='null').replace(b'inputType=0x20001 ', b'inputType=0xffffffff '))
        self.assertEqual('recorded', value['status'])
        self.assertEqual(2**32 - 1, value['fields']['inputEditorInfo']['value']['inputType'])
        self.assertIsNone(value['fields']['inputEditorInfo']['value']['packageName'])
        self.assertIsNone(value['fields']['inputEditorInfo']['value']['fixturePackageMatches'])

    def test_editor_child_duplicates_or_missing_do_not_use_other_sections(self):
        line = b'    inputType=0x20001 imeOptions=0x12000000 privateImeOptions=null\n'
        for data in (synthetic_dump().replace(line, line + line), synthetic_dump().replace(line, b''),
                     synthetic_dump().replace(b'  mInputEditorInfo:\n', b'  mInputEditorInfo:\n  mInputEditorInfo:\n')):
            with self.subTest(data=data[-1000:]):
                value = self.unavailable(data)
                self.assertIsNone(value['fields']['inputEditorInfo']['value'])

    def test_orphan_editor_fields_cannot_amend_null_or_escape_editor_section(self):
        line = b'    inputType=0x1 imeOptions=0x0 privateImeOptions=null\n'
        data = synthetic_dump(editor_null=True).replace(b'  mInputEditorInfo: null\n', b'  mInputEditorInfo: null\n' + line)
        self.unavailable(data, 'INVALID_FIELD')
        for data in (synthetic_dump(), synthetic_dump(editor_null=True)):
            data = data.replace(b'  mViewsCreated=true\n', b'  mViewsCreated=true\n' + line)
            value = self.unavailable(data, 'INVALID_FIELD')
            self.assertIsNone(value['fields']['inputEditorInfo']['value'])

    def test_sensitive_unneeded_editor_strings_not_in_analysis(self):
        data = synthetic_dump().replace(b'hintText=null label=null', b'hintText=synthetic-secret-1 label=synthetic-secret-2')
        data = data.replace(b'privateImeOptions=null', b'privateImeOptions=synthetic-secret-3')
        value = self.analyze(data)
        self.assertEqual('recorded', value['status'])
        self.assertNotIn('synthetic-secret', json.dumps(value))
        self.assertNotIn('BinderProxy', json.dumps(value))

    def test_analysis_is_recomputed_and_boolean_integer_tampering_rejected(self):
        data = synthetic_dump(); value = self.analyze(data)
        self.assertEqual(value, ims.validate_analysis(value, data, COMPONENT, PACKAGE, UID))
        bad = copy.deepcopy(value); bad['fields']['mInputViewStarted']['value'] = 0
        with self.assertRaisesRegex(ValueError, 'IME_STATE_ANALYSIS_MISMATCH'):
            ims.validate_analysis(bad, data, COMPONENT, PACKAGE, UID)
        with self.assertRaises(ValueError):
            ims.validate_analysis(value, data.replace(b'mInputViewStarted=false', b'mInputViewStarted=true'), COMPONENT, PACKAGE, UID)

    def test_arbitrary_bounded_bytes_have_total_parser_result(self):
        rng = random.Random(448)
        for _ in range(100):
            data = bytes(rng.randrange(256) for _ in range(rng.randrange(2000)))
            value = self.analyze(data)
            self.assertIn(value['status'], ('recorded', 'unavailable'))
            self.assertIn('reason', value)
            self.assertNotEqual('PARSER_EXCEPTION', value['reason'])
        for data in (None, '', bytearray(), 12, True):
            value = ims.analyze(data, COMPONENT, PACKAGE, UID)
            self.assertEqual('unavailable', value['status'])
            self.assertEqual('INPUT_TYPE', value['reason'])


if __name__ == '__main__':
    unittest.main()
