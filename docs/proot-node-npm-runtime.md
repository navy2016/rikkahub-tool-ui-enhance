# PRoot Node/npm runtime

RikkaHub runs Alpine Linux through PRoot on Android. Modern Node.js/npm stresses
PRoot path translation through `stat`, `realpath`, `openat`, ESM loader work, and
worker threads. The previous workaround injected a large JavaScript monkey patch
into `node`, `npm`, and `npx`; that hid failures but did not fix PRoot.

This runtime uses Termux PRoot assets instead:

- `proot` 5.1.107.72
- `libtalloc` 2.4.3
- bundled `loader` / `loader32` files extracted beside the PRoot binary

The app stores `PROOT_RUNTIME_VERSION` in `filesDir/proot/proot_runtime_version.txt`.
When the version changes, the old PRoot runtime is replaced in-place without
requiring the Alpine rootfs or upper layer to be deleted.

## Regression contract

The instrumentation test `PRootNodeNpmRegressionTest` executes the real in-app
container path and runs `rikkahub-test-node-npm`, which verifies:

1. Alpine installs `nodejs npm` with `apk`.
2. No legacy JS compatibility wrapper is used.
3. Node main thread can `statSync` and `realpathSync` npm files.
4. Node worker thread can do the same filesystem checks.
5. Real npm workflows succeed:
   - `npm init -y`
   - `npm install lodash chalk cowsay`
   - CommonJS `require('lodash')`
   - ESM `import chalk`
   - `npm exec`
   - global install/run/uninstall of `cowsay`

The GitHub Actions workflow `.github/workflows/proot-node-npm-regression.yml`
runs this on an x86_64 Android emulator. Normal APK builds still package only
`arm64-v8a`; the workflow opts into x86_64 with
`-PincludeX86_64AbiForTests=true`.
