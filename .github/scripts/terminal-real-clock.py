"""Real-clock native-page diagnosis with unique window/vsync/token matching; no guessed presentation."""
import argparse,base64,hashlib,importlib.util,json,os,re,statistics,subprocess,zlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/terminal-pipeline'
TREE='8caf50d5c40e228f1d5bcc09d3db505757568b68'
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalRealClockDiagnosticTest.kt'
CLASS='me.rerere.rikkahub.pipeline.TerminalRealClockDiagnosticTest'
METHODS=('realClockDefaultOrdinary','realClockFullOrdinary','realClockDefaultTui','realClockFullTui')
CASES=('chunkedLayers-ordinary','fullVirtual-ordinary','chunkedLayers-tui','fullVirtual-tui')
PHASES=('hidden','shown','hiddenAgain')
spec=importlib.util.spec_from_file_location('real_clock_wire',Path(__file__).with_name('terminal-full-virtual-latency.py'))
wire=importlib.util.module_from_spec(spec);spec.loader.exec_module(wire)
require=wire.require
pb=wire.protobuf
def guard():
    tree=subprocess.check_output(['git','-c','core.fileMode=false','rev-parse','HEAD:app/src/main'],cwd=ROOT,text=True).strip()
    require(tree==TREE,'Real-clock diagnosis changed production')
    source=(ROOT/TEST).read_text()
    require(all(s not in source for s in ('createAndroidComposeRule','mainClock','waitForIdle','performSemanticsAction')),'Test clock/idling helper leaked into driver')
    return dict(productionSha='55db111129c662067345aa99540b506de305da37',productionTree=TREE,productionChanged=False)
def records(text,marker):return [json.loads(l.split(marker+' ',1)[1]) for l in text.splitlines() if marker+' ' in l]
def case_records(text):
    groups={}
    for row in records(text,'REAL_CLOCK_PART'):
        require(row['case'] in CASES and type(row['part']) is int and 1<=row['part']<=row['parts']<=256,'Invalid case fragment')
        state=groups.setdefault(row['case'],dict(parts=row['parts'],sha256=row['sha256'],data={}))
        require(state['parts']==row['parts'] and state['sha256']==row['sha256'] and row['part'] not in state['data'],'Mixed or repeated fragments')
        state['data'][row['part']]=row['data']
    require(set(groups)==set(CASES),'Missing original case fragments')
    result=[]
    for name in CASES:
        entry=groups[name];require(set(entry['data'])==set(range(1,entry['parts']+1)),'Incomplete case fragments')
        raw=zlib.decompress(base64.b64decode(''.join(entry['data'][i] for i in sorted(entry['data'])),validate=True))
        require(len(raw)<=8*1024*1024 and hashlib.sha256(raw).hexdigest()==entry['sha256'],'Case content hash/size differs')
        value=json.loads(raw);require(value['case']==name,'Mislabeled case payload');result.append(value)
    return result
def validate(text):
    cases=case_records(text);cleanup=records(text,'REAL_CLOCK_CLEANUP')
    require(len(cases)==4 and {r.get('case') for r in cases}==set(CASES),'Missing four real page cases')
    require(len(cleanup)==4 and {r['case'] for r in cleanup}==set(CASES) and all(r['closed'] and r['threadStopped'] for r in cleanup),'Cleanup incomplete')
    for c in cases:
        require(c['clock']=='real-android-no-compose-test-clock' and c['passed'] is True and c['pid']>0,'Driver identity missing')
        require(c['mode'] in ('chunkedLayers','fullVirtual') and c['case']==c['mode']+'-'+('tui' if c['tui'] else 'ordinary'),'Wrong case label')
        ss=c['samples'];require(len(ss)==36 and [(s['phase'],s['index']) for s in ss]==[(p,i) for p in PHASES for i in range(12)],'Workload order/inventory changed')
        require([g['phase'] for g in c['gates']]==list(PHASES) and len({g['rows'] for g in c['gates']})==1 and all(g['columns']==80 for g in c['gates']),'Native grid drift')
        for s in ss:
            raw=f'ECHO:{c["case"]}:{s["phase"]}:{s["index"]:02d}\r\n'.encode()
            require(s['warmup'] is (s['index']<2) and s['virtual'] is (c['mode']=='fullVirtual'),'Warmup/backend changed')
            require(s['beganNs']<=s['uiNs']<=s['drawNs']<=s['observedNs'] and s['fedRevision']<=s['publishedRevision']<=s['drawRevision'],'Invalid event/revision order')
            require(s['outputBytes']==len(raw) and s['outputSha256']==hashlib.sha256(raw).hexdigest(),'Echo content differs')
            require(s.get('visibleAfterMeasurement') is True and s.get('tracePolls',0)>0,'Missing postmeasurement visibility/poll provenance')
            es=s['events'];require(all(a['seq']<b['seq'] and a['ns']<=b['ns'] for a,b in zip(es,es[1:])),'Trace order differs')
            require(sum(e['bytes'] for e in es if e['stage']=='OUTPUT_READ')==len(raw) and sum(e['bytes'] for e in es if e['stage']=='EMULATOR_FED')==len(raw),'Finite bytes incomplete')
            require(sum(e['stage']=='UI_INPUT' for e in es)==1 and any(e['stage']=='FRAME_DRAWN' and e['ns']==s['drawNs'] and e['revision']==s['drawRevision'] for e in es),'Missing actual draw')
        require(c['metrics'] and all(len(r)==10 and r[3]>=0 and r[9]>=0 for r in c['metrics']) and sum(r[9] for r in c['metrics'])==c['droppedFrameMetrics'],'FrameMetrics data missing')
        require([r['phase'] for r in c['phaseDumps']]==list(PHASES),'Missing frame ring snapshots')
    return dict(cases=cases,cleanups=cleanup)
def gfx_frames(text):
    head=None;window=None;result=[]
    for line in text.splitlines():
        line=line.strip()
        if line.startswith('Window:'):window=line
        if line.startswith('Flags,'):head=line.rstrip(',').split(',');continue
        if not head or not re.fullmatch(r'[0-9, -]+',line):continue
        row=line.rstrip(',').split(',')
        if len(row)!=len(head):continue
        try:value={k:int(v) for k,v in zip(head,row)}
        except ValueError:continue
        if 'FrameTimelineVsyncId' in value:value['window']=window;result.append(value)
    return result
def fields(raw):return {k:v for k,w,v in pb(raw)}
def trace_data(raw,pid):
    starts={};ends={};switches=[];prints=[];lost=0;clocks=set();compact=0
    for field,wt,packet in pb(raw):
        if field!=1 or wt!=2:continue
        pieces=list(pb(packet));sc={f:v for f,w,v in pieces if w==0};sequence=sc.get(10,0)
        for field,typ,value in pieces:
            if field==76 and typ==2:
                for kind,w,event in pb(value):
                    if w!=2 or kind not in (2,4,5):continue
                    e=fields(event);key=(sequence,e.get(1))
                    if kind==5:ends[key]=sc.get(8)
                    elif kind==2 or e.get(4)==pid:
                        starts[key]=dict(kind=kind,ns=sc.get(8),clock=sc.get(58,6),token=e.get(2),displayToken=e.get(3) if kind==4 else e.get(2),
                            pid=e.get(4) if kind==4 else e.get(3),present=e.get(6) if kind==4 else e.get(4),layer=e.get(5,b'').decode(errors='replace') if kind==4 else '')
            if field!=1 or typ!=2:continue
            bundle=list(pb(value));bf={f:v for f,w,v in bundle if w==0};clocks.add(bf.get(5,0));lost+=bool(bf.get(3,0));cpu=bf.get(1,-1)
            compact+=sum(f==4 and w==2 for f,w,v in bundle)
            for f,w,event in bundle:
                if f!=2 or w!=2:continue
                ef=fields(event);ts=ef.get(1);tid=ef.get(2)
                if 4 in ef:
                    sw=fields(ef[4]);switches.append((cpu,ts,sw.get(2),sw.get(6),sw.get(4),sw.get(5,b'').decode(errors='replace')))
                if 3 in ef:
                    e=fields(ef[3]);buf=e.get(2,b'').decode(errors='replace')
                    if tid==pid:prints.append((ts,buf))
    surfaces=[];displays=[];unmatched=0
    for key,value in starts.items():
        end=ends.get(key)
        if not isinstance(end,int) or not isinstance(value['ns'],int) or end<value['ns']:unmatched+=1;continue
        value=dict(value,endNs=end);(surfaces if value['kind']==4 else displays).append(value)
    return dict(surfaces=surfaces,displays=displays,switches=switches,prints=prints,lostFtraceBundles=lost,ftraceClocks=sorted(clocks),compactSchedBundles=compact,unmatchedTimelineStarts=unmatched)
def correlate(case,trace):
    phases={r['phase']:r for r in case['phaseDumps']};rows=[];intervals=[]
    for cpu in {r[0] for r in trace['switches']}:
        es=sorted((r for r in trace['switches'] if r[0]==cpu),key=lambda r:r[1])
        for a,b in zip(es,es[1:]):
            if a[3]==case['pid'] and b[2]==case['pid']:intervals.append((a[1],b[1]))
    for s in case['samples']:
        out=dict(phase=s['phase'],index=s['index'],warmup=s['warmup'],inputToDrawNs=s['drawNs']-s['uiNs'],publishToDrawNs=s['drawNs']-s['publishedNs'])
        fed=max(e['ns'] for e in s['events'] if e['stage']=='EMULATOR_FED');out.update(fedToPublishNs=s['publishedNs']-fed,inputToFedNs=fed-s['uiNs'])
        # OnDraw.drawingTime is stamped only later by ViewRoot; it may belong to the PRIOR frame.
        # Match the terminal callback INSIDE the one recorded UI draw interval, never nearest time.
        gfx=[g for g in gfx_frames(phases[s['phase']]['gfxinfo']) if g.get('Flags')==0 and
             g.get('DrawStart',0)<=s['drawNs']<=g.get('SyncQueued',-1)]
        if len(gfx)!=1:out['match']='missing-or-ambiguous-gfxinfo';rows.append(out);continue
        native=[m for m in case['metrics'] if m[2]==gfx[0]['Vsync']]
        if len(native)!=1:out['match']='ambiguous-window-vsync';rows.append(out);continue
        f=native[0];out.update(windowVsyncNs=f[2],windowTotalNs=f[3],windowDrawNs=f[5],windowGpuNs=f[7])
        token=gfx[0]['FrameTimelineVsyncId'];sf=[f for f in trace['surfaces'] if f['pid']==case['pid'] and f['token']==token]
        if len(sf)!=1:out['match']='missing-or-ambiguous-SurfaceFrame';rows.append(out);continue
        sf=sf[0];display=[d for d in trace['displays'] if d['token']==sf['displayToken']]
        if len(display)!=1 or sf['clock']!=6 or display[0]['clock']!=6:out['match']='display-or-clock-unmatched';rows.append(out);continue
        c=phases[s['phase']];before=c['clockBefore']['bootNs']-c['clockBefore']['monoNs'];after=c['clockAfter']['bootNs']-c['clockAfter']['monoNs']
        if abs(after-before)>1_000_000:out['match']='clock-offset-drift';rows.append(out);continue
        offset=(before+after)//2;end=display[0]['endNs']-offset
        if sf['present'] not in (1,2,3) or display[0]['present'] not in (1,2,3) or end<s['drawNs']:out['match']='dropped-or-invalid-presentation';rows.append(out);continue
        out.update(match='exact-token',vsyncId=token,displayToken=sf['displayToken'],inputToDisplayEndNs=end-s['uiNs'],drawToDisplayEndNs=end-s['drawNs'],clockOffsetDriftNs=abs(after-before))
        if trace['lostFtraceBundles']==0 and trace['ftraceClocks']==[0] and trace['compactSchedBundles']==0:
            lo=s['publishedNs']+offset;hi=s['drawNs']+offset
            out['mainRunningPublishToDrawNs']=sum(max(0,min(hi,b)-max(lo,a)) for a,b in intervals)
        rows.append(out)
    return rows
def report(text,manifest,device,installed):
    data=validate(text);scope=guard();traces=[];summaries=[]
    for method,name in zip(METHODS,CASES):
        case=next(c for c in data['cases'] if c['case']==name);path=OUT/'latency-traces'/(method+'.pftrace')
        status=json.loads((path.parent/(method+'-trace-status.json')).read_bytes())
        require(status['status']=='captured' and path.is_file(),'Real application trace unavailable; preserve diagnostics')
        raw=path.read_bytes();require(0<len(raw)<100663296,'Trace absent or file cap reached')
        decoded=trace_data(raw,case['pid']);require(decoded['surfaces'],'No target app SurfaceFrames')
        matched=correlate(case,decoded)
        record=dict(case=name,sha256=hashlib.sha256(raw).hexdigest(),bytes=len(raw),rows=matched,completeSurfaceFrames=len(decoded['surfaces']),completeDisplayFrames=len(decoded['displays']),
            lostFtraceBundles=decoded['lostFtraceBundles'],compactSchedBundles=decoded['compactSchedBundles'],ftraceClocks=decoded['ftraceClocks'])
        (path.parent/(method+'-correlation.json')).write_text(json.dumps(record,indent=2)+'\n');traces.append(record)
        for phase in PHASES:
            rows=[r for r in matched if r['phase']==phase and not r['warmup']];exact=[r for r in rows if r['match']=='exact-token']
            entry=dict(case=name,phase=phase,n=len(rows),exactTokenMatched=len(exact),matchStates={k:sum(r['match']==k for r in rows) for k in sorted({r['match'] for r in rows})})
            for k in ('inputToDrawNs','publishToDrawNs','fedToPublishNs','inputToFedNs','inputToDisplayEndNs','drawToDisplayEndNs','mainRunningPublishToDrawNs'):
                vs=[r[k] for r in rows if k in r];entry[k]=dict(n=len(vs),median=statistics.median(vs),maximum=max(vs)) if vs else None
            summaries.append(entry)
    result=dict(suite='terminal-real-clock-diagnostic-v4',sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],sourceGuard=scope,
        manifest=manifest,device=device,installedApkHashes=installed,**data,traces=traces,summary=summaries,
        note='Real-clock accessibility actions/nativePTY. Unique gfxinfo DrawStart/SyncQueued containment→vsync token→Surface/DisplayFrame; unmatched retained. '
             'DisplayFrame end is an OS-present observation,not physical photons/phoneFPS. No speedup or old-baseline comparison.')
    wire.legacy.publish('Real clock terminal diagnosis',result,OUT/'real-clock.json',emit_compact=False)
    compact=dict(suite=result['suite'],sourceSha=result['sourceSha'],runId=result['runId'],sourceGuard=scope,summary=summaries,
        reportSha256=hashlib.sha256((OUT/'real-clock.json').read_bytes()).hexdigest(),samples=144,warmups=24,phoneMeasured=False)
    (OUT/'real-clock-summary.json').write_text(json.dumps(compact,separators=(',',':')))
    return result
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','evidence'));a=p.parse_args()
    if a.action=='guard':print(json.dumps(guard()))
    else:wire.legacy.compact('Real clock terminal diagnosis',(OUT/'real-clock-summary.json').read_bytes())
