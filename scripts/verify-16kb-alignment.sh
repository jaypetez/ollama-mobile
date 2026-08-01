#!/usr/bin/env bash
#
# verify-16kb-alignment.sh -- assert that every ELF LOAD segment in every
# shared library is aligned to at least 16 KB.
#
# WHAT THIS ACTUALLY CHECKS
# -------------------------
# Android 15 introduced devices with a 16 KB memory page size. A shared library
# can only be mapped on such a device if its ELF LOAD segments are aligned to a
# multiple of the page size, which in practice means building with
# -Wl,-z,max-page-size=16384 (the NDK does this by default from r28 onwards).
# That is a property of the .so file itself, which is what this script reads out
# of the program headers.
#
# It is NOT the same thing as how the .so is *stored inside the APK*. Zip
# alignment (zipalign -p 4/16, uncompressed native libraries,
# useLegacyPackaging=false) affects whether the loader can mmap the library
# straight out of the APK; it says nothing about page-size compatibility. This
# project deliberately sets useLegacyPackaging = true so ggml's backend loader
# can enumerate CPU variants in nativeLibraryDir, and that choice does not
# weaken 16 KB compliance -- the check below is the one that matters.
#
# Language choice: bash. It wraps llvm-readelf from the NDK, is the thing a
# release workflow will call on Linux, and needs to be identical in both places.
#
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

REQUIRED_ALIGN=16384
READELF_OVERRIDE=""
TARGET=""

usage() {
    cat <<'EOF'
Usage: scripts/verify-16kb-alignment.sh [options] <target>

<target> is one of:
  * a .so file
  * a directory searched recursively for *.so
  * an .apk or .aab, whose lib/<abi>/*.so entries are extracted and checked

Options:
  --readelf <path>   Use this llvm-readelf (or GNU readelf) instead of
                     autodetecting one from the NDK.
  --align <bytes>    Required alignment. Default 16384. Use 65536 to check
                     against the strictest page size seen in the wild.
  -h, --help         This text.

Exit status:
  0  every LOAD segment of every library is aligned
  1  at least one library is misaligned (each offender is listed)
  2  usage or tooling problem

Examples:
  scripts/verify-16kb-alignment.sh core-llm/build/intermediates
  scripts/verify-16kb-alignment.sh app/build/outputs/apk/debug/app-debug.apk
  scripts/verify-16kb-alignment.sh core-llm/prebuilt/arm64-v8a/libllama.so
EOF
}

die() {
    printf '\nERROR: %s\n' "$1" >&2
    exit 2
}

catalog_version() {
    local alias="$1"
    local catalog="${REPO_ROOT}/gradle/libs.versions.toml"
    [ -f "${catalog}" ] || return 1
    sed -n "s/^[[:space:]]*${alias}[[:space:]]*=[[:space:]]*\"\([^\"]*\)\".*/\1/p" "${catalog}" | head -n 1
}

while [ $# -gt 0 ]; do
    case "$1" in
        --readelf)
            [ $# -ge 2 ] || die "--readelf needs a value"
            READELF_OVERRIDE="$2"; shift 2 ;;
        --readelf=*)
            READELF_OVERRIDE="${1#--readelf=}"; shift ;;
        --align)
            [ $# -ge 2 ] || die "--align needs a value"
            REQUIRED_ALIGN="$2"; shift 2 ;;
        --align=*)
            REQUIRED_ALIGN="${1#--align=}"; shift ;;
        -h|--help)
            usage; exit 0 ;;
        -*)
            usage >&2; die "Unknown option: $1" ;;
        *)
            [ -z "${TARGET}" ] || die "Only one target may be given (got '${TARGET}' and '$1')"
            TARGET="$1"; shift ;;
    esac
done

[ -n "${TARGET}" ] || { usage >&2; die "No target given."; }
[ -e "${TARGET}" ] || die "Target does not exist: ${TARGET}"

case "${REQUIRED_ALIGN}" in
    ''|*[!0-9]*) die "--align must be a positive integer, got '${REQUIRED_ALIGN}'" ;;
esac

# --- locate a readelf ------------------------------------------------------

ndk_host_tag() {
    case "$(uname -s)" in
        Linux*)                     printf 'linux-x86_64' ;;
        Darwin*)                    printf 'darwin-x86_64' ;;
        MINGW*|MSYS*|CYGWIN*|Windows_NT) printf 'windows-x86_64' ;;
        *)                          printf 'linux-x86_64' ;;
    esac
}

exe_suffix() {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*|Windows_NT) printf '.exe' ;;
        *) printf '' ;;
    esac
}

find_readelf() {
    if [ -n "${READELF_OVERRIDE}" ]; then
        command -v "${READELF_OVERRIDE}" >/dev/null 2>&1 || [ -x "${READELF_OVERRIDE}" ] \
            || die "--readelf ${READELF_OVERRIDE} is not executable"
        printf '%s' "${READELF_OVERRIDE}"
        return
    fi

    local suffix ndk_roots root candidate
    suffix="$(exe_suffix)"

    ndk_roots=()
    if [ -n "${ANDROID_NDK_HOME:-}" ]; then ndk_roots+=("${ANDROID_NDK_HOME}"); fi
    if [ -n "${ANDROID_NDK_ROOT:-}" ]; then ndk_roots+=("${ANDROID_NDK_ROOT}"); fi

    local sdk_root ndk_version
    sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "${sdk_root}" ] && [ -f "${REPO_ROOT}/local.properties" ]; then
        sdk_root="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "${REPO_ROOT}/local.properties" | head -n 1)"
        sdk_root="${sdk_root//\\:/:}"
        sdk_root="${sdk_root//\\\\/\\}"
    fi
    ndk_version="$(catalog_version ndk || true)"
    if [ -n "${sdk_root}" ] && [ -n "${ndk_version}" ]; then
        ndk_roots+=("${sdk_root}/ndk/${ndk_version}")
    fi

    for root in "${ndk_roots[@]:-}"; do
        [ -n "${root}" ] || continue
        candidate="${root}/toolchains/llvm/prebuilt/$(ndk_host_tag)/bin/llvm-readelf${suffix}"
        if [ -x "${candidate}" ]; then
            printf '%s' "${candidate}"
            return
        fi
    done

    # llvm-readelf and GNU readelf print the same LOAD lines under -l -W.
    local fallback
    for fallback in llvm-readelf readelf; do
        if command -v "${fallback}" >/dev/null 2>&1; then
            printf '%s' "${fallback}"
            return
        fi
    done

    die "$(cat <<'EOF'
No llvm-readelf found.

Looked at $ANDROID_NDK_HOME, $ANDROID_NDK_ROOT, $ANDROID_HOME/ndk/<version from
gradle/libs.versions.toml>, and then llvm-readelf / readelf on PATH.

Install the NDK:
    Windows:  .\scripts\setup-ndk.ps1
    Linux/CI: sdkmanager "ndk;<version>"

Or point at a binary yourself:
    scripts/verify-16kb-alignment.sh --readelf /usr/bin/llvm-readelf <target>
EOF
)"
}

READELF="$(find_readelf)"

# --- gather the libraries --------------------------------------------------

WORK_DIR=""
cleanup() {
    if [ -n "${WORK_DIR}" ] && [ -d "${WORK_DIR}" ]; then
        rm -rf "${WORK_DIR}"
    fi
    return 0
}
trap cleanup EXIT

extract_archive() {
    local archive="$1" destination="$2"
    if command -v unzip >/dev/null 2>&1; then
        # Quiet, overwrite, only native libraries.
        unzip -qq -o "${archive}" 'lib/*/*.so' -d "${destination}" 2>/dev/null || true
        # AAB stores them under base/lib/<abi>/.
        unzip -qq -o "${archive}" 'base/lib/*/*.so' -d "${destination}" 2>/dev/null || true
    elif command -v python3 >/dev/null 2>&1; then
        python3 -m zipfile -e "${archive}" "${destination}"
    elif command -v python >/dev/null 2>&1; then
        python -m zipfile -e "${archive}" "${destination}"
    else
        die "Need 'unzip' or python to open ${archive}. Install one, or point this script at a directory of .so files instead."
    fi
}

LIBRARIES=()

if [ -d "${TARGET}" ]; then
    while IFS= read -r found; do
        LIBRARIES+=("${found}")
    done < <(find "${TARGET}" -type f -name '*.so' | sort)
else
    case "${TARGET}" in
        *.apk|*.aab|*.zip)
            WORK_DIR="$(mktemp -d)"
            printf 'Extracting native libraries from %s\n' "${TARGET}"
            extract_archive "${TARGET}" "${WORK_DIR}"
            while IFS= read -r found; do
                LIBRARIES+=("${found}")
            done < <(find "${WORK_DIR}" -type f -name '*.so' | sort)
            ;;
        *.so)
            LIBRARIES+=("${TARGET}")
            ;;
        *)
            die "Do not know how to handle '${TARGET}'. Give a .so, a directory, or an .apk/.aab."
            ;;
    esac
fi

if [ "${#LIBRARIES[@]}" -eq 0 ]; then
    die "$(printf 'No .so files found in %s.\n\nIf this was an APK built with -Pollama.nativeSource=none that is expected:\nthat build contains no native code at all. Build with -Pollama.nativeSource=build\nor prebuilt first.' "${TARGET}")"
fi

# --- check -----------------------------------------------------------------

printf 'readelf     : %s\n' "${READELF}"
printf 'required    : %s bytes (0x%x)\n' "${REQUIRED_ALIGN}" "${REQUIRED_ALIGN}"
printf 'libraries   : %s\n\n' "${#LIBRARIES[@]}"

failures=0
checked=0

for library in "${LIBRARIES[@]}"; do
    display="${library}"
    if [ -n "${WORK_DIR}" ]; then display="${display#${WORK_DIR}/}"; fi
    display="${display#${REPO_ROOT}/}"

    if ! headers="$("${READELF}" -l -W "${library}" 2>&1)"; then
        printf '[ERROR] %s\n        readelf failed: %s\n' "${display}" "${headers}"
        failures=$((failures + 1))
        continue
    fi

    # LOAD lines under -W look like:
    #   LOAD 0x000000 0x0000000000000000 0x0000000000000000 0x01d2f0 0x01d2f0 R E 0x4000
    # The last column is p_align.
    aligns="$(printf '%s\n' "${headers}" | awk '$1 == "LOAD" { print $NF }')"

    if [ -z "${aligns}" ]; then
        printf '[ERROR] %s\n        no LOAD segments found; is this really an ELF shared object?\n' "${display}"
        failures=$((failures + 1))
        continue
    fi

    worst=""
    bad=0
    while IFS= read -r align; do
        [ -n "${align}" ] || continue
        value=$((align))
        if [ -z "${worst}" ] || [ "${value}" -lt "${worst}" ]; then
            worst="${value}"
        fi
        if [ "${value}" -lt "${REQUIRED_ALIGN}" ]; then
            bad=1
        fi
    done <<<"${aligns}"

    checked=$((checked + 1))
    if [ "${bad}" -eq 1 ]; then
        printf '[FAIL]  %s\n        smallest LOAD p_align = %s (0x%x), need >= %s\n' \
            "${display}" "${worst}" "${worst}" "${REQUIRED_ALIGN}"
        failures=$((failures + 1))
    else
        printf '[ok]    %s  (p_align >= 0x%x)\n' "${display}" "${worst}"
    fi
done

printf '\n%s libraries checked, %s misaligned\n' "${checked}" "${failures}"

if [ "${failures}" -gt 0 ]; then
    cat <<EOF

FAILED: the libraries listed above cannot be loaded on an Android 15+ device
with a 16 KB page size.

Fix at the link step, not at packaging time. For a CMake target:

    target_link_options(<target> PRIVATE "-Wl,-z,max-page-size=16384")

NDK r28 and later pass this by default, so a misaligned library usually means
one of:
  * a prebuilt .so from a third party that was built with an older NDK
  * an explicit -Wl,-z,max-page-size or -Wl,--no-rosegment override somewhere
  * an old copy under core-llm/prebuilt/ that predates the NDK bump

Rebuild it with:
    scripts/build-native.sh --clean --abi arm64-v8a
EOF
    exit 1
fi

printf 'PASSED\n'
exit 0
