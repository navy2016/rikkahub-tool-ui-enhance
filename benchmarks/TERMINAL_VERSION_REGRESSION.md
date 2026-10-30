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

## Completed comparison and clarified global feedback

User confirms allinteractivepages(chat/settings included)and both-default terminalIMEopen/close.
Run41071712739/source3ab091707ea51617a9e4cd12032d892ea7bb5297 completed288measured/72warmup/
30transportprobes,oneboot/common-driver753751e9cb240c03f5f7208c39b9bcc6f95ff610b394210efa182c7ac4b35149.
Default draw paired median deltas(55minus212):mounted−2.726ms,imeVisible+13.197ms,
imeHidden+2.326ms,remount+4.306ms. IMEvisible pairs[+2.641,+73.674,−6.593,+23.753]ms;
retain thelargeoutlier andnegativepair. This is echo afterkeyboardphase,NOT measuredIMEanimation
duration or proof of a uniformglobalfactor. Panelcompositionpairedmedianunchanged1→1.
No P99/phone/wholeapp claim; commonbuildnonminified/speedcompiled differs from originalR8APK/ARTstate.
FullresultcanonicalSHA2566d802562ef9143d03711e75e44b637806d896cfe4eede69e49d0e1ec1b617ca9.

Originalsignedprofileaudit41075540623 passed after correctingthetest'sAPKCRCdefinition;both profiles
matchtheirDEX CRC/method/typeindexes. Oldaudit41075099086 is harnessdefinitionfailure,notcorruptAPK.
Packagedvaliditydoesnotprove installedphoneART/AOTstate. Optionaluser-invoked read-onlycollector
benchmarks/terminal-phone-observation/collect.py reads exactAPKhash,OS/model,animationvalues,limited
ARTstatus,batterytemperature and numericgfxinfo;no install/clear/force-stop/compile/settingschanges.
Assistant hasNOT run it on thephone. Rootcause unresolved;no productionchange/newRelease.
