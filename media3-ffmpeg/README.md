# Media3 FFmpeg audio extension for Reelix

The Java audio renderer/decoder and JNI source are from AndroidX Media **1.5.1**,
under Apache-2.0. The experimental video renderer is deliberately excluded.
FFmpeg **6.0.1**, LGPL-2.1-or-later, is compiled without GPL/nonfree components.
Exo enables the extension as a fallback (`EXTENSION_RENDERER_MODE_ON`).

Source and reproduction:

- https://github.com/androidx/media/tree/1.5.1/libraries/decoder_ffmpeg
- https://ffmpeg.org/releases/ffmpeg-6.0.1.tar.xz
- https://github.com/msys2/msys2-installer/releases

On this Windows checkout, portable MSYS2 is at
`<ANDROID_SDK>\reelix-native\msys64` with `make` and `diffutils` installed via
`pacman -Sy --noconfirm make diffutils`. Extract FFmpeg into the sibling
`ffmpeg-6.0.1` directory. Run `pwsh -File scripts/build-ffmpeg.ps1` from the project.
This uses the existing NDK 27.0.12077973 and CMake 3.22.1, API 21, builds four
ABIs, statically links the C++ runtime, and aligns the JNI library to 16 KB pages.
The script records the full decoder configuration; it builds only audio decoders.
The generated libraries are in `src/main/jniLibs/`. Release APKs filter to ARM.

For redistribution, retain these notices, the corresponding FFmpeg sources,
build scripts and relinkable FFmpeg archives/build objects. The current build
objects and installed archives are under `NativeRoot/build/<ABI>/`.
Source hashes are recorded in `SOURCE-SHA256.txt`.

The second playback engine is the official Maven artifact
`org.videolan.android:libvlc-all:3.7.0` (LGPL-2.1), selected because it works with
this project's Kotlin 2.0/compileSdk 35 toolchain. 3.7.7 requires Kotlin 2.2.10
and compileSdk 36; this change does not upgrade that toolchain.
Sources: https://code.videolan.org/videolan/libvlcjni and the matching Maven sources JAR.
