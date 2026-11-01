"""Offline verification of the one B1 mechanism run. No GitHub, Gradle or resampling."""
import argparse,base64,collections,hashlib,json,re,zlib
from pathlib import Path
import xml.etree.ElementTree as ET
RUN=41295383061
SOURCE='f9c7e03643be06625c164ee0c4ef8359063941c1'
IDENTITY='c8c2475077b82b23afad953a7b038e2f060739d9ac2a960ee6908a437c29be9d'
def sha(raw):return hashlib.sha256(raw).hexdigest()
def require(ok,why):
    if not ok:raise ValueError(why)
def read(path):return json.loads(path.read_bytes())
def junit(paths):
    found=set();count=0
    for path in paths:
        for case in ET.parse(path).getroot().iter('testcase'):
            key=(case.get('classname'),case.get('name'));require(key not in found,'Duplicate JUnit method')
            require(all(case.find(tag) is None for tag in ('failure','error','skipped')),'JUnit did not pass:'+str(key))
            count+=1;found.add(key)
    require(count==len(found),'JUnit duplicates');return found
def annotations(receipt,title):
    fragments={};identity=None
    for job in receipt['jobs']:
        for row in job['annotations']:
            hit=re.fullmatch(re.escape(title)+r' (\d+)/(\d+)',row.get('title',''))
            if hit:
                header,data=row['message'].split('\n',1)
                current=(int(hit[2]),re.fullmatch(r'zlib-base64 sha256=([a-f0-9]{64})',header)[1])
                require(identity is None or identity==current,'Mixed annotation identity');identity=current
                require(int(hit[1]) not in fragments,'Duplicate annotation fragment');fragments[int(hit[1])]=data
    require(identity and set(fragments)==set(range(1,identity[0]+1)),'Incomplete annotation evidence')
    raw=zlib.decompress(base64.b64decode(''.join(fragments[n] for n in sorted(fragments)),validate=True))
    require(sha(raw)==identity[1],'Annotation checksum mismatch');return json.loads(raw)
def verify(root):
    root=root.resolve();manifest=read(root/'SHA256SUMS.json')
    for name,item in manifest.items():
        path=(root/name).resolve();require(path.is_relative_to(root) and not path.is_symlink(),'Unsafe manifest path')
        raw=path.read_bytes();require(len(raw)==item['bytes'] and sha(raw)==item['sha256'],'File integrity mismatch:'+name)
    source=root/'source';base=source/'benchmarks/terminal-physical-metrics-b1'
    identity=read(base/'identity.json');require(sha((base/'identity.json').read_bytes())==IDENTITY,'Different B1 candidate')
    protocol=read(base/'protocol.json');require(sha((base/'protocol.json').read_bytes())==identity['protocolSha256'],'Different frozen protocol')
    hashes={name:item['candidate'] for name,item in identity['files'].items()}
    for name,item in identity['files'].items():
        require(sha((source/item['asset']).read_bytes())==item['candidate'],'Candidate source mismatch:'+name)
        require(sha((source/name).read_bytes())==item['base'],'Base source mismatch:'+name)
    for item in protocol['unitInventory']+protocol['deviceInventory']+[protocol['candidateUnit']]:
        require(sha((source/item.get('asset',item['file'])).read_bytes())==item['sha256'],'Test source mismatch:'+item['file'])
    receipt=read(root/'receipts/run-41295383061-receipt.json');run=receipt['run']
    require(run['id']==RUN and run['head_sha']==SOURCE and run['head_branch']==protocol['branch'] and
        run['path']=='.github/workflows/terminal-pipeline-tests.yml' and run['conclusion']=='success','Source run identity mismatch')
    executed=[x for x in receipt['jobs'] if x['conclusion']!='skipped']
    require(len(executed)==2 and all(x['conclusion']=='success' for x in executed),'Unexpected executed jobs')
    counts={}
    for arm,title,inventory in (
        ('jvm','B1 physical metrics JVM evidence',protocol['unitInventory']+[protocol['candidateUnit']]),
        ('component','B1 physical metrics device evidence',protocol['deviceInventory'])):
        folder=root/'original'/arm;metadata=read(folder/'artifact-metadata.json');extraction=read(folder/'extraction.json')
        require(metadata['workflow_run']['id']==RUN and metadata['workflow_run']['head_sha']==SOURCE,'Artifact not source bound')
        require(extraction['artifactId']==metadata['id'] and extraction['sourceSha']==SOURCE and extraction['relayDigest']==metadata['digest'],'Relay identity mismatch')
        selected={item['name']:item for item in extraction['selected']}
        for name,item in selected.items():
            path=(folder/name).resolve();require(path.is_relative_to(folder.resolve()),'Unsafe original path')
            raw=path.read_bytes();require(sha(raw)==item['sha256'] and len(raw)==item['bytes'],'Extracted member hash mismatch')
            require(path.suffix.lower() not in ('.apk','.aab'),'Unexpected application artifact')
        reports=list(folder.rglob('report.json'));require(len(reports)==1,'Ambiguous arm report')
        report=read(reports[0]);summary=annotations(receipt,title)
        require({k:v for k,v in report.items() if k!='cases'}==summary,'Artifact/check annotation disagreement')
        require(report['sourceSha']==SOURCE and int(report['runId'])==RUN and report['identitySha256']==IDENTITY and
            report['protocolSha256']==identity['protocolSha256'] and report['candidateHashes']==hashes,'Report source identity mismatch')
        require(report['passed'] and not report['productionChanged'] and not report['performanceMeasured'] and not report['formalReleaseBuilt'],'Report status scope mismatch')
        expected={(x['classname'],m) for x in inventory for m in x['methods']}
        actual=junit(folder.rglob('*TEST-*.xml'));recorded={(x['classname'],x['name']) for x in report['cases']}
        require(expected==actual==recorded and len(actual)==report['count'],'Exact JUnit inventory mismatch')
        counts[arm]=len(actual)
        if arm=='component':
            logpaths=list(folder.rglob('lifecycle-logcat.txt'));require(len(logpaths)==1,'Ambiguous physical metrics log')
            # Reuse only the frozen source-bound pure scene validator; never call guard/build/device.
            import importlib.util
            loader=importlib.util.spec_from_file_location('b1_capsule_scenes',source/'.github/scripts/terminal-physical-metrics-b1.py')
            validator=importlib.util.module_from_spec(loader);loader.loader.exec_module(validator)
            rows=validator.scenes(logpaths[0].read_text(),protocol)
            require(rows==report['scenes'],'Scene report differs from original logcat')
            copied=read(next(folder.rglob('fixture-source.json')))['copiedSources']
            require(set(hashes)<=set(copied),'Component fixture missed candidate source')
            for name,value in copied.items():
                expected=hashes.get(name) or sha((source/name).read_bytes())
                require(value==expected,'Generated component source differs:'+name)
    require(counts==dict(jvm=529,component=28),'Method total mismatch')
    return dict(sourceSha=SOURCE,runId=RUN,baseProductionSha=protocol['baseProductionSha'],candidateIdentitySha256=IDENTITY,
        protocolSha256=identity['protocolSha256'],jvmMethods=529,deviceMethods=28,deviceScenes=24,passed=True,
        performanceMeasured=False,productionAdopted=False,formalReleaseBuilt=False,originalsIndependentlyVerified=True)
def main():
    parser=argparse.ArgumentParser();parser.add_argument('root',nargs='?',type=Path,default=Path(__file__).resolve().parent);a=parser.parse_args()
    print(json.dumps(verify(a.root),indent=2))
if __name__=='__main__':main()
