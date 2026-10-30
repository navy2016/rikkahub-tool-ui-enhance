# Targeted virtual selection diagnosis and repair

User explicitly approved targeted diagnosis/fix and affected verification after the failed checkpoint.
Baseline62d00ca2cda005b87d1ca92fe505bd0c14f5ce20, mainc4da501312976a99776dc99fcd2d7db78bc1256a.
Originalc07100d/run40272608906 andfa16a20/run40620489127 selection failures remain immutable.

## Scope and sequence

1. One explicit observational cloud diagnostic, NOT acceptance. Same10k synthetic corpus and
   word/handle platform gestures on oldCHUNKED_LAYERS selection andFULL_VIRTUAL. Capture actual
   hit/offset/wordBoundary, endpoints, focus/menu/copy/clipboard, pointer transforms, edge requests,
   effect ownership/consumed pixels/visible row IDs and cleanup. Missing callbacks are evidence,
   not invented progress. A diagnostic JUnit success means the trace was collected, not SEL works.
2. Explain the observed chain and implement the smallest repair. No parser/input/default/KEYS,
   history capacity, font/native/dependency changes. Keep Text shaping/real heights, virtual SEL
   and the sole vertical executor. Full-history copy never depends on retained UI nodes.
3. Concentrated affected acceptance including the two ORIGINAL word/offscreen assertions plus
   reversed/bidirectional/emoji endpoints, lifecycle/focus, TUI/IME/MOUSE/ALT/handoff and cleanup.
   One candidate and at most one consolidated correction; preserve every failure. Existing first
   component30samples are frozen and are not resampled to seek better performance. If a fix changes
   the timed render path, do not claim those samples measured the new production tree.
4. Resume formalRelease and independent verification only after all relevant gates pass. Deliver
   report/evidence, noAPK. Real-device performance remains unverified until the user tests it.

## Diagnostic implementation boundary

Production observational edits onlyTerminalVirtualSelectionUi.kt,TerminalViewportBinding.kt and
TerminalLazyItemExecutor.kt. Optional test callbacks are null in the real page. Do not retain extra
rowLayouts/documents or install tracing globally; snapshots contain scalar IDs/offsets, measured
rectangles, lengths andSHA256, never transcript content. No high-volume production logging.
Probe callbacks must not mutate selection/controller or create scroll coroutines. Frozen control
still uses the existingSelectionContainer/Text tree. Diagnostic cases are independently identified
asordinary-word/control+fullVirtual andlocked-handle/control+fullVirtual; expected failures do not
relax any acceptance assertion. Corpus/driver identity and measured geometry accompany all records.

Cloud onlyRelease fixture compilation on the explicit optimization branch, commits[skip ci].
LocalPython/static/patch verification allowed, noKotlin/Android/native builds,Debug,APK downloads,
new fonts/dependencies or agents. ForegroundSHA-bound900s automatic wait, bounded tool resumes,
no background watcher/re-dispatch. This protocol records authorization, not completed evidence.

## Observed cause and candidate acceptance

Authorized diagnostic sourceae71d82d292b21d5e759daf2c1b9b5f05a27b914/run41025419455 completed all
four observed cases/JUnit collection and source/report gate. The overall workflow failed only its two
drag compact-publication steps("Evidence exceeds compact budget"); this remains an original failed
run, not a reclassified success. Exact467663B full-virtual dragJSON was retrieved from the SAME artifact
12963753058, digesteea9cb6eb5e9e61b0d2d88e7f1b182ea1eec411347a7ea716de2f97b58fd6145,
via relay41026403313. No extra diagnostic/performance run was made.

Word hit index10014/offset3/word0..9 and initial9character text hash were correct. A stationary
long-press near the top scheduled6edge requests(-7px each), changed the endpoint to index10013/offset3,
and copied19characters. The old control copied the correct9characters. Copy callback/clipboard matched
the already-corrupted selection; no focus loss before copy. Handle case started at index500 but
stationary long-press first scrolled-102px, reversing its selection. All220MOVE events reached the
handle,32effects were consumed with one writer; subsequent down-drag netted only+14px and keptindex500
visible. Thus neither missing pointer injection nor failed scroll consumption explains the failure.

Candidate keeps a scalar motion-intent tracker separate from long-press/handle ownership. Stationary
selection never edge-scrolls; leaving the upper edge downward cannot trigger upward scrolling (and
vice versa). After real motion toward the edge, holding still can continue existing edge-scroll until
release; release/cancel resets intent. The same sole effect executor is unchanged except nullable
observational callbacks. Popup left alignment uses ACTUAL measured popup width rather than parent's
density estimate (diagnostic parent25dp=33px versus popup measured88px). No new renderer/fallback.

Acceptance: original six correctness methods are byte-identical (including WORD/all-offscreen-copy
and actual recycled-start assertions), excluding the passed original performance method. Three
new instrumented regression methods produce13exact copy/rectangle records:stationary top/bottom,
reverseLTR/RTL including combining/ZWJ text and physical popup alignment,ordinary/TUI/IME/MOUSE/
fullGrid/ALT/main/handoff/remount. Four new JVM motion regressions are added to the existing10.
Run old173viewport regression plus these9correctness methods(182total),completeReleaseJVM, and
native full-page/PTY gates. This is candidate acceptance1 under the added authority, not the old
failed correction. Prior30performance samples remain source-bound toc07100d and are NOT new-tree
timings; no post-failure resampling. Only after correctness/source gates pass may formalRelease resume.

## Candidate1 result and the single consolidated correction

Candidate347d17aa5676530f805f05ae3bd424f7c7cc4730, mainb7edc837d11f84e24f1619c4ab8d116b494930ad:
native-page/PTY41028517457 passed. JVM41028489789 executed487cases without test failures, but its
report still expected10fullVirtual cases rather than14; preserve the failed reporting run.
Viewport41028506352:old173passed,4original new-mode geometry/handoff methods and3new selection
regressions passed. Word now copied correctly and SelectAll was reached, then Android rejected
`TransactionTooLargeException: data parcel size 2525220 bytes` while serializing the repeated row
SpanStyle annotations. Original drag still failedline263. Same-run XML/log artifact12965176766 was
retrieved via relay41030395370, not a test rerun. Original drag started atscreen128,73 with a88px
popup; native menu avoidance used a content rectangle ending only1px below the29px text baseline.
The first candidate did not record a native-menu hit box; do not call overlap a captured old event.

One consolidated correction:copy-time coalescing of equal contiguous style ranges (including a sole
inter-rowLF) preserves all text, hyperlinks/other annotations, order and differing/overlapping styles.
No truncation or plain-text-only fallback; threeJVM tests cover10krows and annotation preservation.
Native floating-menu exclusion rectangle includes measured handle height; new actual UI assertions
require Copy/SelectAll hit boxes NOT cover handle centers in all13regression copies. Existing original
six methods and fixed220MOVE driver remain byte-identical. JVM report inventory is17fullVirtual/490
total cases, checked against source by a host test. Repeat fullReleaseJVM,old173+9affectedviewport and
native page gates together. No new performance samples. This exhausts the one correction allowance;
do not create an automatic further retry or relabel any original failed run.

## Final checkpoint: selected copy fixed; handle interaction remains blocked

Final9b718019bc582026d7f9139bfc507f399260e733/main3ccc04c2f05e4506b22ed2e897f6ee887785447c.
490Release JVM41033789881 passed,including17fullVirtual tests. Native-page/PTY41033824934 passed;
its original compressed page report was hash-checked and revalidated(18records/16cursor rectangles,
12kernel queries/8windows,78200bytes each read/fed,2outputCOPY/2echo/2cleanup).
Viewport41033807694 completed182cases:old173passed plus7/9affected cases passed. Two actual failures:

- Original `fullVirtualSelectionHandleCrossesViewportWithoutLosingOffscreenText`,line263:
  `Drag never recycled the start row`. Same fixed220MOVE/222events/222frames,screen128,73→213,339.
  No per-event production trace from this candidate. Do not claim zero scroll or invent handler state.
- Added `selectionRepairStationaryEdgesKeepExactWords`,line128→word:100→copy:73:
  `Copy menu intercepts the actual grip center`. stationary-top completed; stationary-bottom failed
  this new native-hit-box assertion. This identifies a bottom-menu/grip overlap, not proof that it
  caused the separate original TOP-row drag failure. Measured-height exclusion did not solve every
  native floating-menu placement. The original stationary word selection remains fixed.

Original `fullVirtualSelectionSystemMenuCopiesAWordAndOffscreenSelectAll` now PASSED with exact
10024rows/319650bytes/SHA2565c4acf6976bacf0d923251c00ec27932e4c04779966f2d1760f94b9a2c719e3f.
This confirms the copy chain and repeated-style Binder repair, not the still-unreached dragged-range
copy. ReverseLTR/RTL/graphemes/popup alignment,ordinary/TUI/IME/MOUSE/fullGrid/ALT/main/handoff/remount
copies passed.12of13new ROW/MENU receipts exist; the missing bottom receipt remains a failure.

Final raw artifact12967223260 from this failed run was relayed by41036095275 and only selected
XML/log text was read locally. NoAPK download, no performance resample, no further acceptance or
formalRelease. Original diagnostic/first-candidate failures are retained. The explicit allowance is
exhausted: next targeted authorization must address actual native-menu geometry versus popup/window
coordinate updates and touch ownership, retain all original assertions and verify only affected paths.
Production is not reverted automatically and remains opt-in/unreleased. Latest formal app remains
212b93e/run40066315546. Oldc07100d performance timings do not measure this final production tree.
