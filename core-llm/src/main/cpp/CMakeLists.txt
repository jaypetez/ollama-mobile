# Native build entry point for :core-llm.
#
# This file is deliberately thin. Everything about *how* llama.cpp is
# configured (BUILD_SHARED_LIBS, GGML_BACKEND_DL, GGML_CPU_ALL_VARIANTS,
# LLAMA_BUILD_COMMON, ...) is passed in as -D arguments by
# build-logic/convention/src/main/kotlin/AndroidNativeConventionPlugin.kt, so
# the flag set and the comment explaining each flag live in one reviewable
# place. See docs/local-inference/native-build.md.
#
# What is here and cannot be there:
#   * link options that must reach *every* .so, including llama.cpp's own
#     targets, which means they have to be set at directory scope before
#     add_subdirectory();
#   * the two upstream facts that only a CMakeLists can react to -- the ARM-only
#     KleidiAI switch and the name of the common library target.

cmake_minimum_required(VERSION 3.22)

project(ollamamobile_llm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# Link options for every target produced below
# ---------------------------------------------------------------------------
#
# add_link_options() at directory scope, placed BEFORE add_subdirectory(), is
# inherited by every target created afterwards -- ours, libllama.so, libggml.so,
# libggml-base.so and each libggml-cpu-<variant>.so. Putting these on our own
# target with target_link_options() would harden exactly one of the ~10 shared
# objects that end up in the APK, which is the same as not doing it.
#
# max-page-size/common-page-size: Android 15 introduced devices with a 16 KB
# kernel page size and a library whose ELF LOAD segments are 4 KB aligned cannot
# be mapped there at all. NDK r28+ emits 16 KB alignment by default and we are
# on 29 -- these are passed anyway because "it is the toolchain default" is not
# a property this project controls, and a future NDK bump must not be able to
# silently break loading. Verify with scripts/verify-16kb-alignment.sh.
#
# relro/now: full RELRO. The GOT is made read-only after relocation, which
# removes the classic "overwrite a GOT entry" primitive. It costs a slightly
# slower dlopen, paid once per library.
add_link_options(
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
    "-Wl,-z,relro"
    "-Wl,-z,now"
)

# NOTE: no add_compile_options() here, and specifically no -D_FORTIFY_SOURCE.
# The NDK's clang already defines _FORTIFY_SOURCE for release builds. Defining
# it again at directory scope reaches llama.cpp's ~1500 translation units as a
# -Wmacro-redefined warning apiece, and upstream turns warnings into errors as
# soon as anyone sets LLAMA_FATAL_WARNINGS -- so a flag that looks like
# hardening becomes a build break we did not cause and cannot fix. Our own
# hardening flags go on our own target, at the bottom of this file.

# ---------------------------------------------------------------------------
# KleidiAI is an ARM micro-kernel library
# ---------------------------------------------------------------------------
#
# The convention plugin passes -DGGML_CPU_KLEIDIAI=ON for every ABI because
# arm64-v8a is the ABI that ships. Debug builds additionally produce x86_64 so
# instrumentation tests can run on an emulator, and upstream's
# `if (GGML_CPU_KLEIDIAI)` block is NOT guarded by architecture: on x86_64 it
# still fetches KleidiAI and adds NEON pack kernels to the source list, which
# then fail to compile. Forcing it off for anything that is not arm64 keeps the
# emulator ABI buildable and costs nothing -- there are no KleidiAI kernels for
# x86 to lose.
if(GGML_CPU_KLEIDIAI AND NOT CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    message(STATUS "ollamamobile: GGML_CPU_KLEIDIAI forced OFF for ABI "
                   "'${CMAKE_ANDROID_ARCH_ABI}' (ARM-only library)")
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "KleidiAI is arm64-only" FORCE)
endif()

# ---------------------------------------------------------------------------
# The submodule
# ---------------------------------------------------------------------------
get_filename_component(OLLAMAMOBILE_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(LLAMA_CPP_DIR "${OLLAMAMOBILE_REPO_ROOT}/third_party/llama.cpp")

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
            "third_party/llama.cpp is missing or not initialised.\n"
            "Run:  git submodule update --init --depth 1 third_party/llama.cpp\n"
            "Looked in: ${LLAMA_CPP_DIR}")
endif()

# ggml writes its dlopen-able backend MODULE libraries to
# CMAKE_RUNTIME_OUTPUT_DIRECTORY rather than CMAKE_LIBRARY_OUTPUT_DIRECTORY
# (see ggml_add_backend_library in ggml/src/CMakeLists.txt). AGP sets the
# library directory to the per-ABI obj/ folder it packages from; if it ever
# stops setting the runtime one, every libggml-cpu-<variant>.so would be built
# into the CMake binary directory and silently left out of the AAR -- which
# reads at runtime as "no CPU backend found", not as a packaging bug.
if(NOT CMAKE_RUNTIME_OUTPUT_DIRECTORY)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
endif()

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

# ---------------------------------------------------------------------------
# The name of the common library target
# ---------------------------------------------------------------------------
#
# LLAMA_BUILD_COMMON=ON is not optional for a chat client: llama_chat_apply_
# template() in the core library handles a small hard-coded set of formats and
# is not a Jinja engine, while real GGUF files (Qwen3, Gemma 3, anything with
# tool calling) carry genuine Jinja2 in tokenizer.chat_template. Rendering those
# needs common/chat.h and the jinja implementation behind it.
#
# The target was called `common` for years and is called `llama-common` at the
# pinned tag. Probing for it -- instead of hard-coding either name -- turns the
# next upstream rename into one legible CMake error at configure time rather
# than a wall of undefined references to common_chat_templates_apply at link
# time, which is a considerably worse place to learn about it.
if(TARGET llama-common)
    set(LLAMA_COMMON_TARGET llama-common)
elseif(TARGET common)
    set(LLAMA_COMMON_TARGET common)
else()
    message(FATAL_ERROR
            "Neither a `llama-common` nor a `common` target exists after adding "
            "llama.cpp. Upstream has renamed the common library again.\n"
            "Find the new name in third_party/llama.cpp/common/CMakeLists.txt "
            "and add it to the if(TARGET ...) chain in this file.\n"
            "Do not work around this by dropping LLAMA_BUILD_COMMON: "
            "common/chat.h is what renders real Jinja chat templates.")
endif()
message(STATUS "ollamamobile: common library target is '${LLAMA_COMMON_TARGET}'")

# ---------------------------------------------------------------------------
# The JNI bridge
# ---------------------------------------------------------------------------
find_library(ANDROID_LOG_LIB log)

add_library(ollamamobile_llm SHARED jni/llama_jni.cpp)

target_link_libraries(ollamamobile_llm PRIVATE llama ${LLAMA_COMMON_TARGET} ${ANDROID_LOG_LIB})

# Hardening, on our translation unit only. See the note next to add_link_options
# above for why none of this is set at directory scope.
#
# -U_FORTIFY_SOURCE before -D_FORTIFY_SOURCE=2 is the whole trick: the NDK
# already defines the macro, and redefining it without undefining it first is a
# -Wmacro-redefined warning. Undefining is a no-op when it is not defined.
#
# -fvisibility=hidden is safe with RegisterNatives: the only symbol that has to
# be findable by the dynamic loader is JNI_OnLoad, and JNIEXPORT already marks
# it __attribute__((visibility("default"))). Nothing else in this library is
# meant to be reachable from outside it.
target_compile_options(ollamamobile_llm PRIVATE
                       -Wall
                       -Wextra
                       -Werror=return-type
                       -fstack-protector-strong
                       -fvisibility=hidden
                       -fvisibility-inlines-hidden
                       -U_FORTIFY_SOURCE
                       -D_FORTIFY_SOURCE=2)

# --no-undefined: an unresolved symbol must fail the link here, not produce a
# library that dlopens fine and aborts on the first call into the missing one.
target_link_options(ollamamobile_llm PRIVATE "-Wl,--no-undefined")
