#!/usr/bin/env bash
#
# build-native.sh -- compile llama.cpp for one or more ABIs through Gradle.
#
# Language choice: bash. This is a thin wrapper around ./gradlew that has to run
# identically on a Windows developer machine (Git Bash) and on the Linux
# workflow_dispatch job that produces the prebuilt .so artefacts. There is
# nothing Windows-specific in it, so duplicating it in PowerShell would just be
# two things to keep in sync.
#
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

VARIANT="debug"
ABIS=""
CLEAN=0

usage() {
    cat <<'EOF'
Usage: scripts/build-native.sh [options]

Builds :core-llm with -Pollama.nativeSource=build -Pollama.requireNative=true,
which compiles llama.cpp from third_party/llama.cpp through CMake and the NDK.

Options:
  --abi <list>       Comma separated ABIs to build. Repeatable.
                     Accepted: arm64-v8a, x86_64
                     Default: whatever the variant's abiFilters say
                     (debug: arm64-v8a + x86_64, release: arm64-v8a).
  --variant <name>   debug (default) or release.
  --clean            Run :core-llm:clean and delete core-llm/.cxx first.
                     CMake caches the toolchain; after changing the NDK or a
                     CMake flag this is usually what you actually need.
  -h, --help         This text.

Examples:
  scripts/build-native.sh --abi arm64-v8a
  scripts/build-native.sh --abi arm64-v8a,x86_64 --clean
  scripts/build-native.sh --variant release --abi arm64-v8a

Notes:
  Release ships arm64-v8a only. x86_64 exists so emulator instrumentation tests
  can run on hosted CI runners; it is never part of a release.

  Verify the result with:
    scripts/verify-16kb-alignment.sh core-llm/build/intermediates
EOF
}

die() {
    printf '\nERROR: %s\n' "$1" >&2
    exit 1
}

note() { printf '%s\n' "$*"; }

catalog_version() {
    # Reads a [versions] entry out of gradle/libs.versions.toml. Keeping the
    # expected NDK version in exactly one place is worth the sed.
    local alias="$1"
    local catalog="${REPO_ROOT}/gradle/libs.versions.toml"
    [ -f "${catalog}" ] || die "Version catalogue not found at ${catalog}"
    local value
    value="$(sed -n "s/^[[:space:]]*${alias}[[:space:]]*=[[:space:]]*\"\([^\"]*\)\".*/\1/p" "${catalog}" | head -n 1)"
    [ -n "${value}" ] || die "Version alias '${alias}' is missing from ${catalog}"
    printf '%s' "${value}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --abi)
            [ $# -ge 2 ] || die "--abi needs a value"
            if [ -z "${ABIS}" ]; then ABIS="$2"; else ABIS="${ABIS},$2"; fi
            shift 2
            ;;
        --abi=*)
            value="${1#--abi=}"
            if [ -z "${ABIS}" ]; then ABIS="${value}"; else ABIS="${ABIS},${value}"; fi
            shift
            ;;
        --variant)
            [ $# -ge 2 ] || die "--variant needs a value"
            VARIANT="$2"
            shift 2
            ;;
        --variant=*)
            VARIANT="${1#--variant=}"
            shift
            ;;
        --clean)
            CLEAN=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            die "Unknown argument: $1"
            ;;
    esac
done

case "${VARIANT}" in
    debug)   GRADLE_VARIANT="Debug" ;;
    release) GRADLE_VARIANT="Release" ;;
    *) die "--variant must be 'debug' or 'release', got '${VARIANT}'" ;;
esac

if [ -n "${ABIS}" ]; then
    IFS=',' read -r -a abi_array <<<"${ABIS}"
    for abi in "${abi_array[@]}"; do
        case "${abi}" in
            arm64-v8a|x86_64) ;;
            armeabi-v7a|x86)
                die "ABI '${abi}' is not supported. minSdk is 29 and the project is 64-bit only: arm64-v8a ships, x86_64 exists for the emulator."
                ;;
            *)
                die "Unknown ABI '${abi}'. Accepted: arm64-v8a, x86_64"
                ;;
        esac
    done
fi

# --- preconditions ---------------------------------------------------------

if [ ! -f "${REPO_ROOT}/third_party/llama.cpp/CMakeLists.txt" ]; then
    die "$(cat <<'EOF'
third_party/llama.cpp is not present, so there is nothing to compile.

If the submodule exists but is not checked out:
    git submodule update --init --depth 1 third_party/llama.cpp

If the submodule has not been added to the repository yet (that is the state
today -- it lands in a later stage), this script cannot run. Until then use:
    ./gradlew assembleDebug                      (no native code at all)
    ./gradlew assembleDebug -Pollama.nativeSource=prebuilt
EOF
)"
fi

if [ ! -f "${REPO_ROOT}/core-llm/src/main/cpp/CMakeLists.txt" ]; then
    die "core-llm/src/main/cpp/CMakeLists.txt is missing. The JNI layer has not been written yet; -Pollama.nativeSource=build cannot work without it."
fi

NDK_VERSION="$(catalog_version ndk)"
CMAKE_VERSION="$(catalog_version cmake)"

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "${SDK_ROOT}" ] && [ -f "${REPO_ROOT}/local.properties" ]; then
    SDK_ROOT="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "${REPO_ROOT}/local.properties" | head -n 1)"
    # java.util.Properties escaping, e.g. C\:\\Users\\me\\Sdk
    SDK_ROOT="${SDK_ROOT//\\:/:}"
    SDK_ROOT="${SDK_ROOT//\\\\/\\}"
fi

if [ -z "${SDK_ROOT}" ]; then
    die "Cannot find the Android SDK. Set ANDROID_HOME or put sdk.dir in local.properties."
fi

if [ ! -d "${SDK_ROOT}/ndk/${NDK_VERSION}" ]; then
    die "$(printf 'NDK %s is not installed under %s/ndk/.\n\nInstall it:\n    Windows:  .\\scripts\\setup-ndk.ps1\n    Linux/CI: sdkmanager "ndk;%s" "cmake;%s"' \
        "${NDK_VERSION}" "${SDK_ROOT}" "${NDK_VERSION}" "${CMAKE_VERSION}")"
fi

if [ ! -d "${SDK_ROOT}/cmake/${CMAKE_VERSION}" ]; then
    die "$(printf 'SDK CMake %s is not installed under %s/cmake/.\n\nInstall it:\n    Windows:  .\\scripts\\setup-ndk.ps1\n    Linux/CI: sdkmanager "cmake;%s"' \
        "${CMAKE_VERSION}" "${SDK_ROOT}" "${CMAKE_VERSION}")"
fi

cd "${REPO_ROOT}"
[ -x ./gradlew ] || [ -f ./gradlew ] || die "./gradlew not found in ${REPO_ROOT}"

# --- build -----------------------------------------------------------------

GRADLE_ARGS=(
    "-Pollama.nativeSource=build"
    "-Pollama.requireNative=true"
)

if [ -n "${ABIS}" ]; then
    # There is no -Pollama.abi switch in build-logic: the ABI set is a property
    # of the build type (see AndroidApplicationConventionPlugin). AGP's
    # android.injected.build.abi is the supported way to narrow it from outside
    # -- it is the same hook Android Studio uses when deploying to one device --
    # so that is what we reuse rather than inventing a project property that the
    # build does not read.
    GRADLE_ARGS+=("-Pandroid.injected.build.abi=${ABIS}")
fi

note "repo        : ${REPO_ROOT}"
note "variant     : ${VARIANT}"
note "abis        : ${ABIS:-<from build type>}"
note "ndk         : ${NDK_VERSION}"
note "cmake       : ${CMAKE_VERSION}"
note ""

if [ "${CLEAN}" -eq 1 ]; then
    note "== clean =="
    ./gradlew :core-llm:clean "${GRADLE_ARGS[@]}"
    rm -rf "${REPO_ROOT}/core-llm/.cxx"
    note ""
fi

note "== assemble${GRADLE_VARIANT} =="
./gradlew ":core-llm:assemble${GRADLE_VARIANT}" "${GRADLE_ARGS[@]}"

# --- report ----------------------------------------------------------------

note ""
note "== produced shared libraries =="
found=0
if [ -d "${REPO_ROOT}/core-llm/build/intermediates" ]; then
    while IFS= read -r so; do
        found=1
        printf '  %s\n' "${so#${REPO_ROOT}/}"
    done < <(find "${REPO_ROOT}/core-llm/build/intermediates" -name '*.so' -type f | sort)
fi

if [ "${found}" -eq 0 ]; then
    note "  none found under core-llm/build/intermediates -- check the Gradle output above."
    exit 1
fi

note ""
note "Next: scripts/verify-16kb-alignment.sh core-llm/build/intermediates"
