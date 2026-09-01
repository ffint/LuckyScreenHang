#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
B="$ROOT/out"
rm -rf "$B"
mkdir -p "$B/apk/lib/arm64-v8a" "$ROOT/signing"
if [ ! -f "$ROOT/signing/lucky-signing-key.pem" ] || [ ! -f "$ROOT/signing/lucky-signing-cert.pem" ]; then
  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$ROOT/signing/lucky-signing-key.pem" \
    -out "$ROOT/signing/lucky-signing-cert.pem" -days 3650 \
    -subj "/CN=Lucky Screen Hang Local Build/O=Local Build" >/dev/null 2>&1
fi
python3 "$ROOT/src/build_manifest.py" "$B/apk/AndroidManifest.xml"
python3 "$ROOT/src/build_dex.py" "$B/apk/classes.dex"
python3 "$ROOT/src/verify_dex.py" "$B/apk/classes.dex"
clang --target=aarch64-linux-android29 -Os -fPIC -ffreestanding -fno-builtin -fno-stack-protector -fvisibility=hidden -c "$ROOT/src/luckyhang_debug.c" -o "$B/luckyhang.o"
clang --target=aarch64-linux-android29 -Os -fPIC -ffreestanding -fno-builtin -fno-stack-protector -c "$ROOT/src/libc_stub.c" -o "$B/libc_stub.o"
ld.lld -shared -soname libc.so -o "$B/libc.so" "$B/libc_stub.o"
ld.lld -shared -soname libluckyhang.so -z max-page-size=16384 -z common-page-size=16384 -o "$B/apk/lib/arm64-v8a/libluckyhang.so" "$B/luckyhang.o" -L"$B" -lc
(
  cd "$B/apk"
  zip -q -9 -r "$B/LuckyScreenHangLite-unsigned.apk" AndroidManifest.xml classes.dex lib
)
python3 "$ROOT/src/sign_apk_v2.py" "$B/LuckyScreenHangLite-unsigned.apk" "$B/LuckyScreenHang-Android17-v1.3.3.apk" "$ROOT/signing/lucky-signing-key.pem" "$ROOT/signing/lucky-signing-cert.pem"
sha256sum "$B/LuckyScreenHang-Android17-v1.3.3.apk" > "$B/SHA256.txt"
echo "Built: $B/LuckyScreenHang-Android17-v1.3.3.apk"
