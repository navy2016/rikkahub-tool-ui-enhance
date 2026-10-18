# Terminal bottom visibility: blank cursor and accumulated TUI height

Baseline `8ed4f06e10039233511c4ef43bb39ed717ef6ef6`, production tree
`639ee4f3286cb647a0b09cfa61a3dc1659586e1d` (Release a914fca). The user explicitly authorized
this follow-up after physical-device reports: Python's blank cursor row half clipped (also when
launched from sh), and Pi's clipping increasing with retained history. Screenshots arrived as text
placeholders, not inspectable pixels; do not invent observed pixel counts or label source reasoning
as a phone reproduction. This is distinct from the completed four-part bundle.

## Scope

- The follow row includes the visible cursor in addition to the last nonblank text. Hidden cursor,
  ordinary trailing blanks and locked reading keep their semantics. Shared by eager/item paths.
- All eager backends use actual Text heights. TUI screen origin is measured history prefix, and
  TUI bottom/physical-grid policy uses measured screen rows; no historyCount*nominalHeight target.
- Preserve complete-frame publication, deferred TUI blanks, full-grid/ALT/SEL/MOUSE fallbacks,
  RAW/IME input, default CHUNKED_LAYERS, 8dp tail, original font metrics and one scroll writer.
- Feed measured geometry into automatic IME avoidance and screen-relative decisions. Do not
  make fixed output padding or PTY resize on keyboard toggling the fix.
- Parser/byte-buffer/keyboard/mouse wire protocol and dependencies are not changed.

## Validation

- New source-bound tests compare actual cursor/Text rectangle to actual viewport, not the same
  formula as the controller. Two negative-control cases use exact baseline production and assert
  the original clipping is observed; candidate must keep those rows fully visible.
- Cursor: blank trailing row, hidden cursor, shell-style nonempty prompt, ordinary/virtual/IME,
  font/density changes, renderer roundtrip, short/empty screen and locked reader.
- TUI: growing variable-height history, stable IDs/FIFO trim, full-grid/ALT, incomplete/stale
  geometry, locked anchor and IME shrink/return. Keep natural heights and complete-frame gating.
- Real-page native PTY tests with status+KEYS visible test tail cursor and main-screen TUI with
  history; existing page/transport cases remain. No fixed sleep as a visibility completion proof.
- Run Release cloud lint/type/JVM + isolated viewport/negative control + actual page/native PTY.
  One initial candidate verification and at most one consolidated correction, preserving failures.
  No local Android/Kotlin/native compilation, no Debug APK, no dependency changes, no APK download.
- Only after gates pass: formal Release and independent verification against a914fca/run39036798535.
  Evidence binds SHA/APK/runner/case inventory. No performance improvement claims from this fix.

This new coverage supersedes neither earlier measurements nor the user's evidence. Previously
passing nonblank-tail tests did not prove blank cursor visibility or accumulated TUI history geometry.
