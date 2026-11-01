"""Verify complete delivery/source/CI/shard coverage; --recompute additionally reparses all32traces.
No network, Gradle, APK, or replacement sampling. The default checks saved recomputation receipts.
"""
import argparse,base64,collections,csv,hashlib,importlib.util,json,re,zlib
from pathlib import Path
HERE=Path(__file__).resolve().parent
RUN=41281700735;SOURCE='0fc31431f637b47912ad5b7f8a688b0fe703b2e9'
RESULT='6687a55df37c5418e8b40475bcbcc996285ef3dd25258be4f99c42e16d98e4e0'
def require(ok,why):
    if not ok:raise ValueError(why)
def read(p):return json.loads(p.read_bytes())
def sha(p):
    with p.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def summary_from_receipt(receipt):
    parts={};proof=None
    for job in receipt['jobs']:
        for row in job['annotations']:
            hit=re.fullmatch(r'A1 MAIN width performance evidence (\d+)/(\d+)',row.get('title',''))
            if hit:
                head,data=row['message'].split('\n',1);current=(int(hit[2]),re.fullmatch(r'zlib-base64 sha256=([a-f0-9]{64})',head)[1])
                require(proof is None or proof==current,'Mixed summary identity');proof=current
                require(int(hit[1]) not in parts,'Duplicate summary fragment');parts[int(hit[1])]=data
    require(proof and set(parts)==set(range(1,proof[0]+1)),'Missing CI summary fragment')
    raw=zlib.decompress(base64.b64decode(''.join(parts[i] for i in sorted(parts)),validate=True))
    require(hashlib.sha256(raw).hexdigest()==proof[1],'Summary checksum differs');return json.loads(raw)
def verify(root,originals=None,recompute=False):
    root=root.resolve();originals=(originals or root/'original').resolve()
    def file(name):
        relative=Path(name);require(not relative.is_absolute() and '..' not in relative.parts,'Unsafe capsule path')
        base=originals if relative.parts[0]=='original' else root
        path=base.joinpath(*relative.parts[1:]) if relative.parts[0]=='original' else base/relative
        require(path.resolve().is_relative_to(base) and not path.is_symlink(),'Unsafe capsule link')
        return path
    manifest=read(root/'SHA256SUMS.json')
    actual={p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file() and p.name!='SHA256SUMS.json' and '__pycache__' not in p.parts and (originals==root/'original' or not p.is_relative_to(root/'original'))}
    if originals!=root/'original':actual|={'original/'+p.relative_to(originals).as_posix() for p in originals.rglob('*') if p.is_file()}
    require(actual==set(manifest),'Capsule inventory differs')
    for name,item in manifest.items():
        path=file(name);require(path.suffix.lower() not in ('.apk','.aab','.jar','.so','.dex'),'Unexpected application binary')
        require(path.stat().st_size==item['bytes'] and sha(path)==item['sha256'],'Capsule hash mismatch:'+name)
    receipt=read(root/'run-41281700735-completed-receipt.json');run=receipt['run']
    require(run['id']==RUN and run['head_sha']==SOURCE and run['conclusion']=='success' and run['status']=='completed','Run source/status differs')
    summary=summary_from_receipt(receipt);require(summary==read(root/'completed-ci-summary.json'),'Saved summary differs from original check annotations')
    data_root=originals/'terminal-main-width-performance';doc=read(data_root/'results.json')
    require(sha(data_root/'results.json')==RESULT==summary['resultSha256'],'Not the original complete result')
    spec=importlib.util.spec_from_file_location('capsule_a1_verify',root/'verify_experiment.py');v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
    require(v.SOURCE.resolve().is_relative_to(root),'Verifier imported a source outside this capsule')
    plan=doc['plan'];require(plan['sourceSha']==SOURCE and int(plan['runId'])==RUN,'Manifest source/run mismatch')
    require(plan['protocol']==v.m.protocol() and plan['buildContractSha256']==v.m.BUILD_CONTRACT_SHA256,'Frozen contracts differ')
    v.m.build_contract();v.m.verify_sources(plan['manifests']);v.m.pair.verify_sources=v.m.verify_sources
    v.m.pair.verify_bundles(plan['manifests'],check_files=False,expected_identity=(SOURCE,str(RUN)))
    omissions={x['path']:x for x in read(root/'source-binary-omissions.json')}
    for name,value in plan['manifests']['legacy']['sources'].items():
        if name in omissions:require(omissions[name]['sha256']==value,'Binary source metadata differs')
        else:require(sha(root/'source'/name)==value,'Frozen base source differs:'+name)
    for name,item in plan['candidateSpec']['files'].items():require(sha(root/'source'/item['asset'])==item['candidate'],'Frozen candidate source differs')
    extraction=read(originals/'extraction.json');artifact=read(originals/'artifact-metadata.json')
    require(artifact['id']==13075880259 and artifact['workflow_run']['id']==RUN and artifact['workflow_run']['head_sha']==SOURCE and
        extraction['relayDigest']==artifact['digest'],'Source artifact relay differs')
    for item in extraction['selected']:
        path=originals/item['name'];require(sha(path)==item['sha256'] and path.stat().st_size==item['bytes'],'Selected original differs')
    shards=[];rows=[];notifications=collections.Counter();devices=[]
    for i,inv in enumerate(doc['invocations']):
        shard=read(root/'verified-shards'/f'slot-{i}.json')
        require(shard['verifiedInvocationSlots']==[i] and shard['invocationHashes']==[inv['fullResultSha256']] and shard['verifierSha256']==sha(root/'verify_experiment.py'),'Shard/verification source differs')
        require(shard['sourceSha']==SOURCE and shard['runId']==RUN and shard['resultSha256']==RESULT and shard['verifiedWindows']==252 and not shard['incompleteWindows'],'Shard incomplete or foreign')
        require(shard['gateFailures']==summary['gateFailures'] and shard['passesFrozenPerformanceScreen'] is False,'Shard acceptance differs')
        path=data_root/inv['fullResultFile'];require(sha(path)==inv['fullResultSha256'],'Full invocation hash differs')
        original=read(path);require({k:x for k,x in original.items() if k!='cases'}==inv['result'],'Light/full invocation differs');devices.append(original['device'])
        for method,group in zip(v.m.METHODS,original['classifications']):require(sha(path.parent/'latency-traces'/(method+'.pftrace'))==group['traceSha256'],'Referenced trace differs')
        with (root/'verified-shards'/f'slot-{i}.csv').open() as stream:part=list(csv.DictReader(stream))
        require(len(part)==252 and all(int(x['pair'])==inv['pair'] and x['arm']==inv['arm'] for x in part),'Shard CSV misbound')
        rows+=part;shards.append(shard);notifications.update(shard['notificationStates'])
    require(len(shards)==8 and all(x==devices[0] for x in devices),'Incomplete8slots or changed device')
    expected={(n,arm,case,f'r{rnd}-{cond}') for n,arms in enumerate(v.m.protocol()['matrix']['armOrder']) for arm in arms
        for case in v.m.protocol()['matrix']['cases'] for rnd,conditions in enumerate(v.m.protocol()['matrix']['conditionOrder']) for cond in conditions}
    require(len(rows)==2016 and {(int(x['pair']),x['arm'],x['case'],x['phase']) for x in rows}==expected,'Window coverage differs')
    require(sum(x['warmup']=='True' for x in rows)==96 and all(x['identityDecidable']=='True' and all(int(x[k])>0 for k in v.m.METRICS) for x in rows),'Window timing/classification differs')
    with (root/'all-windows.csv').open() as stream:require(list(csv.DictReader(stream))==rows,'Combined CSV differs')
    aggregate=v.m.aggregate(doc['invocations']);require(all(doc[k]==x==summary[k] for k,x in aggregate.items()),'Recomputed frozen gates differ')
    final=read(root/'final-verification.json');require(final['allRawIdentitiesVerified'] and final['verifiedWindows']==2016 and final['resultSha256']==RESULT and
        final['dataComplete'] and final['passesFrozenPerformanceScreen'] is False and final['productionAdopted'] is False,'Final verification misbound')
    require(final['notificationStates']==dict(notifications) and final['qualifiedAttempts']==sum(x['qualifiedAttempts'] for x in shards) and
        final['submittedAttempts']==sum(x['submittedAttempts'] for x in shards) and final['presentedAttempts']==sum(x['presentedAttempts'] for x in shards),'Attempt counts differ')
    require(final['gateFailures']==aggregate['gateFailures'] and not aggregate['passesFrozenPerformanceScreen'] and aggregate['dataComplete'],'Wrong final decision')
    if recompute:
        new,_=v.verify(data_root)
        for key in ('verifiedWindows','qualifiedAttempts','submittedAttempts','presentedAttempts','notificationStates','dataComplete','passesFrozenPerformanceScreen','gateFailures'):
            require(new[key]==final[key],'Full reparse disagrees:'+key)
    return dict(runId=RUN,sourceSha=SOURCE,verifiedWindows=2016,rawTraceFiles=32,completeShardCoverage=True,
        allFilesHashed=True,dataComplete=True,passesFrozenPerformanceScreen=False,gateFailureCount=len(aggregate['gateFailures']),
        rawTraceReparseThisInvocation=recompute,productionAdopted=False,
        note='Default checks saved full recomputation receipts and all original hashes; --recompute independently reparses32traces again. No build/network/resampling.')
def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=HERE);p.add_argument('--original-root',type=Path);p.add_argument('--recompute',action='store_true');a=p.parse_args()
    print(json.dumps(verify(a.root,a.original_root,a.recompute),indent=2))
if __name__=='__main__':main()
