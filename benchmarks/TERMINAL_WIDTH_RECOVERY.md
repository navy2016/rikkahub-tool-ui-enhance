# P2 independent MAIN → ALT → MAIN width recovery baseline

Production remains55db111129c662067345aa99540b506de305da37/main8caf50d5c40e228f1d5bcc09d3db505757568b68.
No candidate implemented, no P1/width/cursor patch applied, no dependency change. This run tests
the previously code-confirmed history-width loss across ALT, not another steady-echo speedup.

## Mechanism gate before native measurement

An unchanged generated production fixture, existing natural-height/atomic publisher/executor,
1k/10k retained history. 16fixedstates per size =32records:initial,MAINtext,twoALTreturncycles,
font changes inMAIN andALT,columns change inALT,oneFIFOappend andanotherreturn. Logs accepted
index cumulative history measurements, currentwidthcandidate counts andcheckpoint availability.
Counters do not include discarded/cancelled worker forks; never interpret lastMeasuredHistoryRows
as totalwork, because a finalzero-work pass resets it. ComposeTestRule/clock is correctnessonly.
Expected baseline:unchangedMAINedit0newhistory;eachreturn/changedmetrics rescansH;FIFOappend1.
Same-historyreturn asserts immutablehistory snapshot identity. Alllayout/rowstate/writer bounds
anddisposal mustpass. No optimization implied by this count test.

## Real-clock native path contrast, not optimization A/B

Onlyaftermechanismgate:one unchanged Release-derived app+driver,new nonroot KVMguest,API34,
2CPU/SwiftShader,80columns,keyboardhidden;normalpriorARTspeedcompile fromexistingharness.
Two methods/sizes1000 and10000,5fixedrounds each. Round0 warmup,rounds1–4 measured.
Eachround contains oneMAIN-local refresh andoneALT→MAIN return,order alternatesAB/BA.
Total20windows=4warmup+16measured. No addedretries orreplacement samples. This is a baseline
contrast of two paths in the SAMEunmodified app,not a codecandidate comparison orphoneclaim.

Shellpeer isfinite canonicalread insideRAWnativePTY,all exactbytes validated. InitialSEED,
ALTentry and700msstability/visibility gating areoutside measuredwindows. One response changes
onlyrow24;RETURN additionally exitsALT. Afterdraw,assert originalMAINhistoryobject unchanged.
Eachwindow preserves gfxinfo promptly toavoid frame-ring overwrite duringcold10k preparation.
No mainClock/waitForIdle/ComposeTestRule inside native driver. Timedendpoint remains first
eligible response-revisionFRAME_DRAWN;nativevisibility ischeckedseparately aftertiming.

TraceP0/P1 collector filtered main-threadprints;P2 explicitlyparses process-owned atrace B/E
on eachactualthread includingDispatchers.Default. Report Terminal.productionWidth intervals
andCPUintersections forworker/main separately overUI_INPUT→draw,withsame MONO/BOOT calibration.
Widthwall usesunion,CPU sums threadrunning intervals;these are notadditive latency components.
Lostschedulerbundles/openwidths/missingexactsystem-frame associations remainfailures,neverguess.
Standard FRAME_DRAWN→gfxDrawStart/SyncQueued→Vsync→Surface/Displaytoken matching unchanged.

## Decision after observation

If widths really rescan andworkerCPU occupies a materialrecovery interval,design a separate
P2 scalarcheckpoint candidate (oneMAINcheckpoint,owner/generation/style/column/font/FIFOrange
validation,epoch/lease safety). First correctness/invalidation checks,then fixedsame-transition
candidate/controlpairs. No acceptance percentage canbe inferred fromthisbaseline orfromP1.
If repeatedwork isnotmeasured,stop thecheckpoint hypothesis. Neverchange thisbaseline'sprotocol
after seeingresults. RealIME/font/fasttoggle/cancellation stress belongs tocandidatevalidation,
not an undocumented expansion of the20initialnativewindows.

Workflow:terminal-pipeline-tests.yml suite=widthRecovery → terminal-width-recovery.yml.
CI-only Releasebuilds,existingnon-rootKVMharness;no hostpolicychanges/no localAndroidcompilation.
Mechanismguest startsfrom an explicitlymarked bootstrap manifest(noAPKproof),then realci.prepare
replaces it afterrealappbuild. Native execution rejects bootstrap-only data. Five resolved
runtimegraphs saved asbefore;drivers canuseFoundationprovidedbytargetAPK.
