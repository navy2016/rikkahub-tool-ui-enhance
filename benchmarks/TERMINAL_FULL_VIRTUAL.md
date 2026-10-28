# Full virtual renderer: independent opt-in, every existing terminal scene

User reported little/no perceptible improvement from TUI chunk layers on a real long-history session.
That feedback supersedes any implication that component improvements established phone speed.
New explicit scope: add `FULL_VIRTUAL` / `fullVirtual` / 全量虚拟化, preserve all nine old choices
and their default/eligibility. Baseline09d863449e2e16cf8fd1eac95d5e11264934156b (production212b93e).

## Architecture and invariants

- One independent row-item lazy surface for ordinary/TUI/IME/SEL/MOUSE/full-grid/alternate. Only
  visible/precomposed rows retain UI/Text layouts; no all-history eager fallback in the new mode.
- Sparse publication uses immutable archived history plus bounded physical-screen states. Keep
  existing RAW bytes, input FIFO, protocol processing, frame/row atomicity and50ms blank grace.
- Preserve natural Text shaping and exact horizontal maximum. Cold exact width discovery remains
  documented work; never substitute columns*nominal cell width for arbitrary shaped Text.
- Physical screen row heights are at most80 scalars, measured by the same font/text engine and
  verified against actual visible Text. No historyCount*nominalHeight. SCREEN follow/minimum,
  explicit grid extent and keyboard offset-preservation differ from ordinary tail following.
- Stable-ID selection independent of composed rows: long-press word, handles, cross-offscreen
  endpoints, copy/select-all, keyboard/accessibility actions. No hidden giant TextField or materialized
  selected-history tree; output COPY continues to copy the emulator's entire retained transcript.
- Mouse hit-testing uses visible Text positions and exact physical screen origin, not history
  estimates. The original input writer/coordinate generation check is unchanged. ALT remains a
  screen, not an invented transcript recorder; CSI3J/CLR retain their semantics.
- Keep the single controller/effect executor. Selection-edge autoscroll submits intent through
  that owner, not a competing lazy.scroll coroutine. Reader anchors use stable IDs and real clips.

## Acceptance planned before candidate dispatch

Cloud-only Release compilation; no local Android/Kotlin/native builds, dependencies/font upgrades,
APK download or agents. Edits use apply_patch, commits [skip ci], explicit optimization branch.
Initial candidate plus one consolidated correction; keep every original failure and timing sample.

Full old JVM/viewport regressions plus new model and real viewport cases:1k/5k/10k, continuous FIFO,
TUI/full-grid/ALT, actual/model IME, nonavoiding IME, locks/jumps/fonts/density/RTL, mouse cell/pixel,
cross-viewport selected copy/select-all, stable selection while rows recycle and cleanup. Require
new mode remains virtual in all scene combinations, and old choices retain their prior gates.

Same-device fixed order-reversed full-page/component observations, including cold-width/state and
actual cold mount/output completion and input responsiveness. No "FPS" from inclusive CPU/commit
spans. UI row/measurement counts must scale with visible content not history; full retained history
bytes/IDs and physical-grid dimensions remain. Report all data, including slow cases.

Real nativePTY page: new explicit mode, sustained bounded output during keyboard/reentry/selection,
complete read/parsed bytes, actual cursor rectangles, kernel dimensions, real clipboard and input,
session/capture/UI cleanup; old original regressions independently covered. Release and independent
verification follow successful gates. On-device improvement is still a user retest, not a promise.

Fixed initial component comparison:1k/5k/10k, current TUIlayers vs fullVirtual, five AB/BA rounds
(one warmup/four formal),30cold+stream pairs with30disposals on one APK/process. Every timed cold
entry includes state creation and exact width preparation.5k/10k3tick stream must improve all four
pairs and median<=80%control. Other medians<=115%control+20ms. No post-result threshold changes.
New viewport inventory7methods/45scene rectangles plus offscreen all-copy/handle-drag and30samples;
all legacy173viewport methods remain. Frame/render/read byte contracts remain independent gates.

## Initial result and one targeted correction

Candidatec07100dfb9be25c42e502cb5e0a429fc08cc9006/productionc4da501312976a99776dc99fcd2d7db78bc1256a:
483JVM40272600220 passed. Actual-page/PTY40272615096 passed; viewport40272608906 failed2/180,
both new selection methods. Old173 and other new5 passed. Original2failures remain failed:
system Copy menu required value null(line157);"Drag never recycled the start row"(line192).
No production Release was built. First30performance samples and30disposals remain exact originals,
fixed six criteria pass (component scope only). Original artifact12629136625/10343406B/SHA256
a48669cba4a9ca69fa856020fa4e611c8c43c1fef8dfb2a453cb279f6539d729; raw log59e3a09f4ef6d758059c4eaa4cae03a70af0c63b75e1b27459f85b10ec3714eb.

Driver inspection:Popup.boundsInWindow is local to its separate window, while UiDevice injects
screen coordinates. Native waits/drag do not advance Compose's test frame clock. Corrected driver
waits for Compose idle before native menus, injects222events in screen coordinates with222explicit
frame advances(220fixed MOVE steps), restoring clock/cancelling touch in finally. Text/copy/recycling
assertions are unchanged. This is an identified driver defect, not reconstructed events from the
old failure. Production remains byte-identical; no first-performance resampling. Only these2methods
run in fullVirtualSelection. Its reporter binds the original failed run/artifact/production hashes
and labels prior/current evidence independently. If this one correction fails, stop without another
automatic round or Release; retain the new diagnostic original.
