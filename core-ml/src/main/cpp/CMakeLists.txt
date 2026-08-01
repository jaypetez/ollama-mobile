#
# libollama-ml — the whole native surface of :core-ml.
#
# NOT WIRED INTO THE BUILD TODAY. :core-ml does not apply the native convention
# plugin, so nothing invokes this file and no .so is produced. That is
# deliberate: the module must keep working in the default
# `-Pollama.nativeSource=none` build, which has no NDK, so every Kotlin caller
# has a pure-Kotlin fallback (see NativeCpuFeatures and VectorKernels). Wiring
# it up later is a build-file change, not a redesign.
#
# It links against libc and nothing else. In particular it does NOT link
# llama.cpp: capability probing and the RAG vector kernel must survive a build
# with no inference engine, and folding them into libllama.so would delete them
# exactly when they are most needed.
#
cmake_minimum_required(VERSION 3.22.1)

project(ollama-ml LANGUAGES C)

add_library(ollama-ml SHARED
    ml_jni.c
)

target_compile_options(ollama-ml PRIVATE
    -O2
    -fvisibility=hidden
    -Wall
    -Wextra
    -Werror
)

# 16 KB page-size compliance. NDK r28+ emits this by default, but the flag is
# stated explicitly so a downgrade of the NDK cannot silently produce a library
# Android 15+ refuses to load.
target_link_options(ollama-ml PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--build-id=sha1"
)

find_library(log-lib log)
target_link_libraries(ollama-ml PRIVATE ${log-lib})
