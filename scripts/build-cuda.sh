#!/bin/bash

# scripts/ 配下のスクリプトはプロジェクトルートからの相対パスを前提とするため、
# どこから呼ばれてもルートへ移動する。
cd "$(dirname "$0")/.." || exit 1

set -xe

# User env required:
# - CUDA Toolkit installed

TMP_DIR=tmp-build
TARGET_DIR=whisperjni-build

build_lib() {

    mkdir -p $TMP_DIR $TARGET_DIR


    cmake -B build $CMAKE_ARGS \
        -DCMAKE_CXX_FLAGS="-std=c++20" \
        -DCMAKE_INSTALL_PREFIX=$TMP_DIR \
        -DWHISPER_BUILD_IS_DEV=OFF \
        -DGGML_CUDA=ON
    cmake --build build --config Release
    cmake --install build
    mkdir -p "$TARGET_DIR"

    # copy *.so in $TMP_DIR (auto resolve soft link)
    cp -f "$TMP_DIR"/*.so "$TARGET_DIR"/
    cp -f "$TMP_DIR"/lib/*.so "$TARGET_DIR"/
    ls "$TARGET_DIR"

    # clean
    rm -rf "$TMP_DIR"
}

CMAKE_ARGS="-DGGML_AVX=OFF -DGGML_AVX2=OFF -DGGML_FMA=OFF -DGGML_F16C=OFF" build_lib

# analyze the resulting library
readelf -d "$TARGET_DIR"/libwhisper-jni.so | grep NEEDED