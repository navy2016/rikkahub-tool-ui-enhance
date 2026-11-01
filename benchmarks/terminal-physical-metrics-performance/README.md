# B1 independent native-page performance V1

Baseline306 versus the unchanged five-file B1 candidate. Mechanism run41295383061 and its
529JVM/28component/24scene original evidence must be checked before either new target is built.
A1/P2 remain rejected; no old samples, no A1 source or C optimization is included.

## Fixed design

-4AB/BA pairs, one guest boot, same driver binary/assets/real IME; native ActivityScenario and PTY.
-12process-isolated cases: MAIN redraw,ALT redraw,guard cohort ×1k/10k ×IME hidden/shown.
-Each redraw process has21windows (one warmup+20formal). Guards have63windows, with20formal
  refresh,20SYNC60ms and20ALT→MAIN returns. Total3360=160warmup+3200formal,96separate raw traces.
-Each timed redraw is one shell printf command for24real physical rows, with ANSI bold/italic/color,
  CJK,combining marks,ZWJ emoji and RTL. Finite output≤4096bytes; actual read chunk counts are not assumed.
-Existing80columns,stable physical rows≥24,500ms natural tail,25s response deadline and240s/96MiB
  per-trace bounds stay fixed. Separate21/63window processes avoid extending a trace opportunistically.
-After the timing window, verify complete24model rows and native visible marker. ALT does not expose
  MAIN history: its per-window history proof is explicitly null; restore MAIN after all timed windows
  and verify the original immutable history identity. Setup gates are included in the inventory.

## Acceptance frozen before sampling

-MAIN and ALT redraw in each size/IME case: primary submission and UI median paired ratios≤0.80,
  with≥3/4pairs improving. Guard refresh,SYNC,return median ratios≤1.05. All system-end medians≤1.05.
-Every condition/endpoint samplep95 ratio≤1.10 (rank19of20), every pair max≤max(legacy×1.5,legacy+100ms).
-All3360windows require their own qualified submission/presentation; nulls propagate,not dropped.
-Exact unchanged BOOTTIME token/buffer/SurfaceFrame/DisplayFrame identity parser. FrameMetrics
  ABSENT is retained,CONFLICT fails; neither callbacks nor work counters replace real endpoints.
-No gain offsets a guard failure. No resampling/threshold changes/automatic adoption/formal APK.

Typed profile qualification is independently bound before sampling: all non-profile assets,
including Web,byte-identical; own-DEX profile validation and full symbolic equivalence. No rewriting,
stripping,copying or resigning APK. Source and typed manifests are saved even when qualification fails.

`driver.patch` documents changes from the historical shared A1 native driver; that original remains
unchanged. Source bugs are tracked in`benchmarks/TERMINAL_BUG_LOG.md`. Post-freeze changes require
a new explicitly documented version,not a hidden edit to this experiment.
