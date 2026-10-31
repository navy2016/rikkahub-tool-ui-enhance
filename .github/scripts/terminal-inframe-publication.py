"""Isolated safe in-frame publication candidate: boundaries and mechanism before performance."""
import argparse,hashlib,importlib.util,json,os,re,shutil,subprocess,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];BASE=ROOT/'benchmarks/terminal-inframe-publication-candidate'
OUT=ROOT/'artifacts/terminal-inframe-publication'
PROTOCOL_SHA='884495008f0760ece6b96de9293dcb320e0cabfd7d924cae8c18e8a42b4549c2'
PAGE='app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt'
HELPER='app/src/main/java/me/rerere/rikkahub/data/container/TerminalInFramePublication.kt'
UNIT='app/src/test/java/me/rerere/rikkahub/data/container/TerminalInFramePublicationTest.kt'
TRACE='app/src/main/java/me/rerere/rikkahub/data/container/TerminalPipelineTrace.kt'
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalInFramePublicationTest.kt'
TAG=' // INFRAME_PUBLICATION_PROBE'
STAGES=('INFRAME_WAIT_START','INFRAME_CALLBACK_START','INFRAME_CALLBACK_END','INFRAME_ELIGIBLE','INFRAME_PUBLISH_CALL','INFRAME_PUBLISH_RESULT',
    'INFRAME_CONTINUATION','INFRAME_FALLBACK_SYNC_START','INFRAME_FALLBACK_SYNC_END','INFRAME_FALLBACK_RESIZE_START',
    'INFRAME_FALLBACK_RESIZE_END','INFRAME_FALLBACK_CALL','INFRAME_FALLBACK_RETURN','INFRAME_BLANK_START','INFRAME_BLANK_END')
s=importlib.util.spec_from_file_location('inframe_ci',Path(__file__).with_name('run-terminal-pipeline.py'))
ci=importlib.util.module_from_spec(s);s.loader.exec_module(ci)
s=importlib.util.spec_from_file_location('inframe_identity',Path(__file__).with_name('terminal-frame-identity.py'))
identity=importlib.util.module_from_spec(s);s.loader.exec_module(identity)
real=ci.real_clock;require=real.require;old_sources=ci.sources
METHODS=('inframeOneThousandHidden','inframeOneThousandShown','inframeTenThousandHidden','inframeTenThousandShown')
def sha(raw):return hashlib.sha256(raw).hexdigest()
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def protocol():
    raw=(BASE/'protocol.json').read_bytes();require(sha(raw)==PROTOCOL_SHA,'In-frame boundary protocol changed');return json.loads(raw)
def spec():return json.loads((BASE/'sources.json').read_bytes())
def guard():
    p=protocol();require(git('rev-parse','HEAD:app/src/main').decode().strip()==p['baseTree'],'Production tree changed')
    require(not git('diff','--name-only',p['baseProductionSha'],'--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml').strip(),'Production/dependencies dirty')
    require(not (ROOT/HELPER).exists() and not (ROOT/UNIT).exists(),'Temporary candidate/helper test left in production tree')
    require(sha((ROOT/PAGE).read_bytes())==spec()['files'][PAGE]['base'],'Base Page changed')
    return p
def patch_page(original):
    lines=(BASE/'candidate.patch').read_text().splitlines(keepends=True);source=original.splitlines(keepends=True);out=[];cursor=0;i=0
    require(lines[:2]==['--- a/'+PAGE+'\n','+++ b/'+PAGE+'\n'],'Patch scope is not exactly Page')
    i=2
    while i<len(lines):
        hit=re.fullmatch(r'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*\n',lines[i]);require(hit is not None,'Invalid Page hunk')
        start=int(hit[1])-1;require(start>=cursor,'Overlapping patch hunks');out.extend(source[cursor:start]);cursor=start;i+=1;old_count=0;new_count=0
        while i<len(lines) and not lines[i].startswith('@@ '):
            line=lines[i];require(line[:1] in (' ','+','-'),'Unexpected patch operation')
            if line[0] in ' -':require(cursor<len(source) and source[cursor]==line[1:],'Patch base context differs');cursor+=1;old_count+=1
            if line[0] in ' +':out.append(line[1:]);new_count+=1
            i+=1
        require(old_count==int(hit[2] or 1) and new_count==int(hit[4] or 1),'Patch hunk counts differ')
    out.extend(source[cursor:]);return ''.join(out)
def strip_candidate(page):
    imports=('import kotlinx.coroutines.currentCoroutineContext\n','import kotlinx.coroutines.isActive\n',
        'import me.rerere.rikkahub.data.container.terminalInFramePublicationAllowed\n','import me.rerere.rikkahub.data.container.tryTerminalInFramePublication\n')
    for line in imports:require(page.count(line)==1,'Unexpected candidate import');page=page.replace(line,'')
    page=page.replace('    val publicationAttached = remember(processId) { AtomicBoolean(false) }\n','')
    a=page.index('    val latestTryPublishInFrame by rememberUpdatedState<() -> Boolean>({\n');b=page.index('    fun scheduleTerminalRender() {\n',a)
    page=page[:a]+page[b:]
    start=page.index('                val publicationContext = currentCoroutineContext()\n',page.index('    fun scheduleTerminalRender() {'))
    body=page.index('                    val syncCompleted = if (terminalOutputUpdates != null) {\n',start)
    end=page.index('                if (forceBlankPending.get()) {\n',body)
    fallback=page[body:end];require(fallback.endswith('                }\n'),'Fallback branch not closed')
    fallback=fallback[:-len('                }\n')]
    fallback=''.join(line[4:] if line.strip() else line for line in fallback.splitlines(keepends=True))
    page=page[:start]+'                withFrameNanos { } // One publication per display frame; no fixed 33ms polling cadence.\n'+fallback+page[end:]
    page=page.replace('        publicationAttached.set(true)\n','').replace('            publicationAttached.set(false)\n','')
    return page
def candidate():
    guard();base=(ROOT/PAGE).read_text();page=patch_page(base);helper=(BASE/'TerminalInFramePublication.kt').read_text()
    require(strip_candidate(page)==base,'Candidate changed original publication/fallback beyond declared structure')
    require(sha(page.encode())==spec()['files'][PAGE]['candidate'] and sha(helper.encode())==spec()['files'][HELPER]['candidate'],'Candidate digest mismatch')
    return {PAGE:page,HELPER:helper}
def marker(name,spaces,flag=None):
    return ' '*spaces+f'TerminalPipelineTrace.record(processId, TerminalPipelineStage.{name}'+(', virtual = '+flag if flag else '')+')'+TAG+'\n'
def instrument(files):
    out=dict(files);page=out[PAGE]
    a='''        tryTerminalInFramePublication(allowed, renderPending) {
            latestPublishTerminalFrame(false, false)
        }
'''
    b=marker('INFRAME_ELIGIBLE',8,'allowed')+'''        tryTerminalInFramePublication(allowed, renderPending) {
'''+marker('INFRAME_PUBLISH_CALL',12)+'''            val inFrameProbeResult = latestPublishTerminalFrame(false, false)
'''+marker('INFRAME_PUBLISH_RESULT',12,'inFrameProbeResult')+'''            inFrameProbeResult
        }
'''
    require(page.count(a)==1,'Missing helper call');page=page.replace(a,b)
    a='''                val publishedInFrame = withFrameNanos {
                    publicationContext.isActive && latestTryPublishInFrame()
                }
'''
    b=marker('INFRAME_WAIT_START',16)+'''                val publishedInFrame = withFrameNanos {
'''+marker('INFRAME_CALLBACK_START',20)+'''                    val inFrameProbeAllowed = publicationContext.isActive && latestTryPublishInFrame()
'''+marker('INFRAME_CALLBACK_END',20,'inFrameProbeAllowed')+'''                    inFrameProbeAllowed
                }
'''+marker('INFRAME_CONTINUATION',16,'publishedInFrame')
    require(page.count(a)==1,'Missing callback');page=page.replace(a,b)
    page=page.replace('''                if (!publishedInFrame) {
''','''                if (!publishedInFrame) {
'''+marker('INFRAME_FALLBACK_SYNC_START',20,'terminalOutputUpdates?.value?.synchronizedOutput ?: terminalEmulator.isSynchronizedOutput()'))
    page=page.replace('''                    if (resizeAwaitingTuiRedraw.get()) {
''',marker('INFRAME_FALLBACK_SYNC_END',20,'syncCompleted')+marker('INFRAME_FALLBACK_RESIZE_START',20,'resizeAwaitingTuiRedraw.get()')+'''                    if (resizeAwaitingTuiRedraw.get()) {
''')
    page=page.replace('''                    renderPending.set(false) // Capture includes all notifications received while awaiting this frame.
''',marker('INFRAME_FALLBACK_RESIZE_END',20)+'''                    renderPending.set(false) // Capture includes all notifications received while awaiting this frame.
''')
    page=page.replace('''                    if (!latestPublishTerminalFrame(forced, !syncCompleted)) {
''',marker('INFRAME_FALLBACK_CALL',20,'forced')+'''                    val fallbackProbeResult = latestPublishTerminalFrame(forced, !syncCompleted)
'''+marker('INFRAME_FALLBACK_RETURN',20,'fallbackProbeResult')+'''                    if (!fallbackProbeResult) {
''')
    page=page.replace('''                    delay(TERMINAL_GRID_CLEAR_GRACE_MS)
''',marker('INFRAME_BLANK_START',20)+'''                    delay(TERMINAL_GRID_CLEAR_GRACE_MS)
'''+marker('INFRAME_BLANK_END',20))
    trace=(ROOT/TRACE).read_text();trace=trace.replace('    ROW_SYNC_STARTED, ROW_SYNC_FINISHED,\n',
        '    ROW_SYNC_STARTED, ROW_SYNC_FINISHED,\n'+''.join('    '+stage+','+TAG+'\n' for stage in STAGES))
    out[PAGE]=page;out[TRACE]=trace
    require(strip_probe(PAGE,page)==files[PAGE] and strip_probe(TRACE,trace)==(ROOT/TRACE).read_text(),'Trace-only overlay did not roundtrip')
    return out
def strip_probe(name,text):
    text='\n'.join(line for line in text.split('\n') if TAG not in line)
    if name==PAGE:
        text=text.replace('''            val inFrameProbeResult = latestPublishTerminalFrame(false, false)
            inFrameProbeResult
''','''            latestPublishTerminalFrame(false, false)
''')
        text=text.replace('''                    val inFrameProbeAllowed = publicationContext.isActive && latestTryPublishInFrame()
                    inFrameProbeAllowed
''','''                    publicationContext.isActive && latestTryPublishInFrame()
''')
        text=text.replace('''                    val fallbackProbeResult = latestPublishTerminalFrame(forced, !syncCompleted)
                    if (!fallbackProbeResult) {
''','''                    if (!latestPublishTerminalFrame(forced, !syncCompleted)) {
''')
    return text
