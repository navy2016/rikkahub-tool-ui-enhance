# Real-clock input/publication/draw diagnosis (new study, not replay)

User asked to continue after thin-cursor NOTADOPTED. Production remains55db111/main
8caf50d5c40e228f1d5bcc09d3db505757568b68. No default/KEYS/selection/input or release change.
Past global slowdown reportedly subsided; no phone collector requirement or asserted ART cause.

Four fresh processes:DEFAULT/FULL_VIRTUAL × ordinary/TUI,1000history,80columns,12sp.
Each has keyboardhidden/shown/hiddenAgain,2warmup+10observed finiteechoes/phase=144total/24warmup.
ActivityScenario and native accessibilitySetText/IME_ENTER; no ComposeTestRule/testclock,manual
frame advancement,performSemanticsAction or waitForIdle. Only read-only2mspoll of existing bounded
viewport/model in the input interval. Trace snapshot,contenthash,gfxinfo andJSON work follow draw.
Setup-only stable700msgrid gate; no measurement interval is stretched to call an unstable scene done.
No FPS/P99/speedup claim; this is a fixed small causal investigation,not old528/396/40resampling.

Observe nativeWindow OnDraw.drawingTimeMillis + actualFrameMetrics.VSYNC_TIMESTAMP; require a
unique millisecond key and exact nanosecond gfxinfoVsync to obtain FrameTimelineVsyncId. Then
require unique targetPID SurfaceFrame.token and corresponding DisplayFrame.token. Convert MONO
toBOOTTIME only with bracketing offsets differing<=1ms. Missing/ambiguous/dropped matches remain
explicit,never nearest-neighbor or fabricated input-to-present. DisplayFrame.end is an OS present
event,not a measurement of physical photons. OldSettings traces are not reused as app evidence.

Perfetto keeps sched_switch/waking + appatrace + SurfaceFlinger timelines. Compact sched disabled;
only complete nonlost BOOTTIME sched intervals are used for main-thread running-time attribution.
File cap96MiB,4min bounded tracer,unconditional retrieval even ifPIDended. Full raw traces kept.
TestAPKis Release-derived,nonminified/speedcompiled inemulator,not phone or originalR8install state.

Only if data isolates avoidable publication/queue waiting will a single production candidate be
proposed/measured. PreserveFIFO,fullhistory,atomicframe/rows,sync-output,50msblankgrace,onewriter.
