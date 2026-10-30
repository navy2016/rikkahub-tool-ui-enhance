# Single-change thin-cursor experiment after the user's slowdown subsided

Latestuser:"使用一段时间后，好像没有之前那种缓慢感觉了！请继续按计划推进终端优化。"
Preserve as perceived improvement,not proof that ART warmup was the cause. Globalregression
investigation is paused; no phone data collection is required to continue this scoped experiment.

Production remains55db111/main8caf50d5c40e228f1d5bcc09d3db505757568b68. Test-only Fixture adapter
can choose the existing renderFrameForCursorOverlay and feed the existing cursor state to the
existing full-virtual row surface. Existing callers defaultfalse;no production/default/KEYS changes.
This tests whether small BAR/UNDERLINEcursor updates can stop changing text/shapedParagraph/width.
It is not another proof that all rows re-layout(the prior wrapper-identity pitfall remains excluded).

2Releaseinstrumentation methods:40actualpixel cases(LTR/RTL,fontscale1/1.3,bar/underline,
ASCII/CJK/combining/ZWJ/Hebrew positions)and40AB/BA timing/work-count windows:
1k/10k ×bar/underline×5rounds×2arms;round0warmupretained,4pairedmeasurements.24samecursor moves/window.
Primary evidence:overlay changeszeroParagraph/text/width measurements whilecontrol changesmore;
corpus/realheight/onewriter/rowbounds/disposal/pixels stay exact. Timing includes idle/layout-query
overhead and is secondary,not phoneFPS or end-to-endPTY. Keepallslower/failing samples and no rerun
forgreen metrics. Integration into production and fullselection/mouse/IME/ALT/nativePTY gates are
separate follow-up steps only if this experiment justifies them. No formalRelease yet.

Firstexperiment1e5f20a/run41085513057passed40pixelcases/all40windows. Every24moves:paragraph,
text,width,rowmeasurement medians48→0. BUT all16nonwarmuppairs have positiveelapsedDelta:
overlaytakesroughly3.27–3.31s versuscontrol2.80s/window. Do NOT promote toproduction based onlyon
workcount. No oldsamplediscard/resample. NewcursorTiming diagnosis runs16isolated publications
withmanualComposeclock andtest-onlyactualdrawobserver,recordingpublish/draw/idle-return timings.
This is causal diagnosis,not a replacementperformancewindow orphone/PTYtest.

Timingdiagnosticc74286e/run41087209082passed16points:botharmsrequireONEexplicit16mstestframe.
Medianpublish→actualdraw(control/overlay)=23.506/41.197ms. Hence the original slowdown cannot be
explained away as merely an extra manually advanced Composeframe. Small,ordered diagnosticnot
phoneproof;exact queue/renderer cause still unresolved. Keep the experiment as NOTADOPTED:work
counts48→0 do not outweigh all16worse elapsedpairs. No productioncursorpolicy changes/newRelease.
Original40windows/40pixelcases plus16diagnosticpoints andJUnit/log/APIhashes independentlyreread.
Nextpriority remains input/publication/firstdraw waiting;no requirement to collectphone state now
that the user reports the earlier globalsluggishness has subsided. No claim of havingfixedit.
