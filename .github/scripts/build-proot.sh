#!/bin/bash
set -euo pipefail

OUTPUT_DIR="$1"
mkdir -p "$OUTPUT_DIR"

# 1. Clona o fork do Termux (com os patches Android)
PROOT_SRC=/tmp/proot-src
if [ ! -d "$PROOT_SRC" ]; then
    git clone --depth=1 https://github.com/termux/proot.git "$PROOT_SRC"
fi
cd "$PROOT_SRC"

# 2. Toolchain do NDK
NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=26
TARGET="aarch64-linux-android${API}"
CC="$TOOLCHAIN/bin/${TARGET}-clang"
STRIP="$TOOLCHAIN/bin/llvm-strip"

# 3. Prepara dir de libs (talloc + shmem do workspace)
LIBDIR=/tmp/proot-libs
mkdir -p "$LIBDIR"
cp "$GITHUB_WORKSPACE/app/src/main/assets/libtalloc.so.2"      "$LIBDIR/"
cp "$GITHUB_WORKSPACE/app/src/main/assets/libandroid-shmem.so" "$LIBDIR/"

# 4. Compila
cd src
make clean || true
make -j"$(nproc)" \
    CC="$CC" \
    LD="$CC" \
    CFLAGS="-O2 -fPIC -D__ANDROID_API__=${API} -DANDROID" \
    LDFLAGS="-L${LIBDIR} -ltalloc -landroid-shmem" \
    proot

# 5. Copia para o output
if [ ! -f proot ]; then
    echo "ERRO: binário proot não foi gerado"
    ls -la
    exit 1
fi

cp proot "$OUTPUT_DIR/libproot.so"
"$STRIP" "$OUTPUT_DIR/libproot.so" || true
ls -la "$OUTPUT_DIR/libproot.so"
echo "proot compilado com sucesso"
