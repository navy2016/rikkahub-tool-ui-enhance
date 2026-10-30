"""Read-only prof010/profm002↔DEX identity audit of the existing signed APKs; no install/compile."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import zipfile
import zlib

EXPECTED={'reference':('212b93e04980b1087118007bd59da010a15435d8','7c5c3904ec355eb190b88ebbc4a2f2b260a76748661094658428b4f40a6700a1'),
          'candidate':('55db111129c662067345aa99540b506de305da37','5fdaceacfbfaff5d378f9b2ac23070f009c6bf347be42d1561fb53e6080c6f4c')}
def require(ok,msg):
    if not ok:raise ValueError(msg)
def sha(raw):return hashlib.sha256(raw).hexdigest()
class Reader:
    def __init__(self,raw):self.raw=raw;self.at=0
    def take(self,n):
        require(0<=n<=len(self.raw)-self.at,'Truncated binary profile');value=self.raw[self.at:self.at+n];self.at+=n;return value
    def number(self,n):return int.from_bytes(self.take(n),'little')
    def done(self):require(self.at==len(self.raw),'Trailing profile content')
def unpack(raw,magic,version,count_bytes):
    r=Reader(raw);require(r.take(4)==magic and r.take(4)==version,'Unsupported profile encoding')
    count=r.number(count_bytes);size=r.number(4);compressed=r.number(4)
    require(count>0 and 0<size<16*1024*1024 and compressed==len(raw)-r.at,'Invalid bounded profile dimensions')
    packed=r.take(compressed);r.done();d=zlib.decompressobj();body=d.decompress(packed,size+1)
    require(d.eof and not d.unused_data and not d.unconsumed_tail and len(body)==size,'Profile zlib length/stream mismatch')
    return count,Reader(body)
def key(value):
    result=value.replace(':','!').split('!')[-1]
    return 'classes.dex' if result.endswith('.apk') else result
def ids(reader,count):
    result=[];last=0
    for _ in range(count):
        last+=reader.number(2);require(not result or last>result[-1],'Repeated/nonincreasing profile class index');result.append(last)
    return result
def profile(raw):
    count,r=unpack(raw,b'pro\0',b'010\0',1);rows=[]
    for _ in range(count):
        length=r.number(2);classes=r.number(2);hot_size=r.number(4);checksum=r.number(4);methods=r.number(4)
        require(0<length<=1024 and 0<methods<=65536,'Invalid profile DEX dimensions')
        name=r.take(length).decode();rows.append(dict(key=name,dex=key(name),classes=classes,hotSize=hot_size,checksum=checksum,numMethodIds=methods))
    require(len({v['dex'] for v in rows})==count,'Duplicate profile DEX key')
    for row in rows:
        hot=Reader(r.take(row['hotSize']));last=0;hot_methods=[]
        while hot.at<len(hot.raw):
            last+=hot.number(2);require(last<row['numMethodIds'] and (not hot_methods or last>hot_methods[-1]),'Profile hot method outside DEX')
            hot_methods.append(last)
            for _ in range(hot.number(2)):
                hot.number(2);maps=hot.number(1)
                if maps not in (6,7):
                    for _ in range(maps):
                        dex_index=hot.number(1);require(dex_index<count,'Inline cache DEX index invalid')
                        hot.take(hot.number(1)*2)
        hot.done();classes=ids(r,row['classes']);bits=r.take((row['numMethodIds']*2+7)//8)
        def flagged(offset):return {i for i in range(row['numMethodIds']) if bits[(i+offset)//8]&(1<<((i+offset)%8))}
        startup=flagged(0);post=flagged(row['numMethodIds']);all_methods=set(hot_methods)|startup|post
        row.update(classTypeIds=classes,hotMethods=len(hot_methods),startupMethods=len(startup),postStartupMethods=len(post),profiledMethods=len(all_methods))
    r.done();return rows
def metadata(raw):
    count,r=unpack(raw,b'prm\0',b'002\0',2);rows=[]
    for _ in range(count):
        index=r.number(2);length=r.number(2);require(0<length<=1024,'Metadata key invalid')
        name=r.take(length).decode();types=r.number(4);count_classes=r.number(2)
        rows.append(dict(index=index,key=name,dex=key(name),typeIdCount=types,classDefIds=ids(r,count_classes)))
    r.done();require({v['index'] for v in rows}==set(range(count)) and len({v['dex'] for v in rows})==count,'Metadata DEX index inventory differs')
    return rows
def dex_header(raw):
    require(len(raw)>=112 and raw[:4]==b'dex\n' and raw[7]==0,'Invalid DEX header')
    checksum=struct.unpack_from('<I',raw,8)[0]
    require(checksum==(zlib.adler32(raw[12:])&0xffffffff) and raw[12:32]==hashlib.sha1(raw[32:]).digest(),'DEX internal checksum/signature mismatch')
    size,header,endian=struct.unpack_from('<III',raw,32);require(size==len(raw) and header==112 and endian==0x12345678,'Unexpected DEX container layout')
    return dict(checksum=checksum,typeIds=struct.unpack_from('<I',raw,64)[0],methodIds=struct.unpack_from('<I',raw,88)[0],classDefs=struct.unpack_from('<I',raw,96)[0],sha256=sha(raw),bytes=len(raw))
def audit(path,arm,out):
    raw=path.read_bytes();source,expected=EXPECTED[arm];require(sha(raw)==expected,'Different existing signed APK')
    with zipfile.ZipFile(path) as z:
        blobs={n:z.read(n) for n in ('assets/dexopt/baseline.prof','assets/dexopt/baseline.profm')}
        entries=profile(blobs['assets/dexopt/baseline.prof']);meta=metadata(blobs['assets/dexopt/baseline.profm'])
        require({r['dex'] for r in entries}=={r['dex'] for r in meta},'Profile/metadata DEX keys differ')
        for p in entries:
            h=dex_header(z.read(p['dex']));m=next(v for v in meta if v['dex']==p['dex'])
            require(p['checksum']==h['checksum'] and p['numMethodIds']==h['methodIds'],'Profile does not match installed DEX checksum/method count')
            require(m['typeIdCount']==h['typeIds'] and all(i<h['typeIds'] for i in p['classTypeIds']) and
                all(i<h['classDefs'] for i in m['classDefIds']),'Profile class/type indices do not match DEX')
            p['dexHeader']=h;p['metadataClassCount']=len(m['classDefIds']);p['matchesDex']=True
        for name,data in blobs.items():(out/(arm+'-'+Path(name).name)).write_bytes(data)
    return dict(sourceSha=source,apkSha256=expected,profileVersion='010',metadataVersion='002',dex=entries,matched=True,
        profileFiles={n:dict(bytes=len(b),sha256=sha(b)) for n,b in blobs.items()})
def main():
    p=argparse.ArgumentParser();p.add_argument('--reference',type=Path,required=True);p.add_argument('--candidate',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    a.out.mkdir(parents=True,exist_ok=True);report=dict(suite='signed-release-profile-audit-v1',phoneArtStateMeasured=False,apkRebuilt=False)
    for arm in EXPECTED:
        paths=list(getattr(a,arm).rglob('*.apk'));require(len(paths)==1,'Expected one exact APK per arm')
        report[arm]=audit(paths[0],arm,a.out)
    report['note']='Matching shipped profile/DEX proves package consistency only,not installed/compiled profile state or absence of a performance regression.'
    (a.out/'profile-audit.json').write_text(json.dumps(report,indent=2)+'\n')
    spec=importlib.util.spec_from_file_location('profile_evidence',Path(__file__).with_name('terminal-tui-history-reports.py'))
    publish=importlib.util.module_from_spec(spec);spec.loader.exec_module(publish)
    publish.compact('Signed release profile audit',(a.out/'profile-audit.json').read_bytes())
if __name__=='__main__':
    try:main()
    except (ValueError,KeyError,OSError,zipfile.BadZipFile,zlib.error,UnicodeError) as e:
        print('::error title=Signed profile audit rejected::'+str(e)[:2000].replace('%','%25').replace('\n','%0A'));raise SystemExit(1)
