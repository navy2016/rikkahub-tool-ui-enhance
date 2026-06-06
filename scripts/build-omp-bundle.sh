set -eu

apk add --no-cache bash ca-certificates curl git tar gzip xz libstdc++ gcompat build-base python3 make gcc g++ pkgconf openssl-dev zlib-dev
update-ca-certificates || true

# Alpine 3.19 packages ship an older Cargo that cannot parse Cargo resolver=3.
# Install current stable Rust in the Alpine/musl builder; Rust/Cargo are build-time only.
curl --proto '=https' --tlsv1.2 -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal
export PATH="$HOME/.cargo/bin:$PATH"
rustc --version
cargo --version
# napi-rs passes an explicit aarch64-unknown-linux-musl target. In native Alpine arm64,
# the system C compiler is already musl/aarch64, but Rust expects this cross-linker name.
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER=cc

work=/work/.omp-build/work
out=/work/app/src/main/assets
rm -rf "$work"
mkdir -p "$work" "$out/bun" "$out/omp" /work/.omp-build

curl -fsSL https://bun.sh/install | bash
export BUN_INSTALL=/root/.bun
export PATH="$BUN_INSTALL/bin:$PATH"
bun --version

git clone --depth=1 --branch "${OMP_REF:-main}" https://github.com/can1357/oh-my-pi.git "$work/oh-my-pi"
cd "$work/oh-my-pi"

# oh-my-pi currently fails on Alpine/aarch64 because libc exposes ioctl request as c_int
# for this target while pi-iso passes Linux FICLONE as u64. Keep the workaround scoped
# to this packaging job until upstream carries the cast.
python3 - <<'PY'
from pathlib import Path
p = Path('crates/pi-iso/src/linux_reflink.rs')
s = p.read_text()
s = s.replace('libc::ioctl(dst_file.as_raw_fd(), FICLONE, src_file.as_raw_fd())', 'libc::ioctl(dst_file.as_raw_fd(), FICLONE as libc::c_int, src_file.as_raw_fd())')
p.write_text(s)
PY

bun install --frozen-lockfile
bun run build || bun --cwd=packages/coding-agent run build || true

bundle="$work/bundle"
mkdir -p "$bundle/omp" "$bundle/bun/bin" "$bundle/bun/lib"
cp -a package.json bun.lock packages node_modules "$bundle/omp/"
cp "$(command -v bun)" "$bundle/bun/bin/bun"
# The official Bun Alpine binary dynamically links libstdc++ and libgcc_s.
# RikkaHub's minirootfs intentionally stays minimal, so bundle the exact musl
# runtime libraries beside Bun instead of requiring apk/network access on-device.
for lib in /usr/lib/libstdc++.so.6 /usr/lib/libgcc_s.so.1; do
  cp -L "$lib" "$bundle/bun/lib/"
done

cat > "$bundle/omp/omp" <<'EOS'
set -eu
export BUN_INSTALL="${BUN_INSTALL:-/usr/local/bun}"
export OMP_HOME="${OMP_HOME:-/usr/local/omp}"
export PATH="$BUN_INSTALL/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
export LD_LIBRARY_PATH="$BUN_INSTALL/lib:/usr/local/lib:${LD_LIBRARY_PATH:-}"
exec "$BUN_INSTALL/bin/bun" "$OMP_HOME/packages/coding-agent/src/cli.ts" "$@"
EOS
chmod +x "$bundle/omp/omp" "$bundle/bun/bin/bun"

export BUN_INSTALL="$bundle/bun"
export OMP_HOME="$bundle/omp"
export PATH="$bundle/bun/bin:$PATH"
export LD_LIBRARY_PATH="$bundle/bun/lib:${LD_LIBRARY_PATH:-}"
"$bundle/bun/bin/bun" --version
sh "$bundle/omp/omp" --version
sh "$bundle/omp/omp" --help >/work/.omp-build/omp-help.txt
head -40 /work/.omp-build/omp-help.txt

cd "$bundle"
tar -czf /work/app/src/main/assets/bun/bun-alpine-aarch64-musl.tar.gz bun
tar -czf /work/app/src/main/assets/omp/omp-alpine-aarch64-musl.tar.gz omp
