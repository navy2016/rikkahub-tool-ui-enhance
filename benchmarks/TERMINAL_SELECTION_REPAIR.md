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
