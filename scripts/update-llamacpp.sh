#!/usr/bin/env bash
#
# update-llamacpp.sh -- move the third_party/llama.cpp submodule to a given tag.
#
# llama.cpp moves fast and breaks the C API without ceremony. Bumping it is a
# deliberate, reviewable act: this script stages the new commit and prints the
# upstream log for the range so the diff in the pull request is accompanied by
# an actual list of what changed. It does not commit and it does not push.
#
# Language choice: bash. It is git submodule plumbing; identical everywhere.
#
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

SUBMODULE_PATH="third_party/llama.cpp"
UPSTREAM_URL="https://github.com/ggml-org/llama.cpp"
TAG=""
DRY_RUN=0
NO_FETCH=0
MAX_LOG_LINES=200

usage() {
    cat <<'EOF'
Usage: scripts/update-llamacpp.sh [options] <tag>

Checks out <tag> in third_party/llama.cpp and stages the submodule bump.
Tags look like b6841 (llama.cpp uses a monotonic build number, not semver).

Options:
  --dry-run       Show what would change; touch nothing.
  --no-fetch      Do not fetch from the remote first. Only useful offline.
  --log-lines <n> Cap the changelog output. Default 200.
  -h, --help      This text.

Examples:
  scripts/update-llamacpp.sh b6841
  scripts/update-llamacpp.sh --dry-run b6841

Exit status:
  0  submodule moved (or, with --dry-run, could be moved)
  2  submodule missing, tag unknown, or usage problem
EOF
}

die() {
    printf '\nERROR: %s\n' "$1" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=1; shift ;;
        --no-fetch) NO_FETCH=1; shift ;;
        --log-lines) [ $# -ge 2 ] || die "--log-lines needs a value"; MAX_LOG_LINES="$2"; shift 2 ;;
        --log-lines=*) MAX_LOG_LINES="${1#--log-lines=}"; shift ;;
        -h|--help) usage; exit 0 ;;
        -*) usage >&2; die "Unknown option: $1" ;;
        *)
            [ -z "${TAG}" ] || die "Only one tag may be given (got '${TAG}' and '$1')"
            TAG="$1"; shift ;;
    esac
done

[ -n "${TAG}" ] || { usage >&2; die "No tag given."; }

cd "${REPO_ROOT}"
git rev-parse --git-dir >/dev/null 2>&1 || die "${REPO_ROOT} is not a git repository."

# --- guard: does the submodule exist at all? -------------------------------

if [ ! -f "${REPO_ROOT}/.gitmodules" ] || ! git config --file .gitmodules --get-regexp "submodule\..*\.path" 2>/dev/null | grep -q "${SUBMODULE_PATH}"; then
    cat >&2 <<EOF

third_party/llama.cpp is not registered as a submodule yet.

That is the expected state today; it lands in a later stage. To add it (once,
deliberately, on a branch):

    git submodule add ${UPSTREAM_URL} ${SUBMODULE_PATH}
    git -C ${SUBMODULE_PATH} checkout ${TAG}
    git add .gitmodules ${SUBMODULE_PATH}
    git commit -m "build: vendor llama.cpp ${TAG} as a submodule"

Then note the licence: llama.cpp is MIT, "Copyright (c) 2023-2024 The ggml
authors", and that attribution has to appear in the app's licence screen and in
the repository's NOTICE.

EOF
    exit 2
fi

if [ ! -d "${REPO_ROOT}/${SUBMODULE_PATH}/.git" ] && [ ! -f "${REPO_ROOT}/${SUBMODULE_PATH}/.git" ]; then
    die "$(printf '%s is registered but not checked out.\n\n    git submodule update --init --depth 1 %s' "${SUBMODULE_PATH}" "${SUBMODULE_PATH}")"
fi

# --- resolve ---------------------------------------------------------------

OLD_COMMIT="$(git -C "${SUBMODULE_PATH}" rev-parse HEAD)"
OLD_DESCRIPTION="$(git -C "${SUBMODULE_PATH}" describe --tags --always 2>/dev/null || printf '%s' "${OLD_COMMIT}")"

if [ "${NO_FETCH}" -eq 0 ]; then
    printf 'Fetching %s\n' "${UPSTREAM_URL}"
    # A submodule cloned with --depth 1 has neither tags nor history, so the
    # fetch is unshallowed here. It is a one-off cost of a few hundred MB and it
    # is the only way `git log old..new` below can say anything useful.
    if [ -f "$(git -C "${SUBMODULE_PATH}" rev-parse --git-dir)/shallow" ]; then
        printf '  (unshallowing the submodule so the changelog range resolves)\n'
        git -C "${SUBMODULE_PATH}" fetch --unshallow --tags --force origin || \
            die "Fetch failed. Check network access, or pass --no-fetch if the tag is already local."
    else
        git -C "${SUBMODULE_PATH}" fetch --tags --force origin || \
            die "Fetch failed. Check network access, or pass --no-fetch if the tag is already local."
    fi
fi

if ! NEW_COMMIT="$(git -C "${SUBMODULE_PATH}" rev-parse --verify --quiet "${TAG}^{commit}")"; then
    die "$(printf "Tag '%s' does not exist in %s.\n\nRecent tags:\n%s\n\nBrowse them at %s/tags" \
        "${TAG}" "${SUBMODULE_PATH}" \
        "$(git -C "${SUBMODULE_PATH}" tag --sort=-creatordate | head -n 10 | sed 's/^/    /')" \
        "${UPSTREAM_URL}")"
fi

printf '\n'
printf 'submodule   : %s\n' "${SUBMODULE_PATH}"
printf 'current     : %s (%s)\n' "${OLD_DESCRIPTION}" "${OLD_COMMIT}"
printf 'requested   : %s (%s)\n' "${TAG}" "${NEW_COMMIT}"
printf '\n'

if [ "${OLD_COMMIT}" = "${NEW_COMMIT}" ]; then
    printf 'Already at %s. Nothing to do.\n' "${TAG}"
    exit 0
fi

# --- changelog -------------------------------------------------------------

printf '== upstream changes %s..%s ==\n\n' "${OLD_DESCRIPTION}" "${TAG}"

if git -C "${SUBMODULE_PATH}" merge-base --is-ancestor "${OLD_COMMIT}" "${NEW_COMMIT}" 2>/dev/null; then
    git -C "${SUBMODULE_PATH}" log --no-merges --oneline "${OLD_COMMIT}..${NEW_COMMIT}" \
        | head -n "${MAX_LOG_LINES}" \
        | sed 's/^/  /'
    total="$(git -C "${SUBMODULE_PATH}" rev-list --no-merges --count "${OLD_COMMIT}..${NEW_COMMIT}")"
    printf '\n  (%s commits total)\n' "${total}"
else
    printf '  %s is not an ancestor of %s -- this is a move sideways or backwards,\n' "${OLD_DESCRIPTION}" "${TAG}"
    printf '  so there is no linear log to show. Compare by hand:\n'
fi

printf '\n  %s/compare/%s...%s\n\n' "${UPSTREAM_URL}" "${OLD_COMMIT}" "${TAG}"

# Changes to the public C API are the ones that break the JNI layer.
printf '== headers touched (the JNI layer tracks these) ==\n\n'
changed_headers="$(git -C "${SUBMODULE_PATH}" diff --name-only "${OLD_COMMIT}" "${NEW_COMMIT}" -- \
    'include/*.h' 'ggml/include/*.h' 2>/dev/null || true)"
if [ -n "${changed_headers}" ]; then
    printf '%s\n' "${changed_headers}" | sed 's/^/  /'
else
    printf '  none\n'
fi
printf '\n'

if [ "${DRY_RUN}" -eq 1 ]; then
    printf -- '--dry-run: nothing was changed.\n'
    exit 0
fi

# --- move ------------------------------------------------------------------

if [ -n "$(git -C "${SUBMODULE_PATH}" status --porcelain)" ]; then
    die "$(printf '%s has local modifications. Commit, stash or discard them first:\n\n    git -C %s status' "${SUBMODULE_PATH}" "${SUBMODULE_PATH}")"
fi

git -C "${SUBMODULE_PATH}" checkout --detach "${NEW_COMMIT}"
git add -- "${SUBMODULE_PATH}"

printf 'Staged: %s -> %s\n\n' "${OLD_DESCRIPTION}" "${TAG}"

cat <<EOF
BEFORE YOU COMMIT THIS

  1. Rebuild the native code from scratch. An incremental CMake build will
     happily reuse stale objects across an upstream bump:

         scripts/build-native.sh --clean --abi arm64-v8a

  2. Re-run the JNI smoke tests. This is the whole point of the exercise --
     llama.cpp changes llama.h without a major version bump, and the failure
     mode is a link error at best and a silent ABI mismatch at worst:

         ./gradlew :core-llm:test
         ./gradlew :core-llm:connectedDebugAndroidTest   (x86_64 emulator)

  3. Re-check page alignment; a new upstream CMake flag can undo it:

         scripts/verify-16kb-alignment.sh core-llm/build/intermediates

  4. Check the licence and attribution. llama.cpp is MIT, "Copyright (c)
     2023-2024 The ggml authors". If upstream added a vendored dependency with
     a different licence, the app's licence screen and NOTICE need updating.

  5. Commit the submodule pointer on its own:

         git commit -m "build(llama.cpp): bump to ${TAG}"

Nothing here has been committed or pushed.
EOF
