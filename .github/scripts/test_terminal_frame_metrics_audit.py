import base64,hashlib,importlib.util,json,unittest,zlib
from pathlib import Path
s=importlib.util.spec_from_file_location('frame_audit_tests',Path(__file__).with_name('terminal-frame-metrics-audit.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def fixture():
    requests=[];draws=[];commits=[];metrics=[];phases=[]
    token=1
    for phase in m.PHASES:
        gfx=['Window: test','Flags,FrameTimelineVsyncId,Vsync,DrawStart,SyncQueued,']
        for i in range(96):
            ns=token*100
            requests.append(dict(phase=phase,index=i,ns=ns));draws.append(dict(phase=phase,index=i,ns=ns+2));commits.append(dict(phase=phase,index=i,ns=ns+5))
            raw=[0]*23;raw[1]=token;raw[2]=ns;raw[3]=ns
            metrics.append(dict(ns=ns+10,raw=raw,publicVsync=ns,publicIntended=ns,dropped=0))
            gfx.append(f'0,{token},{ns},{ns+1},{ns+3},');token+=1
        phases.append(dict(phase=phase,gfxinfo='\n'.join(gfx)))
    return dict(passed=True,performanceMeasured=False,noTerminalCandidate=True,pid=1,requests=requests,draws=draws,commits=commits,metrics=metrics,phases=phases)
class FrameAuditContracts(unittest.TestCase):
    def test_raw_record_inventory_and_digest(self):
        d=fixture();raw=json.dumps(d).encode();encoded=base64.b64encode(zlib.compress(raw)).decode()
        text='FRAME_AUDIT_PART '+json.dumps(dict(part=1,parts=1,sha256=hashlib.sha256(raw).hexdigest(),data=encoded))
        self.assertEqual(d,m.validate(m.decode(text)))
        with self.assertRaises(ValueError):m.decode(text+'\n'+text)
    def test_missing_never_borrows_neighbor_or_changes_gate(self):
        d=fixture();del d['metrics'][20]
        rows=m.join(m.validate(d),dict(surfaces=[],displays=[]))
        self.assertEqual(192,len(rows));self.assertEqual('no-metric-by-either-exact-id',rows[20]['category'])
        self.assertEqual('both-exact-same-metric',rows[19]['category'])
    def test_raw_token_can_diagnose_different_vsync_without_relabeling_old_match(self):
        d=fixture();d['metrics'][20]['publicVsync']+=1
        rows=m.join(d,dict(surfaces=[],displays=[]));self.assertEqual('raw-token-only',rows[20]['category'])
    def test_driver_no_terminal_candidate_or_resampling(self):
        m.guard();text=(m.ROOT/m.TEST).read_text()
        self.assertIn('(rawField.get(value) as LongArray).clone()',text)
        self.assertIn('registerFrameCommitCallback',text);self.assertIn('!active || !pending',text)
        self.assertIn('SystemClock.sleep(250)',text)
        for word in ('ProcessSessionPage','mainClock','settings.update','sendInput','while(metrics'):self.assertNotIn(word,text)
if __name__=='__main__':unittest.main()
