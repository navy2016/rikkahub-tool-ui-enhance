# Full virtualization: alternate-screen width reuse candidate

User explicitly requested full virtualization, superseding planned DEFAULT-TUI grouping comparison.
Production remains55db111/main8caf50d5c40e228f1d5bcc09d3db505757568b68. No new render option,
no thin-cursor overlay, no default/KEYS/selection/input or scheduler changes.

Code path: FULL_VIRTUAL supports alternate screen; widthIndex.width currently skips ALLscreen
reuse there. Every new frame measures the wholephysicalscreen even for one-line updates.
Three-file candidate.patch is NOT live production. fullVirtualWidth explicitly permits owned
alternate-screen metadata only, same owner/generation/style/columns/screen generation/metrics,
non-backward revision, equal fullAnnotatedString by stablelineID. At mostMAX_ROWS text+scalar
entries, noParagraph/layout/historytext cache. Failure/fallback/clear removes screen entries;
fork/epoch accept remains atomic. All old width() callers retain conservative behavior.

CI firstruns6newJVMcontracts plus unchanged width-index and fullvirtual tests;then twoRelease-
derived non-debuggable APKs from identical checkout/resources/nativeassets with ONEdriverAPK.
42real-font oracle comparisons coverLTR/RTL,3density/scale settings,ANSI/CJK/emoji/combining,
insert/delete/cursor. This correctness oracle can useComposeTestRule;performance CANNOT.

Real-clock page driver is copied from verifiedv5 and scoped toFULL_VIRTUAL+ALT,mainhistory1k
preservedbehindalternate,80columns,25physicalrows expected via kernel/model stability. Fixed
single-line redrawatrow24 stays in the visible lower viewport whenIMEshows. Samefinitebyte
response/realframe/systemtoken correlation inbotharms,3IMEscenes,2warmup+10measured each.
4launchpairs orderedAB/BA/AB/BA ononeboot =288windows/48warmup/240measured. No old samples replayed.
Sourcepatch/driver/APK/runtimehashes andallrawtraces are preserved. TargetARTspeedcompile and
SwiftShader2CPU are testenvironment,notphone orformalRelease behavior.

Frozen acceptance before running: allcorrectness/byte/visibility/systemmatches first. Performance
screening requires input→revisiondraw medianpairedratio<=0.90 perphase,>=3/4pairs lower perphase,
and matched input→DisplayEnd medianpairedratio<=1.05 perphase. Preserve any regression/failedpair.
Workcounter reduction alone cannot authorize promotion. Passing only enables a localized production
candidate forfull492JVM/182viewport/nativePTY regressions,not an automatic release orphoneclaim.

Never change archivedreport guards to accept the overlay. Buildscript patches only3allowedfiles
onGitHub runner,records hashes andrestorescheckout in finally. Local compilation forbidden.
Workflowterminal-alt-width.yml is dispatch-only anddoes not deliverAPK. Explicit branch required.
