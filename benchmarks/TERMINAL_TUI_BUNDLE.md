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

Semantic-correct reference commit: `917995852c9e5eb195cb44262f41a1238f422bca`. The parser reference
copies its exact emulator/frame/screen source, with package suffix only; hashes and the source diff
outside approved allocation regions are checked. The following allocation commit can be reverted
without undoing protocol/input/geometry/display corrections. This is not a passed-gates statement.

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

## First integration failure and consolidated correction

At `d86ee64`, JVM457, full viewport162 and both Release-derived compile gates passed. Integration
run39032047858 passed transport15/service2/byte-read137 and five new TUI cases, but its resize peer
waited for `SIZE:10 60` from BusyBox `stty size` and timed out (test line209). The original run remains
failed; page36/settlement12 had not started. Original artifact12101256364 (63733B, SHA256
4ab67ffdde05e5bad69fbbd5f50dc08d04756117f71407ebf5c1df451c0f41bb) was relayed by39033177611.

BusyBox1.36's `stty` uses `get_terminal_width_height`/`wh_helper`: valid inherited LINES/COLUMNS
override the ioctl values. A separate local non-acceptance PTY diagnostic reproduced kernel10x60
versus stty24x80, kernel20x100 versus stty24x80, and restored24x80; `env -u LINES -u COLUMNS stty size`
reported each true kernel value while the parent environment stayed24/80. No local compilation.
The original Android failure log did not capture peer output, so it is not claimed as that missing
observation. The correction changes the test query only, records both readings and the inherited
environment, requires unique request IDs and exact kernel/snapshot sizes, and keeps the15s bound.
Production sources, parser oracle, workload sizes and already-passed gates are not changed/relabelled.
