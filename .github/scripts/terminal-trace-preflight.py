"""Short fixed trace-transport diagnosis on a disposable guest: no build/APK/baseline resampling."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import pwd
import re
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/terminal-trace-preflight'
TREE='8caf50d5c40e228f1d5bcc09d3db505757568b68'
PRODUCTION='55db111129c662067345aa99540b506de305da37'
CASES=('foreground-file','foreground-buffer','background-wait')
spec=importlib.util.spec_from_file_location('preflight_wire',Path(__file__).with_name('terminal-full-virtual-latency.py'))
wire=importlib.util.module_from_spec(spec);spec.loader.exec_module(wire)
require=wire.require

def note(level,title,value):
    text=str(value)[:2600].replace('%','%25').replace('\r','%0D').replace('\n','%0A')
    print(f'::{level} title={title}::{text}',flush=True)
def save(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
def sha(raw):return hashlib.sha256(raw).hexdigest()
def run(command,timeout=25,input_text=None):
    try:
        r=subprocess.run(command,input=input_text,capture_output=True,text=True,timeout=timeout)
        return dict(exit=r.returncode,stdout=r.stdout,stderr=r.stderr,timedOut=False)
    except subprocess.TimeoutExpired as e:
        def text(value):return value.decode(errors='replace') if isinstance(value,bytes) else value or ''
        return dict(exit=None,stdout=text(e.stdout),stderr=text(e.stderr),timedOut=True)
def adb(*args,timeout=25,input_text=None):return run(['adb',*args],timeout,input_text)
def config(case):
    require(case in CASES,'Unknown fixed trace case')
    original=(ROOT/'benchmarks/terminal-full-virtual-latency/perfetto.pbtxt').read_text()
    value=original.replace('duration_ms: 540000','duration_ms: 2000').replace('atrace_apps: "me.rerere.rikkahub.dev.next.terminaltest"',
        'atrace_apps: "com.android.settings"\n  atrace_apps: "com.android.systemui"')
    if case=='foreground-buffer':
        value=value.replace('write_into_file: true','write_into_file: false').replace('file_write_period_ms: 1000\n','').replace('max_file_size_bytes: 67108864\n','')
    return value

def inspect_trace(path,pid):
    raw=path.read_bytes();require(0<len(raw)<=32*1024*1024,'Trace empty/oversize')
    decoded=wire.frame_timeline(raw,pid)
    return dict(bytes=len(raw),sha256=sha(raw),**{k:v for k,v in decoded.items() if k!='frames'})

def one_case(case,run_id,pid):
    folder=OUT/case;folder.mkdir(exist_ok=False)
    text=config(case);(folder/'config.pbtxt').write_text(text)
    remote=f'/data/misc/perfetto-traces/preflight-{run_id}-{case}.pftrace'
    result=dict(case=case,configSha256=sha(text.encode()),remotePath=remote,settingsPid=pid,baselineSamples=0,
        durationMs=2000,collectionAttempted=False)
    # Foreground mode retains the COMPLETE native diagnostics which daemonization can hide.
    command=['adb','shell','perfetto','--txt','-c','-','-o',remote]
    if case=='background-wait':command.append('--background-wait')
    log=folder/'perfetto.log';proc=None
    try:
        with log.open('wb') as stream:
            proc=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=stream,stderr=subprocess.STDOUT)
            proc.stdin.write(text.encode());proc.stdin.close();proc.stdin=None
            # Fixed stimulus only; no app accounts, no user terminal data, no performance samples.
            time.sleep(.35)
            stimulus=adb('shell','input','swipe','700','1750','700','650','350')
            result['stimulus']=stimulus
            result['reverseStimulus']=adb('shell','input','swipe','700','650','700','1750','350')
            try:result['commandExit']=proc.wait(timeout=35 if case=='background-wait' else 15)
            except subprocess.TimeoutExpired:
                proc.terminate()
                try:proc.wait(timeout=5)
                except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=5)
                result['commandTimedOut']=True;result['commandExit']=proc.returncode
        if case=='background-wait':
            # A missing PID AFTER the2s fixed duration is not a capture failure. Still retrieve.
            time.sleep(2.2)
            pids=re.findall(r'^([0-9]+)$',log.read_text(errors='replace'),re.M)
            result['reportedPids']=pids
            if len(pids)==1:result['pidAfterDuration']=adb('shell','kill','-0',pids[0])
    finally:
        # Crucial: do not let exit/kill status suppress the original output file retrieval.
        result['collectionAttempted']=True
        result['remoteStat']=adb('shell','ls','-l',remote)
        result['pull']=adb('pull',remote,str(folder/'trace.pftrace'),timeout=45)
        if (folder/'trace.pftrace').is_file():
            try:result['trace']=inspect_trace(folder/'trace.pftrace',pid)
            except (ValueError,KeyError,TypeError) as error:result['traceDecodeError']=str(error)
        result['logSha256']=sha(log.read_bytes())
        result['logExcerpt']=log.read_text(errors='replace')[-2200:]
        result['captureVerified']=result.get('trace',{}).get('completeFrames',0)>0
        save(folder/'receipt.json',result)
        note('notice' if result['captureVerified'] else 'warning','Trace transport '+case,
             json.dumps(result,separators=(',',':'))[:2600])
    return result

def validate(report):
    require(report['productionTree']==TREE and report['productionSha']==PRODUCTION and report['noApkBuilt'] is True and
        report['baselineResampled'] is False,'Preflight scope changed')
    cases=report['cases'];require(len(cases)==3 and tuple(r['case'] for r in cases)==CASES,'Fixed diagnostic matrix differs')
    for row in cases:
        require(row['durationMs']==2000 and row['baselineSamples']==0 and row['collectionAttempted'] is True,
                'Missing original trace attempt')
        if row['captureVerified']:
            require(row['trace']['completeFrames']>0 and row['pull']['exit']==0 and row['trace']['bytes']>0,'False capture success')
    require(report['transportAvailable']==any(r['captureVerified'] for r in cases),'Trace transport conclusion differs')
    return report

def guest_work():
    sha_id=os.environ['GITHUB_SHA'];run_id=os.environ['GITHUB_RUN_ID']
    require(re.fullmatch('[a-f0-9]{40}',sha_id) and re.fullmatch('[0-9]+',run_id),'Exact source/run required')
    output=subprocess.check_output(['git','-c','core.fileMode=false','rev-parse','HEAD:app/src/main'],cwd=ROOT,text=True).strip()
    require(output==TREE,'Production changed before transport diagnosis')
    env={name:adb('shell',*args) for name,args in (
        ('identity',('id',)),('fingerprint',('getprop','ro.build.fingerprint')),('perfettoVersion',('perfetto','--version')),
        ('serviceQuery',('perfetto','--query')),('outputDirectory',('ls','-ldZ','/data/misc/perfetto-traces')))}
    save(OUT/'environment.json',env)
    started=adb('shell','am','start','-W','-a','android.settings.SETTINGS')
    save(OUT/'settings-launch.json',started);require(started['exit']==0,'Disposable Settings window failed to launch')
    pids=adb('shell','pidof','com.android.settings')['stdout'].strip().split()
    require(len(pids)==1 and pids[0].isdigit(),'Missing synthetic target Settings PID')
    report=dict(suite='terminal-trace-transport-v1',sourceSha=sha_id,runId=run_id,productionSha=PRODUCTION,productionTree=TREE,
        noApkBuilt=True,baselineResampled=False,phoneMeasured=False,appPerformanceMeasured=False,
        userFeedback='55db111 APK feels slightly slow overall; renderer/page/device details not yet specified.',
        environment=env,cases=[one_case(case,run_id,int(pids[0])) for case in CASES],
        note='Two-second Settings-window trace transport only,not application performance or a fix for user-reported slowdown.')
    report['transportAvailable']=any(r['captureVerified'] for r in report['cases'])
    validate(report);save(OUT/'report.json',report)
    wire.legacy.compact('Terminal trace preflight',(OUT/'report.json').read_bytes())
    require(report['transportAvailable'],'No verified trace transport; preserve all three focused native diagnostics')

def emulator():
    sys.path.insert(0,str(ROOT/'benchmarks'));import production_emulator as guest
    OUT.mkdir(parents=True,exist_ok=True)
    state=ROOT/'artifacts/trace-preflight-state';state.mkdir(parents=True,exist_ok=True)
    env=dict(os.environ);sdk=Path(env['ANDROID_HOME'])
    env.update(ANDROID_SDK_ROOT=str(sdk),ANDROID_AVD_HOME=str(state/'avds'),ANDROID_USER_HOME=str(state/'android'),ANDROID_SERIAL=guest.SERIAL)
    for k in ('ANDROID_AVD_HOME','ANDROID_USER_HOME'):Path(env[k]).mkdir(exist_ok=True)
    env['PATH']=str(sdk/'platform-tools')+os.pathsep+env['PATH'];user=pwd.getpwuid(os.getuid()).pw_name
    tools=sdk/'cmdline-tools/latest/bin';process=None
    try:
        guest.logged([str(tools/'sdkmanager'),'--install','emulator',guest.IMAGE],OUT/'sdk-install.log',env,300)
        binary=sdk/'emulator/emulator'
        acceleration=subprocess.run(guest.group_command(binary,['-accel-check'],env,user),capture_output=True,text=True,timeout=30)
        acceleration.stdout=(acceleration.stdout or '')+(acceleration.stderr or '');(OUT/'acceleration.txt').write_text(acceleration.stdout)
        guest.require_acceleration(acceleration)
        guest.logged([str(tools/'avdmanager'),'create','avd','--force','-n','tracePreflight','--package',guest.IMAGE,'--device','Nexus 6'],OUT/'avd-create.log',env,90,'no\n')
        with (Path(env['ANDROID_AVD_HOME'])/'tracePreflight.avd/config.ini').open('a') as f:f.write('\nhw.cpu.ncore=2\nhw.ramSize=4096M\nhw.keyboard=yes\n')
        options=['-port','5554','-avd','tracePreflight','-no-window','-accel','on','-gpu','swiftshader_indirect','-noaudio','-no-boot-anim','-no-snapshot','-camera-back','none']
        with (OUT/'emulator.log').open('w') as f:
            process=subprocess.Popen(guest.group_command(binary,options,env,user),env=env,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
            guest.wait_for_boot(process,env);os.environ.update(PATH=env['PATH'],ANDROID_SERIAL=guest.SERIAL)
            adb('shell','input','keyevent','82');guest_work()
    finally:guest.stop_emulator(process,env)

if __name__=='__main__':
    try:emulator()
    except (ValueError,OSError,KeyError,subprocess.SubprocessError) as error:
        note('error','Trace preflight stopped',type(error).__name__+': '+str(error));raise SystemExit(1)
