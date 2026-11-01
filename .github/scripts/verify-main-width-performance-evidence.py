"""Recompute A1 frame identities and all frozen median/tail/worst gates from the same originals only."""
import argparse,collections,hashlib,importlib.util,json
from pathlib import Path
HERE=Path(__file__).resolve().parent
SOURCE=HERE/'source/.github/scripts/terminal-main-width-performance.py'
if not SOURCE.is_file():SOURCE=Path('/workspace/rikkahub-tool-ui-enhance/.github/scripts/terminal-main-width-performance.py')
spec=importlib.util.spec_from_file_location('offline_a1_performance',SOURCE)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
EXPECTED_SOURCE='0fc31431f637b47912ad5b7f8a688b0fe703b2e9'
EXPECTED_RUN=41281700735
def require(ok,message):
    if not ok:raise ValueError(message)
def digest(raw):return hashlib.sha256(raw).hexdigest()
def verify(root,slot=None):
    root=root.resolve();doc=json.loads((root/'results.json').read_bytes());plan=doc['plan'];p=m.protocol()
    require(doc['sourceSha']==plan['sourceSha']==EXPECTED_SOURCE and int(doc['runId'])==int(plan['runId'])==EXPECTED_RUN,'Not the one frozen A1 execution')
    require(plan['protocol']==p and plan['protocolSha256']==m.PROTOCOL_SHA256 and plan['freezeSha']==m.FREEZE_SHA,'Protocol/freeze identity differs')
    require(plan['buildContractSha256']==m.BUILD_CONTRACT_SHA256,'Wrong typed profile build qualification')
    m.build_contract()
    require(plan['candidateSpec']==m.candidate.spec()[0] and plan['productionChanged'] is False,'Candidate identity/adoption changed')
    m.verify_sources(plan['manifests']);m.pair.verify_sources=m.verify_sources
    m.pair.verify_bundles(plan['manifests'],check_files=False,expected_identity=(EXPECTED_SOURCE,str(EXPECTED_RUN)))
    # Own-DEX binary checks were performed in CI on the original signed target hashes. No APKs
    # are downloaded locally; recompute the complete symbolic payload and typed asset proof.
    profile_audit=m.load('offline_typed_profile','terminal-paired-profile-audit.py')
    assets={arm:json.loads((root/f'built-{arm}-assets.json').read_bytes()) for arm in ('legacy','candidate')}
    profiles={arm:json.loads((root/f'profile-{arm}.json').read_bytes()) for arm in assets}
    for arm in assets:
        symbols=json.loads((root/f'profile-{arm}-symbols.json').read_bytes())
        require(profile_audit.sha(profile_audit.canonical(sorted(symbols,key=lambda x:x['dex'])))==profiles[arm]['symbolicContentSha256'],'Profile symbolic original differs')
        require(profiles[arm]['apkSha256']==plan['manifests'][arm]['apks']['target']['sha256'] and profiles[arm]['sourceSha']==EXPECTED_SOURCE,'Profile APK/source not bound')
    qualification=profile_audit.qualify(profiles,assets)
    record=json.loads((root/'typed-build-qualification.json').read_bytes())
    require(record['sourceSha']==EXPECTED_SOURCE and int(record['runId'])==EXPECTED_RUN and record['qualification']==qualification and record['profiles']==profiles,'Typed qualification receipt differs')
    typed=dict(assets['legacy']);typed['assets/dexopt/baseline.prof']=dict(kind='own-dex-validated-prof010-symbolic-v1',symbolicContentSha256=profiles['legacy']['symbolicContentSha256'])
    require(digest(json.dumps(typed,sort_keys=True).encode())==plan['sharedAssetInventorySha256']==record['sharedAssetInventorySha256'],'Typed asset inventory hash differs')
    require(slot is None or type(slot) is int and 0<=slot<len(doc['invocations']),'Invalid verification shard')
    windows=0;attempts=0;submitted=0;presented=0;states=collections.Counter();no_submissions=[];missing=[];table=[]
    devices=[]
    selected=doc['invocations'] if slot is None else [doc['invocations'][slot]]
    for inv in selected:
        reference=Path(inv['fullResultFile']);require(not reference.is_absolute() and '..' not in reference.parts,'Unsafe invocation reference')
        path=root/reference;raw=path.read_bytes()
        require(len(raw)==inv['fullResultBytes'] and digest(raw)==inv['fullResultSha256'],'Original invocation bytes differ')
        original=json.loads(raw);require({k:v for k,v in original.items() if k!='cases'}==inv['result'],'Aggregate differs from original invocation')
        folder=path.parent;log='\n'.join((folder/(method+'.log')).read_text() for method in m.METHODS)
        cases,cleanup=m.validate_cases(log);require(cases==original['cases'] and cleanup==original['cleanup'],'Native logs differ from recorded cases')
        manifest=plan['manifests'][inv['arm']]
        require(original['installedApkHashes']=={role:value['sha256'] for role,value in manifest['apks'].items()},'Installed target/common driver mismatch')
        devices.append(original['device'])
        for method,case,group in zip(m.METHODS,cases,original['classifications']):
            trace_path=folder/'latency-traces'/(method+'.pftrace');raw=trace_path.read_bytes()
            require(digest(raw)==group['traceSha256'] and len(raw)==group['traceBytes'],'Trace bytes differ')
            trace=m.identity.parse_trace(raw,case['pid']);index=m.identity.FrameIndex(trace);queues=m.observers(case)
            rows=[dict(phase=w['phase'],index=w['index'],position=w['position'],condition=w['condition'],warmup=w['warmup'],
                diagnostics=m.diagnostics(w,trace),**m.identity.classify_window(w,index,queues)) for w in case['samples']]
            require(rows==group['rows'] and trace['faults']==group['traceFaults'] and trace['sequenceInitialMarkers']==group['sequenceInitialMarkers'],'Recomputed exact identity differs')
            require(group['callbackDropped']==[sum(x['dropped'] for x in q) for q in queues],'Observer dropped inventory differs')
            for row in rows:
                windows+=1
                if not m.complete_window(row):missing.append(dict(pair=inv['pair'],arm=inv['arm'],case=case['case'],phase=row['phase']))
                flat=dict(pair=inv['pair'],position=inv['position'],arm=inv['arm'],case=case['case'],phase=row['phase'],condition=row['condition'],warmup=row['warmup'],
                    identityDecidable=row['identityDecidable'],**{key:row.get(key) for key in m.METRICS},**row['diagnostics']);table.append(flat)
                for attempt in row['attempts']:
                    if attempt['attempt']!='QUALIFIED':continue
                    attempts+=1;states[attempt.get('metrics',{}).get('state','UNLINKED')]+=1
                    submitted+=attempt['submission']=='SUBMITTED';presented+=attempt['presentation']=='PRESENTED'
                    if attempt['submission']=='NO_SUBMISSION':no_submissions.append(dict(pair=inv['pair'],arm=inv['arm'],case=case['case'],phase=row['phase'],
                        eventSeq=attempt['eventSeq'],token=attempt.get('token'),reason=attempt.get('reason')))
    require(all(device==devices[0] for device in devices),'Guest display/IME/fingerprint changed between arms')
    # Always recompute the complete supplied aggregate, but a shard is NOT final raw evidence.
    result=m.aggregate(doc['invocations']);require(all(doc[k]==v for k,v in result.items()),'Frozen performance gate recomputation differs')
    require(windows==(p['matrix']['windows'] if slot is None else 252) and result['warmups']==96 and result['formalWindows']==1920,'Window inventory differs')
    return dict(sourceSha=EXPECTED_SOURCE,runId=EXPECTED_RUN,protocolSha256=m.PROTOCOL_SHA256,buildContractSha256=m.BUILD_CONTRACT_SHA256,
        rawAssetsByteIdentical=qualification['rawAssetsByteIdentical'],typedBuildQualification=True,resultSha256=digest((root/'results.json').read_bytes()),
        verifiedWindows=windows,qualifiedAttempts=attempts,submittedAttempts=submitted,presentedAttempts=presented,notificationStates=dict(states),
        noSubmissionAttempts=no_submissions,incompleteWindows=missing,dataComplete=result['dataComplete'],passesFrozenPerformanceScreen=result['passesFrozenPerformanceScreen'],
        gateFailures=result['gateFailures'],productionAdopted=False,
        verifiedInvocationSlots=list(range(len(doc['invocations']))) if slot is None else [slot],
        invocationHashes=[doc['invocations'][i]['fullResultSha256'] for i in (range(len(doc['invocations'])) if slot is None else [slot])],
        verifierSha256=digest(Path(__file__).read_bytes()),allRawIdentitiesVerified=slot is None,
        verification='offline same-original recomputation;no network/build/replacement data'),table
def main():
    parser=argparse.ArgumentParser();parser.add_argument('root',type=Path);parser.add_argument('--output',type=Path);parser.add_argument('--csv',type=Path);parser.add_argument('--slot',type=int);args=parser.parse_args()
    report,table=verify(args.root,args.slot)
    if args.output:args.output.write_text(json.dumps(report,indent=2)+'\n')
    if args.csv:
        import csv
        with args.csv.open('w',newline='') as stream:
            writer=csv.DictWriter(stream,fieldnames=list(table[0]));writer.writeheader();writer.writerows(table)
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
