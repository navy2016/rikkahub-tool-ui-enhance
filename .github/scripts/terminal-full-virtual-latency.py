"""Frozen-source full-virtual baseline evidence, with explicit non-phone/non-P99 boundaries."""
import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess

ROOT=Path(__file__).resolve().parents[2]
BASE='55db111129c662067345aa99540b506de305da37'
TREE='8caf50d5c40e228f1d5bcc09d3db505757568b68'
CLASS='me.rerere.rikkahub.pipeline.TerminalFullVirtualLatencyTest'
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalFullVirtualLatencyTest.kt'
METHODS=('baselineOrdinary1000','baselineOrdinary10000','baselineTui1000','baselineTui10000')
CASES=('ordinary-1000','ordinary-10000','tui-1000','tui-10000')
PHASES=('echo','cursor','row','burst','imeEcho','remountEcho')
OUT=ROOT/'artifacts/terminal-pipeline'
spec=importlib.util.spec_from_file_location('latency_history',Path(__file__).with_name('terminal-tui-history-reports.py'))
legacy=importlib.util.module_from_spec(spec);spec.loader.exec_module(legacy)
require=legacy.require

def guard():
    def git(*a):return subprocess.check_output(['git','-c','core.fileMode=false',*a],cwd=ROOT,timeout=30).decode().strip()
    require(git('rev-parse','HEAD:app/src/main')==TREE,'Baseline production tree changed')
    require(not git('diff','--name-only',BASE,'--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml'),
            'Baseline changed production/dependency/build settings')
    return dict(productionSha=BASE,productionTree=TREE,baselineOnly=True,phoneMeasured=False,oldPerformanceResampled=False)

def records(text,marker):return [json.loads(line.split(marker+' ',1)[1]) for line in text.splitlines() if marker+' ' in line]
def output_for(case,phase,i,rows):
    marker=f'{case}-{phase}-{i:02d}'
    if phase=='cursor':return f'\x1b[{rows};{3+i%30}H'
    if phase=='row':return '\r\x1b[2KROW:'+marker
    if phase=='burst':return ''.join(f'B:BURST:{marker}:{j:02d} 中文\r\n' for j in range(32))
    return 'ECHO:'+marker+'\r\n'
def finite(value):return type(value) in (int,float) and math.isfinite(value) and value>=0

def validate(text):
    cases=records(text,'FV_LATENCY_CASE');samples=records(text,'FV_LATENCY_SAMPLE');frames=records(text,'FV_LATENCY_FRAME')
    mounts=records(text,'FV_LATENCY_MOUNT');memory=records(text,'FV_LATENCY_MEMORY');cleanups=records(text,'FV_LATENCY_CLEANUP')
    probes=records(text,'FV_LATENCY_LAYOUT_PROBE');grid=records(text,'FV_LATENCY_GRID')
    require(len(cases)==4 and {r.get('case') for r in cases}==set(CASES),'Missing baseline cases')
    ids={r['case']:r['launch'] for r in cases}
    require(len(set(ids.values()))==4 and all(re.fullmatch('[a-f0-9-]{36}',v) for v in ids.values()),'Mixed launch identities')
    require(all(r.get('passed') is True and r.get('samples')==132 and r.get('frameBufferOverflow')==0 and r.get('frames',0)>0 for r in cases),'Incomplete baseline case')
    require(len(mounts)==8 and {(r.get('case'),r.get('seedIncluded')) for r in mounts}=={(c,v) for c in CASES for v in (True,False)},'Missing mount observations')
    grid_rows={r['case']:r['rows'] for r in mounts if r['seedIncluded']}
    for r in mounts:
        require(r.get('launch')==ids[r['case']] and r.get('columns')==80 and r.get('rows')==grid_rows[r['case']] and finite(r.get('elapsedNs')),
                'Mount/native grid changed')
    expected={(c,p,i) for c in CASES for p in PHASES for i in range(22)};seen=set()
    for r in samples:
        key=(r.get('case'),r.get('phase'),r.get('iteration'))
        require(key in expected and key not in seen,'Duplicate/unknown baseline sample');seen.add(key)
        c,p,i=key;payload=output_for(c,p,i,grid_rows[c]).encode()
        require(r.get('launch')==ids[c] and r.get('warmup') is (i<2) and r.get('virtual') is True and r.get('tui') is c.startswith('tui-') and
                r.get('historyRows')==int(c.rsplit('-',1)[1]) and r.get('ime') is (p=='imeEcho'),'Wrong baseline scene/backend')
        require(r.get('outputBytes')==len(payload) and r.get('outputSha256')==hashlib.sha256(payload).hexdigest(),'Finite peer bytes differ')
        require(all(finite(r.get(k)) for k in ('inputToDrawNs','inputToCheckNs','startedNs','uiNs','drawNs','checkedNs')) and
                r['startedNs']<=r['uiNs']<=r['drawNs']<=r['checkedNs'] and r['inputToDrawNs']==r['drawNs']-r['uiNs'] and
                r['inputToCheckNs']==r['checkedNs']-r['uiNs'],'Invalid timing')
        require(r['beforeRevision']<=r['fedRevision']<=r['publishedRevision']<=r['drawnRevision'] and
                6<=r.get('retainedRows',0)<=80 and 1<=r.get('measuredRows',0)<=128 and r.get('writers')==0,'Unsettled/nonvirtual sample')
        require(all((type(v) in (int,float) and math.isfinite(v)) if k=='feedToPublishNs' else finite(v) for k,v in r.get('spans',{}).items()) and set(r['spans'])=={'readToLockNs','captureNs','rowSyncNs','parseWindowNs',
                'uiToNativeReadNs','feedToPublishNs','publishToDrawNs'},'Missing stage spans')
        require(0<=r['changedTexts']<=r['commonLayouts'] and 0<=r['paragraphReplacedLayouts']<=r['commonLayouts'] and
                0<=r['replacedLayouts']<=r['commonLayouts']<=min(r['visibleBefore'],r['visibleAfter']),
                'Invalid visible layout identity inventory')
        es=r.get('events',[])
        require(es and all(a['sequence']<b['sequence'] and a['timeNs']<=b['timeNs'] for a,b in zip(es,es[1:])),'Invalid trace ordering')
        require(sum(e['stage']=='UI_INPUT' for e in es)==1 and sum(e['bytes'] for e in es if e['stage']=='EMULATOR_FED')==len(payload) and
                sum(e['bytes'] for e in es if e['stage']=='OUTPUT_READ')==len(payload),'Input/output provenance incomplete')
        require(any(e['stage']=='FRAME_DRAWN' and e['timeNs']==r['drawNs'] and e['revision']==r['drawnRevision'] and e['virtual'] is True for e in es),
                'No matching actual draw')
    require(seen==expected,'Baseline must retain all528samples including48warmups')
    require(len(memory)==28 and {(r.get('case'),r.get('phase')) for r in memory}=={(c,p) for c in CASES for p in ('ready',*PHASES)},'Missing memory windows')
    require(len(probes)==24 and {(r.get('case'),r.get('phase')) for r in probes}=={(c,p) for c in CASES for p in PHASES} and
        all(r.get('outputInjected') is False and 0<=r['resultReplaced']<=r['common'] and 0<=r['paragraphReplaced']<=r['common'] for r in probes),
        'Missing no-output calibration; result identity alone is not a re-layout count')
    require({(r.get('case'),r.get('phase')) for r in grid}=={(c,p) for c in CASES for p in ('seed','ime','ime-hidden','remount')},'Missing actual grid observations')
    for c in cases:
        fs=[r for r in frames if r['case']==c['case']]
        require(len(fs)==c['frames'] and [r['index'] for r in fs]==list(range(len(fs))) and
                all(r['launch']==c['launch'] and r['totalNs']>=0 and r['dropped']>=0 for r in fs) and
                sum(r['dropped'] for r in fs)==c['frameReportsDropped'],'FrameMetrics records missing or relabelled')
    require(len(cleanups)==4 and {r['case'] for r in cleanups}==set(CASES) and all(r.get('closed') is True and r.get('frameThreadStopped') is True
            and r.get('launch')==ids[r['case']] for r in cleanups),'Cleanup incomplete')
    return dict(cases=cases,samples=samples,frames=frames,mounts=mounts,memory=memory,cleanups=cleanups,layoutProbeCalibration=probes,gridObservations=grid)

def summary(data):
    values=[]
    for case in CASES:
        for phase in PHASES:
            rows=[r for r in data['samples'] if r['case']==case and r['phase']==phase and not r['warmup']]
            d=dict(case=case,phase=phase,n=len(rows),p99NotEstimated=True)
            for key in ('inputToDrawNs','inputToCheckNs','panelCompositions','replacedLayouts','paragraphReplacedLayouts','changedTexts'):
                ordered=sorted(r[key] for r in rows)
                d[key]=dict(median=statistics.median(ordered),p95=ordered[math.ceil(len(ordered)*.95)-1],maximum=max(ordered))
            d['spans']={k:statistics.median(r['spans'][k] for r in rows) for k in rows[0]['spans']}
            values.append(d)
    return values

def protobuf(raw):
    """Minimal bounded wire reader; field mapping is pinned to the included Perfetto v40 schema."""
    at=0
    def varint():
        nonlocal at
        value=0
        for shift in range(0,70,7):
            require(at<len(raw),'Truncated protobuf varint')
            b=raw[at];at+=1;value|=(b&127)<<shift
            if not b&128:return value
        raise ValueError('Oversized protobuf varint')
    while at<len(raw):
        tag=varint();field,wire=tag>>3,tag&7;require(field>0,'Invalid protobuf tag')
        if wire==0:value=varint()
        elif wire in (1,2,5):
            size=varint() if wire==2 else 8 if wire==1 else 4
            require(size<=len(raw)-at,'Truncated protobuf field');value=raw[at:at+size];at+=size
        else:raise ValueError('Unexpected protobuf group/wire type')
        yield field,wire,value

def frame_timeline(raw,pid):
    starts={};ends={};rows=[];packet_count=0
    for field,wire,packet in protobuf(raw):
        if field!=1 or wire!=2:continue
        packet_count+=1;parts=list(protobuf(packet));scalars={f:v for f,w,v in parts if w==0}
        for f,w,event in parts:
            if f!=76 or w!=2:continue
            for kind,encoding,message in protobuf(event):
                if encoding!=2 or kind not in (4,5):continue
                values={k:v for k,typ,v in protobuf(message) if typ==0}
                if 1 not in values:continue
                key=(scalars.get(10,0),values[1])
                if kind==4 and values.get(4)==pid:
                    require(key not in starts,'Duplicate actual SurfaceFrame cookie')
                    starts[key]=dict(startNs=scalars.get(8),clock=scalars.get(58,6),token=values.get(2),
                        displayToken=values.get(3),presentType=values.get(6),jankType=values.get(9),onTimeFinish=values.get(7),isBuffer=values.get(11))
                elif kind==5:ends[key]=scalars.get(8)
    for key,value in starts.items():
        end=ends.get(key);start=value['startNs']
        if type(start) is int and type(end) is int and end>=start:rows.append(dict(value,endNs=end,durationNs=end-start))
    return dict(pid=pid,tracePackets=packet_count,actualSurfaceStarts=len(starts),completeFrames=len(rows),unmatchedStarts=len(starts)-len(rows),
        status='decoded-target-SurfaceFrame' if rows else 'no-decodable-target-SurfaceFrame',
        presentTypes={str(k):sum(r['presentType']==k for r in rows) for k in sorted({r['presentType'] for r in rows if r['presentType'] is not None})},
        frames=rows,note='Whole target window OS SurfaceFrames,not terminal-only FPS or input-to-present causality. Missing/unsupported frames remain explicit.')

def report(text,manifest,device,installed):
    evidence=validate(text);scope=guard()
    traces=[]
    for method,case in zip(METHODS,CASES):
        status_path=OUT/'latency-traces'/(method+'-trace-status.json')
        status=json.loads(status_path.read_bytes())
        if status['status']!='captured':
            traces.append(dict(case=case,status=status['status'],rawTraceComplete=False,diagnostic=status,
                note='System trace unavailable; no SurfaceFlinger presentation claim. Window FrameMetrics is independent.'))
            continue
        path=OUT/'latency-traces'/(method+'.pftrace');raw=path.read_bytes()
        require(0<len(raw)<67108864,'Trace missing or size cap reached')
        pid=next(r['pid'] for r in evidence['cases'] if r['case']==case)
        decoded=frame_timeline(raw,pid);details=OUT/'latency-traces'/(method+'-frames.json')
        details.write_text(json.dumps(decoded,separators=(',',':')))
        traces.append(dict(case=case,bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest(),
            **{k:v for k,v in decoded.items() if k!='frames'},rawTraceComplete=True,decodedSha256=hashlib.sha256(details.read_bytes()).hexdigest()))
    result=dict(suite='terminal-full-virtual-latency-v2',sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],
        sourceGuard=scope,manifest=manifest,device=device,installedApkHashes=installed,**evidence,summary=summary(evidence),
        surfaceFrameEvidence=traces,
        note='Single frozen-source baseline;test-driven nativePTY,not phone FPS,no speedup claim,n=20/workload,no trusted P99. '
             'replacedLayouts counts returned-object identity changes,NOT proven paragraph shaping; see no-output calibration.')
    legacy.publish('Full virtual latency baseline',result,OUT/'full-virtual-latency.json',emit_compact=False)
    compact={k:v for k,v in result.items() if k not in ('manifest','samples','frames','memory')}
    compact['fullReportSha256']=hashlib.sha256((OUT/'full-virtual-latency.json').read_bytes()).hexdigest()
    (OUT/'full-virtual-latency-summary.json').write_text(json.dumps(compact,separators=(',',':')))
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','evidence'));p.add_argument('--page',type=int,choices=(1,2),default=1);a=p.parse_args()
    try:
        if a.action=='guard':print(json.dumps(guard()))
        else:legacy.compact('Full virtual latency baseline',(OUT/'full-virtual-latency-summary.json').read_bytes(),a.page)
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        print('::error title=Full virtual baseline rejected::'+str(e)[:2200].replace('%','%25').replace('\n','%0A'));raise SystemExit(1)
