"""Fixed Android34 shell/Perfetto config transport diagnosis. No APK, terminal windows or qualification claim."""
import base64,hashlib,importlib.util,json,os,shlex,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'artifacts/terminal-trace-transport-stop-diagnostic-v2'
s=importlib.util.spec_from_file_location('diagnostic_emulator',Path(__file__).with_name('run-terminal-pipeline.py'))
ci=importlib.util.module_from_spec(s);s.loader.exec_module(ci)
CONFIG=ROOT/'benchmarks/terminal-trace-lifecycle-v2/trace.pbtxt'
CONFIG_SHA='e379b818afffb31c1b61cb4d8a8a8550f14da2fbce2a0d4abd0828674ecaa374'
ROUTES=('pipe',)
def save(name,value):(OUT/name).write_text(json.dumps(value,indent=2)+'\n')
def call(args,input=b'',timeout=40):
    p=subprocess.run(['adb','shell',*args],input=input,capture_output=True,timeout=timeout)
    return dict(exit=p.returncode,stdout=p.stdout.decode(errors='replace'),stderr=p.stderr.decode(errors='replace'))
def script(route,command,config):
    if route=='direct':return None
    if route=='heredoc':return (command+" <<'TRACE_CONFIG_DIAG_END'\n"+config.decode()+"TRACE_CONFIG_DIAG_END\n").encode()
    encoded=base64.b64encode(config).decode();return ("printf %s '"+encoded+"' | base64 -d | "+command+'\n').encode()
def transport(route,command,config):
    if route=='direct':return call(shlex.split(command),config)
    return call(['sh'],script(route,command,config))
def main():
    if os.environ.get('GITHUB_ACTIONS')!='true':raise ValueError('Android diagnostic is CI-only')
    config=CONFIG.read_bytes();assert hashlib.sha256(config).hexdigest()==CONFIG_SHA
    assert subprocess.check_output(['git','rev-parse','HEAD:app/src/main'],cwd=ROOT,text=True).strip()=='ac97798416997a8230c9fa2a5a8569cf0ed0a911'
    OUT.mkdir(parents=True,exist_ok=False);ci.OUT=OUT
    manifest=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],sources=ci.sources(),noApkBuilt=True,apks={});save('manifest.json',manifest)
    (OUT/'trace.pbtxt').write_bytes(config)
    def operation():
        save('device.json',dict(identity=call(['id']),domain=call(['cat','/proc/self/attr/current']),
            fingerprint=call(['getprop','ro.build.fingerprint']),perfettoVersion=call(['perfetto','--version'])))
        cases=[]
        for route in ROUTES:
            cat=transport(route,'cat',config)
            cat_bytes=cat['stdout'].encode();assert cat['exit']==0 and cat_bytes==config,'Raw byte transport failed:'+route
            remote='/data/misc/perfetto-traces/transport-diag-'+route+'.pftrace'
            call(['rm','-f',remote])
            # Standalone cat does not consume the Perfetto invocation's independent config input.
            start=transport(route,'perfetto --txt --background-wait -c - -o '+remote,config)
            pids=[s for s in start['stdout'].splitlines() if s.isdigit()];record=dict(route=route,cat=cat,catSha256=hashlib.sha256(cat_bytes).hexdigest(),start=start)
            if start['exit']==0 and len(pids)==1:
                pid=pids[0]
                record['procBefore']=call(['cat',f'/proc/{pid}/stat'])
                record['domainBefore']=call(['cat',f'/proc/{pid}/attr/current'])
                record['signullBefore']=call(['kill','-0',pid])
                stop=call(['sh'],(f'kill -INT {pid} || exit 71\nn=0\nwhile [ -d /proc/{pid} ]; do\n'
                    f'if data=$(cat /proc/{pid}/stat 2>/dev/null); then printf "%s\\n" "$data"; '
                    f'else [ ! -d /proc/{pid} ] && break; exit 73; fi\n'
                    'n=$((n+1)); [ "$n" -lt 100 ] || exit 72; sleep 0.1; done\n'
                    f'[ ! -d /proc/{pid} ] || exit 74\nprintf "PROC_ABSENT\\n"\n').encode())
                record['stop']=stop
                record['procAfter']=call(['cat',f'/proc/{pid}/stat'])
                pulled=subprocess.run(['adb','pull',remote,str(OUT/(route+'.pftrace'))],capture_output=True,text=True,timeout=90)
                record['pull']=dict(exit=pulled.returncode,stdout=pulled.stdout,stderr=pulled.stderr)
                if pulled.returncode==0:
                    raw=(OUT/(route+'.pftrace')).read_bytes();record.update(traceSha256=hashlib.sha256(raw).hexdigest(),traceBytes=len(raw))
            cases.append(record);save('progress.json',cases)
        diagnostics=call(['logcat','-d','-b','all','-v','threadtime'],timeout=30)
        lines=[line for line in (diagnostics['stdout']+'\n'+diagnostics['stderr']).splitlines() if 'avc:' in line and any(s in line.lower() for s in ('perfetto','shell','traced'))]
        (OUT/'selinux-relevant.txt').write_text('\n'.join(lines[-100:])+'\n')
        report=dict(sourceSha=manifest['sourceSha'],runId=manifest['runId'],configSha256=CONFIG_SHA,cases=cases,noApkBuilt=True,
            nativeTerminalWindows=0,qualificationPassed=False,performanceMeasured=False,productionChanged=False)
        save('report.json',report)
        ci.notice('notice','Trace config transport diagnostic',json.dumps(dict(sourceSha=manifest['sourceSha'],runId=manifest['runId'],
            routes=[dict(route=c['route'],exactConfig=c['catSha256']==CONFIG_SHA,startExit=c['start']['exit'],error=c['start']['stderr'][:500],procBefore=c.get('procBefore'),signullBefore=c.get('signullBefore'),stop=c.get('stop'),procAfter=c.get('procAfter')) for c in cases],
            noApkBuilt=True,nativeTerminalWindows=0,qualificationPassed=False),separators=(',',':')))
    ci.emulator(operation=operation)
if __name__=='__main__':main()
