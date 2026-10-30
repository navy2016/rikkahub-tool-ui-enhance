# Reported212b93e→55db111 slowdown: exact shared-mode source comparison

Reference212b93e04980b1087118007bd59da010a15435d8,candidate55db111129c662067345aa99540b506de305da37.
User describes the latter as a slight "decelerator" relative to the reference. Respect this physical
observation; mode/page/device remain unspecified. No numeric regression factor claimed.

Scope:one already-proven same-boot AB/BA engine, four launch pairs, both versions in each pair.
Frozen212b93einstrumentation source compiled once and reused BYTE IDENTICALLY. Both targets built
Release-derived under the same current toolchain,assets,native overlay,manifest and signing identity;
the only production code delta is the exact18terminalfiles. TemporaryCI overlays restored in finally.
These are not the originally installed APK binaries; ART speed compilation and emulated rendering
do not reproduce phone install/profile/warmup state. No production source changes or formalRelease.

Modes:chunkedLayers(default),lazyHistory,lazyHistoryIme. Ordinary nativePTY,1000history,each with
mounted/IMEvisible/IMEhidden/remount ×3echoes =36samples per invocation.72excludedwarmups plus
288measured samples across8invocations,all retained. Transport preflight for both targets. Frames
and byte/renderer/source/boot assertions unchanged. Echoes are not independent device trials;
analyze paired within-launch medians. Any slowdown remains in report; success means evidence is
complete,not that55db111 is faster. No rerun or altered thresholds to select good performance.

Do NOT compare55fullVirtual with212othermode. No fullVirtual528 or prior396 samples repeated.
This ordinary shared-mode screen is a focused regression probe,not TUI/wholeapp/phone/P99/present
validation. Further scope follows results and user's mode/page clarification,not speculative rollback.
