#!/usr/bin/env python3
"""Bounded offline timing analysis of immutable API37 keyboard-experiment archives.

No production validator is imported. Numbers come from original command bytes,
raw markers and fixture receipts, not producer pass/fail or positive-count labels.
"""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
import subprocess

PROGRAM = Path(__file__).resolve()
PROGRAM_RELATIVE = 'scripts/window_infos_timeline.py'
MAX_FILE = 4 * 1024 * 1024
MAX_TOTAL = 36 * 1024 * 1024
MAX_DUMP = 262144
MAX_HISTORY = 256
ARMS = ('baseline', 'enabled')
RAW_FILES = {'report.json','commands.json','capture.json','raw-result.json','fixture.json',
             'probe-build.json','probe.jar','screenshot.png','ime-state.json'}
TOP_FILES = {'report.json','commands.json','journal.json','input-devices.json','avd-config.ini','fixture.apk'}
ORIGIN_FILES = {'source-run.json','source-jobs.json','source-artifacts.json'}
FILES = TOP_FILES | ORIGIN_FILES | {'manifest.json'} | {a+'/'+f for a in ARMS for f in RAW_FILES}
PROPS = ('ro.kernel.qemu','ro.boot.qemu','ro.build.version.sdk','ro.product.cpu.abi','sys.boot_completed','ro.build.fingerprint')
ARM_IDS = ('devices',*('property_'+p for p in PROPS),'extra_vm_options','current_user','shell_uid',
           'timeout_available','installed_packages','ime_before','ime_list','ime_uid','apk_badging',
           'install_fixture','fixture_paths','fixture_uid','push_probe','probe_digest','raw_probe',
           'fixture_start','ime_state','screenshot','fixture_receipt','ime_after','ime_uid_after','cleanup_fixture','cleanup_probe')
TOP_IDS = ('devices','api','qemu','current_user','shell_uid','input_dump','settings_before','uninstall_fixture',
           'packages_after_uninstall','enable_setting','settings_enabled','restore_setting','settings_restored')
SCOPE = {'diagnosticOnly': True, 'newDeviceExecution': False, 'inputFilesModified': False,
         'producerAcceptanceLabelsUsedForTiming': False, 'causalInference': False,
         'originalNotesAcceptance': False, 'fiveSecondReferenceOrigin': 'THIS_FIXTURE_SHOW_REQUEST',
         'fiveSecondReferenceIsOriginalNotesWaitReplay': False,
         'historyPrintOrderIsStartTimeOrder': False, 'historyAbsenceProvesNoEvent': False,
         'tagIsBinderIdentity': False, 'requestWindowNameExportedOrUsed': False,
         'trackerCancelMeansUiRollback': False, 'printedWallTimeTimezone': 'DEVICE_LOCAL_UNSPECIFIED',
         'elapsedClockSourceCommit': '94b4c163b7dfe5ce3607f7bb8456f9573f7de57d',
         'elapsedClockSourcePath': 'services/core/java/com/android/server/inputmethod/ImeTrackerService.java',
         'elapsedClockSourceSha256': '8e2032cfc2bb43f2e365ca76fe2a99647c0ab46391529681b85c4423186fd658',
         'completedHistoryCapacity': 200, 'sourceImageEquivalenceVerified': False,
         'callbackGapProvesContinuousEmptyRegion': False}


class TimelineError(ValueError):
    pass


def require(condition, reason):
    if not condition: raise TimelineError(reason)


def identity(data): return {'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
def same(a,b): return json.dumps(a,sort_keys=True,separators=(',',':')) == json.dumps(b,sort_keys=True,separators=(',',':'))
def integer(value, low=0, high=2**63-1): return type(value) is int and low <= value <= high


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'JSON_DUPLICATE_KEY'); result[key] = value
    return result


def decode(data):
    require(type(data) is bytes and len(data) <= MAX_FILE, 'JSON_INPUT_BOUND')
    try:
        def finite_float(text):
            require(len(text)<=64, 'JSON_FLOAT_BOUND')
            value=float(text);require(math.isfinite(value),'JSON_NONFINITE');return value
        value=json.loads(data, object_pairs_hook=unique_object,parse_float=finite_float,
                         parse_constant=lambda _: (_ for _ in ()).throw(TimelineError('JSON_NONFINITE')))
        pending=[(value,0)];nodes=0
        while pending:
            item,depth=pending.pop();nodes+=1
            require(depth<=64 and nodes<=100000,'JSON_STRUCTURE_BOUND')
            if type(item) is dict:pending.extend((child,depth+1) for child in item.values())
            elif type(item) is list:pending.extend((child,depth+1) for child in item)
        return value
    except (UnicodeError, ValueError, RecursionError) as error:
        if isinstance(error, TimelineError): raise
        raise TimelineError('JSON_FORMAT') from None


def read_file(path, limit=MAX_FILE):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor,'rb') as stream:
        info = os.fstat(stream.fileno()); require(stat.S_ISREG(info.st_mode) and info.st_size <= limit, 'FILE_BOUND_OR_TYPE')
        data = stream.read(limit+1)
        require(len(data) == info.st_size and len(data) <= limit, 'FILE_CHANGED_OR_LIMIT')
        return data


def git(repo, *args):
    try:
        return subprocess.run(['git','-C',str(repo),*args],check=True,stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL,timeout=10).stdout
    except (OSError, subprocess.SubprocessError): raise TimelineError('GIT_OBJECT_UNAVAILABLE') from None


def commit(repo, value):
    require(type(value) is str and re.fullmatch('[0-9a-f]{40}',value), 'GIT_COMMIT_FORMAT')
    require(git(repo,'cat-file','-t',value).strip() == b'commit', 'GIT_COMMIT_TYPE')
    return value


def git_blob(repo, revision, path, limit=MAX_FILE):
    require(type(path) is str and len(path) <= 256 and re.fullmatch('[A-Za-z0-9_./-]+',path)
            and not path.startswith('/') and '..' not in Path(path).parts, 'GIT_PATH')
    obj = revision+':'+path
    require(git(repo,'cat-file','-t',obj).strip() == b'blob', 'GIT_BLOB_TYPE')
    size = git(repo,'cat-file','-s',obj).strip()
    require(re.fullmatch(rb'[0-9]{1,12}',size) and int(size) <= limit, 'GIT_BLOB_BOUND')
    data = git(repo,'cat-file','blob',obj)
    require(len(data) == int(size), 'GIT_BLOB_SIZE')
    return data


def timestamp(value):
    require(type(value) is str and len(value) <= 40, 'COMMAND_TIME_FORMAT')
    try: parsed = datetime.fromisoformat(value.replace('Z','+00:00'))
    except ValueError: raise TimelineError('COMMAND_TIME_FORMAT') from None
    require(parsed.utcoffset() is not None and parsed.utcoffset().total_seconds() == 0, 'COMMAND_TIME_NOT_UTC')
    return parsed


def source_identity(value):
    require(type(value) is dict and set(value) == {'repository','commit','runId','attempt'}
            and value['repository'] == 'ecojust/Agent-OS' and type(value['commit']) is str
            and re.fullmatch('[0-9a-f]{40}',value['commit'])
            and integer(value['runId'],1,10**19-1) and integer(value['attempt'],1,10**19-1), 'EVIDENCE_SOURCE')
    return value


def file_limit(name):
    if name == 'manifest.json' or name == 'avd-config.ini' or name.endswith('/screenshot.png'): return 1048576
    if name == 'fixture.apk' or name.endswith('/probe.jar'): return 2097152
    return MAX_FILE


def read_archive(directory, repo, archive_commit):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), 'ARCHIVE_DIRECTORY')
    blobs = {}; total = 0
    for item in sorted(directory.iterdir()):
        require(not item.is_symlink(), 'ARCHIVE_SYMLINK')
        if item.name in ARMS:
            require(item.is_dir(), 'ARCHIVE_ARM_DIRECTORY'); entries = sorted(item.iterdir())
        else: entries = [item]
        for entry in entries:
            name = entry.relative_to(directory).as_posix()
            require(name in FILES and entry.is_file() and not entry.is_symlink(), 'ARCHIVE_FILESET')
            data = read_file(entry,file_limit(name)); blobs[name] = data; total += len(data)
    require(total <= MAX_TOTAL and {'manifest.json',*ORIGIN_FILES,'report.json','commands.json'} <= set(blobs), 'ARCHIVE_REQUIRED_OR_TOTAL')
    manifest = decode(blobs['manifest.json'])
    require(type(manifest) is dict and manifest.get('schemaVersion') == 1 and type(manifest.get('schemaVersion')) is int
            and manifest.get('kind') == 'window-infos-keyboard-archive', 'ARCHIVE_SCHEMA')
    source = source_identity(manifest['source']); commit(repo,source['commit'])
    require(git(repo,'rev-list','--parents','-n','1',archive_commit).decode().strip().split()
            == [archive_commit,source['commit']], 'ARCHIVE_PARENT_NOT_SOURCE')
    entries = manifest.get('files'); require(type(entries) is list and len(entries) <= len(FILES)-1, 'MANIFEST_FILE_BOUND')
    listed = set()
    for item in entries:
        require(type(item) is dict and set(item) == {'path','bytes','sha256'} and item['path'] not in listed
                and item['path'] in blobs and item['path'] != 'manifest.json', 'MANIFEST_FILE_ENTRY')
        name = item['path']; listed.add(name)
        require(same(identity(blobs[name]),{k:item[k] for k in ('bytes','sha256')}), 'MANIFEST_BYTES')
    require(listed == set(blobs)-{'manifest.json'}, 'MANIFEST_FILESET')
    prefix = f"screenshots/window-infos-keyboard-{source['runId']}-{source['attempt']}/"
    for name, data in blobs.items():
        require(git_blob(repo,archive_commit,prefix+name,file_limit(name)) == data, 'ARCHIVE_GIT_BYTES')
    values = {name:decode(data) for name,data in blobs.items() if name.endswith('.json')}
    report = values['report.json']
    require(type(report) is dict and report.get('kind') == 'window-infos-keyboard' and report.get('schemaVersion') == 1
            and type(report.get('schemaVersion')) is int and same(report.get('source'),source), 'REPORT_SOURCE_OR_SCHEMA')
    sources = report.get('sourceFiles'); require(type(sources) is dict and 18 <= len(sources) <= 64, 'SOURCE_FILES_BOUND')
    for path, receipt in sources.items():
        require(same(identity(git_blob(repo,source['commit'],path)),receipt), 'SOURCE_FILE_BYTES')
    for arm in ARMS:
        require(arm+'/report.json' in values and arm+'/commands.json' in values, 'ARM_REQUIRED')
        child = values[arm+'/report.json']
        require(type(child) is dict and child.get('kind') == 'window-infos-raw' and child.get('schemaVersion') == 2
                and type(child.get('schemaVersion')) is int and child.get('apiLevel') == 37
                and same(child.get('source'),source), 'ARM_SOURCE_OR_SCHEMA')
        child_sources = child.get('sourceFiles')
        require(type(child_sources) is dict and 10 <= len(child_sources) <= len(sources)
                and all(path in sources and same(receipt,sources[path]) for path,receipt in child_sources.items()), 'ARM_SOURCE_FILES')
    run = values['source-run.json']
    require(type(run) is dict and run.get('id') == source['runId'] and run.get('run_attempt') == source['attempt']
            and run.get('head_sha') == source['commit'] and run.get('path') == '.github/workflows/window-infos-raw.yml'
            and run.get('event') == 'workflow_dispatch' and run.get('head_branch') == 'master'
            and run.get('status') == 'completed', 'SOURCE_RUN_BINDING')
    repository=run.get('repository');head_repository=run.get('head_repository')
    require(type(repository) is dict and type(head_repository) is dict
            and repository.get('full_name') == head_repository.get('full_name') == source['repository']
            and integer(repository.get('id'),1) and repository['id'] == head_repository.get('id'), 'SOURCE_REPOSITORY_BINDING')
    require(manifest.get('sourceConclusion') == run.get('conclusion')
            and run.get('conclusion') in {'success','failure','cancelled','timed_out','action_required','neutral','stale'}, 'SOURCE_RUN_CONCLUSION')
    def pages(name,key):
        pages = values[name]; require(type(pages) is list and 1 <= len(pages) <= 8, 'ORIGIN_PAGE_BOUND')
        result = []
        for page in pages:
            require(type(page) is dict and type(page.get(key)) is list and len(page[key]) <= 256, 'ORIGIN_PAGE_SHAPE')
            require(all(type(item) is dict for item in page[key]),'ORIGIN_ITEM_SHAPE');result.extend(page[key])
        require(len(result) <= 512, 'ORIGIN_ITEM_BOUND'); return result
    require(type(manifest.get('keyboardJob')) is dict and type(manifest.get('artifact')) is dict,'MANIFEST_ORIGIN_SHAPE')
    jobs = [job for job in pages('source-jobs.json','jobs') if job.get('id') == manifest['keyboardJob'].get('id')]
    require(len(jobs) == 1 and jobs[0].get('name') == 'keyboard' and jobs[0].get('run_id') == source['runId']
            and jobs[0].get('run_attempt') == source['attempt'] and jobs[0].get('head_sha') == source['commit']
            and jobs[0].get('status') == 'completed' and jobs[0].get('conclusion') == manifest['keyboardJob'].get('conclusion')
            and jobs[0].get('conclusion') in {'success','failure','cancelled','timed_out','action_required','neutral','stale'}, 'SOURCE_JOB_BINDING')
    artifacts = [item for item in pages('source-artifacts.json','artifacts') if item.get('id') == manifest.get('artifact',{}).get('id')]
    require(len(artifacts) == 1 and artifacts[0].get('name') == 'window-infos-keyboard-api-37.0-attempt-'+str(source['attempt'])
            and type(artifacts[0].get('workflow_run')) is dict, 'SOURCE_ARTIFACT_BINDING')
    origin=artifacts[0]['workflow_run']
    require(origin.get('id') == source['runId'] and origin.get('head_sha') == source['commit']
            and origin.get('head_branch') == 'master' and origin.get('repository_id') == origin.get('head_repository_id') == repository['id'], 'SOURCE_ARTIFACT_BINDING')
    return blobs,values,source,{'manifest':identity(blobs['manifest.json']),'filesChecked':len(blobs),
                               'sourceFilesChecked':len(sources),'totalArchiveBytes':total}


def command_data(item):
    try: return base64.b64decode(item['outputBase64'],validate=True)
    except (ValueError,TypeError): raise TimelineError('COMMAND_BASE64') from None


def complete(item):
    return item is not None and item['status'] == 'completed' and item['exitCode'] == 0 \
        and item['endedAt'] is not None and item['elapsedMillis'] <= item['timeoutSeconds']*1000 \
        and item['bytes'] < item['byteLimit']


def command_text(item): return command_data(item).decode('utf-8').strip()


def commands(value, source, arm):
    require(type(value) is dict and same(value.get('source'),source)
            and type(value.get('schemaVersion')) is int and value['schemaVersion'] == 1
            and value.get('kind') == ('window-infos-raw-commands' if arm else 'window-infos-keyboard-commands'), 'COMMAND_SOURCE_OR_SCHEMA')
    entries = value.get('commands'); catalog = ARM_IDS if arm else TOP_IDS
    require(type(entries) is list and len(entries) <= len(catalog), 'COMMAND_COUNT')
    result = {}; previous = -1; total = 0; previous_start = None
    for ordinal,item in enumerate(entries,1):
        require(type(item) is dict and item.get('id') in catalog and item['id'] not in result
                and type(item.get('ordinal')) is int and item['ordinal'] == ordinal, 'COMMAND_ID')
        name = item['id']; position = catalog.index(name); require(position>previous,'COMMAND_ORDER');previous=position
        require(type(item.get('argv')) is list and 1<=len(item['argv'])<=32
                and all(type(s) is str and 0<len(s)<=1024 and '\x00' not in s for s in item['argv']), 'COMMAND_ARGV_BOUND')
        seconds = 60 if name in ('raw_probe','install_fixture') else 12 if name in ('input_dump','ime_state') else 30 if name=='uninstall_fixture' else 10
        limit = 1048576 if name=='screenshot' else MAX_DUMP if name in ('raw_probe','input_dump','ime_state') else 65536
        require(type(item.get('timeoutSeconds')) is int and item['timeoutSeconds']==seconds
                and type(item.get('byteLimit')) is int and item['byteLimit']==limit, 'COMMAND_LIMITS')
        require(item.get('status') in {'running','completed','timed_out','output_limit','cancelled','exception'}
                and integer(item.get('elapsedMillis')) and (item.get('exitCode') is None or integer(item['exitCode'],-2**31,2**31-1)), 'COMMAND_STATE')
        require(type(item.get('outputBase64')) is str and len(item['outputBase64'])<=4*((limit+2)//3), 'COMMAND_BASE64_BOUND')
        blob=command_data(item);total+=len(blob)
        require(len(blob)<=limit and same(identity(blob),{k:item.get(k) for k in ('bytes','sha256')}),'COMMAND_BYTES')
        start=timestamp(item.get('startedAt'));require(previous_start is None or start>=previous_start,'COMMAND_TIME_ORDER');previous_start=start
        if item.get('endedAt') is None:require(item['status']=='running','COMMAND_END_MISSING')
        else:require(timestamp(item['endedAt'])>=start,'COMMAND_END_BEFORE_START')
        for earlier in result.values():
            if earlier['endedAt'] is not None and start<timestamp(earlier['endedAt']):
                require(arm and earlier['id']=='raw_probe' and name=='fixture_start'
                        and item['endedAt'] is not None and timestamp(item['endedAt'])<=timestamp(earlier['endedAt']), 'COMMAND_OVERLAP')
        result[name]=item
    require(total<=2097152,'COMMAND_TOTAL_OUTPUT')
    return result


def unknown(reason, **extra): return {'status':'unknown','reason':reason,**extra}


def raw_timeline(cm, values, arm, source):
    try:
        needed=('raw_probe','fixture_start','fixture_receipt','ime_before','ime_after','ime_uid','ime_uid_after','fixture_uid',
                'property_ro.build.version.sdk','current_user','shell_uid')
        require(all(name in cm and complete(cm[name]) for name in needed),'RAW_OR_FIXTURE_COMMAND_NOT_COMPLETE')
        require(command_text(cm['property_ro.build.version.sdk'])=='37' and command_text(cm['current_user'])=='0'
                and command_text(cm['shell_uid'])=='2000','RAW_ENVIRONMENT')
        component=command_text(cm['ime_before']);require(component==command_text(cm['ime_after']) and component.count('/')==1,'RAW_IME_COMPONENT')
        package,service=component.split('/');require(re.fullmatch(r'[A-Za-z][A-Za-z0-9_.]*',package)
                and re.fullmatch(r'\.?[A-Za-z_$][A-Za-z0-9_.$]*',service),'RAW_IME_COMPONENT')
        ime_uid_text=command_text(cm['ime_uid']);match=re.fullmatch('package:'+re.escape(package)+r' uid:([0-9]+)',ime_uid_text)
        require(match is not None and ime_uid_text==command_text(cm['ime_uid_after']),'RAW_IME_UID');ime_uid=int(match[1])
        require(0<ime_uid<100000,'RAW_IME_UID_RANGE')
        wire=command_data(cm['raw_probe']);require(wire.endswith(b'\n'),'RAW_MARKER_TERMINATION')
        lines=wire.splitlines();ready_prefix=b'WINDOW_INFOS_RAW_READY_V1 ';result_prefix=b'WINDOW_INFOS_RAW_RESULT_V1 '
        ready_lines=[x for x in lines if x.startswith(ready_prefix)];results=[x for x in lines if x.startswith(result_prefix)]
        require(len(ready_lines)==len(results)==1 and all(len(x)+1<=131072 for x in ready_lines+results),'RAW_MARKER_COUNT_OR_BOUND')
        ready=decode(ready_lines[0][len(ready_prefix):]);raw=decode(results[0][len(result_prefix):])
        require(type(raw) is dict and type(ready) is dict
                and same(raw,values.get(arm+'/raw-result.json')) and same(raw.get('source'),source)
                and same(ready.get('source'),source),'RAW_MARKER_SOURCE_OR_ORIGINAL')
        require(raw.get('schemaVersion')==1 and type(raw.get('schemaVersion')) is int
                and raw.get('imePackage')==ready.get('imePackage')==package
                and raw.get('imeUid')==ready.get('imeUid')==ime_uid and raw.get('displayId')==ready.get('displayId')==0,'RAW_MARKER_IDENTITY')
        require(raw['resultStatus']=='RECORDED' and raw['registrationStatus']=='REGISTERED'
                and raw['unregistrationStatus']=='RETURNED' and raw['constructAttempts']==raw['registerAttempts']==raw['unregisterAttempts']==1
                and raw['collectionFrozen'] is True and raw['outputTruncated'] is False
                and raw['callbacksDropped']==raw['callbacksOutsideWindow']==raw['outputOmittedCallbacks']==0,'RAW_COLLECTION_INCOMPLETE')
        process=raw['process'];times=raw['times'];samples=raw['samples']
        require(process['sdk']==37 and process['uid']==2000 and integer(process['startedElapsedMs'])
                and integer(process['finishedElapsedMs']) and 0<process['finishedElapsedMs']-process['startedElapsedMs']<40000,'RAW_PROCESS_CLOCK')
        require(integer(times['readyElapsedMs']) and integer(times['observationDeadlineElapsedMs'])
                and ready['elapsedRealtimeMs']==times['readyElapsedMs']
                and times['observationDeadlineElapsedMs']-times['readyElapsedMs']==12000
                and times['freezeElapsedMs']>=times['observationDeadlineElapsedMs']
                and process['finishedElapsedMs']>=times['freezeElapsedMs'],'RAW_OBSERVATION_CLOCK')
        require(type(samples) is list and len(samples)<=128 and raw['callbacksSeen']==raw['callbacksStored']==len(samples),'RAW_CALLBACK_COUNT')
        receipt=decode(command_data(cm['fixture_receipt']))
        require(type(receipt) is dict and same(receipt,values.get(arm+'/fixture.json')) and same(receipt.get('source'),source),'FIXTURE_SOURCE_OR_ORIGINAL')
        fixture_uid=re.fullmatch(r'package:dev\.agentos\.compat uid:([0-9]+)',command_text(cm['fixture_uid']))
        require(fixture_uid is not None and receipt['uid']==int(fixture_uid[1]) and receipt['sdkInt']==37 and receipt['displayId']==0,'FIXTURE_UID')
        show=receipt['showRequestedElapsedMs'];insets=receipt['firstImeVisibleElapsedMs']
        require(receipt['showImeAttempts']==1 and receipt['showImeAccepted'] is True and receipt['imeVisibleObserved'] is True
                and integer(show) and integer(insets) and times['readyElapsedMs']<=receipt['startedElapsedMs']<=show<=insets<=process['finishedElapsedMs'],'FIXTURE_SHOW_CLOCK')
        argv=cm['raw_probe']['argv'];require(len(argv)>3 and argv[1]=='-s' and re.fullmatch(r'emulator-[0-9]{4,5}',argv[2]),'RAW_SERIAL')
        prefix=argv[:3];remote=f"/data/local/tmp/agentos-window-infos-raw-{source['runId']}-{source['attempt']}.jar"
        require(argv==prefix+['shell','CLASSPATH='+remote,'/system/bin/timeout','-s','KILL','40s','app_process','/system/bin',
                'dev.agentos.raw.WindowInfosRawMain',source['commit'],str(source['runId']),str(source['attempt']),package,str(ime_uid),'0'],'RAW_ARGV')
        require(cm['fixture_start']['argv']==prefix+['shell','am','start','-W','--user','0','-n','dev.agentos.compat/.RawWindowFixtureActivity',
                '--es','sourceSha',source['commit'],'--es','runId',str(source['runId']),'--es','runAttempt',str(source['attempt']),'--ez','rawWindowProbe','true'],'FIXTURE_ARGV')
        callbacks=[];positive=[];previous_seq=0;previous_elapsed=process['startedElapsedMs']
        for sample in samples:
            require(type(sample) is dict and sample.get('kind')=='CALLBACK' and integer(sample.get('sequence'),previous_seq+1,raw['callbacksSeen'])
                    and integer(sample.get('elapsedRealtimeMs')) and process['startedElapsedMs']<=sample['elapsedRealtimeMs']<times['observationDeadlineElapsedMs'],'RAW_CALLBACK_CLOCK_OR_SEQUENCE')
            require(sample['elapsedRealtimeMs']>=previous_elapsed,'RAW_CALLBACK_ELAPSED_ORDER')
            previous_elapsed=sample['elapsedRealtimeMs']
            previous_seq=sample['sequence'];rows=sample['rows'];require(type(rows) is list and len(rows)<=16,'RAW_ROW_BOUND')
            require(sample['complete'] is True and sample['issues']==[] and sample['candidateRowCount']==len(rows),'RAW_SAMPLE_INCOMPLETE')
            ids=[];passing=0
            for row in rows:
                require(row['ownerUid']==ime_uid and row['layoutParamsType']==2011 and row['displayId']==0
                        and integer(row['binderId'],0,16) and integer(row['inputConfig'],-2**31,2**31-1)
                        and type(row['frame']) is list and len(row['frame'])==4 and all(integer(x,-2**31,2**31-1) for x in row['frame'])
                        and type(row['regionEmpty']) is bool,'RAW_ROW_FORMAT')
                left,top,right,bottom=row['frame'];config=row['inputConfig'];ids.append(row['binderId'])
                if config&2==0 and config&65536==0 and left<right and top<bottom and not row['regionEmpty']:passing+=1
            unique=bool(ids) and 0 not in ids and len(set(ids))==1
            require(sample['nullTokenCount']==ids.count(0) and sample['distinctBinderCount']==len(set(ids)-{0}),'RAW_BINDER_COUNTERS')
            qualifies=unique and passing>0 and sample['elapsedRealtimeMs']>=show
            point={'sequence':sample['sequence'],'elapsedRealtimeMs':sample['elapsedRealtimeMs'],
                   'candidateRows':len(rows),'uniqueCandidateBinder':unique,'passingRowsRecomputed':passing,'qualifiesAfterShow':qualifies}
            callbacks.append(point)
            if qualifies:positive.append(point)
        first=positive[0] if positive else None
        previous=next((callbacks[i-1] for i,p in enumerate(callbacks) if i>0 and first is p),None)
        delta=None if first is None else first['elapsedRealtimeMs']-show
        return {'status':'recorded','reason':None,'processStartElapsedMs':process['startedElapsedMs'],
                'processFinishElapsedMs':process['finishedElapsedMs'],'readyElapsedMs':times['readyElapsedMs'],
                'observationDeadlineElapsedMs':times['observationDeadlineElapsedMs'],'fixtureShowElapsedMs':show,
                'firstInsetsVisibleElapsedMs':insets,'showToInsetsVisibleMs':insets-show,
                'callbackCount':len(callbacks),'candidateCallbackCount':sum(p['candidateRows']>0 for p in callbacks),
                'positiveCallbackCountRecomputed':len(positive),'firstPositive':first,'showToFirstPositiveMs':delta,
                'previousCallbackBeforeFirstPositive':previous,
                'previousCallbackGapMs':None if previous is None else first['elapsedRealtimeMs']-previous['elapsedRealtimeMs'],
                'fiveSecondFromThisFixtureShow':{'referenceMs':5000,'firstPositiveWithinReference':None if delta is None else delta<=5000,
                                               'replaysOriginalNotesWait':False},'callbacks':callbacks,
                'callbackElapsedOrderSequenceNumbers':[p['sequence'] for p in callbacks],
                'imeIdentity':{'component':component,'package':package,'uidFromPackageCommands':ime_uid},
                'hostRawCommand':{k:cm['raw_probe'][k] for k in ('startedAt','endedAt','elapsedMillis','bytes','sha256')}}
    except (TimelineError,KeyError,TypeError,ValueError) as error:
        return unknown(str(error) if isinstance(error,TimelineError) else 'RAW_FORMAT',callbacks=None,firstPositive=None)


WALL = r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}'


def parse_history(data, raw=None):
    """One complete IMMS history; count/format failure is unknown, never an empty list."""
    try:
        require(type(data) is bytes and 0<len(data)<MAX_DUMP and data.endswith(b'\n'),'HISTORY_INPUT_BOUND_OR_TERMINATION')
        text=data.decode('utf-8');require(not any(ord(c)<32 and c not in '\n\r\t' for c in text),'HISTORY_CONTROL_CHARACTER')
        errors=('Failed to dump input method','No input method client.','No input method service.',
                "Warning: Current input method client doesn't match",'Permission Denial:','Exception:',
                'Timeout waiting for dump','Interrupted waiting for dump',"*** SERVICE 'input_method' DUMP TIMEOUT",
                "Can't find service: input_method","Error with service 'input_method'",'Failed to create pipe',
                'Error in poll while dumping','Failed to read while dumping','Failed to write while dumping','usage: dumpsys')
        require(not any(e in text for e in errors),'HISTORY_DUMP_ERROR')
        lines=text.splitlines();require(len(lines)<=8192 and all(len(line)<=8192 for line in lines),'HISTORY_LINE_BOUND')
        manager=[i for i,line in enumerate(lines) if line=='Input Method Manager Service state:']
        headers=[i for i,line in enumerate(lines) if line.startswith('  mImeTrackerService#History:')]
        users=[i for i,line in enumerate(lines) if line.startswith('  UserId=')]
        require(len(manager)==len(headers)==len(users)==1 and manager[0]<headers[0]<users[0]
                and lines[headers[0]]=='  mImeTrackerService#History:' and lines[users[0]]=='  UserId=0','HISTORY_BOUNDARY')
        service_headers=[i for i,line in enumerate(lines) if line.startswith('Input method service state for ')]
        service_ends=[i for i,line in enumerate(lines) if line.startswith('  mBackCallbackRegistered=')]
        require(len(service_headers)==len(service_ends)==1 and users[0]<service_headers[0]<service_ends[0]
                and re.fullmatch(r'Input method service state for [A-Za-z_$][A-Za-z0-9_.$]*@[0-9a-f]{1,16}:',lines[service_headers[0]])
                and re.fullmatch(r'  mBackCallbackRegistered=(true|false)',lines[service_ends[0]]),'HISTORY_DUMP_SERVICE_BOUNDARY')
        segment=lines[headers[0]+1:users[0]];position=0;events=[];seen=set();counts={}
        for section,label in (('active','mActiveEntries'),('completed','mCompletedEntries')):
            require(position<len(segment),'HISTORY_COUNT_MISSING')
            match=re.fullmatch('    '+label+r': ([0-9]{1,4}) elements',segment[position])
            require(match is not None,'HISTORY_COUNT_FORMAT');count=int(match[1]);counts[section]=count;position+=1
            require(count<=MAX_HISTORY and (section!='completed' or count<=200) and len(events)+count<=MAX_HISTORY,'HISTORY_ENTRY_LIMIT')
            for _ in range(count):
                require(position+5<=len(segment),'HISTORY_ENTRY_TRUNCATED');block=segment[position:position+5];position+=5
                head=re.fullmatch(r'      #([0-9]{1,10}) (TYPE_[A-Z0-9_]{1,64}) - (STATUS_[A-Z0-9_]{1,64}) - (.{1,256}) \((-?[0-9]{1,19})ms\):',block[0])
                start=re.fullmatch(r'        startTime=('+WALL+r') \(timestamp=([0-9]{1,19})\) (ORIGIN_[A-Z0-9_]{1,64})',block[1])
                reason=re.fullmatch(r'        reason=([A-Z0-9_]{1,96}) (PHASE_[A-Z0-9_]{1,96})(?: lastProgressTime=('+WALL+r'))?',block[2])
                user=re.fullmatch(r'        userId=(-?[0-9]{1,10}) displayId=(-?[0-9]{1,10})',block[3])
                require(all(x is not None for x in (head,start,reason,user))
                        and re.fullmatch(r'        requestWindowName=.{0,2048}',block[4]),'HISTORY_ENTRY_FORMAT')
                final_states={'STATUS_SUCCESS','STATUS_FAIL','STATUS_CANCEL','STATUS_TIMEOUT'}
                require(head[3] in final_states | ({'STATUS_RUN'} if section=='active' else set()),'HISTORY_STATUS_NOT_KNOWN_FOR_SECTION')
                sequence=int(head[1]);duration=int(head[5]);elapsed=int(start[2]);require(sequence not in seen,'HISTORY_DUPLICATE_SEQUENCE');seen.add(sequence)
                require(integer(sequence,0,2**31-1) and integer(duration) and integer(elapsed) and elapsed+duration<2**63,'HISTORY_TIME_RANGE')
                require((head[3]=='STATUS_TIMEOUT') == (reason[3] is not None),'HISTORY_TIMEOUT_FORMAT')
                datetime.strptime(start[1],'%Y-%m-%d %H:%M:%S.%f')
                if reason[3] is not None:datetime.strptime(reason[3],'%Y-%m-%d %H:%M:%S.%f')
                clock_known=elapsed>0 and head[2]!='TYPE_NOT_SET' and start[3]!='ORIGIN_NOT_SET' and head[4]!='not set'
                event={'printOrdinal':len(events)+1,'section':section,'sequence':sequence,'type':head[2],'status':head[3],
                       'tagPrinted':head[4],'durationMsPrinted':duration,'startWallTimePrinted':start[1],
                       'startElapsedMsPrinted':elapsed,'origin':start[3],'reason':reason[1],'phase':reason[2],
                       'lastProgressWallTimePrinted':reason[3],'userIdPrinted':int(user[1]),'displayIdPrinted':int(user[2]),
                       'clockStatus':'recorded' if clock_known else 'unknown',
                       'trackingFinishedElapsedMsFromDuration':elapsed+duration if section=='completed' and clock_known else None,
                       'offsets':None}
                if clock_known and raw is not None and raw.get('status')=='recorded':
                    first=raw['firstPositive']
                    event['offsets']={'eventStartMinusRawStartMs':elapsed-raw['processStartElapsedMs'],
                                      'eventStartMinusFixtureShowMs':elapsed-raw['fixtureShowElapsedMs'],
                                      'eventStartMinusFirstInsetsVisibleMs':elapsed-raw['firstInsetsVisibleElapsedMs'],
                                      'firstPositiveMinusEventStartMs':None if first is None else first['elapsedRealtimeMs']-elapsed,
                                      'firstPositiveMinusTrackingFinishMs':None if first is None or section!='completed' else first['elapsedRealtimeMs']-(elapsed+duration)}
                events.append(event)
        require(position==len(segment),'HISTORY_COUNT_MISMATCH_OR_FOREIGN_LINE')
        chronological=sorted((e for e in events if e['clockStatus']=='recorded'),key=lambda e:(e['startElapsedMsPrinted'],e['printOrdinal']))
        return {'status':'recorded','reason':None,'counts':counts,'events':events,
                'clockStatus':'recorded' if len(chronological)==len(events) else 'unknown_for_some_entries',
                'elapsedStartOrderSequenceNumbers':[e['sequence'] for e in chronological],
                'historyBodyIdentity':identity(('\n'.join(segment)+'\n').encode()),
                'startOrderContainsAllEntries':len(chronological)==len(events)}
    except (TimelineError,UnicodeError,ValueError,TypeError,KeyError) as error:
        return unknown(str(error) if isinstance(error,TimelineError) else 'HISTORY_FORMAT',events=None,counts=None,
                       elapsedStartOrderSequenceNumbers=None)


def history_from_command(cm, raw):
    command=cm.get('ime_state')
    if not complete(command):return unknown('IME_DUMP_COMMAND_NOT_COMPLETE',events=None,counts=None,elapsedStartOrderSequenceNumbers=None)
    if 'raw_probe' not in cm or cm['raw_probe']['endedAt'] is None or timestamp(command['startedAt'])<timestamp(cm['raw_probe']['endedAt']):
        return unknown('IME_DUMP_NOT_AFTER_RAW',events=None,counts=None,elapsedStartOrderSequenceNumbers=None)
    prefix=cm['raw_probe']['argv'][:3]
    if command['argv'] != prefix+['shell','/system/bin/timeout','-s','KILL','8s','/system/bin/dumpsys','-t','5','input_method','--user','0']:
        return unknown('IME_DUMP_ARGV',events=None,counts=None,elapsedStartOrderSequenceNumbers=None)
    value=parse_history(command_data(command),raw)
    value['originalCommand']={k:command[k] for k in ('id','bytes','sha256','startedAt','endedAt','elapsedMillis')}
    return value


def analyze(directory, repo, archive_commit, analysis_source_commit=None):
    """Read-only entry point. Unknown integrity yields no derived arm/event claims."""
    result={'schemaVersion':1,'kind':'window-infos-offline-timeline','status':'unknown','reason':'NOT_ANALYZED',
            'analysisProgram':identity(read_file(PROGRAM))|{'path':PROGRAM_RELATIVE},
            'analysisSourceCommit':None,'analysisProgramSourceVerified':False,
            'scope':dict(SCOPE),'evidence':None,'arms':None}
    try:
        repo=Path(repo).resolve(strict=True)
        revision=git(repo,'rev-parse','HEAD').decode().strip() if analysis_source_commit is None else analysis_source_commit
        commit(repo,revision);result['analysisSourceCommit']=revision
        require(git_blob(repo,revision,PROGRAM_RELATIVE)==read_file(PROGRAM),'ANALYSIS_PROGRAM_SOURCE_MISMATCH')
        result['analysisProgramSourceVerified']=True;commit(repo,archive_commit)
        blobs,values,source,evidence=read_archive(directory,repo,archive_commit)
        result['evidence']={'source':source,'archiveCommit':archive_commit,**evidence}
        commands(values['commands.json'],source,False)
        arms={}
        for arm in ARMS:
            cm=commands(values[arm+'/commands.json'],source,True)
            raw=raw_timeline(cm,values,arm,source);history=history_from_command(cm,raw)
            arms[arm]={'raw':raw,'imeTrackerHistory':history}
        result['arms']=arms
        known=all(part['status']=='recorded' for arm in arms.values() for part in arm.values())
        result['status']='recorded' if known else 'unknown'
        result['reason']=None if known else 'ONE_OR_MORE_SUBANALYSES_UNKNOWN'
    except (TimelineError,KeyError,TypeError,ValueError,OSError) as error:
        result['reason']=str(error) if isinstance(error,TimelineError) else 'INPUT_FORMAT_OR_IO'
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive',type=Path,required=True);parser.add_argument('--repo',type=Path,required=True)
    parser.add_argument('--archive-commit',required=True);parser.add_argument('--analysis-source-commit')
    parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    target=args.output.resolve();archive=args.archive.resolve()
    require(target!=archive and archive not in target.parents,'OUTPUT_INSIDE_ORIGINAL_ARCHIVE')
    require(not target.exists() and not args.output.is_symlink(),'OUTPUT_ALREADY_EXISTS')
    result=analyze(args.archive,args.repo,args.archive_commit,args.analysis_source_commit)
    encoded=(json.dumps(result,ensure_ascii=False,indent=2)+'\n').encode();require(len(encoded)<=MAX_FILE,'ANALYSIS_OUTPUT_BOUND')
    target.parent.mkdir(parents=True,exist_ok=True)
    with target.open('xb') as stream:stream.write(encoded)
    return 0 if result['status']=='recorded' else 1


if __name__=='__main__':raise SystemExit(main())
