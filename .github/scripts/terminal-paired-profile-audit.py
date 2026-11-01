"""Read-only prof010/profm002 and symbolic DEX audit for newly built source-bound paired targets.
Never copies, strips, changes, signs or installs an APK. Not an asset-equality exception by itself.
"""
import hashlib,importlib.util,json,struct,zipfile
from pathlib import Path
s=importlib.util.spec_from_file_location('profile_binary_readers',Path(__file__).with_name('audit-release-profiles.py'))
binary=importlib.util.module_from_spec(s);s.loader.exec_module(binary)
require=binary.require
def sha(raw):return hashlib.sha256(raw).hexdigest()
def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':')).encode()
def parse_profile(raw):
    count,r=binary.unpack(raw,b'pro\0',b'010\0',1);rows=[];normalized=bytearray(r.raw)
    for _ in range(count):
        length=r.number(2);classes=r.number(2);hot_size=r.number(4);at=r.at;checksum=r.number(4);methods=r.number(4)
        normalized[at:at+4]=b'\0'*4
        require(0<length<=1024 and 0<methods<=65536,'Invalid profile dimensions')
        name=r.take(length).decode();rows.append(dict(key=name,dex=binary.key(name),classes=classes,hotSize=hot_size,checksum=checksum,numMethodIds=methods))
    require(len({x['dex'] for x in rows})==count,'Duplicate profile DEX key')
    for row in rows:
        hot=binary.Reader(r.take(row['hotSize']));last=0;hot_ids=[]
        while hot.at<len(hot.raw):
            last+=hot.number(2);require(last<row['numMethodIds'] and (not hot_ids or last>hot_ids[-1]),'Invalid hot method ordinal')
            hot_ids.append(last);require(hot.number(2)==0,'Runtime inline caches unsupported in this shipped baseline-profile audit')
        hot.done();types=binary.ids(r,row['classes']);bits=r.take((2*row['numMethodIds']+7)//8)
        hot_set=set(hot_ids);flags={}
        for method in range(row['numMethodIds']):
            value=(1 if method in hot_set else 0)
            if bits[method//8] & (1<<(method%8)):value|=2
            p=method+row['numMethodIds']
            if bits[p//8] & (1<<(p%8)):value|=4
            if value:flags[method]=value
        row.update(classTypeIds=types,methodFlags=flags)
    r.done();return rows,sha(bytes(normalized))
class Dex:
    def __init__(self,raw):
        self.raw=raw;self.header=binary.dex_header(raw)
        self.stringCount,self.stringOffset=self.pair(56);self.typeCount,self.typeOffset=self.pair(64)
        self.protoCount,self.protoOffset=self.pair(72);self.methodCount,self.methodOffset=self.pair(88)
        self.classCount,self.classOffset=self.pair(96);self.strings={}
        for count,offset,width in ((self.stringCount,self.stringOffset,4),(self.typeCount,self.typeOffset,4),
            (self.protoCount,self.protoOffset,12),(self.methodCount,self.methodOffset,8),(self.classCount,self.classOffset,32)):
            require(0<=offset<=len(raw) and count>=0 and offset+count*width<=len(raw),'DEX table out of bounds')
    def pair(self,at):return struct.unpack_from('<II',self.raw,at)
    def u32(self,at):
        require(0<=at<=len(self.raw)-4,'DEX read out of bounds');return struct.unpack_from('<I',self.raw,at)[0]
    def string(self,index):
        require(0<=index<self.stringCount,'DEX string index invalid')
        if index in self.strings:return self.strings[index]
        offset=self.u32(self.stringOffset+4*index);count=0;ended=False
        for shift in range(0,35,7):
            require(offset<len(self.raw),'Truncated string length');byte=self.raw[offset];offset+=1;count|=(byte&127)<<shift
            if byte<128:ended=True;break
        require(ended,'Oversized string length')
        end=self.raw.find(b'\0',offset);require(end>=offset and end-offset<1024*1024,'Unterminated/oversized DEX string')
        # DEX strings use modified UTF8; UTF16 surrogate units are legal and preserved.
        text=self.raw[offset:end].replace(b'\xc0\x80',b'\0').decode('utf-8',errors='surrogatepass')
        require(len(text.encode('utf-16-le',errors='surrogatepass'))//2==count,'DEX UTF16 length differs')
        self.strings[index]=text;return text
    def type(self,index):
        require(0<=index<self.typeCount,'DEX type index invalid');return self.string(self.u32(self.typeOffset+4*index))
    def method(self,index):
        require(0<=index<self.methodCount,'DEX method index invalid')
        owner,proto,name=struct.unpack_from('<HHI',self.raw,self.methodOffset+8*index)
        require(0<=proto<self.protoCount,'DEX proto index invalid')
        _,returned,parameters=struct.unpack_from('<III',self.raw,self.protoOffset+12*proto);arguments=[]
        if parameters:
            size=self.u32(parameters);require(size<=65535 and parameters+4+size*2<=len(self.raw),'DEX parameters invalid')
            arguments=[self.type(struct.unpack_from('<H',self.raw,parameters+4+2*n)[0]) for n in range(size)]
        return self.type(owner)+'->'+self.string(name)+'('+''.join(arguments)+')'+self.type(returned)
    def class_def(self,index):
        require(0<=index<self.classCount,'DEX class definition invalid');return self.type(self.u32(self.classOffset+32*index))
def audit(path,expected_apk_sha,expected_source):
    with path.open('rb') as stream:require(hashlib.file_digest(stream,'sha256').hexdigest()==expected_apk_sha,'Different paired target APK')
    import re
    with zipfile.ZipFile(path) as archive:
        require(len(archive.namelist())==len(set(archive.namelist())),'Duplicate APK members')
        revision=archive.read('META-INF/version-control-info.textproto').decode()
        require(re.findall(r'revision:\s*"([a-f0-9]{40})"',revision)==[expected_source],'Profile audit source SHA differs')
        prof=archive.read('assets/dexopt/baseline.prof');profm=archive.read('assets/dexopt/baseline.profm')
        rows,normalized=parse_profile(prof);meta=binary.metadata(profm)
        require({x['dex'] for x in rows}=={x['dex'] for x in meta},'Profile/metadata DEX inventories differ')
        symbolic=[];entries=[]
        for row in rows:
            dex=Dex(archive.read(row['dex']));metadata=next(x for x in meta if x['dex']==row['dex'])
            crc=archive.getinfo(row['dex']).CRC
            require(row['checksum']==crc and row['numMethodIds']==dex.methodCount and metadata['typeIdCount']==dex.typeCount,'Profile mismatches own DEX CRC/method/type counts')
            methods=sorted((dex.method(index),flags) for index,flags in row['methodFlags'].items())
            classes=sorted(dex.type(index) for index in row['classTypeIds'])
            definitions=sorted(dex.class_def(index) for index in metadata['classDefIds'])
            item=dict(dex=row['dex'],methods=methods,profileClassTypes=classes,metadataClassDefinitions=definitions)
            symbolic.append(item);entries.append(dict(dex=row['dex'],dexSha256=dex.header['sha256'],profileChecksum=row['checksum'],apkLocationCrc=crc,
                methodIdCount=dex.methodCount,typeIdCount=dex.typeCount,profileMethodCount=len(methods),profileClassCount=len(classes),
                methodsSha256=sha(canonical(methods)),classesSha256=sha(canonical(classes)),classDefsSha256=sha(canonical(definitions)),matchesOwnDex=True))
    return dict(apkSha256=expected_apk_sha,sourceSha=expected_source,profileSha256=sha(prof),metadataSha256=sha(profm),
        normalizedProfileBodyExceptLocationChecksumsSha256=normalized,symbolicContentSha256=sha(canonical(sorted(symbolic,key=lambda x:x['dex']))),
        entries=entries,matchesOwnDex=True),symbolic
def compare(legacy,candidate):
    return dict(bothMatchOwnDex=legacy['matchesOwnDex'] and candidate['matchesOwnDex'],
        sameMetadataBytes=legacy['metadataSha256']==candidate['metadataSha256'],
        onlyLocationChecksumsDiffer=legacy['normalizedProfileBodyExceptLocationChecksumsSha256']==candidate['normalizedProfileBodyExceptLocationChecksumsSha256'],
        sameSymbolicMethodsFlagsAndClasses=legacy['symbolicContentSha256']==candidate['symbolicContentSha256'],
        sourceSame=legacy['sourceSha']==candidate['sourceSha'])
