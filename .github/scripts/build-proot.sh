#!/bin/bash
set -euo pipefail

OUTPUT_DIR="$1"
mkdir -p "$OUTPUT_DIR"

# 1. Headers Android (do repo, não do Ubuntu)
ANDROID_HEADERS="$GITHUB_WORKSPACE/third_party/android-headers"
if [ ! -f "$ANDROID_HEADERS/talloc.h" ]; then
    echo "ERRO: talloc.h não encontrado em $ANDROID_HEADERS"
    exit 1
fi
if [ ! -f "$ANDROID_HEADERS/sys/shm.h" ]; then
    echo "ERRO: sys/shm.h não encontrado em $ANDROID_HEADERS"
    exit 1
fi
echo "Headers Android encontrados:"
ls -la "$ANDROID_HEADERS"
ls -la "$ANDROID_HEADERS/sys"

# 2. Clona o fork do Termux
PROOT_SRC=/tmp/proot-src
if [ ! -d "$PROOT_SRC" ]; then
    git clone --depth=1 https://github.com/termux/proot.git "$PROOT_SRC"
fi
cd "$PROOT_SRC"

# 3. Toolchain do NDK
NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
if [ ! -d "$NDK" ]; then
    # fallback: usa o que existir
    NDK=$(ls -d $ANDROID_HOME/ndk/* | tail -1)
fi
echo "NDK: $NDK"

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=26
TARGET="aarch64-linux-android${API}"
CC="$TOOLCHAIN/bin/${TARGET}-clang"
STRIP="$TOOLCHAIN/bin/llvm-strip"

# 4. Libs de implementação (do Termux, já nos assets)
LIBDIR=/tmp/proot-libs
mkdir -p "$LIBDIR"
cp "$GITHUB_WORKSPACE/app/src/main/assets/libtalloc.so.2"      "$LIBDIR/"
cp "$GITHUB_WORKSPACE/app/src/main/assets/libandroid-shmem.so" "$LIBDIR/"

# Cria symlinks para o linker achar -ltalloc e -landroid-shmem
ln -sf "$LIBDIR/libtalloc.so.2"        "$LIBDIR/libtalloc.so"
ln -sf "$LIBDIR/libandroid-shmem.so"   "$LIBDIR/libandroid-shmem.so"

# 5. Compila
cd src
make clean || true

make -j"$(nproc)" \
    CC="$CC" \
    LD="$CC" \
    CFLAGS="-O2 -fPIC -I$ANDROID_HEADERS" \
    LDFLAGS="-L${LIBDIR} -ltalloc -landroid-shmem" \
    proot

# 6. Copia para output
if [ ! -f proot ]; then
    echo "ERRO: binário proot não foi gerado"
    ls -la
    exit 1
fi

cp proot "$OUTPUT_DIR/libproot.so"
"$STRIP" "$OUTPUT_DIR/libproot.so" || true
ls -la "$OUTPUT_DIR/libproot.so"
echo "proot compilado com sucesso"
