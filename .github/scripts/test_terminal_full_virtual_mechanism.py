import importlib.util,json,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('p0_contracts',Path(__file__).with_name('terminal-full-virtual-mechanism.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def fixture():
    rows=[]
    same={'sameFrame','font','scale','rtl','ime','imeHidden','selectionOn','selectionOff','mouseOn','mouseOff'}
    for scene,r in m.SCENES:
        row=dict(scene=scene,repeat=r,sameScreenIdentity=True,sameFrameIdentity=scene in same,sameIds=True,
            rowStates=24,measuredRows=24,maxWriters=1,physicalTextSha256='a'*64,changedRows=[20] if scene=='text' else [],
            definitions=0,definitionChanges=0,screenChanges=0,frameChanges=0,intervalBuilds=0,itemAttempts=0,
            unchangedItemCommits=0,unchangedRowCommits=0,rowMeasurements=0,notifications=0,scrollEffects=0,eligibleParagraphs=24,changedParagraphs=0)
        rows.append(row)
    return rows
def log(rows):return '\n'.join('FULL_MECHANISM_SAMPLE '+json.dumps(r) for r in rows)+'\nFULL_MECHANISM_DONE '+json.dumps(dict(samples=25,retainedRows=0,pointerRows=0,performanceMeasured=False,simulatedIme=True,passed=True))
class MechanismContracts(unittest.TestCase):
    def test_generated_overlay_changes_observation_not_production_or_calls(self):
        m.guard();source=(m.ROOT/'app/src/main/java'/m.SOURCE).read_text();out=m.instrument_text(source)
        self.assertIn('content = observedContent',out)
        for text in ('TerminalVirtualSelectableRow(selection, index, row, style, bound.pass.metricKey,',
            'state = binding.lazy,','key(bound.pass.metricKey, row.lineId)','Spacer(Modifier.height(8.dp))',
            'val preparedWidth = rememberTerminalPreparedWidth(frame, style, binding.widthIndex, binding.widthCheckpoint)'):
            self.assertEqual(source.count(text),out.count(text))
        self.assertNotIn('remember(screen',out);self.assertNotIn('fullVirtualWidth',out)
        for text in ('intervalBuilt()','itemEntered()','itemCommitted(row.lineId)','definitionCommitted(observedContent, frame, screen)'):
            self.assertEqual(1,out.count(text))
    def test_source_drift_is_not_instrumented(self):
        source=(m.ROOT/'app/src/main/java'/m.SOURCE).read_text()
        with self.assertRaises(ValueError):m.instrument_text(source+'\n')
    def test_fixed_inventory_and_negative_controls(self):
        rows=fixture();parsed,done=m.parse_samples(log(rows));self.assertEqual(25,len(parsed))
        for mutate in (lambda r:r.pop(),lambda r:r[0].update(changedParagraphs=1),lambda r:r[6].update(sameScreenIdentity=False),lambda r:r[0].update(maxWriters=2)):
            r=fixture();mutate(r)
            with self.assertRaises(ValueError):m.parse_samples(log(r))
    def test_probe_is_bounded_nonobservable_and_has_no_duration_claim(self):
        probe=(m.ROOT/m.PROBE).read_text();test=(m.ROOT/m.TEST).read_text()
        self.assertNotIn('mutableStateOf',probe);self.assertNotIn('nanoTime',probe)
        self.assertIn('WeakReference',probe);self.assertIn('target.size < 256',probe)
        self.assertIn('performanceMeasured",false',test)
        self.assertNotIn('elapsedNs',test)
    def test_workflow_routes_one_shard_and_keeps_flag_on_second_gradle_invocation(self):
        workflow=(m.ROOT/'.github/workflows/terminal-viewport-interactions.yml').read_text()
        self.assertIn("inputs.suite == 'fullVirtualMechanism' && '[\"fullVirtualMechanism\"]'",workflow)
        run=(m.ROOT/'benchmarks/terminal-viewport-tests/run-ci.sh').read_text()
        self.assertIn('extra+=(-PterminalFullVirtualMechanism=true -PterminalPipelineTests=true)',run)
        self.assertIn('"${extra[@]}"',run)
    def test_dependency_capture_never_forces_versions(self):
        script=(m.ROOT/'benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle').read_text()
        self.assertIn('terminaltestAndroidTestRuntimeClasspath',script)
        self.assertIn('releaseAndroidTestRuntimeClasspath',script)
        self.assertNotIn('resolutionStrategy',script);self.assertNotIn('strictly',script)
if __name__=='__main__':unittest.main()
