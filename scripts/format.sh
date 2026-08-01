#!/usr/bin/env bash
#
# format.sh -- format everything this repository knows how to format.
#
#   Kotlin / Gradle scripts   ./gradlew spotlessApply   (ktlint 1.8.0)
#   C/C++                     clang-format              (.clang-format)
#   CMake                     gersemi                   (.gersemirc)
#
# Only Spotless is a merge gate; ./gradlew spotlessCheck is what CI runs. The
# other two are here so that the native code, when it arrives, does not become
# the one corner of the repository with no agreed style. A missing clang-format
# or gersemi is reported and skipped rather than treated as an error: not every
# contributor works on the native side.
#
# Language choice: bash. It orchestrates three command line tools and is called
# from CI (Linux) as well as by hand on Windows; a second PowerShell copy would
# be two things to keep in sync for no benefit.
#
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

CHECK_ONLY=0
SKIP_GRADLE=0

usage() {
    cat <<'EOF'
Usage: scripts/format.sh [options]

Options:
  --check         Report violations without rewriting anything.
                  Uses spotlessCheck, clang-format --dry-run -Werror and
                  gersemi --check. Exits non-zero if anything is unformatted.
  --skip-gradle   Do not run Spotless. Useful when you only touched C++.
  -h, --help      This text.

Exit status:
  0  everything formatted (or, with --check, already correct)
  1  --check found violations, or a formatter failed
  2  usage problem
EOF
}

die() {
    printf '\nERROR: %s\n' "$1" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --check) CHECK_ONLY=1; shift ;;
        --skip-gradle) SKIP_GRADLE=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; die "Unknown argument: $1" ;;
    esac
done

cd "${REPO_ROOT}"

failures=0
CPP_DIR="core-llm/src/main/cpp"

section() { printf '\n== %s ==\n' "$1"; }

# --- Kotlin ----------------------------------------------------------------

section "Spotless (Kotlin, Gradle scripts)"

if [ "${SKIP_GRADLE}" -eq 1 ]; then
    printf 'skipped (--skip-gradle)\n'
elif [ ! -f ./gradlew ]; then
    printf 'skipped: ./gradlew not found\n'
    failures=$((failures + 1))
else
    if [ "${CHECK_ONLY}" -eq 1 ]; then
        if ./gradlew --console=plain spotlessCheck; then
            printf 'clean\n'
        else
            printf 'spotlessCheck failed. Run scripts/format.sh (without --check) to fix.\n'
            failures=$((failures + 1))
        fi
    else
        ./gradlew --console=plain spotlessApply
    fi
fi

# --- C / C++ ---------------------------------------------------------------

section "clang-format (${CPP_DIR})"

find_clang_format() {
    if command -v clang-format >/dev/null 2>&1; then
        command -v clang-format
        return 0
    fi

    # The NDK ships one, which is convenient on Windows where clang-format is
    # otherwise not on PATH.
    local sdk_root ndk_version host suffix candidate
    sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "${sdk_root}" ] && [ -f "${REPO_ROOT}/local.properties" ]; then
        sdk_root="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "${REPO_ROOT}/local.properties" | head -n 1)"
        sdk_root="${sdk_root//\\:/:}"
        sdk_root="${sdk_root//\\\\/\\}"
    fi
    ndk_version="$(sed -n 's/^[[:space:]]*ndk[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "${REPO_ROOT}/gradle/libs.versions.toml" | head -n 1)"
    [ -n "${sdk_root}" ] && [ -n "${ndk_version}" ] || return 1

    case "$(uname -s)" in
        Linux*)  host="linux-x86_64";  suffix="" ;;
        Darwin*) host="darwin-x86_64"; suffix="" ;;
        *)       host="windows-x86_64"; suffix=".exe" ;;
    esac
    candidate="${sdk_root}/ndk/${ndk_version}/toolchains/llvm/prebuilt/${host}/bin/clang-format${suffix}"
    [ -x "${candidate}" ] || return 1
    printf '%s' "${candidate}"
}

if [ ! -d "${CPP_DIR}" ]; then
    printf 'skipped: %s does not exist yet (the JNI layer lands in a later stage)\n' "${CPP_DIR}"
elif ! CLANG_FORMAT="$(find_clang_format)"; then
    cat <<'EOF'
skipped: clang-format not found.

Install it if you are touching the native code:
  Windows: it ships with the NDK, or  winget install LLVM.LLVM
  Linux:   apt install clang-format
EOF
else
    cpp_files=()
    while IFS= read -r file; do
        cpp_files+=("${file}")
    done < <(find "${CPP_DIR}" -type f \( -name '*.c' -o -name '*.cc' -o -name '*.cpp' -o -name '*.h' -o -name '*.hpp' \) | sort)

    if [ "${#cpp_files[@]}" -eq 0 ]; then
        printf 'skipped: no C/C++ sources under %s\n' "${CPP_DIR}"
    else
        printf 'using %s on %s file(s)\n' "${CLANG_FORMAT}" "${#cpp_files[@]}"
        if [ "${CHECK_ONLY}" -eq 1 ]; then
            if "${CLANG_FORMAT}" --dry-run -Werror "${cpp_files[@]}"; then
                printf 'clean\n'
            else
                printf 'clang-format found violations. Run scripts/format.sh to fix.\n'
                failures=$((failures + 1))
            fi
        else
            "${CLANG_FORMAT}" -i "${cpp_files[@]}"
            printf 'formatted\n'
        fi
    fi
fi

# --- CMake -----------------------------------------------------------------

section "gersemi (CMakeLists.txt, *.cmake)"

cmake_files=()
while IFS= read -r file; do
    cmake_files+=("${file}")
done < <(git ls-files -- '*CMakeLists.txt' '*.cmake' | sort)

if [ "${#cmake_files[@]}" -eq 0 ]; then
    printf 'skipped: no tracked CMake files yet\n'
elif ! command -v gersemi >/dev/null 2>&1; then
    cat <<'EOF'
skipped: gersemi not found.

Install it if you are touching the CMake build:
  python -m pip install gersemi
EOF
else
    printf 'using %s on %s file(s)\n' "$(command -v gersemi)" "${#cmake_files[@]}"
    if [ "${CHECK_ONLY}" -eq 1 ]; then
        if gersemi --check "${cmake_files[@]}"; then
            printf 'clean\n'
        else
            printf 'gersemi found violations. Run scripts/format.sh to fix.\n'
            failures=$((failures + 1))
        fi
    else
        gersemi --in-place "${cmake_files[@]}"
        printf 'formatted\n'
    fi
fi

# --- verdict ---------------------------------------------------------------

printf '\n'
if [ "${failures}" -gt 0 ]; then
    printf 'FAILED: %s formatter(s) reported problems.\n' "${failures}" >&2
    exit 1
fi

if [ "${CHECK_ONLY}" -eq 1 ]; then
    printf 'All formatters clean.\n'
else
    printf 'Done. Review with: git diff\n'
fi
