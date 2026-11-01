# Independent306 trace lifecycle V3 — diagnosed pipe and process-exit proof

V1/V2remain failed: each had12original setup failures,0timing windows and0trace starts. C1mechanism
528/44/17remains passed but not adopted. V3does not include C1or a performance comparison.

Three no-APK Android34diagnostics establish the transport boundary:
-41341668403: all3cat inputs have exact572byte config SHA. Directstdin/anonymous pipe start Perfetto;
 heredoc fails. SELinux audit explicitly denies reading mksh's shell_data_file temporary.
-41342164946: `/proc/PID/stat` reads the live Perfetto process while kill0 returns Permission denied.
 The first directory-test/read loop exposed an exit race; that stopExit73is retained.
-41342417785: race-safe proc read/disappearance check returns PROC_ABSENT after SIGINT,stopExit0.

V3uses sh's stdin for the program and base64-decoded anonymous pipe for exact config bytes. No chmod,
named config file or SELinux policy change. Start records readable PID/comm/start ticks; before and
during SIGINT waiting, PID reuse is rejected. Stop requires observed proc disappearance. A failed
stat read counts as exit ONLY after proc absence is rechecked; permission failure never means exit.

The840window/24trace/two-launch matrix,trace config and frozen identity parser remain byte-identical
toV2. Stop proof is strengthened,not relaxed. All raw faults/nulls still reject their trace. No old
frames/packets are filtered or reclassified. `v2-transport-stop-fix.patch` documents the native changes.
Passing diagnostics does not qualify native page traces; this separate V3run must check every window.
No automatic C1performance dispatch,production adoption or formalRelease even on qualification success.
