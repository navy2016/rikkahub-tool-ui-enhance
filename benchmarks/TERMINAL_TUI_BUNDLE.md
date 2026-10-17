# Terminal protocol / consistency / parser / optional display bundle

Baseline: `7f60e641f3840c1a3e025202496a381954266c8e` (production equals b7a93c9).
Branch: `opt/terminal-verified-a79e7b6`. The user authorized one implementation/acceptance batch.
No local Android/Kotlin/native compilation, dependency changes, debug APK, APK download or subagents.
Use explicit branch dispatch and `[skip ci]` commits; first verification plus one consolidated correction.

## Scope and non-goals

1. Distinguish CSI prefixes/intermediates; xterm modifyOtherKeys negotiation, harmless unsupported
   Kitty probing (no false capability response), correct ED cursor behavior, complete IME graphemes,
   X10/UTF8/SGR/URXVT mouse encoding and actual-layout pointer boundaries/modifiers/pixel metrics.
2. Serialize resize against parse with acknowledged dimensions; immutable atomic read-only screen
   snapshot and cell/text-offset mapping; one FIFO UI input entry preserving command/control/paste
   order and raw bytes. Keep protocol replies on the same session writer, raw stream/backpressure intact.
3. Bounded decoder scratch, direct BMP parse, immutable style/cell reuse and independent historical
   row arrays. Compare to the frozen semantic-correct parser before these performance edits; keep all
   input chunks, invalid UTF8, annotations, cursor state, IDs/generations and protocol responses exact.
4. New opt-in RENDER paths: verified explicit Pi regular transcript history (RAW input stays RAW),
   and cursor overlay on the existing Text row layout. Existing modes/default and selection/mouse/
   alternate/full-grid fallback rules remain. Unknown main-screen TUI is never inferred from no ALT.
   Cursor overlay preserves text, spans, natural geometry, cursor reservation and one scroll writer.

No complete Kitty keyboard protocol, alternate-screen transcript recording, font/height approximation,
history truncation, generic shell-command reinterpretation, or raw-byte conflation. User controls keep
their actions/labels; protocol corrections intentionally fix malformed bytes rather than preserve bugs.

## Acceptance

- Independent spec vectors (including current erroneous legacy expectations), chunk-boundary/random
  parser differential, atomic snapshot concurrency, acknowledged resize/failed ioctl, mixed UI inputs.
- Release cloud JVM + viewport Release lint/type checks; exhaustive existing terminal suites remain.
- Real-page/native PTY tests cover protocol query/response and exact IME/mouse/control bytes, actual
  resize, default/virtual renderers, and new opt-in display paths; bind source and installed APK hashes.
- Parser AB/BA allocation/timing on identical workload after semantic fixes, not pre-fix vs post-fix.
  New display work/geometry tests retain slower samples and require actual last-row visibility.
- Trace read completion and lock acquisition separately; original timing protocol remains labelled.
  CPU/allocation/work counters never become a phone-FPS promise. Physical-device acceptance pending.
- Performance candidates lacking evidence/benefit may be reverted independently of correctness fixes.
  Preserve original failures; never relax assertions/repeat for favorable timing. Release and independent
  APK verification follow all gates. Report/evidence only; no APK attachment.
