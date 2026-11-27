#!/usr/bin/env bash
set -euo pipefail
# Run in portable MSYS2. Upstream Media3 1.5.1 + FFmpeg 6.0.1; no GPL/nonfree.
native_root="${1:-/d/AndroidDev/reelix-native}"
ndk="${2:-/d/AndroidDev/Sdk/ndk/27.0.12077973}"
project="${3:-/d/Project/DeepSeek/Reelix}"
bin="$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
export PATH="$bin:$PATH"
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
    case "$abi" in
        arm64-v8a) arch=aarch64; target=aarch64-linux-android21; extra=() ;;
        armeabi-v7a) arch=arm; target=armv7a-linux-androideabi21; extra=(--extra-cflags=-march=armv7-a) ;;
        x86_64) arch=x86_64; target=x86_64-linux-android21; extra=(--disable-x86asm) ;;
        x86) arch=x86; target=i686-linux-android21; extra=(--disable-x86asm) ;;
    esac
    out="$native_root/build/$abi"
    mkdir -p "$out"
    cd "$out"
    if [[ ! -f installed/lib/libavcodec.a ]]; then
        "$native_root/ffmpeg-6.0.1/configure" \
            --prefix="$out/installed" --arch="$arch" --target-os=android \
            --cc="clang --target=$target" --cxx="clang++ --target=$target" \
            --ar=llvm-ar --ranlib=llvm-ranlib --nm=llvm-nm --strip=llvm-strip \
            --enable-cross-compile --enable-pic --enable-static --disable-shared \
            --disable-doc --disable-programs --disable-everything --disable-autodetect \
            --disable-avdevice --disable-avformat --disable-swscale --disable-postproc \
            --disable-avfilter --disable-symver --enable-swresample \
            --enable-decoder=aac,aac_latm,ac3,eac3,dca,truehd,mlp,flac,opus,vorbis,mp3,alac,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f64le,pcm_u8,pcm_alaw,pcm_mulaw \
            "${extra[@]}"
        make -j8
        make install-libs install-headers
    fi
    cmake_bin=/d/AndroidDev/Sdk/cmake/3.22.1/bin
    "$cmake_bin/cmake.exe" -S "$(cygpath -m "$project/media3-ffmpeg/src/main/jni")" \
        -B "$(cygpath -m "$out/jni")" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$(cygpath -m "$cmake_bin/ninja.exe")" \
        -DCMAKE_TOOLCHAIN_FILE="$(cygpath -m "$ndk/build/cmake/android.toolchain.cmake")" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release -DFFMPEG_ROOT="$(cygpath -m "$out/installed")"
    "$cmake_bin/cmake.exe" --build "$(cygpath -m "$out/jni")" --parallel 8
    mkdir -p "$project/media3-ffmpeg/src/main/jniLibs/$abi"
    cp "$out/jni/libffmpegJNI.so" "$project/media3-ffmpeg/src/main/jniLibs/$abi/"
    echo "READY $abi"
done
