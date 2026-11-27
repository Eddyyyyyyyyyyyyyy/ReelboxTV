# ============================================================
# QuickJS（quickjs-ng 0.9.0）静态编译 + JNI 桥
# ============================================================
cmake_minimum_required(VERSION 3.22.1)

project(quickjs_jni C)

set(QJS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/quickjs)

# 只编 5 个核心 .c —— qjs.c / quickjs-libc.c 是命令行工具用的，不需要
add_library(quickjs STATIC
    ${QJS_DIR}/quickjs.c
    ${QJS_DIR}/libregexp.c
    ${QJS_DIR}/libunicode.c
    ${QJS_DIR}/cutils.c
    ${QJS_DIR}/libbf.c
)

target_include_directories(quickjs PUBLIC ${QJS_DIR})

target_compile_options(quickjs PRIVATE
    -O2
    -fno-strict-aliasing
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-missing-field-initializers
)

# Android 上没有 <malloc.h> 的某些扩展，走标准实现
target_compile_definitions(quickjs PRIVATE
    CONFIG_VERSION=\"0.9.0\"
)

add_library(quickjs_jni SHARED quickjs_jni.c)

target_link_libraries(quickjs_jni
    quickjs
    log
)
