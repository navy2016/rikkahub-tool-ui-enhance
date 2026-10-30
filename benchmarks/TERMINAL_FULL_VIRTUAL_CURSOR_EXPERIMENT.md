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
