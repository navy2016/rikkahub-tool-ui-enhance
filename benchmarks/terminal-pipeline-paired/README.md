# Same-device full-page subscription comparison

Run the existing `terminal-pipeline-tests.yml` workflow on
`opt/terminal-verified-a79e7b6` with `suite=paired`. All Android and native builds run in
GitHub Actions. The normal arm64 Release and the production subscription stay untouched.

The experiment freezes the `3f33ee3` production page. `legacy-panel-subscription.patch`
restores only the two-line full-controller-state subscription from `616010f`. Both arms
keep identical trace instrumentation and all other production and test sources. The
entire candidate/control page hashes, source maps and exact patch are checked. The
builder reverses its patch in `finally`; it refuses a dirty content checkout.

The candidate Release-derived, non-debuggable, unminified target and driver are built
first. Only the target is rebuilt for the legacy arm. Both arms must use the byte-identical
test APK, signer, Android manifest, resources, native libraries and pinned PRoot overlay.
There is no production runtime switch. The original [pipeline protocol](../PIPELINE.md)
still checks byte counts, frame revisions, real visible row geometry and IME behavior.

One non-root KVM guest stays booted throughout. Both targets pass the 15 transport probes
and one excluded full 36-sample warmup. Measurement order is legacy/candidate,
candidate/legacy, legacy/candidate, candidate/legacy. Each invocation reinstalls the
bound target and common driver, verifies the installed base APK hashes, compiles the
target with `cmd package compile -f -m speed`, and runs each mode in a fresh process.
Setup/dexopt are outside the echo timestamps; this is not a cold-start experiment.

Each arm has 4 launches per mode, 4 phases per launch and 3 echoes per phase: 144 measured
echoes per arm, 288 total. The 72 warmup echoes are retained separately and excluded.
Every receipt binds the arm, installed APKs, monotonic invocation interval, boot ID,
screen/IME identity and unique launch IDs. Reboots, mixed packages, overlaps, missing or
duplicate samples, wrong AB/BA ordering, trace drops and failed preflights reject the run.

For each mode/phase/metric, compute the median of the 3 echoes within each launch, then
candidate-minus-legacy for each of the 4 launch pairs. Publish all four deltas, their
median and both arms' medians. The 12 echoes per arm are not 12 independent device trials.
There is no success criterion demanding lower latency: report increases and decreases.
`panelCompositions` includes SetText/submission/output; draw and visibility-check timings
remain synthetic-input diagnostics, not physical typing, GPU presentation or phone FPS.

The successful result, both APKs, all logs and receipts are artifacts. Separate bounded
annotations export the plan/summary, both preflights and warmups, and each measured pair.
All sections share a SHA-256 of the canonical result (`sort_keys=True`, compact JSON), so
the complete result can be reconstructed and verified without downloading full logs.
