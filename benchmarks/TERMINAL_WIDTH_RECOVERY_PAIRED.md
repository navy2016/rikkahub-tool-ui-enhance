# P2 fixed scalar-checkpoint real-PTY comparison (not yet a production change)

Prerequisite32c07c8b6cf504cbd62a8c07a7e795cdf3f2acb8/run41180293887 passed45JVM+32candidate
state records+sixoldinteractions(7Androidmethods). SameMAINreturns measure0history,metric/column
changesstillH,ALTdisposal held1scalarcandidate then0. Candidatepatch hash mustequal thatgate.
Production remains55db111/main8caf50d5c40e228f1d5bcc09d3db505757568b68. No P1/ALTwidth/cursor mix.

OneCI environment,sharednativeassets/resources,twothree-file-difference Release-derived targets,
ONEdriverAPK. 4AB/BAlaunchpairs onone nonrootAPI34KVM2CPU/SwiftShader guest. Eachinvocationruns
4freshprocess cases:1k/10k × nativeIMEhidden/shown. IMEopens onlyafter hiddenkernelgrid has
settled700ms;assertphysicalrows unchanged. Eachcase5alternatingrefresh/returnrounds,round0warmup,
rounds1–4formal. 320windows/64warmups/256formal total. No measuredComposeclock oridling calls.

Primary:input→response-revision firsteligibleFRAME_DRAWN,as priorbaseline. Postdraw native
visibility isindependent,NOTsame-framefirst-visible proof. Alsoexact systemassociation and
worker/mainwidthCPU viaallthread trace. PRIMARYFROZEN for everyhistory/IMEcase:
- return pairedmedianratio<=.80 andatleast3/4launchpairs improve;
- refresh pairedmedianratio<=1.05;
- input→DisplayFrameend pairedmedianratio<=1.05 forbothconditions;
- completeexactsystem association/no lostscheduler/openwidthrecords.
Warmupsalwayskept/excluded. 4formal transitionsperconditionperlaunch are notindependentdevice
trials,nor P99 evidence. Full4pairs required. Missingassociation:finishonlyfixedmatrix,store
missingmetric asnull,doNOTreplaceresample/drop,finalgate/CI stillfails. Nativecorrectnessfailure
stops. Resultsuccess meansonlymeasurementintegrity;passesFrozenPerformanceScreen isseparate.

InitialSEED,ALTentry andsetupgates outsidewindow. ExactUTF8/escape bytes andsameMAINhistory
identity aftereachresponse checked. Includes a cold-first-start warmup butdoesnotclaimcoldapp
performance. Previousbaseline samplesare notpartofthis newA/B. Candidate remainsunadopted until
this performancegate andcomplete492/182+newproduction/page regressions pass. NoformalAPK here.

## First attempt stopped during test preparation

33efb2e038065b9838fc8616f82af3e97a8fac27/run41181696048 failed atpair0/legacy,method
recoveryShownTenThousand,`Real clock deadline: initial page`. Otherthreelegacy methods completed
10windows each;failedmethod had0samples andnocandidate ran. No completepair/performanceverdict.
Originalrecord:initialized/geometryReady/widthReady true,butPANfalse,55columns,30physicalrows,
history0;10kconfiguredlimit notsatisfied. Thus not simply a staleinitialized cache/slow trace.

Code audit:SettingsStore is inPreferencesStore.kt. update() optimistically writes settingsFlow,
DataStore subsequentlyre-emits;setTerminalRenderer separatelycopies currentflow afteritswrite.
Panel PAN/forcedcols/historylimit are remember(processId) initializers. An incorrect first
LocalSettings canlatch defaults. The exact oldemission interleaving wasnotcaptured andisNOT
claimedproven. No productionsettings change isauthorizedbythis testpreparation failure.

Driver preparationv2 only:oneSettings.update sets commandpreferences+renderermap together,
waitfor production-decoded persisted/flow state,thenverify actualLocalSettings beforeFIRSTpanel
commit. Afterfirstcommit,guard cannotunmount measuredpage. Boundedsetup snapshots recorddecoded
fields/hashes;nominaltimeouts/measurementprotocol/thresholds/candidatepatch unchanged.
Run four setupOnly cases (no performancewindows) first viawidthRecoverySetup. Do notrerun320
windows untilpreflight passes. Old30windows stay separate,neverfilled intoa new comparison.

Preparationpreflight4dc2bf59ee161b73c9cd65dcdbd5b7c7ab217ead/run41184883013 PASSED4cases with
0performancewindows:80columns/25physicalrows,1000/10000history,nativeIMEhidden/shown. Inall4,
after-write decodedflow stillhadnomatchingcommandpref;persisted-and-flow-confirmed/before-mount
correct. This captures anactual readinessgap,not the completeoldfailureinterleaving.
PreflightreportSHA2566c7be22f97374e0cd5ec8ccc045115c618c22e842654e7c5fe28ca38ce7b9344.

v2 now pinsbyte-identicalpreflightdriver andsame32c07c8candidate. Newfixed320windowrun,not
completion/replacement ofold30windows. Same thresholds/condition order/samplecounts,withactual
first-mountconfiguration checked. No productionsettings repair included.
