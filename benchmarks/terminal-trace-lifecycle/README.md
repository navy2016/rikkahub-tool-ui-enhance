# Independent306 trace lifecycle qualification V1

C1 mechanism passed, but it is not included here. This is a NEW acquisition qualification, not a
rerun/reinterpretation of rejectedB1 and not a comparison or performance claim.

Two fixed launches ×12process cases ×420windows =840windows (40warmup,800formal),24raw traces.
Native page/PTY, real IME, MAIN/ALT redraw andrefresh/SYNC/return retain21/63window durations and
all original byte/history/visibility/identity checks. No window is omitted, retried or replaced.

## Explicit recipe changes before sampling

-The driver owns the shell-UID Perfetto process using Android14public UiAutomation RWE pipes.
 Exact config bytes are passed on stdin, with checksum/PID/exit receipts and descriptor cleanup.
-Start only after app startup, settings, native page, PTY history and IME/grid setup are stable.
-Stop+wait+hash the raw file after all fixed windows, BEFORE ALT history restoration, listener
 removal, Activity destruction or settings cleanup. The app is not changed or forced to draw.
-Keep the exact original32MiB ring,1s writes,240s/96MiB file bounds, ftrace categories+sched and
 SurfaceFlinger FrameTimeline. Exclude the separate linux.process_stats producer from this
 endpoint trace. This is declared here, not an after-the-fact packet deletion or parser exception.
 Startup/device/package diagnostics remain separately bound in the artifacts.

The old frame-identity parser/protocol remain byte-identical. ANY received packet-loss marker
outside the original first-packet exception, unclosed relevant span or conflicting identity is
fatal for its trace. No time-range exemption, raw trace filtering or nearest-frame matching.
Every840window needs its own submitted/presented exact chain. Collection failure is not success.

All same-run original logs, raw traces, lifecycle receipts and nulls are retained even on failure.
A single bounded summary is published; long evidence is in the artifact, not10+annotations.
No candidate/production adoption/performance dispatch is automatic even if qualification passes.
This validates this recipe and matrix only, not universal future trace completeness. Future C1
performance would freeze the same qualified recipe for both arms and still check every trace.
