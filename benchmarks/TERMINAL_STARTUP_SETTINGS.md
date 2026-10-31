# P2 paired-v2 stopped: isolate the application startup writer, no new performance sampling

ecd34ca/run41185891968 failed atpair2/candidate/1k-hidden beforepanelmount. Persisted+flow
hadconfirmedcorrectpreferences,thenbecameemptyagain beforeLocalSettingsfirstmount. Error:
`Configured LocalSettings never reached the test host`. This isNOTjustone missingflowdelivery.
5full40windowinvocations=200windows preserved;thefailedinvocation'sother3methods retain30more.
Only2completefour-casepairs. Also2missing exactFrameMetrics observed sofar despite0callback
drops;all negative/incomplete records remain failures,not grounds toresampleforbetter scores.

Concrete code path: RikkaHubApp.incrementLaunchCount() reads settingsFlowRaw.first() then calls
store.update(current.copy(launchCount=current.launchCount+1)),writingALL settings fromthat old
copy. A test configuration writtenbetween thatreadandwrite canbe overwritten. The priortrace
didnot capture thewriter'scallstack,so code-path plausibility isnotyet proof ofthatexact execution.

Newdiagnosisusesunmodifiedproduction only,NOActivity/terminal/optimization/performancewindows:
4fresh-processunfenced preparations,4fencedafterthisPID'sexistingincrementLaunchCount completion
log,1EXPLICITdeterministicstale-copyreplay. Record hashedcommandpref/launchcount beforewrite,
afterconfirmationattempt,andafterstartupreceipt. Fencedcases mustretain;replay mustoverwrite;
unfencedoutcomeisobserved withoutforcing ittofail. 2sbounded confirmationobservations canmiss
transientupdatesandarelogged assuch;theyareNOTlatency measurements or retry-until-success.
No attempttofix productionSettingsStore orlaunchcount ispartofthis P2experiment.

If causalpathisconfirmed,anynext performanceprotocolmustfirstwaitfor applicationstartupwrite
completion,thenconfigureonceandkeepexisting persisted/flow/firstLocalSettingschecks. Do not
add a loopthatrewritespreferences untila desiredsampleappears. Priorv1/v2results remain separate.

## Observed a2dc738 / run41192017536 (success)

All9diagnostic methods completed. unfenced0 naturally showedprefpresent withlaunchCount0,
thenprefemptywithlaunchCount1 afterthisPID'sincrementLaunchCount completion;other3unfenced
cases retained. All4fencedcases retained. Explicitstale-copyreplay removedtheprefagain.
Thus the whole-settings startupwriter race is reproduced inunchangedproduction;thisdoesnot
capture theexactstackoftheearlierv1/v2failure. ReportSHA256
533c628152a2347f3029fbced5c1a09e0b4e6172ebfc7b894eac918ad74b928e.
No productionfix ornewP2performance invocation wasmade. Futuretestsetupcanwaitforthisexisting
startupcompletion beforewriting once,thenretainthepersisted/flow/firstLocalSettings checks.

P2v2reanalysis preserved230windows(46warmups),228exactsystemmatches,24rawtraces. Two missing
FrameMetrics arepair0/candidate/10k-hidden/r2-refresh andpair2/legacy/10k-shown/r3-refresh.
Both haveexactSurfaceFrame/DisplayFrame tokens andis_buffer=true (protofield11),yetno exact
VsyncFrameMetrics. NearbyFrameMetrics belongtothepriorVsync. TXlayerprefix isNOTproof ofa
bufferlessframe. Originalmissingclassification remains;notanexcusetoborrowanotherframe or
removeFrameMetrics requirement posthoc. No thirdperformance rununtilmeasurement issue resolved.
