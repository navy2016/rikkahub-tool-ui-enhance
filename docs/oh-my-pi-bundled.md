# Bundled oh-my-pi / OMP runtime

RikkaHub can bundle oh-my-pi as an offline Alpine PRoot CLI. Android/Gradle builds do not compile Bun/Rust code, and Cargo is not installed on the device for this feature.

Runtime layout after container initialization:

```text
/usr/local/bun/bin/bun
/usr/local/omp
/usr/local/bin/bun
/usr/local/bin/omp
```

Expected optional assets:

```text
app/src/main/assets/bun/bun-alpine-aarch64-musl.tar.gz
app/src/main/assets/omp/omp-alpine-aarch64-musl.tar.gz
```

The bundle must be built and smoke-tested for Alpine/musl/aarch64. Ubuntu/glibc arm64 artifacts are not acceptable for the Android Alpine PRoot runtime.

Cargo/Rust belong to CI only. Device runtime loads prebuilt native artifacts from the OMP bundle.

Diagnostics inside the container:

```sh
rikkahub-omp-help
rikkahub-test-omp
omp --version
omp --help
```

For the TUI, start an interactive background terminal session with `interactive=true` and `tty=true` and run `omp`.
