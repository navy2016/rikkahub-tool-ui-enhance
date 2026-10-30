"""Isolated thin-cursor adapter experiment; production tree stays frozen; no phone performance claim."""
import argparse,hashlib,importlib.util,json,math,os,re,statistics,subprocess
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/terminal-cursor-experiment'
TREE='8caf50d5c40e228f1d5bcc09d3db505757568b68'
TEST='benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalFullVirtualCursorExperimentTest.kt'
FIXTURE='benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalItemViewportInstrumentedTest.kt'
CLASS='me.rerere.rikkahub.viewporttest.TerminalFullVirtualCursorExperimentTest'
METHODS={'cursorVirtualOverlayGraphemePixelsAndNaturalLayout','cursorVirtualPairedMovesKeepEverySample'}
spec=importlib.util.spec_from_file_location('cursor_old_helpers',Path(__file__).with_name('terminal-tui-history-reports.py'))
legacy=importlib.util.module_from_spec(spec);spec.loader.exec_module(legacy)
require=legacy.require
def records(text,name):return legacy.records(text,'CURSOR_VIRTUAL_'+name+' ')
def validate(text):
    samples=records(text,'SAMPLE');disposed=records(text,'DISPOSE');pixels=records(text,'PIXEL');device=records(text,'DEVICE')
    expected={(n,s,r,a) for n in (1000,10000) for s in (4,6) for r in range(5) for a in (False,True)}
    def key(v):return(v.get('size'),v.get('shape'),v.get('round'),v.get('overlay'))
    require(len(samples)==40 and {key(v) for v in samples}==expected,'Missing fixed40samplematrix')
    require(len(disposed)==40 and {key(v) for v in disposed}==expected and all(v['retainedLayouts']==v['pointerRows']==0 for v in disposed),'Cleanup matrix differs')
    for v in samples:
        require(type(v['overlay']) is bool and type(v['warmup']) is bool and v['warmup'] is (v['round']==0) and v['moves']==24,'Workload/order changed')
        require(all(type(v[k]) is int and v[k]>=0 for k in ('elapsedNs','paragraphChanges','textChanges','widthMeasurements','rowMeasurements')) and v['elapsedNs']>0,'Invalid counts')
        require(0<v['retainedRows']<=80 and 0<v['measuredRows']<=128 and v['writers']<=1 and re.fullmatch('[a-f0-9]{64}',v['textSha256']),'Invalid bounded virtual evidence')
    for size in (1000,10000):require(len({s['textSha256'] for s in samples if s['size']==size})==1,'Corpus changed betweenarms')
    order=[(n,s,r,a) for n in (1000,10000) for s in (6,4) for r in range(5) for a in ((False,True) if r%2==0 else (True,False))]
    require([key(v) for v in samples]==order,'AB/BA order changed')
    expected_pixels={(rtl,scale,shape,c) for rtl in (False,True) for scale in (1,1.3) for shape in (6,4) for c in (6,8,13,15,18)}
    require(len(pixels)==40 and {(p['rtl'],p['scale'],p['shape'],p['column']) for p in pixels}==expected_pixels and
        all(p['changedPixels']>0 and p['paragraphReused'] is True and p['virtual'] is True for p in pixels),'Cursor pixel or grapheme geometry incomplete')
    require(len(device)==1 and device[0]['pid']>0 and all(re.fullmatch('[a-f0-9]{64}',device[0][k]) for k in ('targetApk','testApk')),'Missing device/APK identity')
    summary=[]
    for size in (1000,10000):
        for shape in (6,4):
            group=[s for s in samples if s['size']==size and s['shape']==shape and not s['warmup']]
            arms={str(a):[s for s in group if s['overlay']==a] for a in (False,True)}
            values={key:{k:statistics.median(r[k] for r in rows) for k in ('elapsedNs','paragraphChanges','textChanges','widthMeasurements','rowMeasurements')} for key,rows in arms.items()}
            deltas=[next(s['elapsedNs'] for s in group if s['round']==r and s['overlay'])-next(s['elapsedNs'] for s in group if s['round']==r and not s['overlay']) for r in range(1,5)]
            work_reduced=all(s['paragraphChanges']==s['textChanges']==s['widthMeasurements']==0 for s in arms['True']) and all(s['paragraphChanges']>0 and s['widthMeasurements']>0 for s in arms['False'])
            summary.append(dict(size=size,shape=shape,arms=values,pairedElapsedDeltaNs=deltas,workReductionDemonstrated=work_reduced))
    return dict(samples=samples,disposals=disposed,pixels=pixels,device=device,summary=summary)
def report():
    tree=subprocess.check_output(['git','rev-parse','HEAD:app/src/main'],cwd=ROOT,text=True).strip();require(tree==TREE,'Production changed before isolation experiment')
    data=validate((OUT/'cursor-logcat.txt').read_text());found=[]
    base=ROOT/'benchmarks/terminal-viewport-tests'
    for p in (base/'build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for c in ET.parse(p).getroot().iter('testcase'):
            require(not any(c.find(t) is not None for t in ('failure','error','skipped')),'Experiment method failed')
            found.append((c.get('classname'),c.get('name')))
    require(len(found)==2 and {n for c,n in found if c==CLASS}==METHODS,'Wrong JUnit methods')
    hashes={}
    for p in (base/'build/generated/viewportSources').rglob('*.kt'):
        original=ROOT/'app/src/main/java'/p.relative_to(base/'build/generated/viewportSources')
        require(p.read_bytes()==original.read_bytes(),'Copied production source differs');hashes[str(original.relative_to(ROOT))]=hashlib.sha256(p.read_bytes()).hexdigest()
    require(len(hashes)>=40,'Missing production source identity')
    value=dict(suite='full-virtual-thin-cursor-isolation-v1',sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],productionTree=tree,
        productionChanged=False,phoneMeasured=False,oldSamplesReplayed=False,productionHashes=hashes,
        tests={p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in (TEST,FIXTURE)},**data,
        note='Adapter experiment,not integrated production/PTY/phone. elapsedNs includes test idle/layout-query overhead; no FPS or overall speedup claim.')
    legacy.publish('Full virtual thin cursor',value,OUT/'experiment.json',emit_compact=False)
    compact={k:v for k,v in value.items() if k not in ('samples','disposals','pixels','productionHashes')}
    compact['reportSha256']=hashlib.sha256((OUT/'experiment.json').read_bytes()).hexdigest()
    (OUT/'summary.json').write_text(json.dumps(compact,separators=(',',':')))
def timing_diagnostic():
    require(subprocess.check_output(['git','rev-parse','HEAD:app/src/main'],cwd=ROOT,text=True).strip()==TREE,'Diagnostic changed production')
    rows=legacy.records((OUT/'timing-logcat.txt').read_text(),'CURSOR_DRAW_DIAGNOSTIC ')
    require(len(rows)==16 and {(r['overlay'],r['step']) for r in rows}=={(a,i) for a in (False,True) for i in range(8)},'Missing16diagnosticpublications')
    for r in rows:
        require(r['beganNs']<=r['publishedNs']<=r['drawnNs']<=r['returnedNs'] and 0<=r['explicitFrames']<=8 and
            r['targetDraws']>0 and r['writers']<=1 and r['virtual'] is True,'Incomplete diagnostic draw')
    found=[]
    for p in (ROOT/'benchmarks/terminal-viewport-tests/build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for c in ET.parse(p).getroot().iter('testcase'):
            require(not any(c.find(k) is not None for k in ('error','failure','skipped')),'Timing diagnostic failed')
            found.append(c.get('name'))
    require(found==['cursorDiagnosticPublicationToActualDrawWithoutAutoAdvance'],'Different diagnosticmethod inventory')
    value=dict(suite='thin-cursor-draw-diagnostic-v1',sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],
        productionTree=TREE,productionChanged=False,previousExperimentRun=41085513057,performanceResampled=False,rows=rows,
        note='Explicit test-clock publication-to-draw diagnostic only. Not phone/PTy/presentation or replay of40performancewindows.')
    legacy.publish('Thin cursor draw diagnostic',value,OUT/'timing-diagnostic.json',emit_compact=True)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('report','evidence','timing'));a=p.parse_args()
    try:
        if a.action=='report':report()
        elif a.action=='timing':timing_diagnostic()
        else:legacy.compact('Full virtual thin cursor',(OUT/'summary.json').read_bytes())
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        print('::error title=Thin cursor experiment rejected::'+str(e).replace('%','%25').replace('\n','%0A'));raise SystemExit(1)
