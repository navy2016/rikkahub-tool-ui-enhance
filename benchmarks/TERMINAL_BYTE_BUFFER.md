# Raw terminal byte-tail optimization

Baseline: `6e81739d8790c524ae25b010dcd6e21ee0ee0a4d` on `opt/terminal-verified-a79e7b6`.
The user requested continued terminal optimization after the Pi-mode diagnosis. This is a new,
bounded performance change, not another B1–B6 run or a TUI-history feature.

## Change and invariants

The previous 256KiB `SessionOutputBuffer` appends to `ByteArrayOutputStream`. Once saturated, each
read chunk copies capacity+chunk bytes to a new array, copies the retained capacity into another
array, then rewrites that retained array into the stream. Small reads also copy the whole stream
before selecting the requested range. These operations happen independently of UI rendering.

`TerminalByteRingBuffer` copies at most min(chunk bytes, capacity) per append into a fixed array,
using at most two spans. Reads allocate/copy only the requested retained range. Absolute `Long`
offsets, clamped old/future offsets, read-limit clamping, `hasMore` and per-read UTF-8 replacement
semantics remain unchanged. Inputs and returned arrays never alias stored bytes. Synchronized
methods publish bytes and offsets atomically. An impossible Long counter overflow fails before
state mutation rather than wrapping metadata.

Tradeoff: the first non-empty write reserves 256KiB even for a short-lived session; empty sessions
allocate no byte array. Saturated sessions do not keep the legacy growth array or transient full
tail copies. Full `snapshotAsString` and line-log reads remain O(retained bytes); the manager still
allocates its public `BufferRead` DTO. No claim of zero overall allocation or phone FPS is made.

Only production `BackgroundProcessManager.kt` and the new buffer file change. The parser still
receives EVERY input byte through the existing output mutex, then queues responses and publishes
notifications. The raw tail's existing eviction is not terminal scrollback truncation. UI COPY
continues to read the live parsed emulator; rendering modes, stable row IDs, geometries, IME,
single vertical writer, TOUCH/MOUSE/SEL, CLR and alternate-screen semantics are untouched.

## Verification protocol

- Local changed-file whitespace/Python syntax/workflow YAML/shell checks and reporter contract tests.
  Do not compile Android/Kotlin/native locally or install/upgrade project dependencies.
- `terminal-emulator-tests.yml suite=byteBuffer`: 12 differential/edge/concurrency tests, 2 manager
  wiring tests, 1 benchmark case, 11 existing bundle contracts and 13 live-snapshot tests (39 total).
  The 15 new tests have the unique `byteRing` token; no retries, skipped cases or missing suites pass.
- Benchmark protocol `terminal-byte-buffer-bench-v1`: old algorithm vs ring, same JVM, two scenarios
  (saturated 4KiB append and 64-byte reads), 2 warmup AB/BA pairs and 4 measurement AB/BA pairs per
  scenario. Eight warmup / sixteen formal samples. Saturation and construction are outside timing.
  HotSpot's actual per-thread allocation counter is required; reflection avoids introducing a JVM
  management dependency into Android compile stubs. Wall-clock duration is reported, not filtered.
  Each formal pair must reduce allocation by >50%, and independently regenerated snapshot hashes
  and read checksums must match. The test-only legacy algorithm is whitespace-normalized against
  the fixed baseline source before publishing evidence.
- `terminal-pipeline-tests.yml suite=all`: keep the 15 original transport probes and 2 service tests,
  then run the new byte-buffer PTY case and the existing 36 real-page echoes / 12 settle receipts.
  All use the same Release-derived APK pair and non-root guest. Exact source and installed APK
  hashes are bound to receipts; each mode/case gets independent process lifecycle and captures.
- PTY protocol `terminal-byte-buffer-pty-v1`: 3 rounds, 7,000 ANSI/CJK/emoji lines each, 455,015 bytes
  per round plus the 14-byte startup marker = 1,365,059 bytes. Validate all/tail/new/read-log results,
  137 exact read checks, tail SHA256 against Python-generated source bytes, native-PTY backend,
  unchanged 1,000 parsed history-row limit, clean exit and session removal. Poll actual completion,
  never replace it with a fixed delay. `suite=byteBuffer` can isolate this case when appropriate.
- Only after both gates pass, dispatch `release.yml build_type=release`, then `verify-release-apk.yml`
  with the exact run/SHA and previous Release `38914679438` as read-only package/signature reference.
  No APK attachment/download requested; provide verified Actions links and compact evidence.

Old V6, old 30-minute pressures and whole-batch AB/BA remain archived, not relabelled or re-run here.
The frozen whole-batch experiment has its own source allowlist and is not extended by this change.
This raw-tail microbenchmark does not predict default eager cold composition, PTY end-to-end speed,
phone keyboard latency, GPU FPS, thermal behavior or absence of memory leaks. Physical-device
performance acceptance remains pending. Preserve the first failure if any; fix the cause once and
run only the affected gate, never rerun to select a favorable sample.
