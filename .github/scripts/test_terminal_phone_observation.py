import importlib.util
from pathlib import Path
import unittest
PATH=Path(__file__).resolve().parents[2]/'benchmarks/terminal-phone-observation/collect.py'
spec=importlib.util.spec_from_file_location('phone_observation',PATH);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class PhoneObservationTest(unittest.TestCase):
    def test_only_numeric_frames_and_hashed_window_are_retained(self):
        text='Window: private-title\nFlags,IntendedVsync,Vsync,FrameCompleted,\n0,100,101,200,\nchat-secret\n'
        rows=m.frame_rows(text);self.assertEqual(1,len(rows));self.assertNotIn('private-title',str(rows));self.assertNotIn('chat-secret',str(rows))
        report=m.summarize([],rows);self.assertEqual(1,report['validFrames'])
    def test_duplicate_and_incomplete_records_stay_explicit(self):
        base=m.frame_rows('Flags,IntendedVsync,FrameCompleted,\n0,100,200,\n')
        later=m.frame_rows('Flags,IntendedVsync,FrameCompleted,\n0,100,200,\n1,300,400,\n0,500,600,\n')
        value=m.summarize(base,later);self.assertEqual(2,len(value['newFrameRecords']));self.assertEqual(1,len(value['flaggedOrIncomplete']));self.assertEqual(1,value['validFrames'])
    def test_no_mutating_adb_operations_or_private_logs(self):
        text=PATH.read_text()
        for command in ("'install'","'uninstall'","'clear'","'reset'","'compile'","'force-stop'","'logcat'","'screencap'"):
            self.assertNotIn(command,text)
if __name__=='__main__':unittest.main()
