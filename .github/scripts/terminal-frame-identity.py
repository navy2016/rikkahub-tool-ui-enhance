"""Independent BOOTTIME UI-attempt / exact buffer / DisplayFrame identity state machine.

No CLI builds or historical performance acceptance. Old P2 collectors stay unchanged.
"""
import bisect,collections,hashlib,importlib.util,json,re
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
PROTOCOL=ROOT/'benchmarks/terminal-frame-identity/protocol.json'
PROTOCOL_SHA256='8205b614f689ceaa0553aefa1323a56ca1bcd939ec8ffefe66726095e02c43b9'
spec=importlib.util.spec_from_file_location('identity_pb_wire',Path(__file__).with_name('terminal-real-clock.py'))
wire=importlib.util.module_from_spec(spec);spec.loader.exec_module(wire)
require=wire.require
UI_RE=re.compile(r'Choreographer#doFrame (\d+)')
RT_RE=re.compile(r'DrawFrames (\d+)')
MAP_RE=re.compile(r'setFrameTimelineInfo\((.*)\) frameNumber: (\d+) vsyncId: (\d+)')
ACQUIRE_RE=re.compile(r'acquireNextBufferLocked - (.*?)\(f:\d+,a:\d+\)frame=(\d+)')
QUEUE_RE=re.compile(r'VRI\[TerminalPipelineTestActivity\]#\d+')

def protocol():
    raw=PROTOCOL.read_bytes();require(hashlib.sha256(raw).hexdigest()==PROTOCOL_SHA256,'Frozen identity protocol changed')
    return json.loads(raw)

def relevant(name):
    return any(pattern.fullmatch(name) for pattern in (UI_RE,RT_RE,MAP_RE,ACQUIRE_RE)) or name.startswith(('Drawing ','eglSwapBuffers')) or name=='syncFrameState'

def parse_trace(raw,pid):
    """Keep errors and duplicates; never make a dict overwrite look like a unique identity."""
    require(type(pid) is int and pid>0 and 0<len(raw)<100663296,'Invalid bounded trace/PID')
    events=collections.defaultdict(list);starts=collections.defaultdict(list);ends=collections.defaultdict(list)
    faults=[];clocks=set();ordinal=0;seen_sequences=collections.Counter();initial_markers=[]
    for tag,typ,packet in wire.pb(raw):
        if tag!=1 or typ!=2:continue
        entries=list(wire.pb(packet));scalars={f:v for f,w,v in entries if w==0}
        sequence=scalars.get(10,0);clock=scalars.get(58,6);ts=scalars.get(8)
        first=scalars.get(87,0);loss=scalars.get(42,0)
        if first and (sequence==0 or seen_sequences[sequence]):faults.append('invalid-sequence-first-marker')
        if loss:
            # Perfetto's producer first-packet marker explicitly proves no preceding data loss.
            # Some service versions also set the bare dropped bit on this first packet. Keep
            # this evidence; accept ONLY the first observed packet, a real sequence and bit1.
            if loss==1 and first==1 and sequence>0 and seen_sequences[sequence]==0:
                initial_markers.append(dict(sequence=sequence,timestamp=ts,previousPacketDropped=loss,firstPacketOnSequence=first))
            else:faults.append('packet-loss')
        seen_sequences[sequence]+=1
        for field,w,value in entries:
            if field==76 and w==2:
                for kind,z,record in wire.pb(value):
                    if z!=2 or kind not in (2,4,5):continue
                    e=wire.fields(record);key=(sequence,e.get(1))
                    if kind==5:
                        ends[key].append(dict(ns=ts,clock=clock));continue
                    if kind==4 and e.get(4)!=pid:continue
                    start=dict(cookie=list(key),kind=kind,startNs=ts,clock=clock,token=e.get(2),
                        displayToken=e.get(3) if kind==4 else e.get(2),pid=e.get(4) if kind==4 else e.get(3),
                        present=e.get(6) if kind==4 else e.get(4),isBuffer=e.get(11) if kind==4 else None,
                        layer=e.get(5,b'').decode(errors='replace') if kind==4 else '')
                    starts[key].append(start)
            if field!=1 or w!=2:continue
            fields=list(wire.pb(value));bundle={f:v for f,z,v in fields if z==0};clocks.add(bundle.get(5,0))
            if bundle.get(3,0):faults.append('lost-ftrace-bundle')
            if any(f==8 for f,z,v in fields):faults.append('ftrace-parse-error')
            if any(f==4 and z==2 for f,z,v in fields):faults.append('compact-sched-not-allowed')
            for f,z,record in fields:
                if f!=2 or z!=2:continue
                e=wire.fields(record)
                if 3 not in e:continue
                now=e.get(1);tid=e.get(2);text=wire.fields(e[3]).get(2,b'').decode(errors='replace').strip();ordinal+=1
                if not isinstance(tid,int) or not isinstance(now,int):faults.append('bad-print-identity');continue
                if text.startswith(f'B|{pid}|') or text in ('E',f'E|{pid}'):
                    events[tid].append((now,ordinal,text))
    if clocks!={0}:faults.append('ftrace-clock-not-boot')
    spans=[];open_spans=[]
    for tid,records in events.items():
        stack=[]
        for now,order,text in sorted(records):
            if text.startswith(f'B|{pid}|'):stack.append(dict(tid=tid,startNs=now,name=text.split('|',2)[2]))
            elif stack:
                span=stack.pop();span['endNs']=now;spans.append(span)
        open_spans.extend(s for s in stack if relevant(s['name']))
    if open_spans:faults.append('unclosed-relevant-frame-span')
    surfaces=[];displays=[]
    for key,records in starts.items():
        for record in records:
            endpoint=ends.get(key,[])
            record=dict(record,complete=False,endNs=None)
            if len(records)!=1 or len(endpoint)>1:record['error']='duplicate-cookie'
            elif len(endpoint)!=1:record['error']='missing-cookie-end'
            elif record['clock']!=6 or endpoint[0]['clock']!=6:record['error']='timeline-clock-not-boot'
            elif not isinstance(record['startNs'],int) or not isinstance(endpoint[0]['ns'],int) or endpoint[0]['ns']<record['startNs']:
                record['error']='invalid-timeline-range'
            else:record.update(complete=True,endNs=endpoint[0]['ns'])
            if record['clock']!=6:faults.append('timeline-clock-not-boot')
            (surfaces if record['kind']==4 else displays).append(record)
    return dict(pid=pid,spans=spans,surfaces=surfaces,displays=displays,faults=sorted(set(faults)),openSpans=open_spans,sequenceInitialMarkers=initial_markers,
        clock='CLOCK_BOOTTIME',traceSha256=hashlib.sha256(raw).hexdigest(),traceBytes=len(raw))

def inside(child,parent):
    return child['tid']==parent['tid'] and parent['startNs']<=child['startNs'] and child['endNs']<=parent['endNs']

class FrameIndex:
    def __init__(self,trace):
        self.trace=trace;self.ui=[];self.tasks=collections.defaultdict(list);self.by_tid=collections.defaultdict(list)
        self.surfaces=collections.defaultdict(list);self.displays=collections.defaultdict(list)
        for span in trace['spans']:
            self.by_tid[span['tid']].append(span)
            m=UI_RE.fullmatch(span['name'])
            if m and span['tid']==trace['pid']:self.ui.append(dict(span,token=int(m[1])))
            m=RT_RE.fullmatch(span['name'])
            if m:self.tasks[int(m[1])].append(span)
        self.ui.sort(key=lambda x:x['startNs']);self.ui_starts=[x['startNs'] for x in self.ui]
        self.ui_prefix_end=[]
        for span in self.ui:self.ui_prefix_end.append(max(span['endNs'],self.ui_prefix_end[-1] if self.ui_prefix_end else span['endNs']))
        for spans in self.by_tid.values():spans.sort(key=lambda x:x['startNs'])
        self.tid_starts={tid:[s['startNs'] for s in spans] for tid,spans in self.by_tid.items()}
        for surface in trace['surfaces']:self.surfaces[surface['token']].append(surface)
        for display in trace['displays']:self.displays[display['token']].append(display)
    def ui_for(self,ns):
        i=bisect.bisect_right(self.ui_starts,ns)-1;out=[]
        while i>=0 and self.ui_prefix_end[i]>=ns:
            if self.ui[i]['startNs']<=ns<=self.ui[i]['endNs']:out.append(self.ui[i])
            i-=1
        return out
    def children(self,task):
        tid=task['tid'];start=bisect.bisect_left(self.tid_starts[tid],task['startNs']);end=bisect.bisect_right(self.tid_starts[tid],task['endNs'])
        return [s for s in self.by_tid[tid][start:end] if inside(s,task)]

def metrics_state(token,observers):
    """Absence is not conflict. Raw metadata is a cross-check, never a presentation substitute."""
    seen=[];faults=[]
    for observer in observers:
        matches=[]
        for value in observer:
            raw=value.get('raw',[])
            if len(raw)!=23:faults.append('bad-raw-metric-size');continue
            if raw[1]!=token:continue
            if raw[2]!=value.get('publicIntended') or raw[3]!=value.get('publicVsync'):faults.append('raw-public-metric-conflict')
            matches.append(value)
        if len(matches)>1:faults.append('duplicate-metric-token')
        seen.append(matches)
    if len(seen)==2 and all(len(m)==1 for m in seen) and any(seen[0][0]['raw'][i]!=seen[1][0]['raw'][i] for i in (1,2,3,8,12)):
        faults.append('observer-frame-identity-conflict')
    if faults:return dict(state='CONFLICT',faults=sorted(set(faults)),matches=seen)
    count=sum(len(x)==1 for x in seen)
    return dict(state='BOTH_EXACT' if count==2 else 'ONE_EXACT' if count==1 else 'ABSENT',faults=[],matches=seen)

def attempt(index,event,observers):
    result=dict(eventSeq=event['seq'],revision=event['revision'],uiAttemptBootNs=event['ns'],attempt='QUALIFIED',
        submission='UNKNOWN',presentation='UNOBSERVED',bufferEvidenceBootNs=None,displayEndBootNs=None,reason=None)
    if index.trace['faults']:result['reason']='TRACE_INTEGRITY';return result
    parents=index.ui_for(event['ns']);result['uiMatches']=len(parents)
    if len(parents)!=1:
        result.update(submission='AMBIGUOUS' if parents else 'UNKNOWN',reason='UI_FRAME_NOT_UNIQUE');return result
    ui=parents[0];token=ui['token'];result.update(uiFrame=ui,token=token,metrics=metrics_state(token,observers))
    tasks=index.tasks[token]
    if len(tasks)!=1:
        result.update(submission='AMBIGUOUS' if tasks else 'UNKNOWN',reason='RT_TASK_NOT_UNIQUE');return result
    task=tasks[0];children=index.children(task);result['renderTask']=task
    maps=[];acquires=[];draws=[];swaps=[];sync=[]
    for span in children:
        name=span['name'];m=MAP_RE.fullmatch(name)
        if m:maps.append(dict(span,queue=m[1],frameNumber=int(m[2]),token=int(m[3])))
        m=ACQUIRE_RE.fullmatch(name)
        if m:acquires.append(dict(span,queue=m[1],frameNumber=int(m[2])))
        if name.startswith('Drawing '):draws.append(span)
        if name.startswith('eglSwapBuffers'):swaps.append(span)
        if name=='syncFrameState':sync.append(span)
    surfaces=[s for s in index.surfaces[token] if s['pid']==index.trace['pid']]
    result.update(mappings=maps,acquires=acquires,drawingSpans=draws,swapSpans=swaps,surfaces=surfaces)
    if sync and not (maps or acquires or draws or swaps or surfaces):
        result.update(submission='NO_SUBMISSION',presentation='NOT_APPLICABLE',reason='SYNC_WITHOUT_BUFFER');return result
    if len(maps)>1 or len(surfaces)>1 or len(acquires)>1:
        result.update(submission='AMBIGUOUS',reason='BUFFER_IDENTITY_NOT_UNIQUE');return result
    if len(maps)!=1 or len(surfaces)!=1 or len(acquires)!=1 or not draws or not swaps:
        result['reason']='BUFFER_CHAIN_INCOMPLETE';return result
    mapping=maps[0];acquire=acquires[0];surface=surfaces[0]
    if mapping['token']!=token or not QUEUE_RE.fullmatch(mapping['queue']) or mapping['queue']!=acquire['queue'] or mapping['frameNumber']!=acquire['frameNumber']:
        result.update(submission='AMBIGUOUS',reason='BUFFER_ID_CONFLICT');return result
    if surface.get('isBuffer')!=1 or 'TerminalPipelineTestActivity' not in surface.get('layer',''):
        result['reason']='SURFACE_NOT_EXPECTED_BUFFER';return result
    if event['ns']>acquire['startNs'] or mapping['startNs']>acquire['startNs'] or not any(inside(acquire,s) for s in swaps):
        result.update(submission='AMBIGUOUS',reason='ACQUIRE_NOT_IN_SAME_SWAP');return result
    result.update(submission='SUBMITTED',bufferEvidenceBootNs=acquire['startNs'],reason=None,frameNumber=mapping['frameNumber'],queue=mapping['queue'])
    if not surface.get('complete'):
        result.update(presentation='AMBIGUOUS' if surface.get('error')=='duplicate-cookie' else 'UNOBSERVED',reason=surface.get('error','SURFACE_END_MISSING'));return result
    if surface.get('present')==4:result.update(presentation='DROPPED',reason='SURFACE_DROPPED');return result
    if surface.get('present') not in (1,2,3):result['reason']='SURFACE_PRESENT_UNKNOWN';return result
    displays=index.displays[surface['displayToken']];result['displays']=displays
    if len(displays)!=1:
        result.update(presentation='AMBIGUOUS' if displays else 'UNOBSERVED',reason='DISPLAY_NOT_UNIQUE');return result
    display=displays[0]
    if not display.get('complete'):
        result.update(presentation='AMBIGUOUS' if display.get('error')=='duplicate-cookie' else 'UNOBSERVED',reason=display.get('error','DISPLAY_END_MISSING'));return result
    if display.get('present')==4:result.update(presentation='DROPPED',reason='DISPLAY_DROPPED');return result
    if display.get('present') not in (1,2,3):result['reason']='DISPLAY_PRESENT_UNKNOWN';return result
    if display['endNs']<acquire['startNs'] or display['endNs']<surface['endNs']:
        result.update(presentation='AMBIGUOUS',reason='PRESENTATION_PRECEDES_SUBMISSION');return result
    result.update(presentation='PRESENTED',displayEndBootNs=display['endNs'],displayToken=display['token']);return result

def classify_window(window,index,observers):
    """Qualification values only. A later attempt is a different row, never a repair of an older one."""
    p=protocol();events=window['events'];require(window['clock']=='CLOCK_BOOTTIME','Pipeline is not BOOTTIME')
    require(all(a['seq']<b['seq'] and a['ns']<=b['ns'] for a,b in zip(events,events[1:])),'Nonmonotonic event inventory')
    revision=window['fedRevision'];cutoff=window['closedBootNs'];ui=[e for e in events if e['stage']=='UI_INPUT']
    require(len(ui)==1,'Exactly one input required')
    all_attempts=[e for e in events if e['stage']=='FRAME_DRAWN'];require(len(all_attempts)<=p['matrix']['maxAttemptRecordsPerWindow'],'Attempt capture over bound')
    rows=[]
    for e in all_attempts:
        if e['revision']!=revision:rows.append(dict(eventSeq=e['seq'],revision=e['revision'],uiAttemptBootNs=e['ns'],attempt='OTHER_REVISION'));continue
        if e['ns']>cutoff:rows.append(dict(eventSeq=e['seq'],revision=e['revision'],uiAttemptBootNs=e['ns'],attempt='AFTER_WINDOW'));continue
        require(e['ns']>=window['publishedNs']>=ui[0]['ns'],'Attempt precedes response publication')
        rows.append(attempt(index,e,observers))
    eligible=[r for r in rows if r['attempt']=='QUALIFIED'];require(eligible,'No finite response attempt')
    require(cutoff==eligible[0]['uiAttemptBootNs']+p['matrix']['naturalObservationTailNs'],'Natural observation cutoff changed')
    candidates=[r for r in eligible if r['submission']=='SUBMITTED' and r['bufferEvidenceBootNs']<=cutoff]
    submitted=min(candidates,key=lambda r:r['bufferEvidenceBootNs']) if candidates else None
    presented=[r for r in candidates if r['presentation']=='PRESENTED' and r['displayEndBootNs']<=cutoff]
    presentation=min(presented,key=lambda r:r['displayEndBootNs']) if presented else None
    value=dict(attempts=rows,firstAttemptEventSeq=eligible[0]['eventSeq'],firstSubmittedAttemptEventSeq=submitted['eventSeq'] if submitted else None,
        firstPresentedAttemptEventSeq=presentation['eventSeq'] if presentation else None,
        inputToAttemptNs=eligible[0]['uiAttemptBootNs']-ui[0]['ns'],inputToBufferEvidenceNs=submitted['bufferEvidenceBootNs']-ui[0]['ns'] if submitted else None,
        inputToDisplayEndNs=presentation['displayEndBootNs']-ui[0]['ns'] if presentation else None,
        noSubmittedAttempt=candidates==[],noPresentedAttempt=presented==[],performanceAccepted=False)
    value['identityDecidable']=not index.trace['faults'] and all(r['submission'] in ('SUBMITTED','NO_SUBMISSION') and
        r['presentation'] in ('PRESENTED','DROPPED','NOT_APPLICABLE') and r.get('metrics',{}).get('state')!='CONFLICT' for r in eligible)
    return value
