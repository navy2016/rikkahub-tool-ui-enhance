# Full-page terminal/PTY diagnostics

The opt-in `-PterminalPipelineTests=true` adds the `terminaltest` build type. It inherits Release,
is non-debuggable/profileable/unminified, has the distinct ID `me.rerere.rikkahub.dev.next.terminaltest`,
and contains the test Activity. None of those test entry points belong to the normal signed Release.
Only GitHub Actions builds/runs the Android target; no local Android build is required.

`TerminalPipelineInstrumentedTest` mounts the real `ProcessSessionPage`, calls the production session
manager, and requires the bundled PRoot plus native PTY backend (no pipe fallback accepted). Its fixed
child writes history, then echoes one submitted request at a time with terminal line-discipline echo
disabled. The real input field's semantics actions run the normal submission callback. Actual keyboard
show/hide and composition remount are included; this is not physical typing or Android process death.
There are 3 render modes × 4 phases × 3 samples = 36 validated echo samples from 3 tests.

`TerminalPipelineTrace` is inactive unless explicitly installed by instrumentation. It accepts only
stage enum values, counts, revisions and a backend flag. It stores no command/output/input text,
paths or externally supplied labels; it is capped at 4096 events and reports overwritten events.
It has no persisted switch, logging callback or public Activity intent to enable it in normal usage.
Captured events use `System.nanoTime`, not the wall clock or a simulated Compose frame timestamp.

The fixed child echo, exact byte count, single-flight requests, fresh capture windows and matching
frame revisions permit phase correlation for THIS fixture. Unsolicited real terminal output is not
automatically attributed to arbitrary user input. Enqueue/emit completion can race the actor/collector,
so completion timestamps are not used to assert producer-before-consumer order.

`inputToDrawMs` ends at the first draw callback for a frame containing the completed reply; the row
need not yet be aligned inside the viewport at that first draw. `inputToVisibleCheckMs` is a separate
test-observed upper bound including semantics/idle wait overhead, after the actual row is wholly
inside the viewport. Neither proves GPU presentation time, phone FPS or an input latency percentile.

Current runs also require `panelCompositions`: committed `TerminalInteractivePanel` compositions in
the complete sample window, starting before SetText and ending after the visibility/idle check.
It includes input and submission work, not just output rendering. The probe is absent when capture
is disabled. Earlier V1 archives have no such field and must not be assigned a fabricated zero.
The panel now observes only the controller's distinct AUTO/LOCK projection; anchor, gesture and
effect updates still flow to the original binding, executor and persistence consumers. The viewport
suite separately compares both subscriptions on the same controller, including lifecycle restart and
controller replacement. Its work-count evidence is not a full-page old/new timing comparison.

The runner verifies source SHA, target/test package IDs, matching signers, ZIP integrity, x86_64 native
PTY and target hash, fully compiles the installed target with `cmd package compile -m speed`, and uses
the existing non-root KVM startup primitives. Output artifacts include exact source hashes, APK hashes,
device/IME identity, compact validated samples and bounded failure diagnostics. Test absence, skips,
partial samples, backend mismatches, invalid timings and trace overflows must fail validation.

The disposable test package is pregranted POST_NOTIFICATIONS before launch. The real app targets
SDK 28, where a foreground notification channel can trigger Android 13+'s automatic permission
prompt and pause an Activity that Compose instrumentation needs RESUMED. This does not change the
production app's manifest or runtime permission policy. Test Activity configuration handling matches
RouteActivity and emits fixed lifecycle/focus probes for diagnosing lost UI roots.

The x86_64 guest additionally needs the [pinned test-only PRoot fork overlay](proot-x86_64/README.md):
Android rejects musl's legacy fork, which otherwise aborts the existing stty preamble with exit 2.
The dedicated test asset converts it to clone(SIGCHLD); normal arm64 Release stays unchanged.
Every test manifest and result records the overlay, compiler, patch and source hashes. `suite=all`
runs the transport preflight first and the full-page samples second using the same APK/device,
preserving their separate logs. A transport failure is never ignored to proceed to page timing.

This first step measures the current renderer without changing output batching, synchronized-output
hold, TUI resize, scroll animation handoff or the renderer default. It is a diagnostic baseline; no
speedup is established until a controlled same-device comparison succeeds.

The separate [`suite=paired` experiment](terminal-pipeline-paired/README.md) builds a frozen
one-site legacy subscription control and the current production page with the identical driver.
Both pass transport and excluded warmup runs before four AB/BA pairs on one booted guest.
It preserves 288 measured echoes, 72 excluded warmup echoes and installed APK hashes; paired
launch-median differences are exported without requiring either arm to be faster.
