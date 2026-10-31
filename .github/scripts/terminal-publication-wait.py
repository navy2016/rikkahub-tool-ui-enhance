"""Trace-only publication scheduler diagnosis on55; no optimized scheduler or rejected P2 overlay."""
import argparse,hashlib,importlib.util,json,os,re,shutil,subprocess,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'benchmarks/terminal-publication-wait';OUT=ROOT/'artifacts/terminal-publication-wait'
PROTOCOL_SHA='989f03de269a1cdd8ac1e73df4f477710b20b4c10d0ac35865009064c5ebccf1'
PAGE='app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt'
TRACE='app/src/main/java/me/rerere/rikkahub/data/container/TerminalPipelineTrace.kt'
MANAGER='app/src/main/java/me/rerere/rikkahub/data/container/BackgroundProcessManager.kt'
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalPublicationWaitTest.kt'
s=importlib.util.spec_from_file_location('publication_wait_ci',Path(__file__).with_name('run-terminal-pipeline.py'))
ci=importlib.util.module_from_spec(s);s.loader.exec_module(ci)
s=importlib.util.spec_from_file_location('publication_wait_identity',Path(__file__).with_name('terminal-frame-identity.py'))
identity=importlib.util.module_from_spec(s);s.loader.exec_module(identity)
real=ci.real_clock;require=real.require;old_sources=ci.sources
METHODS=('publicationOneThousandHidden','publicationOneThousandShown','publicationTenThousandHidden','publicationTenThousandShown')
TAG=' // PUBLICATION_WAIT_PROBE'
def protocol():
    raw=(BASE/'protocol.json').read_bytes();require(hashlib.sha256(raw).hexdigest()==PROTOCOL_SHA,'Frozen publication protocol changed')
    return json.loads(raw)
def digest(raw):return hashlib.sha256(raw).hexdigest()
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def guard():
    p=protocol();require(git('rev-parse','HEAD:app/src/main').decode().strip()==p['baseTree'],'Production tree changed')
    require(not git('diff','--name-only',p['baseProductionSha'],'--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml').strip(),'Live production/dependencies differ')
    return p
def mark(stage,indent=8,flag=None,process='processId',revision=None):
    args=(', virtual = '+flag) if flag is not None else ''
    if revision is not None:args+=', frameRevision = '+revision
    return ' '*indent+f'TerminalPipelineTrace.record({process}, TerminalPipelineStage.{stage}{args})'+TAG+'\n'
def strip_probe(name,text):
    if name==TRACE:
        return '\n'.join(line for line in text.split('\n') if TAG not in line)
    text='\n'.join(line for line in text.split('\n') if TAG not in line)
    if name==PAGE:
        text=text.replace('''        if (renderJob.get()?.isActive == true) {
            return
        }
''','''        if (renderJob.get()?.isActive == true) return
''')
        text=text.replace('''                withFrameNanos {
                } // One publication per display frame; no fixed 33ms polling cadence.
''','''                withFrameNanos { } // One publication per display frame; no fixed 33ms polling cadence.
''')
        text=text.replace('''                val publicationProbeResult = latestPublishTerminalFrame(forced, !syncCompleted)
                if (!publicationProbeResult) {
''','''                if (!latestPublishTerminalFrame(forced, !syncCompleted)) {
''')
    return text
def overlay():
    guard();original={p:(ROOT/p).read_text() for p in (PAGE,TRACE,MANAGER)};out=dict(original)
    def replace(name,old,new):
        require(out[name].count(old)==1,'Probe anchor not unique:'+old[:90]);out[name]=out[name].replace(old,new)
    replace(TRACE,'    ROW_SYNC_STARTED, ROW_SYNC_FINISHED,\n',
        '    ROW_SYNC_STARTED, ROW_SYNC_FINISHED,\n'+''.join('    '+stage+','+TAG+'\n' for stage in protocol()['markers']))
    replace(PAGE,'        val frame = synchronized(terminalEmulator) {\n',
        '        val frame = synchronized(terminalEmulator) {\n'+mark('PUBLICATION_MODEL_LOCK_ACQUIRED',12))
    start=out[PAGE].index('    fun scheduleTerminalRender() {\n');end=out[PAGE].index('\n    LaunchedEffect(processId, appliedTerminalRenderMode',start)
    old=out[PAGE][start:end];body=old
    body=body.replace('    fun scheduleTerminalRender() {\n','    fun scheduleTerminalRender() {\n'+mark('PUBLICATION_REQUEST'))
    body=body.replace('        if (renderJob.get()?.isActive == true) return\n',
        '        if (renderJob.get()?.isActive == true) {\n'+mark('PUBLICATION_COALESCED',12)+'            return\n        }\n')
    body=body.replace('        renderJob.set(scope.launch {\n',mark('PUBLICATION_ENQUEUED')+'        renderJob.set(scope.launch {\n'+mark('PUBLICATION_JOB_START',12))
    body=body.replace('            while (renderPending.getAndSet(false)) {\n',
        '            while (renderPending.getAndSet(false)) {\n'+mark('PUBLICATION_CYCLE_START',16)+mark('PUBLICATION_FRAME_WAIT_START',16))
    body=body.replace('                withFrameNanos { } // One publication per display frame; no fixed 33ms polling cadence.\n',
        '                withFrameNanos {\n'+mark('PUBLICATION_FRAME_CALLBACK',20)+'                } // One publication per display frame; no fixed 33ms polling cadence.\n'+
        mark('PUBLICATION_FRAME_WAIT_END',16)+mark('PUBLICATION_SYNC_CHECK',16,flag='terminalOutputUpdates?.value?.synchronizedOutput ?: terminalEmulator.isSynchronizedOutput()'))
    body=body.replace('                if (resizeAwaitingTuiRedraw.get()) {\n',mark('PUBLICATION_SYNC_DONE',16,flag='syncCompleted')+
        mark('PUBLICATION_RESIZE_CHECK',16,flag='resizeAwaitingTuiRedraw.get()')+'                if (resizeAwaitingTuiRedraw.get()) {\n')
    body=body.replace('                renderPending.set(false) // Capture includes all notifications received while awaiting this frame.\n',
        mark('PUBLICATION_RESIZE_DONE',16)+'                renderPending.set(false) // Capture includes all notifications received while awaiting this frame.\n')
    body=body.replace('                if (!latestPublishTerminalFrame(forced, !syncCompleted)) {\n',
        mark('PUBLICATION_CALL',16,flag='forced')+'                val publicationProbeResult = latestPublishTerminalFrame(forced, !syncCompleted)\n'+
        mark('PUBLICATION_RETURN',16,flag='publicationProbeResult')+'                if (!publicationProbeResult) {\n')
    body=body.replace('                    delay(TERMINAL_GRID_CLEAR_GRACE_MS)\n',mark('PUBLICATION_BLANK_WAIT_START',20)+
        '                    delay(TERMINAL_GRID_CLEAR_GRACE_MS)\n'+mark('PUBLICATION_BLANK_WAIT_END',20))
    body=body.replace('        })\n',mark('PUBLICATION_JOB_END',12)+'        })\n')
    replace(PAGE,old,body)
    replace(MANAGER,'                record.notifications.published(data.size, revision, System.nanoTime(),\n                    record.terminalEmulator.isSynchronizedOutput())\n',
        mark('OUTPUT_NOTIFY_START',16,process='record.processId',revision='revision')+
        '                record.notifications.published(data.size, revision, System.nanoTime(),\n                    record.terminalEmulator.isSynchronizedOutput())\n'+
        mark('OUTPUT_NOTIFY_END',16,process='record.processId',revision='revision'))
    for name in original:require(strip_probe(name,out[name])==original[name],'Probe inverse did not recover original bytes:'+name)
    return original,out
