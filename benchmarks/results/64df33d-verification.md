# Terminal width-retention verification

Production change: `11d96be74690e1147a17275530316e8faae33919`.
Release / full V2 benchmark source: `64df33d466965cd7851cc80954abd27251b03e07`.
The application sources and build inputs are unchanged between these revisions; later changes
fix benchmark completion reporting and diagnostic provenance, not the app's terminal behavior.

## Verified behavior

- The scalar width index belongs to the mounted session viewport binding, not its conditional lazy branch.
- IME fallback does not measure virtual widths. Explicit retry measures the active screen and any new
  surviving archive rows, without remeasuring unchanged historical rows under the same font/source metrics.
- FIFO pruning removes retired width candidates; owner, generation, render revision, columns, font,
  density/direction and replaced-frame changes revoke reuse. Disposing/replacing the binding clears it.
- No full historical TextLayoutResult or Paragraph is retained. The default remains CHUNKED_LAYERS;
  virtual history remains an explicit RENDER option. IME fallback stays latched until explicit retry.
- ProcessSessionPage, status bar/KEYS controls, PTY input, mouse encoding and selection policy are unchanged.

## Remote verification

| Check | Result | Workflow run |
| --- | --- | --- |
| Application Release JVM regressions at 11d96be | 328 passed, no failures/errors/skips | [37218892543](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37218892543) |
| Release viewport instrumentation at 11d96be | 90 passed, including real keyboard, metric invalidation and disposal | [37218892679](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37218892679) |
| V2 fixture JVM tests and production smoke at 64df33d | 25 JVM cases; 12 smoke cases passed | [37254521185](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37254521185) |
| Full production V2 benchmark | Six scenarios, both modes, 1k/5k/10k, three iterations: 36 cases / 108 measurements passed | [37255295641](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37255295641) |
| Original device/source/phase evidence | Six scenario contexts recovered and hash-checked | [37256826428](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37256826428) |
| Application Release build at 64df33d | Passed | [37254521258](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37254521258) |
| Independent APK verification | Signature, manifest, ZIP, ABI and hash passed | [37255299199](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37255299199) |

Android compilation and device execution were performed only on GitHub Actions. Local Python
report/contract tests passed (49 cases); no local Android compilation was performed.

## APK

[Download the Release artifact (ZIP containing app-release.apk)](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37254521258/artifacts/11322680567).
GitHub sign-in is required. The artifact API reports expiry at `2026-10-19T02:22:37Z`.
The APK was verified remotely; it was not downloaded or reverified locally.

- Package: `me.rerere.rikkahub.dev.next.mod`
- Version: `2.1.62`, versionCode `151`
- APK bytes: `83693987`; native ABI: `arm64-v8a`; debuggable: `false`
- SHA-256: `fd8bd1f60dda05ce25a14cec2694f447fa8a37f35e08c6fcf54d5695b7866252`
- Signer certificate SHA-256: `f136fff34c01d34edd2f625e77df5d408f6e09431b2e46e6d58cc5654a722265`

## Findings and limits

In this V2 emulator run, 10k virtual-history IME retry performs zero additional historical width
measurements; its width calculation is 0.97 ms per call. Explicit retry still takes 570.21 ms, and
show/fallback takes 9246.28 ms. The full IME scenario is 12435.12 ms versus the default control's
4297.59 ms on that scenario's same device. These are separate medians, not additive phase accounting.
The remaining focus is the eager fallback handoff and its full row-tree construction/layout.

Cold width measurement and remount still scan all history. Snapshot directory aggregation remains
O(H / 128). This work does not claim to eliminate those costs or all terminal stalls.

V1 run 37220612385 failed its 5k IME case: target PID 4037 logged done at 17:46:16.811, but the
UIAutomator status observer timed out at 17:48:36.477. V2 uses launch-bound, signature-protected
receipts sent only after actual viewport validation; timeouts and production keyboard/scroll
behavior are unchanged. That failed V1 invocation is not an accepted baseline.

The benchmark uses Full compilation and API 34 x86_64 KVM, real production components and system
IME, not the full process-session page or a real PTY/phone. No FPS or cross-run improvement claim
is made; different runners/scenarios/protocols must remain separate. Missing metrics remain absent.
