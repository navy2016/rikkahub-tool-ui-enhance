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
