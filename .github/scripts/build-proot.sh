#!/bin/bash
set -euo pipefail

OUTPUT_DIR="$1"
mkdir -p "$OUTPUT_DIR"

ANDROID_HEADERS="$GITHUB_WORKSPACE/third_party/android-headers"
if [ ! -f "$ANDROID_HEADERS/talloc.h" ] || [ ! -f "$ANDROID_HEADERS/sys/shm.h" ]; then
    echo "ERRO: headers Android faltando"
    exit 1
fi

PROOT_SRC=/tmp/proot-src
if [ ! -d "$PROOT_SRC" ]; then
    git clone --depth=1 https://github.com/termux/proot.git "$PROOT_SRC"
fi
cd "$PROOT_SRC"

# --- Patch de includes (NDK 27 exige string.h explícito) ---
for f in $(grep -rl "strcmp\|memset\|strcpy\|strlen" src/extension/ashmem_memfd/ 2>/dev/null); do
    if ! grep -q "#include <string.h>" "$f"; then
        echo "Patching $f"
        sed -i '1i #include <string.h>' "$f"
    fi
done

for f in $(grep -rl "\bstrcmp\|\bmemset\|\bstrcpy\|\bstrlen\b" src/extension/kompat/ 2>/dev/null); do
    if ! grep -q "#include <string.h>" "$f"; then
        echo "Patching $f"
        sed -i '1i #include <string.h>' "$f"
    fi
done

NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
if [ ! -d "$NDK" ]; then
    NDK=$(ls -d $ANDROID_HOME/ndk/* | tail -1)
fi
echo "NDK: $NDK"

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=26
TARGET="aarch64-linux-android${API}"
CC="$TOOLCHAIN/bin/${TARGET}-clang"
STRIP="$TOOLCHAIN/bin/llvm-strip"

LIBDIR=/tmp/proot-libs
mkdir -p "$LIBDIR"
cp "$GITHUB_WORKSPACE/app/src/main/assets/libtalloc.so.2"      "$LIBDIR/"
cp "$GITHUB_WORKSPACE/app/src/main/assets/libandroid-shmem.so" "$LIBDIR/"
ln -sf "libtalloc.so.2" "$LIBDIR/libtalloc.so"

cd src
make clean || true

make -j"$(nproc)" \
    CC="$CC" \
    LD="$CC" \
    CFLAGS="-O2 -fPIC -include string.h -I$ANDROID_HEADERS" \
    LDFLAGS="-L${LIBDIR} -ltalloc -landroid-shmem" \
    proot

if [ ! -f proot ]; then
    echo "ERRO: binário proot não foi gerado"
    ls -la
    exit 1
fi

cp proot "$OUTPUT_DIR/libproot.so"
"$STRIP" "$OUTPUT_DIR/libproot.so" || true
ls -la "$OUTPUT_DIR/libproot.so"
echo "proot compilado com sucesso"
