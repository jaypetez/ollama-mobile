#!/usr/bin/env bash
#
# bench.sh -- run the on-device benchmark harness and collect the result JSON.
#
# STATUS: the :benchmark module exists but contains no benchmark classes yet, so
# this script currently stops at the guard below with an explanation. It is
# written out in full so that the day the harness lands, the invocation and the
# result-collection story are already agreed rather than improvised.
#
# Language choice: bash. It is adb plumbing plus a Gradle call, and it must work
# the same from Git Bash on the Windows dev machine and from a Linux runner
# driving an emulator.
#
# Honest scope note: there is no physical arm64 device in this project and none
# is planned. Everything this script can measure today is x86_64 emulator
# behaviour, which is useful for catching regressions in allocation counts and
# startup work but says nothing about real inference throughput. Do not quote
# numbers produced here as device performance.
#
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

SERIAL=""
OUTPUT_DIR="${REPO_ROOT}/benchmark-results"
ALLOW_MISSING=0
GRADLE_EXTRA=()

usage() {
    cat <<'EOF'
Usage: scripts/bench.sh [options] [-- <extra gradle args>]

Runs the :benchmark macrobenchmark module against a connected device or
emulator and copies the result JSON into benchmark-results/ (gitignored).

Options:
  -s, --serial <id>   adb device serial. Required when more than one device is
                      attached. Default: the only attached device.
  -o, --output <dir>  Where to copy result JSON. Default benchmark-results/
  --allow-missing     Exit 0 instead of 2 when the harness does not exist yet.
                      For wiring this into a workflow before the harness lands.
  -h, --help          This text.

Exit status:
  0  benchmarks ran and results were collected
  2  the harness does not exist yet, or no usable device, or adb missing
  other: whatever Gradle returned
EOF
}

die() {
    printf '\nERROR: %s\n' "$1" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        -s|--serial) [ $# -ge 2 ] || die "--serial needs a value"; SERIAL="$2"; shift 2 ;;
        --serial=*)  SERIAL="${1#--serial=}"; shift ;;
        -o|--output) [ $# -ge 2 ] || die "--output needs a value"; OUTPUT_DIR="$2"; shift 2 ;;
        --output=*)  OUTPUT_DIR="${1#--output=}"; shift ;;
        --allow-missing) ALLOW_MISSING=1; shift ;;
        -h|--help) usage; exit 0 ;;
        --) shift; while [ $# -gt 0 ]; do GRADLE_EXTRA+=("$1"); shift; done ;;
        *) usage >&2; die "Unknown argument: $1" ;;
    esac
done

# --- guard: does the harness exist? ----------------------------------------

harness_sources=0
if [ -d "${REPO_ROOT}/benchmark/src" ]; then
    # com.android.test modules keep their benchmark classes in src/main.
    first_source="$(find "${REPO_ROOT}/benchmark/src" -type f \( -name '*.kt' -o -name '*.java' \) -print -quit)"
    if [ -n "${first_source}" ]; then
        harness_sources=1
    fi
fi

if [ "${harness_sources}" -eq 0 ]; then
    cat >&2 <<'EOF'
The benchmark harness does not exist yet.

:benchmark is configured (androidx.benchmark macrobenchmark, `benchmark` build
type, targetProjectPath = ":app") but has no benchmark classes under
benchmark/src/main. There is nothing for this script to run.

When the harness lands, this script will:
  1. check exactly one device/emulator is attached
  2. run ./gradlew :benchmark:connectedBenchmarkAndroidTest
  3. copy the androidx.benchmark JSON out of
     benchmark/build/outputs/ into benchmark-results/

Nothing was executed and nothing was changed.
EOF
    if [ "${ALLOW_MISSING}" -eq 1 ]; then
        exit 0
    fi
    exit 2
fi

# --- device ----------------------------------------------------------------

ADB="adb"
if ! command -v adb >/dev/null 2>&1; then
    sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "${sdk_root}" ] && [ -f "${REPO_ROOT}/local.properties" ]; then
        sdk_root="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "${REPO_ROOT}/local.properties" | head -n 1)"
        sdk_root="${sdk_root//\\:/:}"
        sdk_root="${sdk_root//\\\\/\\}"
    fi
    for candidate in "${sdk_root}/platform-tools/adb" "${sdk_root}/platform-tools/adb.exe"; do
        if [ -x "${candidate}" ]; then ADB="${candidate}"; break; fi
    done
fi

if ! "${ADB}" version >/dev/null 2>&1; then
    die "adb not found. Install the platform-tools SDK package (sdkmanager \"platform-tools\") and put it on PATH."
fi

devices="$("${ADB}" devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
device_count="$(printf '%s\n' "${devices}" | grep -c . || true)"

if [ -z "${SERIAL}" ]; then
    if [ "${device_count}" -eq 0 ]; then
        die "$(printf 'No device or emulator is attached.\n\nStart one:\n    emulator -list-avds\n    emulator -avd <name>\n\nThen re-run. Note that only x86_64 emulators are available to this project.')"
    fi
    if [ "${device_count}" -gt 1 ]; then
        die "$(printf 'More than one device attached:\n%s\n\nPick one with --serial <id>.' "${devices}")"
    fi
    SERIAL="${devices}"
fi

if ! printf '%s\n' "${devices}" | grep -qx -- "${SERIAL}"; then
    die "$(printf "Serial '%s' is not in adb devices:\n%s" "${SERIAL}" "${devices}")"
fi

printf 'device      : %s\n' "${SERIAL}"
printf 'output      : %s\n\n' "${OUTPUT_DIR}"

# --- run -------------------------------------------------------------------

cd "${REPO_ROOT}"
mkdir -p "${OUTPUT_DIR}"

# connectedBenchmarkAndroidTest is AGP's generated connected-test task for the
# `benchmark` variant of a com.android.test module. It is not a task this
# project declares; confirm it with `./gradlew :benchmark:tasks` if AGP ever
# renames it.
export ANDROID_SERIAL="${SERIAL}"

if [ "${#GRADLE_EXTRA[@]}" -gt 0 ]; then
    ./gradlew :benchmark:connectedBenchmarkAndroidTest "${GRADLE_EXTRA[@]}"
else
    ./gradlew :benchmark:connectedBenchmarkAndroidTest
fi

# --- collect ---------------------------------------------------------------

printf '\n== collecting results ==\n'

collected=0
if [ -d "${REPO_ROOT}/benchmark/build/outputs" ]; then
    while IFS= read -r json; do
        cp -f "${json}" "${OUTPUT_DIR}/"
        printf '  %s\n' "$(basename "${json}")"
        collected=$((collected + 1))
    done < <(find "${REPO_ROOT}/benchmark/build/outputs" -type f -name '*.json' | sort)
fi

if [ "${collected}" -eq 0 ]; then
    # Fallback: androidx.benchmark writes to the app's media directory on the
    # device when Gradle's additionalTestOutputDir plumbing is not in play.
    printf '  nothing under benchmark/build/outputs; trying the device media directory\n'
    remote_dir="/storage/emulated/0/Android/media/io.github.jaypetez.ollamamobile.benchmark"
    if "${ADB}" -s "${SERIAL}" shell "test -d ${remote_dir}" >/dev/null 2>&1; then
        "${ADB}" -s "${SERIAL}" pull "${remote_dir}" "${OUTPUT_DIR}" >/dev/null
        collected="$(find "${OUTPUT_DIR}" -type f -name '*.json' | wc -l | tr -d '[:space:]')"
    fi
fi

if [ "${collected}" -eq 0 ]; then
    printf '\nERROR: the benchmarks ran but no result JSON was found.\n' >&2
    printf 'Look in benchmark/build/outputs/ and in %s on the device.\n' "${remote_dir:-<device media dir>}" >&2
    exit 1
fi

printf '\n%s result file(s) in %s\n' "${collected}" "${OUTPUT_DIR#${REPO_ROOT}/}"
printf 'Emulator numbers only. Do not present these as device performance.\n'
