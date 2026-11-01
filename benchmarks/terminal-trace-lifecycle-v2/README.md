# Independent306 trace lifecycle qualification V2 — transport correction only

V1run41338176466/source2f1535c remains failed. Its12original failure cases were independently read:
zeroobserved windows andzeroPerfetto starts. Failure was before trace start, at shell `rm` setup:
`Trace shell exit receipt missing` / `no closing quote`.

Android14 UiAutomationConnection calls Runtime.exec(String), which tokenizes whitespace instead of
interpreting shell quotes. Passing `sh -c '... ...'` did not preserve the program argument.

V2 executes ONLY`sh` and sends the complete POSIX program on stdin. The unchanged Perfetto config
uses a distinct quoted heredoc so the shell program cannot be consumed as config input. Exit status,
stdout/stderr limits, timeouts and descriptor cleanup remain. Original tokenization is reproduced
in host tests; corrected stdin, exact quoted config bytes and failure status are tested explicitly.

`v1-transport-fix.patch` shows the complete native change (shell transport+protocol version marker).
Production306, frame identity parser, trace config,840window/24trace/two-launch matrix, all lifecycle
boundaries, gates and no-candidate/no-performance scope are unchanged. V1files are not edited.

This is separately versioned qualification after a proven zero-window harness failure, not B1
replacement sampling or relaxation of an observed failing trace. Any V2missing/unknown/conflicting
identity still fails. No C1performance or adoption is automatic. See V1README and bug log for scope.
