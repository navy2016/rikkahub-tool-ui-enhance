#!/usr/bin/env python3
"""User-invoked, read-only ADB observation. No install, compile, resets, logs, screenshots or chat text."""
import argparse
import csv
import hashlib
import io
import json
import math
from pathlib import Path
import re
import statistics
import subprocess
import time

PACKAGE='me.rerere.rikkahub.dev.next.mod'
VERSIONS={'7c5c3904ec355eb190b88ebbc4a2f2b260a76748661094658428b4f40a6700a1':'212b93e',
          '5fdaceacfbfaff5d378f9b2ac23070f009c6bf347be42d1561fb53e6080c6f4c':'55db111'}

def frame_rows(text):
    """Keep supported numeric fields only; diagnostics/window names are never written."""
    header=None;rows=[];window='unspecified'
    for line in text.splitlines():
        line=line.strip()
        if line.startswith('Window:'):
            window=hashlib.sha256(line.encode()).hexdigest()[:16]
        if line.startswith('Flags,'):
            header=line.rstrip(',').split(',');continue
        if not header or not re.fullmatch('[0-9, -]+',line):continue
        parts=line.rstrip(',').split(',')
        if len(parts)!=len(header):continue
        try:values={k:int(v) for k,v in zip(header,parts)}
        except ValueError:continue
        if not {'Flags','IntendedVsync','FrameCompleted'}<=values.keys():continue
        row={k:v for k,v in values.items() if re.fullmatch('[A-Za-z0-9_]+',k)}
        row['windowToken']=window;rows.append(row)
    return rows

def summarize(before,after):
    def identity(r):return (r['windowToken'],r['IntendedVsync'],r.get('Vsync'),r['FrameCompleted'])
    seen={identity(r) for r in before};fresh=[]
    for r in after:
        ident=identity(r)
        if ident in seen:continue
        seen.add(ident);fresh.append(r)
    valid=[];rejected=[]
    for r in fresh:
        start,end=r['IntendedVsync'],r['FrameCompleted']
        if r['Flags']!=0 or start<=0 or end<start or end>=2**63-1:
            rejected.append(r);continue
        valid.append((end-start)/1e6)
    ordered=sorted(valid)
    return dict(newFrameRecords=fresh,flaggedOrIncomplete=rejected,validFrames=len(valid),
        durationMs=dict(median=statistics.median(ordered),p95=ordered[math.ceil(.95*len(ordered))-1],maximum=max(ordered)) if ordered else None,
        note='Bounded gfxinfo ring only,not every frame in the interval;wholewindow,not input/IME animation latency. Flagged/incomplete records retained.')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--phase',required=True,choices=('chat','settings','terminal-ime'))
    p.add_argument('--seconds',type=int,default=30)
    p.add_argument('--serial',help='Optional already-authorized ADB device; not saved in results')
    p.add_argument('--out',type=Path,default=Path('phone-observation'))
    a=p.parse_args()
    if not 10<=a.seconds<=60:p.error('--seconds must be10..60')
    base=['adb']+(['-s',a.serial] if a.serial else [])
    def adb(*cmd,timeout=20):
        r=subprocess.run(base+list(cmd),capture_output=True,text=True,timeout=timeout)
        return r.returncode,r.stdout,r.stderr
    status,_,_=adb('get-state')
    if status:raise SystemExit('Connect and authorize exactly one phone first. No pairing/permissions are changed by this script.')
    status,paths,_=adb('shell','pm','path',PACKAGE)
    apks=[s[8:] for s in paths.splitlines() if s.startswith('package:')]
    if status or len(apks)!=1:raise SystemExit('Expected one installed base APK for the exact package; no install/change performed.')
    code,digest,_=adb('shell','sha256sum',apks[0]);digest=digest.split()[0] if digest.split() else ''
    if code or digest not in VERSIONS:raise SystemExit('Installed APK is not either exact reference build; observation stopped without changes.')
    label=VERSIONS[digest]
    a.out.mkdir(parents=True,exist_ok=True)
    dest=a.out/f'{label}-{a.phase}-{time.time_ns()}.json'
    def properties():
        return {name:adb('shell','getprop',name)[1].strip() for name in ('ro.product.manufacturer','ro.product.model','ro.build.version.release','ro.build.version.sdk')}
    def battery():
        text=adb('shell','dumpsys','battery')[1]
        return {k:int(m[1]) for k in ('level','temperature','status') if (m:=re.search(r'^\s*'+k+r':\s*(-?\d+)\s*$',text,re.M))}
    def compilation():
        code,text,_=adb('shell','pm','art','dump',PACKAGE)
        if code:text=adb('shell','dumpsys','package',PACKAGE)[1]
        # Never save complete package dumps or unrelated app metadata.
        return dict(pmArtSupported=code==0,fields=[line.strip() for line in text.splitlines()
            if re.search(r'\[(?:status|reason|primary-abi)=|compiler-filter|compilation-reason',line)][:24])
    scales={key:adb('shell','settings','get','global',key)[1].strip() for key in ('window_animation_scale','transition_animation_scale','animator_duration_scale')}
    record=dict(suite='read-only-phone-observation-v1',version=label,apkSha256=digest,phase=a.phase,seconds=a.seconds,
        device=properties(),animationScales=scales,compilation=compilation(),batteryBefore=battery(),
        mutatingCommandsUsed=False,chatTextCollected=False,apkDownloaded=False)
    code,before,_=adb('shell','dumpsys','gfxinfo',PACKAGE,'framestats')
    if code:raise SystemExit('gfxinfo unavailable; no resets or setting changes attempted.')
    print(f'{label}: for {a.seconds}s, use {a.phase} normally. Do not change animation/refresh settings or clear data.')
    time.sleep(a.seconds)
    code,after,_=adb('shell','dumpsys','gfxinfo',PACKAGE,'framestats')
    record.update(batteryAfter=battery(),frameReadSucceeded=code==0)
    record['frames']=summarize(frame_rows(before),frame_rows(after)) if code==0 else dict(error='gfxinfo read failed')
    dest.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
    print('Saved:',dest);print('No data cleared, no APK transferred, no compilation or settings changed.')

if __name__=='__main__':main()
