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
