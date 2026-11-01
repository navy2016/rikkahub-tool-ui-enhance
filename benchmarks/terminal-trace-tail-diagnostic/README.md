# Independent FrameTimeline tail diagnostic V1 — no APK or terminal performance

V3run41343554975is closed/rejected after full840window/24trace replay. Sixteenformalr20windows lack
complete frame evidence. This diagnostic never replaces or reclassifies those samples.

Same Android34fingerprint and Perfetto34; built-in Settings only,no app build/install. Fixed8cases:
baseline,wait,periodic,composite,composite,periodic,wait,baseline. Every case does the same setup/swipe.
The logical cutoff comes from proc uptime; its10ms uncertainty is explicit. Only timestamps strictly
below its floor bound can be pre-cut evidence. This is not a terminal input/latency endpoint.

-Baseline: unchanged collection recipe except Settings atrace,50ms after logical cut then stop.
-Wait: same recipe,1050ms quiet before stop.
-Periodic: additionallyflush_period_ms250,1050msquiet. Diagnostic configuration only.
-Composite:50msquiet,eight1004SurfaceFlinger debug composite requests at50msintervals,600msquiet.
 Permission/acknowledgement failure is retained and never bypassed with root or SELinux changes.

PID/comm/start ticks and race-safe proc absence prove stop ownership. All traces are preserved,including
execution failures. Last pre-cut Settings UI token is checked against its own Surface/Display graph;
later tokens and post-cut ends never repair it. Missing and duplicate records remain explicit.

Neither successful execution nor a complete generic-system tail graph constitutes native840window
qualification,C1performance or adoption. Any result is a lead for a separately preregistered collector.
V3protocol/parser/production306and all historical candidates remain byte-identical.
