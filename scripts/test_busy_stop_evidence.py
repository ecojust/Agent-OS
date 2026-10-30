"""Trust-boundary tests for bounded busy-stop reports and committed prefixes."""

import copy
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import time
import unittest
from unittest.mock import patch

import collect_busy_stop_evidence as busy

SOURCE = dict(repository='ecojust/Agent-OS', runId=123, attempt=2, commit='a' * 40)


def encoded(value):
    return (json.dumps(value, separators=(',', ':'), allow_nan=False) + '\n').encode()


def collected_capture(report, label='37.0', source=SOURCE):
    raw = encoded(report)
    value = busy.empty_capture(label, source, report.get('profile', 'pilot'))
    value.update(actualSdkInt=int(label.split('.')[0]), captureStatus='collected', captureError=None,
                 gradleExitCode=0 if report['status'] == 'passed' else 1,
                 diagnosticStatus=busy.diagnostic_status(report, 0 if report['status'] == 'passed' else 1),
                 report=dict(path=busy.REPORT_NAME, bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest(),
                             status=report['status']))
    return value, raw


def valid_trial(index=0, api=37, profile='pilot', schema_version=1):
    """Synthetic raw measurements for trust-boundary tests, never run evidence."""
    base = 10_000_000_000 + index * 10_000_000_000
    at = lambda ms: base + ms * 1_000_000
    boot = lambda ms: at(ms) + 100_000_000_000
    identity = busy.trial_identity(index, profile)
    device = identity['scenario'] == 'DEVICE'; attempts = int(device); epoch=4
    task = f'synthetic-owner-{index}'
    trial = dict(schemaVersion=schema_version, kind=busy.TRIAL_KIND, **identity, status='passed', phase='complete',
                 failure=None, startedNanoTime=at(0), endedNanoTime=at(1500), taskId=task, epochBefore=epoch,
                 input=dict(bounds=[0,0,100,100], point=[50,50], downIssuedNanoTime=at(400),
                            downReturnedNanoTime=at(410), upIssuedNanoTime=at(450), upIssuedElapsedNanos=boot(450),
                            upReturnedNanoTime=at(451), downAccepted=True, upAccepted=True,
                            touchUpNanoTime=at(451), touchUpElapsedNanos=boot(451), touchUpCount=1),
                 probe=dict(events=[],overflow=0,ownerMatched=True), fixture=None, model=None, frames=None,
                 trace=dict(events=[],overwritten=0),
                 checks=dict(initialCounter=0,finalCounter=attempts,approvalCount=attempts,
                             frozenUsageUnchanged=True,frozenResultUnchanged=True,workerDrained=True,
                             quietMillis=500,noFurtherRequests=True,workerDrainedNanoTime=at(650),
                             networkDrained=True,networkDrainedNanoTime=at(660),closerDrained=True,
                             closerDrainedNanoTime=at(670),requestCountBeforeStop=1),
                 cleanup={k:True for k in busy.TRIAL_CLEANUP},
                 feedback=dict(controlGoneObservedNanoTime=at(456),quietStartedNanoTime=at(700),quietEndedNanoTime=at(1200),
                               overlayBefore=dict(originalAttached=True,currentMatchesOriginal=True,controlAvailable=True),
                               overlayAfter=dict(originalAttached=False,currentOverlayPresent=False,controlAvailable=False)),
                 releaseRequestedNanoTime=at(600),gateProbe=None,
                 oldEpochProbe=dict(sampleNanoTime=at(690),code='TASK_STOPPED',attempts=attempts))
    for position, (key, ms) in enumerate(zip(busy.SNAPSHOTS, (300,455,500,680,1210))):
        stopped=position!=0
        trial[key]=dict(sampleNanoTime=at(ms),cancelled=stopped,epoch=epoch+stopped,
                        dispatchAttempts=attempts,inFlight=device and position<3,controllerRunning=not stopped,
                        resultStopped=stopped,usageTaskMatches=True,controlAvailable=position<2,
                        ownerTaskId=task,usageTaskId=task,resultKind='STOPPED' if stopped else None,
                        resultCode='TASK_STOPPED' if stopped else '')
    points=[(5,452,epoch,0),(6,453,epoch+1,1),(7,454,epoch+1,1)]
    if device: points=[(1,200,epoch,0),*points,(2,630,epoch,1),(4,640,epoch,1)]
    trial['probe']['events']=[[kind,at(ms),owner_epoch,attempts,detail] for kind,ms,owner_epoch,detail in points]
    trial['trace']['events']=[['STOP_RECEIVED',boot(452),100_000,True,epoch,attempts],
                              ['STOP_EPOCH_INVALIDATED',boot(453)+200_000,100_000,True,epoch+1,attempts],
                              ['STOP_UI_APPLIED',boot(454)+200_000,100_000,True,epoch+1,attempts]]
    if device:
        trial['trace']['events'].insert(0,['DEVICE_DISPATCH_ATTEMPT',boot(199),100_000,False,epoch,1])
        first=dict(token=f'synthetic-token-{index}',pid=4321,claimed=True,released=False,returned=False,
                   expired=False,barrierEnteredNanoTime=at(210),barrierExitedNanoTime=-1,
                   effectWrittenNanoTime=-1,effectCount=-1,startedElapsedNanos=boot(210),finishedElapsedNanos=0)
        final=dict(first,released=True,returned=True,barrierExitedNanoTime=at(610),
                   effectWrittenNanoTime=at(620),effectCount=1,finishedElapsedNanos=boot(610))
        trial['fixture']=dict(beforeStop=first,beforeRelease=copy.deepcopy(first),afterSettle=final)
        trial['gateProbe']=dict(sampleNanoTime=at(490),replacementTaskId=f'synthetic-replacement-{index}',
                               replacementEpoch=1,code='CAPABILITY_UNAVAILABLE',submitted=False,
                               dispatchSubmitted=False,dispatchAccepted=False,attempts=0)
        trial['model']=dict(requests=1,enteredNanoTime=-1,releaseNanoTime=-1,writeAttemptNanoTime=-1,
                            writeEndNanoTime=-1,peerClosed=False,writeSucceeded=False,heldExpired=False)
    else:
        trial['model']=dict(requests=1,enteredNanoTime=at(200),releaseNanoTime=at(605),writeAttemptNanoTime=at(610),
                            writeEndNanoTime=at(620),peerClosed=True,writeSucceeded=False,heldExpired=False)
        def draw(sequence, ms, vsync):
            return dict(sequence=sequence,drawNanoTime=at(ms),drawingTimeMillis=at(vsync)//1_000_000,
                        readNanoTime=at(ms+1),statusBounds=[10,10,210,110],statusWidth=200,statusHeight=100,**{key:True for key in busy.DRAW_FLAGS})
        def metric(intended,vsync,total,callback):
            return dict(intendedVsyncNanoTime=at(intended),vsyncNanoTime=at(vsync),totalDurationNanos=total*1_000_000,
                        firstDraw=0,gpuDurationNanos=-1,deadlineNanos=-1,callbackNanoTime=at(callback),droppedReports=0)
        quality=dict(receivedReports=2,observedDrawCount=2,discardedNonTargetCandidates=0,
                     **{key:0 for key in busy.FRAME_ERRORS},targetMatchCount=1,
                     **{key:key!='drainTimedOut' for key in busy.FRAME_FLAGS},
                     drainStartedNanoTime=at(475),drainEndedNanoTime=at(480),callbackFailureCode='')
        trial['frames']=dict(clockDomain='CLOCK_MONOTONIC_System.nanoTime',status='passed',pid=1234,
                             activityIdentity=101,windowIdentity=102,decorIdentity=103,armedNanoTime=at(440),
                             baseline=dict(draw=draw(1,95,90),frame=metric(80,90,20,110),matchCount=1,
                                           droppedReports=0,complete=True,timedOut=False),
                             firstStoppedDraw=draw(2,457,450),targetFrame=metric(440,450,20,470),
                             completedNanoTime=at(460),quality=quality)
    if schema_version == 2 and trial['frames'] is not None:
        frame=trial['frames']
        for draw,metric in ((frame['baseline']['draw'],frame['baseline']['frame']),
                            (frame['firstStoppedDraw'],frame['targetFrame'])):
            metric.update(unknownDelayDurationNanos=draw['drawNanoTime']-metric['intendedVsyncNanoTime'],
                          inputHandlingDurationNanos=250_000,animationDurationNanos=250_000,layoutMeasureDurationNanos=0)
            metric['drawStartNanoTime']=busy.checked_draw_start(metric)
        frame['candidateReports']=[[frame['targetFrame'][key] for key in busy.FRAME_REPORT_COLUMNS]]
        frame['quality'].update(candidateReportsObserved=1,candidateReportsNotRetained=0)
    return trial


def write_bundle(directory, api=37, profile='pilot', trials=None, status='passed', source=SOURCE, schema_version=1):
    """Write a synthetic complete bundle; return (capture, root) for integration tests."""
    directory=Path(directory); directory.mkdir(parents=True, exist_ok=True)
    if trials is None: trials=[valid_trial(i,api,profile,schema_version) for i in range(busy.trial_count(profile))]
    report=dict(schemaVersion=schema_version,kind=busy.KIND,status=status,profile=profile,diagnosticOnly=True,
                budgetAcceptance=False,fullSuiteAcceptance=False,api=api,appProcessPid=1234,
                device=dict(manufacturer='synthetic',model='validator-fixture',fingerprint='synthetic/test-only'),
                conditions=busy.conditions(profile,schema_version),startedNanoTime=1,
                endedNanoTime=-1 if status=='incomplete' else (trials[-1]['endedNanoTime']+1 if trials else 2),
                cleanup={key:status=='passed' for key in busy.CLEANUP_FIELDS},trials=[])
    for trial in trials:
        raw=encoded(trial); path=busy.trial_name(trial['index'])
        (directory/path).write_bytes(raw)
        report['trials'].append(dict(**{key:trial[key] for key in ('index','round','position','order','scenario','status')},
                                     path=path,bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest()))
    label='37.0' if api==37 else str(api)
    capture,raw=collected_capture(report,label,source)
    (directory/busy.REPORT_NAME).write_bytes(raw); (directory/'capture.json').write_bytes(encoded(capture))
    return capture,report


def maximum_failed_trial(index=0, schema_version=1):
    """Full-width values and worst UTF-8→JSON escaping, within each scalar bound."""
    trial=valid_trial(index,schema_version=schema_version)
    delta=busy.LONG_MAX-trial['endedNanoTime']-1
    def shift(value,key=''):
        if type(value) is dict:return {k:shift(v,k) for k,v in value.items()}
        if type(value) is list:return [shift(v) for v in value]
        if key.endswith('NanoTime') and type(value) is int and value>=0:return value+delta
        return value
    trial=shift(trial)
    trial.update(status='failed',failure='😀'*125,phase='😀'*25,taskId='😀'*16,epochBefore=busy.LONG_MAX)
    for key in busy.SNAPSHOTS:
        trial[key].update(ownerTaskId='😀'*16,usageTaskId='😀'*16,resultKind='😀'*8,resultCode='😀'*32,
                          resultStopped=False,epoch=busy.LONG_MAX,dispatchAttempts=busy.LONG_MAX)
    if trial['gateProbe']:
        trial['gateProbe'].update(replacementTaskId='😀'*16,code='😀'*16,replacementEpoch=busy.LONG_MAX,attempts=busy.LONG_MAX)
    trial['oldEpochProbe'].update(code='😀'*16,attempts=busy.LONG_MAX)
    for item in (trial['fixture'] or {}).values():
        item.update(token='😀'*16,pid=2**31-1,effectCount=busy.LONG_MAX,
                    startedElapsedNanos=busy.LONG_MAX,finishedElapsedNanos=busy.LONG_MAX)
    trial['trace']['events']=[[max(busy.STAGES,key=len),busy.LONG_MAX//2,busy.LONG_MAX//2,False,busy.LONG_MAX,busy.LONG_MAX] for _ in range(128)]
    trial['trace']['overwritten']=busy.LONG_MAX
    trial['probe']['events']=[[7,trial['endedNanoTime'],busy.LONG_MAX,busy.LONG_MAX,1] for _ in range(16)]
    trial['probe']['overflow']=busy.LONG_MAX
    for key in ('initialCounter','finalCounter','approvalCount','requestCountBeforeStop'):trial['checks'][key]=busy.LONG_MAX
    trial['model']['requests']=busy.LONG_MAX
    for key in ('upIssuedElapsedNanos','touchUpElapsedNanos','touchUpCount'):trial['input'][key]=busy.LONG_MAX
    trial['input'].update(bounds=[-(2**31)]*4,point=[-(2**31)]*2)
    if trial['frames']:
        frame=trial['frames']
        frame.update(pid=2**31-1,activityIdentity=2**31-1,windowIdentity=2**31-1,decorIdentity=2**31-1)
        for draw in (frame['baseline']['draw'],frame['firstStoppedDraw']):
            draw.update(sequence=busy.LONG_MAX,drawingTimeMillis=busy.LONG_MAX,statusBounds=[-(2**31)]*4,statusWidth=2**31-1,statusHeight=2**31-1)
        for metric in (frame['baseline']['frame'],frame['targetFrame']):
            for key in ('intendedVsyncNanoTime','vsyncNanoTime','totalDurationNanos','gpuDurationNanos','deadlineNanos','droppedReports'):metric[key]=busy.LONG_MAX
        frame['baseline'].update(matchCount=busy.LONG_MAX,droppedReports=busy.LONG_MAX)
        for key,item in frame['quality'].items():
            if type(item) is int and not key.endswith('NanoTime'):frame['quality'][key]=busy.LONG_MAX
        frame['quality']['callbackFailureCode']='draw_read_post'
    def widest(value,key=''):
        if type(value) is dict:return {k:widest(v,k) for k,v in value.items()}
        if type(value) is list:return [widest(v) for v in value]
        if type(value) is bool:return False
        if key.endswith('NanoTime'):return busy.LONG_MAX-1
        return value
    trial=widest(trial)
    trial['startedNanoTime']=busy.LONG_MAX-1000
    for event in trial['probe']['events']:event[1]=busy.LONG_MAX-1
    if trial['frames']:
        trial['frames']['status']='not_started'
        for metric in (trial['frames']['baseline']['frame'],trial['frames']['targetFrame']):metric['firstDraw']=-1
    if schema_version == 2 and trial['frames']:
        frame=trial['frames']
        for metric in (frame['baseline']['frame'],frame['targetFrame']):
            for key in busy.FRAME_V2_DURATIONS:metric[key]=-2**63
        frame['candidateReports']=[[frame['targetFrame'][key] for key in busy.FRAME_REPORT_COLUMNS] for _ in range(8)]
        frame['quality'].update(candidateReportsObserved=busy.LONG_MAX,candidateReportsNotRetained=busy.LONG_MAX-8)
    return trial


class BusyStopRawContractTests(unittest.TestCase):
    def validate(self, value, api=37, profile='pilot'):
        return busy.validate_trial(value,value['index'],dict(profile=profile,api=api,appProcessPid=1234,schemaVersion=value['schemaVersion']))

    def test_all_apis_pilot_and_formal_round_trip_original_bytes(self):
        for api in range(30,38):
            for profile in ('pilot','formal'):
                with self.subTest(api=api,profile=profile), tempfile.TemporaryDirectory() as folder:
                    capture,root=write_bundle(folder,api,profile)
                    label='37.0' if api==37 else str(api)
                    payloads=busy.validate_payloads(folder,capture,label,SOURCE,profile)
                    self.assertEqual(busy.trial_count(profile)+1,len(payloads))
                    for name,raw in payloads.items(): self.assertEqual((Path(folder)/name).read_bytes(),raw)

    def test_failed_and_interrupted_prefix_remain_collected_without_promoting_failure(self):
        for status in ('failed','incomplete'):
            trials=[valid_trial(0),valid_trial(1)]
            if status=='failed':
                trials[-1].update(status='failed',phase='stop_frame',failure='AssertionError')
                trials[-1]['frames']['status']='incomplete'
                trials[-1]['frames']['quality']['candidateOverflow']=1
            with tempfile.TemporaryDirectory() as folder:
                capture,root=write_bundle(folder,trials=trials,status=status)
                self.assertEqual(status,capture['diagnosticStatus'])
                self.assertEqual(3,len(busy.validate_payloads(folder,capture,'37.0',SOURCE,'pilot')))
        # Preserve a failure before setup; absent measurements stay explicit unknowns.
        trial=valid_trial();trial.update(status='failed',phase='setup',failure='SetupError',taskId=None,epochBefore=-1)
        for key in busy.SNAPSHOTS:trial[key]=None
        trial.update(fixture=None,model=None,gateProbe=None,oldEpochProbe=None)
        trial['probe']=dict(events=[],overflow=0,ownerMatched=False)
        trial['trace']=dict(events=[],overwritten=0)
        self.validate(trial)

    def test_trial_raw_identity_types_bounds_and_unknown_fields_are_strict(self):
        changes=[lambda t:t.update(schemaVersion=True),lambda t:t.update(index=True),
                 lambda t:t.update(round=1),lambda t:t.update(scenario='MODEL'),
                 lambda t:t.update(extra='unrecognized'),lambda t:t.update(phase='x'*101),
                 lambda t:t.update(failure='汉'*167),lambda t:t['input'].update(upAccepted=1),
                 lambda t:t['input'].update(point=[True,50]),lambda t:t['input'].update(downIssuedNanoTime=-2),
                 lambda t:t['probe']['events'].append([1,0,4,1,0]),
                 lambda t:t['probe'].update(events=t['probe']['events']*3),
                 lambda t:t['trace'].update(events=t['trace']['events']*33),
                 lambda t:t['trace']['events'][0].__setitem__(3,1),
                 lambda t:t['trace']['events'][0].__setitem__(0,'NEW_STAGE')]
        for change in changes:
            trial=valid_trial();change(trial)
            with self.subTest(change=change),self.assertRaises(ValueError):self.validate(trial)

    def test_pass_cannot_hide_bad_input_epoch_dispatch_ticket_gate_or_late_result(self):
        changes=[lambda t:t['input'].update(touchUpCount=0),lambda t:t['input'].update(upAccepted=False),
                 lambda t:t['input'].update(point=[99,99]),lambda t:t['probe'].update(overflow=1),
                 lambda t:t['probe'].update(ownerMatched=False),lambda t:t['probe']['events'][4].__setitem__(2,5),
                 lambda t:t['probe']['events'][5].__setitem__(4,0),
                 lambda t:t['beforeRelease'].update(inFlight=False),
                 lambda t:t['afterStop'].update(usageTaskId='replacement'),
                 lambda t:t['finalQuiet'].update(resultKind='SUCCESS',resultStopped=False),
                 lambda t:t['finalQuiet'].update(controlAvailable=True),
                 lambda t:t['gateProbe'].update(code='OK',dispatchSubmitted=True,attempts=1),
                 lambda t:t['gateProbe'].update(replacementTaskId=t['taskId']),
                 lambda t:t['oldEpochProbe'].update(code='OK'),lambda t:t['checks'].update(networkDrained=False),
                 lambda t:t['checks'].update(requestCountBeforeStop=2),lambda t:t['checks'].update(finalCounter=2),
                 lambda t:t['trace']['events'].append(copy.deepcopy(t['trace']['events'][0])),
                 lambda t:t['fixture']['beforeRelease'].update(released=True),
                 lambda t:t['fixture']['afterSettle'].update(expired=True),
                 lambda t:t['fixture']['afterSettle'].update(effectWrittenNanoTime=t['releaseRequestedNanoTime']-1),
                 lambda t:t['feedback'].update(quietEndedNanoTime=t['feedback']['quietStartedNanoTime']+499_999_999),
                 lambda t:t['cleanup'].update(controllerCleared=False),
                 lambda t:t['feedback']['overlayBefore'].update(currentMatchesOriginal=False),
                 lambda t:t['feedback']['overlayAfter'].update(originalAttached=True),
                 lambda t:t['feedback']['overlayAfter'].update(currentOverlayPresent=True)]
        for change in changes:
            trial=valid_trial();change(trial)
            with self.subTest(change=change),self.assertRaises(ValueError):self.validate(trial)

    def test_frame_is_first_natural_unique_complete_hwui_and_never_overlay_removal(self):
        changes=[lambda t:t.update(frames=None),lambda t:t['frames'].update(pid=4321),
                 lambda t:t['frames']['baseline'].update(complete=False),
                 lambda t:t['frames']['firstStoppedDraw'].update(uiVerified=False),
                 lambda t:t['frames']['firstStoppedDraw'].update(windowIdentityMatched=False),
                 lambda t:t['frames']['firstStoppedDraw'].update(taskIdentityMatched=False),
                 lambda t:t['frames']['firstStoppedDraw'].update(statusBounds=[10,10,110,110]),
                 lambda t:t['frames']['firstStoppedDraw'].update(drawingTimeMillis=1),
                 lambda t:t['frames']['firstStoppedDraw'].update(drawNanoTime=t['input']['upIssuedNanoTime']-1),
                 lambda t:t['frames']['firstStoppedDraw'].update(drawNanoTime=t['input']['touchUpNanoTime']),
                 lambda t:t['frames']['targetFrame'].update(firstDraw=1),
                 lambda t:t['frames']['targetFrame'].update(totalDurationNanos=0),
                 lambda t:t['frames'].update(completedNanoTime=t['frames']['targetFrame']['callbackNanoTime']),
                 lambda t:t['frames']['quality'].update(targetMatchCount=2),
                 lambda t:t['frames']['quality'].update(drainBarrierObserved=False),
                 lambda t:t['frames']['quality'].update(frameThreadStopped=False),
                 lambda t:t['frames']['quality'].update(secureFlagsPreserved=False),
                 lambda t:t['model'].update(writeSucceeded=True,peerClosed=True),
                 lambda t:t['model'].update(requests=2),
                 lambda t:t['model'].update(releaseNanoTime=t['input']['upIssuedNanoTime'])]
        changes.extend(lambda t,k=k:t['frames']['quality'].update({k:1}) for k in busy.FRAME_ERRORS)
        for change in changes:
            trial=valid_trial(1);change(trial)
            with self.subTest(change=change),self.assertRaises(ValueError):self.validate(trial)
        trial=valid_trial(1)
        # Current vsync may begin before UP; injection return may precede posted PerformClick.
        self.assertLess(trial['frames']['targetFrame']['intendedVsyncNanoTime'],trial['input']['upIssuedNanoTime'])
        self.assertLess(trial['input']['upReturnedNanoTime'],trial['probe']['events'][0][1])
        trial['frames']['baseline']['frame']['firstDraw']=1
        self.validate(trial)
        trial['frames']['firstStoppedDraw']['readNanoTime']=trial['frames']['targetFrame']['callbackNanoTime']+1
        self.validate(trial)
        trial['model'].update(writeSucceeded=True,peerClosed=False)
        self.validate(trial)
        # Accessibility false return remains raw; the independently observed single effect is authoritative.
        device=valid_trial();device['probe']['events'][4][4]=0;self.validate(device)

    def test_failed_measurements_preserve_diagnostic_violations_without_becoming_samples(self):
        trial=valid_trial();trial.update(status='failed',phase='settle',failure='AssertionError')
        trial['finalQuiet'].update(dispatchAttempts=2,ownerTaskId='another-owner',usageTaskId='another-usage',usageTaskMatches=True)
        trial['fixture']['afterSettle'].update(expired=True,effectCount=2)
        trial['probe']['overflow']=12;trial['checks']['workerDrained']=False
        self.validate(trial)
        frame=valid_trial(1);frame.update(status='failed',failure='AssertionError')
        frame['frames']['quality']['candidateOverflow']=5
        frame['frames']['firstStoppedDraw']['taskIdentityMatched']=False
        self.validate(frame)
        trial['probe']['events'][0][1]=trial['endedNanoTime']+1
        with self.assertRaisesRegex(ValueError,'outside_trial'):self.validate(trial)

    def test_missing_orphan_duplicate_ref_rehash_and_cross_trial_time_are_rejected(self):
        for attack in ('missing','orphan','hash','reference','rehash','root_pass_prefix','time'):
            with self.subTest(attack=attack),tempfile.TemporaryDirectory() as folder:
                rootdir=Path(folder);capture,root=write_bundle(rootdir)
                if attack=='missing':(rootdir/'trial-000.json').unlink()
                elif attack=='orphan':(rootdir/'trial-004.json').write_text('{}')
                elif attack=='hash':(rootdir/'trial-000.json').write_text(' '*(root['trials'][0]['bytes']))
                elif attack=='reference':root['trials'][1]['index']=0
                elif attack=='root_pass_prefix':root['trials'].pop()
                else:
                    trial=json.loads((rootdir/'trial-000.json').read_bytes())
                    if attack=='rehash':trial['beforeRelease']['inFlight']=False
                    else:trial['endedNanoTime']=valid_trial(1)['endedNanoTime']
                    raw=encoded(trial);(rootdir/'trial-000.json').write_bytes(raw)
                    root['trials'][0].update(bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())
                capture,raw=collected_capture(root);(rootdir/busy.REPORT_NAME).write_bytes(raw)
                (rootdir/'capture.json').write_bytes(encoded(capture))
                with self.assertRaises(ValueError):busy.validate_payloads(rootdir,capture,'37.0',SOURCE,'pilot')

    def test_max_serialized_envelope_fits_frozen_limits(self):
        for index in (0,1):
            trial=maximum_failed_trial(index)
            self.validate(trial)
            raw=encoded(trial)
            self.assertLess(len(raw),busy.MAX_TRIAL_BYTES)
        with tempfile.TemporaryDirectory() as folder:
            capture,root=write_bundle(folder,profile='formal')
            root['device']={key:'😀'*(bound//4) for key,bound in (('manufacturer',256),('model',256),('fingerprint',1024))}
            root['startedNanoTime']=busy.LONG_MAX-1;root['endedNanoTime']=busy.LONG_MAX
            root.update(status='failed',appProcessPid=2**31-1,cleanup={key:False for key in busy.CLEANUP_FIELDS})
            for ref in root['trials']:ref['bytes']=busy.MAX_TRIAL_BYTES
            raw_root=encoded(root)
            busy.validate_report(root,37,'formal')
            self.assertLess(len(raw_root),busy.MAX_REPORT_BYTES)
            capture,raw_root=collected_capture(root)
            capture.update(gradleExitCode=255,emulatorStepOutcome='cancelled',capturedAt='2026-10-30T12:00:00.12345678901234+00:00')
            capture['sourceRun']={**SOURCE,'runId':busy.LONG_MAX,'attempt':busy.LONG_MAX}
            capture['report']['status']='incomplete';capture['diagnosticStatus']='incomplete'
            busy.validate_capture(capture,'37.0',capture['sourceRun'],'formal')
            self.assertLess(len(encoded(capture)),busy.MAX_CAPTURE_BYTES)
            self.assertLess(8*(200*busy.MAX_TRIAL_BYTES+busy.MAX_REPORT_BYTES+busy.MAX_CAPTURE_BYTES),busy.MAX_ARCHIVE_BYTES)

    def test_walkthrough_reuses_strict_single_device_without_accepting_extra_profile(self):
        busy.validate_single_device_trial(valid_trial(api=35),35)
        with self.assertRaises(ValueError):busy.validate_single_device_trial(valid_trial(1,35),35)


class BusyStopV2ContractTests(unittest.TestCase):
    def validate(self, trial):
        return busy.validate_trial(trial,trial['index'],dict(profile='pilot',api=37,appProcessPid=1234,
                                                            schemaVersion=trial['schemaVersion']))

    def row(self, metric):
        return [metric[key] for key in busy.FRAME_REPORT_COLUMNS]

    def candidates(self, trial, metrics, omitted=0):
        frame=trial['frames'];frame['candidateReports']=[self.row(metric) for metric in metrics]
        frame['quality'].update(candidateReportsObserved=len(metrics)+omitted,candidateReportsNotRetained=omitted)

    def unrelated(self, trial):
        metric=copy.deepcopy(trial['frames']['targetFrame'])
        metric['unknownDelayDurationNanos']-=2_000_000
        metric['drawStartNanoTime']=busy.checked_draw_start(metric)
        return metric

    def test_v1_and_v2_complete_roundtrips_and_single_device_remain_separate(self):
        for version in (1,2):
            for profile in ('pilot','formal'):
                for api in (30,37):
                    with self.subTest(version=version,profile=profile,api=api),tempfile.TemporaryDirectory() as folder:
                        capture,root=write_bundle(folder,api,profile,schema_version=version)
                        label='37.0' if api==37 else str(api)
                        self.assertEqual(busy.trial_count(profile)+1,len(busy.validate_payloads(folder,capture,label,SOURCE,profile)))
            busy.validate_single_device_trial(valid_trial(api=35,schema_version=version),35)

    def test_root_trial_condition_versions_cannot_be_mixed_or_inferred(self):
        for root_version,trial_version in ((1,2),(2,1)):
            trials=[valid_trial(i,schema_version=trial_version) for i in range(4)]
            with tempfile.TemporaryDirectory() as folder:
                capture,root=write_bundle(folder,trials=trials,schema_version=root_version)
                with self.assertRaises(ValueError):busy.validate_payloads(folder,capture,'37.0',SOURCE,'pilot')
        with tempfile.TemporaryDirectory() as folder:
            _,root=write_bundle(folder,schema_version=2)
            for version in (True,0,3):
                with self.assertRaises(ValueError):busy.validate_report(dict(root,schemaVersion=version),37,'pilot')
            root['conditions']=busy.conditions('pilot',1)
            with self.assertRaises(ValueError):busy.validate_report(root,37,'pilot')
            root.update(schemaVersion=1,conditions=busy.conditions('pilot',2))
            with self.assertRaises(ValueError):busy.validate_report(root,37,'pilot')
        for version,other in ((1,2),(2,1)):
            trial=valid_trial(1,schema_version=version)
            trial['frames']=valid_trial(1,schema_version=other)['frames']
            with self.assertRaises(ValueError):self.validate(trial)

    def test_checked_signed_arithmetic_retains_cancellation_and_rejects_intermediate_overflow(self):
        def metric(start,parts):
            return dict(intendedVsyncNanoTime=start,**dict(zip(busy.FRAME_V2_DURATIONS,parts)))
        self.assertEqual(105,busy.checked_draw_start(metric(100,[-20,30,-10,5])))
        self.assertEqual(0,busy.checked_draw_start(metric(100,[-100,0,0,0])))
        for value in (metric(0,[1,1,1,1]),metric(100,[-101,0,0,0]),
                      metric(busy.LONG_MAX,[1,-1,0,0]),metric(1,[-2**63,-2,2,0])):
            self.assertEqual(-1,busy.checked_draw_start(value))

    def test_same_vsync_late_callback_does_not_authorize_wrong_draw_or_bad_selected_stages(self):
        changes=[lambda t:t['frames']['targetFrame'].update(drawStartNanoTime=t['frames']['targetFrame']['drawStartNanoTime']+1),
                 lambda t:t['frames']['targetFrame'].update(unknownDelayDurationNanos=busy.LONG_MAX,drawStartNanoTime=-1),
                 lambda t:t['frames']['targetFrame'].update(unknownDelayDurationNanos=-1,drawStartNanoTime=-1)]
        for key in busy.FRAME_V2_DURATIONS:
            def negative(t,key=key):
                metric=t['frames']['targetFrame'];metric[key]=-1
                metric['drawStartNanoTime']=busy.checked_draw_start(metric)
            changes.append(negative)
        def before(t):
            metric=t['frames']['targetFrame'];metric['unknownDelayDurationNanos']-=2_000_000
            metric['drawStartNanoTime']=busy.checked_draw_start(metric)
        changes.append(before)
        def after(t):
            metric=t['frames']['targetFrame'];metric['unknownDelayDurationNanos']+=2_000_000
            metric['drawStartNanoTime']=busy.checked_draw_start(metric)
        changes.append(after)
        def beyond_end(t):
            frame=t['frames'];metric=frame['targetFrame']
            frame['firstStoppedDraw']['readNanoTime']=frame['completedNanoTime']+2_000_000
            metric['unknownDelayDurationNanos']+=4_000_000;metric['drawStartNanoTime']=busy.checked_draw_start(metric)
        changes.append(beyond_end)
        for change in changes:
            trial=valid_trial(1,schema_version=2);change(trial)
            self.candidates(trial,[trial['frames']['targetFrame']])
            with self.subTest(change=change),self.assertRaises(ValueError):self.validate(trial)
        # Both endpoints of the measured OnDraw→post-read bracket are inclusive.
        for offset in (0,1_000_000):
            trial=valid_trial(1,schema_version=2);frame=trial['frames'];metric=frame['targetFrame']
            metric['inputHandlingDurationNanos']=offset;metric['animationDurationNanos']=0
            metric['drawStartNanoTime']=busy.checked_draw_start(metric);self.candidates(trial,[metric]);self.validate(trial)

    def test_candidate_prefix_keeps_signed_non_target_reports_without_replacing_original_draw(self):
        trial=valid_trial(1,schema_version=2);target=trial['frames']['targetFrame']
        old=self.unrelated(trial)
        # Negative raw stages are retained; their checked signed sum places this
        # report outside the frozen bracket. This does not prove what it displayed.
        old['unknownDelayDurationNanos']=-1;old['inputHandlingDurationNanos']=0
        old['animationDurationNanos']=0;old['layoutMeasureDurationNanos']=0
        old['drawStartNanoTime']=busy.checked_draw_start(old)
        other=copy.deepcopy(old);other['callbackNanoTime']+=1
        self.candidates(trial,[other,old,target])
        self.validate(trial)  # Prefix order is processing order, not sorted callback time.
        for omitted in (1,1000):
            trial=valid_trial(1,schema_version=2)
            self.candidates(trial,[self.unrelated(trial) for _ in range(8)],omitted)
            self.validate(trial)  # Bounded raw-prefix omission is not a quality failure.

    def test_rt_only_synthetic_or_pre_vsync_draw_start_cannot_be_selected(self):
        for selected in ('baseline', 'target'):
            for vsync_offset in (0, 250_000):
                trial=valid_trial(1,schema_version=2);frame=trial['frames']
                draw,metric=((frame['baseline']['draw'],frame['baseline']['frame'])
                             if selected=='baseline' else (frame['firstStoppedDraw'],frame['targetFrame']))
                end=metric['intendedVsyncNanoTime']+metric['totalDurationNanos']
                metric.update(intendedVsyncNanoTime=draw['drawNanoTime'],
                              vsyncNanoTime=draw['drawNanoTime']+vsync_offset,
                              totalDurationNanos=end-draw['drawNanoTime'])
                metric.update({key:0 for key in busy.FRAME_V2_DURATIONS})
                metric['drawStartNanoTime']=busy.checked_draw_start(metric)
                draw['drawingTimeMillis']=metric['vsyncNanoTime']//1_000_000
                self.candidates(trial,[frame['targetFrame']])
                # Zero-stage RT-only timestamps can satisfy both the millisecond
                # VSYNC identity and the frozen draw/read bracket; still reject.
                with self.subTest(selected=selected,vsync_offset=vsync_offset),self.assertRaises(ValueError):
                    self.validate(trial)

    def test_ambiguous_unknown_or_contradictory_candidate_cannot_be_hidden_by_omission(self):
        for attack in ('duplicate','different_match','unknown','negative_inside','before_draw','missing_target','truncated_match'):
            trial=valid_trial(1,schema_version=2);target=trial['frames']['targetFrame'];other=copy.deepcopy(target)
            metrics=[other,target];omitted=0
            if attack=='different_match':other['gpuDurationNanos']=1
            elif attack=='unknown':other.update(unknownDelayDurationNanos=busy.LONG_MAX,drawStartNanoTime=-1)
            elif attack=='negative_inside':
                other['unknownDelayDurationNanos']=-1
                other['inputHandlingDurationNanos']=target['drawStartNanoTime']-other['intendedVsyncNanoTime']+1
                other['animationDurationNanos']=0;other['layoutMeasureDurationNanos']=0
                other['drawStartNanoTime']=busy.checked_draw_start(other)
            elif attack=='before_draw':other['callbackNanoTime']=trial['frames']['firstStoppedDraw']['drawNanoTime']-1
            elif attack=='missing_target':metrics=[self.unrelated(trial)]
            elif attack=='truncated_match':
                other['gpuDurationNanos']=1;metrics=[other,*[self.unrelated(trial) for _ in range(7)]];omitted=100
            self.candidates(trial,metrics,omitted)
            with self.subTest(attack=attack),self.assertRaises(ValueError):self.validate(trial)

    def test_prefix_shape_conservation_and_type_bounds_are_strict_but_failed_values_survive(self):
        changes=[lambda t:t['frames'].update(candidateReports=[]),
                 lambda t:t['frames']['quality'].update(candidateReportsObserved=2),
                 lambda t:t['frames']['quality'].update(candidateReportsNotRetained=1),
                 lambda t:t['frames']['quality'].update(candidateReportsObserved=True),
                 lambda t:t['frames'].update(candidateReports=t['frames']['candidateReports']*9),
                 lambda t:t['frames']['candidateReports'][0].pop(),
                 lambda t:t['frames']['candidateReports'][0].__setitem__(8,False),
                 lambda t:t['frames']['candidateReports'][0].__setitem__(8,-2**63-1),
                 lambda t:t['frames']['candidateReports'][0].__setitem__(7,-1)]
        for change in changes:
            trial=valid_trial(1,schema_version=2);change(trial)
            with self.subTest(change=change),self.assertRaises(ValueError):self.validate(trial)
        trial=valid_trial(1,schema_version=2);trial.update(status='failed',phase='stop_frame',failure='AssertionError')
        trial['frames']['targetFrame'].update(unknownDelayDurationNanos=-2**63,drawStartNanoTime=-1)
        trial['frames']['quality'].update(candidateReportsObserved=99,candidateReportsNotRetained=5)
        self.validate(trial)  # Preserve bounded arithmetic/accounting anomalies on failed originals.

    def test_v2_full_capacity_is_bounded_without_changing_existing_caps(self):
        self.assertEqual((32768,65536,16384,8388608,67108864),
                         (busy.MAX_TRIAL_BYTES,busy.MAX_REPORT_BYTES,busy.MAX_CAPTURE_BYTES,busy.MAX_ARTIFACT_BYTES,busy.MAX_ARCHIVE_BYTES))
        for version in (1,2):
            for index in (0,1):
                trial=maximum_failed_trial(index,version);self.validate(trial)
                self.assertLess(len(encoded(trial)),busy.MAX_TRIAL_BYTES)
            with tempfile.TemporaryDirectory() as folder:
                _,root=write_bundle(folder,profile='formal',schema_version=version)
                root['device']={key:'😀'*(bound//4) for key,bound in (('manufacturer',256),('model',256),('fingerprint',1024))}
                root.update(startedNanoTime=busy.LONG_MAX-1,endedNanoTime=busy.LONG_MAX,status='failed',
                            appProcessPid=2**31-1,cleanup={key:False for key in busy.CLEANUP_FIELDS})
                for ref in root['trials']:ref['bytes']=busy.MAX_TRIAL_BYTES
                busy.validate_report(root,37,'formal');self.assertLess(len(encoded(root)),busy.MAX_REPORT_BYTES)


class BusyStopCaptureBoundaryTests(unittest.TestCase):
    def test_only_named_profiles_and_fixed_orders_are_available(self):
        self.assertEqual('pilot', busy.profile_for_scope('busy-stop-pilot'))
        self.assertEqual('formal', busy.profile_for_scope('busy-stop-diagnostic'))
        for value in ('full', 'future', 'busy-stop-diagnostic --class Other', None):
            with self.assertRaises(ValueError):
                busy.profile_for_scope(value)
        self.assertEqual(4, busy.trial_count('pilot'))
        self.assertEqual(200, busy.trial_count('formal'))
        with self.assertRaises(ValueError): busy.trial_count('walkthrough')
        identities = [busy.trial_identity(i, 'pilot') for i in range(4)]
        self.assertEqual([busy.SCENARIOS[0], busy.SCENARIOS[1], busy.SCENARIOS[1], busy.SCENARIOS[0]],
                         [value['scenario'] for value in identities])
        for index in (-1, 4, True):
            with self.assertRaises(ValueError): busy.trial_identity(index, 'pilot')

    def test_capture_identity_cannot_mix_source_attempt_api_scope_or_profile(self):
        value = busy.empty_capture('37.0', SOURCE, 'pilot')
        busy.validate_capture(value, '37.0', SOURCE, 'pilot')
        mutations = [lambda v: v.update(profile='formal'),
                     lambda v: v.update(executionScope='busy-stop-diagnostic'),
                     lambda v: v.update(matrixLabel='37'),
                     lambda v: v.update(apiLevel=36),
                     lambda v: v.update(fullSuiteAcceptance=True),
                     lambda v: v.update(budgetAcceptance=True),
                     lambda v: v.update(diagnosticOnly=False),
                     lambda v: v.update(schemaVersion=True),
                     lambda v: v['sourceRun'].update(attempt=True),
                     lambda v: v['sourceRun'].update(commit='b' * 40),
                     lambda v: v.update(declaredCiProfile='faster_emulator')]
        for mutate in mutations:
            changed = copy.deepcopy(value); mutate(changed)
            with self.assertRaises(ValueError): busy.validate_capture(changed, '37.0', SOURCE, 'pilot')

    def test_capture_states_preserve_raw_failure_and_do_not_borrow_device_success(self):
        for status in ('passed', 'failed', 'incomplete'):
            value, _ = collected_capture(dict(profile='pilot', status=status))
            busy.validate_capture(value, '37.0', SOURCE, 'pilot')
            expected = status
            self.assertEqual(expected, value['diagnosticStatus'])
            if status == 'passed':
                value['gradleExitCode'] = 1
                with self.assertRaises(ValueError): busy.validate_capture(value, '37.0', SOURCE)
                value['diagnosticStatus'] = 'failed'
                busy.validate_capture(value, '37.0', SOURCE)
            else:
                value['diagnosticStatus'] = 'passed'
                with self.assertRaises(ValueError): busy.validate_capture(value, '37.0', SOURCE)

    def test_uncaptured_data_cannot_claim_pass_or_hide_an_attached_root(self):
        original = busy.empty_capture('30', SOURCE, 'pilot')
        for change in ({'diagnosticStatus': 'passed'}, {'report': {}},
                       {'captureError': None}, {'captureStatus': 'collected'},
                       {'captureStatus': 'partial'}, {'gradleExitCode': True}):
            value = dict(original, **change)
            with self.assertRaises(ValueError): busy.validate_capture(value, '30', SOURCE)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'capture.json').write_bytes(encoded(original))
            self.assertEqual({}, busy.validate_payloads(root, original, '30', SOURCE))
            (root / busy.REPORT_NAME).write_text('{}')
            with self.assertRaisesRegex(ValueError, 'payloads_present'):
                busy.validate_payloads(root, original, '30', SOURCE)

    def test_symlinks_duplicate_json_fields_and_oversized_capture_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original = busy.empty_capture('30', SOURCE, 'pilot')
            target = root / 'real.json'; target.write_bytes(encoded(original))
            folder = root / 'bundle'; folder.mkdir()
            (folder / 'capture.json').symlink_to(target)
            with self.assertRaisesRegex(ValueError, 'unsafe'):
                busy.validate_payloads(folder, original, '30', SOURCE)
            target.write_text('{"same":1,"same":2}')
            with self.assertRaises(ValueError): busy.read_json(target)
            target.write_bytes(b' ' * (busy.MAX_CAPTURE_BYTES + 1))
            with self.assertRaisesRegex(ValueError, 'bounded'):
                busy.read_json(target, busy.MAX_CAPTURE_BYTES)

    def test_capture_argument_must_match_its_actual_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original = busy.empty_capture('30', SOURCE, 'pilot')
            on_disk = dict(original, captureError='device_or_report_unavailable')
            (root / 'capture.json').write_bytes(encoded(on_disk))
            with self.assertRaisesRegex(ValueError, 'file_differs'):
                busy.validate_payloads(root, original, '30', SOURCE)

    def test_one_tar_call_preserves_all_formal_refs_without_shell_expansion(self):
        refs = [dict(path=busy.trial_name(i), bytes=2) for i in range(200)]
        seen = []
        def run(args, **kwargs):
            seen.append(args)
            with tarfile.open(fileobj=kwargs['stdout'], mode='w:') as stream:
                for ref in refs:
                    member = tarfile.TarInfo(ref['path']); member.size=2
                    stream.addfile(member, io.BytesIO(b'{}'))
            return subprocess.CompletedProcess(args, 0)
        with tempfile.TemporaryDirectory() as temporary, patch.object(busy.subprocess, 'run', side_effect=run):
            folder = Path(temporary)
            busy.pull_shards(dict(trials=refs), folder, time.monotonic() + 30)
            self.assertEqual(200, len(list(folder.iterdir())))
            self.assertEqual(1, len(seen))
            self.assertEqual(['adb', 'exec-out', 'run-as', 'dev.agentos.app', 'tar', '-C',
                              'files/performance/busy-stop', '-cf', '-'], seen[0][:9])
            self.assertEqual([ref['path'] for ref in refs], seen[0][9:])

    def test_tar_rejects_escape_duplicate_links_unknown_names_and_wrong_lengths(self):
        for attack in ('escape', 'duplicate', 'link', 'extra', 'size', 'missing'):
            def run(args, **kwargs):
                with tarfile.open(fileobj=kwargs['stdout'], mode='w:') as stream:
                    name = '../outside.json' if attack=='escape' else 'trial-000.json'
                    names = [] if attack=='missing' else [name]
                    if attack=='duplicate': names.append(name)
                    if attack=='extra': names.append('trial-001.json')
                    for name in names:
                        member = tarfile.TarInfo(name)
                        if attack=='link':
                            member.type=tarfile.SYMTYPE; member.linkname='../target'
                            stream.addfile(member)
                        else:
                            data=b'{} ' if attack=='size' else b'{}'; member.size=len(data)
                            stream.addfile(member, io.BytesIO(data))
                return subprocess.CompletedProcess(args, 0)
            with self.subTest(attack=attack), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(busy.subprocess, 'run', side_effect=run):
                folder=Path(temporary)
                with self.assertRaises(ValueError):
                    busy.pull_shards(dict(trials=[dict(path='trial-000.json',bytes=2)]), folder, time.monotonic()+30)
                self.assertFalse((folder/'shards.tar').exists())

    def test_collector_reads_exact_committed_bundle_and_preserves_nonzero_exit(self):
        for status,exit_code in (('passed',0),('passed',1),('failed',1),('incomplete',124)):
            with self.subTest(status=status,exit=exit_code),tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary);source_dir=root/'source';output=root/'output'
                trials=None if status=='passed' else [valid_trial()]
                capture,report=write_bundle(source_dir,status=status,trials=trials)
                calls=[]
                def run(args,**kwargs):
                    calls.append(args)
                    if 'getprop' in args:kwargs['stdout'].write(b'37\n')
                    elif 'cat' in args:kwargs['stdout'].write((source_dir/busy.REPORT_NAME).read_bytes())
                    elif 'tar' in args:
                        with tarfile.open(fileobj=kwargs['stdout'],mode='w:') as stream:
                            for ref in report['trials']:
                                raw=(source_dir/ref['path']).read_bytes()
                                member=tarfile.TarInfo(ref['path']);member.size=len(raw)
                                stream.addfile(member,io.BytesIO(raw))
                    else:raise AssertionError(args)
                    self.assertGreater(kwargs['timeout'],0);self.assertLessEqual(kwargs['timeout'],15)
                    return subprocess.CompletedProcess(args,0)
                with patch.object(busy.subprocess,'run',side_effect=run):
                    actual=busy.collect('37.0',SOURCE,exit_code,output,'pilot')
                self.assertEqual('collected',actual['captureStatus'])
                self.assertEqual(status if status!='passed' or exit_code==0 else 'failed',actual['diagnosticStatus'])
                self.assertEqual(exit_code,actual['gradleExitCode']);self.assertEqual(3,len(calls))
                for path in output.iterdir():self.assertEqual((source_dir/path.name).read_bytes(),path.read_bytes())
                (output/'capture.json').write_bytes(encoded(actual))
                busy.validate_payloads(output,actual,'37.0',SOURCE,'pilot')

    def test_collection_deadline_and_wrong_device_do_not_create_a_pass(self):
        with self.assertRaisesRegex(ValueError, 'deadline'):
            busy.remaining_timeout(time.monotonic()-1)
        def run(args, **kwargs):
            kwargs['stdout'].write(b'36\n')
            return subprocess.CompletedProcess(args, 0)
        with tempfile.TemporaryDirectory() as temporary, patch.object(busy.subprocess, 'run', side_effect=run):
            value=busy.collect('37.0', SOURCE, 0, Path(temporary), 'pilot')
            self.assertEqual('invalid', value['captureStatus'])
            self.assertEqual('device_sdk_mismatch', value['captureError'])
            self.assertEqual('unavailable', value['diagnosticStatus'])
            busy.validate_capture(value, '37.0', SOURCE, 'pilot')


if __name__ == '__main__':
    unittest.main()
