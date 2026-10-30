import hashlib
import importlib.util
from pathlib import Path
import struct
import unittest
import zlib
spec=importlib.util.spec_from_file_location('signed_profiles',Path(__file__).with_name('audit-release-profiles.py'))
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)
def pack(magic,version,count,body):
    value=zlib.compress(body);return magic+version+count+struct.pack('<II',len(body),len(value))+value
def synthetic_profile():
    key=b'classes.dex';head=struct.pack('<HHIII',len(key),1,4,123,8)+key
    return pack(b'pro\0',b'010\0',b'\1',head+struct.pack('<HHH',1,0,2)+bytes((2,4)))
class ReleaseProfileTests(unittest.TestCase):
    def test_profile010_hot_class_and_bitmaps(self):
        row=gate.profile(synthetic_profile())[0]
        self.assertEqual([2],row['classTypeIds']);self.assertEqual(1,row['hotMethods'])
        self.assertEqual(1,row['startupMethods']);self.assertEqual(1,row['postStartupMethods']);self.assertEqual(2,row['profiledMethods'])
    def test_metadata002_and_bounds(self):
        key=b'classes.dex';body=struct.pack('<HH',0,len(key))+key+struct.pack('<IH',9,1)+struct.pack('<H',2)
        rows=gate.metadata(pack(b'prm\0',b'002\0',b'\1\0',body));self.assertEqual([2],rows[0]['classDefIds'])
        with self.assertRaises(ValueError):gate.profile(synthetic_profile()[:-1])
        with self.assertRaises(ValueError):gate.profile(synthetic_profile()+b'x')
        with self.assertRaises(ValueError):gate.profile(synthetic_profile().replace(b'010\0',b'015\0',1))
    def test_dex_header_internal_checksums(self):
        raw=bytearray(112);raw[:8]=b'dex\n035\0';struct.pack_into('<III',raw,32,112,112,0x12345678)
        struct.pack_into('<I',raw,64,8);struct.pack_into('<I',raw,88,10);struct.pack_into('<I',raw,96,4)
        raw[12:32]=hashlib.sha1(raw[32:]).digest();struct.pack_into('<I',raw,8,zlib.adler32(raw[12:])&0xffffffff)
        self.assertEqual(10,gate.dex_header(bytes(raw))['methodIds']);raw[-1]^=1
        with self.assertRaises(ValueError):gate.dex_header(bytes(raw))
if __name__=='__main__':unittest.main()
