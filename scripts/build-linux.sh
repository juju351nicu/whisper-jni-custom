#!/bin/bash

# scripts/ 配下のスクリプトはプロジェクトルートからの相対パスを前提とするため、
# どこから呼ばれてもルートへ移動する。
cd "$(dirname "$0")/.." || exit 1

set -xe

TMP_DIR=tmp-build
TARGET_DIR=whisperjni-build


build_lib() {

    mkdir -p $TMP_DIR $TARGET_DIR

    # Define Vulkan as an environment variable
    VULKAN_ARG=${VULKAN:-OFF} # set through CI/CD

    # Set up MUSL environment variables
    if [[ -n "$CC" && "$CC" =~ musl ]] || [[ -n "$CXX" && "$CXX" =~ musl ]]; then
        MUSL_CFLAGS="-static-libgcc -static-libstdc++ -fPIC"
        MUSL_LDFLAGS="-L${MUSL_ROOT}/lib -lc -lm -static-libgcc -static-libstdc++"
    fi

    cmake -B build $CMAKE_ARGS \
        -D_GLIBCXX_USE_CXX11_ABI=0 \
        -DCMAKE_C_COMPILER=${CC:-gcc} \
        -DCMAKE_CXX_COMPILER=${CXX:-g++} \
        -DCMAKE_C_FLAGS="${CMAKE_CFLAGS} ${MUSL_CFLAGS}" \
        -DCMAKE_CXX_FLAGS="${MUSL_CFLAGS}" \
        -DCMAKE_SHARED_LINKER_FLAGS="${MUSL_LDFLAGS}" \
        -DCMAKE_INSTALL_PREFIX=$TMP_DIR \
        -DWHISPER_BUILD_IS_DEV=OFF \
        -DGGML_VULKAN=${VULKAN_ARG}
    cmake --build build --config Release
    cmake --install build
    mkdir -p "$TARGET_DIR"

    # copy *.so in $TMP_DIR (auto resolve soft link)
    cp -f "$TMP_DIR"/*.so "$TARGET_DIR"/
    cp -f "$TMP_DIR"/lib/*.so "$TARGET_DIR"/
    ls "$TARGET_DIR"

    # copy libc.so from musl into $TMP_DIR && rename it as `libc-musl.so` && patchelf
    if [[ -n "$MUSL_LDFLAGS" ]]; then
        cp -f "${MUSL_ROOT}/lib/libc.so" "${TARGET_DIR}/libc-musl.so"
        cp -f "${MUSL_ROOT}/lib/libgomp.so" "${TARGET_DIR}/libgomp-musl.so"
        patchelf --set-soname libc-musl.so "${TARGET_DIR}/libc-musl.so"
        patchelf --set-soname libgomp-musl.so "${TARGET_DIR}/libgomp-musl.so"

        for SO_FILE in "${TARGET_DIR}"/*.so*; do
            if [[ "$(basename "$SO_FILE")" != "libc-musl.so" ]]; then
                echo "🔧 Patching libc.so dependency for: $SO_FILE"
                patchelf --replace-needed libc.so    libc-musl.so    "$SO_FILE"
                patchelf --replace-needed libgomp.so libgomp-musl.so "$SO_FILE"
                patchelf --set-rpath '$ORIGIN' "$SO_FILE"
                patchelf --force-rpath "$SO_FILE"
                echo "✅ Patched successfully: $SO_FILE"
            fi
        done
    fi

    # clean
    rm -rf "$TMP_DIR"
}

# 既定は「どの x86_64 CPU でも動く可搬ビルド」（AVX 系を切る）。配布用にはこれが正しいが、
# 速度は AVX2 有効時の半分以下になる。速度計測（EC2 等）では NATIVE=ON を付けて
# 実行マシンの命令セットに最適化したビルドを使うこと。Windows の build-windows.ps1 は AVX2 が既定で有効。
AARCH=$(uname -m)
if [[ "${NATIVE:-OFF}" == "ON" ]]; then
    echo "[INFO] NATIVE=ON: このマシンの CPU 命令セットに最適化してビルドします（配布用ではありません）"
    CMAKE_ARGS="-DGGML_NATIVE=ON" build_lib
elif [[ "$AARCH" =~ ^(arm64|aarch64)$ ]]; then
    CMAKE_CFLAGS="-march=armv8.1-a+crc" build_lib
elif [[ "$AARCH" =~ ^(x86_64|amd64|x64)$ ]]; then
    CMAKE_ARGS="-DGGML_AVX=OFF -DGGML_AVX2=OFF -DGGML_FMA=OFF -DGGML_F16C=OFF" build_lib
fi

# analyze the resulting library
readelf -d "$TARGET_DIR"/libwhisper-jni.so | grep NEEDED