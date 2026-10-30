# Full-virtual latency baseline v1 (new study, not old performance resampling)

User accepted the next optimization plan with "继续" under standing terminal-task authorization.
Frozen production:55db111129c662067345aa99540b506de305da37/main8caf50d5c40e228f1d5bcc09d3db505757568b68.
P0 adds ONLY test/CI/report instrumentation. No production,dependency,font,native,default-mode changes.
Do not rebuild the formalRelease. Oldc07100d30samples and every prior failure stay unchanged.

Four fresh native-PTY/page cases:ordinary/TUI ×1000/10000history. FULL_VIRTUAL only,80columns,12sp,
same emulator/API34/non-rootKVM/Release-derived target. Six deterministic workloads:echo,cursor,
row,burst,imeEcho,remountEcho. Each has2warmup+20recorded samples in a frozen order,all retained.
Burst means32lines/command; it is NOT a30minute soak or open-loop sustained-throughput benchmark.
Startup and remount convergence are separate observations,not the old cold benchmark.

Each sample binds one existing UI input action to the exact finite peer output byte count,final fed
revision,first matching published/drawn frame. Capture native-read/parse/capture/row-sync spans,
panel compositions,visible TextLayoutResult identity reuse,Java/native/PSS/GC snapshots,actual
viewport/backend and stable native grid. No trace buffer overflow. Layout snapshots and memory
queries occur outside the input→draw interval; their cost still affects the closed-loop workload.
feedToPublishNs remains signed: a concurrently captured model revision can precede the observer's
EMULATOR_FED event. Never clamp negative observer gaps or call them negative physical latency.

Android Window FrameMetrics records measured-layout/draw/sync/GPU/total/deadline/vsync timestamps
independently on a test-only listener. Raw callbacks,dropped reports and firstDraw flags remain.
Perfetto records app atrace and SurfaceFlinger FrameTimeline percase. A trace artifact alone is NOT
proof of presentation; publish a separately decoded OS frame summary or explicit unsupported status.
No FPS or phone improvement inferred from Compose FRAME_DRAWN or test visibility confirmation.

Report per-workload medians/P95/max;n=20 is too small for a trustworthy P99,so do not market P99.
This single-source baseline cannot prove speedup. Future candidates reuse this exact workload and
need same-device interleaved baseline/candidate comparison,all samples retained and gates fixed
before running. Sample failure/trace limitation must be preserved; no blind retries to get green.
Existing492JVM/182viewport/page gates remain immutable for a later production change.

Harness compile correction:source6d4da2397f01cf214152b941f1f9ce73d397b7fa/run41058225696
failed compileTerminaltestAndroidTestKotlin with`Unresolved reference 'cursorColumn'` at testline233.
RenderFrame carries cursorRow but not cursorColumn; use the existing synchronized model getter
(1-based). No instrumentation/performance sample ran. Preserve this failure; production is unchanged.

Perfetto bootstrap:source0debc6b6bf27cf2d7bc0ea6af315e796abdf7e5f/run41059663096 compiled,then
the very first `adb shell perfetto --txt --background ...` exited1 before starting instrumentation.
Its wrapper omitted the captured output; exact reason is NOT established. Preserve the failed run.
Now always retain startup stdout/stderr/exit and stop/retrieval results. Unavailable system tracing is
an explicit separate outcome,not fabricated presentation evidence; all528terminal/WindowFrameMetrics
samples remain required. No prior runtime samples exist, so this is not favorable resampling.

## Runtime harness v2 correction; previous396samples remain separate failed-run evidence

Run41061360774/source3e7eb05f382cc5250d89bffb72439bbafb3fd120:ordinary1k captured transitory32rows,
then failed its FIRST echo with32!=25. Other3cases completed132samples each and emittedtheir case
records,then failed because the FrameMetrics listener was removed twice:
`attempt to remove OnFrameMetricsAvailableListener that was never added`. Keep all396original
samples as a failed/partial baseline,not acceptance. No timings are selected or overwritten.
Perfetto startup original now proves`Could not open /data/local/tmp/fv-latency.pbtxt (errno:13,Permission denied)`.

V2 changes only harness:single-owner idempotent listener removal;before measurement require actual
grid acknowledgement/model/output bounds stable for700ms and>=2distinct draws (cover160ms+160ms
resize,120msIME guard,180msfallback),recordevery grid transition;passPerfetto config onstdin.
All per-sample grid equality/byte/renderer assertions stay. Add24no-output layout snapshot controls:
GetTextLayoutResult may create wrappers,so identity changes alone must NOT be called actual shaping.
The new run is a separately identified v2 baseline under corrected measurement conditions,not a
replacement of favorable old samples. Production and workloads remain frozen; no optimization yet.
