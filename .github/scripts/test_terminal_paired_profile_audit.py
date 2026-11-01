import copy,hashlib,importlib.util,struct,unittest,zlib
from pathlib import Path
s=importlib.util.spec_from_file_location('paired_profile_tests',Path(__file__).with_name('terminal-paired-profile-audit.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def dex_bytes():
    strings=['Ltest/T;','V','run'];sections={};at=112
    for name,count,width in [('strings',3,4),('types',2,4),('protos',1,12),('methods',1,8),('classes',1,32)]:
        sections[name]=(count,at);at+=count*width
    raw=bytearray(at);data_offset=at
    for n,value in enumerate(strings):
        struct.pack_into('<I',raw,sections['strings'][1]+4*n,len(raw));raw.extend(bytes([len(value)])+value.encode()+b'\0')
    struct.pack_into('<II',raw,sections['types'][1],0,1)
    struct.pack_into('<III',raw,sections['protos'][1],1,1,0)
    struct.pack_into('<HHI',raw,sections['methods'][1],0,0,2)
    struct.pack_into('<I',raw,sections['classes'][1],0)
    raw[:8]=b'dex\n035\0';struct.pack_into('<III',raw,32,len(raw),112,0x12345678)
    for offset,name in [(56,'strings'),(64,'types'),(72,'protos'),(88,'methods'),(96,'classes')]:struct.pack_into('<II',raw,offset,*sections[name])
    struct.pack_into('<II',raw,104,len(raw)-data_offset,data_offset)
    raw[12:32]=hashlib.sha1(raw[32:]).digest();struct.pack_into('<I',raw,8,zlib.adler32(raw[12:])&0xffffffff)
    return bytes(raw)
def profile(checksum=123,flags=3,inline=0):
    key=b'base.apk!classes.dex';hot=struct.pack('<HH',0,inline) if flags&1 else b''
    bits=(1 if flags&2 else 0)|(2 if flags&4 else 0)
    body=struct.pack('<HHIII',len(key),1,len(hot),checksum,1)+key+hot+struct.pack('<H',0)+bytes([bits])
    packed=zlib.compress(body)
    return b'pro\0' + b'010\0'+bytes([1])+struct.pack('<II',len(body),len(packed))+packed
class PairedProfileReaderTests(unittest.TestCase):
    def test_dex_names_and_method_prototype_resolve(self):
        dex=m.Dex(dex_bytes());self.assertEqual(dex.type(0),'Ltest/T;');self.assertEqual(dex.method(0),'Ltest/T;->run()V');self.assertEqual(dex.class_def(0),'Ltest/T;')
    def test_corrupt_dex_checksum_rejected(self):
        raw=bytearray(dex_bytes());raw[-2]^=1
        with self.assertRaises(ValueError):m.Dex(bytes(raw))
    def test_out_of_range_symbol_indices_rejected(self):
        dex=m.Dex(dex_bytes())
        for fn in (dex.type,dex.method,dex.class_def,dex.string):
            with self.assertRaises(ValueError):fn(9999)
    def test_profile_recovers_flags_and_type_ids(self):
        rows,_=m.parse_profile(profile(flags=7));self.assertEqual(rows[0]['methodFlags'],{0:7});self.assertEqual(rows[0]['classTypeIds'],[0]);self.assertEqual(rows[0]['dex'],'classes.dex')
    def test_only_checksum_is_normalized_not_method_flags(self):
        a,da=m.parse_profile(profile(123));b,db=m.parse_profile(profile(456));self.assertNotEqual(a[0]['checksum'],b[0]['checksum']);self.assertEqual(da,db)
        _,dc=m.parse_profile(profile(123,flags=7));self.assertNotEqual(da,dc)
    def test_incomplete_and_unsupported_profiles_rejected(self):
        for raw in (profile()[:-1],b'bad',profile(inline=1)):
            with self.assertRaises(ValueError):m.parse_profile(raw)
    def test_equivalence_cannot_ignore_different_methods_or_metadata(self):
        a=dict(matchesOwnDex=True,metadataSha256='meta',normalizedProfileBodyExceptLocationChecksumsSha256='body',symbolicContentSha256='symbols',sourceSha='source')
        self.assertTrue(all(m.compare(a,a).values()))
        b=copy.deepcopy(a);b['symbolicContentSha256']='other';self.assertFalse(m.compare(a,b)['sameSymbolicMethodsFlagsAndClasses'])
        b=copy.deepcopy(a);b['metadataSha256']='other';self.assertFalse(m.compare(a,b)['sameMetadataBytes'])
    def valid_qualification(self):
        profiles={arm:dict(matchesOwnDex=True,metadataSha256='meta',profileSha256=arm,
            normalizedProfileBodyExceptLocationChecksumsSha256=arm,symbolicContentSha256='symbols',sourceSha='source',
            entries=[dict(matchesOwnDex=True)]) for arm in ('legacy','candidate')}
        assets={arm:{'assets/dexopt/baseline.prof':dict(bytes=10,sha256=arm),
            'assets/dexopt/baseline.profm':dict(bytes=5,sha256='meta'),'lib/x86_64/lib.so':dict(bytes=3,sha256='native'),
            'static/index.html':dict(bytes=5,sha256='web')} for arm in profiles}
        return profiles,assets
    def test_typed_qualification_requires_semantics_not_equal_profile_crc_body(self):
        profiles,assets=self.valid_qualification();q=m.qualify(profiles,assets)
        self.assertFalse(q['rawAssetsByteIdentical']);self.assertTrue(q['nonProfileAssetsByteIdentical'])
        self.assertFalse(q['comparison']['onlyLocationChecksumsDiffer'])
    def test_other_assets_and_web_must_still_be_byte_identical(self):
        for name in ('lib/x86_64/lib.so','static/index.html','assets/dexopt/baseline.profm'):
            profiles,assets=self.valid_qualification();assets['candidate'][name]['sha256']='changed'
            with self.assertRaises(ValueError):m.qualify(profiles,assets)
    def test_missing_file_changed_flag_and_unbound_profile_cannot_pass(self):
        for kind in ('missing','symbols','source','dex','profile'):
            profiles,assets=self.valid_qualification()
            if kind=='missing':assets['candidate'].pop('static/index.html')
            elif kind=='symbols':profiles['candidate']['symbolicContentSha256']='other'
            elif kind=='source':profiles['candidate']['sourceSha']='other'
            elif kind=='dex':profiles['candidate']['entries'][0]['matchesOwnDex']=False
            else:profiles['candidate']['profileSha256']='other'
            with self.assertRaises(ValueError):m.qualify(profiles,assets)
if __name__=='__main__':unittest.main()
