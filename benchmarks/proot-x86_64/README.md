# Test-only Android x86_64 PRoot fork compatibility

The API 34 x86_64 emulator rejects musl's legacy `fork` syscall. Packaged PRoot 5.1.107.72
turns the trapped call into ENOSYS; BusyBox ash exits with `can't fork: Function not implemented`
before the normal session `stty` preamble completes. Builtin output and direct exec are unaffected.
The production arm64 APK does not have this syscall path; its runtime is not changed here.

This overlay rebuilds exactly Termux PRoot commit `58aad2cb1c36ea6af7b32d76ccd5bf8d0a967939`
(v5.1.107.72), adding only `fork -> clone(SIGCHLD, 0, 0, 0, 0)` to the SIGSYS/ENOSYS
compatibility handler for x86_64. The same mapping is documented and tested in
https://github.com/termux/proot/issues/237#issuecomment-1178592429 . Neither Android's
filter nor PRoot's acceleration filter is disabled. There is no guest LD_PRELOAD wrapper.

The GitHub Actions-only build links the repository's unchanged x86_64 libtalloc, using
the exact 2.4.3 header, and reuses existing loader/loader32 assets. NDK r27c produces an
optimized PIE with `$ORIGIN` runpath. Build helpers may be fetched only at pinned commits
or hashes; target, source, patch, library, header and tool identities go into the evidence.
The separate header patch supplies the missing `string.h` declaration in ashmem_memfd.c
required by NDK clang; it changes no runtime policy or compiler diagnostics.

Only `terminaltest` uses `app/build/generated/pipelineAssets/proot/proot-x86_64` as a
higher-priority asset overlay. `app/src/main/assets`, normal Release variants, the main
PRoot version and runtime selection are untouched. This is an explicitly documented
test environment adaptation, not evidence that arm64 PRoot performance improved.

Reproduction evidence before the overlay: workflow 37738137774, source 7bc3dc0.
Login-only, raw/cooked PTY, pipe and redirects passed; a command requiring fork failed.
The full-page results must retain the overlay hash so these environments cannot be mixed.
