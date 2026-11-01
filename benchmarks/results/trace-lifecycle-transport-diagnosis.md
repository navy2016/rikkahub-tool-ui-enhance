# Trace lifecycle qualification — transport defects diagnosed; V3 completed/rejected

**Final update:** run41343554975 completed. All840windows/24raw traces independently replayed;
qualificationPassed=false,16formalr20windows have incomplete endpoints. See`trace-lifecycle-v3-rejected.md`.
The historicalpending descriptions below are superseded; no qualification run is active and none is redispatched.

C1mechanism528JVM/44component/17scene has passed independently. This work is a separate306-only
acquisition qualification, not C1performance/adoption and not reclassification of rejectedA1/B1.

| Execution | Evidence outcome | Terminal timing windows |
|---|---|---:|
| V1`41338176466` / `2f1535c` | Build passed; Runtime.exec(String) split sh-c quotes; all12original cases fail before tracing |0|
| V2`41339783414` / `90815fa` | Build passed; shell works but Perfetto reads empty heredoc config; all12original cases verified |0|
| Transport diagnostic`41341668403` / `5e46c113` | No APK. Exact572B config in direct/heredoc/pipe; direct andpipe start,heredoc fails |0|
| Stop diagnostic`41342164946` / `86b07f5` | No APK. Live proc readable; kill0 denied; first proc-read loop retains race exit73 |0|
| Race-safe stop diagnostic`41342417785` / `c59fb528` | No APK. SIGINT followed by actual proc disappearance,exit0 |0|
| V3first`41342838188` / `9985cfc` | Build blocked by JitPack401 on unchanged sqlite-android:-SNAPSHOT; native step never starts |0|
| V3infra retry`41343554975` / `a5ccea0` | Build passed; native840window qualification running. All V3source/driver/config/protocol bytes unchanged |pending|

## Confirmed causes and correction

Android14UiAutomationConnection calls Runtime.exec(String), not a shell parser. Execute only`sh`
and pass its program overstdin. POSIXhost heredoc tests alone were insufficient for the next layer:
the Android shell stores heredocs under`/data/local/tmp/sh*.tmp`; original SELinux audit explicitly
denies `perfetto`read of`shell_data_file`. An anonymous pipe succeeds with the same configuration hash.

The same audit denies`signull`fromshell toPerfetto. `kill -0`failure is therefore NOT proof that a
traced process exited. Original diagnostic records show readable live`/proc/PID/stat`while kill0
returns Permission denied. A race-safe proc check accepts failed stat read only after verifying the
proc directory disappeared; persistent read/permission failure is an error. Subsequent diagnostic
returns`PROC_ABSENT`andstopExit0. V3also binds PID/comm/start ticks before and during waiting.

These findings do not establish which historical A1/B1packet/frame was affected. Old failures and
frozen parsers remain unchanged. Status success never substitutes for complete trace content.

## FrozenV3 acquisition

-Unmodified306target; no C1/A1/B1/P2source overlays. Test collector is isolated and removed after build.
-Two launches,12process cases each,840windows=40warmup+800formal,24raw traces. No dropped/retried windows.
-Trace begins after stable native page/IME/history setup and stops before ALT restoration/teardown.
-Same32MiB ring/1s writes/240s96MiB bounds,ftrace+sched+gfx/view/wm/dalvik+FrameTimeline. The separate
 process_stats producer is explicitly excluded before sampling,not filtered out of recorded bytes.
-Every observed noninitial drop flag,unclosed relevant span,conflicting frame identity or missing
 required endpoint remains fatal under the byte-identical identity parser. No time-range exception.
-Single bounded summary; all原件,attempts,nulls and lifecycle receipts retained in artifacts.

V3protocol SHA256`3b1565f113c9eff14ceb11e71c1bf1ad3a6c799537838fb92981c62a3bca3288`;
driver`83f7f3a15b76266ceb1f57705384e968753d504134de3a8f187180c860d3bf4a`;
config`e379b818afffb31c1b61cb4d8a8a8550f14da2fbce2a0d4abd0828674ecaa374`.
18newtransport/lifecycle+20original identity host tests pass. This is not native840window qualification.

The public JitPack metadata URL returned200without credentials after the firstV3build401, naming
snapshot`-80cedc8888-1`. No dependency declaration or version was changed and no token sent toJitPack.
The retry preserves the original failedrun and has no earlier device sample to replace.

## Recovery

Work directories under`/workspace/terminal-review/`:
`full-virtual-geometry-c1`, `full-virtual-trace-lifecycle`, `full-virtual-trace-lifecycle-v2`,
`full-virtual-trace-transport-diagnostic`, `full-virtual-trace-stop-diagnostic`,
`full-virtual-trace-stop-diagnostic-v2`, `full-virtual-trace-lifecycle-v3`,
`full-virtual-trace-lifecycle-v3-infra-retry`.

Read existing41343554975state/receipt only; never duplicate its dispatch. If native qualification
completes, verify all840windows/24raw traces from the same source with the frozen parser. Even a
pass does not automatically dispatch C1performance,adopt a candidate or build a formalRelease.
Production remains306/main treeac977984. See`benchmarks/TERMINAL_BUG_LOG.md`.
