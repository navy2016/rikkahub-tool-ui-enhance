# Independent P2 scalar MAIN-checkpoint candidate

Baseline97b0ef261e7d18cec36d30a8e3d1b0f9feb70c03/run41177096610 completed32mechanism records and
20nativewindows(4warmup). SameMAINhistory object survives ALT;returnscansH=1000/10000. Native
return UI→draw medians239.461/530.900ms;workerwidth CPU61.725/383.364ms;refresh61.641/62.159ms
with0workerwidth. All20systemframe associations valid. This pathcontrast isNOTcandidategain.
Raw resultSHA256993b66ba2a32c7305cf779a20278428729a2014e7bbffc8bfe3b039baac467b3.

Candidate.patch changes3files ONLY inCI temporarycopies. Productionstill55db111/main8caf50d5c40e228f1d5bcc09d3db505757568b68.
No P1 contentreuse,ALTrowwidthcache,cursorpatch,defaultmode ordependency change.

## Mechanism

- OnlyFULL_VIRTUAL opts into preserveMainAcrossAlternate(defaultfalseforalloldcallers).
- Beforewidthselection/scanning,parkONEexistingMAIN scalar Checkpoint when entering trustedALT.
- Parked data contains candidateordinal/width pairs,source token/generations andexternalfontproof,
  never Text/Paragraph/Layout/Activity or a chain ofoldframes.
- WhileALT,source/font/columns invalidation dropsit. ALTwidth() remainstheoldfullscreen scan.
- OnMAINreturn validateowner/generation/renderRevision/columns/fontproof/FIFOrange;restore once
  viaexistingclear()/epoch andmeasureonlymissinghistory. Screenwidths are freshlyrecomputed.
- Parkedfield isNOTcopied into workerforks. Clear/eagerfallback/disposal releasesit;oldsession
  checkpointleases stayunchanged,withnonew competingwriter. FirstdirectALT mount hasnoMAIN
  checkpoint andmust perform normalcoldscan onfirstreturn.
- Restorationversion participates inrememberedwidthkey,so a repeatedframe reference cannot
  reuse a scalar width whose ready-proofwasrevoked byrestore/clear.

## Firstgate (no performance)

8newJVM contracts + unchanged18width and19fullvirtual =45. Coversrepeatedreturns,noopt-in,
font/columns/owner/renderstyle/generation/unowned reset,unknownfont,partialMAINcapture,headtrim/
missingFIFOrows,workerfork/obsoleteepoch,measurementfailure,oldsessionlease protection.
Candidatefixture32states frombaseline testsreuse0onlywherevalid;fonts/columns mustrescanH.
DiscardwhileALT withheldcheckpoint mustreleaseit. Original6fullvirtualinteraction methods
rununchanged:scenes,anchors/RTL/IME/reentry,copy,drag,mouse,handoff. SevenJUnit methods total.
Fullgeneratedcopyinventory verifiesONLYthreecandidatefiles changed,no otheroverlay.

## Laterperformance protocol, not yet dispatched

Aftercorrectness,buildunchangedbaseandcandidate withoneshareddriver/resources;no mechanismprobe.
Use identicalMAIN/ALTreturn actions onboth arms,1k/10k,4AB/BAlaunchpairs;record response-revision
draw andpostmeasurement visibility separately. IncludeMAINrefresh guard,realIME/resize/font
correctness protection. Freezeexactworkload andthresholdsbefore firstsample. Suggestedprimary
returnrecoverypairedratio<=.80,>=3/4wins atbothsizes;refresh/systempresentationguard≤1.05.
These areacceptancecriteria,not predictions. No fittingthresholds to thisbaseline/noresampling.
Only afterthefutureperformancegate passes run complete492/182+newproductionregressions.

Workflowterminal-pipeline-tests.yml/widthRecoveryCandidate. AllcompilationRelease/CIonly.
Currentcandidate file namesmaylookproduction-like butlive app/src/main remainsunmodified.
