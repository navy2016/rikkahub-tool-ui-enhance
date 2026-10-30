# Trace transport preflight and new physical-device feedback

User:"55db111版APK运行时整体有一点点缓慢的感觉" and"继续". Preserve this qualitative
physical-device observation. Renderer/device/page/comparison details are not specified. DEFAULT is
still CHUNKED_LAYERS; do not attribute overall APK slowness to FULL_VIRTUAL without evidence.
Existing virtual-only emulated measurements neither disprove this feedback nor establish its cause.

Further user clarification:"感觉55db111版本是在212b93e版本上加了一个轻微减速器".
Treat212b93e04980b1087118007bd59da010a15435d8 as the explicit reference and55db111 as the reported
slower build. No numerical slowdown factor or affected mode/page is established yet. This is a
reported cross-version regression,not evidence that every path has the same slowdown mechanism.
Git audit:exactly18app/src/main files differ,all terminal-related;parser,BackgroundProcessManager,
native/assets,app/build.gradle.kts andgradle/libs.versions.toml unchanged. This does not eliminate
shared UI/codegen/package/runtime effects. Inspect shared row/viewport/publisher paths first,not
only the new FULL_VIRTUAL branch. Previously reported Web/profile/DEX output differences remain.

Next scoped step is trace transport diagnosis only. Frozenproduction55db111/main8caf50d5c40e228f1d5bcc09d3db505757568b68,
no app or test APK built/downloaded/installed, no old528/396/30samples repeated or replaced.
Disposable Android14/non-rootKVM/SwiftShader guest and its built-in Settings window. Fixed matrix:
foreground file-writing,foreground buffer readback,background-wait;2seconds each,sameconfigsources.
Keep complete native diagnostic output. ALWAYS attempt output retrieval evenif exit/PID/stop fails.
Decode Settings-PID actualSurfaceFrames and distinguish transport success from application performance.
Do not call this appFPS,input-to-present attribution,phone validation or an optimization.

Why:the previous --background parent returnedPID before data sources completed; all PIDs later
disappeared and collection was skipped afterkillfailure. UpstreamPerfettov40.0 explicitly offers
--background-wait for initialization readiness. Foreground invocation preserves post-start errors.
No root permissions or SELinux policy changes; config passedstdin. User-facing application changes
must wait for useful evidence and preserve all original render/input/history/selection contracts.

Result:d166bfc/run41070952490 succeeded. foregroundfile/buffer/backgroundwait captured24/38/37
completeSettingsSurfaceFrames,3unmatchedboundary starts retained. Allnativeoutputs/rawtraces
independentlyreread;latePIDnotfound doesNOTmean missingfile. OldP0trace cannot be retroactively
recovered. Collection code now usesbackground-wait and isolatesstart/stopstatus frompull;the fullP0
matrix was not replayed. Transfer41071827540 failedpermissions;exactisolatedmetadatarecovery and
relay41072192579 succeeded without rerunningpreflight. Source/traces under/workspace/terminal-review/trace-preflight/.
