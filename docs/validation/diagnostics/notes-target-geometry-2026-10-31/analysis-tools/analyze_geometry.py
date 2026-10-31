#!/usr/bin/env python3
"""Read-only analysis of original, source-bound Notes target geometry failures."""
import argparse, hashlib, importlib.util, json, re
from pathlib import Path

BASE_PATH=Path(__file__).with_name('analyze_notes_confirmation.py')
spec=importlib.util.spec_from_file_location('original_notes_confirmation',BASE_PATH)
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
MARKER='NOTES_TARGET_GEOMETRY_DIAGNOSTIC_V1 '
MAX_CHARS=4096
ACTIONS={'NONE','CLICK_NEW_NOTE','CLICK_TEXT_TYPE','CLICK_CONFIRM','CLICK_OTHER','SET_TITLE','SET_BODY','KEY_HOME','KEY_BACK','OTHER_ACTION'}
RESULTS={'NOT_ATTEMPTED','SUBMISSION_UNKNOWN','ACCEPTED','REJECTED','OTHER_RESULT'}
STAGES={'UNSET','LAUNCH','INITIAL_EDITOR','INITIAL_CAPTURE','OPEN_CREATE_DIALOG','SET_TITLE','SELECT_TEXT_TYPE','CONFIRM_CREATE','CREATED_EDITOR','CREATED_TITLE','SET_BODY','SAVE_BACKGROUND','REOPEN','REOPENED_EDITOR','VERIFY_PERSISTED','RESULT_CAPTURE'}
RESOURCES={'NOT_SELECTED','NEW_NOTE','TITLE_FIELD','TEXT_TYPE','CONFIRM_BUTTON','BODY_FIELD','EXACT_TITLE','OTHER_RESOURCE'}
SNAPSHOT_KEYS={'schemaVersion','snapshotKind','operationStage','resourceClass','readPhase','appWindowId','appBounds','displayId','displayBounds','imeWindowId','imeBounds','matchedCount','visibleMatchCount','matchScanComplete','targetPresent','targetBounds','targetVisible','targetEnabled','targetWindowId'}
TOP_KEYS={'schemaVersion','guard','snapshot','lastAction','lastActionResult','actionCounterScope','actionAttempts','actionAttemptCountCap','actionAttemptCountAtLimit','homeAttempts','diagnosticTruncated'}


def extract_marker(output, marker, max_chars):
    """Bind diagnostics only inside their complete status block or explicit summary stack."""
    base.require(type(output)is str and len(output.encode('utf-8'))<=base.MAX_PAYLOAD,'invalid instrumentation output')
    occurrences=[]; block=[]; mode='free'; offset=0
    def flush(kind, complete=False):
        nonlocal block
        if not block:return
        methods=set(); classes=set(); explicit=set(); bad=[]
        for _,_,line in block:
            m=re.match(r'INSTRUMENTATION_STATUS: class=(\S+)\s*$',line)
            if m:classes.add(m.group(1))
            m=re.match(r'INSTRUMENTATION_STATUS: test=(\w+)\s*$',line)
            if m:methods.add(m.group(1))
            m=re.search(r'(?:Error in |^\d+\) )(\w+)\('+re.escape(base.CLASS)+r'\)',line)
            if m:explicit.add(m.group(1))
            m=re.search(r'\bat '+re.escape(base.CLASS)+r'\.(\w+)\(',line)
            if m and m.group(1)in base.METHODS:explicit.add(m.group(1))
        known=methods|explicit
        if methods-set(base.METHODS):bad.append('unknown test method in this block')
        if classes and classes!={base.CLASS}:bad.append('class field differs within this block')
        if kind=='status' and not complete:bad.append('unterminated instrumentation status block')
        if len(known)!=1:bad.append('no unique explicit method in this block')
        if not explicit and classes!={base.CLASS}:bad.append('test field lacks matching class or explicit current stack')
        method=next(iter(known))if len(known)==1 and not bad else None
        if method not in base.METHODS:method=None
        for line_number,line_offset,line in block:
            position=line.find(marker)
            if position<0:continue
            base.require(line.count(marker)==1,'ambiguous diagnostic marker line')
            payload=line[position+len(marker):].rstrip('\r\n')
            base.require(len(payload)+len(marker)<=max_chars,'oversized diagnostic message')
            value,consumed=base.DECODER.raw_decode(payload)
            base.require(not payload[consumed:].strip()and type(value)is dict,'unexpected diagnostic payload')
            raw=payload[:consumed]
            occurrences.append({'method':method,'bindingStatus':'bound'if method is not None else'unassigned',
                'bindingScope':kind,'bindingIssues':list(bad),'line':line_number,'characterOffset':line_offset+position,
                'rawJson':raw,'rawJsonSha256':base.digest(raw.encode('utf-8')),'value':value})
        block=[]
    for number,line in enumerate(output.splitlines(keepends=True),1):
        if line.startswith('INSTRUMENTATION_STATUS_CODE:'):
            flush(mode,True);mode='free'
        elif line.startswith('INSTRUMENTATION_RESULT:') or line.startswith('INSTRUMENTATION_CODE:'):
            flush(mode,False);mode='summary'
            block.append((number,offset,line))
        elif line.startswith('INSTRUMENTATION_STATUS:'):
            if mode!='status':flush(mode,False);mode='status'
            # Repeated class marks a new/incomplete block; never reuse an old case.
            if line.startswith('INSTRUMENTATION_STATUS: class=') and any(x[2].startswith('INSTRUMENTATION_STATUS: class=')for x in block):
                flush(mode,False)
            block.append((number,offset,line))
        else:
            if mode!='status' and re.search(r'(?:Error in |^\d+\) )(\w+)\('+re.escape(base.CLASS)+r'\)',line):
                flush(mode,False);mode='summary'
            block.append((number,offset,line))
        offset+=len(line)
    flush(mode,False)
    return occurrences


def extract(output):return extract_marker(output,MARKER,MAX_CHARS)


def int32(x):return type(x)is int and -(2**31)<=x<2**31

def rectangle(x):return type(x)is list and len(x)==4 and all(int32(i)for i in x)

def empty(r):return r[0]>=r[2] or r[1]>=r[3]

def derive(value):
    issues=[]
    if value.get('diagnosticSerializationFailed') is True:
        return {'availability':'serialization_failed','consistencyIssues':[]if set(value)=={'diagnosticSerializationFailed'}else['serialization fallback has unexpected fields']}
    if value.get('diagnosticTruncated') is True:
        n=value.get('untruncatedChars');valid=set(value)=={'diagnosticTruncated','untruncatedChars'}and type(n)is int and n>MAX_CHARS
        return {'availability':'truncated','untruncatedChars':n,'consistencyIssues':[]if valid else['truncation fallback has unexpected fields or length']}
    def check(condition,message):
        if not condition:issues.append(message)
    check(set(value)==TOP_KEYS,'unexpected top-level geometry diagnostic fields')
    check(type(value.get('schemaVersion'))is int and value.get('schemaVersion')==1 and value.get('guard')=='TARGET_FULLY_INSIDE' and value.get('diagnosticTruncated')is False,'geometry schema/guard/truncation differs')
    s=value.get('snapshot')
    if type(s)is not dict:return {'availability':'invalid_snapshot','consistencyIssues':issues+['snapshot is not an object']}
    check(set(s)==SNAPSHOT_KEYS,'unexpected snapshot fields')
    check(type(s.get('schemaVersion'))is int and s.get('schemaVersion')==1 and s.get('snapshotKind')=='LAST_OBSERVATION','snapshot schema/kind differs')
    check(s.get('readPhase')=='TARGET_CHECK','geometry marker did not retain TARGET_CHECK phase')
    check(s.get('operationStage')in STAGES and s.get('resourceClass')in RESOURCES,'stage/resource enum differs')
    for k in ['appWindowId','displayId','imeWindowId','matchedCount','visibleMatchCount','targetWindowId']:
        check(s.get(k)is None or int32(s.get(k)),k+' is not null/int32')
    for k in ['targetPresent','targetVisible','targetEnabled']:
        check(s.get(k)is None or type(s.get(k))is bool,k+' is not null/boolean')
    check(type(s.get('matchScanComplete'))is bool,'matchScanComplete is not boolean')
    for k in ['appBounds','displayBounds','imeBounds','targetBounds']:
        check(s.get(k)is None or rectangle(s.get(k)),k+' is not null/4 signed ints')
    check(value.get('lastAction')in ACTIONS,'unknown action enum')
    check(value.get('lastActionResult')in RESULTS,'unknown action result enum')
    check(value.get('actionCounterScope')=='CURRENT_HARNESS','action count scope differs')
    n=value.get('actionAttempts')
    check(type(n)is int and 0<=n<=64,'action attempts outside original saturation range')
    check(type(value.get('actionAttemptCountCap'))is int and value.get('actionAttemptCountCap')==64,'action saturation cap differs')
    check(type(value.get('actionAttemptCountAtLimit'))is bool and value.get('actionAttemptCountAtLimit')==(type(n)is int and n>=64),'action limit flag differs')
    check(type(value.get('homeAttempts'))is int and value['homeAttempts']>=0,'home attempts invalid')
    a,t=s.get('appBounds'),s.get('targetBounds')
    geometry={'available':rectangle(a) and rectangle(t)}
    if geometry['available']:
        ae,te=empty(a),empty(t)
        contains=not ae and a[0]<=t[0] and a[1]<=t[1] and a[2]>=t[2] and a[3]>=t[3]
        fully_inside=not ae and not te and contains
        geometry.update(appEmpty=ae,targetEmpty=te,rectContains=contains,fullyInside=fully_inside,
            edgesOutside=[] if ae or te else [k for k,v in [('left',t[0]<a[0]),('top',t[1]<a[1]),('right',t[2]>a[2]),('bottom',t[3]>a[3])]if v])
        check(not fully_inside,'original complete-containing assertion contradicts retained rectangles')
    else:issues.append('original app/target rectangle unavailable; no geometry reconstructed')
    return {'availability':'complete','snapshot':s,'memoryActionMetadata':{k:value.get(k)for k in ['lastAction','lastActionResult','actionCounterScope','actionAttempts','actionAttemptCountCap','actionAttemptCountAtLimit','homeAttempts']},'recomputedGeometry':geometry,'consistencyIssues':issues}


def analyze(archive,source,run_id,confirmation_output=None):
    old_extract=base.extract
    try:
        base.extract=lambda output:[o for o in extract_marker(output,base.MARKER,base.MAX_DIAGNOSTIC)if o['bindingStatus']=='bound']
        original=base.analyze(archive,source,run_id,1)
    finally:base.extract=old_extract
    original['bindingPolicy']='complete status block or explicit current summary/stack; unassigned retained separately'
    original['bindingAuditScriptSha256']=base.digest(Path(__file__).read_bytes())
    original['bindingAudits']=[]
    lanes=[];by_case={}
    for label in base.LABELS:
        path=archive/('api-'+label)/'instrumentation.json'
        if not path.exists():
            lanes.append({'api':label,'instrumentationAvailable':False,'occurrences':[]});continue
        instrument,raw=base.read_json(path);occurrences=extract(instrument['output'])
        confirmations=extract_marker(instrument['output'],base.MARKER,base.MAX_DIAGNOSTIC)
        original['bindingAudits'].append({'api':label,'boundOccurrences':sum(x['bindingStatus']=='bound'for x in confirmations),'unassignedOccurrences':[x for x in confirmations if x['bindingStatus']!='bound']})
        lanes.append({'api':label,'instrumentationAvailable':True,'instrumentationSha256':base.digest(raw),'instrumentationBytes':len(raw),'occurrences':occurrences})
        for occurrence in occurrences:
            if occurrence['bindingStatus']!='bound':continue
            app,state=base.METHODS[occurrence['method']]
            by_case.setdefault((label,app,state),[]).append(occurrence)
    cases=[]
    for old in original['cases']:
        key=old['api'],old['app'],old['state'];occurrences=by_case.get(key,[])
        distinct={o['rawJsonSha256']:o['value']for o in occurrences}
        item={k:old[k]for k in ['api','app','state','originalStatus','originalStage']}
        item.update(geometryDiagnosticOccurrences=len(occurrences),distinctGeometryDiagnosticCount=len(distinct))
        if old['app']=='notes-1.7.0':item['originalConfirmationPreparation']=old.get('originalConfirmationPreparation')
        if len(distinct)==1:
            item['analysis']=derive(next(iter(distinct.values())))
            if old['originalStatus']!='failed':item['analysis']['consistencyIssues'].append('geometry failure diagnostic attached to original nonfailed case')
            if old['app']!='notes-1.7.0':item['analysis']['consistencyIssues'].append('Notes geometry diagnostic attached to Calculator method')
        else:item['analysis']={'availability':'absent'if not distinct else'ambiguous','consistencyIssues':[]if not distinct else['multiple distinct geometry diagnostics for one original case; no preferred value chosen']}
        cases.append(item)
    if confirmation_output is not None:
        confirmation_output.write_text(json.dumps(original,ensure_ascii=False,indent=2)+'\n')
    return {'schemaVersion':1,'kind':'original-notes-target-geometry-diagnostic-analysis','sourceRun':original['sourceRun'],'manifestSha256':original['manifestSha256'],'originalStateCounts':original['originalStateCounts'],'plannedCombinations':32,'cases':cases,'lanes':lanes,'unassignedGeometryOccurrences':sum(x['bindingStatus']!='bound'for lane in lanes for x in lane['occurrences']),'unassignedConfirmationOccurrences':sum(len(x['unassignedOccurrences'])for x in original['bindingAudits']),'analysisScriptSha256':base.digest(Path(__file__).read_bytes()),'confirmationAnalysisScriptSha256':base.digest(BASE_PATH.read_bytes()),'limitations':['Only original instrumentation data are used; post-failure captures do not fill original rectangles.','Original JUnit occurrences and raw JSON hashes remain separate; duplicate prints do not create additional failures.','Retained rectangles come from original ordered reads, not an atomic platform snapshot.','Geometry guard failure does not by itself identify animation, stale geometry, invalid target, platform bug or regression.','Action counters apply to CURRENT_HARNESS and saturate at 64; last action is not an exhaustive per-operation history.','Whether a particular action had been submitted requires its original stack/source and action metadata; no general before-write assumption is made.','Absence of this failure-only marker is not a reproduction or a fix of a historical geometry failure.','No original result, sampling budget, geometry predicate or denominator is changed.']}


def main():
    p=argparse.ArgumentParser();p.add_argument('--archive',type=Path,required=True);p.add_argument('--source',required=True);p.add_argument('--run-id',type=int,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--confirmation-output',type=Path)
    a=p.parse_args();r=analyze(a.archive,a.source,a.run_id,a.confirmation_output);a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'counts':r['originalStateCounts'],'geometryCases':sum(c['distinctGeometryDiagnosticCount']==1 for c in r['cases']),'geometryOccurrences':sum(c['geometryDiagnosticOccurrences']for c in r['cases']),'caseConsistencyIssues':sum(len(c['analysis']['consistencyIssues'])for c in r['cases']),'unassignedGeometryOccurrences':r['unassignedGeometryOccurrences'],'unassignedConfirmationOccurrences':r['unassignedConfirmationOccurrences']}))

if __name__=='__main__':main()
